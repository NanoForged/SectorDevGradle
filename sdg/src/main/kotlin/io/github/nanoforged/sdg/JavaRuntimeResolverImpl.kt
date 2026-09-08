package io.github.nanoforged.sdg

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [JavaRuntimeResolver] 默认实现（候选清单与参数过滤迁移自 Asteria `launchGame`）。
 *
 * 候选分层：显式配置 → JBR（`~/.jdks` 下的 jbr-* 与当前 JVM 为 JBR 时的自身）
 * → 游戏目录自带（zulu25 优先于旧 jre）→ 当前 JVM 兜底。
 * JBR 默认优先于游戏自带运行时：JBR 支持增强类重定义
 * （`-XX:+AllowEnhancedClassRedefinition`，热重载方法体替换），开发期收益大。
 * 保护栏：版本低于游戏自带运行时的 JBR（如 JBR 17 vs 自带 zulu25）不参与竞争，
 * 避免无版本约束时误选无法加载新字节码的旧 JBR；显式 `-Pstarsector.javaVendor=`
 * 指定 vendor 时保护栏不生效（用户明确意图优先）。
 *
 * @property userHome 用户主目录（`~/.jdks` 扫描根）；测试可注入临时目录隔离真实环境
 */
class JavaRuntimeResolverImpl(
    private val userHome: File = File(System.getProperty("user.home")),
) : JavaRuntimeResolver {

    override fun resolve(
        gameDir: File,
        configuredJava: List<File>,
        configuredJavaHomes: List<File>,
        requiredVersion: Int?,
        requiredVendor: String?,
    ): JavaRuntime {
        val javaExt = if (osKey() == "windows") ".exe" else ""

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
        val probedFallback = probe(fallback)
        // 当前 JVM 本身是 JBR 时（如 IDEA 以 JBR 跑 Gradle 守护进程）并入 JBR 层
        val jbrTier = if (probedFallback?.isJetBrainsRuntime == true) {
            jbrCandidates + fallback
        } else {
            jbrCandidates
        }

        val configured = configuredJava + configuredJavaHomes.map { File(it, "bin/java$javaExt") }
        val probedConfigured = configured.mapNotNull(::probe)
        val probedJbr = jbrTier.mapNotNull(::probe)
        val probedBundled = bundledCandidates.mapNotNull(::probe)

        val vendorAliases = requiredVendor?.let { vendorAliasesOf(it) }
        // JBR 保护栏：未显式指定 vendor 时，版本低于游戏自带运行时的 JBR 不参与竞争
        // （无版本约束的项目不会被 JBR 17 抢走而选择无法加载新字节码的运行时）；
        // 显式 vendor=jetbrains 是用户明确意图，不受此限
        val bestBundledVersion = probedBundled.mapNotNull { it.majorVersion }.maxOrNull()
        val jbrGuarded = if (vendorAliases == null && bestBundledVersion != null) {
            probedJbr.filter { (it.majorVersion ?: 0) >= bestBundledVersion }
        } else {
            probedJbr
        }
        val ordered = probedConfigured + jbrGuarded + probedBundled + listOfNotNull(probedFallback)
        val matched = ordered.filter { runtime ->
            (requiredVersion == null || runtime.majorVersion == requiredVersion) &&
                (vendorAliases == null || runtime.vendor in vendorAliases)
        }
        return matched.firstOrNull()
            ?: throw IllegalStateException(
                buildString {
                    val candidates = configured + jbrTier + bundledCandidates + fallback
                    val allProbed = probedConfigured + probedJbr + probedBundled + listOfNotNull(probedFallback)
                    append("未找到满足条件的 Java 运行时")
                    if (requiredVersion != null) append("（要求主版本 $requiredVersion）")
                    if (requiredVendor != null) append("（要求 vendor $requiredVendor）")
                    append("。已探测 ${candidates.size} 个候选：")
                    allProbed.forEach { append("\n  ${it.executable} → ${it.versionLine}") }
                    val unprobed = candidates - allProbed.map { it.executable }.toSet()
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
