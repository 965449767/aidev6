package com.aidev.six.agent

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTaskPlanTest {

    @Test
    fun androidPlanBuildsSequentialSteps() {
        val plan = AgentTaskPlan.fromTemplate(
            name = "Android 闭环",
            description = "构建并验证 Android 项目",
            template = AgentTaskTemplate(
                id = "android-loop",
                name = "Android 闭环",
                description = "构建并验证 Android 项目",
                steps = listOf(
                    AgentTaskStep("构建", "./gradlew assembleDebug"),
                    AgentTaskStep("测试", "./gradlew test")
                )
            )
        )

        assertEquals("Android 闭环", plan.name)
        assertEquals(2, plan.steps.size)
        assertEquals("./gradlew assembleDebug", plan.steps.first().command)
        assertTrue(plan.steps.last().command.contains("test"))
    }
}
