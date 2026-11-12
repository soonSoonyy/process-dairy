package io.github.soonsoonyy.processdiary.alerts

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessActions
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.model.SystemStats
import io.github.soonsoonyy.processdiary.settings.ProcessDiaryConfigurable
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import io.github.soonsoonyy.processdiary.ui.Fmt
import io.github.soonsoonyy.processdiary.ui.ProcessToolWindowFactory

/**
 * "기준을 넘은 상태가 일정 시간 계속되면 한 번 알림".
 * 알린 뒤에는 사용량이 기준보다 5%p 넘게 내려가야 다시 알릴 수 있고, 같은 종류는 최소 [cooldownMs] 간격을 둔다.
 */
class Threshold(private val cooldownMs: Long = 15 * 60_000L) {
    private var since: Long? = null
    private var fired = false
    private var lastFired = Long.MIN_VALUE / 2

    /** 기준을 넘어 지켜보는 중 (프로세스 목록을 미리 모아 두어야 알림에 "가장 많이 쓰는 프로세스"를 넣을 수 있음) */
    val watching: Boolean get() = since != null && !fired

    fun update(now: Long, value: Double, enabled: Boolean, limit: Int, durationMs: Long): Boolean {
        if (!enabled) { since = null; fired = false; return false }
        if (value >= limit) {
            if (since == null) since = now
            if (!fired && now - since!! >= durationMs && now - lastFired >= cooldownMs) {
                fired = true
                lastFired = now
                return true
            }
        } else {
            since = null
            if (value < limit - 5) fired = false
        }
        return false
    }
}

/** 시스템 CPU/메모리 경고 (ProcessDiaryService 가 매 샘플마다 EDT에서 호출) */
class UsageAlerts {
    private val cpu = Threshold()
    private val mem = Threshold()

    val watching: Boolean get() = cpu.watching || mem.watching

    fun enabled(): Boolean = ProcessDiarySettings.get().state.let { it.alertCpu || it.alertMem }

    fun check(sys: SystemStats, procs: List<ProcInfo>, cores: Int) {
        val o = ProcessDiarySettings.get().state
        val durationMs = o.alertSec * 1000L
        val memPct = if (sys.memTotal > 0) sys.memUsed * 100.0 / sys.memTotal else 0.0
        if (cpu.update(sys.time, sys.cpu, o.alertCpu, o.alertCpuPct, durationMs)) {
            val top = procs.sortedByDescending { it.cpu }.take(3).filter { it.cpu >= 1.0 }
            notify(
                PD.msg("alert.cpu.title", o.alertCpuPct, duration(o.alertSec)),
                PD.msg("alert.cpu.body", sys.cpu.toInt()),
                top.map { "${StringUtil.escapeXmlEntities(it.displayName)} &nbsp;<b>${"%.0f".format(it.cpu)}%</b>" },
                top.firstOrNull(),
            )
        }
        if (mem.update(sys.time, memPct, o.alertMem, o.alertMemPct, durationMs)) {
            val top = procs.sortedByDescending { it.rssBytes }.take(3)
            notify(
                PD.msg("alert.mem.title", memPct.toInt()),
                PD.msg("alert.mem.body", Fmt.bytes(sys.memUsed.toDouble()), Fmt.bytes(sys.memTotal.toDouble())),
                top.map { "${StringUtil.escapeXmlEntities(it.displayName)} &nbsp;<b>${Fmt.mem(it.rssBytes)}</b>" },
                top.firstOrNull(),
            )
        }
    }

    private fun duration(sec: Int) = if (sec % 60 == 0) PD.msg("alert.minutes", sec / 60) else PD.msg("alert.seconds", sec)

    private fun notify(title: String, body: String, lines: List<String>, top: ProcInfo?) {
        val text = body + if (lines.isEmpty()) "" else "<br>" + lines.joinToString("<br>")
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(title, text, NotificationType.WARNING)
        if (top != null && !ProcessActions.isProtected(top.pid)) {
            n.addAction(NotificationAction.createSimpleExpiring(PD.msg("alert.stop", top.displayName)) {
                ProcessActions.kill(activeProject(), listOf(top), force = false)
            })
        }
        if (top != null) {
            n.addAction(NotificationAction.createSimple(PD.msg("notify.showInToolWindow")) {
                activeProject()?.let { ProcessToolWindowFactory.showPid(it, top.pid) }
            })
        }
        n.addAction(NotificationAction.createSimple(PD.msg("alert.settings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(activeProject(), ProcessDiaryConfigurable::class.java)
        })
        n.notify(activeProject())
    }

    private fun activeProject(): Project? {
        IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.let { return it }
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
