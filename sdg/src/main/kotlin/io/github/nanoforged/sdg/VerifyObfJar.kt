package io.github.nanoforged.sdg

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * obf 产物质量门：reobf 字节码中不残留 named 符号引用。
 * 无产物，失败即构建失败（携带违规样本明细）。
 */
@DisableCachingByDefault(because = "纯校验任务，无产物，输出仅为通过/失败信号")
abstract class VerifyObfJar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val obfJar: RegularFileProperty

    /** reobf 使用的同一张全量 tiny 表（校验基准与 remap 基准必须一致）。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    /** named 类抽样上限，默认 500。 */
    @get:Input
    abstract val sampleSize: Property<Int>

    @TaskAction
    fun verify() {
        val violations = ObfJarVerifierImpl().verify(
            obfJar.get().asFile,
            mapping.get().asFile,
            sampleSize.get(),
        )
        if (violations.isNotEmpty()) {
            val detail = violations.take(20).joinToString("\n  ")
            throw GradleException(
                "obf 产物残留 ${violations.size} 处 named 符号引用（前 ${minOf(20, violations.size)} 条）：\n  $detail"
            )
        }
        logger.lifecycle("SDG: obf 产物校验通过（抽样 ${sampleSize.get()} 个 named 类无残留）")
    }
}
