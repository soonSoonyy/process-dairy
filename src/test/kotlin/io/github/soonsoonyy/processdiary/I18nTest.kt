package io.github.soonsoonyy.processdiary

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.soonsoonyy.processdiary.data.Describe
import io.github.soonsoonyy.processdiary.model.Category
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.util.Properties

/** 영어/한국어 메시지 파일과 언어 전환 */
class I18nTest : BasePlatformTestCase() {
    private fun load(name: String) = Properties().apply {
        I18nTest::class.java.classLoader.getResourceAsStream("messages/$name")!!.reader(Charsets.UTF_8).use { load(it) }
    }

    override fun tearDown() {
        try { ProcessDiarySettings.get().state.language = "AUTO" } finally { super.tearDown() }
    }

    fun testBundlesHaveSameKeys() {
        val en = load("ProcessDiaryBundle.properties").stringPropertyNames()
        val ko = load("ProcessDiaryBundle_ko.properties").stringPropertyNames()
        assertEquals("영어에만 있는 키", emptySet<String>(), en - ko)
        assertEquals("한국어에만 있는 키", emptySet<String>(), ko - en)
        assertTrue(en.size > 150)
    }

    fun testSameParametersInBothLanguages() {
        val en = load("ProcessDiaryBundle.properties")
        val ko = load("ProcessDiaryBundle_ko.properties")
        val param = Regex("\\{\\d+}")
        for (k in en.stringPropertyNames()) {
            assertEquals("매개변수 불일치: $k", param.findAll(en.getProperty(k)).map { it.value }.toSet(),
                param.findAll(ko.getProperty(k)).map { it.value }.toSet())
        }
    }

    fun testLanguageSwitch() {
        val s = ProcessDiarySettings.get().state
        s.language = "EN"
        assertEquals("Port 8080 is already in use", PD.msg("conflict.title", 8080)) // 천 단위 쉼표 없이
        assertEquals("Sent a stop request to 12345 process(es).", PD.msg("kill.result.done", 12345))
        assertEquals("This project", PD.msg("scope.project"))
        assertEquals("A Node.js (JavaScript) program, often a development server.", Describe.describe("node", "/usr/bin/node", Category.DEV))
        assertEquals("Dev tool", Category.DEV.label)
        s.language = "KO"
        assertEquals("포트 8080이(가) 이미 사용 중입니다", PD.msg("conflict.title", 8080))
        assertEquals("이 프로젝트", PD.msg("scope.project"))
        assertEquals("\"Code\" 앱이 실행 중인 프로세스입니다.", Describe.describe("Electron", "/Applications/Code.app/Contents/MacOS/Electron", Category.APP))
        assertEquals("개발 도구", Category.DEV.label)
        assertEquals("최근 2분", PD.msg("history.minutes", 2))
        // 버전이 붙은 이름과 Helper
        assertTrue(Describe.describe("python3.13", "/usr/local/bin/python3.13", Category.DEV).startsWith("Python"))
        assertTrue(Describe.describe("Google Chrome Helper (Renderer)", "", Category.APP).contains("렌더러"))
        // 자동 = IDE 언어 (테스트 IDE는 영어)
        s.language = "AUTO"
        assertEquals("Stop", PD.msg("kill.verb"))
    }
}
