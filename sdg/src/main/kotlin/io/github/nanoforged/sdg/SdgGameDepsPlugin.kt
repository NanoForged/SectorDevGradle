package io.github.nanoforged.sdg

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 游戏依赖装配轻量插件（`io.github.nanoforged.sectordevgradle.gamedeps`）。
 *
 * 把 mod 插件内部的「named 仓 4 jar compileOnly + gameLibraries 创建/扫描/挂载」抽为可独立
 * apply 的能力，供非 mod 项目（如 SSOptimizer 的功能模块）直接复用，不引入 mod 元数据、
 * 部署、运行等模组专属逻辑。
 *
 * 行为（afterEvaluate 装配，与 mod 插件原装配时机一致）：
 * - apply JavaPlugin（幂等），提供 `starsectorDeps {}` 扩展（[SdgGameDepsExtension]）。
 * - 注册 SourceSector named 仓 maven repository，全部配置 `cacheChangingModulesFor(0)`。
 * - [NAMED_GAME_ARTIFACTS] 4 个 named 坐标挂 compileOnly。
 * - 创建 `gameLibraries` 配置，扫描 named 仓 `starsector/game/` 组逐个挂
 *   `starsector.game:<artifact>`，compileOnly/testCompileOnly extendsFrom 之。
 *
 * [SdgGameDepsExtension.enabled] 为 false 时整段装配跳过（mod 插件 GAME_DIR 模式使用）。
 */
class SdgGameDepsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply(JavaPlugin::class.java)

        val ext = project.extensions.create("starsectorDeps", SdgGameDepsExtension::class.java)
        ext.sourceRepo.convention(namedRepoConvention(project))
        ext.gameVersion.convention(gameVersionConvention(project))

        project.afterEvaluate { wireGameDependencies(project, ext) }
    }

    /** afterEvaluate 装配入口：enabled 门 + named 仓 + gameLibraries。 */
    private fun wireGameDependencies(project: Project, ext: SdgGameDepsExtension) {
        if (!ext.enabled.get()) {
            project.logger.info("SDG: starsectorDeps 装配被禁用（enabled=false），跳过 named 仓与 gameLibraries。")
            return
        }

        val gameVersion = ext.gameVersion.orNull
            ?: throw GradleException("starsectorDeps.gameVersion 未设置（named 装配必填，如 0.98a-RC8）。")
        val repoDir = ext.sourceRepo.get().asFile
        if (!repoDir.isDirectory) {
            throw GradleException(
                "SourceSector named 仓不存在：${repoDir.absolutePath}。" +
                    "请先运行 SourceSector 的 publishNamedGameJars，或通过 starsectorDeps.sourceRepo" +
                    "（mod 插件为 starsector.sourceRepo）指定正确路径。"
            )
        }

        wireNamedRepoDeps(project, repoDir, gameVersion)
        wireGameLibraries(project, repoDir, gameVersion, ext)
    }

    /** named 仓 maven repository 注册 + 4 个 named 坐标 compileOnly（SNAPSHOT 每次重解析）。 */
    private fun wireNamedRepoDeps(project: Project, repoDir: File, gameVersion: String) {
        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.configureEach {
            it.resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
        }
        NAMED_GAME_ARTIFACTS.forEach { artifact ->
            project.dependencies.add("compileOnly", "$NAMED_GAME_GROUP:$artifact:$gameVersion-SNAPSHOT")
        }
    }

    /**
     * 游戏自带第三方库自动供给：
     * 扫描 SourceSector 仓 `starsector/game/` 组下全部 artifact 目录（透传 jar，
     * artifact = 文件名去 .jar，version 与 named jar 相同），逐个生成 `starsector.game:<artifact>`
     * 坐标挂进 gameLibraries；目录不存在或为空直接报错，提示先跑 SourceSector 发布。
     * 目录名命中 [SdgGameDepsExtension.gameLibraryExcludes] 的不挂载。
     */
    private fun wireGameLibraries(
        project: Project,
        repoDir: File,
        gameVersion: String,
        ext: SdgGameDepsExtension,
    ) {
        val gameLibsDir = repoDir.resolve("starsector/game")
        val artifactDirs = gameLibsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (!gameLibsDir.isDirectory || artifactDirs.isEmpty()) {
            throw GradleException(
                "SourceSector 仓的 starsector/game 组不存在或为空：${gameLibsDir.absolutePath}。" +
                    "请先运行 SourceSector 的游戏第三方库透传发布任务。"
            )
        }

        val excludes = ext.gameLibraryExcludes.get().toSet()
        val gameLibraries = project.configurations.create("gameLibraries") {
            it.isCanBeResolved = true
            it.isCanBeConsumed = false
            it.isVisible = false
        }
        project.configurations.getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME).extendsFrom(gameLibraries)
        project.configurations.getByName(JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME).extendsFrom(gameLibraries)
        // changing module 缓存策略由 wireNamedRepoDeps 的 configureEach 统一覆盖（gameLibraries 后建亦生效）
        artifactDirs.filter { it.name !in excludes }.forEach { artifactDir ->
            project.dependencies.add(gameLibraries.name, "starsector.game:${artifactDir.name}:$gameVersion-SNAPSHOT")
        }
    }

    companion object {
        /** SourceSector named 组坐标前缀。 */
        const val NAMED_GAME_GROUP = "starsector.named"

        /** SourceSector 发布的 4 个 named 游戏 jar 坐标。 */
        val NAMED_GAME_ARTIFACTS = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

        /**
         * sourceRepo 默认值 provider：`-Psourcesector.namedRepo=` 覆盖优先
         * （绝对路径或相对根工程的路径），否则默认 `<根工程上级>/SourceSector/build/named-game-repo/windows`。
         */
        fun namedRepoConvention(project: Project): Provider<Directory> =
            project.providers.gradleProperty("sourcesector.namedRepo")
                .map { project.rootProject.layout.projectDirectory.dir(it) }
                .orElse(
                    project.provider {
                        project.rootProject.layout.projectDirectory.dir("../SourceSector/build/named-game-repo/windows")
                    }
                )

        /** gameVersion 默认值 provider：`-Pstarsector.gameVersion=` 覆盖优先，默认 `0.98a-RC8`。 */
        fun gameVersionConvention(project: Project): Provider<String> =
            project.providers.gradleProperty("starsector.gameVersion").orElse("0.98a-RC8")
    }
}
