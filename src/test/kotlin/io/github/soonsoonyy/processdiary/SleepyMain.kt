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
