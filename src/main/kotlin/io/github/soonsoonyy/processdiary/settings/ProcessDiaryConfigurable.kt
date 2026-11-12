package io.github.soonsoonyy.processdiary.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessDiaryService
import io.github.soonsoonyy.processdiary.ui.ProcessPanel
import io.github.soonsoonyy.processdiary.update.UpdateRepository

/** Settings → Tools → Process Diary */
class ProcessDiaryConfigurable : BoundConfigurable("Process Diary") {
    override fun createPanel(): DialogPanel {
        val o = ProcessDiarySettings.get().state
        // 언어 이름은 어떤 언어로 보고 있든 알아볼 수 있게 각 언어로 표시
        val languages = listOf(Choice("AUTO", PD.msg("settings.language.auto")), Choice("KO", "한국어"), Choice("EN", "English"))
        return panel {
            group(PD.msg("settings.group.display")) {
                row(PD.msg("settings.language")) {
                    comboBox(languages)
                        .bindItem({ languages.firstOrNull { it.value == o.language } ?: languages[0] }, { o.language = it?.value ?: "AUTO" })
                        .comment(PD.msg("settings.language.comment"))
                }
                row(PD.msg("settings.interval")) {
                    comboBox(ProcessDiarySettings.INTERVALS.map { Choice(it, PD.msg("settings.interval.value", it)) })
                        .bindItem({ Choice(o.intervalSec, PD.msg("settings.interval.value", o.intervalSec)) }, { o.intervalSec = it?.value ?: 2 })
                        .comment(PD.msg("settings.interval.comment"))
                }
                row(PD.msg("settings.statusBar")) {
                    comboBox(StatusFormat.entries.map { Choice(it, it.label) })
                        .bindItem({ Choice(o.statusFormat, o.statusFormat.label) }, { o.statusFormat = it?.value ?: StatusFormat.CPU_MEM })
                        .comment(PD.msg("settings.statusBar.comment"))
                }
                row(PD.msg("settings.defaultScope")) {
                    comboBox(ProcessPanel.Scope.entries)
                        .bindItem({ ProcessPanel.Scope.entries.firstOrNull { it.name == o.defaultScope } ?: ProcessPanel.Scope.MINE },
                            { o.defaultScope = (it ?: ProcessPanel.Scope.MINE).name })
                        .comment(PD.msg("settings.defaultScope.comment"))
                }
            }
            group(PD.msg("settings.group.update")) {
                row {
                    checkBox(PD.msg("settings.update"))
                        .bindSelected({ UpdateRepository.isEnabled() }, { UpdateRepository.setEnabled(it) })
                        .comment(PD.msg("settings.update.comment") + "<br>" + UpdateRepository.URL)
                }
            }
            group(PD.msg("settings.group.watch")) {
                row {
                    checkBox(PD.msg("settings.watch.ports"))
                        .bindSelected({ o.watchPortConflicts }, { o.watchPortConflicts = it })
                }
                row {
                    checkBox(PD.msg("settings.watch.leftovers"))
                        .bindSelected({ o.watchLeftovers }, { o.watchLeftovers = it })
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        ProcessDiaryService.get().reschedule() // 갱신 주기 바로 적용
    }
}

/** 콤보 상자 항목 (값 + 화면에 보일 글자) */
private data class Choice<T>(val value: T, val label: String) {
    override fun toString() = label
}
