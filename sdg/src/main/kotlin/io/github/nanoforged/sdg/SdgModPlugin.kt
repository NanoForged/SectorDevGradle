package io.github.nanoforged.sdg

import io.github.nanoforged.sdg.SdgExtension.Companion.NAMED_GAME_ARTIFACTS
import io.github.nanoforged.sdg.SdgExtension.Companion.NAMED_GAME_GROUP
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SDG 核心插件（`io.github.nanoforged.sdg.mod`）。
 *
 * 职责（按实现计划分轮接入）：
 * - R1：工作区与游戏依赖解析（SourceSector named 仓 / gameDir 扫描、第三方 mod 依赖桥）
 * - R2：mod 产物（mod_info.json 生成、mod_production 布局、zip、deployMod）
 * - R3：reobfJar（named→obf）与 obf 形态 shade
 * - R4：runGame（launch-spec）、debug 注入、IDEA run 配置、decompileDependencies
 */
class SdgModPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply(JavaPlugin::class.java)

        val ext = project.extensions.create("sdg", SdgExtension::class.java)
        ext.modVersion.convention(project.provider { project.version.toString() })
        ext.modName.convention(ext.modId)
        ext.deployDirName.convention(ext.modId)
        ext.contentsDir.convention(project.layout.projectDirectory.dir("contents"))
        ext.sourceRepo.convention(
            project.rootProject.layout.projectDirectory.dir("../SourceSector/build/named-game-repo/windows")
        )

        // SourceSector mapping 工具构件发布在 mavenLocal（与 NanoForge API 的消费方式一致）；
        // mavenCentral 用于解析工具的传递依赖（ASM 等）
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()

        wireModProduction(project, ext)
        wireObfArtifacts(project, ext)
        wireDeployment(project, ext)
        project.afterEvaluate { wireGameDependencies(project, ext) }
    }

    /** R2：mod_production 产物布局（contents 同步 + jars 汇集 + mod_info.json 生成）与 zip 发布包。 */
    private fun wireModProduction(project: Project, ext: SdgExtension) {
        val modProductionDir = project.layout.buildDirectory.dir("mod_production")

        project.tasks.register("cleanModProduction", Delete::class.java) {
            it.group = TASK_GROUP
            it.description = "清理 mod 产物目录"
            it.delete(modProductionDir)
        }

        project.tasks.register("copyContents", Sync::class.java) {
            it.group = TASK_GROUP
            it.description = "同步静态内容目录到产物布局"
            it.from(ext.contentsDir)
            it.into(modProductionDir)
            // jars/ 与 mod_info.json 由 copyJars / generateModInfoJson 负责，保留不被 Sync 清理
            it.preserve { p ->
                p.include("jars/**")
                p.include("mod_info.json")
            }
        }

        val mainJar = project.tasks.named("jar", Jar::class.java)
        // 附加产物（sources/agent 等 classifier jar）原样汇集；主 jar 按产物形态二选一，shadeObfJar 永不直接进入布局
        val extraJarTasks = project.tasks.withType(Jar::class.java)
            .matching { t -> t.name != "jar" && t.name != "shadeObfJar" }
        val copyJars = project.tasks.register("copyJars", Sync::class.java) {
            it.group = TASK_GROUP
            it.description = "汇集构建产物 jar 到产物布局 jars/（主 jar 按 artifactMode 取 named 或 obf 产物）"
            it.from(project.provider {
                val extras = extraJarTasks.map { t -> t.archiveFile.get().asFile }
                val main = when (ext.artifactMode.get()) {
                    ArtifactMode.DEOBF -> mainJar.get().archiveFile.get().asFile
                    ArtifactMode.OBF -> project.tasks.named("shadeObfJar", Jar::class.java).get()
                        .archiveFile.get().asFile
                }
                extras + main
            })
            it.into(modProductionDir.map { d -> d.dir("jars") })
            it.dependsOn(extraJarTasks)
            it.dependsOn(mainJar)
            it.dependsOn(project.provider {
                if (ext.artifactMode.get() == ArtifactMode.OBF) listOf("verifyObfJar") else emptyList<String>()
            })
        }

        val generateModInfo = project.tasks.register("generateModInfoJson", GenerateModInfoJson::class.java) {
            it.group = TASK_GROUP
            it.description = "生成 mod_info.json（DSL 派生，jars 字段按产物枚举）"
            it.modId.set(ext.modId)
            it.modName.set(ext.modName)
            it.modVersion.set(ext.modVersion)
            it.author.set(ext.author)
            it.modDescription.set(ext.description)
            it.gameVersion.set(ext.gameVersion)
            it.modPlugin.set(ext.modPlugin)
            it.dependencies.set(ext.dependencies)
            it.productionJars.from(project.fileTree(copyJars.map { t -> t.destinationDir }))
            it.outputFile.set(modProductionDir.map { d -> d.file("mod_info.json") })
            it.dependsOn(copyJars)
            it.dependsOn("copyContents")
        }

        project.tasks.register("modProduction") {
            it.group = TASK_GROUP
            it.description = "组装 mod 产物目录"
            it.dependsOn(generateModInfo)
        }

        project.tasks.register("zipModProduction", Zip::class.java) {
            it.group = TASK_GROUP
            it.description = "打包 mod 发布 zip"
            it.from(modProductionDir)
            it.archiveFileName.set(ext.modName.map { name -> "$name-${ext.modVersion.get()}.zip" })
            it.destinationDirectory.set(project.layout.buildDirectory)
            it.entryCompression = ZipEntryCompression.DEFLATED
            it.isPreserveFileTimestamps = false
            it.isReproducibleFileOrder = true
            it.dependsOn("modProduction")
        }
        project.tasks.named(BasePlugin.ASSEMBLE_TASK_NAME) {
            it.finalizedBy("zipModProduction")
        }
    }

    /** R3：OBF 形态产物链——reobfJar（named→obf）→ shadeObfJar（合并第三方库）→ verifyObfJar（质量门）。 */
    private fun wireObfArtifacts(project: Project, ext: SdgExtension) {
        val mappingTool = project.configurations.create("sdgMappingTool") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.isVisible = false
        }
        val mappings = project.configurations.create("sdgMappings") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.isVisible = false
        }
        project.dependencies.add(mappingTool.name, "io.github.nanoforged:sourcesector-mapping:0.1.0-SNAPSHOT")

        // mapping 表来源：DSL 直指定优先，否则从 sourceRepo 解析 mappings 构件（dep 在 afterEvaluate 补充）
        val mappingFile: Provider<File> = project.provider {
            ext.mappingFile.orNull?.asFile ?: mappings.singleFile
        }

        val mainJar = project.tasks.named("jar", Jar::class.java)
        val reobfOutput = project.layout.buildDirectory.file(
            mainJar.flatMap { t -> t.archiveFileName }.map { name -> "reobf/$name" }
        )

        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        val reobfJar = project.tasks.register("reobfJar", JavaExec::class.java) {
            it.group = TASK_GROUP
            it.description = "将主 jar 从 named 重映射为 obf 字节码（SourceSector mapping 工具）"
            it.onlyIf { ext.artifactMode.get() == ArtifactMode.OBF }
            it.classpath = mappingTool
            it.mainClass.set("io.github.nanoforged.sourcesector.mapping.JarRemapCli")
            // mapping 工具以 release 25 编译
            it.javaLauncher.set(
                toolchains.launcherFor { spec -> spec.languageVersion.set(JavaLanguageVersion.of(25)) }
            )
            it.inputs.file(mainJar.flatMap { t -> t.archiveFile })
            it.inputs.file(mappingFile)
            it.outputs.file(reobfOutput)
            it.dependsOn(mainJar)
            it.doFirst { exec ->
                val input = mainJar.get().archiveFile.get().asFile
                val output = reobfOutput.get().asFile
                output.parentFile.mkdirs()
                (exec as JavaExec).setArgs(
                    listOf(
                        "--mapping=${mappingFile.get().absolutePath}",
                        "single", "named-to-obf",
                        input.absolutePath, output.absolutePath,
                    )
                )
            }
        }

        val shadeObfJar = project.tasks.register("shadeObfJar", Jar::class.java) {
            it.group = TASK_GROUP
            it.description = "obf 形态主产物：reobf 结果合并 runtimeClasspath 第三方库"
            it.archiveFileName.set(mainJar.flatMap { t -> t.archiveFileName })
            it.destinationDirectory.set(project.layout.buildDirectory.dir("obf"))
            it.from(project.provider { project.zipTree(reobfOutput.get().asFile) })
            it.from(project.provider {
                project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).files
                    .filter { f -> f.extension == "jar" }
                    .map { f -> project.zipTree(f) }
            })
            // SSOptimizer 已验证：RFB 会把 module-info.class 误判为命名模块，必须排除
            it.exclude("module-info.class", "META-INF/versions/**")
            it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            it.dependsOn(reobfJar)
        }

        project.tasks.register("verifyObfJar", VerifyObfJar::class.java) {
            it.group = TASK_GROUP
            it.description = "obf 产物质量门：不残留 named 符号引用"
            it.obfJar.set(shadeObfJar.flatMap { t -> t.archiveFile })
            it.mapping.fileProvider(mappingFile)
            it.sampleSize.convention(500)
            it.dependsOn(shadeObfJar)
            it.outputs.upToDateWhen { false }
        }
    }

    /** R2：deployMod 覆盖式部署 + 可选 enabled_mods.json 维护。 */
    private fun wireDeployment(project: Project, ext: SdgExtension) {
        fun resolveDeployTarget(): File {
            val gameDir = ext.gameDir.orNull?.asFile
                ?: throw GradleException("sdg.gameDir 未设置，无法部署。")
            if (!gameDir.isDirectory) {
                throw GradleException("sdg.gameDir 不存在：${gameDir.absolutePath}")
            }
            return gameDir.resolve("mods/${ext.deployDirName.get()}")
        }

        // 尽力删除：Windows/NTFS 占用导致的删除失败只告警，硬失败留给覆盖写入
        project.tasks.register("cleanDeploy") {
            it.group = TASK_GROUP
            it.description = "清理游戏目录中的旧部署（尽力删除，失败仅告警）"
            it.doLast {
                val target = resolveDeployTarget()
                if (target.exists()) {
                    try {
                        target.deleteRecursively()
                    } catch (e: Exception) {
                        project.logger.warn("SDG: 无法删除旧部署目录（将继续覆盖式部署）：${target.absolutePath}：${e.message}")
                    }
                }
            }
        }

        project.tasks.register("deployMod", Sync::class.java) {
            it.group = TASK_GROUP
            it.description = "部署 mod 到游戏目录 mods/<deployDirName>/"
            it.from(project.layout.buildDirectory.dir("mod_production"))
            it.into(project.provider { resolveDeployTarget() })
            it.dependsOn("modProduction", "cleanDeploy")
            it.doLast {
                project.logger.lifecycle("SDG: 已部署到 ${resolveDeployTarget().absolutePath}")
                if (ext.manageEnabledMods.get()) {
                    val changed = EnabledModsImpl().enable(ext.gameDir.get().asFile, ext.modId.get())
                    if (changed) {
                        project.logger.lifecycle("SDG: 已将 ${ext.modId.get()} 加入 enabled_mods.json")
                    }
                }
            }
        }
    }

    /** 按 DSL 配置装配游戏依赖与第三方 mod 依赖桥。 */
    private fun wireGameDependencies(project: Project, ext: SdgExtension) {
        val modId = ext.modId.orNull
            ?: throw GradleException("sdg.modId 未设置，它是模组元数据的唯一事实源。")

        when (ext.gameDependencyMode.get()) {
            GameDependencyMode.NAMED_REPO -> wireNamedRepoDeps(project, ext)
            GameDependencyMode.GAME_DIR -> wireGameDirDeps(project, ext)
        }

        wireModDependencyBridge(project, ext, modId)
        wireMappingsDependency(project, ext)
    }

    /** OBF 形态且未直指定表时，从 sourceRepo 解析 mappings 表构件（`@tiny`）。 */
    private fun wireMappingsDependency(project: Project, ext: SdgExtension) {
        if (ext.artifactMode.get() != ArtifactMode.OBF || ext.mappingFile.isPresent) return
        val gameVersion = ext.gameVersion.orNull
            ?: throw GradleException("OBF 形态需要 sdg.gameVersion 以解析 mappings 表构件（或用 sdg.mappingFile 直指定）。")
        project.dependencies.add(
            "sdgMappings",
            "$NAMED_GAME_GROUP:mappings-${ext.mappingPlatform.get()}:$gameVersion-SNAPSHOT@tiny"
        )
    }

    /** named 仓模式：SourceSector 本地 maven 仓 + 4 个 named 坐标（SNAPSHOT 每次重解析）。 */
    private fun wireNamedRepoDeps(project: Project, ext: SdgExtension) {
        val gameVersion = ext.gameVersion.orNull
            ?: throw GradleException("sdg.gameVersion 未设置（NAMED_REPO 模式必填，如 0.98a-RC8）。")
        val repoDir = ext.sourceRepo.get().asFile
        if (!repoDir.isDirectory) {
            throw GradleException(
                "SourceSector named 仓不存在：${repoDir.absolutePath}。" +
                    "请先运行 SourceSector 的 publishNamedGameJars，或通过 sdg.sourceRepo 指定正确路径。"
            )
        }

        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.configureEach {
            it.resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
        }
        NAMED_GAME_ARTIFACTS.forEach { artifact ->
            project.dependencies.add("compileOnly", "$NAMED_GAME_GROUP:$artifact:$gameVersion-SNAPSHOT")
        }
    }

    /** gameDir 模式：游戏根目录与 starfarer-core 的 jar 全量 compileOnly。 */
    private fun wireGameDirDeps(project: Project, ext: SdgExtension) {
        val gameDir = requireGameDir(ext)
        project.dependencies.add("compileOnly", project.fileTree(gameDir) { it.include("*.jar") })
        project.dependencies.add(
            "compileOnly",
            project.fileTree(gameDir.resolve("starfarer-core")) { it.include("*.jar") }
        )
    }

    /** 第三方 mod 依赖桥：扫描 gameDir/mods 下各模组 mod_info.json 的 jars 字段。 */
    private fun wireModDependencyBridge(project: Project, ext: SdgExtension, modId: String) {
        val gameDirFile = ext.gameDir.orNull?.asFile
        if (gameDirFile == null) {
            project.logger.info("SDG: sdg.gameDir 未设置，跳过第三方 mod 依赖桥。")
            return
        }
        val modsDir = gameDirFile.resolve("mods")
        if (!modsDir.isDirectory) {
            project.logger.info("SDG: ${modsDir.absolutePath} 不存在，跳过第三方 mod 依赖桥。")
            return
        }

        val index = ModJarIndexImpl().scan(modsDir, modId) { project.logger.warn("SDG: $it") }
        index.values.flatten().forEach { jar ->
            project.dependencies.add("compileOnly", project.files(jar))
        }
    }

    private fun requireGameDir(ext: SdgExtension): File {
        val gameDir = ext.gameDir.orNull?.asFile
            ?: throw GradleException("sdg.gameDir 未设置（GAME_DIR 模式必填）。")
        if (!gameDir.isDirectory) {
            throw GradleException("sdg.gameDir 不存在：${gameDir.absolutePath}")
        }
        return gameDir
    }

    private companion object {
        const val TASK_GROUP = "sdg"
    }
}
