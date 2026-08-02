package io.github.nanoforged.sdg

import groovy.json.JsonSlurper
import java.io.File

/**
 * [ModJarIndex] 默认实现。
 *
 * StarSector 生态的 mod_info.json 事实标准允许 `#` 行注释与尾逗号，
 * 解析前做与 Asteria `DependencyConfig.kt` 一致的宽松化预处理。
 */
class ModJarIndexImpl : ModJarIndex {

    override fun scan(modsDir: File, excludeModId: String?, onWarn: (String) -> Unit): Map<String, List<File>> {
        val result = linkedMapOf<String, List<File>>()
        val modDirs = modsDir.listFiles { file -> file.isDirectory } ?: return result
        for (modDir in modDirs) {
            val infoFile = modDir.resolve("mod_info.json")
            if (!infoFile.isFile) continue

            val parsed = try {
                parseLenient(infoFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                onWarn("解析 ${infoFile.absolutePath} 失败，跳过该模组：${e.message}")
                continue
            }

            val modId = parsed["id"] as? String
            if (modId.isNullOrBlank()) {
                onWarn("${infoFile.absolutePath} 缺少 id 字段，跳过该模组。")
                continue
            }
            if (modId == excludeModId) continue

            val jarPaths = (parsed["jars"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val jarFiles = jarPaths.map { modDir.resolve(it) }.filter { it.isFile }
            if (jarFiles.isNotEmpty()) {
                result[modId] = jarFiles
            }
        }
        return result
    }

    /** 去除 `#` 行注释与尾逗号后按 JSON 解析。 */
    private fun parseLenient(content: String): Map<*, *> {
        val cleaned = content
            .replace(Regex("(?m)#.*$"), "")
            .replace(Regex(",(\\s*[}\\]])"), "$1")
        return JsonSlurper().parseText(cleaned) as Map<*, *>
    }
}
