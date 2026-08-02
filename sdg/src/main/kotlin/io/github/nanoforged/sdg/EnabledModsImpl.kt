package io.github.nanoforged.sdg

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * [EnabledMods] 默认实现。保留文件内其他键，仅维护 `enabledMods` 列表。
 */
class EnabledModsImpl : EnabledMods {

    override fun enable(gameDir: File, modId: String): Boolean {
        val file = gameDir.resolve("mods/enabled_mods.json")
        if (!file.isFile) return false

        val parsed = try {
            JsonSlurper().parse(file)
        } catch (e: Exception) {
            throw EnabledModsException("解析 ${file.absolutePath} 失败：${e.message}")
        }
        if (parsed !is MutableMap<*, *>) {
            throw EnabledModsException("${file.absolutePath} 顶层不是 JSON 对象。")
        }

        @Suppress("UNCHECKED_CAST")
        val json = parsed as MutableMap<String, Any?>
        val enabled = (json["enabledMods"] as? List<*>)?.map { it.toString() }?.toMutableList()
            ?: mutableListOf()
        if (modId in enabled) return false

        enabled.add(modId)
        json["enabledMods"] = enabled
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)))
        return true
    }
}
