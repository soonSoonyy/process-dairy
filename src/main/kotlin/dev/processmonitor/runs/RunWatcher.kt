package dev.processmonitor.runs

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import dev.processmonitor.ProcessActions
import dev.processmonitor.ProcessMonitorService
import dev.processmonitor.model.Category
import dev.processmonitor.model.ProcInfo
import dev.processmonitor.settings.ProcessMonitorSettings
import dev.processmonitor.ui.Fmt
import dev.processmonitor.ui.ProcessToolWindowFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDE의 Run/Debug 실행을 지켜보다가
 *  1) "포트가 이미 사용 중"으로 실패하면 → 그 포트를 쓰는 프로세스를 알려주고 [종료하고 다시 실행]
 *  2) 실행을 멈춘 뒤에도 남아 있는 하위/분리된 프로세스가 있으면 → [모두 종료]
 */
class RunWatcher(private val project: Project) : ExecutionListener {
    private class Tracked(val pid: Long?, val startedAt: Long) {
        /** 실행 중에 관찰된 하위 프로세스 */
        val seen: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }

    private val tracked = ConcurrentHashMap<ProcessHandler, Tracked>()
    @Volatile private var sampler: ScheduledFuture<*>? = null

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val pid = pidOf(handler)
        tracked[handler] = Tracked(pid, System.currentTimeMillis())
        if (ProcessMonitorSettings.get().state.watchPortConflicts) handler.addProcessListener(PortConflictListener(project, env, pid))
        ensureSampler()
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        tracked[handler]?.let { sample(it) } // 멈추기 직전의 하위 프로세스 기록
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        val t = tracked.remove(handler) ?: return
        val name = env.runProfile.name
        // 종료 처리가 끝날 시간을 준 뒤 확인
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ checkLeftovers(name, t) }, 2500, TimeUnit.MILLISECONDS)
    }

    private fun pidOf(handler: ProcessHandler): Long? = try { (handler as? BaseProcessHandler<*>)?.process?.pid() } catch (_: Throwable) { null }

    private fun ensureSampler() {
        synchronized(this) {
            if (sampler != null) return
            sampler = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                if (tracked.isEmpty()) {
                    synchronized(this) { sampler?.cancel(false); sampler = null }
                } else tracked.values.forEach { sample(it) }
            }, 1, 2, TimeUnit.SECONDS)
        }
    }

    private fun sample(t: Tracked) {
        val pid = t.pid ?: return
        try { ProcessHandle.of(pid).ifPresent { h -> h.descendants().forEach { t.seen += it.pid() } } } catch (_: Exception) {}
    }

    private fun checkLeftovers(runName: String, t: Tracked) {
        if (project.isDisposed || !ProcessMonitorSettings.get().state.watchLeftovers) return
        val service = ProcessMonitorService.get()
        val procs = service.quickList()
        if (procs.isEmpty()) return
        val byPid = procs.associateBy { it.pid }
        val ideTree = ProcessHandle.current().descendants().map { it.pid().toInt() }.toList().toSet()
        val base = project.basePath
        val result = LinkedHashMap<Int, ProcInfo>()
        // 1) 실행 중에 하위 프로세스였는데 아직 살아 있는 것
        for (pid in t.seen) byPid[pid.toInt()]?.let { if (it.pid.toLong() != t.pid) result[it.pid] = it }
        // 2) 실행 중에 시작돼 부모와 분리된(고아) 프로세스 중 작업 폴더가 이 프로젝트인 것
        if (base != null) {
            for (p in procs) {
                val orphan = p.ppid <= 1 || byPid[p.ppid]?.name in setOf("launchd", "systemd", "init")
                if (orphan && p.startTime >= t.startedAt - 1000 && p.cwd.isNotEmpty() &&
                    (p.cwd == base || p.cwd.startsWith("$base/") || p.cwd.startsWith("$base\\"))) result[p.pid] = p
            }
        }
        val leftovers = result.values.filter { it.pid !in ideTree && !ProcessActions.isProtected(it.pid) && it.category != Category.SYSTEM }
        if (leftovers.isEmpty()) return
        // 남은 것들 중 부모-자식 관계면 최상위만 대상으로 (하위는 트리 종료로 함께 처리)
        val ids = leftovers.map { it.pid }.toSet()
        val roots = leftovers.filter { it.ppid !in ids }
        val list = leftovers.take(5).joinToString(", ") { "${StringUtil.escapeXmlEntities(it.name)} (${it.pid})" } +
            if (leftovers.size > 5) " 외 ${leftovers.size - 5}개" else ""
        NotificationGroupManager.getInstance().getNotificationGroup("Process Monitor")
            .createNotification(
                "실행을 멈췄지만 프로세스가 남아 있습니다",
                "'${StringUtil.escapeXmlEntities(runName)}' 실행이 끝났는데 ${leftovers.size}개가 아직 실행 중입니다:<br>$list",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("모두 종료") {
                AppExecutorUtil.getAppExecutorService().execute {
                    val r = ProcessActions.killNow(roots.map { it.pid }, force = false, tree = true, names = leftovers.associate { it.pid to it.name })
                    ProcessActions.notifyResult(project, r)
                    service.requestNow()
                }
            })
            .addAction(NotificationAction.createSimple("Process Monitor에서 보기") {
                ProcessToolWindowFactory.showPid(project, roots.first().pid)
            })
            .notify(project)
    }
}

