package io.github.nanoforged.sdg.nanoforge

/**
 * nanoforge.mod.toml / coremod.toml 的写出工具。
 *
 * 两个文件的字段集都是稳定的字符串/字符串数组/整数，手写序列化即可，
 * 不引入 night-config 写侧依赖（它仅存在于 NanoForge 运行时读侧）。
 */
internal object TomlWriter {

    fun string(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun stringArray(values: List<String>): String =
        values.joinToString(", ", "[", "]") { string(it) }

    /** `key = value` 行。 */
    fun entry(key: String, value: String): String = "$key = $value"
}
