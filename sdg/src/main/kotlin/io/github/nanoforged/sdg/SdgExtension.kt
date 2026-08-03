package io.github.nanoforged.sdg

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

/**
 * SDG 核心 DSL（`starsector { ... }`）。
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

    /** 产物形态，默认 [ArtifactMode.DEOBF]；[ArtifactMode.OBF] 启用 reobf + shadow 产物链。 */
    val artifactMode: Property<ArtifactMode> =
        objects.property(ArtifactMode::class.java).convention(ArtifactMode.DEOBF)

    /** 映射平台（决定 mappings 表构件 artifactId），默认 windows（全平台唯一基准）。 */
    val mappingPlatform: Property<String> =
        objects.property(String::class.java).convention("windows")

    /** 全量 tiny 表文件直指定（过渡路径）；不设置时从 sourceRepo 解析 mappings 表构件。 */
    val mappingFile: RegularFileProperty = objects.fileProperty()

    /** runGame 启动模式，默认随 artifactMode：OBF→VANILLA，DEOBF→NANOFORGE。 */
    val launchMode: Property<LaunchMode> = objects.property(LaunchMode::class.java)

    /** VANILLA 模式的启动配置文件（jvmArgs/classpath 清单），默认 `<工程>/launch-config.json`。 */
    val launchConfigFile: RegularFileProperty = objects.fileProperty()

    /** JDWP / IDEA attach 端口（`-Pstarsector.debug=true` 启用），默认 5005。 */
    val debugPort: Property<Int> = objects.property(Int::class.java).convention(5005)

    /** NANOFORGE 模式堆大小（Xms=Xmx），默认 4g（launch-spec 脚本基线 16g 面向生产）。 */
    val heap: Property<String> = objects.property(String::class.java).convention("4g")

    /** 反编译器版本，默认 1.12.0（与 SourceSector 对齐）。 */
    val decompilerVersion: Property<String> = objects.property(String::class.java).convention("1.12.0")

    /** decompileDependencies 输出目录，默认 `<工程>/dev-resources/sources`。 */
    val decompiledSourcesDir: DirectoryProperty = objects.directoryProperty()

    companion object {
        /** SourceSector 发布的 4 个 named 游戏 jar 坐标。 */
        const val NAMED_GAME_GROUP = "starsector.named"
        val NAMED_GAME_ARTIFACTS = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")
    }
}
