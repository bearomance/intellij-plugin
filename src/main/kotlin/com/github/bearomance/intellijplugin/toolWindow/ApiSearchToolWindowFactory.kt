package com.github.bearomance.intellijplugin.toolWindow

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import com.github.bearomance.intellijplugin.services.EndpointService
import com.github.bearomance.intellijplugin.settings.ApiSearchSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    companion object {
        private const val MAX_HISTORY = 10
    }

    private val endpointService = project.service<EndpointService>()
    private val resultList = JBList<ApiEndpoint>()
    private val searchField = JBTextField()
    private val statusLabel = JLabel("Enter keywords to search")
    private val historyPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
    private val searchHistory = mutableListOf<String>()
    private var debounceTimer: Timer? = null

    init {
        resultList.cellRenderer = ApiSearchCellRenderer()

        // 刷新按钮
        val refreshButton = JButton("↻").apply {
            toolTipText = "Refresh Index"
            addActionListener {
                endpointService.forceRebuildIndex()
                statusLabel.text = "Refreshing index..."
            }
        }

        // 设置按钮
        val settingsButton = JButton("⚙").apply {
            toolTipText = "Configure Service Prefixes"
            addActionListener {
                showSettingsDialog()
            }
        }

        // 按钮面板
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            add(refreshButton)
            add(settingsButton)
        }

        // 搜索面板
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
            add(JLabel("Search: "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
        }

        // 历史记录面板
        historyPanel.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)

        // 顶部面板（搜索 + 历史）
        val topPanel = JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(historyPanel, BorderLayout.CENTER)
        }

        add(topPanel, BorderLayout.NORTH)
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
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })

        updateHistoryPanel()
    }

    private fun addToHistory(query: String) {
        if (query.isBlank()) return
        searchHistory.remove(query)
        searchHistory.add(0, query)
        if (searchHistory.size > MAX_HISTORY) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
        updateHistoryPanel()
    }

    private fun updateHistoryPanel() {
        historyPanel.removeAll()
        if (searchHistory.isEmpty()) {
            historyPanel.isVisible = false
        } else {
            historyPanel.isVisible = true
            searchHistory.forEach { query ->
                val label = JLabel(query).apply {
                    foreground = java.awt.Color(100, 149, 237) // 蓝色
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            searchField.text = query
                            performSearch()
                        }
                        override fun mouseEntered(e: MouseEvent) {
                            foreground = java.awt.Color(65, 105, 225)
                        }
                        override fun mouseExited(e: MouseEvent) {
                            foreground = java.awt.Color(100, 149, 237)
                        }
                    })
                }
                historyPanel.add(label)
            }
        }
        historyPanel.revalidate()
        historyPanel.repaint()
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
            statusLabel.text = "Enter keywords to search"
            return
        }

        if (endpointService.isIndexing()) {
            statusLabel.text = "Indexing, please wait..."
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
            "Showing $maxResults of ${results.size} results"
        } else {
            "Found ${results.size} results"
        }
    }

    private fun navigateToSelected() {
        val selected = resultList.selectedValue ?: return
        // 添加到历史
        val query = searchField.text
        addToHistory(query)

        // 关闭 Tool Window
        ToolWindowManager.getInstance(project).getToolWindow("API Search")?.hide()

        ApplicationManager.getApplication().invokeLater {
            val psiMethod = selected.psiMethod
            val containingFile = psiMethod.containingFile?.virtualFile ?: return@invokeLater
            val offset = psiMethod.textOffset
            val descriptor = OpenFileDescriptor(project, containingFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    fun focusSearch() {
        // 清空搜索框和结果
        searchField.text = ""
        resultList.model = DefaultListModel()
        statusLabel.text = "Enter keywords to search"
        searchField.requestFocusInWindow()
    }

    /**
     * 显示服务名前缀配置对话框
     */
    private fun showSettingsDialog() {
        val settings = ApiSearchSettings.getInstance(project)
        val currentPrefixes = settings.getServicePrefixes().joinToString("\n")

        val textArea = JTextArea(currentPrefixes).apply {
            rows = 8
            columns = 30
            toolTipText = "Enter one service prefix per line, e.g.:\nuser\nuser-web\norder"
        }

        val panel = JPanel(BorderLayout()).apply {
            add(JLabel("<html>Service prefixes (one per line):<br><small>For /api/{prefix}/xxx → /api/xxx matching</small></html>"), BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            "Configure Service Prefixes",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            val prefixes = textArea.text
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            settings.setServicePrefixes(prefixes)
            statusLabel.text = "Saved ${prefixes.size} service prefix(es)"
        }
    }
}

