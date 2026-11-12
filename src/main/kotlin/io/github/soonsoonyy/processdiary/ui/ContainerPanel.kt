package io.github.soonsoonyy.processdiary.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessActions
import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.model.Container
import io.github.soonsoonyy.processdiary.settings.ProcessViewState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** 도구 창의 "Containers" 탭: Docker 컨테이너 목록, 게시 포트, 시작/정지/재시작 */
class ContainerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    enum class CCol(val width: Int) {
        NAME(200), IMAGE(180), STATUS(160), PORTS(150), CPU(70), MEM(90), PROJECT(120);
        val title: String get() = PD.msg("docker.col.${name.lowercase()}")
    }

    private val view = ProcessViewState.get(project)
    internal val allBox = JBCheckBox(PD.msg("docker.showStopped"), view.state.dockerAll)
    internal val projectBox = JBCheckBox(PD.msg("docker.thisProject"), view.state.dockerProject).apply { toolTipText = PD.msg("docker.thisProject.tooltip") }
    private val refreshBtn = JButton(PD.msg("docker.refresh"), AllIcons.Actions.Refresh)
    private val countLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var all: List<Container> = emptyList()
    private var stats: Map<String, Pair<Double, String>> = emptyMap()
    private var statsAt = 0L
    private val model = Model()
    internal val table = JBTable(model)
    private val loading = AtomicBoolean(false)
    @Volatile private var visibleNow = false
    private var future: ScheduledFuture<*>? = null
    /** 테스트용: 마지막 조회 결과 */
    @Volatile internal var lastResult: Docker.Result? = null

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(allBox); add(projectBox); add(refreshBtn); add(countLabel)
        }
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(24)
        table.setStriped(true)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = PD.msg("common.loading")
        CCol.entries.forEach { c -> table.columnModel.getColumn(c.ordinal).preferredWidth = JBUI.scale(c.width) }
        table.columnModel.getColumn(CCol.NAME.ordinal).cellRenderer = NameCell()
        table.columnModel.getColumn(CCol.IMAGE.ordinal).cellRenderer = text { it.image }
        table.columnModel.getColumn(CCol.STATUS.ordinal).cellRenderer = text { it.status }
        table.columnModel.getColumn(CCol.PORTS.ordinal).cellRenderer = PortsCell()
        table.columnModel.getColumn(CCol.CPU.ordinal).cellRenderer = text(right = true) { c -> c.cpu?.let { "%.1f%%".format(it) } ?: "" }
        table.columnModel.getColumn(CCol.MEM.ordinal).cellRenderer = text(right = true) { it.memText }
        table.columnModel.getColumn(CCol.PROJECT.ordinal).cellRenderer = text { it.composeProject }

        add(bar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        allBox.addActionListener { view.state.dockerAll = allBox.isSelected; rebuild(); refresh() }
        projectBox.addActionListener { view.state.dockerProject = projectBox.isSelected; rebuild() }
        refreshBtn.addActionListener { refresh(withStats = true) }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
        })
        // 보이는 동안에만 5초마다 갱신
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                visibleNow = isShowing
                if (visibleNow) refresh(withStats = true)
            }
        }
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (visibleNow) refresh() }, 5, 5, TimeUnit.SECONDS)
    }

    /** 백그라운드에서 docker ps (+ 10초마다 docker stats) */
    fun refresh(withStats: Boolean = false) {
        if (!loading.compareAndSet(false, true)) return
        val includeAll = allBox.isSelected
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val r = Docker.list(all = includeAll)
                val now = System.currentTimeMillis()
                val s = if (r is Docker.Result.Ok && r.containers.any { it.running } && (withStats || now - statsAt > 10_000)) {
                    statsAt = now
                    Docker.stats()
                } else null
                ApplicationManager.getApplication().invokeLater({ apply(r, s) }, ModalityState.any())
            } finally {
                loading.set(false)
            }
        }
    }

    private fun apply(r: Docker.Result, s: Map<String, Pair<Double, String>>?) {
        lastResult = r
        if (s != null) stats = s
        when (r) {
            is Docker.Result.Ok -> {
                all = r.containers
                table.emptyText.text = PD.msg(if (allBox.isSelected) "docker.empty.all" else "docker.empty")
            }
            Docker.Result.NotInstalled -> { all = emptyList(); table.emptyText.text = PD.msg("docker.notInstalled") }
            is Docker.Result.Failed -> { all = emptyList(); table.emptyText.text = PD.msg("docker.failed", r.message.take(140)) }
        }
        rebuild()
    }

    private fun rebuild() {
        val base = project.basePath
        val rows = all.asSequence()
            .filter { allBox.isSelected || it.running }
            .filter { !projectBox.isSelected || (base != null && it.composeDir.isNotEmpty() && inside(it.composeDir, base)) }
            .map { c -> stats[c.id]?.let { (cpu, mem) -> c.copy(cpu = cpu, memText = mem) } ?: c }
            .sortedWith(compareBy<Container>({ !it.running }, { it.composeProject }, { it.name }))
            .toList()
        val selected = selectedContainers().map { it.id }.toSet()
        model.rows = rows
        model.fireTableDataChanged()
        rows.forEachIndexed { i, c -> if (c.id in selected) table.addRowSelectionInterval(i, i) }
        val running = all.count { it.running }
        countLabel.text = PD.msg("docker.count", running, all.size - running)
    }

    private fun inside(dir: String, base: String): Boolean {
        val d = dir.replace('\\', '/').trimEnd('/')
        val b = base.replace('\\', '/').trimEnd('/')
        return d.equals(b, ignoreCase = true) || d.startsWith("$b/", ignoreCase = true)
    }

    private fun selectedContainers(): List<Container> = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }

    // ---------------------------------------------------------------- 동작
    private fun popup(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
        val sel = selectedContainers()
        val c = model.rows[row]
        val g = DefaultActionGroup()
        fun item(text: String, icon: Icon? = null, enabled: Boolean = true, run: () -> Unit) = g.add(Item(text, icon, enabled, run))
        val label = if (sel.size > 1) " (${sel.size})" else ""
        item(PD.msg("docker.stop") + label, AllIcons.Actions.Suspend, sel.any { it.running }) { act("stop", sel.filter { it.running }) }
        item(PD.msg("docker.start") + label, AllIcons.Actions.Execute, sel.any { !it.running }) { act("start", sel.filter { !it.running }) }
        item(PD.msg("docker.restart") + label, AllIcons.Actions.Restart, sel.any { it.running }) { act("restart", sel.filter { it.running }) }
        item(PD.msg("docker.remove") + label, AllIcons.Actions.GC, sel.any { !it.running }) { remove(sel.filter { !it.running }) }
        if (c.hostPorts.isNotEmpty()) {
            g.addSeparator()
            c.hostPorts.take(3).forEach { port -> item(PD.msg("detail.openBrowser") + "  http://localhost:$port", AllIcons.General.Web) { ProcessActions.openPort(port) } }
        }
        g.addSeparator()
        item(PD.msg("docker.copyName"), AllIcons.Actions.Copy) { ProcessActions.copy(c.name, project) }
        item(PD.msg("docker.copyId")) { ProcessActions.copy(c.id.take(12), project) }
        item(PD.msg("docker.copyLogs")) { ProcessActions.copy("docker logs -f --tail 200 ${c.name}", project) }
        ActionManager.getInstance().createActionPopupMenu("ProcessDiary.ContainerPopup", g).component.show(table, e.x, e.y)
    }

    private fun remove(targets: List<Container>) {
        if (targets.isEmpty()) return
        val names = targets.take(10).joinToString("\n") { "• ${it.name} (${it.image})" }
        if (Messages.showYesNoDialog(project, PD.msg("docker.remove.confirm", targets.size) + "\n\n" + names, PD.msg("docker.remove"),
                PD.msg("docker.remove"), PD.msg("common.cancel"), Messages.getWarningIcon()) != Messages.YES) return
        act("rm", targets)
    }

    /** docker <verb> 를 백그라운드에서 실행하고 실패만 알림 */
    fun act(verb: String, targets: List<Container>) {
        if (targets.isEmpty()) return
        AppExecutorUtil.getAppExecutorService().execute {
            val errors = targets.mapNotNull { c -> Docker.command(verb, c.id)?.let { "${c.name}: $it" } }
            if (errors.isNotEmpty()) ProcessActions.notify(project, PD.msg("docker.failedAction") + "<br>" + errors.take(5).joinToString("<br>"), NotificationType.ERROR)
            refresh(withStats = true)
        }
    }

    private class Item(text: String, icon: Icon?, private val enabled: Boolean, private val run: () -> Unit) : DumbAwareAction() {
        init {
            templatePresentation.setText(text, false)
            templatePresentation.icon = icon
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    // ---------------------------------------------------------------- 표
    private class Model : AbstractTableModel() {
        var rows: List<Container> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = CCol.entries.size
        override fun getColumnName(column: Int) = CCol.entries[column].title
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
    }

    private fun text(right: Boolean = false, f: (Container) -> String) = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val c = value as? Container
            val l = super.getTableCellRendererComponent(table, c?.let(f) ?: "", isSelected, false, row, column) as JLabel
            l.horizontalAlignment = if (right) SwingConstants.RIGHT else SwingConstants.LEFT
            l.border = if (right) JBUI.Borders.emptyRight(8) else JBUI.Borders.emptyLeft(6)
            if (!isSelected) l.foreground = UIUtil.getContextHelpForeground()
            l.toolTipText = c?.let(f)?.ifEmpty { null }
            return l
        }
    }

    private class NameCell : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val c = value as? Container ?: return
            icon = StateDot(stateColor(c.state))
            iconTextGap = JBUI.scale(6)
            ipad = JBUI.insetsLeft(4)
            append(c.name, if (c.running) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            toolTipText = "${c.name} · ${c.state} · ${c.id.take(12)}"
        }
    }

    private class PortsCell : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val c = value as? Container
            val ports = c?.hostPorts ?: emptyList()
            val t = ports.take(3).joinToString("  ") { ":$it" } + if (ports.size > 3) "  +${ports.size - 3}" else ""
            val l = super.getTableCellRendererComponent(table, t, isSelected, false, row, column) as JLabel
            l.border = JBUI.Borders.emptyLeft(6)
            if (!isSelected) l.foreground = Palette.PORT
            l.toolTipText = c?.portsText?.ifEmpty { null }
            return l
        }
    }

    /** 상태 점 (실행 중: 초록, 멈춤: 회색, 재시작 중/일시정지: 주황) */
    private class StateDot(private val color: Color) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val d = JBUI.scale(8)
            g2.fillOval(x + (iconWidth - d) / 2, y + (iconHeight - d) / 2, d, d)
            g2.dispose()
        }
        override fun getIconWidth() = JBUI.scale(12)
        override fun getIconHeight() = JBUI.scale(12)
    }

    override fun dispose() {
        future?.cancel(false)
    }

    companion object {
        fun stateColor(state: String): Color = when (state) {
            "running" -> Palette.NET_RX
            "paused", "restarting" -> Palette.CPU
            "dead" -> JBColor.RED
            else -> JBColor.GRAY
        }
    }
}
