package dev.processmonitor.ui

import dev.processmonitor.ProcessMonitorService

/** CPU·메모리·디스크·네트워크 그래프 4개 묶음 (도구 창과 상태 표시줄 팝업에서 공용) */
class SystemCharts(height: Int = 74) {
    val cpu = SparkChart(listOf(Palette.CPU), height)
    val mem = SparkChart(listOf(Palette.MEM), height)
    val disk = SparkChart(listOf(Palette.DISK_R, Palette.DISK_W), height)
    val net = SparkChart(listOf(Palette.NET_RX, Palette.NET_TX), height)
    val all = listOf(cpu, mem, disk, net)

    fun update(s: ProcessMonitorService) {
        val h = s.history
        val last = h.lastOrNull() ?: return
        cpu.setData(listOf(h.map { it.cpu }), 100.0, "CPU", "%.0f%%".format(last.cpu),
            "논리 코어 ${s.cores}개 · ${s.historyLabel()}", "100%")
        val memPct = h.map { if (it.memTotal > 0) it.memUsed * 100.0 / it.memTotal else 0.0 }
        mem.setData(listOf(memPct), 100.0, "메모리", "%.0f%%".format(memPct.last()),
            "${Fmt.mem(last.memUsed)} / ${Fmt.mem(last.memTotal)} 사용", "100%")
        val dmax = Fmt.niceMax((h.map { it.diskRead } + h.map { it.diskWrite }).maxOrNull() ?: 0.0, 1048576.0)
        disk.setData(listOf(h.map { it.diskRead }, h.map { it.diskWrite }), dmax, "디스크",
            "읽기 ${Fmt.bytes(last.diskRead)}/s · 쓰기 ${Fmt.bytes(last.diskWrite)}/s",
            "여유 ${Fmt.bytes(last.diskTotal - last.diskUsed)} / ${Fmt.bytes(last.diskTotal)}",
            Fmt.bytes(dmax) + "/s", listOf("읽기", "쓰기"))
        val nmax = Fmt.niceMax((h.map { it.netRx } + h.map { it.netTx }).maxOrNull() ?: 0.0, 65536.0)
        net.setData(listOf(h.map { it.netRx }, h.map { it.netTx }), nmax, "네트워크",
            "↓ ${Fmt.bytes(last.netRx)}/s · ↑ ${Fmt.bytes(last.netTx)}/s",
            "부팅 후 받음 ${Fmt.bytes(last.netRxTotal)} · 보냄 ${Fmt.bytes(last.netTxTotal)}",
            Fmt.bytes(nmax) + "/s", listOf("받기", "보내기"))
    }
}
