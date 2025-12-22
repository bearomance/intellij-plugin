package com.github.bearomance.intellijplugin.services

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

@Service(Service.Level.PROJECT)
class EndpointService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(EndpointService::class.java)

    // 缓存扫描结果
    @Volatile
    private var cachedEndpoints: List<ApiEndpoint> = emptyList()

    @Volatile
    private var isScanning = false

    @Volatile
    private var isInitialized = false

    init {
        // 监听文件变化，自动刷新缓存
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // 只关心 Java/Kotlin 文件变化
                    val hasRelevantChange = events.any { event ->
                        val path = event.path
                        path.endsWith(".java") || path.endsWith(".kt")
                    }
                    if (hasRelevantChange && isInitialized) {
                        logger.info("Detected file change, rebuilding index in background")
                        rebuildIndexAsync()
                    }
                }
            }
        )

        // 等待索引完成后，异步构建缓存
        DumbService.getInstance(project).runWhenSmart {
            rebuildIndexAsync()
        }
    }

    /**
     * 异步重建索引（使用非阻塞读取）
     */
    private fun rebuildIndexAsync() {
        if (isScanning) {
            logger.info("Already scanning, skip")
            return
        }

        logger.info("Starting async index rebuild...")
        isScanning = true

        ReadAction.nonBlocking<List<ApiEndpoint>> {
            logger.info("NonBlocking ReadAction started")
            val result = mutableListOf<ApiEndpoint>()
            val scope = GlobalSearchScope.allScope(project)
            val psiFacade = JavaPsiFacade.getInstance(project)

            for (controllerAnnotation in CONTROLLER_ANNOTATIONS) {
                logger.info("Searching for: $controllerAnnotation")
                val annotationClass = psiFacade.findClass(controllerAnnotation, scope)
                if (annotationClass == null) {
                    logger.info("Annotation class not found: $controllerAnnotation")
                    continue
                }

                val projectScope = GlobalSearchScope.projectScope(project)
                val controllers = AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll()
                logger.info("Found ${controllers.size} controllers")

                for (controller in controllers) {
                    val classPath = getClassLevelPath(controller)
                    result.addAll(scanControllerMethods(controller, classPath))
                }
            }
            logger.info("Scan complete: ${result.size} endpoints")
            result
        }
        .inSmartMode(project)
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { endpoints ->
            cachedEndpoints = endpoints
            isInitialized = true
            isScanning = false
            logger.info("Index built and cached: ${endpoints.size} endpoints")
        }
        .expireWith(this)
        .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() {
        cachedEndpoints = emptyList()
    }

    companion object {
        private val CONTROLLER_ANNOTATIONS = listOf(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
        )

        private val MAPPING_ANNOTATIONS = mapOf(
            "org.springframework.web.bind.annotation.RequestMapping" to listOf("GET", "POST", "PUT", "DELETE"),
            "org.springframework.web.bind.annotation.GetMapping" to listOf("GET"),
            "org.springframework.web.bind.annotation.PostMapping" to listOf("POST"),
            "org.springframework.web.bind.annotation.PutMapping" to listOf("PUT"),
            "org.springframework.web.bind.annotation.DeleteMapping" to listOf("DELETE"),
            "org.springframework.web.bind.annotation.PatchMapping" to listOf("PATCH")
        )
    }

    /**
     * 手动刷新缓存
     */
    fun refreshCache() {
        rebuildIndexAsync()
    }

    /**
     * 是否正在扫描
     */
    fun isIndexing(): Boolean = isScanning || !isInitialized

    /**
     * 根据 URL 搜索匹配的端点（直接从缓存读取，不阻塞）
     */
    fun searchEndpoints(query: String): List<ApiEndpoint> {
        val allEndpoints = cachedEndpoints

        if (query.isBlank()) return allEndpoints

        val normalizedQuery = query.trim().lowercase()

        return allEndpoints.filter { endpoint ->
            endpoint.path.lowercase().contains(normalizedQuery) ||
            endpoint.methodName.lowercase().contains(normalizedQuery) ||
            endpoint.moduleName.lowercase().contains(normalizedQuery)
        }
    }

    private fun getClassLevelPath(psiClass: PsiClass): String {
        val requestMapping = psiClass.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
        return requestMapping?.let { extractPath(it) } ?: ""
    }

    private fun scanControllerMethods(controller: PsiClass, classPath: String): List<ApiEndpoint> {
        val endpoints = mutableListOf<ApiEndpoint>()
        val moduleName = getModuleName(controller)

        for (method in controller.methods) {
            for ((annotationFqn, httpMethods) in MAPPING_ANNOTATIONS) {
                val annotation = method.getAnnotation(annotationFqn)
                if (annotation != null) {
                    val methodPath = extractPath(annotation)
                    val fullPath = normalizePath(classPath + methodPath)
                    val methods = extractHttpMethods(annotation, httpMethods)

                    for (httpMethod in methods) {
                        endpoints.add(
                            ApiEndpoint(
                                method = httpMethod,
                                path = fullPath,
                                psiMethod = method,
                                className = controller.name ?: "Unknown",
                                methodName = method.name,
                                moduleName = moduleName
                            )
                        )
                    }
                }
            }
        }

        return endpoints
    }

    private fun getModuleName(psiClass: PsiClass): String {
        val virtualFile = psiClass.containingFile?.virtualFile ?: return "Unknown"
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile)
        val fullName = module?.name ?: return "Unknown"

        // 从 "root.subProject.main" 中提取 "subProject"
        val parts = fullName.split(".")
        return when {
            parts.size >= 2 -> parts[parts.size - 2]  // 取倒数第二个（去掉 .main/.test）
            else -> fullName
        }
    }

    private fun extractPath(annotation: PsiAnnotation): String {
        // 尝试获取 value 或 path 属性
        val valueAttr = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")

        return when {
            valueAttr == null -> ""
            valueAttr.text.startsWith("{") -> {
                // 数组形式 {"path1", "path2"}，取第一个
                val text = valueAttr.text
                val match = Regex("\"([^\"]+)\"").find(text)
                match?.groupValues?.get(1) ?: ""
            }
            else -> valueAttr.text.removeSurrounding("\"")
        }
    }

    private fun extractHttpMethods(annotation: PsiAnnotation, defaultMethods: List<String>): List<String> {
        if (annotation.qualifiedName != "org.springframework.web.bind.annotation.RequestMapping") {
            return defaultMethods
        }

        val methodAttr = annotation.findAttributeValue("method") ?: return defaultMethods
        val text = methodAttr.text

        return if (text.contains("RequestMethod.")) {
            Regex("RequestMethod\\.(\\w+)").findAll(text).map { it.groupValues[1] }.toList()
        } else {
            defaultMethods
        }
    }

    private fun normalizePath(path: String): String {
        val normalized = "/" + path.replace("//", "/").trim('/')
        return if (normalized == "/") "/" else normalized
    }
}

