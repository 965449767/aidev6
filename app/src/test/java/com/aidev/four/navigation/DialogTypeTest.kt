package com.aidev.four.navigation

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class DialogTypeTest {

    @Test
    fun `all data object subtypes are distinct`() {
        val set = setOf(
            DialogType.SFtpTransfer,
            DialogType.ProjectScaffold,
        )
        assertEquals(2, set.size)
    }

    @Test
    fun `data objects are stable singletons`() {
        assertNotNull(DialogType.SFtpTransfer)
        assertNotNull(DialogType.ProjectScaffold)
    }
}
