package com.github.bearomance.intellijplugin.action

import com.github.bearomance.intellijplugin.toolWindow.ApiSearchPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class SearchApiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("API Search") ?: return

        if (toolWindow.isVisible) {
            // 已打开则关闭
            toolWindow.hide()
        } else {
            // 未打开则打开并聚焦搜索框
            toolWindow.show {
                val content = toolWindow.contentManager.getContent(0)
                (content?.component as? ApiSearchPanel)?.focusSearch()
            }
        }
    }
}

