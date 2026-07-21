package com.aidev.six.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FileUtils 纯函数单元测试。
 * 覆盖：formatSize / isImageFile / isLikelyText。
 */
class FileUtilsTest {

    // ── formatSize ────────────────────────────────────────────────

    @Test
    fun `formatSize - 字节范围`() {
        assertEquals("0B", formatSize(0))
        assertEquals("1B", formatSize(1))
        assertEquals("512B", formatSize(512))
        assertEquals("1023B", formatSize(1023))
    }

    @Test
    fun `formatSize - KB 范围`() {
        assertEquals("1.0K", formatSize(1024))
        assertEquals("1.5K", formatSize(1024 + 512))
        assertEquals("1023.0K", formatSize(1024 * 1023))
    }

    @Test
    fun `formatSize - MB 范围`() {
        assertEquals("1.0M", formatSize(1024L * 1024L))
        assertEquals("2.5M", formatSize(1024L * 1024L * 2 + 1024L * 512))
        assertEquals("1023.0M", formatSize(1024L * 1024L * 1023L))
    }

    @Test
    fun `formatSize - GB 范围`() {
        assertEquals("1.0G", formatSize(1024L * 1024L * 1024L))
        assertEquals("2.0G", formatSize(1024L * 1024L * 1024L * 2))
    }

    // ── isImageFile ───────────────────────────────────────────────

    @Test
    fun `isImageFile - 常见图片扩展名`() {
        assertTrue(isImageFile(File("photo.png")))
        assertTrue(isImageFile(File("image.jpg")))
        assertTrue(isImageFile(File("image.jpeg")))
        assertTrue(isImageFile(File("anim.webp")))
        assertTrue(isImageFile(File("pic.gif")))
        assertTrue(isImageFile(File("img.bmp")))
    }

    @Test
    fun `isImageFile - 扩展名大小写不敏感`() {
        assertTrue(isImageFile(File("photo.PNG")))
        assertTrue(isImageFile(File("image.JPG")))
        assertTrue(isImageFile(File("image.Jpeg")))
    }

    @Test
    fun `isImageFile - 非图片文件`() {
        assertFalse(isImageFile(File("doc.txt")))
        assertFalse(isImageFile(File("code.kt")))
        assertFalse(isImageFile(File("data.json")))
        assertFalse(isImageFile(File("archive.zip")))
        assertFalse(isImageFile(File("Makefile")))
    }

    @Test
    fun `isImageFile - 无扩展名`() {
        assertFalse(isImageFile(File("README")))
        assertFalse(isImageFile(File("LICENSE")))
    }

    // ── isLikelyText ──────────────────────────────────────────────

    @Test
    fun `isLikelyText - 纯文本文件`() {
        val tmp = File.createTempFile("test-text-", ".txt")
        try {
            tmp.writeText("Hello, World!\nThis is a text file.\n")
            assertTrue(isLikelyText(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `isLikelyText - 空文件视为文本`() {
        val tmp = File.createTempFile("test-empty-", ".txt")
        try {
            tmp.writeText("")
            assertTrue(isLikelyText(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `isLikelyText - 含null字节视为二进制`() {
        val tmp = File.createTempFile("test-binary-", ".bin")
        try {
            // 写入包含 null 字节的二进制内容
            tmp.outputStream().use { it.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01, 0x02, 0x03)) }
            assertFalse(isLikelyText(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `isLikelyText - 不存在的文件安全返回false`() {
        assertFalse(isLikelyText(File("/nonexistent/path/file.txt")))
    }
}
