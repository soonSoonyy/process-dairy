package dev.processmonitor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.processmonitor.settings.ProcessMonitorConfigurable
import dev.processmonitor.settings.ProcessMonitorSettings
import dev.processmonitor.settings.ProcessViewState
import dev.processmonitor.settings.StatusFormat
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.processmonitor.ui.ProcessPanel

/** 설정 화면, Find Action 동작 등록, 보기 상태 저장/복원 */
class UsabilityTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { ProcessViewState.get(project).loadState(ProcessViewState.Options()) } finally { super.tearDown() } // 다른 테스트에 영향 없게
    }

    fun testActionsRegisteredInToolsMenu() {
        val am = ActionManager.getInstance()
        for (id in listOf("ProcessMonitor.Show", "ProcessMonitor.FindByPort", "ProcessMonitor.KillOnPort")) {
            assertNotNull("action $id", am.getAction(id))
        }
        val tools = am.getAction("ToolsMenu") as DefaultActionGroup
        assertTrue("Tools 메뉴에 등록", tools.childActionsOrStubs.any { am.getId(it) == "ProcessMonitor.Group" })
        assertEquals("포트를 쓰는 프로세스 종료…", am.getAction("ProcessMonitor.KillOnPort").templatePresentation.text)
    }

    fun testSettingsPage() {
        val c = ProcessMonitorConfigurable()
        try {
            assertNotNull(c.createComponent())
            c.reset()
            assertFalse(c.isModified)
        } finally {
            c.disposeUIResources()
        }
        val s = ProcessMonitorSettings.get()
        s.state.intervalSec = 5
        assertEquals(5000L, s.intervalMs)
        assertEquals("최근 5분", ProcessMonitorService.get().historyLabel())
        s.state.intervalSec = 2
        assertEquals("최근 2분", ProcessMonitorService.get().historyLabel())
    }

    /** 설정 파일에 저장했다가 다시 읽어도 값이 그대로인지 (IDE 재시작 상황) */
    fun testSettingsSurviveSaveAndLoad() {
        val o = ProcessMonitorSettings.Options().apply {
            intervalSec = 1; statusFormat = StatusFormat.ALL; defaultScope = "PROJECT"; watchLeftovers = false
        }
        val xml = XmlSerializer.serialize(o)
        assertTrue(JDOMUtil.write(xml), JDOMUtil.write(xml).contains("\"ALL\""))
        val back = XmlSerializer.deserialize(xml, ProcessMonitorSettings.Options::class.java)
        assertEquals(1, back.intervalSec)
        assertEquals(StatusFormat.ALL, back.statusFormat)
        assertEquals("PROJECT", back.defaultScope)
        assertFalse(back.watchLeftovers)

        val v = ProcessViewState.Options().apply { scope = "PORTS"; columnWidths = mutableListOf(28, 300, 70) }
        val vb = XmlSerializer.deserialize(XmlSerializer.serialize(v), ProcessViewState.Options::class.java)
        assertEquals("PORTS", vb.scope)
        assertEquals(listOf(28, 300, 70), vb.columnWidths)
    }

    fun testViewStateRestoredAndSaved() {
        val st = ProcessViewState.get(project).state
        st.scope = "PORTS"; st.tree = true; st.field = "CWD"; st.sortCol = "MEM"; st.sortDesc = false
        val p1 = ProcessPanel(project)
        Disposer.register(testRootDisposable, p1)
        assertEquals(ProcessPanel.Scope.PORTS, p1.scopeBox.selectedItem)
        assertTrue(p1.treeBox.isSelected)
        assertEquals(ProcessPanel.Field.CWD, p1.fieldBox.selectedItem)
        assertEquals("메모리 ▲", p1.table.columnModel.getColumn(4).headerValue)

        // 화면에서 바꾸면 저장됨
        p1.scopeBox.selectedItem = ProcessPanel.Scope.PROJECT
        p1.treeBox.doClick()
        assertEquals("PROJECT", st.scope)
        assertFalse(st.tree)

        // 새로 열어도 유지
        val p2 = ProcessPanel(project)
        Disposer.register(testRootDisposable, p2)
        assertEquals(ProcessPanel.Scope.PROJECT, p2.scopeBox.selectedItem)
        assertFalse(p2.treeBox.isSelected)
    }
}
