package io.github.soonsoonyy.processdiary.data

import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.model.Conn
import io.github.soonsoonyy.processdiary.model.ProcDetail
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.model.SystemStats
import oshi.PlatformEnum
import oshi.SystemInfo
import oshi.software.os.InternetProtocolStats
import oshi.software.os.OSProcess
import oshi.software.os.OperatingSystem
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * OSHI로 시스템/프로세스 정보를 모은다. 이전 샘플과의 차이로 "현재" 사용률과 속도를 계산하므로
 * 한 스레드에서 순서대로 호출해야 한다 (ProcessDiaryService가 보장).
 */
class Collector {
    private val si = SystemInfo()
    private val hal = si.hardware
    val os: OperatingSystem = si.operatingSystem
    private val platform = SystemInfo.getCurrentPlatform()
    val cores: Int = hal.processor.logicalProcessorCount.coerceAtLeast(1)
    val homeDir: String = System.getProperty("user.home")

    private var prevTicks: LongArray = hal.processor.systemCpuLoadTicks
    private var prevProcTimes: Map<Int, Long> = emptyMap()
    private var prevProcAt = 0L
    private var prevIo: LongArray? = null
    private var prevIoAt = 0L
    private var portCache: Map<Int, List<Int>> = emptyMap()
    private var portAt = 0L

    fun system(): SystemStats {
        val now = System.currentTimeMillis()
        val cpu = (hal.processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100).coerceIn(0.0, 100.0)
        prevTicks = hal.processor.systemCpuLoadTicks
        val mem = hal.memory
        var dr = 0L
        var dw = 0L
        for (d in hal.diskStores) {
            dr += d.readBytes
            dw += d.writeBytes
        }
        var rx = 0L
        var tx = 0L
        for (n in hal.getNetworkIFs(false)) { // false = 루프백/가상 인터페이스 제외
            rx += n.bytesRecv
            tx += n.bytesSent
        }
        val prev = prevIo
        val dt = (now - prevIoAt) / 1000.0
        fun rate(i: Int, v: Long) = if (prev != null && dt > 0.2) (v - prev[i]).coerceAtLeast(0) / dt else 0.0
        val home = File(homeDir) // 사용자 데이터가 있는 볼륨 (맥: Data 볼륨)
        val stats = SystemStats(
            now, cpu, mem.total, mem.total - mem.available,
            rate(0, dr), rate(1, dw), home.totalSpace, home.totalSpace - home.usableSpace,
            rate(2, rx), rate(3, tx), rx, tx,
        )
        prevIo = longArrayOf(dr, dw, rx, tx)
        prevIoAt = now
        return stats
    }

    fun processes(memTotal: Long): List<ProcInfo> {
        val now = System.currentTimeMillis()
        val elapsed = now - prevProcAt
        val ports = ports(now)
        val list = os.getProcesses(null, null, 0)
        val times = HashMap<Int, Long>(list.size * 2)
        val out = ArrayList<ProcInfo>(list.size)
        for (p in list) {
            val pid = p.processID
            val t = p.kernelTime + p.userTime
            times[pid] = t
            val prev = prevProcTimes[pid]
            // 누적 CPU 시간의 증가분 / 경과 시간 = 최근 사용률 (첫 샘플은 평균값으로 대체)
            val cpu = if (prev != null && elapsed in 300..120_000) (t - prev).coerceAtLeast(0) * 100.0 / elapsed
            else p.processCpuLoadCumulative * 100.0
            val path = p.path ?: ""
            val name = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { p.name ?: "?" }
            val rss = p.residentSetSize
            out += ProcInfo(
                pid = pid, ppid = p.parentProcessID, name = name, path = path, user = p.user ?: "",
                cpu = cpu.coerceIn(0.0, cores * 100.0), rssBytes = rss,
                memPct = if (memTotal > 0) rss * 100.0 / memTotal else 0.0,
                upTimeMs = p.upTime, startTime = p.startTime, cwd = cwdOf(p),
                category = Describe.classify(path, name), ports = ports[pid] ?: emptyList(),
            )
        }
        prevProcTimes = times
        prevProcAt = now
        return out
    }

    private fun cwdOf(p: OSProcess): String = try { p.currentWorkingDirectory ?: "" } catch (_: Throwable) { "" }

    /** pid → LISTEN 중인 TCP 포트 (5초 캐시) */
    private fun ports(now: Long): Map<Int, List<Int>> {
        if (now - portAt < 5000) return portCache
        portAt = now
        val m = HashMap<Int, MutableSet<Int>>()
        try {
            for (c in os.internetProtocolStats.connections) {
                if (c.state == InternetProtocolStats.TcpState.LISTEN && c.type.startsWith("tcp") && c.getowningProcessId() > 0) {
                    m.getOrPut(c.getowningProcessId()) { sortedSetOf() }.add(c.localPort)
                }
            }
        } catch (_: Throwable) {
        }
        if (m.isEmpty() && platform != PlatformEnum.WINDOWS) lsofListen(m)
        portCache = m.mapValues { it.value.sorted() }
        return portCache
    }

