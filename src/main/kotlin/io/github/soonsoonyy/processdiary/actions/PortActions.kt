package io.github.soonsoonyy.processdiary.actions

import io.github.soonsoonyy.processdiary.PD
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.soonsoonyy.processdiary.ProcessActions
import io.github.soonsoonyy.processdiary.ProcessDiaryService
import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.ui.ProcessToolWindowFactory

/** 포트 번호 입력 (1~65535) */
private fun askPort(project: Project?, title: String): Int? {
    val validator = object : InputValidator {
        override fun checkInput(inputString: String?) = inputString?.trim()?.removePrefix(":")?.toIntOrNull()?.let { it in 1..65535 } == true
        override fun canClose(inputString: String?) = checkInput(inputString)
    }
    return Messages.showInputDialog(project, PD.msg("port.ask.prompt"), title, null, "", validator)
        ?.trim()?.removePrefix(":")?.toIntOrNull()
}

/** Find Action / Tools 메뉴: 포트를 쓰는 프로세스 종료 */
class KillProcessOnPortAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val port = askPort(project, PD.msg("port.ask.kill.title")) ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            val service = ProcessDiaryService.get()
            val owners = service.listenersOnPort(port).toSet()
            val procs = if (owners.isEmpty()) emptyList() else service.quickList().filter { it.pid in owners }
            // Docker 가 게시한 포트면 컨테이너를 멈춤 (Docker 프록시 프로세스를 끝내면 Docker 가 멈출 수 있음)
            val containers = if (procs.isEmpty() || procs.any { Docker.isDockerProcess(it.name) }) Docker.containersOnPort(port) else emptyList()
            ApplicationManager.getApplication().invokeLater {
                if (containers.isNotEmpty()) {
                    val names = containers.joinToString("\n") { "• ${it.name} (${it.image})" }
                    if (Messages.showYesNoDialog(project, PD.msg("port.container.confirm", port) + "\n\n" + names, PD.msg("docker.stop"),
                            PD.msg("docker.stop"), PD.msg("common.cancel"), Messages.getWarningIcon()) == Messages.YES) {
                        AppExecutorUtil.getAppExecutorService().execute {
                            val errors = containers.mapNotNull { c -> Docker.command("stop", c.id)?.let { "${c.name}: $it" } }
                            if (errors.isEmpty()) ProcessActions.notify(project, PD.msg("docker.stopped", containers.joinToString(", ") { it.name }), NotificationType.INFORMATION)
                            else ProcessActions.notify(project, PD.msg("docker.failedAction") + "<br>" + errors.joinToString("<br>"), NotificationType.ERROR)
                            service.requestNow()
                        }
                    }
                } else if (procs.isEmpty()) {
                    ProcessActions.notify(project, PD.msg("port.none", port), NotificationType.INFORMATION)
                } else {
                    ProcessActions.kill(project, procs, force = false, tree = true)
                }
            }
        }
    }
}

/** Find Action / Tools 메뉴: 포트로 프로세스 찾기 (도구 창에서 보여줌) */
class FindProcessByPortAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val port = askPort(project, PD.msg("port.ask.find.title")) ?: return
        ProcessToolWindowFactory.showPort(project, port)
    }
}

/** Find Action / Tools 메뉴: 도구 창 열기 */
class ShowProcessDiaryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(ProcessToolWindowFactory.ID)?.show()
    }
}
