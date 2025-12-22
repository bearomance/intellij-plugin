package com.github.bearomance.intellijplugin.action

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import com.github.bearomance.intellijplugin.services.EndpointService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Timer
import java.util.TimerTask
import javax.swing.*
import javax.swing.event.DocumentEvent

class SearchApiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).showDumbModeNotification("正在索引项目，请稍候...")
            return
        }
        
        showSearchPopup(project)
    }

    private fun showSearchPopup(project: Project) {
        val endpointService = project.service<EndpointService>()
        val resultList = JBList<ApiEndpoint>()
        val searchField = JBTextField()
        
        resultList.cellRenderer = ApiPopupCellRenderer()
        
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(600, 400)
            
            // 搜索框
            val searchPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(JLabel("Search API:  "), BorderLayout.WEST)
                add(searchField, BorderLayout.CENTER)
            }
            
            add(searchPanel, BorderLayout.NORTH)
            add(JBScrollPane(resultList), BorderLayout.CENTER)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setTitle("Search API Endpoints")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        // 防抖定时器
        var debounceTimer: Timer? = null

        // 状态标签
        val logger = com.intellij.openapi.diagnostic.Logger.getInstance(SearchApiAction::class.java)

        val statusLabel = JLabel(if (endpointService.isIndexing()) "正在索引..." else "准备就绪")
        panel.add(statusLabel, BorderLayout.SOUTH)

        logger.info("Popup opened, isIndexing=${endpointService.isIndexing()}")

        // 搜索逻辑（直接从缓存读取，不阻塞）
        fun performSearch() {
            logger.info("performSearch called on thread: ${Thread.currentThread().name}")
            val query = searchField.text

            if (endpointService.isIndexing()) {
                logger.info("Still indexing, skip search")
                statusLabel.text = "正在索引，请稍候..."
                return
            }

            logger.info("Searching for: $query")
            val startTime = System.currentTimeMillis()
            val results = endpointService.searchEndpoints(query)
            logger.info("Search took ${System.currentTimeMillis() - startTime}ms, found ${results.size} results")

            // 限制显示数量
            val maxResults = 20
            val displayResults = if (results.size > maxResults) results.take(maxResults) else results

            // 批量更新：先构建新模型，再替换
            val newModel = DefaultListModel<ApiEndpoint>()
            displayResults.forEach { newModel.addElement(it) }
            resultList.model = newModel

            if (displayResults.isNotEmpty()) {
                resultList.selectedIndex = 0
            }

            statusLabel.text = if (results.size > maxResults) {
                "显示前 $maxResults 个，共 ${results.size} 个端点"
            } else {
                "找到 ${results.size} 个端点"
            }
            logger.info("UI updated")
        }

        // 防抖搜索
        fun debounceSearch() {
            debounceTimer?.cancel()
            debounceTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        SwingUtilities.invokeLater {
                            performSearch()
                        }
                    }
                }, 150)
            }
        }

        // 跳转逻辑
        fun navigateToSelected() {
            val selected = resultList.selectedValue ?: return
            popup.cancel()
            ApplicationManager.getApplication().invokeLater {
                val psiMethod = selected.psiMethod
                val containingFile = psiMethod.containingFile?.virtualFile ?: return@invokeLater
                val offset = psiMethod.textOffset
                val descriptor = OpenFileDescriptor(project, containingFile, offset)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }

        // 搜索框监听（使用防抖）
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                debounceSearch()
            }
        })

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
                    KeyEvent.VK_ESCAPE -> popup.cancel()
                }
            }
        })

        // 双击跳转
        resultList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })

        // 弹窗关闭时清理
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                debounceTimer?.cancel()
            }
        })

        popup.showCenteredInCurrentWindow(project)
    }
}

