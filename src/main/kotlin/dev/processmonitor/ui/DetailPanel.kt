package dev.processmonitor.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.processmonitor.ProcessActions
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.data.Describe
import dev.processmonitor.model.ProcDetail
import dev.processmonitor.model.ProcInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Rectangle
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret

/** 오른쪽 상세 패널: 선택한 프로세스가 무엇인지, 추이 그래프, 포트, 실행 명령 */
class DetailPanel(
    private val onSelect: (Int) -> Unit,
    private val onSearchCwd: (String) -> Unit,
    private val onKill: (ProcInfo, Boolean) -> Unit,
) : JPanel(BorderLayout()) {
    private val title = JBLabel().apply { font = JBFont.h3().asBold() }
    private val subtitle = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val cpuChart = SparkChart(listOf(Palette.CPU), 64)
    private val memChart = SparkChart(listOf(Palette.MEM), 64)
    private val html = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE // 새로고침해도 스크롤 위치 유지
        addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) handle(e.description) }
    }
    private val header = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 4, 10)
    }
    private var current: ProcInfo? = null
    private var lastHtml = ""
    private fun textBlock() = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE // 내용이 바뀌어도 스크롤이 끝으로 내려가지 않게
        lineWrap = true          // 공백이 없는 긴 경로도 패널 폭에 맞춰 글자 단위로 줄바꿈
        wrapStyleWord = false
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1))
        border = JBUI.Borders.empty(0, 10, 6, 10)
        alignmentX = LEFT_ALIGNMENT
    }
    private fun sectionTitle(text: String) = JBLabel(text).apply {
        font = JBFont.label().asBold()
        border = JBUI.Borders.empty(4, 10, 2, 10)
        alignmentX = LEFT_ALIGNMENT
    }
    private val cmdTitle = sectionTitle("실행 명령")
    private val cmdText = textBlock()
    private val pathTitle = sectionTitle("경로")
    private val pathText = textBlock()
    private val textBlocks: List<JComponent> = listOf(cmdTitle, cmdText, pathTitle, pathText)

    init {
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(subtitle, BorderLayout.SOUTH)
        }
        val charts = JPanel(GridLayout(1, 2, JBUI.scale(10), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(cpuChart)
            add(memChart)
        }
        header.add(head)
        header.add(charts)
        html.border = JBUI.Borders.empty(0, 10, 6, 10)
        html.alignmentX = LEFT_ALIGNMENT
        header.alignmentX = LEFT_ALIGNMENT
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(header); add(html); textBlocks.forEach { add(it) }
        }
        val body = WidthTrackingPanel().apply { add(column, BorderLayout.NORTH) }
        add(JBScrollPane(body, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        showEmpty()
    }

    private fun setIfChanged(t: JBTextArea, text: String) { if (t.text != text) t.text = text }

    fun showEmpty() {
        textBlocks.forEach { it.isVisible = false }
        current = null
        title.icon = null
        title.text = "프로세스를 선택하세요"
        subtitle.text = "목록에서 고르면 어떤 프로세스인지 자세히 보여줍니다 (↑↓ 키로 이동)"
        cpuChart.isVisible = false
        memChart.isVisible = false
        setHtml("<p style='color:${hex(UIUtil.getContextHelpForeground())}'>우클릭하면 종료·복사·포트 열기 메뉴가 나옵니다.<br>Space: 체크 표시, Delete: 선택 항목 종료</p>")
    }

    fun show(p: ProcInfo?, gonePid: Int?, service: ProcessMonitorService, detail: ProcDetail?) {
        if (p == null) {
            if (gonePid != null) {
                current = null
                textBlocks.forEach { it.isVisible = false }
                title.icon = null
                title.text = "PID $gonePid"
                subtitle.text = "이미 종료된 프로세스입니다"
                cpuChart.isVisible = false; memChart.isVisible = false
                setHtml("")
            } else showEmpty()
            return
        }
        current = p
        title.text = p.name
        title.icon = BadgeIcon(p.category, JBUI.scale(20))
        subtitle.text = "PID ${p.pid} · ${p.category.label} · ${p.user}"
        cpuChart.isVisible = true; memChart.isVisible = true
        val ch = service.cpuHistory[p.pid]?.toList() ?: emptyList()
        val cmax = maxOf(10.0, Math.ceil((ch.maxOrNull() ?: 0.0) / 10) * 10)
        cpuChart.setData(listOf(ch), cmax, "CPU", "%.1f%%".format(p.cpu), service.historyLabel(), "${cmax.toInt()}%")
        val mh = service.memHistory[p.pid]?.toList() ?: emptyList()
        val mmax = Fmt.niceMax(mh.maxOrNull() ?: 0.0, 16.0 * 1048576)
        memChart.setData(listOf(mh), mmax, "메모리", Fmt.mem(p.rssBytes), "%.1f%% · ".format(p.memPct) + service.historyLabel(), Fmt.bytes(mmax))

        val dim = hex(UIUtil.getContextHelpForeground())
        val link = hex(Palette.PORT)
        val d = detail?.takeIf { it.pid == p.pid }
        val parent = service.byPid[p.ppid]?.takeIf { it.pid != p.pid }
        val children = service.processes.filter { it.ppid == p.pid && it.pid != p.pid }
        fun esc(s: String) = StringUtil.escapeXmlEntities(s)
        // 아주 긴 경로/명령만 '/' 뒤에서 줄바꿈 허용 (짧은 건 그대로 표시)
        fun a(href: String, text: String) = "<a href='$href' style='color:$link'>${esc(text)}</a>"
        val sb = StringBuilder()
        sb.append("<div style='margin-top:6px'><b>이 프로세스는?</b><br>${esc(Describe.describe(p.name, p.path, p.category))}</div>")
        sb.append("<table cellpadding='2' style='margin-top:8px'>")
        fun kv(k: String, v: String) = sb.append("<tr><td style='color:$dim' valign='top' nowrap>$k</td><td>$v</td></tr>")
        kv("실행 시간", esc(Fmt.uptime(p.upTimeMs)))
        if (p.startTime > 0) kv("시작 시각", esc(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(p.startTime))))
        if (d != null) kv("상태", esc(d.state))
        if (p.cwd.isNotEmpty()) kv("작업 폴더", a("cwd:${p.cwd}", Fmt.shortPath(p.cwd)) + " <span style='color:$dim'>(클릭: 같은 폴더 프로세스 찾기)</span>")
        kv("부모", if (parent != null) a("pid:${parent.pid}", "${parent.name} (${parent.pid})") else "-")
        sb.append("</table>")

        sb.append("<div style='margin-top:8px'><b>포트 / 네트워크</b>")
        when {
            d == null -> sb.append("<br><span style='color:$dim'>불러오는 중…</span>")
            d.conns.isEmpty() && p.ports.isEmpty() -> sb.append("<br><span style='color:$dim'>열려 있는 포트가 없습니다.</span>")
            d.conns.isEmpty() -> p.ports.forEach { sb.append("<br>LISTEN :$it &nbsp;${a("open:$it", "브라우저에서 열기")}") }
            else -> d.conns.forEach { c ->
                val open = if (c.state == "LISTEN") c.local.substringAfterLast(':').toIntOrNull()?.let { " &nbsp;" + a("open:$it", "열기") } ?: "" else ""
                sb.append("<br><code>${esc(c.proto)} ${esc(c.local)}${if (c.remote.isNotEmpty()) " → " + esc(c.remote) else ""}</code> <span style='color:$dim'>${esc(c.state)}</span>$open")
            }
        }
        sb.append("</div>")
        if (children.isNotEmpty()) {
            sb.append("<div style='margin-top:8px'><b>하위 프로세스 (${children.size})</b>")
            children.take(12).forEach { sb.append("<br>" + a("pid:${it.pid}", "${it.name} (${it.pid})")) }
            if (children.size > 12) sb.append("<br><span style='color:$dim'>…외 ${children.size - 12}개</span>")
            sb.append("</div>")
        }
        sb.append("<div style='margin-top:12px'>")
        if (!ProcessActions.isProtected(p.pid)) {
            sb.append(a("kill:15", "■ 종료") + " &nbsp;&nbsp; " + a("kill:9", "■ 강제 종료") + " &nbsp;&nbsp; ")
        }
        sb.append(a("copy:${p.pid}", "PID 복사") + "</div>")
        setHtml(sb.toString())
        textBlocks.forEach { it.isVisible = true }
        setIfChanged(cmdText, d?.commandLine?.ifEmpty { p.path } ?: "불러오는 중…")
        setIfChanged(pathText, p.path.ifEmpty { "(권한이 없어 확인할 수 없음)" })
    }

    private fun setHtml(body: String) {
        val text = "<html><body>$body</body></html>"
        if (text != lastHtml) {
            lastHtml = text
            html.text = text
        }
    }

    private fun handle(desc: String?) {
        val d = desc ?: return
        val p = current
        when {
            d.startsWith("pid:") -> d.substring(4).toIntOrNull()?.let(onSelect)
            d.startsWith("cwd:") -> onSearchCwd(d.substring(4))
            d.startsWith("open:") -> d.substring(5).toIntOrNull()?.let { ProcessActions.openPort(it) }
            d.startsWith("kill:") && p != null -> onKill(p, d == "kill:9")
            d.startsWith("copy:") -> ProcessActions.copy(d.substring(5), null)
        }
    }

    private fun hex(c: java.awt.Color) = "#" + ColorUtil.toHex(c)
}

/** 스크롤 영역의 폭을 따라가는 패널 (내용이 길어도 가로로 넓어지지 않음) */
private class WidthTrackingPanel : JPanel(BorderLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
