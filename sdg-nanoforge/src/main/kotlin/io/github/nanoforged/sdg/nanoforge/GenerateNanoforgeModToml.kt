package io.github.nanoforged.sdg.nanoforge

import io.github.nanoforged.sdg.ModDependency
import io.github.nanoforged.sdg.ModJarOrdering
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import java.security.MessageDigest

/**
 * 生成 nanoforge.mod.toml（契约见 docs/design/nanoforge-mod-toml-v1.md）。
 *
 * 元数据字段与 mod_info.json 同源（同一 sdg DSL 派生）；
 * `[libraries]` 段在构建期解析坐标 jar 并写入 sha256，`providedBy = "game"` 的库同样解析
 * （摘要是运行时校验数据，是否参与解析由 NanoForge 读取端决定）。
 */
@CacheableTask
abstract class GenerateNanoforgeModToml : DefaultTask() {

    @get:Input
    abstract val modId: Property<String>

    @get:Input
    abstract val modName: Property<String>

    @get:Input
    abstract val modVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val author: Property<String>

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

    @get:Input
    abstract val libraries: ListProperty<NanoForgeLibrary>

    /** 产物 jar 文件名清单（任务图推导：附加 classifier jar + 按 artifactMode 选主产物），生成 `jars` 字段。 */
    @get:Input
    abstract val jarFileNames: ListProperty<String>

    /** `[libraries]` 坐标解析出的 jar 文件（sdgNanoLibraries configuration），用于计算 sha256。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val lines = mutableListOf<String>()
        lines += TomlWriter.entry("id", TomlWriter.string(modId.get()))
        lines += TomlWriter.entry("name", TomlWriter.string(modName.get()))
        lines += TomlWriter.entry("version", TomlWriter.string(modVersion.get()))
        author.orNull?.let { lines += TomlWriter.entry("author", TomlWriter.string(it)) }
        modDescription.orNull?.let { lines += TomlWriter.entry("description", TomlWriter.string(it)) }
        gameVersion.orNull?.let { lines += TomlWriter.entry("gameVersion", TomlWriter.string(it)) }
        modPlugin.orNull?.let { lines += TomlWriter.entry("modPlugin", TomlWriter.string(it)) }

        val jars = ModJarOrdering.mainFirstNames(jarFileNames.get())
        if (jars.isNotEmpty()) {
            lines += TomlWriter.entry("jars", TomlWriter.stringArray(jars))
        }

        dependencies.get().forEach { dep ->
            lines += ""
            lines += "[[dependencies]]"
            lines += TomlWriter.entry("id", TomlWriter.string(dep.id))
            dep.name?.let { lines += TomlWriter.entry("name", TomlWriter.string(it)) }
            dep.version?.let { lines += TomlWriter.entry("version", TomlWriter.string(it)) }
        }

        libraries.get().forEach { lib ->
            lines += ""
            lines += "[[libraries]]"
            lines += TomlWriter.entry("group", TomlWriter.string(lib.group))
            lines += TomlWriter.entry("artifact", TomlWriter.string(lib.artifact))
            lines += TomlWriter.entry("version", TomlWriter.string(lib.version))
            lines += TomlWriter.entry("sha256", TomlWriter.string(sha256Of(lib)))
            lib.providedBy?.let { lines += TomlWriter.entry("providedBy", TomlWriter.string(it)) }
        }

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(lines.joinToString("\n") + "\n")
    }

    /** 在解析产物中定位坐标对应 jar 并计算 sha256；定位失败即显式报错（不允许静默跳过校验数据）。 */
    private fun sha256Of(lib: NanoForgeLibrary): String {
        val jar = libraryJars.files.singleOrNull { it.name == lib.jarFileName }
            ?: throw GradleException(
                "无法为 [libraries] 坐标 ${lib.notation} 定位解析产物 ${lib.jarFileName}" +
                    "（sdgNanoLibraries 实际产物：${libraryJars.files.map { it.name }.sorted()}）"
            )
        val digest = MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
