package io.github.soonsoonyy.processdiary

/** JvmTest 가 따로 띄우는 Java 프로그램 (메인 클래스 이름과 힙 정보를 읽어 보기 위함) */
object SleepyMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val keep = ByteArray(8 shl 20) // 힙 사용량이 0이 아니게
        Thread.sleep(60_000)
        println(keep.size)
    }
}

/** 테스트용 Java 자식 프로세스 */
object ChildJava {
    val javaBin: String = java.io.File(System.getProperty("java.home"), "bin/" + if (java.io.File.separatorChar == '\\') "java.exe" else "java").path

    /** 클래스가 들어 있는 폴더나 jar */
    private fun classRoot(c: Class<*>): String {
        val url = c.getResource("/" + c.name.replace('.', '/') + ".class")!!.toString()
        return if (url.startsWith("jar:")) java.io.File(java.net.URI(url.removePrefix("jar:").substringBefore("!/"))).path
        else java.io.File(java.net.URI(url.substringBefore(c.name.replace('.', '/') + ".class"))).path
    }

    // 클래스 경로는 짧게 (윈도우 명령 길이 제한): 테스트 클래스 폴더 + Kotlin 표준 라이브러리
    val classPath: String = listOf(SleepyMain::class.java, Unit::class.java).map(::classRoot).joinToString(java.io.File.pathSeparator)

    val args: List<String> get() = listOf(javaBin, "-Xmx64m", "-cp", classPath, SleepyMain::class.java.name)

    fun start(dir: java.io.File? = null): Process = ProcessBuilder(args).directory(dir).redirectErrorStream(true).start()
}
