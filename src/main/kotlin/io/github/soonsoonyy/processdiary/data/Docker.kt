package io.github.soonsoonyy.processdiary.data

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import io.github.soonsoonyy.processdiary.model.Container
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.io.File

/**
 * docker 명령으로 컨테이너를 조회하고 시작/정지한다.
 * Docker Desktop, OrbStack, Colima, Rancher Desktop 처럼 docker 명령을 제공하는 것이면 모두 동작한다.
 * 모든 함수는 시간이 걸릴 수 있으니 백그라운드 스레드에서 호출한다.
 */
object Docker {
    sealed class Result {
        data class Ok(val containers: List<Container>) : Result()
        /** docker 명령을 찾지 못함 */
        object NotInstalled : Result()
        /** 명령은 있지만 실패 (대부분 Docker가 꺼져 있음) */
        data class Failed(val message: String) : Result()
    }

    private const val SEP = "\u001f" // 값 사이 구분자 (이름·상태 글에 나오지 않는 문자)
    private val FIELDS = listOf("{{.ID}}", "{{.Names}}", "{{.Image}}", "{{.State}}", "{{.Status}}", "{{.Ports}}",
        "{{.Label \"com.docker.compose.project\"}}", "{{.Label \"com.docker.compose.project.working_dir\"}}",
        "{{.Label \"com.docker.compose.service\"}}")

    /** 포트를 대신 열어 주는 Docker 쪽 프로세스 이름 (이 프로세스를 끝내면 Docker 자체가 멈출 수 있음) */
    private val PROXY_NAMES = setOf("docker-proxy", "com.docker.backend", "com.docker.vpnkit", "vpnkit", "vpnkit-bridge", "dockerd",
        "rootlesskit", "rootlesskit-docker-proxy", "wslrelay", "orbstack", "orbstack helper", "gvproxy", "limactl", "docker desktop", "rancher-desktop")

    fun isDockerProcess(name: String): Boolean = Describe.baseName(name).lowercase() in PROXY_NAMES

    @Volatile private var cachedCli: Pair<String, String?>? = null // (설정값, 찾은 경로)

    /** docker 실행 파일 경로 (설정 → PATH → 흔한 설치 위치 순) */
    fun cli(): String? {
        val configured = ProcessDiarySettings.get().state.dockerPath?.trim().orEmpty()
        cachedCli?.let { if (it.first == configured && (it.second == null || File(it.second!!).exists())) return it.second }
        val found = findCli(configured)
        cachedCli = configured to found
        return found
    }