/** 실행 로그를 읽다가 포트 충돌 메시지를 발견하면 알림 */
class PortConflictListener(
    private val project: Project,
    private val env: ExecutionEnvironment,
    private val ownPid: Long?,
) : ProcessListener {
    private val reported = AtomicBoolean(false)

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (reported.get()) return
        val port = event.text.lineSequence().firstNotNullOfOrNull { PortConflict.extractPort(it) } ?: return
        if (!reported.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute { report(port) }
    }

    private fun report(port: Int) {
        if (project.isDisposed) return
        val service = ProcessMonitorService.get()
        val own = ownPid?.let { pid -> buildSet { add(pid.toInt()); ProcessActions.descendantsOf(pid.toInt()).forEach { add(it) } } } ?: emptySet()
        val owners = service.listenersOnPort(port).filter { it !in own }
        if (owners.isEmpty()) { reported.set(false); return } // 확인 못 하면 조용히 넘어감
        val byPid = service.quickList().associateBy { it.pid }
        val desc = owners.joinToString("<br>") { pid ->
            val p = byPid[pid]
            if (p == null) "PID $pid" else "${StringUtil.escapeXmlEntities(p.name)} (PID $pid)" +
                (if (p.cwd.isNotEmpty()) " · ${StringUtil.escapeXmlEntities(Fmt.shortPath(p.cwd))}" else "")
        }
        val runName = env.runProfile.name
        val canKill = owners.none { ProcessActions.isProtected(it) }
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Process Monitor")
            .createNotification(
                "포트 $port 이(가) 이미 사용 중입니다",
                "'${StringUtil.escapeXmlEntities(runName)}' 실행이 포트 충돌로 실패한 것 같습니다. 지금 이 포트를 쓰는 프로세스:<br>$desc",
                NotificationType.WARNING,
            )
        if (canKill) {
            n.addAction(NotificationAction.createSimpleExpiring("종료하고 다시 실행") { killAndMaybeRestart(port, owners, byPid, restart = true) })
            n.addAction(NotificationAction.createSimpleExpiring("종료만") { killAndMaybeRestart(port, owners, byPid, restart = false) })
        }
        n.addAction(NotificationAction.createSimple("Process Monitor에서 보기") { ProcessToolWindowFactory.showPort(project, port) })
        n.notify(project)
    }

    private fun killAndMaybeRestart(port: Int, owners: List<Int>, byPid: Map<Int, ProcInfo>, restart: Boolean) {
        AppExecutorUtil.getAppExecutorService().execute {
            val names = owners.associateWith { byPid[it]?.name ?: "PID $it" }
            var r = ProcessActions.killNow(owners, force = false, tree = true, names = names)
            var free = ProcessActions.waitPortFree(port, 5000)
            if (!free) { // 정상 종료에 응답하지 않으면 강제 종료
                r = ProcessActions.killNow(owners, force = true, tree = true, names = names)
                free = ProcessActions.waitPortFree(port, 3000)
            }
            ProcessMonitorService.get().requestNow()
            if (!free) {
                ProcessActions.notify(project, "포트 $port 이(가) 아직 사용 중입니다. 권한이 없는 프로세스일 수 있습니다.<br>" + r.failed.take(3).joinToString("<br>"), NotificationType.ERROR)
                return@execute
            }
            if (restart) {
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) ExecutionUtil.restart(env) }, project.disposed)
                ProcessActions.notify(project, "포트 $port 을(를) 비우고 '${StringUtil.escapeXmlEntities(env.runProfile.name)}'을(를) 다시 실행합니다.", NotificationType.INFORMATION)
            } else {
                ProcessActions.notifyResult(project, r)
            }
        }
    }

    companion object {
        @Suppress("unused") private val LOG = logger<PortConflictListener>()
    }
}
