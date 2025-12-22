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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Timer
import java.util.TimerTask
import javax.swing.*
import javax.swing.event.DocumentEvent

class ApiSearchToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApiSearchPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class ApiSearchPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val endpointService = project.service<EndpointService>()
    private val resultList = JBList<ApiEndpoint>()
    private val searchField = JBTextField()
    private val statusLabel = JLabel("输入关键词搜索")
    private var debounceTimer: Timer? = null

    init {
        resultList.cellRenderer = ApiSearchCellRenderer()
        
        // 搜索面板
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("搜索: "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        
        add(searchPanel, BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        
        // 搜索监听
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                debounceSearch()
            }
        })
        
        // 键盘导航
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val size = resultList.model.size
                        if (size > 0) {
                            val newIndex = (resultList.selectedIndex + 1).coerceAtMost(size - 1)
                            resultList.selectedIndex = newIndex
                            resultList.ensureIndexIsVisible(newIndex)
                        }
                    }
                    KeyEvent.VK_UP -> {
                        val newIndex = (resultList.selectedIndex - 1).coerceAtLeast(0)
                        resultList.selectedIndex = newIndex
                        resultList.ensureIndexIsVisible(newIndex)
                    }
                    KeyEvent.VK_ENTER -> navigateToSelected()
                }
            }
        })
        
        // 双击跳转
        resultList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })
    }
    
    private fun debounceSearch() {
        debounceTimer?.cancel()
        debounceTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    SwingUtilities.invokeLater { performSearch() }
                }
            }, 150)
        }
    }
    
    private fun performSearch() {
        val query = searchField.text
        
        if (query.isBlank()) {
            resultList.model = DefaultListModel()
            statusLabel.text = "输入关键词搜索"
            return
        }
        
        if (endpointService.isIndexing()) {
            statusLabel.text = "正在索引，请稍候..."
            return
        }
        
        val results = endpointService.searchEndpoints(query)
        val maxResults = 50
        val displayResults = if (results.size > maxResults) results.take(maxResults) else results
        
        val newModel = DefaultListModel<ApiEndpoint>()
        displayResults.forEach { newModel.addElement(it) }
        resultList.model = newModel
        
        if (displayResults.isNotEmpty()) {
            resultList.selectedIndex = 0
        }
        
        statusLabel.text = if (results.size > maxResults) {
            "显示前 $maxResults 个，共 ${results.size} 个"
        } else {
            "找到 ${results.size} 个"
        }
    }
    
    private fun navigateToSelected() {
        val selected = resultList.selectedValue ?: return
        ApplicationManager.getApplication().invokeLater {
            val psiMethod = selected.psiMethod
            val containingFile = psiMethod.containingFile?.virtualFile ?: return@invokeLater
            val offset = psiMethod.textOffset
            val descriptor = OpenFileDescriptor(project, containingFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
    
    fun focusSearch() {
        searchField.requestFocusInWindow()
        searchField.selectAll()
    }
}

