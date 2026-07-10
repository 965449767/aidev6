package com.aidev.six

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals

@RunWith(JUnit4::class)
class UbuntuShellTest {

    @Test
    fun `ShellResult stores output correctly`() {
        val result = ShellResult("out", "err", 0)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `ShellResult with error exit code`() {
        val result = ShellResult("", "not found", 127)
        assertEquals(127, result.exitCode)
        assertEquals("not found", result.stderr)
    }
}
