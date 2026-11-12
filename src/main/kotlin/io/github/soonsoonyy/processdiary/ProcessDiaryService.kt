package io.github.soonsoonyy.processdiary

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.soonsoonyy.processdiary.data.Collector
import io.github.soonsoonyy.processdiary.model.ProcDetail
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.model.SystemStats
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 2초마다 시스템 사용량을 수집하는 앱 전체 공용 서비스.
 * 프로세스 목록은 보고 있는 화면(도구 창/팝업)이 있을 때만 수집해 IDE 부담을 줄인다.
 * 아래 상태 값들은 모두 EDT(UI 스레드)에서만 읽고 쓴다.
 */
@Service(Service.Level.APP)
class ProcessDiaryService : Disposable {
    companion object {
        const val HISTORY = 60          // 2초 × 60 = 최근 2분
        private val LOG = logger<ProcessDiaryService>()
        fun get(): ProcessDiaryService = service()
    }

    private class Subscriber(val needsProcesses: () -> Boolean, val onUpdate: () -> Unit)

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val lock = ReentrantLock()
    private var future: ScheduledFuture<*>? = null
    private val collector: Collector? by lazy {
        try { Collector() } catch (e: Throwable) { LOG.warn("시스템 정보 수집기를 초기화하지 못했습니다", e); error = e.message ?: e.toString(); null }
    }

    val history = ArrayDeque<SystemStats>()
    var processes: List<ProcInfo> = emptyList(); private set
    var byPid: Map<Int, ProcInfo> = emptyMap(); private set
    val cpuHistory = HashMap<Int, ArrayDeque<Double>>()
    val memHistory = HashMap<Int, ArrayDeque<Double>>()
    @Volatile var error: String? = null; private set
    val cores: Int get() = collector?.cores ?: Runtime.getRuntime().availableProcessors()
    val latest: SystemStats? get() = history.lastOrNull()

    fun subscribe(parent: Disposable, needsProcesses: () -> Boolean, onUpdate: () -> Unit) {
        val s = Subscriber(needsProcesses, onUpdate)
        subscribers += s
        Disposer.register(parent) { subscribers -= s }
        synchronized(this) { if (future == null) schedule() }
    }

    private fun schedule() {
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::tick, 0, ProcessDiarySettings.get().intervalMs, TimeUnit.MILLISECONDS)
    }

    /** 설정에서 갱신 주기를 바꾸면 다시 예약 */
    fun reschedule() = synchronized(this) {
        if (future != null) { future?.cancel(false); schedule() }
    }

    /** 그래프가 보여주는 기간 (예: "최근 2분") */
    fun historyLabel(): String {
        val sec = HISTORY * ProcessDiarySettings.get().intervalMs / 1000
        return if (sec % 60 == 0L) PD.msg("history.minutes", sec / 60) else PD.msg("history.seconds", sec)
    }

    /** 다음 주기를 기다리지 않고 바로 한 번 수집 */
    fun requestNow() = AppExecutorUtil.getAppExecutorService().execute(::tick)

    private fun tick() {
        if (subscribers.isEmpty() || !lock.tryLock()) return
        try {
            val c = collector ?: return
            val sys = c.system()
            val procs = if (subscribers.any { it.needsProcesses() }) c.processes(sys.memTotal) else null
            ApplicationManager.getApplication().invokeLater({ apply(sys, procs) }, ModalityState.any())
        } catch (e: Throwable) {
            LOG.warn(e)
        } finally {
            lock.unlock()
        }
    }

    private fun apply(sys: SystemStats, procs: List<ProcInfo>?) {
        history.addLast(sys)
        while (history.size > HISTORY) history.removeFirst()
        if (procs != null) {
            processes = procs
            byPid = procs.associateBy { it.pid }
            for (p in procs) {
                cpuHistory.getOrPut(p.pid) { ArrayDeque() }.also { it.addLast(p.cpu); if (it.size > HISTORY) it.removeFirst() }
                memHistory.getOrPut(p.pid) { ArrayDeque() }.also { it.addLast(p.rssBytes.toDouble()); if (it.size > HISTORY) it.removeFirst() }
            }
            cpuHistory.keys.retainAll(byPid.keys)
            memHistory.keys.retainAll(byPid.keys)
        }
        for (s in subscribers) {
            try { s.onUpdate() } catch (e: Throwable) { LOG.warn(e) }
        }
    }

    /** 포트를 쓰는 프로세스 PID (백그라운드 스레드에서 호출) */
    fun listenersOnPort(port: Int): List<Int> = try { collector?.listenersOnPort(port) ?: emptyList() } catch (e: Throwable) { LOG.debug(e); emptyList() }

    /** 현재 프로세스 목록을 바로 조회 (백그라운드 스레드에서 호출) */
    fun quickList(): List<ProcInfo> = try { collector?.quickList() ?: emptyList() } catch (e: Throwable) { LOG.debug(e); emptyList() }

    /** 선택한 프로세스 상세 정보 (백그라운드에서 조회 후 EDT로 전달) */
    fun loadDetail(pid: Int, onLoaded: (ProcDetail?) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val d = try { collector?.details(pid) } catch (e: Throwable) { LOG.debug(e); null }
            ApplicationManager.getApplication().invokeLater({ onLoaded(d) }, ModalityState.any())
        }
    }

    override fun dispose() {
        future?.cancel(false)
    }
}
