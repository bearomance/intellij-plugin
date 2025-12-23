package com.github.bearomance.intellijplugin.services

import com.github.bearomance.intellijplugin.model.EndpointIndexState
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * 持久化索引数据到 .idea/api-search-index.xml
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ApiSearchIndex",
    storages = [Storage("api-search-index.xml")]
)
class EndpointIndexPersistence : PersistentStateComponent<EndpointIndexState> {
    
    private var state = EndpointIndexState()
    
    override fun getState(): EndpointIndexState = state
    
    override fun loadState(state: EndpointIndexState) {
        this.state = state
    }
    
    companion object {
        fun getInstance(project: Project): EndpointIndexPersistence {
            return project.service<EndpointIndexPersistence>()
        }
    }
}

