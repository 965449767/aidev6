package com.aidev.six

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class TransferTaskTest {

    @Test
    fun `default values are set correctly`() {
        val task = TransferTask()
        assertNotNull(task.id)
        assertTrue(task.id.length <= 8)
        assertEquals("", task.remotePath)
        assertEquals("", task.localPath)
        assertEquals(TransferDirection.DOWNLOAD, task.direction)
        assertEquals(0f, task.progress)
        assertEquals(TransferStatus.PENDING, task.status)
    }

    @Test
    fun `constructor parameters are stored correctly`() {
        val task = TransferTask(
            id = "task01",
            remotePath = "/remote/file",
            localPath = "/local/file",
            direction = TransferDirection.UPLOAD,
            progress = 0.5f,
            status = TransferStatus.ACTIVE,
        )
        assertEquals("task01", task.id)
        assertEquals("/remote/file", task.remotePath)
        assertEquals("/local/file", task.localPath)
        assertEquals(TransferDirection.UPLOAD, task.direction)
        assertEquals(0.5f, task.progress)
        assertEquals(TransferStatus.ACTIVE, task.status)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = TransferTask(
            remotePath = "/a", localPath = "/b",
            direction = TransferDirection.DOWNLOAD, status = TransferStatus.PENDING,
        )
        val updated = original.copy(status = TransferStatus.ACTIVE)
        assertEquals(original.id, updated.id)
        assertEquals(original.remotePath, updated.remotePath)
        assertEquals(original.localPath, updated.localPath)
        assertEquals(original.direction, updated.direction)
        assertEquals(original.progress, updated.progress)
        assertEquals(TransferStatus.ACTIVE, updated.status)
    }

    @Test
    fun `status transitions cover all states`() {
        val pending = TransferTask(status = TransferStatus.PENDING)
        val active = pending.copy(status = TransferStatus.ACTIVE)
        val done = active.copy(status = TransferStatus.DONE)
        val failed = active.copy(status = TransferStatus.FAILED)

        assertEquals(TransferStatus.PENDING, pending.status)
        assertEquals(TransferStatus.ACTIVE, active.status)
        assertEquals(TransferStatus.DONE, done.status)
        assertEquals(TransferStatus.FAILED, failed.status)
    }

    @Test
    fun `transfer direction values are correct`() {
        assertEquals(TransferDirection.UPLOAD, TransferDirection.valueOf("UPLOAD"))
        assertEquals(TransferDirection.DOWNLOAD, TransferDirection.valueOf("DOWNLOAD"))
        assertEquals(2, TransferDirection.entries.size)
    }

    @Test
    fun `transfer status values are correct`() {
        assertEquals(TransferStatus.PENDING, TransferStatus.valueOf("PENDING"))
        assertEquals(TransferStatus.ACTIVE, TransferStatus.valueOf("ACTIVE"))
        assertEquals(TransferStatus.DONE, TransferStatus.valueOf("DONE"))
        assertEquals(TransferStatus.FAILED, TransferStatus.valueOf("FAILED"))
        assertEquals(4, TransferStatus.entries.size)
    }

    @Test
    fun `progress range is preserved across copies`() {
        val task = TransferTask(progress = 0.0f).copy(progress = 0.75f)
        assertEquals(0.75f, task.progress)
        val full = task.copy(progress = 1.0f)
        assertEquals(1.0f, full.progress)
    }

    @Test
    fun `toString contains key fields`() {
        val task = TransferTask(id = "t001", remotePath = "/r", localPath = "/l")
        val str = task.toString()
        assertTrue(str.contains("t001"))
        assertTrue(str.contains("/r"))
        assertTrue(str.contains("/l"))
        assertTrue(str.contains("direction"))
        assertTrue(str.contains("status"))
    }
}
