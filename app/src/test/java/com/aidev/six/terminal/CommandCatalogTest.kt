package com.aidev.six.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogTest {

    @Test
    fun registryContainsOnlyAidevExclusiveCommands() {
        val names = CommandCatalog.allRegistryCommands().map { it.name }
        // 必须包含的代表性 AIDev 命令
        assertTrue("aidev-build-request 应在表中", "aidev-build-request" in names)
        assertTrue("task-run 应在表中", "task-run" in names)
        assertTrue("sysnotify 应在表中", "sysnotify" in names)
        assertTrue("android-sh 应在表中", "android-sh" in names)
        // 不应包含通用 Linux / Git / Shell 命令
        listOf("ls", "cd", "pwd", "clear", "grep", "find", "git", "df", "ps", "env", "whoami", "cat", "ll", "alias", "history", "help")
            .forEach { generic ->
                assertFalse("通用命令 $generic 不应出现在帮助表", generic in names)
            }
    }

    @Test
    fun filterRegistryExcludesGenericAndMarkerStubs() {
        // 模拟已部署：aidev-build-request + 一个 marker 桩 + 一个通用命令（不应出现）
        val deployed = setOf("aidev-build-request", "ubuntu", "ls")
        val result = CommandCatalog.filterRegistry(deployed).map { it.name }
        assertTrue("已部署的 aidev 命令应保留", "aidev-build-request" in result)
        assertTrue("marker 桩 ubuntu 应保留（经别名可用）", "ubuntu" in result)
        assertFalse("通用命令 ls 不在表，故结果中不应出现", "ls" in result)
    }

    @Test
    fun filterRegistryEmptyWhenNothingDeployed() {
        assertTrue(CommandCatalog.filterRegistry(emptySet()).isEmpty())
    }

    @Test
    fun everyRegistryEntryHasDescriptionAndUsage() {
        CommandCatalog.allRegistryCommands().forEach { cmd ->
            assertTrue("命令 ${cmd.name} 缺少描述", cmd.description.isNotBlank())
            assertTrue("命令 ${cmd.name} 缺少用法", cmd.usage.isNotBlank())
            assertTrue("命令 ${cmd.name} 缺少分类", cmd.category.isNotBlank())
        }
    }
}
