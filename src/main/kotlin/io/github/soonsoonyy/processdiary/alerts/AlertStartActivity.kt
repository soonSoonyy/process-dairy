package io.github.soonsoonyy.processdiary.alerts

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.soonsoonyy.processdiary.ProcessDiaryService

/** 상태 표시줄 위젯을 꺼 둔 경우에도 CPU/메모리 경고가 동작하도록 수집 시작 */
class AlertStartActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ProcessDiaryService.get().start()
    }
}
