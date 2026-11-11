package dev.processmonitor

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import dev.processmonitor.settings.ProcessViewState
import dev.processmonitor.ui.ProcessPanel
import dev.processmonitor.ui.StatusPopupPanel
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 도구 창 화면과 상태 표시줄 팝업을 실제로 만들어 이미지로 그려본다 (헤드리스) */
class UiRenderTest : BasePlatformTestCase() {
    private val outDir = File(System.getProperty("processmonitor.screenshotDir", "build/screenshots")).apply { mkdirs() }

    private fun waitFor(timeoutMs: Long = 15000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
        assertTrue("condition not met in time", cond())
    }

    private fun layoutAll(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach { layoutAll(it) }
    }

    private fun render(c: Component, w: Int, h: Int, name: String) {
        c.setSize(w, h)
        repeat(3) { layoutAll(c) }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(outDir, name))
    }

    fun testToolWindowPanel() {
        val service = ProcessMonitorService.get()
        ProcessViewState.get(project).loadState(ProcessViewState.Options().apply { scope = "ALL" })
        val panel = ProcessPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.forceCollect = true
        service.requestNow()
        waitFor { panel.table.rowCount > 0 && service.history.size >= 3 }
        // 행 선택 → 상세 패널
        panel.table.setRowSelectionInterval(0, 0)
        waitFor { true }
        Thread.sleep(2500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        render(panel, 1500, 720, "toolwindow.png")
        assertTrue(File(outDir, "toolwindow.png").length() > 1000)
    }

    fun testStatusPopup() {
        val service = ProcessMonitorService.get()
        val popup = StatusPopupPanel(project) {}
        Disposer.register(testRootDisposable, popup)
        waitFor { service.processes.isNotEmpty() && service.history.size >= 2 }
        Thread.sleep(2200)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val size = popup.preferredSize
        render(popup, size.width, size.height, "status-popup.png")
    }
}
