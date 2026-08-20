package io.github.nanoforged.sdg

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * 游戏依赖装配 DSL（`starsectorDeps { ... }`）。
 *
 * 轻量插件 [SdgGameDepsPlugin] 的配置入口：供非 mod 工程（如 SSOptimizer 的功能模块）
 * 直接 apply 使用，也可由 mod 插件把 `starsector {}` 的值桥接进来。默认值与
 * `-P` 属性覆盖逻辑见 [SdgGameDepsPlugin]。
 */
abstract class SdgGameDepsExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * SourceSector named 仓目录，默认 `<根工程上级>/SourceSector/build/named-game-repo/windows`，
     * 可用 `-Psourcesector.namedRepo=` 覆盖（绝对路径或相对根工程的路径）。
     */
    val sourceRepo: DirectoryProperty = objects.directoryProperty()

    /** 目标游戏版本，默认 `0.98a-RC8`，可用 `-Pstarsector.gameVersion` 覆盖。 */
    val gameVersion: Property<String> = objects.property(String::class.java)

    /**
     * gameLibraries 装配时排除的 artifact 名（`starsector.game` 组目录名）。
     *
     * 默认排除 `xstream-1.4.10`：游戏安装目录残留的 xstream-1.4.10 与运行期实际使用的
     * miko 补丁版 xstream-1.4.21 类重复且 API 不兼容，必须排除以免编译/运行期错配。
     */
    val gameLibraryExcludes: ListProperty<String> =
        objects.listProperty(String::class.java).convention(listOf("xstream-1.4.10"))

    /**
     * 是否装配 named 仓与 gameLibraries，默认 true。
     * 消费方需要完全关闭 named 装配时置 false（如 mod 插件的 [GameDependencyMode.GAME_DIR] 模式）。
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
