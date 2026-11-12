package io.github.soonsoonyy.processdiary

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.soonsoonyy.processdiary.settings.ProcessDiaryConfigurable
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import io.github.soonsoonyy.processdiary.settings.ProcessViewState
import io.github.soonsoonyy.processdiary.settings.StatusFormat
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.soonsoonyy.processdiary.ui.ProcessPanel

/** 설정 화면, Find Action 동작 등록, 보기 상태 저장/복원 */
class UsabilityTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { ProcessViewState.get(project).loadState(ProcessViewState.Options()) } finally { super.tearDown() } // 다른 테스트에 영향 없게
    }

    fun testActionsRegisteredInToolsMenu() {
        val am = ActionManager.getInstance()
        for (id in listOf("ProcessDiary.Show", "ProcessDiary.FindByPort", "ProcessDiary.KillOnPort")) {
            assertNotNull("action $id", am.getAction(id))
        }
        val tools = am.getAction("ToolsMenu") as DefaultActionGroup
        assertTrue("Tools 메뉴에 등록", tools.childActionsOrStubs.any { am.getId(it) == "ProcessDiary.Group" })
        assertEquals("Stop Process Using Port…", am.getAction("ProcessDiary.KillOnPort").templatePresentation.text)
    }

    fun testSettingsPage() {
        val c = ProcessDiaryConfigurable()
        try {
            assertNotNull(c.createComponent())
            c.reset()
            assertFalse(c.isModified)
        } finally {
            c.disposeUIResources()
        }
        val s = ProcessDiarySettings.get()
        s.state.intervalSec = 5
        assertEquals(5000L, s.intervalMs)
        assertEquals("last 5 min", ProcessDiaryService.get().historyLabel())
        s.state.intervalSec = 2
        assertEquals("last 2 min", ProcessDiaryService.get().historyLabel())
    }

    /** 설정 파일에 저장했다가 다시 읽어도 값이 그대로인지 (IDE 재시작 상황) */
    fun testSettingsSurviveSaveAndLoad() {
        val o = ProcessDiarySettings.Options().apply {
            intervalSec = 1; statusFormat = StatusFormat.ALL; defaultScope = "PROJECT"; watchLeftovers = false
        }
        val xml = XmlSerializer.serialize(o)
        assertTrue(JDOMUtil.write(xml), JDOMUtil.write(xml).contains("\"ALL\""))
        val back = XmlSerializer.deserialize(xml, ProcessDiarySettings.Options::class.java)
        assertEquals(1, back.intervalSec)
        assertEquals(StatusFormat.ALL, back.statusFormat)
        assertEquals("PROJECT", back.defaultScope)
        assertFalse(back.watchLeftovers)

        val v = ProcessViewState.Options().apply { scope = "PORTS"; columnWidths = mutableListOf(28, 300, 70) }
        val vb = XmlSerializer.deserialize(XmlSerializer.serialize(v), ProcessViewState.Options::class.java)
        assertEquals("PORTS", vb.scope)
        assertEquals(listOf(28, 300, 70), vb.columnWidths)
    }

    /** 자동 업데이트 켜기/끄기 = IDE 플러그인 저장소 목록에 주소 등록/해제 */
    fun testUpdateRepositoryToggle() {
        val hosts = com.intellij.openapi.updateSettings.impl.UpdateSettings.getInstance().storedPluginHosts
        val before = hosts.toList()
        try {
            io.github.soonsoonyy.processdiary.update.UpdateRepository.setEnabled(true)
            io.github.soonsoonyy.processdiary.update.UpdateRepository.setEnabled(true) // 두 번 켜도 한 번만 등록
            assertEquals(1, hosts.count { it == io.github.soonsoonyy.processdiary.update.UpdateRepository.URL })
            assertTrue(io.github.soonsoonyy.processdiary.update.UpdateRepository.isEnabled())
            io.github.soonsoonyy.processdiary.update.UpdateRepository.setEnabled(false)
            assertFalse(io.github.soonsoonyy.processdiary.update.UpdateRepository.isEnabled())
        } finally {
            hosts.clear(); hosts.addAll(before)
        }
    }

    fun testViewStateRestoredAndSaved() {
        val st = ProcessViewState.get(project).state
        st.scope = "PORTS"; st.tree = true; st.field = "CWD"; st.sortCol = "MEM"; st.sortDesc = false
        val p1 = ProcessPanel(project)
        Disposer.register(testRootDisposable, p1)
        assertEquals(ProcessPanel.Scope.PORTS, p1.scopeBox.selectedItem)
        assertTrue(p1.treeBox.isSelected)
        assertEquals(ProcessPanel.Field.CWD, p1.fieldBox.selectedItem)
        assertEquals("Memory ▲", p1.table.columnModel.getColumn(4).headerValue)

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
