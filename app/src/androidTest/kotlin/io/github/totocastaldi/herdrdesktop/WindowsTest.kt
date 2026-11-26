package io.github.totocastaldi.herdrdesktop

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every window of the app is a task of its own, so Desktop Mode shows it as a separate window (REQ-DM-001). */
@RunWith(AndroidJUnit4::class)
class WindowsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tasks get() = context.getSystemService(ActivityManager::class.java).appTasks
    private val windows get() = tasks.filter { it.taskInfo?.baseActivity?.className == MainActivity::class.java.name }

    @After
    fun closeWindows() {
        windows.forEach { it.finishAndRemoveTask() }
    }

    @Test
    fun twoWindowsRunInSeparateTasks() {
        val before = windows.size
        open(context, WindowTarget(null, null))
        open(context, WindowTarget(null, null))
        assertEquals(before + 2, waitForWindows(before + 2))
        assertTrue(windows.map { it.taskInfo?.taskId }.toSet().size >= 2)
    }

    private fun open(context: Context, target: WindowTarget) {
        context.startActivity(MainActivity.windowIntent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForWindows(expected: Int): Int {
        repeat(WAIT_STEPS) {
            if (windows.size >= expected) return windows.size
            Thread.sleep(WAIT_STEP_MILLIS)
        }
        return windows.size
    }

    private companion object {
        const val WAIT_STEPS = 50
        const val WAIT_STEP_MILLIS = 100L
    }
}
