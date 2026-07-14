package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCommandGuardTest {

    private fun isAllowed(cmd: String): Boolean =
        SafeCommandGuard.check(cmd).verdict == SafeCommandGuard.Verdict.ALLOW

    @Test
    fun allowsNormalBuildCommand() {
        assertTrue(isAllowed("aidev-build"))
        assertTrue(isAllowed("./gradlew :app:assembleDebug"))
        assertTrue(isAllowed("ls -la /workspace"))
    }

    @Test
    fun blocksDangerousPatterns() {
        assertFalse(isAllowed("rm -rf /sdcard/AIDev"))
        assertFalse(isAllowed("rm -rf ."))
        assertFalse(isAllowed("git reset --hard"))
        assertFalse(isAllowed("git push --force"))
        assertFalse(isAllowed("dd if=/dev/zero of=/dev/sda"))
        assertFalse(isAllowed("mkfs.ext4 /data/foo"))
    }

    @Test
    fun allowsBenignCopiesToSdcard() {
        // cp/mv 到 /sdcard 是正常构建产物落盘，应放行
        assertTrue(isAllowed("cp app-debug.apk /sdcard/AIDev/"))
        assertTrue(isAllowed("mv build/outputs/apk /sdcard/AIDev/apk"))
    }

    @Test
    fun requiresConfirmForDestructiveWriteToProtectedPath() {
        val res = SafeCommandGuard.check("rm /sdcard/AIDev/old.apk")
        assertEquals(SafeCommandGuard.Verdict.REQUIRE_CONFIRM, res.verdict)
        val res2 = SafeCommandGuard.check("chmod 777 /sdcard/secrets")
        assertEquals(SafeCommandGuard.Verdict.REQUIRE_CONFIRM, res2.verdict)
    }

    @Test
    fun caseInsensitiveDangerousMatch() {
        assertFalse(isAllowed("RM -RF /SDCARD/X"))
        assertFalse(isAllowed("Git Push --Force"))
    }
}
