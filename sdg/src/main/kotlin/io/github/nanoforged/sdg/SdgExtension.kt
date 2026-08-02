package io.github.nanoforged.sdg

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import javax.inject.Inject

/**
 * SDG 核心 DSL（`sdg { ... }`）。
 *
 * 模组元数据（modId/modVersion 等）是 mod_info.json 与 nanoforge.mod.toml 的共同事实源，
 * 见 docs/design/nanoforge-mod-toml-v1.md。
 */
abstract class SdgExtension @Inject constructor(objects: ObjectFactory) {

    /** 模组唯一 id，对应 mod_info.json 的 `id`。必填。 */
    val modId: Property<String> = objects.property(String::class.java)

    /** 模组版本，默认取 project.version。 */
    val modVersion: Property<String> = objects.property(String::class.java)

    /** 展示名，默认与 modId 一致。 */
    val modName: Property<String> = objects.property(String::class.java)

    /** 作者。 */
    val author: Property<String> = objects.property(String::class.java)

    /** 描述。 */
    val description: Property<String> = objects.property(String::class.java)

    /** `BaseModPlugin` 入口类全限定名。 */
    val modPlugin: Property<String> = objects.property(String::class.java)

    /** 模组依赖声明（写入 mod_info.json `dependencies`）。 */
    val dependencies: ListProperty<ModDependency> = objects.listProperty(ModDependency::class.java)

    /** 声明一条模组依赖。 */
    fun dependency(id: String, name: String? = null, version: String? = null) {
        dependencies.add(ModDependency(id, name, version))
    }

    /** 目标游戏版本（如 `0.98a-RC8`），[GameDependencyMode.NAMED_REPO] 模式必填。 */
    val gameVersion: Property<String> = objects.property(String::class.java)

    /** 本机游戏安装目录。[GameDependencyMode.GAME_DIR] 模式必填；第三方 mod 依赖桥亦依赖它。 */
    val gameDir: DirectoryProperty = objects.directoryProperty()

    /** 部署目录名（mods/<deployDirName>），默认与 modId 一致；解耦用于规避大小写冲突。 */
    val deployDirName: Property<String> = objects.property(String::class.java)

    /** 静态内容目录（data/、graphics/ 等原样同步进产物），默认 `<工程>/contents`。 */
    val contentsDir: DirectoryProperty = objects.directoryProperty()

    /** deployMod 时是否将本模组写入 `mods/enabled_mods.json`，默认 false。 */
    val manageEnabledMods: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /** SourceSector named 仓目录，默认 `<根工程上级>/SourceSector/build/named-game-repo/windows`。 */
    val sourceRepo: DirectoryProperty = objects.directoryProperty()

    /** 游戏依赖来源模式，默认 [GameDependencyMode.NAMED_REPO]。 */
    val gameDependencyMode: Property<GameDependencyMode> =
        objects.property(GameDependencyMode::class.java).convention(GameDependencyMode.NAMED_REPO)

    companion object {
        /** SourceSector 发布的 4 个 named 游戏 jar 坐标。 */
        const val NAMED_GAME_GROUP = "starsector.named"
        val NAMED_GAME_ARTIFACTS = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")
    }
}
