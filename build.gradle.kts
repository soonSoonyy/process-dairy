import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.processmonitor"
version = providers.gradleProperty("pluginVersion").get()

/** 릴리스 파일이 올라가는 GitHub 저장소 */
val repoUrl = "https://github.com/soonSoonyy/process-diary"

/** CHANGELOG.md에서 해당 버전의 항목("- ...")만 뽑기 */
fun changelogItems(v: String): List<String> = file("CHANGELOG.md").readLines()
    .dropWhile { !it.startsWith("## [$v]") }.drop(1)
    .takeWhile { !it.startsWith("## ") }
    .map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }

fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
fun changeNotesHtml(v: String) = changelogItems(v).joinToString("", "<ul>", "</ul>") { "<li>${escapeHtml(it)}</li>" }

repositories {
    // JetBrains 저장소를 먼저 조회 (IntelliJ 테스트 프레임워크 등)
    intellijPlatform { defaultRepositories() }
    mavenCentral()
}

dependencies {
    intellijPlatform {
        // 가장 낮은 지원 버전(2024.2)으로 빌드 → 이후 버전에서도 동작
        intellijIdeaCommunity("2024.2.5")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    // 시스템 정보(프로세스/CPU/메모리/디스크/네트워크/포트) - macOS·Windows·Linux 공통
    // JNA·SLF4J는 IDE에 이미 들어 있으므로 중복으로 넣지 않음 (2024.2 = JNA 5.14 → OSHI 6.4.x)
    implementation("com.github.oshi:oshi-core:6.4.13") {
        exclude(group = "net.java.dev.jna")
        exclude(group = "org.slf4j")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider { changeNotesHtml(project.version.toString()) } // IDE 플러그인 화면의 "What's New"
        ideaVersion {
            sinceBuild = "242"            // 2024.2 이상
            untilBuild = provider { null } // 상한 없음
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.5")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.3")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2024.2에 포함된 Kotlin 표준 라이브러리와 호환되도록
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        // 인터페이스 기본 메서드를 클래스에 복사하지 않음 → IDE 내부/실험적 API를 덮어쓰는 것으로 오인되지 않게
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

tasks.test {
    // 테스트에서 OSHI가 IDE에 포함된 JNA 네이티브 라이브러리를 찾도록
    val jnaDir = intellijPlatform.platformPath.resolve("lib/jna").toFile().listFiles()?.firstOrNull()
    if (jnaDir != null) systemProperty("jna.boot.library.path", jnaDir.absolutePath)
    systemProperty("processmonitor.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

/**
 * IDE가 읽는 플러그인 저장소 파일 (GitHub Release에 함께 올림).
 * IDE에 등록하는 주소: $repoUrl/releases/latest/download/updatePlugins.xml
 */
val generateUpdatePluginsXml by tasks.registering {
    group = "release"
    description = "Writes build/distributions/updatePlugins.xml for the custom plugin repository"
    val v = project.version.toString()
    val out = layout.buildDirectory.file("distributions/updatePlugins.xml")
    val notes = changeNotesHtml(v)
    inputs.property("version", v)
    inputs.file("CHANGELOG.md")
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="dev.processmonitor" url="$repoUrl/releases/download/v$v/process-monitor-intellij-$v.zip" version="$v">
            |    <idea-version since-build="242"/>
            |    <name>Process Monitor</name>
            |    <vendor url="$repoUrl">Process Monitor</vendor>
            |    <description><![CDATA[See running processes, ports and CPU / memory / disk / network usage inside the IDE. IDE 안에서 프로세스·포트·사용량을 보고 종료합니다.]]></description>
            |    <change-notes><![CDATA[$notes]]></change-notes>
            |  </plugin>
            |</plugins>
            |""".trimMargin()
        )
    }
}

/** CI에서 버전 확인용 */
val printVersion by tasks.registering {
    group = "release"
    val v = project.version.toString()
    doLast { println(v) }
}
