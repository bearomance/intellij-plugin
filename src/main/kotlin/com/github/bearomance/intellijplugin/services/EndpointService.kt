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
     * 异步重建索引（带进度条）
     */
    private fun rebuildIndexAsync() {
        if (isScanning) {
            logger.info("Already scanning, skip")
            return
        }

        logger.info("Starting async index rebuild...")
        isScanning = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project, "Indexing API Endpoints", false
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.text = "Scanning controllers..."

                    val result = ReadAction.compute<List<ApiEndpoint>, Throwable> {
                        val endpoints = mutableListOf<ApiEndpoint>()
                        val scope = GlobalSearchScope.allScope(project)
                        val psiFacade = JavaPsiFacade.getInstance(project)

                        val annotations = CONTROLLER_ANNOTATIONS
                        for ((index, controllerAnnotation) in annotations.withIndex()) {
                            indicator.fraction = index.toDouble() / annotations.size * 0.3

                            val annotationClass = psiFacade.findClass(controllerAnnotation, scope) ?: continue
                            val projectScope = GlobalSearchScope.projectScope(project)
                            val controllers = AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll()

                            for ((cIndex, controller) in controllers.withIndex()) {
                                indicator.text2 = controller.name ?: ""
                                indicator.fraction = 0.3 + (index.toDouble() / annotations.size +
                                    cIndex.toDouble() / controllers.size / annotations.size) * 0.7

                                val classPath = getClassLevelPath(controller)
                                endpoints.addAll(scanControllerMethods(controller, classPath))
                            }
                        }
                        endpoints
                    }

                    indicator.fraction = 1.0
                    indicator.text = "Found ${result.size} endpoints"

                    cachedEndpoints = result
                    isInitialized = true
                    isScanning = false
                    logger.info("Index built: ${result.size} endpoints")
                }
            }
        )
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
     *
     * 支持格式：
     * - /api/info
     * - POST /api/info
     * - /api/user/info (会同时搜索 /api/info)
     */
    fun searchEndpoints(query: String): List<ApiEndpoint> {
        val allEndpoints = cachedEndpoints

        if (query.isBlank()) return allEndpoints

        val normalizedQuery = query.trim().lowercase()

        // 解析 HTTP 方法和路径
        val (httpMethod, pathQuery) = parseQuery(normalizedQuery)

        // 搜索查询
        val searchQuery = pathQuery

        return allEndpoints.filter { endpoint ->
            // 标准化路径：将 {userId}, {id} 等统一为 {id}
            val pathLower = normalizePathForMatch(endpoint.path.lowercase())
            val methodLower = endpoint.methodName.lowercase()
            val moduleLower = endpoint.moduleName.lowercase()

            // 如果指定了 HTTP 方法，必须匹配
            val methodMatches = httpMethod == null || endpoint.method.lowercase() == httpMethod

            val contentMatches = pathLower.contains(searchQuery) ||
                methodLower.contains(searchQuery) ||
                moduleLower.contains(searchQuery)

            methodMatches && contentMatches
        }
    }

    /**
     * 解析查询，提取 HTTP 方法和路径
     * "POST /api/info" -> ("post", "/api/info")
     * "/api/info" -> (null, "/api/info")
     * "https://example.com/api/info?a=1" -> (null, "/api/info")
     */
    private fun parseQuery(query: String): Pair<String?, String> {
        val httpMethods = listOf("get", "post", "put", "delete", "patch")
        val parts = query.split(" ", limit = 2)

        val (method, rawPath) = if (parts.size == 2 && parts[0] in httpMethods) {
            Pair(parts[0], parts[1])
        } else {
            Pair(null, query)
        }

        // 清理路径：移除域名和查询参数
        val cleanPath = cleanUrl(rawPath)

        return Pair(method, cleanPath)
    }

    /**
     * 清理 URL，提取路径部分
     * "https://example.com/api/info?a=1" -> "/api/info"
     * "/api/info?a=1" -> "/api/info"
     * "/api/user/12345" -> "/api/user/{id}"
     */
    private fun cleanUrl(url: String): String {
        var path = url

        // 移除协议和域名 (https://xxx.com/api/... -> /api/...)
        val protocolPattern = Regex("^https?://[^/]+")
        path = protocolPattern.replace(path, "")

        // 移除查询参数 (?xxx=yyy)
        val queryIndex = path.indexOf('?')
        if (queryIndex != -1) {
            path = path.substring(0, queryIndex)
        }

        // 将纯数字路径段替换为占位符 (/user/12345 -> /user/{*})
        path = path.split("/").joinToString("/") { segment ->
            if (segment.matches(Regex("^\\d+$"))) "{*}" else segment
        }

        // 标准化所有占位符
        path = normalizePathForMatch(path)

        return path
    }

    /**
     * 标准化路径用于匹配
     * - {userId}, {id}, {} -> {*}
     * - // -> /{*}/
     */
    private fun normalizePathForMatch(path: String): String {
        var result = path
        // 将所有 {xxx} 或 {} 统一为 {*}
        result = result.replace(Regex("\\{[^}]*\\}"), "{*}")
        // 将 // 替换为 /{*}/
        result = result.replace("//", "/{*}/")
        return result
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

