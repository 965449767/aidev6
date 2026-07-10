package com.aidev.six

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class AuthTypeTest {

    @Test
    fun `valueOf returns correct enum constants`() {
        assertEquals(AuthType.PASSWORD, AuthType.valueOf("PASSWORD"))
        assertEquals(AuthType.KEY, AuthType.valueOf("KEY"))
    }

    @Test
    fun `entries contains all values`() {
        assertEquals(2, AuthType.entries.size)
        assertTrue(AuthType.entries.containsAll(listOf(AuthType.PASSWORD, AuthType.KEY)))
    }

    @Test
    fun `ordinal values are sequential`() {
        assertEquals(0, AuthType.PASSWORD.ordinal)
        assertEquals(1, AuthType.KEY.ordinal)
    }

    @Test
    fun `enum constants are distinct`() {
        assertTrue(AuthType.PASSWORD != AuthType.KEY)
    }

    @Test
    fun `name returns uppercase constant name`() {
        assertEquals("PASSWORD", AuthType.PASSWORD.name)
        assertEquals("KEY", AuthType.KEY.name)
    }
}
