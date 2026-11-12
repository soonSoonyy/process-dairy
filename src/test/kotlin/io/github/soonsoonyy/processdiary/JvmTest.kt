package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Jvm
import junit.framework.TestCase
import java.io.File

/** Java 프로세스의 메인 클래스와 힙 정보를 맥/윈도우/리눅스에서 읽는지 확인 */
class JvmTest : TestCase() {

    fun testParsing() {
        assertEquals("com.x.App", Jvm.mainFromArgs(listOf("java", "-Xmx1g", "-cp", "a:b", "-Dx=1", "com.x.App", "--port", "8080")))
        assertEquals("build/app.jar", Jvm.mainFromArgs(listOf("/usr/bin/java", "-jar", "build/app.jar")))
        assertEquals("com.x.Main", Jvm.mainFromArgs(listOf("java", "--module-path", "mods", "-m", "app/com.x.Main")))
        assertEquals("com.x.Main", Jvm.mainFromArgs(listOf("java.exe", "--module=app/com.x.Main")))
        assertNull(Jvm.mainFromArgs(listOf("java", "-version")))
        assertEquals("/path with space/app.jar", Jvm.mainFromJavaCommand("/path with space/app.jar --server.port=8080"))
        assertEquals("com.x.App", Jvm.mainFromJavaCommand("com.x.App a b"))

        assertEquals("Gradle Daemon", Jvm.describe("org.gradle.launcher.daemon.bootstrap.GradleDaemon").label)
        assertEquals("Kotlin Daemon", Jvm.describe("org.jetbrains.kotlin.daemon.KotlinCompileDaemon").label)
        assertEquals("shop-0.1.jar", Jvm.describe("build/libs/shop-0.1.jar").label)
        assertEquals("jar", Jvm.describe("C:\\apps\\shop.jar").kind)
        Jvm.describe("com.example.shop.ShopApplication").let { assertEquals("ShopApplication", it.label); assertEquals("spring", it.kind) }
        Jvm.describe("com.example.Tool").let { assertEquals("Tool", it.label); assertEquals("app", it.kind) }
        assertTrue(Jvm.isJava("java")); assertTrue(Jvm.isJava("javaw.exe")); assertFalse(Jvm.isJava("javac"))
    }

    /** 이 테스트를 돌리는 JVM 자신의 성능 정보 파일 */
    fun testOwnPerfData() {
        val pid = ProcessHandle.current().pid().toInt()
        val perf = Jvm.readPerf(pid)
        assertNotNull("perf data for the test JVM: " + Jvm.perfDiagnostics(pid), perf)
        perf!!
        assertTrue("heap used ${perf.heapUsed}", perf.heapUsed > 0)
        assertTrue("heap committed ${perf.heapCommitted}", perf.heapCommitted >= perf.heapUsed)
        assertTrue("java command: ${perf.strings}", perf.strings["sun.rt.javaCommand"].orEmpty().contains("Gradle", ignoreCase = true) ||
            perf.strings["sun.rt.javaCommand"].orEmpty().isNotEmpty())
        assertEquals(System.getProperty("java.version"), perf.strings["java.property.java.version"])
    }

    /** 따로 띄운 Java 프로그램: 메인 클래스 이름, 힙, 실행 명령으로 알아내기 */
    fun testChildJvm() {
        val proc = ChildJava.start()
        try {
            val pid = proc.pid().toInt()
            val start = ProcessHandle.of(proc.pid()).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(0L)
            var info = Jvm.info(pid, start) { emptyList() }
            val until = System.currentTimeMillis() + 15_000
            while ((info == null || info.heapUsed <= 0) && System.currentTimeMillis() < until) {
                Thread.sleep(300)
                Jvm.retain(emptySet()) // 다시 읽게
                info = Jvm.info(pid, start) { emptyList() }
            }
            assertNotNull("jvm info for child: " + Jvm.perfDiagnostics(pid), info)
            info!!
            assertEquals("SleepyMain", info.label)
            assertEquals(SleepyMain::class.java.name, info.main)
            assertTrue("child heap ${info.heapUsed}: " + Jvm.perfDiagnostics(pid), info.heapUsed > 0)

            // 성능 정보 파일 없이 실행 명령만으로도
            Jvm.retain(emptySet())
            assertEquals(SleepyMain::class.java.name, Jvm.mainFromArgs(ChildJava.args))
        } finally {
            proc.destroyForcibly()
        }
    }
}
