package io.github.nanoforged.sdg.nanoforge

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * SDG NanoForge 支持插件（`io.github.nanoforged.sdg.nanoforge`）。
 *
 * 职责（按实现计划分轮接入）：
 * - R5：nanoforge.mod.toml 生成（含 [libraries] 依赖库元数据）、coremod.toml 生成与校验、
 *   mods/coremods 双落位部署
 * - R6：patch 工作流（消费 SourceSector patch-tool 构件）
 *
 * R0 仅提供插件骨架，不承担任何行为。
 */
class SdgNanoForgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // R0 骨架：行为自 R5 起逐轮接入，见 docs/design/architecture.md 第 4 节。
    }
}
