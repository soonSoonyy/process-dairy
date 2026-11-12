package dev.processmonitor.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.processmonitor.model.Category
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** 공용 색상 (라이트/다크 테마 각각) */
object Palette {
    val CPU = JBColor(Color(0xFF9500), Color(0xFF9F0A))
    val MEM = JBColor(Color(0x30B0C7), Color(0x40C8E0))
    val DISK_R = JBColor(Color(0x5856D6), Color(0x5E5CE6))
    val DISK_W = JBColor(Color(0xFF2D55), Color(0xFF375F))
    val NET_RX = JBColor(Color(0x34C759), Color(0x30D158))
    val NET_TX = JBColor(Color(0xAF52DE), Color(0xBF5AF2))
    val GRID = JBColor(Color(0, 0, 0, 22), Color(255, 255, 255, 22))
    val CHART_BG = JBColor(Color(0, 0, 0, 8), Color(255, 255, 255, 10))
    val PORT = JBColor(Color(0x007AFF), Color(0x0A84FF))

    fun category(c: Category): Color = when (c) {
        Category.SYSTEM -> JBColor(Color(0x8E8E93), Color(0x8E8E93))
        Category.APP -> JBColor(Color(0x007AFF), Color(0x0A84FF))
        Category.DEV -> JBColor(Color(0xAF52DE), Color(0xBF5AF2))
        Category.SHELL -> JBColor(Color(0x34C759), Color(0x30D158))
        Category.OTHER -> JBColor(Color(0xFF9500), Color(0xFF9F0A))
    }

    fun glyph(c: Category) = when (c) {
        Category.SYSTEM -> "⚙"; Category.APP -> "▣"; Category.DEV -> "›"; Category.SHELL -> "$"; Category.OTHER -> "•"
    }

    fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)
}

object Fmt {
    private val home = System.getProperty("user.home")

    fun bytes(b: Double): String = when {
        b >= 1L shl 40 -> "%.2f TB".format(b / (1L shl 40))
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20))
        else -> "%.0f KB".format(b / 1024)
    }

    fun bytes(b: Long) = bytes(b.toDouble())

    fun mem(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"

    fun shortPath(p: String): String = if (p.isNotEmpty() && (p == home || p.startsWith("$home/") || p.startsWith("$home\\"))) "~" + p.substring(home.length) else p

    fun uptime(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400
        val h = s % 86400 / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (d > 0) "${d}일 %d:%02d:%02d".format(h, m, sec) else "%d:%02d:%02d".format(h, m, sec)
    }

    /** 그래프 상한: 2의 거듭제곱으로 올림 */
    fun niceMax(peak: Double, minimum: Double): Double {
        val m = max(minimum, peak * 1.15)
        return 2.0.pow(ceil(ln(m) / ln(2.0)))
    }
}

/** 카테고리 배지 아이콘 (둥근 사각형 + 글자) */
class BadgeIcon(private val cat: Category, private val size: Int = JBUI.scale(16)) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        UISettings.setupAntialiasing(g2) // 글자: IDE와 같은 렌더링(윈도우 ClearType 등)
        g2.color = Palette.category(cat)
        g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), size * 0.35f, size * 0.35f))
        g2.color = Color.WHITE
        g2.font = JBFont.label().deriveFont(Font.BOLD, size * 0.62f)
        val t = Palette.glyph(cat)
        val fm = g2.fontMetrics
        g2.drawString(t, x + (size - fm.stringWidth(t)) / 2f, y + (size + fm.ascent - fm.descent) / 2f)
        g2.dispose()
    }
}

/** 작은 꺾은선 그래프 아이콘 (상태 표시줄용) */
class SparkIcon(private val values: List<Double>, private val color: Color, private val w: Int = JBUI.scale(26), private val h: Int = JBUI.scale(12)) : Icon {
    override fun getIconWidth() = w
    override fun getIconHeight() = h
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        if (values.isEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val n = 30
        val vs = values.takeLast(n)
        val path = Path2D.Double()
        vs.forEachIndexed { i, v ->
            val px = x + (n - vs.size + i) * (w - 1.0) / (n - 1)
            val py = y + h - 1 - v.coerceIn(0.0, 100.0) / 100.0 * (h - 2)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val area = Path2D.Double(path)
        area.lineTo(x + w - 1.0, (y + h).toDouble())
        area.lineTo(x + (n - vs.size) * (w - 1.0) / (n - 1), (y + h).toDouble())
        area.closePath()
        g2.color = Palette.withAlpha(color, 70)
        g2.fill(area)
        g2.color = color
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.2f)
        g2.draw(path)
        g2.dispose()
    }
}

/** 제목·현재 값·보조 설명 + 영역 그래프(한두 계열) */
class SparkChart(private val colors: List<Color>, height: Int = 74) : JComponent() {
    private var series: List<List<Double>> = emptyList()
    private var max = 100.0
    private var title = ""
    private var value = ""
    private var sub = ""
    private var maxLabel = ""
    private var legend: List<String> = emptyList()

