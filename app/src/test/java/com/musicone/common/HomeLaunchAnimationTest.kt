package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchAnimationTest {
    private fun gate(systemSplash: Boolean) = HomeLaunchGate().also {
        it.prepare(reduceMotion = false, systemSplash = systemSplash)
    }

    @Test
    fun timelineRunsGatherThenExit() {
        assertEquals(0f, HomeLaunchTimeline.gather(0), 0f)
        assertEquals(1f, HomeLaunchTimeline.gather(HomeLaunchTimeline.GatherMillis), 0f)
        assertEquals(0f, HomeLaunchTimeline.exit(HomeLaunchTimeline.ExitStartMillis), 0f)
        assertEquals(1f, HomeLaunchTimeline.exit(HomeLaunchTimeline.TotalMillis), 0f)
        assertEquals(580L, HomeLaunchTimeline.TotalMillis)
    }

    @Test
    fun playsOncePerProcess() {
        val gate = HomeLaunchGate()
        assertTrue(gate.prepare(reduceMotion = false, systemSplash = false))
        assertTrue(gate.takeAnimation())
        assertFalse(gate.takeAnimation())
        assertFalse(gate.prepare(reduceMotion = false, systemSplash = false))
        assertFalse(gate.takeAnimation())
    }

    @Test
    fun warmStartDoesNotWaitForSplash() {
        val gate = gate(systemSplash = true)
        gate.takeAnimation()
        assertFalse(gate.prepare(reduceMotion = false, systemSplash = true))
        assertFalse(gate.awaitingSplash)
    }

    @Test
    fun reduceMotionSkipsAnimation() {
        val gate = HomeLaunchGate()
        assertFalse(gate.prepare(reduceMotion = true, systemSplash = true))
        assertFalse(gate.takeAnimation())
        assertFalse(gate.awaitingSplash)
    }

    @Test
    fun splashThemeFollowsAlwaysDark() {
        assertEquals(0, homeLaunchSplashTheme(alwaysDark = false))
        assertEquals(R.style.Theme_MusicOne_Launch_Dark, homeLaunchSplashTheme(alwaysDark = true))
    }
}
