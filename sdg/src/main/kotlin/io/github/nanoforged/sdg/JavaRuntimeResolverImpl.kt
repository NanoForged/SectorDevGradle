package io.github.nanoforged.sdg

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [JavaRuntimeResolver] 默认实现（候选清单与参数过滤迁移自 Asteria `launchGame`）。
 */
class JavaRuntimeResolverImpl : JavaRuntimeResolver {

    override fun resolve(gameDir: File, configuredJava: List<File>, configuredJavaHomes: List<File>): JavaRuntime {
        val javaExt = if (osKey() == "windows") ".exe" else ""
        val userHome = File(System.getProperty("user.home"))

        val jbr17Candidates = File(userHome, ".jdks").listFiles()
            ?.filter { it.isDirectory && it.name.contains("jbr-17") }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "bin/java$javaExt") }
            .orEmpty()

        val bundledCandidates = when (osKey()) {
            "windows" -> listOf(File(gameDir, "zulu25_win"), File(gameDir, "jre"))
            "mac" -> listOf(File(gameDir, "zulu25_mac"), File(gameDir, "jre_mac/Contents/Home"))
            else -> listOf(File(gameDir, "zulu25_linux"), File(gameDir, "jre_linux"))
        }.map { File(it, "bin/java$javaExt") }

        val fallback = File(System.getProperty("java.home"), "bin/java$javaExt")

        val candidates = configuredJava +
            configuredJavaHomes.map { File(it, "bin/java$javaExt") } +
            jbr17Candidates + bundledCandidates + listOf(fallback)

        return candidates.firstNotNullOfOrNull(::probe)
            ?: throw IllegalStateException("未找到可用的 Java 运行时（已探测 ${candidates.size} 个候选）")
    }

    companion object {
        /** launch-config.json 的 OS 段键：windows / linux / mac。 */
        fun osKey(): String {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("win") -> "windows"
                osName.contains("mac") -> "mac"
                else -> "linux"
            }
        }

        /** 探测 java 可执行文件；不可用返回 null。 */
        fun probe(executable: File): JavaRuntime? {
            if (!executable.isFile) return null
            return try {
                val process = ProcessBuilder(executable.absolutePath, "-version")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(5, TimeUnit.SECONDS)
                val combined = output.lowercase()
                JavaRuntime(
                    executable = executable,
                    majorVersion = Regex("version \"(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                    versionLine = output.lineSequence().firstOrNull().orEmpty(),
                    isJetBrainsRuntime = combined.contains("jetbrains") ||
                        combined.contains(" jbr") || combined.contains("jbr-"),
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 过滤与运行时不兼容的 JVM 参数（迁移自 Asteria）：
         * JDK<24 去掉 UseCompactObjectHeaders，非 JBR 去掉 AllowEnhancedClassRedefinition。
         *
         * @return 保留参数 to 被移除参数
         */
        fun filterIncompatibleArgs(args: List<String>, runtime: JavaRuntime): Pair<List<String>, List<String>> {
            val kept = args.filterNot { arg ->
                (arg == "-XX:+UseCompactObjectHeaders" && (runtime.majorVersion ?: Int.MAX_VALUE) < 24) ||
                    (arg == "-XX:+AllowEnhancedClassRedefinition" && !runtime.isJetBrainsRuntime)
            }
            return kept to (args - kept.toSet())
        }
    }
}
