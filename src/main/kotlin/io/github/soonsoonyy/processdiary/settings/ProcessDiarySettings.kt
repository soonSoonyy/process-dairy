package io.github.soonsoonyy.processdiary.settings

import io.github.soonsoonyy.processdiary.PD
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** 상태 표시줄에 무엇을 보여줄지 */
enum class StatusFormat {
    // toString()을 바꾸지 않음: 설정 파일에는 이름(CPU_MEM 등)으로 저장돼야 다시 읽을 수 있음
    CPU_MEM, CPU, NET, ALL;

    val label: String get() = PD.msg("status.format.${name.lowercase()}")
}

/** 앱 전체 설정 (Settings → Tools → Process Diary) */
@Service(Service.Level.APP)
@State(name = "ProcessDiarySettings", storages = [Storage("processDiary.xml")])
class ProcessDiarySettings : SimplePersistentStateComponent<ProcessDiarySettings.Options>(Options()) {
    class Options : BaseState() {
        /** 갱신 주기(초) */
        var intervalSec by property(2)
        var statusFormat by enum(StatusFormat.CPU_MEM)
        /** 처음 열 때의 보기 범위 (ProcessPanel.Scope 이름) */
        var defaultScope by string("MINE")
        var watchPortConflicts by property(true)
        var watchLeftovers by property(true)
        /** "자동 업데이트 켜기" 알림을 이미 보여줬는지 */
        var updatePrompted by property(false)
        /** 화면 언어: AUTO(IDE 언어) / KO / EN */
        var language by string("AUTO")
    }

    companion object {
        val INTERVALS = listOf(1, 2, 5)
        fun get(): ProcessDiarySettings = service()
    }

    val intervalMs: Long get() = (state.intervalSec.takeIf { it in INTERVALS } ?: 2) * 1000L
}
