package com.aidev.four

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class ProjectScaffoldStateTest {

    @Before
    fun setUp() {
        ProjectScaffoldState.form = ProjectForm()
        ProjectScaffoldState.onSendToTerminal = null
    }

    @Test
    fun `templates have three entries`() {
        assertEquals(3, ProjectScaffoldState.templates.size)
    }

    @Test
    fun `templates contain expected names`() {
        val names = ProjectScaffoldState.templates.map { it.name }
        assertTrue(names.contains("empty-activity"))
        assertTrue(names.contains("nav-activity"))
        assertTrue(names.contains("basic-terminal"))
    }

    @Test
    fun `default form uses empty project name`() {
        assertEquals("", ProjectScaffoldState.form.projectName)
    }

    @Test
    fun `default form has package name`() {
        assertEquals("com.example.app", ProjectScaffoldState.form.packageName)
    }

    @Test
    fun `default form uses empty-activity template`() {
        assertEquals("empty-activity", ProjectScaffoldState.form.templateName)
    }

    @Test
    fun `generateScript with blank project returns empty`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "")
        val script = ProjectScaffoldState.generateScript()
        assertEquals("", script)
    }

    @Test
    fun `generateScript with unknown template returns empty`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "Test", templateName = "nonexistent")
        val script = ProjectScaffoldState.generateScript()
        assertEquals("", script)
    }

    @Test
    fun `generateScript includes project name`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "MyApp")
        val script = ProjectScaffoldState.generateScript()
        assertTrue(script.contains("MyApp"))
    }

    @Test
    fun `generateScript includes package path`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "App", packageName = "com.test.myapp")
        val script = ProjectScaffoldState.generateScript()
        assertTrue(script.contains("com/test/myapp"))
    }

    @Test
    fun `generateScript starts with shebang`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "Demo")
        val script = ProjectScaffoldState.generateScript()
        assertTrue(script.startsWith("#!/system/bin/sh"))
    }

    @Test
    fun `generateScript is not empty for valid form`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "MyProject")
        val script = ProjectScaffoldState.generateScript()
        assertFalse(script.isBlank())
    }

    @Test
    fun `generateScript contains mkdir commands`() {
        ProjectScaffoldState.form = ProjectForm(projectName = "TestApp")
        val script = ProjectScaffoldState.generateScript()
        assertTrue(script.contains("mkdir -p"))
    }

    @Test
    fun `onSendToTerminal is initially null`() {
        assertNull(ProjectScaffoldState.onSendToTerminal)
    }
}
