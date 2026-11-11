package dev.processmonitor.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.ui.ProcessPanel

/** Settings → Tools → Process Monitor */
class ProcessMonitorConfigurable : BoundConfigurable("Process Monitor") {
    override fun createPanel(): DialogPanel {
        val o = ProcessMonitorSettings.get().state
        return panel {
            group("표시") {
                row("갱신 주기:") {
                    comboBox(ProcessMonitorSettings.INTERVALS.map { Interval(it) })
                        .bindItem({ Interval(o.intervalSec) }, { o.intervalSec = it?.sec ?: 2 })
                        .comment("짧을수록 실시간에 가깝지만 IDE가 조금 더 바빠집니다. 그래프는 최근 60개 값을 보여줍니다.")
                }
                row("상태 표시줄:") {
                    comboBox(StatusFormat.entries.map { Choice(it, it.label) })
                        .bindItem({ Choice(o.statusFormat, o.statusFormat.label) }, { o.statusFormat = it?.value ?: StatusFormat.CPU_MEM })
                        .comment("상태 표시줄 위젯을 숨기려면 상태 표시줄을 우클릭해서 \"Process Monitor\"를 끄세요.")
                }
                row("처음 보기 범위:") {
                    comboBox(ProcessPanel.Scope.entries)
                        .bindItem({ ProcessPanel.Scope.entries.firstOrNull { it.name == o.defaultScope } ?: ProcessPanel.Scope.MINE },
                            { o.defaultScope = (it ?: ProcessPanel.Scope.MINE).name })
                        .comment("프로젝트에서 처음 도구 창을 열 때 적용됩니다. 이후에는 마지막으로 본 상태를 기억합니다.")
                }
            }
            group("Run/Debug 감시") {
                row {
                    checkBox("포트 충돌로 실행이 실패하면 포트를 쓰는 프로세스를 알려주기")
                        .bindSelected({ o.watchPortConflicts }, { o.watchPortConflicts = it })
                }
                row {
                    checkBox("실행을 멈춘 뒤 남아 있는 프로세스를 알려주기")
                        .bindSelected({ o.watchLeftovers }, { o.watchLeftovers = it })
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        ProcessMonitorService.get().reschedule() // 갱신 주기 바로 적용
    }
}

/** 콤보 상자 항목 ("2초") */
private data class Interval(val sec: Int) {
    override fun toString() = "${sec}초"
}

/** 콤보 상자 항목 (값 + 화면에 보일 글자) */
private data class Choice<T>(val value: T, val label: String) {
    override fun toString() = label
}
