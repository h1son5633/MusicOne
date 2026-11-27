package com.musicone.source.netease

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val images = object : LruCache<String, Bitmap>(16 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
}
/** 网易云 CDN 支持 ?param=宽x高 服务端缩略图：列表与卡片只显示几百像素，原图常有上千像素、几百 KB，
 * 逐张下原图是首页封面加载慢的主因。按解码上限 maxSide 直接向 CDN 要同尺寸的缩略图，字节数降一个数量级。 */
private fun String.neteaseThumbnail(maxSide: Int): String {
    if (!contains("music.126.net") || contains("param=")) return this
    val separator = if (contains('?')) "&" else "?"
    return "$this${separator}param=${maxSide}y$maxSide"
}

/** 封面磁盘缓存上限 64MB，存 cacheDir（系统可回收），按最后访问时间淘汰。 */
private const val ARTWORK_DISK_LIMIT_BYTES = 64L * 1024 * 1024

/** 在途加载去重：一份 key 同时只跑一个完整加载任务（磁盘读/下载/解码全程），其余等待者共享结果。
 * 任务跑在独立作用域上，单个等待者取消（行滚出屏幕）不会取消共享任务；任务结束（含失败、取消）
 * 后在 invokeOnCompletion 里摘除登记，下一次调用会重新加载，失败不会让任何等待者一直挂起。 */
private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val artworkLock = Any()
private val artworkInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

/** 正在被读取/解码的磁盘文件名，淘汰时跳过，避免删掉正在读的文件。 */
private val artworkReading = ConcurrentHashMap.newKeySet<String>()

private fun artworkDiskDir(context: Context): File =
    File(context.cacheDir, "netease-artwork").apply { mkdirs() }

/** 磁盘文件名 = 完整内存 key（url|refreshKey|maxSide）的 SHA-256：不同图片不会误命中，
 * 同图不同尺寸/刷新代际也不会串。 */
private fun artworkDiskName(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun decodeArtwork(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
}

/** 磁盘命中：读文件解码并刷新访问时间；文件损坏（解码失败）删掉并返回 null，调用方自动回源网络。 */
private fun readArtworkDisk(dir: File, name: String, maxSide: Int): Bitmap? {
    val file = File(dir, name)
    if (!file.isFile) return null
    artworkReading.add(name)
    try {
        val bitmap = runCatching { decodeArtwork(file.readBytes(), maxSide) }.getOrNull()
        if (bitmap == null) file.delete() else file.setLastModified(System.currentTimeMillis())
        return bitmap
    } finally {
        artworkReading.remove(name)
    }
}

/** 安全写入：先写临时文件再原子改名，下载/写入中断只留下临时文件，不产生半截缓存。 */
private fun writeArtworkDisk(dir: File, name: String, bytes: ByteArray) {
    runCatching {
        val tmp = File(dir, "$name.tmp-${System.nanoTime()}")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        trimArtworkDisk(dir, protect = name)
    }
}

/** 超过上限时按最后访问时间从旧到新淘汰；正在读的文件与刚写入的文件不删。 */
private fun trimArtworkDisk(dir: File, protect: String) {
    val files = dir.listFiles()?.filter { it.isFile && !it.name.contains(".tmp-") } ?: return
    var total = files.sumOf { it.length() }
    if (total <= ARTWORK_DISK_LIMIT_BYTES) return
    for (file in files.sortedBy { it.lastModified() }) {
        if (total <= ARTWORK_DISK_LIMIT_BYTES) break
        if (file.name == protect || file.name in artworkReading) continue
        val length = file.length()
        if (file.delete()) total -= length
    }
}

private suspend fun loadArtworkBitmap(appContext: Context, url: String, refreshKey: Int?, maxSide: Int): Bitmap? {
    val key = "$url|$refreshKey|$maxSide"
    images.get(key)?.let { return it }
    val task = synchronized(artworkLock) {
        artworkInFlight[key] ?: artworkScope.async {
            runCatchingCancellable {
                val dir = artworkDiskDir(appContext)
                val name = artworkDiskName(key)
                readArtworkDisk(dir, name, maxSide)?.also { images.put(key, it) }
                    ?.let { return@runCatchingCancellable it }
                val bytes = if (url.startsWith("http")) {
                    val connection = URI.create(url.asNeteaseHttpsUrl().neteaseThumbnail(maxSide)).toURL().openConnection() as HttpURLConnection
                    try { connection.connectTimeout = 12_000; connection.readTimeout = 25_000; connection.inputStream.use { it.readBytes() } }
                    finally { connection.disconnect() }
                } else appContext.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() } ?: return@runCatchingCancellable null
                writeArtworkDisk(dir, name, bytes)
                decodeArtwork(bytes, maxSide)?.also { images.put(key, it) }
            }.getOrNull()
        }.also { deferred ->
            artworkInFlight[key] = deferred
            deferred.invokeOnCompletion {
                synchronized(artworkLock) {
                    if (artworkInFlight[key] === deferred) artworkInFlight.remove(key)
                }
            }
        }
    }
    return task.await()
}

@Composable internal fun rememberArtworkBitmap(url: String?, refreshKey: Int? = null, maxSide: Int = 640): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(null, url, refreshKey, maxSide) {
        if (url.isNullOrBlank()) return@produceState
        value = loadArtworkBitmap(context.applicationContext, url, refreshKey, maxSide)
    }
}
@Composable internal fun <T> musicMotion(responseMillis: Int = 320): FiniteAnimationSpec<T> =
    if (responseMillis <= 0 || LocalNeteaseReduceMotion.current) snap() else tween(responseMillis, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal fun musicOneUiAnnotatedString(text: String) = AnnotatedString(text)
internal object ArtworkColorSampler {
    fun sampleBitmap(bitmap: Bitmap): IntArray = IntArray(9) { index ->
        bitmap.getPixel(((index % 3 * 2 + 1) * bitmap.width / 6).coerceIn(0, bitmap.width - 1),
            ((index / 3 * 2 + 1) * bitmap.height / 6).coerceIn(0, bitmap.height - 1))
    }
}
@Composable internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable internal fun AccountAvatar(account: NeteaseAccount?, size: Dp) {
    val bitmap by rememberArtworkBitmap(account?.avatarUrl)
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "账号头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, "账号头像", Modifier.size(size * .5f))
    }
}
@Composable internal fun PlaylistSyncedArtwork(bitmap: Bitmap?, awaitingArtwork: Boolean, imageUrl: String?, start: Long, end: Long,
    mark: String, modifier: Modifier, markSize: TextUnit, shape: androidx.compose.ui.graphics.Shape, sourceKey: String) {
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(start), Color(end)))), contentAlignment = Alignment.Center) {
        Crossfade(bitmap, animationSpec = musicMotion(240), label = "网易云歌单封面") { ready ->
            if (ready != null) Image(ready.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(mark, fontSize = markSize, color = Color.White) }
        }
    }
}
