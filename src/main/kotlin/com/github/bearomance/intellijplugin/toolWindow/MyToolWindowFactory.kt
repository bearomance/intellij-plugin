package com.github.bearomance.intellijplugin.toolWindow

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import com.github.bearomance.intellijplugin.services.EndpointService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApiSearchPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

class ApiSearchPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val searchField = JBTextField()
    private val listModel = DefaultListModel<ApiEndpoint>()
    private val resultList = JBList(listModel)
    private val endpointService = project.service<EndpointService>()
    private val statusLabel = JLabel("输入 URL 路径搜索 API 端点")

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // 搜索框面板
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            add(JLabel("搜索 API: "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }

        // 结果列表
        resultList.cellRenderer = ApiEndpointCellRenderer()
        val scrollPane = JBScrollPane(resultList)

        // 状态栏
        statusLabel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

        add(searchPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        // 搜索框输入监听
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                performSearch()
            }
        })

        // 搜索框键盘监听：上下键移动到列表，回车跳转
        searchField.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        if (listModel.size() > 0) {
                            resultList.selectedIndex = 0
                            resultList.requestFocusInWindow()
                        }
                    }
                    java.awt.event.KeyEvent.VK_UP -> {
                        if (listModel.size() > 0) {
                            resultList.selectedIndex = listModel.size() - 1
                            resultList.requestFocusInWindow()
                        }
                    }
                    java.awt.event.KeyEvent.VK_ENTER -> {
                        if (listModel.size() > 0) {
                            if (resultList.selectedIndex < 0) {
                                resultList.selectedIndex = 0
                            }
                            navigateToMethod(resultList.selectedValue)
                        }
                    }
                }
            }
        })

        // 双击跳转
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = resultList.selectedValue
                    if (selected != null) {
                        navigateToMethod(selected)
                    }
                }
            }
        })

        // 列表键盘监听：回车跳转，ESC 返回搜索框
        resultList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER -> {
                        val selected = resultList.selectedValue
                        if (selected != null) {
                            navigateToMethod(selected)
                        }
                    }
                    java.awt.event.KeyEvent.VK_ESCAPE -> {
                        searchField.requestFocusInWindow()
                    }
                }
            }
        })
    }

    private fun performSearch() {
        val query = searchField.text

        // 检查索引状态
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            statusLabel.text = "正在索引项目，请稍候..."
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = endpointService.searchEndpoints(query)
            SwingUtilities.invokeLater {
                listModel.clear()
                results.forEach { listModel.addElement(it) }
                statusLabel.text = when {
                    query.isBlank() -> "输入 URL 路径搜索 API 端点"
                    results.isEmpty() -> "未找到匹配的 API 端点（确保项目包含 Spring Controller）"
                    else -> "找到 ${results.size} 个端点"
                }
            }
        }
    }

    private fun navigateToMethod(endpoint: ApiEndpoint) {
        ApplicationManager.getApplication().invokeLater {
            val psiMethod = endpoint.psiMethod
            val containingFile = psiMethod.containingFile?.virtualFile ?: return@invokeLater
            val offset = psiMethod.textOffset
            val descriptor = OpenFileDescriptor(project, containingFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}

class ApiEndpointCellRenderer : ListCellRenderer<ApiEndpoint> {
    private val label = JLabel()

    override fun getListCellRendererComponent(
        list: JList<out ApiEndpoint>,
        value: ApiEndpoint?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        if (value != null) {
            label.text = """
                <html>
                <span style="color:#CC7832"><b>[${value.method}]</b></span>
                ${value.path} →
                <i>${value.className}.${value.methodName}()</i>
                <span style="color:#6A8759">[${value.moduleName}]</span>
                </html>
            """.trimIndent().replace("\n", "")
        }
        label.isOpaque = true
        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
        } else {
            label.background = list.background
            label.foreground = list.foreground
        }
        label.border = BorderFactory.createEmptyBorder(3, 5, 3, 5)
        return label
    }
}