    init {
        preferredSize = JBUI.size(220, height)
        minimumSize = JBUI.size(120, height)
    }

    fun setData(series: List<List<Double>>, max: Double, title: String, value: String, sub: String, maxLabel: String, legend: List<String> = emptyList()) {
        this.series = series; this.max = if (max <= 0) 1.0 else max; this.title = title; this.value = value
        this.sub = sub; this.maxLabel = maxLabel; this.legend = legend
        toolTipText = "$title  $value — $sub"
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        UISettings.setupAntialiasing(g2) // 글자: IDE와 같은 렌더링(윈도우 ClearType 등)
        val pad = JBUI.scale(2)
        val bold = JBFont.label().asBold()
        val small = JBFont.small()
        var y = pad + g2.getFontMetrics(bold).ascent
        g2.font = bold
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(title, pad, y)
        val tw = g2.fontMetrics.stringWidth(title)
        g2.font = bold.deriveFont(bold.size2D + 1f)
        val vx = pad + tw + JBUI.scale(8)
        g2.drawString(clip(value, g2, width - vx - pad), vx, y) // 좁을 때 잘리지 않고 … 처리
        y += g2.getFontMetrics(small).height
        g2.font = small
        g2.color = UIUtil.getContextHelpForeground()
        g2.drawString(clip(sub, g2, width - 2 * pad), pad, y)
        val top = y + JBUI.scale(5)
        val ch = height - top - pad
        val cw = width - 2 * pad
        if (ch < 10 || cw < 10) { g2.dispose(); return }
        val arc = JBUI.scale(8).toFloat()
        g2.color = Palette.CHART_BG
        g2.fill(RoundRectangle2D.Float(pad.toFloat(), top.toFloat(), cw.toFloat(), ch.toFloat(), arc, arc))
        g2.color = Palette.GRID
        for (f in listOf(0.25, 0.5, 0.75)) {
            val gy = (top + ch * f).toInt()
            g2.drawLine(pad, gy, pad + cw, gy)
        }
        val clip = g2.clip
        g2.clip(RoundRectangle2D.Float(pad.toFloat(), top.toFloat(), cw.toFloat(), ch.toFloat(), arc, arc))
        series.forEachIndexed { si, vs ->
            if (vs.isEmpty()) return@forEachIndexed
            val color = colors[si % colors.size]
            val n = dev.processmonitor.ProcessMonitorService.HISTORY
            val path = Path2D.Double()
            vs.forEachIndexed { i, v ->
                val px = pad + (n - vs.size + i) * (cw - 1.0) / (n - 1)
                val py = top + ch - 1.5 - (v.coerceIn(0.0, max) / max) * (ch - 3)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val area = Path2D.Double(path)
            area.lineTo(pad + cw - 1.0, (top + ch).toDouble())
            area.lineTo(pad + (n - vs.size) * (cw - 1.0) / (n - 1), (top + ch).toDouble())
            area.closePath()
            g2.color = Palette.withAlpha(color, 60)
            g2.fill(area)
            g2.color = color
            g2.stroke = BasicStroke(JBUI.scale(1) * 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(path)
        }
        g2.clip = clip
        g2.font = small
        val fm = g2.fontMetrics
        val baseY = top + fm.ascent + JBUI.scale(2)
        val mx = pad + cw - fm.stringWidth(maxLabel) - JBUI.scale(5)
        labelBackground(g2, mx, baseY, fm.stringWidth(maxLabel), fm)
        g2.color = UIUtil.getContextHelpForeground()
        g2.drawString(maxLabel, mx, baseY)
        var lx = pad + JBUI.scale(6)
        if (legend.isNotEmpty()) {
            val lw = legend.sumOf { JBUI.scale(7) + JBUI.scale(10) + fm.stringWidth(it) } - JBUI.scale(7)
            labelBackground(g2, lx, baseY, lw, fm)
        }
        legend.forEachIndexed { i, l ->
            val sq = JBUI.scale(7)
            g2.color = colors[i % colors.size]
            g2.fillRect(lx, top + JBUI.scale(4) + (fm.ascent - sq) / 2, sq, sq)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(l, lx + sq + JBUI.scale(3), top + fm.ascent + JBUI.scale(2))
            lx += sq + JBUI.scale(10) + fm.stringWidth(l)
        }
        g2.dispose()
    }

    /** 글자 뒤 반투명 배경 (그래프 선 위에서도 글자가 또렷하게) */
    private fun labelBackground(g2: Graphics2D, x: Int, baseline: Int, w: Int, fm: java.awt.FontMetrics) {
        val padX = JBUI.scale(3)
        g2.color = Palette.withAlpha(UIUtil.getPanelBackground(), 225)
        g2.fillRoundRect(x - padX, baseline - fm.ascent, w + padX * 2, fm.ascent + fm.descent, JBUI.scale(6), JBUI.scale(6))
    }

    private fun clip(s: String, g: Graphics2D, w: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && fm.stringWidth("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }
}
