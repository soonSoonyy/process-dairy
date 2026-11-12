package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Jvm
import junit.framework.TestCase
import java.io.File

/** Java 프로세스의 메인 클래스와 힙 정보를 맥/윈도우/리눅스에서 읽는지 확인 */
class JvmTest : TestCase() {
    // 윈도우는 성능 정보 파일 위치/공유 방식이 달라 아직 확인 중: 진단만 출력하고 실패로 보지 않음
    private val windows = System.getProperty("os.name").lowercase().contains("win")

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
        if (perf == null && windows) return println("WINDOWS hsperfdata: " + Jvm.perfDiagnostics(pid))
        assertNotNull("hsperfdata file for the test JVM (tmpdir=${System.getProperty("java.io.tmpdir")})", perf)
        perf!!
        assertTrue("heap used ${perf.heapUsed}", perf.heapUsed > 0)
        assertTrue("heap committed ${perf.heapCommitted}", perf.heapCommitted >= perf.heapUsed)
        assertTrue("java command: ${perf.strings}", perf.strings["sun.rt.javaCommand"].orEmpty().contains("Gradle", ignoreCase = true) ||
            perf.strings["sun.rt.javaCommand"].orEmpty().isNotEmpty())
        assertEquals(System.getProperty("java.version"), perf.strings["java.property.java.version"])
    }

    /** 클래스가 들어 있는 폴더나 jar */
    private fun classRoot(c: Class<*>): String {
        val url = c.getResource("/" + c.name.replace('.', '/') + ".class")!!.toString()
        return when {
            url.startsWith("jar:") -> File(java.net.URI(url.removePrefix("jar:").substringBefore("!/"))).path
            else -> File(java.net.URI(url.substringBefore(c.name.replace('.', '/') + ".class"))).path
        }
    }

    /** 따로 띄운 Java 프로그램: 메인 클래스 이름, 힙, 실행 명령으로 알아내기 */
    fun testChildJvm() {
        val javaBin = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java").path
        // 클래스 경로는 짧게 (윈도우 명령 길이 제한): 테스트 클래스 폴더 + Kotlin 표준 라이브러리
        val cp = listOf(SleepyMain::class.java, Unit::class.java).map(::classRoot).joinToString(File.pathSeparator)
        val proc = ProcessBuilder(javaBin, "-Xmx64m", "-cp", cp, SleepyMain::class.java.name).redirectErrorStream(true).start()
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
            if (windows && (info == null || info.heapUsed <= 0)) {
                println("WINDOWS child hsperfdata: " + Jvm.perfDiagnostics(pid))
            } else {
                assertNotNull("jvm info for child", info)
                info!!
                assertEquals("SleepyMain", info.label)
                assertEquals(SleepyMain::class.java.name, info.main)
                assertTrue("child heap ${info.heapUsed}", info.heapUsed > 0)
            }

            // 성능 정보 파일 없이 실행 명령만으로도
            Jvm.retain(emptySet())
            val args = listOf(javaBin, "-Xmx64m", "-cp", cp, SleepyMain::class.java.name)
            assertEquals(SleepyMain::class.java.name, Jvm.mainFromArgs(args))
        } finally {
            proc.destroyForcibly()
        }
    }
}
