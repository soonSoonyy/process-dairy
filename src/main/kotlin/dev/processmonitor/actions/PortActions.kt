package dev.processmonitor.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import dev.processmonitor.ProcessActions
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.ui.ProcessToolWindowFactory

/** 포트 번호 입력 (1~65535) */
private fun askPort(project: Project?, title: String): Int? {
    val validator = object : InputValidator {
        override fun checkInput(inputString: String?) = inputString?.trim()?.removePrefix(":")?.toIntOrNull()?.let { it in 1..65535 } == true
        override fun canClose(inputString: String?) = checkInput(inputString)
    }
    return Messages.showInputDialog(project, "포트 번호 (예: 3000, 8080):", title, null, "", validator)
        ?.trim()?.removePrefix(":")?.toIntOrNull()
}

/** Find Action / Tools 메뉴: 포트를 쓰는 프로세스 종료 */
class KillProcessOnPortAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val port = askPort(project, "포트를 쓰는 프로세스 종료") ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            val service = ProcessMonitorService.get()
            val owners = service.listenersOnPort(port).toSet()
            val procs = if (owners.isEmpty()) emptyList() else service.quickList().filter { it.pid in owners }
            ApplicationManager.getApplication().invokeLater {
                if (procs.isEmpty()) {
                    ProcessActions.notify(project, "포트 $port 을(를) 사용 중인 프로세스가 없습니다.", NotificationType.INFORMATION)
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
        val port = askPort(project, "포트로 프로세스 찾기") ?: return
        ProcessToolWindowFactory.showPort(project, port)
    }
}

/** Find Action / Tools 메뉴: 도구 창 열기 */
class ShowProcessMonitorAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(ProcessToolWindowFactory.ID)?.show()
    }
}
