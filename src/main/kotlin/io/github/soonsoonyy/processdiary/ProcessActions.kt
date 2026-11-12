package io.github.soonsoonyy.processdiary

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.soonsoonyy.processdiary.model.ProcInfo
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
                failed += PD.msg(if (pid == selfPid) "kill.fail.protected.ide" else "kill.fail.protected", name)
                continue
            }
            val h = ProcessHandle.of(pid.toLong())
            if (!h.isPresent || !h.get().isAlive) {
                if (pid in pids) failed += PD.msg("kill.fail.gone", name)
                continue
            }
            val ok = try { if (force) h.get().destroyForcibly() else h.get().destroy() } catch (_: Exception) { false }
            if (ok) done++ else failed += PD.msg("kill.fail.denied", name)
        }
        return KillResult(done, failed)
    }

    /** 확인창을 띄운 뒤 종료하고 결과를 알림으로 보여줌 */
    fun kill(project: Project?, targets: List<ProcInfo>, force: Boolean, tree: Boolean = false) {
        if (targets.isEmpty()) return
        val verb = PD.msg(if (force) "kill.verb.force" else "kill.verb")
        val childCount = if (tree) targets.sumOf { descendantsOf(it.pid).size } else 0
        val childText = if (childCount > 0) "\n" + PD.msg("kill.confirm.children", childCount) else ""
        val list = targets.take(10).joinToString("\n") { "• ${it.name} (PID ${it.pid})" } +
            (if (targets.size > 10) "\n" + PD.msg("kill.confirm.more", targets.size - 10) else "")
        val warn = PD.msg("kill.confirm.warning")
        val msg = if (targets.size == 1) PD.msg("kill.confirm.one", targets[0].name, targets[0].pid, verb) + childText + "\n" + warn
        else PD.msg("kill.confirm.many", targets.size, verb) + childText + "\n\n" + list + "\n\n" + warn
        if (Messages.showYesNoDialog(project, msg, PD.msg("kill.confirm.title", verb), verb, PD.msg("common.cancel"), Messages.getWarningIcon()) != Messages.YES) return

        val r = killNow(targets.map { it.pid }, force, tree, targets.associate { it.pid to it.name })
        notifyResult(project, r)
        ProcessDiaryService.get().requestNow()
    }

    fun notifyResult(project: Project?, r: KillResult) {
        val text = buildString {
            append(PD.msg("kill.result.done", r.done))
            if (r.failed.isNotEmpty()) append("<br>" + PD.msg("kill.result.failed", r.failed.size) + "<br>" + r.failed.take(5).joinToString("<br>"))
        }
        notify(project, text, if (r.failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
    }

    /** 포트가 비워질 때까지 기다림 (최대 timeoutMs). 비면 true */
    fun waitPortFree(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        val service = ProcessDiaryService.get()
        while (System.currentTimeMillis() < end) {
            if (service.listenersOnPort(port).isEmpty()) return true
            Thread.sleep(300)
        }
        return service.listenersOnPort(port).isEmpty()
    }

    fun copy(text: String, project: Project?) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(project, PD.msg("copy.done", text), NotificationType.INFORMATION)
    }

    fun openPort(port: Int) = BrowserUtil.browse("http://localhost:$port")

    fun notify(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(text, type).notify(project)
    }
}
