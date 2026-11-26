package com.musicone.source.netease

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object NeteaseCrypto {
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_IV = "0102030405060708"
    private const val WEAPI_PUBKEY = "010001"
    private const val WEAPI_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val WEAPI_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val secureRandom = SecureRandom()

    fun encryptEapi(apiPath: String, payload: String): String {
        val parsedPath = runCatching { URI.create(apiPath).path }.getOrNull().orEmpty()
        val normalizedPath = (parsedPath.ifBlank { apiPath }).replace("/eapi/", "/api/")
        val digestInput = "nobody${normalizedPath}use${payload}md5forencrypt"
        val digest = MessageDigest.getInstance("MD5").digest(digestInput.toByteArray(Charsets.UTF_8)).toHex()
        val message = "$normalizedPath-36cd479b6b5-$payload-36cd479b6b5-$digest"
        return aesEcb(message.toByteArray(Charsets.UTF_8), EAPI_KEY.toByteArray(), Cipher.ENCRYPT_MODE).toHex()
    }

    fun decryptMobileEapi(body: ByteArray): String {
        if (body.looksLikeJson()) return body.toString(Charsets.UTF_8)
        val compressed = body.gunzipIfNeeded()
        val decrypted = aesEcb(compressed, EAPI_KEY.toByteArray(), Cipher.DECRYPT_MODE, padding = false)
            .removePkcs7Padding()
            .gunzipIfNeeded()
        if (!decrypted.looksLikeJson()) throw NeteaseApiException("网易云返回了无法识别的数据")
        return decrypted.toString(Charsets.UTF_8)
    }

    /** weapi 加密（网页端通道）：两次 AES-CBC + RSA，返回 params to encSecKey。 */
    fun encryptWeapi(text: String): Pair<String, String> {
        val secret = buildString {
            repeat(16) { append(WEAPI_ALPHABET[secureRandom.nextInt(WEAPI_ALPHABET.length)]) }
        }
        val firstB64 = android.util.Base64.encodeToString(
            aesCbc(text.toByteArray(Charsets.UTF_8), WEAPI_PRESET_KEY.toByteArray(), WEAPI_IV.toByteArray()),
            android.util.Base64.NO_WRAP)
        val params = android.util.Base64.encodeToString(
            aesCbc(firstB64.toByteArray(Charsets.UTF_8), secret.toByteArray(), WEAPI_IV.toByteArray()),
            android.util.Base64.NO_WRAP)
        val rsa = java.math.BigInteger(1, secret.reversed().toByteArray(Charsets.UTF_8))
            .modPow(java.math.BigInteger(WEAPI_PUBKEY, 16), java.math.BigInteger(WEAPI_MODULUS, 16))
        return params to rsa.toString(16).padStart(256, '0')
    }

    private fun aesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun randomDeviceId(): String = ByteArray(26).also(secureRandom::nextBytes).toHex(upperCase = true)

    private fun aesEcb(data: ByteArray, key: ByteArray, mode: Int, padding: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (padding) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

}

private fun ByteArray.looksLikeJson(): Boolean {
    val first = firstOrNull { byte -> !byte.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
    return first == '{' || first == '['
}

private fun ByteArray.gunzipIfNeeded(): ByteArray {
    if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
    return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
}

private fun ByteArray.removePkcs7Padding(): ByteArray {
    if (isEmpty()) return this
    val padding = last().toInt() and 0xff
    if (padding !in 1..16 || padding > size) return this
    if (takeLast(padding).any { (it.toInt() and 0xff) != padding }) return this
    return copyOf(size - padding)
}

private fun ByteArray.toHex(upperCase: Boolean = false): String {
    val alphabet = if (upperCase) "0123456789ABCDEF" else "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
