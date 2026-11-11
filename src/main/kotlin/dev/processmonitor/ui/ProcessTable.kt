package dev.processmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.processmonitor.model.ProcInfo
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

enum class Col(val title: String, val width: Int, val sortable: Boolean = true) {
    CHECK("", 28, false), NAME("이름", 230), PID("PID", 70), CPU("CPU", 90), MEM("메모리", 100),
    PORTS("포트", 120), CWD("작업 폴더", 220), USER("사용자", 90)
}

/** 표의 한 줄. 트리 보기에서는 깊이(depth)와 자식 수를 함께 가진다 */
data class Row(val p: ProcInfo, val depth: Int, val children: Int, val expanded: Boolean)

class ProcessTableModel(private val onCheckChanged: () -> Unit) : AbstractTableModel() {
    var rows: List<Row> = emptyList(); private set
    val checked = LinkedHashSet<Int>()

    /** 줄 구성이 바뀌었으면 true (선택 복원 필요) */
    fun update(newRows: List<Row>): Boolean {
        val same = newRows.size == rows.size && newRows.indices.all {
            newRows[it].p.pid == rows[it].p.pid && newRows[it].depth == rows[it].depth &&
                newRows[it].expanded == rows[it].expanded && newRows[it].children == rows[it].children
        }
        rows = newRows
        if (same) {
            if (rows.isNotEmpty()) fireTableRowsUpdated(0, rows.size - 1)
        } else {
            fireTableDataChanged()
        }
        return !same
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = Col.entries.size
    override fun getColumnName(column: Int) = Col.entries[column].title
    override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == Col.CHECK.ordinal) java.lang.Boolean::class.java else Any::class.java
    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == Col.CHECK.ordinal
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return if (columnIndex == Col.CHECK.ordinal) r.p.pid in checked else r
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != Col.CHECK.ordinal) return
        val pid = rows[rowIndex].p.pid
        if (aValue == true) checked += pid else checked -= pid
        fireTableCellUpdated(rowIndex, columnIndex)
        onCheckChanged()
    }
}

/** 이름 칸: 들여쓰기 + 펼치기 화살표 + 카테고리 배지 + 이름 */
class NameRenderer(private val tree: () -> Boolean) : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        val r = value as? Row ?: return
        val badge = BadgeIcon(r.p.category)
        if (tree()) {
            val arrow = when {
                r.children == 0 -> EmptyIcon.create(AllIcons.General.ArrowRight)
                r.expanded -> AllIcons.General.ArrowDown
                else -> AllIcons.General.ArrowRight
            }
            ipad = JBUI.insetsLeft(r.depth * INDENT)
            icon = RowIcon(arrow, badge)
        } else {
            ipad = JBUI.insetsLeft(2)
            icon = badge
        }
        iconTextGap = JBUI.scale(5)
        append(r.p.name)
        if (tree() && r.children > 0) append("  (${r.children})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = r.p.path.ifEmpty { null }
    }

    companion object {
        const val INDENT = 16
        /** 화살표 영역 클릭 판정 */
        fun arrowHit(r: Row, xInCell: Int): Boolean {
            val start = JBUI.scale(r.depth * INDENT)
            return r.children > 0 && xInCell >= start && xInCell <= start + JBUI.scale(20)
        }
    }
}

/** 값 뒤에 비율 막대를 그리는 칸 (CPU, 메모리) */
class BarRenderer(private val color: Color, private val ratio: (Row) -> Double, private val text: (Row) -> String) : DefaultTableCellRenderer() {
    private var fraction = 0.0

    init { horizontalAlignment = SwingConstants.RIGHT }

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        super.getTableCellRendererComponent(table, "", isSelected, false, row, column)
        val r = value as? Row ?: return this
        fraction = ratio(r).coerceIn(0.0, 1.0)
        setText(text(r))
        border = JBUI.Borders.emptyRight(8)
        return this
    }

    override fun paintComponent(g: Graphics) {
        if (fraction > 0.001) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val inset = JBUI.scale(4)
            g2.color = Palette.withAlpha(color, 70)
            val w = ((width - inset * 2) * fraction).toInt().coerceAtLeast(JBUI.scale(2))
            g2.fillRoundRect(inset, inset, w, height - inset * 2, JBUI.scale(5), JBUI.scale(5))
        }
        super.paintComponent(g)
    }
}

/** 일반 텍스트 칸 */
class TextRenderer(private val right: Boolean = false, private val dim: Boolean = true, private val text: (Row) -> String, private val tip: (Row) -> String? = { null }) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val r = value as? Row
        val c = super.getTableCellRendererComponent(table, r?.let(text) ?: "", isSelected, false, row, column) as JLabel
        c.horizontalAlignment = if (right) SwingConstants.RIGHT else SwingConstants.LEFT
        c.border = if (right) JBUI.Borders.emptyRight(8) else JBUI.Borders.emptyLeft(6)
        if (!isSelected && dim) c.foreground = UIUtil.getContextHelpForeground()
        c.toolTipText = r?.let(tip)
        return c
    }
}

/** 포트 칸 (파란색) */
class PortRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val r = value as? Row
        val ports = r?.p?.ports ?: emptyList()
        val t = ports.take(3).joinToString("  ") { ":$it" } + if (ports.size > 3) "  +${ports.size - 3}" else ""
        val c = super.getTableCellRendererComponent(table, t, isSelected, false, row, column) as JLabel
        c.border = JBUI.Borders.emptyLeft(6)
        if (!isSelected) c.foreground = Palette.PORT
        c.toolTipText = if (ports.isEmpty()) null else "LISTEN: " + ports.joinToString(", ")
        return c
    }
}