    private fun findCli(configured: String): String? {
        if (configured.isNotEmpty()) return configured.takeIf { File(it).canExecute() }
        val exe = if (SystemInfo.isWindows) "docker.exe" else "docker"
        // IDE 가 읽어 둔 셸 환경의 PATH (맥에서 Dock 으로 IDE 를 열어도 /usr/local/bin 등이 포함됨)
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: ""
        path.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, exe) }.firstOrNull { it.isFile && it.canExecute() }
            ?.let { return it.absolutePath }
        val home = System.getProperty("user.home")
        val candidates = if (SystemInfo.isWindows) listOf(
            "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",
            "$home\\AppData\\Local\\Programs\\Rancher Desktop\\resources\\resources\\win32\\bin\\docker.exe",
        ) else listOf(
            "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "$home/.docker/bin/docker", "$home/.orbstack/bin/docker",
            "$home/.rd/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker", "/usr/bin/docker",
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun run(timeoutMs: Int, vararg args: String): Pair<Int, String> {
        val exe = cli() ?: return -1 to ""
        val cmd = GeneralCommandLine(exe).withParameters(*args)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE) // 셸의 DOCKER_HOST 등 반영
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        if (out.isTimeout) return -2 to "timeout"
        return out.exitCode to (if (out.exitCode == 0) out.stdout else out.stderr.ifBlank { out.stdout })
    }

    /** 컨테이너 목록 ([all] = 멈춘 것도) */
    fun list(all: Boolean = true): Result {
        if (cli() == null) return Result.NotInstalled
        val args = buildList { add("ps"); if (all) add("-a"); add("--no-trunc"); add("--format"); add(FIELDS.joinToString(SEP)) }
        val (code, text) = try { run(8000, *args.toTypedArray()) } catch (e: Exception) { return Result.Failed(e.message ?: e.toString()) }
        if (code != 0) return Result.Failed(firstLine(text))
        return Result.Ok(parsePs(text))
    }

    /** 이 포트를 게시(-p)한 실행 중 컨테이너 */
    fun containersOnPort(port: Int): List<Container> =
        (list(all = false) as? Result.Ok)?.containers?.filter { it.running && port in it.hostPorts } ?: emptyList()

    /** docker stats 한 번 (컨테이너 ID → CPU%, 메모리 글) */
    fun stats(): Map<String, Pair<Double, String>> {
        if (cli() == null) return emptyMap()
        val (code, text) = try { run(15000, "stats", "--no-stream", "--no-trunc", "--format", "{{.ID}}$SEP{{.CPUPerc}}$SEP{{.MemUsage}}") } catch (_: Exception) { return emptyMap() }
        if (code != 0) return emptyMap()
        return parseStats(text)
    }

    /** start / stop / restart / rm 등. 성공하면 null, 실패하면 오류 글 */
    fun command(verb: String, id: String): String? {
        val (code, text) = try { run(60000, verb, id) } catch (e: Exception) { return e.message ?: e.toString() }
        return if (code == 0) null else firstLine(text).ifEmpty { "exit $code" }
    }

    private fun firstLine(s: String) = s.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(300) ?: ""

    // ---------------------------------------------------------------- 결과 해석 (테스트 가능하게 분리)
    fun parsePs(text: String): List<Container> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split(SEP)
        if (f.size < 6) return@mapNotNull null
        Container(
            id = f[0].trim(), name = f[1].trim().substringBefore(','), image = f[2].trim(),
            state = f[3].trim().lowercase(), status = f[4].trim(), portsText = f[5].trim(),
            hostPorts = hostPorts(f[5]),
            composeProject = f.getOrNull(6)?.trim().orEmpty().noValue(),
            composeDir = f.getOrNull(7)?.trim().orEmpty().noValue(),
            composeService = f.getOrNull(8)?.trim().orEmpty().noValue(),
        )
    }.toList()

    /** 값이 없는 라벨은 "<no value>" 로 나옴 */
    private fun String.noValue() = if (this == "<no value>") "" else this

    private val portMap = Regex(":(\\d+)(?:-(\\d+))?->")

    /** "0.0.0.0:8080->80/tcp, :::8080->80/tcp, 0.0.0.0:9000-9001->9000-9001/tcp" → [8080, 9000, 9001] */
    fun hostPorts(ports: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (m in portMap.findAll(ports)) {
            val from = m.groupValues[1].toIntOrNull() ?: continue
            val to = m.groupValues[2].toIntOrNull() ?: from
            if (to - from in 0..100) for (p in from..to) out += p
        }
        return out.toList()
    }

    private val sizeRe = Regex("^([0-9.]+)\\s*([KMGT]?i?B)$", RegexOption.IGNORE_CASE)

    /** "45.1MiB" → "45 MB", "1.2GiB" → "1.2 GB" (표의 다른 메모리 값과 같은 모양) */
    fun size(text: String): String {
        val m = sizeRe.find(text) ?: return text
        val v = m.groupValues[1].toDoubleOrNull() ?: return text
        val mult = when (m.groupValues[2].uppercase().first()) { 'K' -> 1024.0; 'M' -> 1024.0 * 1024; 'G' -> 1024.0 * 1024 * 1024; 'T' -> 1024.0 * 1024 * 1024 * 1024; else -> 1.0 }
        val b = v * mult
        return when {
            b >= 1024.0 * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
            b >= 1024.0 * 1024 -> "${(b / (1024 * 1024)).toLong()} MB"
            else -> "${(b / 1024).toLong()} KB"
        }
    }

    fun parseStats(text: String): Map<String, Pair<Double, String>> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split(SEP)
        if (f.size < 3) return@mapNotNull null
        val cpu = f[1].trim().removeSuffix("%").toDoubleOrNull() ?: return@mapNotNull null
        f[0].trim() to (cpu to size(f[2].trim().substringBefore(" /")))
    }.toMap()
}
