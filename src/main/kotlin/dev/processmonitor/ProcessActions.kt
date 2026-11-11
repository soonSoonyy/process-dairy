package dev.processmonitor

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.processmonitor.model.ProcInfo
import java.awt.datatransfer.StringSelection

/** 종료, 복사, 브라우저 열기 같은 공용 동작 */
object ProcessActions {
    private val selfPid = ProcessHandle.current().pid().toInt()

    fun isProtected(pid: Int) = pid <= 1 || pid == selfPid

    fun kill(project: Project?, targets: List<ProcInfo>, force: Boolean) {
        if (targets.isEmpty()) return
        val verb = if (force) "강제 종료" else "종료"
        val list = targets.take(10).joinToString("\n") { "• ${it.name} (PID ${it.pid})" } +
            (if (targets.size > 10) "\n…외 ${targets.size - 10}개" else "")
        val msg = if (targets.size == 1) "\"${targets[0].name}\" (PID ${targets[0].pid}) 프로세스를 ${verb}할까요?\n저장하지 않은 작업은 사라질 수 있습니다."
        else "선택한 ${targets.size}개 프로세스를 ${verb}할까요?\n\n$list\n\n저장하지 않은 작업은 사라질 수 있습니다."
        if (Messages.showYesNoDialog(project, msg, "프로세스 $verb", verb, "취소", Messages.getWarningIcon()) != Messages.YES) return

        var done = 0
        val failed = ArrayList<String>()
        for (p in targets) {
            if (isProtected(p.pid)) {
                failed += "${p.name}: 보호된 프로세스입니다" + if (p.pid == selfPid) " (지금 사용 중인 IDE)" else ""
                continue
            }
            val h = ProcessHandle.of(p.pid.toLong())
            if (!h.isPresent) { failed += "${p.name}: 이미 종료되었습니다"; continue }
            val ok = try { if (force) h.get().destroyForcibly() else h.get().destroy() } catch (_: Exception) { false }
            if (ok) done++ else failed += "${p.name}: 권한이 없거나 종료할 수 없습니다"
        }
        val text = buildString {
            append("${done}개 종료 요청을 보냈습니다.")
            if (failed.isNotEmpty()) append("<br>실패 ${failed.size}개:<br>" + failed.take(5).joinToString("<br>"))
        }
        notify(project, text, if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
        ProcessMonitorService.get().requestNow()
    }

    fun copy(text: String, project: Project?) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(project, "복사했습니다: $text", NotificationType.INFORMATION)
    }

    fun openPort(port: Int) = BrowserUtil.browse("http://localhost:$port")

    fun notify(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Process Monitor")
            .createNotification(text, type).notify(project)
    }
}
