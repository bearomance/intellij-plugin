package com.github.bearomance.intellijplugin.startup

import com.github.bearomance.intellijplugin.services.EndpointService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动时初始化 EndpointService，触发索引构建
 */
class EndpointIndexStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 触发服务初始化，服务的 init 块会自动开始索引
        project.service<EndpointService>()
    }
}

