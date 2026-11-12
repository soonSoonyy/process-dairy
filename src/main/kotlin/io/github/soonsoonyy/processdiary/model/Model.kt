package io.github.soonsoonyy.processdiary.model

import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.data.JvmInfo

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
    /** Java 프로세스면 메인 클래스와 힙 정보 */
    val jvm: JvmInfo? = null,
) {
    /** 알림·확인창에 쓸 이름 (Java는 무엇을 실행하는지 함께: "java · GradleDaemon") */
    val displayName: String get() = jvm?.let { "$name · ${it.label}" } ?: name
}

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
data class ProcDetail(
    val pid: Int,
    val commandLine: String,
    val state: String,
    val conns: List<Conn>,
    /** 이 프로세스가 대신 열어 둔 포트의 Docker 컨테이너 (docker-proxy, Docker Desktop 등) */
    val containers: List<Container> = emptyList(),
)

/** Docker 컨테이너 한 개 (docker ps 결과) */
data class Container(
    val id: String,
    val name: String,
    val image: String,
    /** running, exited, paused, restarting, created, dead */
    val state: String,
    /** "Up 5 minutes", "Exited (0) 2 hours ago" */
    val status: String,
    /** docker ps 의 Ports 원문 */
    val portsText: String,
    /** 이 컴퓨터에 열린(게시된) 포트 */
    val hostPorts: List<Int>,
    /** docker compose 프로젝트 이름과 폴더 (compose 로 만든 경우) */
    val composeProject: String = "",
    val composeDir: String = "",
    val composeService: String = "",
    /** docker stats 값 (아직 모르면 null) */
    val cpu: Double? = null,
    val memText: String = "",
) {
    val running: Boolean get() = state == "running"
}
