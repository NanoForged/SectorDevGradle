package io.github.nanoforged.sdg

import java.io.Serializable

/**
 * 模组依赖声明，对应 mod_info.json `dependencies[]` 的一项。
 * 与 nanoforge.mod.toml v1 契约的 `[[dependencies]]` 同源（见 docs/design/nanoforge-mod-toml-v1.md 2.1）。
 */
data class ModDependency(
    /** 依赖模组 id。 */
    val id: String,
    /** 展示名。 */
    val name: String? = null,
    /** 版本约束（原样透传，区间语法待契约超集化定义）。 */
    val version: String? = null,
) : Serializable