    private fun lsofListen(m: MutableMap<Int, MutableSet<Int>>) {
        var pid = -1
        for (l in exec("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pn").lineSequence()) {
            if (l.startsWith("p")) pid = l.substring(1).toIntOrNull() ?: -1
            else if (l.startsWith("n") && pid > 0) l.substringAfterLast(':').toIntOrNull()?.let { m.getOrPut(pid) { sortedSetOf() }.add(it) }
        }
    }

    /** 지금 이 포트에서 LISTEN 중인 프로세스 PID (캐시 없이 바로 조회) */
    fun listenersOnPort(port: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        try {
            for (c in os.internetProtocolStats.connections) {
                if (c.localPort == port && c.state == InternetProtocolStats.TcpState.LISTEN && c.getowningProcessId() > 0) {
                    out += c.getowningProcessId()
                }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty() && platform != PlatformEnum.WINDOWS) {
            exec("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t").lineSequence().mapNotNull { it.trim().toIntOrNull() }.forEach { out += it }
        }
        return out.toList()
    }

    /**
     * CPU 계산 상태를 건드리지 않는 가벼운 프로세스 목록 (다른 스레드에서 호출해도 됨).
     * CPU 값은 0으로 채운다.
     */
    fun quickList(): List<ProcInfo> = os.getProcesses(null, null, 0).map { p ->
        val path = p.path ?: ""
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { p.name ?: "?" }
        ProcInfo(p.processID, p.parentProcessID, name, path, p.user ?: "", 0.0, p.residentSetSize, 0.0,
            p.upTime, p.startTime, cwdOf(p), Describe.classify(path, name), emptyList())
    }

    /** 선택한 프로세스의 실행 명령과 네트워크 연결 (느릴 수 있으니 백그라운드에서 호출) */
    fun details(pid: Int): ProcDetail? {
        val p = os.getProcess(pid) ?: return null
        val conns = ArrayList<Conn>()
        try {
            for (c in os.internetProtocolStats.connections) {
                if (c.getowningProcessId() != pid) continue
                val state = c.state.name.takeUnless { it == "NONE" || it == "UNKNOWN" } ?: ""
                conns += Conn(c.type.uppercase(), addr(c.localAddress, c.localPort),
                    if (c.foreignPort > 0) addr(c.foreignAddress, c.foreignPort) else "", state)
            }
        } catch (_: Throwable) {
        }
        if (conns.isEmpty() && platform == PlatformEnum.MACOS) conns += lsofConns(pid)
        conns.sortBy { it.state != "LISTEN" }
        val cmd = try { p.commandLine ?: "" } catch (_: Throwable) { "" }
        return ProcDetail(pid, cmd, stateText(p.state), conns.take(40))
    }

    private fun lsofConns(pid: Int): List<Conn> {
        val out = ArrayList<Conn>()
        var proto = ""
        var name = ""
        var state = ""
        fun flush() { if (proto.isNotEmpty()) out += Conn(proto, name.substringBefore("->"), name.substringAfter("->", ""), state) }
        for (l in exec("lsof", "-a", "-p", pid.toString(), "-i", "-nP", "-F", "PnT").lineSequence()) {
            when {
                l.startsWith("P") -> { flush(); proto = l.substring(1); name = ""; state = "" }
                l.startsWith("n") -> name = l.substring(1)
                l.startsWith("TST=") -> state = l.substring(4)
            }
        }
        flush()
        return out
    }

    private fun addr(bytes: ByteArray?, port: Int): String {
        if (bytes == null || bytes.isEmpty() || bytes.all { it.toInt() == 0 }) return "*:$port"
        return try {
            val a = InetAddress.getByAddress(bytes).hostAddress
            if (':' in a) "[$a]:$port" else "$a:$port"
        } catch (_: Exception) { "?:$port" }
    }

    private fun stateText(s: OSProcess.State?) = when (s) {
        OSProcess.State.RUNNING -> PD.msg("state.running")
        OSProcess.State.SLEEPING -> PD.msg("state.sleeping")
        OSProcess.State.WAITING -> PD.msg("state.waiting")
        OSProcess.State.STOPPED -> PD.msg("state.stopped")
        OSProcess.State.ZOMBIE -> PD.msg("state.zombie")
        OSProcess.State.NEW -> PD.msg("state.new")
        else -> PD.msg("state.unknown")
    }

    private fun exec(vararg cmd: String): String = try {
        val pr = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val text = pr.inputStream.bufferedReader().readText()
        pr.waitFor(5, TimeUnit.SECONDS)
        text
    } catch (_: Exception) { "" }
}
