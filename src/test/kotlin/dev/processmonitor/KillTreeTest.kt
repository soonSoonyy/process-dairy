package dev.processmonitor

import junit.framework.TestCase
import java.util.concurrent.TimeUnit

/** 하위 프로세스까지 종료하는 기능이 실제로 프로세스 트리를 모두 끝내는지 */
class KillTreeTest : TestCase() {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }

    fun testKillTree() {
        // 부모 셸 아래 자식 2개
        val cmd = if (windows) listOf("powershell", "-NoProfile", "-Command",
            "Start-Process -NoNewWindow ping -ArgumentList '-n','300','127.0.0.1'; Start-Process -NoNewWindow ping -ArgumentList '-n','300','127.0.0.1'; Start-Sleep 300")
        else listOf("sh", "-c", "sleep 300 & sleep 300 & wait")
        val parent = ProcessBuilder(cmd).start()
        try {
            val pid = parent.pid().toInt()
            assertTrue("children started", waitUntil(10_000) { ProcessActions.descendantsOf(pid).size >= 2 })
            val kids = ProcessActions.descendantsOf(pid)

            val r = ProcessActions.killNow(listOf(pid), force = true, tree = true)
            assertTrue("failures: ${r.failed}", r.failed.isEmpty())
            assertEquals(kids.size + 1, r.done)
            assertTrue("parent ended", parent.waitFor(10, TimeUnit.SECONDS))
            assertTrue("children ended", waitUntil(10_000) { kids.none { ProcessHandle.of(it.toLong()).map { h -> h.isAlive }.orElse(false) } })
        } finally {
            parent.descendants().forEach { it.destroyForcibly() }
            parent.destroyForcibly()
        }
    }

    fun testProtectsIdeItself() {
        val self = ProcessHandle.current().pid().toInt()
        val r = ProcessActions.killNow(listOf(self), force = false, tree = false)
        assertEquals(0, r.done)
        assertTrue(r.failed.single().contains("보호된"))
    }
}
