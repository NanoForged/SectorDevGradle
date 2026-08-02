package io.github.nanoforged.sdg.nanoforge

import io.github.nanoforged.sdg.SdgExtension
import io.github.nanoforged.sdg.SdgModPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

/**
 * SDG NanoForge 支持插件（`io.github.nanoforged.sdg.nanoforge`）。
 *
 * 职责（按实现计划分轮接入）：
 * - R5：nanoforge.mod.toml 生成（含 [libraries] 依赖库元数据）、coremod.toml 生成、
 *   `mods/coremods/` 落位部署
 * - R6：patch 工作流（消费 SourceSector patch-tool 构件）
 *
 * 依赖 sdg 核心插件（自动应用，幂等）；元数据事实源仍是 `sdg {}` DSL，本插件只追加
 * NanoForge 专属段。
 */
class SdgNanoForgePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply(SdgModPlugin::class.java)
        val sdgExt = project.extensions.getByType(SdgExtension::class.java)
        val ext = project.extensions.create("nanoforge", NanoForgeExtension::class.java)

        // [libraries] 坐标解析 configuration：DSL 声明在首次解析时汇入（withDependencies 是
        // afterEvaluate 的配置缓存兼容替代），构建期解析算 sha256。
        val librariesConfig = project.configurations.create("sdgNanoLibraries") {
            it.isCanBeResolved = true
            it.isCanBeConsumed = false
            it.isVisible = false
            it.isTransitive = false
        }
        librariesConfig.withDependencies { deps ->
            ext.libraries.get().forEach { lib ->
                deps.add(project.dependencies.create(lib.notation))
            }
        }

        val mainJar = project.tasks.named("jar", Jar::class.java)

        // jars 字段从任务图推导文件名（与 copyJars 收集规则一致：附加 classifier jar + 按 artifactMode
        // 选主产物）。不枚举 copyJars 输出：nanoforge.mod.toml 要打进主 jar 根，枚举产物会形成
        // copyJars → jar → generateNanoforgeModToml → copyJars 循环依赖。
        val extraJarTasks = project.tasks.withType(Jar::class.java)
            .matching { t -> t.name != "jar" && t.name != "shadeObfJar" }
        val productionJarNames = project.provider {
            val mainName = when (sdgExt.artifactMode.get()) {
                io.github.nanoforged.sdg.ArtifactMode.DEOBF -> mainJar.get().archiveFileName.get()
                io.github.nanoforged.sdg.ArtifactMode.OBF ->
                    project.tasks.named("shadeObfJar", Jar::class.java).get().archiveFileName.get()
            }
            extraJarTasks.map { t -> t.archiveFileName.get() } + mainName
        }

        val generateModToml = project.tasks.register("generateNanoforgeModToml", GenerateNanoforgeModToml::class.java) {
            it.group = TASK_GROUP
            it.description = "生成 nanoforge.mod.toml（与 mod_info.json 同源派生 + [libraries] 依赖库元数据）"
            it.modId.set(sdgExt.modId)
            it.modName.set(sdgExt.modName)
            it.modVersion.set(sdgExt.modVersion)
            it.author.set(sdgExt.author)
            it.modDescription.set(sdgExt.description)
            it.gameVersion.set(sdgExt.gameVersion)
            it.modPlugin.set(sdgExt.modPlugin)
            it.dependencies.set(sdgExt.dependencies)
            it.libraries.set(ext.libraries)
            it.jarFileNames.set(productionJarNames)
            it.libraryJars.from(librariesConfig)
            // 生成到独立目录再经 copyContents 进产物布局：直接写 mod_production 会与
            // copyContents 的输出目录归属冲突（Sync destinationDir 即输出）。
            it.outputFile.set(project.layout.buildDirectory.file("generated/sdg/nanoforge.mod.toml"))
        }

        val generateCoreModToml = project.tasks.register("generateCoreModToml", GenerateCoreModToml::class.java) {
            it.group = TASK_GROUP
            it.description = "生成 coremod.toml（仅 coremod 形态执行，打进 coremod jar 根）"
            it.coremodId.set(sdgExt.modId)
            it.coremodName.set(sdgExt.modName)
            it.coremodVersion.set(sdgExt.modVersion)
            it.pluginClass.set(ext.pluginClass)
            it.authors.set(ext.authors)
            it.coremodDescription.set(sdgExt.description)
            it.priority.set(ext.priority)
            it.depends.set(ext.depends)
            it.asmTransformers.set(ext.asmTransformers)
            it.asmTransformerExclusions.set(ext.asmTransformerExclusions)
            it.mixinConfigs.set(ext.mixinConfigs)
            it.patchEntries.set(ext.patchEntries)
            it.outputFile.set(project.layout.buildDirectory.file("generated/sdg/coremod.toml"))
            it.onlyIf { _ -> ext.coremod.get() }
        }

        // 两个 toml 都位于 jar 根（契约 §1 / NanoForge TOML_ENTRY_NAME）。
        // generateCoreModToml 非 coremod 形态被 onlyIf 跳过，from 对不存在的产物静默忽略。
        mainJar.configure {
            it.from(generateModToml)
            it.from(generateCoreModToml)
        }

        // nanoforge.mod.toml 进产物布局根（与 mod_info.json 并列），zip/deploy 随之分发。
        project.tasks.named("copyContents", Sync::class.java) {
            it.from(generateModToml)
        }

        // coremod 落位：主 jar → gameDir/mods/coremods/（NanoForge 唯一扫描目录）。
        // 用 Copy 而非 Sync：mods/coremods/ 是多 coremod 共享目录，Sync 会删除非本模组的 jar。
        val deployCoreMod = project.tasks.register("deployCoreMod", Copy::class.java) {
            it.group = TASK_GROUP
            it.description = "coremod 落位：主 jar 部署到游戏目录 mods/coremods/"
            it.from(mainJar.flatMap { t -> t.archiveFile })
            it.into(project.provider {
                val gameDir = sdgExt.gameDir.orNull?.asFile
                    ?: throw org.gradle.api.GradleException("sdg.gameDir 未设置，无法部署 coremod。")
                gameDir.resolve("mods/coremods")
            })
            it.dependsOn(mainJar)
            it.onlyIf { _ -> ext.coremod.get() }
        }
        project.tasks.named("deployMod") {
            it.finalizedBy(deployCoreMod)
        }
    }

    private companion object {
        const val TASK_GROUP = "sdg"
    }
}
