package com.github.bearomance.intellijplugin.services

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import com.github.bearomance.intellijplugin.model.PersistedEndpoint
import com.github.bearomance.intellijplugin.settings.ApiSearchSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

@Service(Service.Level.PROJECT)
class EndpointService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(EndpointService::class.java)
    private val persistence by lazy { project.service<EndpointIndexPersistence>() }

    // 缓存扫描结果
    @Volatile
    private var cachedEndpoints: List<ApiEndpoint> = emptyList()

    @Volatile
    private var isScanning = false

    @Volatile
    private var isInitialized = false

    @Volatile
    private var lastIndexTime = 0L

    // 文件路径 -> 该文件的端点列表（用于增量更新）
    private val endpointsByFile = mutableMapOf<String, MutableList<ApiEndpoint>>()

    init {
        // 监听文件变化，自动刷新缓存
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // 收集变化的 Controller 文件
                    val changedFiles = events
                        .filter { event ->
                            val path = event.path
                            (path.endsWith(".java") || path.endsWith(".kt")) &&
                                (path.contains("Controller") || path.contains("Resource"))
                        }
                        .mapNotNull { it.file?.path }
                        .toSet()

                    if (changedFiles.isNotEmpty() && isInitialized) {
                        val now = System.currentTimeMillis()
                        if (now - lastIndexTime >= MIN_INDEX_INTERVAL_MS) {
                            logger.info("Detected ${changedFiles.size} controller file(s) changed, incremental update")
                            incrementalUpdateAsync(changedFiles)
                        } else {
                            logger.info("Skipping index rebuild, last index was ${(now - lastIndexTime) / 1000}s ago")
                        }
                    }
                }
            }
        )

        // 等待索引完成后，尝试从持久化加载或重建
        DumbService.getInstance(project).runWhenSmart {
            loadOrRebuildIndex()
        }
    }

    /**
     * 手动刷新索引（忽略时间限制，全量重建）
     */
    fun forceRebuildIndex() {
        logger.info("Force rebuild index requested")
        lastIndexTime = 0L
        rebuildIndexAsync(force = true)
    }

    /**
     * 从持久化加载索引，如果无效则重建
     */
    private fun loadOrRebuildIndex() {
        val state = persistence.state
        if (state.endpoints.isNotEmpty()) {
            logger.info("Loading ${state.endpoints.size} endpoints from persistence")

            // 从持久化数据恢复
            val restored = restoreEndpointsFromPersistence(state.endpoints)
            if (restored.isNotEmpty()) {
                cachedEndpoints = restored
                lastIndexTime = state.lastIndexTime
                isInitialized = true
                logger.info("Restored ${restored.size} endpoints from persistence")

                // 后台检查是否需要增量更新
                checkForUpdatesAsync()
                return
            }
        }

        // 持久化数据无效，全量重建
        rebuildIndexAsync()
    }

    /**
     * 异步重建索引（带进度条）
     */
    private fun rebuildIndexAsync(force: Boolean = false) {
        if (isScanning) {
            logger.info("Already scanning, skip")
            return
        }

        logger.info("Starting async index rebuild (force=$force)...")
        isScanning = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project, "Indexing API Endpoints", false
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = false
                        indicator.text = "Scanning controllers..."

                        val result = ReadAction.compute<List<ApiEndpoint>, Throwable> {
                            val endpoints = mutableListOf<ApiEndpoint>()
                            endpointsByFile.clear()
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
                                    val controllerEndpoints = scanControllerMethods(controller, classPath)
                                    endpoints.addAll(controllerEndpoints)

                                    // 按文件分组存储
                                    val filePath = controller.containingFile?.virtualFile?.path
                                    if (filePath != null) {
                                        endpointsByFile.getOrPut(filePath) { mutableListOf() }
                                            .addAll(controllerEndpoints)
                                    }
                                }
                            }
                            endpoints
                        }

                        indicator.fraction = 1.0
                        indicator.text = "Found ${result.size} endpoints"

                        cachedEndpoints = result
                        isInitialized = true
                        lastIndexTime = System.currentTimeMillis()

                        // 持久化索引
                        persistIndex(result)

                        logger.info("Index built: ${result.size} endpoints")
                    } catch (e: Exception) {
                        logger.error("Error during indexing", e)
                    } finally {
                        isScanning = false
                    }
                }
            }
        )
    }

    /**
     * 增量更新：只重新扫描变化的文件
     */
    private fun incrementalUpdateAsync(changedFiles: Set<String>) {
        if (isScanning) {
            logger.info("Already scanning, skip incremental update")
            return
        }

        logger.info("Starting incremental update for ${changedFiles.size} files...")
        isScanning = true

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project, "Updating API Index", false
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Updating changed files..."

                        ReadAction.run<Throwable> {
                            for (filePath in changedFiles) {
                                // 移除该文件的旧端点
                                endpointsByFile.remove(filePath)

                                // 重新扫描该文件
                                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: continue
                                val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: continue

                                val newEndpoints = mutableListOf<ApiEndpoint>()
                                for (child in psiFile.children) {
                                    if (child is PsiClass && isController(child)) {
                                        val classPath = getClassLevelPath(child)
                                        newEndpoints.addAll(scanControllerMethods(child, classPath))
                                    }
                                }

                                if (newEndpoints.isNotEmpty()) {
                                    endpointsByFile[filePath] = newEndpoints.toMutableList()
                                }
                            }
                        }

                        // 重建缓存
                        cachedEndpoints = endpointsByFile.values.flatten()
                        lastIndexTime = System.currentTimeMillis()

                        // 持久化
                        persistIndex(cachedEndpoints)

                        logger.info("Incremental update complete: ${cachedEndpoints.size} endpoints")
                    } catch (e: Exception) {
                        logger.error("Error during incremental update", e)
                    } finally {
                        isScanning = false
                    }
                }
            }
        )
    }

    /**
     * 检查是否有文件需要更新
     */
    private fun checkForUpdatesAsync() {
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project, "Checking API Index", false
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val state = persistence.state
                    val changedFiles = mutableSetOf<String>()

                    ReadAction.run<Throwable> {
                        for ((filePath, timestamp) in state.fileTimestamps) {
                            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                            if (vFile == null || vFile.timeStamp != timestamp) {
                                changedFiles.add(filePath)
                            }
                        }
                    }

                    if (changedFiles.isNotEmpty()) {
                        logger.info("Found ${changedFiles.size} changed files, triggering incremental update")
                        incrementalUpdateAsync(changedFiles)
                    }
                }
            }
        )
    }

    /**
     * 判断类是否是 Controller
     */
    private fun isController(psiClass: PsiClass): Boolean {
        return CONTROLLER_ANNOTATIONS.any { psiClass.hasAnnotation(it) }
    }

    /**
     * 持久化索引到磁盘
     */
    private fun persistIndex(endpoints: List<ApiEndpoint>) {
        val state = persistence.state
        state.endpoints.clear()
        state.fileTimestamps.clear()
        state.lastIndexTime = lastIndexTime

        for (endpoint in endpoints) {
            val filePath = endpoint.psiMethod.containingFile?.virtualFile?.path ?: continue
            val vFile = endpoint.psiMethod.containingFile?.virtualFile

            state.endpoints.add(PersistedEndpoint(
                method = endpoint.method,
                path = endpoint.path,
                className = endpoint.className,
                methodName = endpoint.methodName,
                moduleName = endpoint.moduleName,
                filePath = filePath,
                methodSignature = buildMethodSignature(endpoint.psiMethod)
            ))

            if (vFile != null) {
                state.fileTimestamps[filePath] = vFile.timeStamp
            }
        }

        logger.info("Persisted ${state.endpoints.size} endpoints")
    }

    /**
     * 从持久化数据恢复端点
     */
    private fun restoreEndpointsFromPersistence(persisted: List<PersistedEndpoint>): List<ApiEndpoint> {
        return ReadAction.compute<List<ApiEndpoint>, Throwable> {
            val result = mutableListOf<ApiEndpoint>()

            for (pe in persisted) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(pe.filePath) ?: continue
                val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: continue

                // 查找对应的方法
                val psiMethod = findMethodBySignature(psiFile, pe.className, pe.methodSignature)
                if (psiMethod != null) {
                    result.add(ApiEndpoint(
                        method = pe.method,
                        path = pe.path,
                        psiMethod = psiMethod,
                        className = pe.className,
                        methodName = pe.methodName,
                        moduleName = pe.moduleName
                    ))

                    // 重建 endpointsByFile
                    endpointsByFile.getOrPut(pe.filePath) { mutableListOf() }.add(result.last())
                }
            }

            result
        }
    }

    /**
     * 构建方法签名用于持久化
     */
    private fun buildMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(",") {
            it.type.canonicalText
        }
        return "${method.name}($params)"
    }

    /**
     * 根据签名查找方法
     */
    private fun findMethodBySignature(psiFile: com.intellij.psi.PsiFile, className: String, signature: String): PsiMethod? {
        for (child in psiFile.children) {
            if (child is PsiClass && child.name == className) {
                for (method in child.methods) {
                    if (buildMethodSignature(method) == signature) {
                        return method
                    }
                }
            }
        }
        return null
    }

    override fun dispose() {
        cachedEndpoints = emptyList()
    }

    companion object {
        private const val MIN_INDEX_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

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
     * - /api/user/info (如果 user 在服务名配置中，会同时搜索 /api/info)
     */
    fun searchEndpoints(query: String): List<ApiEndpoint> {
        val allEndpoints = cachedEndpoints

        if (query.isBlank()) return allEndpoints

        val normalizedQuery = query.trim().lowercase()

        // 解析 HTTP 方法和路径
        val (httpMethod, pathQuery) = parseQuery(normalizedQuery)

        // 生成搜索变体（包含去掉服务名前缀的版本）
        val searchQueries = generateSearchVariants(pathQuery)

        return allEndpoints.filter { endpoint ->
            // 标准化路径：将 {userId}, {id} 等统一为 {id}
            val pathLower = normalizePathForMatch(endpoint.path.lowercase())
            val methodLower = endpoint.methodName.lowercase()
            val moduleLower = endpoint.moduleName.lowercase()

            // 如果指定了 HTTP 方法，必须匹配
            val methodMatches = httpMethod == null || endpoint.method.lowercase() == httpMethod

            // 任意一个搜索变体匹配即可
            val contentMatches = searchQueries.any { searchQuery ->
                pathLower.contains(searchQuery) ||
                    methodLower.contains(searchQuery) ||
                    moduleLower.contains(searchQuery)
            }

            methodMatches && contentMatches
        }
    }

    /**
     * 生成搜索变体
     * 如果路径匹配 /api/{服务名}/xxx 格式，生成去掉服务名的变体
     * 例如：/api/user/info -> ["/api/user/info", "/api/info"]
     */
    private fun generateSearchVariants(path: String): List<String> {
        val variants = mutableListOf(path)
        val servicePrefixes = ApiSearchSettings.getInstance(project).getServicePrefixes()

        if (servicePrefixes.isEmpty()) return variants

        // 匹配 /api/{服务名}/xxx 格式
        for (prefix in servicePrefixes) {
            val prefixLower = prefix.lowercase()
            val pattern = "/api/$prefixLower/"

            if (path.contains(pattern)) {
                // 去掉服务名前缀：/api/user/info -> /api/info
                val withoutPrefix = path.replace("/api/$prefixLower/", "/api/")
                variants.add(withoutPrefix)
            }
        }

        return variants
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

