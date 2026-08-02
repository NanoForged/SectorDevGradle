package io.github.nanoforged.sdg

/**
 * 游戏依赖来源模式。
 *
 * - [NAMED_REPO]：SourceSector 发布的本地 maven 仓（named jar + sources），deobf 工作区主路径。
 * - [GAME_DIR]：直接扫描本机游戏安装目录的 jar，兼容模式。
 */
enum class GameDependencyMode {
    NAMED_REPO,
    GAME_DIR,
}
