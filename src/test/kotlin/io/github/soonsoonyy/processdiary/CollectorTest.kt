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
            // 작업 폴더 (윈도우는 직접 읽음)
            assertTrue("cwd of self is empty", me.cwd.isNotEmpty())
            assertEquals("cwd", File(System.getProperty("user.dir")).canonicalPath.lowercase(), File(me.cwd).canonicalPath.lowercase())
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

    /** 한 번 갱신하는 데 걸리는 시간 (CI 로그에서 운영체제별로 비교) */
    fun testSampleTiming() {
        val c = Collector()
        val total = c.system().memTotal
        c.processes(total) // 처음 한 번은 준비 작업 포함
        val times = (1..3).map { val t = System.nanoTime(); val n = c.processes(total).size; Thread.sleep(300); (System.nanoTime() - t) / 1_000_000 - 300 to n }
        println("TIMING ${System.getProperty("os.name")}: processes() ${times.map { it.first }} ms for ${times.last().second} processes")
        assertTrue("one refresh should take well under the 2 s interval: $times", times.minOf { it.first } < 1500)
    }

    /** 다른 폴더에서 띄운 자식 프로세스의 작업 폴더 ("이 프로젝트" 보기, 남은 프로세스 감지에 필요) */
    fun testChildWorkingDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("pd cwd test").toFile() // 공백이 있는 경로도
        val child = ChildJava.start(dir)
        try {
            val c = Collector()
            var cwd = ""
            val end = System.currentTimeMillis() + 10_000
            while (cwd.isEmpty() && System.currentTimeMillis() < end) {
                cwd = c.quickList().firstOrNull { it.pid == child.pid().toInt() }?.cwd ?: ""
                if (cwd.isEmpty()) Thread.sleep(200)
            }
            assertEquals(dir.canonicalPath.lowercase(), File(cwd).canonicalPath.lowercase())
        } finally {
            child.destroyForcibly().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            dir.delete()
        }
    }
}
