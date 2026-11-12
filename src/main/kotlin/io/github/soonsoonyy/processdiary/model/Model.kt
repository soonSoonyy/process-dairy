package io.github.soonsoonyy.processdiary.model

import io.github.soonsoonyy.processdiary.PD

enum class Category {
    SYSTEM, APP, DEV, SHELL, OTHER;

    val label: String get() = PD.msg("category.${name.lowercase()}")
}

/** 한 시점의 프로세스 정보 */
data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val path: String,
    val user: String,
    /** 최근 샘플 구간의 CPU 사용률 (%; 여러 코어를 쓰면 100 초과 가능) */
    val cpu: Double,
    val rssBytes: Long,
    val memPct: Double,
    val upTimeMs: Long,
    val startTime: Long,
    val cwd: String,
    val category: Category,
    val ports: List<Int>,
)

/** 시스템 전체 사용량 (속도는 초당 바이트) */
data class SystemStats(
    val time: Long,
    val cpu: Double,
    val memTotal: Long,
    val memUsed: Long,
    val diskRead: Double,
    val diskWrite: Double,
    val diskTotal: Long,
    val diskUsed: Long,
    val netRx: Double,
    val netTx: Double,
    val netRxTotal: Long,
    val netTxTotal: Long,
)

data class Conn(val proto: String, val local: String, val remote: String, val state: String)

/** 선택한 프로세스를 위해 따로(느리게) 조회하는 정보 */
data class ProcDetail(val pid: Int, val commandLine: String, val state: String, val conns: List<Conn>)
