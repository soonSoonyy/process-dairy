package io.github.soonsoonyy.processdiary

import com.intellij.DynamicBundle
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

const val BUNDLE = "messages.ProcessDiaryBundle"

/**
 * 화면 글자 (영어: ProcessDiaryBundle.properties, 한국어: ProcessDiaryBundle_ko.properties).
 * 기본은 IDE 언어를 따르고, 설정에서 한국어/English 로 고정할 수 있다.
 * 매개변수가 있는 메시지는 MessageFormat 규칙({0}, 작은따옴표는 '')을 따른다.
 */
object PD {
    private val dynamic = object : DynamicBundle(PD::class.java, BUNDLE) {}
    private val fixed = HashMap<Locale, ResourceBundle>()

    private fun fixedBundle(locale: Locale): ResourceBundle = synchronized(fixed) {
        fixed.getOrPut(locale) {
            ResourceBundle.getBundle(BUNDLE, locale, PD::class.java.classLoader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
        }
    }

    /** 설정의 언어: AUTO(IDE 언어) / KO / EN */
    private fun language(): String = try { ProcessDiarySettings.get().state.language ?: "AUTO" } catch (_: Throwable) { "AUTO" }

    fun msg(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): String {
        // 숫자는 그대로 글자로 (MessageFormat 은 8080 을 "8,080" 처럼 천 단위로 끊어 씀: 포트·PID에 맞지 않음)
        val args: Array<Any?> = params.map { if (it is Number) it.toString() else it }.toTypedArray()
        val raw = when (language()) {
            "KO" -> fixedBundle(Locale.KOREAN).getString(key)
            "EN" -> fixedBundle(Locale.ROOT).getString(key)
            else -> return dynamic.getMessage(key, *args)
        }
        return if (args.isEmpty()) raw else MessageFormat(raw, Locale.ROOT).format(args)
    }

    /** 있으면 메시지, 없으면 null (프로세스 설명 사전처럼 키가 있을 수도 없을 수도 있을 때) */
    fun msgOrNull(key: String): String? = try {
        when (language()) {
            "KO" -> fixedBundle(Locale.KOREAN).getString(key)
            "EN" -> fixedBundle(Locale.ROOT).getString(key)
            else -> if (dynamic.containsKey(key)) dynamic.getMessage(key) else null
        }
    } catch (_: java.util.MissingResourceException) { null }
}
