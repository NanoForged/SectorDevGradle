package io.github.nanoforged.sdg

/**
 * 模组产物形态。
 *
 * - [DEOBF]：named 字节码（默认），运行于 NanoForge 环境，第三方库不 shadow（见 R5 [libraries]）。
 * - [OBF]：reobf 后的混淆字节码 + 第三方库 shadow，适用于非 NanoForge 的原版游戏。
 */
enum class ArtifactMode {
    DEOBF,
    OBF,
}
