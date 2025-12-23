package com.github.bearomance.intellijplugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * API 搜索配置
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ApiSearchSettings",
    storages = [Storage("api-search-settings.xml")]
)
class ApiSearchSettings : PersistentStateComponent<ApiSearchSettings.State> {

    data class State(
        // 服务名前缀列表，如 ["user", "user-web", "order"]
        var servicePrefixes: MutableList<String> = mutableListOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * 获取服务名前缀列表
     */
    fun getServicePrefixes(): List<String> = state.servicePrefixes.toList()

    /**
     * 设置服务名前缀列表
     */
    fun setServicePrefixes(prefixes: List<String>) {
        state.servicePrefixes = prefixes.filter { it.isNotBlank() }.toMutableList()
    }

    companion object {
        fun getInstance(project: Project): ApiSearchSettings {
            return project.service<ApiSearchSettings>()
        }
    }
}

