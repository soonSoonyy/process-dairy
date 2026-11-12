package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Collector
import junit.framework.TestCase
import java.io.File
import java.net.ServerSocket

/** 실제 OS에서 OSHI로 정보를 읽어오는지 확인 */
class CollectorTest : TestCase() {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    fun testSystemStats() {
        val c = Collector()
        c.system()
        Thread.sleep(600)
        val s = c.system()
        assertTrue("memory total", s.memTotal > 0)
        assertTrue("memory used", s.memUsed in 1..s.memTotal)
        assertTrue("cpu range", s.cpu in 0.0..100.0)
        assertTrue("disk total", s.diskTotal > 0)
        assertTrue("disk rates", s.diskRead >= 0 && s.diskWrite >= 0 && s.netRx >= 0 && s.netTx >= 0)
    }

    fun testProcessesPortsAndDetails() {
        val c = Collector()
        val self = ProcessHandle.current().pid().toInt()
        ServerSocket(0).use { server ->
            val total = c.system().memTotal
            val first = c.processes(total)
            assertTrue("process count", first.size > 3)
            Thread.sleep(700)
            val procs = c.processes(total)
            val me = procs.firstOrNull { it.pid == self }
            assertNotNull("current process is listed", me)
            me!!
            assertTrue("rss", me.rssBytes > 0)
            // 작업 폴더: Windows는 OSHI가 지원하지 않아 비어 있을 수 있음
            if (!windows || me.cwd.isNotEmpty()) {
                assertEquals("cwd", File(System.getProperty("user.dir")).canonicalPath.lowercase(), File(me.cwd).canonicalPath.lowercase())
            }
            assertTrue("listening port detected: ${me.ports}", server.localPort in me.ports)
            val d = c.details(self)
            assertNotNull(d)
            assertTrue("command line", d!!.commandLine.contains("java"))
            assertTrue("LISTEN connection", d.conns.any { it.state == "LISTEN" && it.local.endsWith(":${server.localPort}") })
            // 부모-자식 관계가 이어지는지
            assertTrue("parent exists", procs.any { it.pid == me.ppid })
            // 포트로 프로세스 찾기 (포트 충돌 알림에서 사용)
            assertTrue("listenersOnPort", self in c.listenersOnPort(server.localPort))
            assertTrue("quickList", c.quickList().any { it.pid == self })
        }
    }
}
