package dev.processmonitor.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** 상태 표시줄에 무엇을 보여줄지 */
enum class StatusFormat(val label: String) {
    // toString()을 바꾸지 않음: 설정 파일에는 이름(CPU_MEM 등)으로 저장돼야 다시 읽을 수 있음
    CPU_MEM("CPU · 메모리"), CPU("CPU만"), NET("네트워크 속도"), ALL("CPU · 메모리 · 네트워크")
}

/** 앱 전체 설정 (Settings → Tools → Process Monitor) */
@Service(Service.Level.APP)
@State(name = "ProcessMonitorSettings", storages = [Storage("processMonitor.xml")])
class ProcessMonitorSettings : SimplePersistentStateComponent<ProcessMonitorSettings.Options>(Options()) {
    class Options : BaseState() {
        /** 갱신 주기(초) */
        var intervalSec by property(2)
        var statusFormat by enum(StatusFormat.CPU_MEM)
        /** 처음 열 때의 보기 범위 (ProcessPanel.Scope 이름) */
        var defaultScope by string("MINE")
        var watchPortConflicts by property(true)
        var watchLeftovers by property(true)
    }

    companion object {
        val INTERVALS = listOf(1, 2, 5)
        fun get(): ProcessMonitorSettings = service()
    }

    val intervalMs: Long get() = (state.intervalSec.takeIf { it in INTERVALS } ?: 2) * 1000L
}
