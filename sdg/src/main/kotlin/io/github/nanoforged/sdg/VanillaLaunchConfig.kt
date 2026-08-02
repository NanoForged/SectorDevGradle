package io.github.nanoforged.sdg

import groovy.json.JsonSlurper
import java.io.File

/**
 * VANILLA 模式的启动配置（launch-config.json，格式与 Asteria 一致）：
 * `jvmArgs.common` + `jvmArgs.<osKey>` 两段 JVM 参数 + 游戏根目录相对 classpath 清单。
 */
data class VanillaLaunchConfig(
    val commonArgs: List<String>,
    val osArgs: List<String>,
    val classpath: List<String>,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(file: File, osKey: String): VanillaLaunchConfig {
            require(file.isFile) {
                "launch-config.json 不存在：${file.absolutePath}（VANILLA 启动模式必需，格式见 Asteria launch-config.json）"
            }
            val parsed = JsonSlurper().parse(file) as? Map<*, *>
                ?: throw IllegalArgumentException("${file.absolutePath} 顶层不是 JSON 对象。")
            val jvmArgs = parsed["jvmArgs"] as? Map<*, *>
                ?: throw IllegalArgumentException("${file.absolutePath} 缺少 jvmArgs 段。")
            val common = (jvmArgs["common"] as? List<*>)?.filterIsInstance<String>()
                ?: throw IllegalArgumentException("${file.absolutePath} 缺少 jvmArgs.common。")
            val os = (jvmArgs[osKey] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val classpath = (parsed["classpath"] as? List<*>)?.filterIsInstance<String>()
                ?: throw IllegalArgumentException("${file.absolutePath} 缺少 classpath 清单。")
            return VanillaLaunchConfig(common, os, classpath)
        }
    }
}
