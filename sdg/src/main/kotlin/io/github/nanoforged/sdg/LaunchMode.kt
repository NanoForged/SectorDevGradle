package io.github.nanoforged.sdg

/**
 * runGame 启动模式。
 *
 * - [NANOFORGE]：launch-spec 前置检查 + RFB Main + `--tweakClass NanoForgeBootstrap`（deobf 环境）。
 * - [VANILLA]：launch-config.json 驱动的原版直启（`com.fs.starfarer.StarfarerLauncher`，obf 环境）。
 */
enum class LaunchMode {
    NANOFORGE,
    VANILLA,
}
