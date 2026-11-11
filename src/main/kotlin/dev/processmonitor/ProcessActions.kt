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

    /** 종료 결과 */
    data class KillResult(val done: Int, val failed: List<String>)

    /** 하위 프로세스 PID (깊은 것부터: 자식을 먼저 종료해야 부모가 다시 띄우지 못함) */
    fun descendantsOf(pid: Int): List<Int> = try {
        ProcessHandle.of(pid.toLong()).map { h -> h.descendants().toList() }.orElse(emptyList())
            .map { it.pid().toInt() }.reversed()
    } catch (_: Exception) { emptyList() }

    /**
     * 확인창 없이 바로 종료 (백그라운드 스레드에서 호출 가능).
     * tree=true 이면 하위 프로세스도 함께 종료한다.
     */
    fun killNow(pids: List<Int>, force: Boolean, tree: Boolean, names: Map<Int, String> = emptyMap()): KillResult {
        val order = LinkedHashSet<Int>()
        for (pid in pids) {
            if (tree) order += descendantsOf(pid)
            order += pid
        }
        var done = 0
        val failed = ArrayList<String>()
        for (pid in order) {
            val name = names[pid] ?: ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }.map { it.substringAfterLast('/').substringAfterLast('\\') }.orElse("PID $pid")
            if (isProtected(pid)) {
                failed += "$name: 보호된 프로세스입니다" + if (pid == selfPid) " (지금 사용 중인 IDE)" else ""
                continue
            }
            val h = ProcessHandle.of(pid.toLong())
            if (!h.isPresent || !h.get().isAlive) {
                if (pid in pids) failed += "$name: 이미 종료되었습니다"
                continue
            }
            val ok = try { if (force) h.get().destroyForcibly() else h.get().destroy() } catch (_: Exception) { false }
            if (ok) done++ else failed += "$name: 권한이 없거나 종료할 수 없습니다"
        }
        return KillResult(done, failed)
    }

    /** 확인창을 띄운 뒤 종료하고 결과를 알림으로 보여줌 */
    fun kill(project: Project?, targets: List<ProcInfo>, force: Boolean, tree: Boolean = false) {
        if (targets.isEmpty()) return
        val verb = if (force) "강제 종료" else "종료"
        val childCount = if (tree) targets.sumOf { descendantsOf(it.pid).size } else 0
        val childText = if (childCount > 0) "\n(하위 프로세스 ${childCount}개 포함)" else ""
        val list = targets.take(10).joinToString("\n") { "• ${it.name} (PID ${it.pid})" } +
            (if (targets.size > 10) "\n…외 ${targets.size - 10}개" else "")
        val msg = if (targets.size == 1) "\"${targets[0].name}\" (PID ${targets[0].pid}) 프로세스를 ${verb}할까요?$childText\n저장하지 않은 작업은 사라질 수 있습니다."
        else "선택한 ${targets.size}개 프로세스를 ${verb}할까요?$childText\n\n$list\n\n저장하지 않은 작업은 사라질 수 있습니다."
        if (Messages.showYesNoDialog(project, msg, "프로세스 $verb", verb, "취소", Messages.getWarningIcon()) != Messages.YES) return

        val r = killNow(targets.map { it.pid }, force, tree, targets.associate { it.pid to it.name })
        notifyResult(project, r)
        ProcessMonitorService.get().requestNow()
    }

    fun notifyResult(project: Project?, r: KillResult) {
        val text = buildString {
            append("${r.done}개 종료 요청을 보냈습니다.")
            if (r.failed.isNotEmpty()) append("<br>실패 ${r.failed.size}개:<br>" + r.failed.take(5).joinToString("<br>"))
        }
        notify(project, text, if (r.failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
    }

    /** 포트가 비워질 때까지 기다림 (최대 timeoutMs). 비면 true */
    fun waitPortFree(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        val service = ProcessMonitorService.get()
        while (System.currentTimeMillis() < end) {
            if (service.listenersOnPort(port).isEmpty()) return true
            Thread.sleep(300)
        }
        return service.listenersOnPort(port).isEmpty()
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
