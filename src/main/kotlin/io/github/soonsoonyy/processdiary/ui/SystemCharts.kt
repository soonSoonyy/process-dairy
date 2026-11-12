package io.github.soonsoonyy.processdiary.ui

import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessDiaryService

/** CPU·메모리·디스크·네트워크 그래프 4개 묶음 (도구 창과 상태 표시줄 팝업에서 공용) */
class SystemCharts(height: Int = 74) {
    val cpu = SparkChart(listOf(Palette.CPU), height)
    val mem = SparkChart(listOf(Palette.MEM), height)
    val disk = SparkChart(listOf(Palette.DISK_R, Palette.DISK_W), height)
    val net = SparkChart(listOf(Palette.NET_RX, Palette.NET_TX), height)
    val all = listOf(cpu, mem, disk, net)

    fun update(s: ProcessDiaryService) {
        val h = s.history
        val last = h.lastOrNull() ?: return
        cpu.setData(listOf(h.map { it.cpu }), 100.0, "CPU", "%.0f%%".format(last.cpu),
            PD.msg("chart.cpu.sub", s.cores, s.historyLabel()), "100%")
        val memPct = h.map { if (it.memTotal > 0) it.memUsed * 100.0 / it.memTotal else 0.0 }
        mem.setData(listOf(memPct), 100.0, PD.msg("chart.mem"), "%.0f%%".format(memPct.last()),
            PD.msg("chart.mem.sub", Fmt.mem(last.memUsed), Fmt.mem(last.memTotal)), "100%")
        val dmax = Fmt.niceMax((h.map { it.diskRead } + h.map { it.diskWrite }).maxOrNull() ?: 0.0, 1048576.0)
        disk.setData(listOf(h.map { it.diskRead }, h.map { it.diskWrite }), dmax, PD.msg("chart.disk"),
            PD.msg("chart.disk.value", Fmt.bytes(last.diskRead), Fmt.bytes(last.diskWrite)),
            PD.msg("chart.disk.sub", Fmt.bytes(last.diskTotal - last.diskUsed), Fmt.bytes(last.diskTotal)),
            Fmt.bytes(dmax) + "/s", listOf(PD.msg("chart.disk.read"), PD.msg("chart.disk.write")))
        val nmax = Fmt.niceMax((h.map { it.netRx } + h.map { it.netTx }).maxOrNull() ?: 0.0, 65536.0)
        net.setData(listOf(h.map { it.netRx }, h.map { it.netTx }), nmax, PD.msg("chart.net"),
            "↓ ${Fmt.bytes(last.netRx)}/s · ↑ ${Fmt.bytes(last.netTx)}/s",
            PD.msg("chart.net.sub", Fmt.bytes(last.netRxTotal), Fmt.bytes(last.netTxTotal)),
            Fmt.bytes(nmax) + "/s", listOf(PD.msg("chart.net.rx"), PD.msg("chart.net.tx")))
    }
}
