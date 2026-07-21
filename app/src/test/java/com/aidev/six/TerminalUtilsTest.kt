package com.aidev.six

import com.aidev.six.terminal.KeyAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalUtils 纯函数单元测试。
 * 覆盖：decodeKeyInput / encodeKeyInput / parseKeyAliases / shellEscape。
 */
class TerminalUtilsTest {

    // ── decodeKeyInput ────────────────────────────────────────────

    @Test
    fun `decodeKeyInput - 普通字符串不变`() {
        assertEquals("hello", decodeKeyInput("hello"))
        assertEquals("c", decodeKeyInput("c"))
        assertEquals("", decodeKeyInput(""))
    }

    @Test
    fun `decodeKeyInput - 转义换行符`() {
        assertEquals("\n", decodeKeyInput("\\n"))
        assertEquals("clear\n", decodeKeyInput("clear\\n"))
    }

    @Test
    fun `decodeKeyInput - 转义制表符`() {
        assertEquals("\t", decodeKeyInput("\\t"))
        assertEquals("cd\t/tmp", decodeKeyInput("cd\\t/tmp"))
    }

    @Test
    fun `decodeKeyInput - 转义ESC`() {
        assertEquals("\u001b", decodeKeyInput("\\e"))
        assertEquals("\u001b[A", decodeKeyInput("\\e[A"))
    }

    @Test
    fun `decodeKeyInput - 混合转义`() {
        assertEquals("ls\n\t\u001b[D", decodeKeyInput("ls\\n\\t\\e[D"))
    }

    // ── encodeKeyInput ────────────────────────────────────────────

    @Test
    fun `encodeKeyInput - 普通字符串不变`() {
        assertEquals("hello", encodeKeyInput("hello"))
        assertEquals("", encodeKeyInput(""))
    }

    @Test
    fun `encodeKeyInput - 编码换行符`() {
        assertEquals("\\n", encodeKeyInput("\n"))
    }

    @Test
    fun `encodeKeyInput - 编码制表符`() {
        assertEquals("\\t", encodeKeyInput("\t"))
    }

    @Test
    fun `encodeKeyInput - 编码ESC`() {
        assertEquals("\\e", encodeKeyInput("\u001b"))
    }

    @Test
    fun `encode与decode互逆`() {
        val raw = "ls\n\t\u001b[D"
        assertEquals(raw, decodeKeyInput(encodeKeyInput(raw)))

        val escaped = "clear\\n\\t\\e[A"
        assertEquals(escaped, encodeKeyInput(decodeKeyInput(escaped)))
    }

    // ── parseKeyAliases ───────────────────────────────────────────

    @Test
    fun `parseKeyAliases - 空字符串返回空列表`() {
        assertTrue(parseKeyAliases("").isEmpty())
    }

    @Test
    fun `parseKeyAliases - 单个别名`() {
        val result = parseKeyAliases("ll\tls -lah")
        assertEquals(1, result.size)
        assertEquals(KeyAlias("ll", "ls -lah"), result[0])
    }

    @Test
    fun `parseKeyAliases - 多个别名`() {
        val result = parseKeyAliases("ll\tls -lah\ng\tgit")
        assertEquals(2, result.size)
        assertEquals("ll", result[0].name)
        assertEquals("ls -lah", result[0].value)
        assertEquals("g", result[1].name)
        assertEquals("git", result[1].value)
    }

    @Test
    fun `parseKeyAliases - 空名称被过滤`() {
        val result = parseKeyAliases("\tempty\nvalid\tvalue")
        assertEquals(1, result.size)
        assertEquals("valid", result[0].name)
    }

    @Test
    fun `parseKeyAliases - 名称前后空白被修剪`() {
        val result = parseKeyAliases("  ll  \tls -lah")
        assertEquals("ll", result[0].name)
    }

    // ── shellEscape ───────────────────────────────────────────────

    @Test
    fun `shellEscape - 普通字符串用单引号包裹`() {
        assertEquals("'hello'", shellEscape("hello"))
        assertEquals("'/tmp/test'", shellEscape("/tmp/test"))
    }

    @Test
    fun `shellEscape - 包含单引号时正确转义`() {
        // 单引号 ' → '\'' （关闭引号、转义单引号、重新打开引号）
        assertEquals("'it'\\''s'", shellEscape("it's"))
    }

    @Test
    fun `shellEscape - 多个单引号连续`() {
        assertEquals("'a'\\''b'\\''c'", shellEscape("a'b'c"))
    }

    @Test
    fun `shellEscape - 空字符串`() {
        assertEquals("''", shellEscape(""))
    }

    @Test
    fun `shellEscape - 特殊字符不额外转义（单引号内全部保留）`() {
        assertEquals("'$PATH & * ?'", shellEscape("\$PATH & * ?"))
    }
}
