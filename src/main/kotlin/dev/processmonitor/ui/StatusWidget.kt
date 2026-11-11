package dev.processmonitor.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.processmonitor.ProcessActions
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.model.ProcInfo
import dev.processmonitor.settings.ProcessMonitorSettings
import dev.processmonitor.settings.StatusFormat
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class StatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = StatusWidget.ID
    override fun getDisplayName() = "Process Monitor (CPU·메모리)"
    override fun createWidget(project: Project): StatusBarWidget = StatusWidget(project)
}

/** 상태 표시줄 오른쪽 아래: 작은 CPU 그래프 + "CPU 23% · MEM 61%". 클릭하면 팝업 */
class StatusWidget(private val project: Project) : CustomStatusBarWidget {
    companion object { const val ID = "dev.processmonitor.status" }

    private val service = ProcessMonitorService.get()
    private val label = JBLabel("CPU –").apply {
        border = JBUI.Borders.empty(0, 6)
        iconTextGap = JBUI.scale(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Process Monitor: 클릭하면 사용량 그래프와 무거운 프로세스를 보여줍니다"
    }
    private var popup: JBPopup? = null

    override fun ID() = ID
    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = togglePopup()
        })
        service.subscribe(this, { false }) { update() }
    }

    private fun update() {
        val s = service.latest ?: return
        val mem = if (s.memTotal > 0) s.memUsed * 100.0 / s.memTotal else 0.0
        val net = "↓ ${Fmt.bytes(s.netRx)}/s ↑ ${Fmt.bytes(s.netTx)}/s"
        label.text = when (ProcessMonitorSettings.get().state.statusFormat) {
            StatusFormat.CPU -> "CPU %3.0f%%".format(s.cpu)
            StatusFormat.NET -> net
            StatusFormat.ALL -> "CPU %3.0f%% · MEM %3.0f%% · %s".format(s.cpu, mem, net)
            StatusFormat.CPU_MEM -> "CPU %3.0f%% · MEM %3.0f%%".format(s.cpu, mem)
        }
        label.icon = if (ProcessMonitorSettings.get().state.statusFormat == StatusFormat.NET) null
        else SparkIcon(service.history.map { it.cpu }, Palette.CPU)
    }

    private fun togglePopup() {
        popup?.let { if (it.isVisible) { it.cancel(); return } }
        val content = StatusPopupPanel(project) { popup?.cancel() }
        val p = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false).setCancelOnClickOutside(true).setMovable(true).setResizable(false)
            .setTitle("Process Monitor").createPopup()
        Disposer.register(p, content)
        popup = p
        val size = content.preferredSize
        p.show(RelativePoint(label, Point(label.width - size.width, -size.height - JBUI.scale(30))))
    }

    override fun dispose() { popup?.cancel() }
}

/** 상태 표시줄 팝업: 그래프 4개 + CPU/메모리 상위 프로세스 (열려 있는 동안 순서 고정) */
class StatusPopupPanel(private val project: Project, private val close: () -> Unit) : JPanel(BorderLayout()), Disposable {
    private val service = ProcessMonitorService.get()
    private val charts = SystemCharts(100)
    private val cpuList = TopList("CPU 많이 쓰는 프로세스") { "%.1f%%".format(it.cpu) }
    private val memList = TopList("메모리 많이 쓰는 프로세스") { Fmt.mem(it.rssBytes) }

    init {
        border = JBUI.Borders.empty(8, 10)
        val grid = JPanel(GridLayout(2, 2, JBUI.scale(14), JBUI.scale(8))).apply {
            charts.all.forEach { add(it.apply { preferredSize = JBUI.size(290, 100) }) }
        }
        val lists = JPanel(GridLayout(1, 2, JBUI.scale(14), 0)).apply {
            border = JBUI.Borders.emptyTop(10)
            add(cpuList); add(memList)
        }
        val links = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(ActionLink("Process Monitor 창 열기") {
                close()
                ToolWindowManager.getInstance(project).getToolWindow(ProcessToolWindowFactory.ID)?.show()
            })
            add(JBLabel("   "))
            add(ActionLink("목록 다시 정렬") { cpuList.reset(); memList.reset(); update() })
        }
        add(grid, BorderLayout.NORTH)
        add(lists, BorderLayout.CENTER)
        add(links, BorderLayout.SOUTH)
        service.subscribe(this, { true }) { update() }
        service.requestNow()
        update()
    }

    private fun update() {
        charts.update(service)
        val procs = service.processes
        if (procs.isEmpty()) return
        cpuList.update(procs.sortedByDescending { it.cpu }, service.byPid)
        memList.update(procs.sortedByDescending { it.rssBytes }, service.byPid)
    }

    override fun dispose() {}

    /** 상위 5개 목록. 처음 정한 순서를 유지하고, 종료된 항목만 빠지면 아래에서 채움 */
    private inner class TopList(title: String, private val value: (ProcInfo) -> String) : JPanel() {
        private val n = 5
        private var pids: List<Int> = emptyList()
        private val names = List(n) { JBLabel() }
        private val values = List(n) { JBLabel().apply { foreground = UIUtil.getContextHelpForeground() } }
        private val kills = List(n) { i -> ActionLink("종료") { target(i)?.let { ProcessActions.kill(project, listOf(it), false) } } }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(title).apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyBottom(4) })
            val rows = JPanel(GridBagLayout())
            for (i in 0 until n) {
                val c = GridBagConstraints().apply { gridy = i; anchor = GridBagConstraints.WEST; insets = JBUI.insets(1, 0) }
                rows.add(names[i], c.apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
                rows.add(values[i], c.apply { gridx = 1; weightx = 0.0; fill = GridBagConstraints.NONE; insets = JBUI.insets(1, 8) })
                rows.add(kills[i], c.apply { gridx = 2; insets = JBUI.insets(1, 0) })
            }
            add(rows)
            alignmentX = LEFT_ALIGNMENT
        }

        private fun target(i: Int): ProcInfo? = pids.getOrNull(i)?.let { service.byPid[it] }

        fun reset() { pids = emptyList() }

        fun update(ranked: List<ProcInfo>, byPid: Map<Int, ProcInfo>) {
            val keep = pids.filter { it in byPid }.toMutableList()
            for (p in ranked) { if (keep.size >= n) break; if (p.pid !in keep) keep += p.pid }
            pids = keep
            for (i in 0 until n) {
                val p = target(i)
                names[i].text = p?.name ?: ""
                names[i].icon = p?.let { BadgeIcon(it.category, JBUI.scale(14)) }
                names[i].toolTipText = p?.let { "PID ${it.pid} · ${it.path}" }
                values[i].text = p?.let(value) ?: ""
                kills[i].isVisible = p != null && !ProcessActions.isProtected(p.pid)
            }
        }
    }
}
