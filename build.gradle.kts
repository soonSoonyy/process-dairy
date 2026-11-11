import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.processmonitor"
version = "1.0.0"

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
    }
}

tasks.test {
    // 테스트에서 OSHI가 IDE에 포함된 JNA 네이티브 라이브러리를 찾도록
    val jnaDir = intellijPlatform.platformPath.resolve("lib/jna").toFile().listFiles()?.firstOrNull()
    if (jnaDir != null) systemProperty("jna.boot.library.path", jnaDir.absolutePath)
    systemProperty("processmonitor.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
