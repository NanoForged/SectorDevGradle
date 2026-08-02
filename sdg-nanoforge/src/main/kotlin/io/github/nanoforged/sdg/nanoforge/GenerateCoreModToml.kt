package io.github.nanoforged.sdg.nanoforge

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * 生成 coremod.toml（NanoForge coremod 装配的权威输入，位于 coremod jar 根）。
 *
 * 字段与 NanoForge `CoreModMetaParser` 的读取键一一对应：
 * 必填 id/name/version/pluginClass，可选 authors/description/priority/depends
 * 与 `[asm]`/`[mixin]`/`[patch]` 三组字符串数组段；空段不输出。
 */
@CacheableTask
abstract class GenerateCoreModToml : DefaultTask() {

    @get:Input
    abstract val coremodId: Property<String>

    @get:Input
    abstract val coremodName: Property<String>

    @get:Input
    abstract val coremodVersion: Property<String>

    /** `pluginClass`（`INanoCorePlugin` 实现类全限定名），缺失即构建失败。 */
    @get:Input
    @get:Optional
    abstract val pluginClass: Property<String>

    @get:Input
    abstract val authors: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val coremodDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val priority: Property<Int>

    @get:Input
    abstract val depends: ListProperty<String>

    @get:Input
    abstract val asmTransformers: ListProperty<String>

    @get:Input
    abstract val asmTransformerExclusions: ListProperty<String>

    @get:Input
    abstract val mixinConfigs: ListProperty<String>

    @get:Input
    abstract val patchEntries: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val plugin = pluginClass.orNull
            ?: throw GradleException("nanoforge.pluginClass 未设置（coremod 形态必填，coremod.toml 的 pluginClass 键）。")

        val lines = mutableListOf<String>()
        lines += TomlWriter.entry("id", TomlWriter.string(coremodId.get()))
        lines += TomlWriter.entry("name", TomlWriter.string(coremodName.get()))
        lines += TomlWriter.entry("version", TomlWriter.string(coremodVersion.get()))
        authors.get().takeIf { it.isNotEmpty() }
            ?.let { lines += TomlWriter.entry("authors", TomlWriter.stringArray(it)) }
        coremodDescription.orNull?.let { lines += TomlWriter.entry("description", TomlWriter.string(it)) }
        priority.orNull?.let { lines += TomlWriter.entry("priority", it.toString()) }
        depends.get().takeIf { it.isNotEmpty() }
            ?.let { lines += TomlWriter.entry("depends", TomlWriter.stringArray(it)) }
        lines += TomlWriter.entry("pluginClass", TomlWriter.string(plugin))

        sectionEntries(lines, "asm", listOf(
            "transformers" to asmTransformers.get(),
            "transformerExclusions" to asmTransformerExclusions.get(),
        ))
        sectionEntries(lines, "mixin", listOf("configs" to mixinConfigs.get()))
        sectionEntries(lines, "patch", listOf("entries" to patchEntries.get()))

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(lines.joinToString("\n") + "\n")
    }

    /** 写一个嵌套段；该段所有键均为空列表时整段不输出。 */
    private fun sectionEntries(lines: MutableList<String>, section: String, entries: List<Pair<String, List<String>>>) {
        val nonEmpty = entries.filter { it.second.isNotEmpty() }
        if (nonEmpty.isEmpty()) return
        lines += ""
        lines += "[$section]"
        nonEmpty.forEach { (key, values) -> lines += TomlWriter.entry(key, TomlWriter.stringArray(values)) }
    }
}
