package dev.processmonitor.update

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import dev.processmonitor.settings.ProcessMonitorSettings

/**
 * GitHub Release를 IDE의 "플러그인 저장소"로 등록해 두면, 새 버전이 나올 때 IntelliJ가 업데이트를 알려준다.
 * (Settings → Plugins → ⚙ → Manage Plugin Repositories 에 들어가는 주소)
 */
object UpdateRepository {
    const val URL = "https://github.com/soonSoonyy/process-diary/releases/latest/download/updatePlugins.xml"

    fun isEnabled(): Boolean = UpdateSettings.getInstance().storedPluginHosts.contains(URL)

    fun setEnabled(enabled: Boolean) {
        val hosts = UpdateSettings.getInstance().storedPluginHosts
        if (enabled && URL !in hosts) hosts += URL
        if (!enabled) hosts.removeAll { it == URL }
    }
}

/** 프로젝트를 처음 열 때 한 번만 "자동 업데이트 켜기"를 제안 */
class UpdateRepositoryActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val o = ProcessMonitorSettings.get().state
        if (o.updatePrompted || UpdateRepository.isEnabled()) return
        o.updatePrompted = true
        NotificationGroupManager.getInstance().getNotificationGroup("Process Monitor")
            .createNotification(
                "Process Monitor 자동 업데이트",
                "새 버전이 나오면 IntelliJ가 알려주고 클릭 한 번으로 업데이트되도록 할까요?<br>" +
                    "(GitHub Release를 플러그인 저장소로 등록합니다. 설정에서 언제든 끌 수 있습니다.)",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("자동 업데이트 켜기") {
                UpdateRepository.setEnabled(true)
                NotificationGroupManager.getInstance().getNotificationGroup("Process Monitor")
                    .createNotification("자동 업데이트를 켰습니다. 새 버전이 나오면 IDE 알림과 Settings → Plugins에 표시됩니다.", NotificationType.INFORMATION)
                    .notify(project)
            })
            .addAction(NotificationAction.createSimpleExpiring("나중에") {})
            .notify(project)
    }
}
