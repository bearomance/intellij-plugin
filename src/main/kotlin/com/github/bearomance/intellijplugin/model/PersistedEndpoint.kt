package com.github.bearomance.intellijplugin.model

import com.intellij.util.xmlb.annotations.Tag

/**
 * 可持久化的端点数据（不包含 PsiMethod 引用）
 */
@Tag("endpoint")
data class PersistedEndpoint(
    var method: String = "",
    var path: String = "",
    var className: String = "",
    var methodName: String = "",
    var moduleName: String = "",
    var filePath: String = "",       // 文件路径，用于重新定位 PsiMethod
    var methodSignature: String = "" // 方法签名，用于精确定位
)

/**
 * 持久化的索引状态
 */
data class EndpointIndexState(
    var endpoints: MutableList<PersistedEndpoint> = mutableListOf(),
    var fileTimestamps: MutableMap<String, Long> = mutableMapOf(), // 文件路径 -> 最后修改时间
    var lastIndexTime: Long = 0L
)

