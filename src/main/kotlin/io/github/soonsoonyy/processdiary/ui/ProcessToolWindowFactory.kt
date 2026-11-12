package io.github.soonsoonyy.processdiary.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import io.github.soonsoonyy.processdiary.PD

class ProcessToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        const val ID = "Process Diary"

        /** 도구 창을 열고 패널을 받아 동작 실행 (EDT에서 호출) */
        private fun withPanel(project: Project, action: (ProcessPanel) -> Unit) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.show {
                val content = tw.contentManager.contents.firstOrNull { it.component is ProcessPanel } ?: return@show
                tw.contentManager.setSelectedContent(content)
                val panel = content.component as ProcessPanel
                panel.afterFirstData { action(panel) }
            }
        }

        fun showPid(project: Project, pid: Int) = withPanel(project) { it.select(pid) }

        fun showPort(project: Project, port: Int) = withPanel(project) { it.searchPort(port) }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProcessPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, PD.msg("tab.processes"), false)
        content.setDisposer(panel)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(content)

        val containers = ContainerPanel(project)
        val dockerContent = ContentFactory.getInstance().createContent(containers, PD.msg("tab.containers"), false)
        dockerContent.setDisposer(containers)
        Disposer.register(toolWindow.disposable, containers)
        toolWindow.contentManager.addContent(dockerContent)
    }
}
