package io.github.soonsoonyy.processdiary.data

import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.model.Category

/** 프로세스 분류와 설명 (설명 글은 메시지 파일에) */
object Describe {
    private val SHELLS = setOf("zsh", "bash", "sh", "fish", "dash", "tcsh", "login", "cmd", "powershell", "pwsh")
    private val DEVS = setOf("python", "python3", "node", "ruby", "java", "go", "docker", "npm", "git", "ssh", "perl", "php",
        "cargo", "rustc", "swift", "clang", "gcc", "make", "vim", "nvim", "code", "idea", "idea64", "gradle", "mvn", "kotlin", "deno", "bun")

    /** 이름에서 .exe 제거 */
    fun baseName(name: String): String = name.removeSuffix(".exe").removeSuffix(".EXE")

    fun classify(path: String, name: String): Category {
        val n = baseName(name)
        val low = n.lowercase()
        val p = path.replace('\\', '/')
        val pl = p.lowercase()
        if (p.startsWith("/System/") || p.startsWith("/usr/libexec") || p.startsWith("/usr/sbin") || p.startsWith("/sbin") ||
            p.startsWith("/Library/Apple") || pl.startsWith("c:/windows/") || n in setOf("launchd", "kernel_task", "System", "Registry", "smss")) {
            return Category.SYSTEM
        }
        if (low in SHELLS) return Category.SHELL
        if (low in DEVS || low.replace(Regex("[-_]?[0-9][0-9.]*$"), "") in DEVS || low.startsWith("python") || p.startsWith("/opt/homebrew") || p.startsWith("/usr/local") ||
            pl.contains("/jetbrains/") || pl.contains("/jdk")) return Category.DEV
        if (p.contains(".app/") || pl.contains("/program files")) return Category.APP
        return Category.OTHER
    }

    /** 설명 사전: 메시지 파일의 desc.proc.<프로세스 이름> */
    private fun known(n: String): String? = PD.msgOrNull("desc.proc.$n")

    fun describe(name: String, path: String, cat: Category): String {
        val n = baseName(name)
        known(n)?.let { return it }
        // python3.13, node22 처럼 버전이 붙은 이름도 찾기
        val noVersion = n.replace(Regex("[-_]?[0-9][0-9.]*$"), "")
        (known(noVersion) ?: known(noVersion + "3"))?.let { return it }
        val low = n.lowercase()
        if ("helper" in low) {
            val base = n.replace(Regex("\\s*Helper.*$", RegexOption.IGNORE_CASE), "")
            val kind = when {
                "renderer" in low -> PD.msg("desc.helper.renderer")
                "gpu" in low -> PD.msg("desc.helper.gpu")
                "plugin" in low -> PD.msg("desc.helper.plugin")
                else -> ""
            }
            return PD.msg("desc.helper", base, kind)
        }
        return when (cat) {
            Category.SYSTEM -> if (n.endsWith("d") || n.endsWith("agent") || path.contains("/usr/libexec"))
                PD.msg("desc.system.daemon") else PD.msg("desc.system")
            Category.APP -> {
                val m = Regex("/([^/]+)\\.app/").find(path.replace('\\', '/'))
                PD.msg("desc.app", m?.groupValues?.get(1) ?: n)
            }
            Category.SHELL -> PD.msg("desc.shell")
            Category.DEV -> PD.msg("desc.dev")
            Category.OTHER -> PD.msg("desc.other")
        }
    }
}
