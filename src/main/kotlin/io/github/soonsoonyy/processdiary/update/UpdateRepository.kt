package io.github.soonsoonyy.processdiary.update

import io.github.soonsoonyy.processdiary.PD
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings

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
        val o = ProcessDiarySettings.get().state
        if (o.updatePrompted || UpdateRepository.isEnabled()) return
        o.updatePrompted = true
        NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(
                PD.msg("update.prompt.title"),
                PD.msg("update.prompt.body"),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(PD.msg("update.prompt.enable")) {
                UpdateRepository.setEnabled(true)
                NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
                    .createNotification(PD.msg("update.enabled"), NotificationType.INFORMATION)
                    .notify(project)
            })
            .addAction(NotificationAction.createSimpleExpiring(PD.msg("update.prompt.later")) {})
            .notify(project)
    }
}
