package io.github.soonsoonyy.processdiary

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.soonsoonyy.processdiary.data.Docker
import java.util.concurrent.TimeUnit

/**
 * 실제 Docker 로 확인 (Docker 가 설치돼 있고 켜져 있을 때만; 없으면 건너뜀).
 * 컨테이너를 하나 띄워 목록·게시 포트·정지·삭제를 확인한다.
 */
class DockerLiveTest : BasePlatformTestCase() {
    fun testContainerLifecycle() {
        val cli = Docker.cli() ?: return println("docker not installed - skipped")
        val first = Docker.list()
        if (first !is Docker.Result.Ok) return println("docker not running - skipped: $first")
        if (System.getProperty("os.name").lowercase().contains("win")) return println("windows runner uses windows containers - skipped")
        val name = "pd-test-" + System.nanoTime()
        // 이미 받아 둔 작은 이미지가 있으면 사용 (Docker Hub 받기 횟수 제한 피하기)
        val image = listOf("alpine:3", "busybox:latest", "redis:7-alpine").firstOrNull {
            ProcessBuilder(cli, "image", "inspect", it).start().waitFor() == 0
        } ?: "alpine:3"
        val run = ProcessBuilder(cli, "run", "-d", "--name", name, "-p", "127.0.0.1::8080", image, "sleep", "120")
            .redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        if (!run.waitFor(180, TimeUnit.SECONDS) || run.exitValue() != 0) return println("could not start test container - skipped: $out")
        try {
            val c = (Docker.list() as Docker.Result.Ok).containers.first { it.name == name }
            assertTrue(c.running)
            assertEquals(1, c.hostPorts.size)
            val port = c.hostPorts[0]
            assertEquals(listOf(name), Docker.containersOnPort(port).map { it.name })
            assertTrue("stats", Docker.stats().containsKey(c.id))

            assertNull(Docker.command("stop", c.id))
            assertTrue(Docker.containersOnPort(port).isEmpty())
            val stopped = (Docker.list(all = true) as Docker.Result.Ok).containers.first { it.name == name }
            assertFalse(stopped.running)
            assertNull(Docker.command("rm", c.id))
            assertTrue((Docker.list(all = true) as Docker.Result.Ok).containers.none { it.name == name })
            assertNotNull("error text for unknown container", Docker.command("stop", "no-such-container-$name"))
        } finally {
            ProcessBuilder(cli, "rm", "-f", name).start().waitFor(30, TimeUnit.SECONDS)
        }
    }
}
