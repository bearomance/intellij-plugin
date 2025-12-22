package com.github.bearomance.intellijplugin.model

import com.intellij.psi.PsiMethod

/**
 * 表示一个 API 端点
 */
data class ApiEndpoint(
    val method: String,           // HTTP 方法: GET, POST, PUT, DELETE 等
    val path: String,             // 完整的 URL 路径
    val psiMethod: PsiMethod,     // 对应的 PSI 方法，用于跳转
    val className: String,        // Controller 类名
    val methodName: String,       // 方法名
    val moduleName: String        // 模块名称
) {
    val displayText: String
        get() = "[$method] $path → $className.$methodName() [$moduleName]"
}

