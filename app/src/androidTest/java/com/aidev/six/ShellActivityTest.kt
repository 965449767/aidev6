package com.aidev.six

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6-04: ShellActivity 生命周期与资源释放回归测试（仪表化，需在真机/模拟器运行）。
 *
 * Robolectric 在本环境的 Aliyun central 镜像无法拉取（DNS/连接重置），故改用 instrumented 测试。
 * 验证：
 *  - onCreate → RESUMED 正常完成，onDestroy 资源释放不抛异常
 *  - switchTo 标签页边界保护（越界值被忽略）
 *
 * 运行：`./gradlew :app:connectedAndroidTest --tests ShellActivityTest`
 */
@RunWith(AndroidJUnit4::class)
class ShellActivityTest {

    @Test
    fun onCreate_and_onDestroy_lifecycleRunsWithoutCrash() {
        val scenario = ActivityScenario.launch(ShellActivity::class.java)
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
    }

    @Test
    fun switchTo_enforcesTabBounds() {
        val scenario = ActivityScenario.launch(ShellActivity::class.java)
        scenario.onActivity { act ->
            act.switchTo(ShellActivity.TAB_SERVER)
            val current = currentTab(act)
            assertEquals(ShellActivity.TAB_SERVER, current)

            // 越界值应被忽略，保持当前页
            act.switchTo(99)
            assertEquals(ShellActivity.TAB_SERVER, currentTab(act))
            act.switchTo(-1)
            assertEquals(ShellActivity.TAB_SERVER, currentTab(act))
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
    }

    private fun currentTab(act: ShellActivity): Int {
        val f = ShellActivity::class.java.getDeclaredField("_currentTab")
        f.isAccessible = true
        val state = f.get(act)
        return state.javaClass.getMethod("getIntValue").invoke(state) as Int
    }

    /**
     * P1-6：启动并销毁 ShellActivity 的生命周期骨架（真机/模拟器运行 connectedAndroidTest）。
     * LeakCanary 在 debug 构建经 ContentProvider 自动安装并自动监视 Activity 泄漏，无需显式断言。
     */
    @Test
    fun launchAndFinish() {
        val scenario = ActivityScenario.launch(ShellActivity::class.java)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        scenario.close()
    }
}
