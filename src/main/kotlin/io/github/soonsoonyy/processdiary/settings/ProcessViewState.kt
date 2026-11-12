package io.github.soonsoonyy.processdiary.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** 프로젝트별로 기억하는 도구 창 보기 상태 (.idea/workspace.xml, 공유되지 않음) */
@Service(Service.Level.PROJECT)
@State(name = "ProcessDiaryView", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProcessViewState : SimplePersistentStateComponent<ProcessViewState.Options>(Options()) {
    class Options : BaseState() {
        /** null이면 설정의 "처음 보기 범위" 사용 */
        var scope by string(null)
        var tree by property(false)
        var field by string("ALL")
        var sortCol by string("CPU")
        var sortDesc by property(true)
        var live by property(false)
        var charts by property(true)
        var columnWidths by list<Int>()
    }

    companion object {
        fun get(project: Project): ProcessViewState = project.service()
    }
}
