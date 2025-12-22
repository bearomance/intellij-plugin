package com.github.bearomance.intellijplugin.action

import com.github.bearomance.intellijplugin.toolWindow.ApiSearchPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager

class SearchApiAction : AnAction(), DumbAware {

    companion object {
        // 记录每个项目之前活跃的右侧 Tool Window
        private val previousToolWindowMap = mutableMapOf<Project, String?>()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val apiSearchWindow = toolWindowManager.getToolWindow("API Search") ?: return

        if (apiSearchWindow.isVisible) {
            // 关闭 API Search
            apiSearchWindow.hide()

            // 恢复之前的 Tool Window
            val previousId = previousToolWindowMap[project]
            if (previousId != null) {
                toolWindowManager.getToolWindow(previousId)?.show()
                previousToolWindowMap.remove(project)
            }
        } else {
            // 记录当前右侧活跃的 Tool Window
            val currentActiveId = toolWindowManager.toolWindowIds
                .mapNotNull { toolWindowManager.getToolWindow(it) }
                .firstOrNull { it.isVisible && it.anchor == ToolWindowAnchor.RIGHT && it.id != "API Search" }
                ?.id
            previousToolWindowMap[project] = currentActiveId

            // 打开 API Search 并聚焦搜索框
            apiSearchWindow.show {
                val content = apiSearchWindow.contentManager.getContent(0)
                (content?.component as? ApiSearchPanel)?.focusSearch()
            }
        }
    }
}

