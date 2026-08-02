package io.github.nanoforged.sdg

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest

/**
 * decompileDependencies：Vineflower 反编译 compileOnly 依赖（游戏/第三方 mod jar）
 * 到统一源码目录，供 IDE 索引阅读（迁移自 Asteria DecompileSourcesTask，串行化简化）。
 *
 * SHA-256 增量：jar 内容未变的条目跳过反编译。
 */
@DisableCachingByDefault(because = "增量由 SHA-256 缓存自管理，输出供 IDE 索引")
abstract class DecompileDependencies : DefaultTask() {

    /** Vineflower 可执行 jar（sdgDecompiler 配置解析结果）。 */
    @get:Classpath
    abstract val decompiler: ConfigurableFileCollection

    /** 待反编译的依赖 jar（compileOnly 可解析副本）。 */
    @get:Classpath
    abstract val inputJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outputDirectory = outputDir.get().asFile
        outputDirectory.mkdirs()
        val hashCacheFile = outputDirectory.resolve(".decompile-cache.txt")
        val hashCache = loadHashCache(hashCacheFile)
        val newHashCache = mutableMapOf<String, String>()

        inputJars.files
            .filter { it.extension == "jar" && !it.name.endsWith("-sources.jar") }
            .sortedBy { it.name }
            .forEach { jar ->
                val currentHash = sha256(jar)
                newHashCache[jar.name] = currentHash
                if (hashCache[jar.name] == currentHash) {
                    logger.lifecycle("${jar.name} 未变更，跳过反编译")
                    return@forEach
                }

                logger.lifecycle("反编译 ${jar.name}")
                val tempDir = outputDirectory.resolve(".temp/${jar.nameWithoutExtension}")
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                val process = ProcessBuilder(
                    "java", "-jar", decompiler.singleFile.absolutePath,
                    jar.absolutePath, tempDir.absolutePath,
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val rc = process.waitFor()
                check(rc == 0) { "Vineflower 反编译 ${jar.name} 失败（rc=$rc）：\n$output" }

                tempDir.listFiles()?.forEach { file ->
                    file.copyRecursively(outputDirectory.resolve(file.name), overwrite = true)
                }
            }

        outputDirectory.resolve(".temp").deleteRecursively()
        saveHashCache(hashCacheFile, newHashCache)
        logger.lifecycle("反编译完成，源码输出到：${outputDirectory.absolutePath}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadHashCache(cacheFile: File): Map<String, String> {
        if (!cacheFile.isFile) return emptyMap()
        return try {
            cacheFile.readLines()
                .filter { it.isNotBlank() && it.contains("=") }
                .associate { val p = it.split("=", limit = 2); p[0].trim() to p[1].trim() }
        } catch (e: Exception) {
            logger.warn("读取反编译 hash 缓存失败（将全量重跑）：${e.message}")
            emptyMap()
        }
    }

    private fun saveHashCache(cacheFile: File, cache: Map<String, String>) {
        cacheFile.writeText(cache.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
}
