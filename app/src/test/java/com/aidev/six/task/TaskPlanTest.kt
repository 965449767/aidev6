package com.aidev.six.task

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPlanTest {

    @Test
    fun androidPlanBuildsSequentialSteps() {
        val plan = TaskPlan.fromTemplate(
            name = "Android 闭环",
            description = "构建并验证 Android 项目",
            template = TaskTemplate(
                id = "android-loop",
                name = "Android 闭环",
                description = "构建并验证 Android 项目",
                steps = listOf(
                    TaskStep("构建", "./gradlew assembleDebug"),
                    TaskStep("测试", "./gradlew test")
                )
            )
        )

        assertEquals("Android 闭环", plan.name)
        assertEquals(2, plan.steps.size)
        assertEquals("./gradlew assembleDebug", plan.steps.first().command)
        assertTrue(plan.steps.last().command.contains("test"))
    }
}
