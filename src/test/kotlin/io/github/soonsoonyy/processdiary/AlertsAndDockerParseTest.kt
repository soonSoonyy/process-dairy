package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.alerts.Threshold
import io.github.soonsoonyy.processdiary.data.Docker
import junit.framework.TestCase

class AlertsAndDockerParseTest : TestCase() {
    fun testThreshold() {
        val t = Threshold(cooldownMs = 600_000)
        // 기준 미만이면 알리지 않음
        assertFalse(t.update(0, 50.0, true, 90, 60_000))
        // 넘은 직후에는 지켜보기만
        assertFalse(t.update(1_000, 95.0, true, 90, 60_000))
        assertTrue(t.watching)
        assertFalse(t.update(30_000, 96.0, true, 90, 60_000))
        // 잠깐 내려가면 처음부터 다시
        assertFalse(t.update(40_000, 80.0, true, 90, 60_000))
        assertFalse(t.update(41_000, 95.0, true, 90, 60_000))
        assertFalse(t.update(90_000, 95.0, true, 90, 60_000))
        // 60초 계속되면 한 번 알림
        assertTrue(t.update(101_000, 95.0, true, 90, 60_000))
        assertFalse(t.watching)
        assertFalse(t.update(200_000, 99.0, true, 90, 60_000))
        // 내려갔다 다시 올라와도 쿨다운(10분) 안이면 알리지 않음
        assertFalse(t.update(210_000, 50.0, true, 90, 60_000))
        assertFalse(t.update(220_000, 95.0, true, 90, 60_000))
        assertFalse(t.update(300_000, 95.0, true, 90, 60_000))
        // 쿨다운이 지나면 다시 알림
        assertTrue(t.update(710_000, 95.0, true, 90, 60_000))
        // 꺼져 있으면 알리지 않음
        val off = Threshold()
        assertFalse(off.update(0, 99.0, false, 90, 0))
        assertFalse(off.update(100_000, 99.0, false, 90, 0))
    }

    fun testDockerPorts() {
        assertEquals(listOf(6379), Docker.hostPorts("0.0.0.0:6379->6379/tcp, :::6379->6379/tcp"))
        assertEquals(listOf(5432, 8080), Docker.hostPorts("127.0.0.1:8080->80/tcp, [::]:5432->5432/tcp"))
        assertEquals(listOf(9000, 9001), Docker.hostPorts("0.0.0.0:9000-9001->9000-9001/tcp"))
        assertEquals(emptyList<Int>(), Docker.hostPorts("80/tcp"))
        assertEquals(emptyList<Int>(), Docker.hostPorts(""))
    }

    fun testDockerPs() {
        val s = "\u001f"
        val text = listOf(
            listOf("abc123", "shop-db", "postgres:16", "running", "Up 5 minutes", "0.0.0.0:5432->5432/tcp", "shop", "/home/u/shop", "db"),
            listOf("def456", "old", "redis:7", "exited", "Exited (0) 2 hours ago", "", "<no value>", "<no value>", "<no value>"),
        ).joinToString("\n") { it.joinToString(s) } + "\n"
        val list = Docker.parsePs(text)
        assertEquals(2, list.size)
        assertEquals("shop-db", list[0].name)
        assertTrue(list[0].running)
        assertEquals(listOf(5432), list[0].hostPorts)
        assertEquals("shop", list[0].composeProject)
        assertEquals("/home/u/shop", list[0].composeDir)
        assertFalse(list[1].running)
        assertEquals("", list[1].composeProject)

        val stats = Docker.parseStats("abc123${s}12.50%${s}45.1MiB / 7.6GiB\nbad line\n")
        assertEquals(12.5, stats["abc123"]!!.first, 0.001)
        assertEquals("45 MB", stats["abc123"]!!.second)
        assertEquals("1.5 GB", Docker.size("1.5GiB"))
        assertEquals("512 KB", Docker.size("512KiB"))
        assertTrue(Docker.isDockerProcess("com.docker.backend"))
        assertTrue(Docker.isDockerProcess("docker-proxy"))
        assertTrue(Docker.isDockerProcess("wslrelay.exe"))
        assertFalse(Docker.isDockerProcess("node"))
    }
}
