package io.github.soonsoonyy.processdiary.runs

import io.github.soonsoonyy.processdiary.PD
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
import io.github.soonsoonyy.processdiary.ProcessActions
import io.github.soonsoonyy.processdiary.ProcessDiaryService
import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.model.Category
import io.github.soonsoonyy.processdiary.model.Container
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import io.github.soonsoonyy.processdiary.ui.Fmt
import io.github.soonsoonyy.processdiary.ui.ProcessToolWindowFactory
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
        if (ProcessDiarySettings.get().state.watchPortConflicts) handler.addProcessListener(PortConflictListener(project, env, pid))
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
        if (project.isDisposed || !ProcessDiarySettings.get().state.watchLeftovers) return
        val service = ProcessDiaryService.get()
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
        val list = leftovers.take(5).joinToString(", ") { "${StringUtil.escapeXmlEntities(it.displayName)} (${it.pid})" } +
            if (leftovers.size > 5) " " + PD.msg("kill.confirm.more", leftovers.size - 5) else ""
        NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(
                PD.msg("leftover.title"),
                PD.msg("leftover.body", StringUtil.escapeXmlEntities(runName), leftovers.size) + "<br>" + list,
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring(PD.msg("leftover.killAll")) {
                AppExecutorUtil.getAppExecutorService().execute {
                    val r = ProcessActions.killNow(roots.map { it.pid }, force = false, tree = true, names = leftovers.associate { it.pid to it.name })
                    ProcessActions.notifyResult(project, r)
                    service.requestNow()
                }
            })
            .addAction(NotificationAction.createSimple(PD.msg("notify.showInToolWindow")) {
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
        val service = ProcessDiaryService.get()
        val own = ownPid?.let { pid -> buildSet { add(pid.toInt()); ProcessActions.descendantsOf(pid.toInt()).forEach { add(it) } } } ?: emptySet()
        val owners = service.listenersOnPort(port).filter { it !in own }
        val byPid = service.quickList().associateBy { it.pid }
        // Docker 가 게시한 포트면 프록시 프로세스가 아니라 컨테이너를 멈춰야 함
        // (리눅스에서 userland-proxy 를 끈 경우엔 포트를 가진 프로세스가 보이지 않음)
        val dockerish = owners.isEmpty() || owners.any { pid -> byPid[pid]?.let { Docker.isDockerProcess(it.name) } == true }
        val containers = if (dockerish) try { Docker.containersOnPort(port) } catch (_: Throwable) { emptyList() } else emptyList()
        if (containers.isNotEmpty()) { reportContainers(port, containers, owners.isNotEmpty()); return }
        if (owners.isEmpty()) { reported.set(false); return } // 확인 못 하면 조용히 넘어감
        val desc = owners.joinToString("<br>") { pid ->
            val p = byPid[pid]
            if (p == null) "PID $pid" else "${StringUtil.escapeXmlEntities(p.displayName)} (PID $pid)" +
                (if (p.cwd.isNotEmpty()) " · ${StringUtil.escapeXmlEntities(Fmt.shortPath(p.cwd))}" else "")
        }
        val runName = env.runProfile.name
        val canKill = owners.none { ProcessActions.isProtected(it) }
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(
                PD.msg("conflict.title", port),
                PD.msg("conflict.body", StringUtil.escapeXmlEntities(runName)) + "<br>" + desc,
                NotificationType.WARNING,
            )
        if (canKill) {
            n.addAction(NotificationAction.createSimpleExpiring(PD.msg("conflict.killRerun")) { killAndMaybeRestart(port, owners, byPid, restart = true) })
            n.addAction(NotificationAction.createSimpleExpiring(PD.msg("conflict.killOnly")) { killAndMaybeRestart(port, owners, byPid, restart = false) })
        }
        n.addAction(NotificationAction.createSimple(PD.msg("notify.showInToolWindow")) { ProcessToolWindowFactory.showPort(project, port) })
        n.notify(project)
    }

    private fun reportContainers(port: Int, containers: List<Container>, showInDiary: Boolean) {
        val runName = env.runProfile.name
        val desc = containers.joinToString("<br>") { c ->
            PD.msg("conflict.container", StringUtil.escapeXmlEntities(c.name), StringUtil.escapeXmlEntities(c.image))
        }
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(PD.msg("conflict.title", port), PD.msg("conflict.body", StringUtil.escapeXmlEntities(runName)) + "<br>" + desc, NotificationType.WARNING)
        n.addAction(NotificationAction.createSimpleExpiring(PD.msg("conflict.stopContainerRerun")) { stopContainers(port, containers, restart = true) })
        n.addAction(NotificationAction.createSimpleExpiring(PD.msg("conflict.stopContainer")) { stopContainers(port, containers, restart = false) })
        if (showInDiary) n.addAction(NotificationAction.createSimple(PD.msg("notify.showInToolWindow")) { ProcessToolWindowFactory.showPort(project, port) })
        n.notify(project)
    }

    private fun stopContainers(port: Int, containers: List<Container>, restart: Boolean) {
        AppExecutorUtil.getAppExecutorService().execute {
            val errors = containers.mapNotNull { c -> Docker.command("stop", c.id)?.let { "${c.name}: $it" } }
            val free = ProcessActions.waitPortFree(port, 10_000)
            ProcessDiaryService.get().requestNow()
            if (errors.isNotEmpty() || !free) {
                ProcessActions.notify(project, PD.msg("conflict.stillBusy", port) + "<br>" + errors.take(3).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }, NotificationType.ERROR)
                return@execute
            }
            if (restart) {
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) ExecutionUtil.restart(env) }, project.disposed)
                ProcessActions.notify(project, PD.msg("conflict.rerunning", port, StringUtil.escapeXmlEntities(env.runProfile.name)), NotificationType.INFORMATION)
            } else {
                ProcessActions.notify(project, PD.msg("docker.stopped", containers.joinToString(", ") { StringUtil.escapeXmlEntities(it.name) }), NotificationType.INFORMATION)
            }
        }
    }

    private fun killAndMaybeRestart(port: Int, owners: List<Int>, byPid: Map<Int, ProcInfo>, restart: Boolean) {
        AppExecutorUtil.getAppExecutorService().execute {
            val names = owners.associateWith { byPid[it]?.displayName ?: "PID $it" }
            var r = ProcessActions.killNow(owners, force = false, tree = true, names = names)
            var free = ProcessActions.waitPortFree(port, 5000)
            if (!free) { // 정상 종료에 응답하지 않으면 강제 종료
                r = ProcessActions.killNow(owners, force = true, tree = true, names = names)
                free = ProcessActions.waitPortFree(port, 3000)
            }
            ProcessDiaryService.get().requestNow()
            if (!free) {
                ProcessActions.notify(project, PD.msg("conflict.stillBusy", port) + "<br>" + r.failed.take(3).joinToString("<br>"), NotificationType.ERROR)
                return@execute
            }
            if (restart) {
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) ExecutionUtil.restart(env) }, project.disposed)
                ProcessActions.notify(project, PD.msg("conflict.rerunning", port, StringUtil.escapeXmlEntities(env.runProfile.name)), NotificationType.INFORMATION)
            } else {
                ProcessActions.notifyResult(project, r)
            }
        }
    }

    companion object {
        @Suppress("unused") private val LOG = logger<PortConflictListener>()
    }
}
