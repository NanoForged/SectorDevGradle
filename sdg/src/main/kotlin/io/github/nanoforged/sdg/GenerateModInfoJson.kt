package io.github.nanoforged.sdg

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 构建期生成 mod_info.json（唯一事实源是 [SdgExtension] DSL，不手写）。
 *
 * `jars` 字段不来自 DSL，而是执行时枚举产物 jars 目录实际内容，
 * 保证元数据与产物一致（含 sources/agent 等附加 classifier jar）。
 */
@CacheableTask
abstract class GenerateModInfoJson : DefaultTask() {

    @get:Input
    abstract val modId: Property<String>

    @get:Input
    abstract val modName: Property<String>

    @get:Input
    abstract val modVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val author: Property<String>

    /** 模组描述（mod_info.json `description`）；命名避开 Task.getDescription。 */
    @get:Input
    @get:Optional
    abstract val modDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val gameVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val modPlugin: Property<String>

    @get:Input
    abstract val dependencies: ListProperty<ModDependency>

    /** 产物 jars（copyJars 的输出），执行时枚举生成 `jars` 字段。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val jars = productionJars.files
            .filter { it.extension == "jar" }
            .map { "jars/${it.name}" }
            .sorted()

        val info = linkedMapOf<String, Any>(
            "id" to modId.get(),
            "name" to modName.get(),
            "version" to modVersion.get(),
        )
        author.orNull?.let { info["author"] = it }
        modDescription.orNull?.let { info["description"] = it }
        gameVersion.orNull?.let { info["gameVersion"] = it }
        if (jars.isNotEmpty()) info["jars"] = jars
        modPlugin.orNull?.let { info["modPlugin"] = it }

        val deps = dependencies.get().map { dep ->
            linkedMapOf<String, String>("id" to dep.id).apply {
                dep.name?.let { put("name", it) }
                dep.version?.let { put("version", it) }
            }
        }
        if (deps.isNotEmpty()) info["dependencies"] = deps

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
    }
}
