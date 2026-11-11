package dev.processmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.processmonitor.ProcessActions
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.model.ProcDetail
import dev.processmonitor.model.ProcInfo
import dev.processmonitor.settings.ProcessMonitorSettings
import dev.processmonitor.settings.ProcessViewState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.Icon
import javax.swing.SwingConstants
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.event.DocumentEvent

/** 도구 창 "Process Monitor"의 전체 화면 */
class ProcessPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    enum class Scope(val label: String) {
        MINE("내 프로세스"), PROJECT("이 프로젝트"), ALL("모든 프로세스"), PORTS("포트 사용 중"), BUSY("CPU 사용 중");
        override fun toString() = label
    }

    enum class Field(val label: String, val hint: String) {
        ALL("전체", "이름, PID, 포트, 폴더 검색"), NAME("이름·PID", "이름 또는 PID"), CWD("작업 폴더", "예: ~/projects/my-app"),
        PORT("포트", "예: 3000"), PATH("실행 경로", "실행 파일 경로");
        override fun toString() = label
    }

    private val service = ProcessMonitorService.get()
    private val view = ProcessViewState.get(project)
    private val home = System.getProperty("user.home")
    private val me = System.getProperty("user.name").substringAfterLast('\\')
    private val projectPath = project.basePath

    internal val scopeBox = ComboBox(Scope.entries.toTypedArray())
    internal val treeBox = JBCheckBox("트리 보기")
    internal val fieldBox = ComboBox(Field.entries.toTypedArray())
    private val search = SearchTextField(false)
    private val resortBtn = JButton("다시 정렬", AllIcons.ObjectBrowser.Sorted).apply { toolTipText = "지금 값 기준으로 순서를 다시 정합니다" }
    private val liveBox = JBCheckBox("실시간 정렬").apply { toolTipText = "켜면 새로고침할 때마다 순서가 바뀝니다" }
    private val chartsBox = JBCheckBox("그래프", true)
    private val cleanupBtn = JButton("정리", AllIcons.Actions.GC).apply {
        isVisible = false
        toolTipText = "이 프로젝트 폴더에서 실행 중이지만 IDE(Run·터미널)와 연결되지 않은 프로세스를 하위 프로세스까지 종료합니다"
    }
    private var cleanupTargets: List<ProcInfo> = emptyList()
    private val pending = ArrayList<() -> Unit>()
    private val countLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val charts = SystemCharts()
    private val chartsRow = JPanel(GridLayout(1, 4, JBUI.scale(14), 0)).apply {
        border = JBUI.Borders.empty(6, 10, 8, 10)
        charts.all.forEach { add(it) }
    }
    private val model: ProcessTableModel = ProcessTableModel { updateActionBar(); table.repaint() }
    val table: JBTable = JBTable(model)
    private val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(3)))
    private val detailPanel = DetailPanel({ select(it) }, { searchCwd(it) }, { p, force -> ProcessActions.kill(project, listOf(p), force) })

    private var sortCol = Col.CPU
    private var sortDesc = true
    private var rank: Map<Int, Int> = emptyMap()
    private val expanded = HashMap<Int, Boolean>()
    private var restoring = false
    private var selectedPid: Int? = null
    private var detail: ProcDetail? = null
    private var tick = 0
    @Volatile private var visibleNow = false
    /** 테스트용: 화면에 보이지 않아도 프로세스 목록을 수집 */
    @Volatile internal var forceCollect = false

    init {
        // ---- 위쪽 도구 막대
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(scopeBox); add(treeBox); add(sep())
            add(fieldBox); add(search.apply { textEditor.columns = 18 }); add(sep())
            add(resortBtn); add(liveBox); add(chartsBox); add(countLabel); add(cleanupBtn)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(bar)
            add(chartsRow)
        }
        // ---- 표
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(24)
        table.setStriped(true)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "표시할 프로세스가 없습니다"
        Col.entries.forEach { c ->
            val tc = table.columnModel.getColumn(c.ordinal)
            tc.preferredWidth = JBUI.scale(c.width)
            if (c == Col.CHECK) { tc.maxWidth = JBUI.scale(c.width); tc.minWidth = JBUI.scale(c.width) }
        }
        table.columnModel.getColumn(Col.NAME.ordinal).cellRenderer = NameRenderer { treeBox.isSelected }
        table.columnModel.getColumn(Col.PID.ordinal).cellRenderer = TextRenderer(right = true, text = { it.p.pid.toString() })
        table.columnModel.getColumn(Col.CPU.ordinal).cellRenderer = BarRenderer(Palette.CPU, { it.p.cpu / 100.0 }, { "%.1f%%".format(it.p.cpu) })
        table.columnModel.getColumn(Col.MEM.ordinal).cellRenderer = BarRenderer(Palette.MEM, { it.p.memPct / 20.0 }, { Fmt.mem(it.p.rssBytes) })
        table.columnModel.getColumn(Col.PORTS.ordinal).cellRenderer = PortRenderer()
        table.columnModel.getColumn(Col.CWD.ordinal).cellRenderer = TextRenderer(text = { Fmt.shortPath(it.p.cwd) }, tip = { it.p.cwd.ifEmpty { null } })
        table.columnModel.getColumn(Col.USER.ordinal).cellRenderer = TextRenderer(text = { it.p.user })
        updateHeaders()

        val tablePanel = JPanel(BorderLayout()).apply {
            add(actionBar, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        actionBar.isVisible = false
        actionBar.background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
        val splitter = OnePixelSplitter(false, "ProcessMonitor.detailSplitter", 0.68f).apply {
            firstComponent = tablePanel
            secondComponent = detailPanel
        }
        add(north, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // ---- 저장된 보기 상태 복원 (이벤트 연결 전에)
        val st = view.state
        scopeBox.selectedItem = Scope.entries.firstOrNull { it.name == (st.scope ?: ProcessMonitorSettings.get().state.defaultScope) } ?: Scope.MINE
        treeBox.isSelected = st.tree
        fieldBox.selectedItem = Field.entries.firstOrNull { it.name == st.field } ?: Field.ALL
        sortCol = Col.entries.firstOrNull { it.name == st.sortCol && it.sortable } ?: Col.CPU
        sortDesc = st.sortDesc
        liveBox.isSelected = st.live
        chartsBox.isSelected = st.charts
        chartsRow.isVisible = st.charts
        st.columnWidths.takeIf { it.size == Col.entries.size }?.forEachIndexed { i, w ->
            if (i != Col.CHECK.ordinal && w > 20) table.columnModel.getColumn(i).preferredWidth = w
        }
        updateHeaders()
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: ChangeEvent?) {
                st.columnWidths = Col.entries.map { table.columnModel.getColumn(it.ordinal).width }.toMutableList()
            }
            override fun columnAdded(e: TableColumnModelEvent?) {}
            override fun columnRemoved(e: TableColumnModelEvent?) {}
            override fun columnMoved(e: TableColumnModelEvent?) {}
            override fun columnSelectionChanged(e: ListSelectionEvent?) {}
        })

        // ---- 이벤트 (바꿀 때마다 보기 상태 저장)
        scopeBox.addActionListener { st.scope = (scopeBox.selectedItem as Scope).name; resort(); rebuild() }
        treeBox.addActionListener { st.tree = treeBox.isSelected; resort(); rebuild() }
        fieldBox.addActionListener { st.field = (fieldBox.selectedItem as Field).name; search.textEditor.emptyText.text = (fieldBox.selectedItem as Field).hint; rebuild() }
        search.textEditor.emptyText.text = (fieldBox.selectedItem as Field).hint
        search.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = rebuild() })
        resortBtn.addActionListener { resort(); rebuild() }
        cleanupBtn.addActionListener { ProcessActions.kill(project, cleanupTargets, force = false, tree = true) }
        liveBox.addActionListener { st.live = liveBox.isSelected; if (liveBox.isSelected) { resort(); rebuild() } }
        chartsBox.addActionListener { st.charts = chartsBox.isSelected; chartsRow.isVisible = chartsBox.isSelected; revalidate() }

        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val col = Col.entries[table.convertColumnIndexToModel(table.columnAtPoint(e.point)).takeIf { it >= 0 } ?: return]
                if (!col.sortable) return
                if (sortCol == col) sortDesc = !sortDesc else { sortCol = col; sortDesc = col !in setOf(Col.NAME, Col.USER, Col.CWD) }
                st.sortCol = sortCol.name; st.sortDesc = sortDesc
                updateHeaders(); resort(); rebuild()
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || !treeBox.isSelected) return
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || table.convertColumnIndexToModel(col) != Col.NAME.ordinal) return
                val r = model.rows[row]
                if (NameRenderer.arrowHit(r, e.x - table.getCellRect(row, col, false).x)) {
                    expanded[r.p.pid] = !r.expanded
                    rebuild()
                }
            }
        })
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || restoring) return@addListSelectionListener
            val lead = table.selectionModel.leadSelectionIndex
            val pid = if (lead in model.rows.indices && table.isRowSelected(lead)) model.rows[lead].p.pid else null
            if (pid != selectedPid) {
                selectedPid = pid
                detail = null
                renderDetail()
                loadDetail()
            }
        }
        bindKey(table, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleCheck") {
            val sel = selectedProcs().map { it.pid }
            val allChecked = sel.all { it in model.checked }
            sel.forEach { if (allChecked) model.checked -= it else model.checked += it }
            table.repaint(); updateActionBar()
        }
        val killAction: () -> Unit = { ProcessActions.kill(project, selectedProcs(), false) }
        bindKey(table, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "killSelected", killAction)
        bindKey(table, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "killSelected2", killAction)

        // 보이는 동안에만 프로세스 목록 수집
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                visibleNow = isShowing
                if (visibleNow) service.requestNow()
            }
        }
        service.subscribe(this, { visibleNow || forceCollect }) { onUpdate() }
        onUpdate()
    }

    private fun sep() = JSeparator(SwingConstants.VERTICAL).apply { preferredSize = JBUI.size(1, 20) }

    private fun bindKey(c: JComponent, ks: KeyStroke, name: String, action: () -> Unit) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name)
        c.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
    }

    private fun updateHeaders() {
        Col.entries.forEach { c ->
            table.columnModel.getColumn(c.ordinal).headerValue = c.title + if (c == sortCol) (if (sortDesc) " ▼" else " ▲") else ""
        }
        table.tableHeader.repaint()
    }

    private fun onUpdate() {
        if (chartsRow.isVisible) charts.update(service)
        service.error?.let { table.emptyText.text = "시스템 정보를 읽지 못했습니다: $it" }
        if (service.processes.isEmpty()) return
        if (rank.isEmpty() || liveBox.isSelected) resort()
        rebuild()
        if (++tick % 2 == 0) loadDetail() else renderDetail()
        if (pending.isNotEmpty()) { val p = pending.toList(); pending.clear(); p.forEach { it() } }
    }

    /** 프로세스 목록이 준비되면 실행 (알림에서 "Process Monitor에서 보기"를 눌렀을 때 사용) */
    fun afterFirstData(action: () -> Unit) {
        if (service.processes.isNotEmpty() && model.rows.isNotEmpty()) action() else { pending += action; service.requestNow() }
    }

    // ---------------------------------------------------------------- 정렬 (순서 고정)
    private fun comparator(): Comparator<ProcInfo> {
        val base: Comparator<ProcInfo> = when (sortCol) {
            Col.NAME -> compareBy { it.name.lowercase() }
            Col.PID -> compareBy { it.pid }
            Col.CPU -> compareBy { it.cpu }
            Col.MEM -> compareBy { it.rssBytes }
            Col.PORTS -> compareBy { it.ports.firstOrNull() ?: Int.MAX_VALUE }
            Col.CWD -> compareBy { it.cwd.ifEmpty { "￿" }.lowercase() }
            Col.USER -> compareBy { it.user.lowercase() }
            Col.CHECK -> compareBy { it.pid }
        }
        return (if (sortDesc) base.reversed() else base).thenBy { it.pid }
    }

    /** 지금 값 기준으로 순위를 다시 매김. 그 뒤로는 이 순위를 유지하고 새 프로세스는 아래에 붙음 */
    private fun resort() {
        rank = service.processes.sortedWith(comparator()).withIndex().associate { it.value.pid to it.index }
    }

    private val stable: Comparator<ProcInfo> get() = Comparator { a, b ->
        val x = rank[a.pid]
        val y = rank[b.pid]
        when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> comparator().compare(a, b)
        }
    }

    // ---------------------------------------------------------------- 필터와 표 구성
    private fun inScope(p: ProcInfo): Boolean = when (scopeBox.selectedItem as Scope) {
        Scope.MINE -> p.user.substringAfterLast('\\').equals(me, ignoreCase = true)
        Scope.PROJECT -> projectPath != null && p.cwd.isNotEmpty() && (p.cwd == projectPath || p.cwd.startsWith("$projectPath/") || p.cwd.startsWith("$projectPath\\"))
        Scope.ALL -> true
        Scope.PORTS -> p.ports.isNotEmpty()
        Scope.BUSY -> p.cpu >= 1.0
    }

    private fun matcher(): (ProcInfo) -> Boolean {
        val q = search.text.trim().lowercase()
        if (q.isEmpty()) return { true }
        val qx = if (q.startsWith("~")) home.lowercase() + q.substring(1) else q
        val qp = q.removePrefix(":")
        val name = { p: ProcInfo -> p.name.lowercase().contains(q) || p.pid.toString() == q }
        val cwd = { p: ProcInfo -> p.cwd.isNotEmpty() && p.cwd.lowercase().contains(qx) }
        val port = { p: ProcInfo -> qp.all(Char::isDigit) && p.ports.any { it.toString().startsWith(qp) } }
        val path = { p: ProcInfo -> p.path.lowercase().contains(qx) }
        return when (fieldBox.selectedItem as Field) {
            Field.ALL -> { p -> name(p) || cwd(p) || port(p) || path(p) }
            Field.NAME -> name
            Field.CWD -> cwd
            Field.PORT -> port
            Field.PATH -> path
        }
    }

    private fun buildRows(): List<Row> {
        val procs = service.processes
        val byPid = service.byPid
        val match = matcher()
        val searching = search.text.isNotBlank()
        if (!treeBox.isSelected) return procs.filter { inScope(it) && match(it) }.sortedWith(stable).map { Row(it, 0, 0, false) }

        val visible = HashSet<Int>()
        for (p in procs) {
            if (!inScope(p) || !match(p)) continue
            visible += p.pid
            if (searching) { // 검색 결과는 부모 경로까지 함께 보여줌
                var c = byPid[p.ppid]
                var guard = 0
                while (c != null && c.pid !in visible && guard++ < 64) { visible += c.pid; c = byPid[c.ppid] }
            }
        }
        val kids = HashMap<Int, MutableList<ProcInfo>>()
        val roots = ArrayList<ProcInfo>()
        for (pid in visible) {
            val p = byPid[pid] ?: continue
            if (p.ppid != p.pid && p.ppid in visible) kids.getOrPut(p.ppid) { ArrayList() } += p else roots += p
        }
        val out = ArrayList<Row>()
        fun walk(p: ProcInfo, depth: Int) {
            val k = kids[p.pid]?.sortedWith(stable) ?: emptyList()
            val open = searching || (expanded[p.pid] ?: (depth < 2))
            out += Row(p, depth, k.size, open)
            if (open && depth < 64) k.forEach { walk(it, depth + 1) }
        }
        roots.sortedWith(stable).forEach { walk(it, 0) }
        return out
    }

    private fun rebuild() {
        val rows = buildRows()
        val selected = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it)?.p?.pid }.toSet()
        // 표 갱신 중에는 JTable이 선택을 잠시 지우는 이벤트를 보내므로, 그동안 선택 변경을 무시하고 직접 복원
        restoring = true
        try {
            val changed = model.update(rows)
            if (changed && selected.isNotEmpty()) {
                table.clearSelection()
                rows.forEachIndexed { i, r -> if (r.p.pid in selected) table.addRowSelectionInterval(i, i) }
                val lead = rows.indexOfFirst { it.p.pid == selectedPid }
                if (lead >= 0) table.selectionModel.addSelectionInterval(lead, lead)
            }
        } finally {
            restoring = false
        }
        model.checked.retainAll(service.byPid.keys)
        countLabel.text = "${rows.size}개"
        updateCleanup(rows)
        updateActionBar()
    }

    /** "이 프로젝트" 보기에서 IDE와 연결되지 않은 프로세스(남겨진 개발 서버 등)를 정리 대상으로 */
    private fun updateCleanup(rows: List<Row>) {
        if (scopeBox.selectedItem != Scope.PROJECT) { cleanupBtn.isVisible = false; return }
        val ideTree = ProcessHandle.current().descendants().map { it.pid().toInt() }.toList().toSet()
        val inView = rows.map { it.p }.filter { inScope(it) }
        val candidates = inView.filter { it.pid !in ideTree && !ProcessActions.isProtected(it.pid) && it.category != dev.processmonitor.model.Category.SHELL }
        val ids = candidates.map { it.pid }.toSet()
        cleanupTargets = candidates.filter { it.ppid !in ids } // 최상위만 (하위는 함께 종료)
        cleanupBtn.text = "IDE 밖 프로세스 정리 (${candidates.size})"
        cleanupBtn.isVisible = candidates.isNotEmpty()
    }

    fun searchPort(port: Int) {
        fieldBox.selectedItem = Field.PORT
        scopeBox.selectedItem = Scope.ALL
        search.text = port.toString()
        resort(); rebuild()
        model.rows.firstOrNull()?.let { select(it.p.pid) }
    }

    private fun updateActionBar() {
        val checked = model.checked.mapNotNull { service.byPid[it] }
        actionBar.isVisible = checked.isNotEmpty()
        if (checked.isEmpty()) return
        actionBar.removeAll()
        val count = checked.size
        val total = Fmt.mem(checked.sumOf { it.rssBytes })
        actionBar.add(JBLabel("<html><b>" + count + "개 체크됨</b> · 메모리 합계 " + total + "</html>"))
        actionBar.add(JButton("체크 해제").apply { addActionListener { this@ProcessPanel.model.checked.clear(); table.repaint(); updateActionBar() } })
        actionBar.add(JButton("체크한 항목 종료").apply { addActionListener { ProcessActions.kill(project, checkedProcs(), false) } })
        actionBar.add(JButton("강제 종료").apply { addActionListener { ProcessActions.kill(project, checkedProcs(), true) } })
        actionBar.revalidate(); actionBar.repaint()
    }

    private fun checkedProcs(): List<ProcInfo> = model.checked.mapNotNull { service.byPid[it] }
    private fun selectedProcs(): List<ProcInfo> = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it)?.p }

    // ---------------------------------------------------------------- 상세 패널
    private fun loadDetail() {
        val pid = selectedPid ?: return
        service.loadDetail(pid) { d -> if (selectedPid == pid) { detail = d; renderDetail() } }
    }

    private fun renderDetail() {
        val pid = selectedPid
        detailPanel.show(pid?.let { service.byPid[it] }, pid, service, detail)
    }

    fun select(pid: Int) {
        var idx = model.rows.indexOfFirst { it.p.pid == pid }
        if (idx < 0) { // 필터에 가려져 있으면 전체 보기로
            scopeBox.selectedItem = Scope.ALL
            search.text = ""
            rebuild()
            idx = model.rows.indexOfFirst { it.p.pid == pid }
        }
        if (idx >= 0) {
            table.setRowSelectionInterval(idx, idx)
            table.scrollRectToVisible(table.getCellRect(idx, 0, true))
        }
    }

    fun searchCwd(cwd: String) {
        fieldBox.selectedItem = Field.CWD
        scopeBox.selectedItem = Scope.ALL
        search.text = Fmt.shortPath(cwd)
        resort(); rebuild()
    }

    // ---------------------------------------------------------------- 우클릭 메뉴
    private fun popup(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
        val sel = selectedProcs()
        val p = model.rows[row].p
        val g = DefaultActionGroup()
        fun item(text: String, icon: Icon? = null, enabled: Boolean = true, run: () -> Unit) = g.add(SimpleAction(text, icon, enabled, run))
        val many = sel.size > 1
        val killable = sel.any { !ProcessActions.isProtected(it.pid) }
        item(if (many) "선택한 ${sel.size}개 종료" else "종료", AllIcons.Actions.Suspend, killable) { ProcessActions.kill(project, sel, false) }
        item(if (many) "선택한 ${sel.size}개 강제 종료" else "강제 종료", AllIcons.Debugger.KillProcess, killable) { ProcessActions.kill(project, sel, true) }
        val kids = sel.sumOf { ProcessActions.descendantsOf(it.pid).size }
        if (kids > 0) item("하위 프로세스까지 종료 (+${kids}개)", AllIcons.Actions.GC, killable) { ProcessActions.kill(project, sel, false, tree = true) }
        if (model.checked.isNotEmpty()) item("체크한 ${model.checked.size}개 종료") { ProcessActions.kill(project, checkedProcs(), false) }
        val allChecked = sel.all { it.pid in model.checked }
        item(if (allChecked) "체크 해제" else "체크 표시", AllIcons.Actions.Checked) {
            sel.forEach { if (allChecked) model.checked -= it.pid else model.checked += it.pid }
            table.repaint(); updateActionBar()
        }
        g.addSeparator()
        p.ports.take(3).forEach { port -> item("브라우저에서 열기  http://localhost:$port", AllIcons.General.Web) { ProcessActions.openPort(port) } }
        if (p.ports.isNotEmpty()) g.addSeparator()
        item("PID 복사", AllIcons.Actions.Copy) { ProcessActions.copy(p.pid.toString(), project) }
        if (p.path.isNotEmpty()) item("경로 복사") { ProcessActions.copy(p.path, project) }
        if (p.cwd.isNotEmpty()) {
            item("작업 폴더 복사") { ProcessActions.copy(p.cwd, project) }
            item("같은 작업 폴더의 프로세스 찾기", AllIcons.Actions.Find) { searchCwd(p.cwd) }
        }
        service.byPid[p.ppid]?.takeIf { it.pid != p.pid }?.let { par ->
            g.addSeparator()
            item("부모 프로세스로 이동 (${par.name})", AllIcons.Hierarchy.Supertypes) { select(par.pid) }
        }
        ActionManager.getInstance().createActionPopupMenu("ProcessMonitor.TablePopup", g).component.show(table, e.x, e.y)
    }

    /** 우클릭 메뉴 항목 */
    private class SimpleAction(text: String, icon: Icon?, private val enabled: Boolean, private val run: () -> Unit) : DumbAwareAction() {
        init {
            templatePresentation.setText(text, false) // 이름 속 '_'를 단축키 표시로 해석하지 않음 (process_api 등)
            templatePresentation.icon = icon
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    override fun dispose() {}
}
