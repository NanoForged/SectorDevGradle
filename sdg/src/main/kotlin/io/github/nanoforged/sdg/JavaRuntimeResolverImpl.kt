package io.github.nanoforged.sdg

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [JavaRuntimeResolver] 默认实现（候选清单与参数过滤迁移自 Asteria `launchGame`）。
 *
 * 候选顺序：显式配置 → 游戏目录自带（zulu25 优先于旧 jre）→ `~/.jdks` 下的 JBR → 当前 JVM 兜底。
 * 游戏自带运行时优先于 JBR：模组生态已要求 Java 25，JBR 17 无法加载新字节码；
 * 需要 JBR 热重定义时显式指定 `-Pstarsector.javaVendor=jetbrains`（搭配 `~/.jdks` 下的新版 JBR）。
 */
class JavaRuntimeResolverImpl : JavaRuntimeResolver {

    override fun resolve(
        gameDir: File,
        configuredJava: List<File>,
        configuredJavaHomes: List<File>,
        requiredVersion: Int?,
        requiredVendor: String?,
    ): JavaRuntime {
        val javaExt = if (osKey() == "windows") ".exe" else ""
        val userHome = File(System.getProperty("user.home"))

        val jbrCandidates = File(userHome, ".jdks").listFiles()
            ?.filter { it.isDirectory && it.name.contains("jbr-") }
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
            bundledCandidates + jbrCandidates + listOf(fallback)

        val probed = candidates.mapNotNull(::probe)
        val vendorAliases = requiredVendor?.let { vendorAliasesOf(it) }
        val matched = probed.filter { runtime ->
            (requiredVersion == null || runtime.majorVersion == requiredVersion) &&
                (vendorAliases == null || runtime.vendor in vendorAliases)
        }
        return matched.firstOrNull()
            ?: throw IllegalStateException(
                buildString {
                    append("未找到满足条件的 Java 运行时")
                    if (requiredVersion != null) append("（要求主版本 $requiredVersion）")
                    if (requiredVendor != null) append("（要求 vendor $requiredVendor）")
                    append("。已探测 ${candidates.size} 个候选：")
                    probed.forEach { append("\n  ${it.executable} → ${it.versionLine}") }
                    val unprobed = candidates - probed.map { it.executable }.toSet()
                    unprobed.forEach { append("\n  $it → 不可执行") }
                }
            )
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

        /** vendor 别名：用户输入（小写）→ 可接受的 [JavaRuntime.vendor] 集合。 */
        fun vendorAliasesOf(vendor: String): Set<String> = when (vendor.lowercase()) {
            "jbr", "jetbrains" -> setOf("jetbrains")
            "zulu", "azul" -> setOf("zulu")
            "temurin", "adoptium", "adoptopenjdk" -> setOf("temurin")
            else -> setOf(vendor.lowercase())
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
                    vendor = parseVendor(combined),
                )
            } catch (e: Exception) {
                null
            }
        }

        /** 从 `java -version` 全文（已小写化）识别发行版。 */
        fun parseVendor(versionOutput: String): String = when {
            versionOutput.contains("jetbrains") || versionOutput.contains("jbr") -> "jetbrains"
            versionOutput.contains("zulu") -> "zulu"
            versionOutput.contains("temurin") -> "temurin"
            versionOutput.contains("graalvm") -> "graalvm"
            versionOutput.contains("corretto") -> "corretto"
            versionOutput.contains("microsoft") -> "microsoft"
            versionOutput.contains("semeru") -> "semeru"
            else -> "openjdk"
        }

        /**
         * 支持增强重定义的运行时（JBR）自动附加 `-XX:+AllowEnhancedClassRedefinition`（未声明时）；
         * 其他 vendor 原样返回（该参数为 JBR 独有，[filterIncompatibleArgs] 会剥离）。
         */
        fun withEnhancedRedefinition(args: List<String>, runtime: JavaRuntime): List<String> =
            if (runtime.isJetBrainsRuntime && "-XX:+AllowEnhancedClassRedefinition" !in args) {
                args + "-XX:+AllowEnhancedClassRedefinition"
            } else {
                args
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
