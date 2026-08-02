package io.github.nanoforged.sdg.nanoforge

/**
 * nanoforge.mod.toml `[libraries]` 段的一条依赖库声明（契约见 docs/design/nanoforge-mod-toml-v1.md §3）。
 *
 * deobf 形态模组的第三方库不 shadow，由本声明经 NanoForge 运行时统一解析；
 * [providedBy] 目前仅定义值 `"game"`：游戏自带库，运行时跳过解析。
 */
data class NanoForgeLibrary(
    val group: String,
    val artifact: String,
    val version: String,
    val providedBy: String? = null,
) : java.io.Serializable {

    /** maven 坐标记法 `g:a:v`。 */
    val notation: String get() = "$group:$artifact:$version"

    /** 解析产物 jar 的期望文件名（local maven / release 形态）。 */
    val jarFileName: String get() = "$artifact-$version.jar"

    companion object {
        /** 从 `g:a:v` 记法解析；格式非法直接抛错（元数据是唯一事实源，不允许静默兜底）。 */
        fun parse(notation: String, providedBy: String? = null): NanoForgeLibrary {
            val parts = notation.split(':')
            require(parts.size == 3 && parts.all { it.isNotBlank() }) {
                "nanoforge.library 坐标必须是 group:artifact:version 形式：$notation"
            }
            require(providedBy == null || providedBy == "game") {
                "nanoforge.library 的 providedBy 目前仅定义值 \"game\"：$providedBy"
            }
            return NanoForgeLibrary(parts[0], parts[1], parts[2], providedBy)
        }
    }
}
