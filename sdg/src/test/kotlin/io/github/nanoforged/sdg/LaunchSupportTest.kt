package io.github.nanoforged.sdg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VanillaLaunchConfigTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `解析 common 与 OS 段及 classpath 清单`() {
        val file = dir.resolve("launch-config.json")
        file.writeText(
            """
            {
              "jvmArgs": {
                "common": ["-Xms4g", "-XX:+UseG1GC"],
                "linux": ["-Djava.library.path=./native/linux"],
                "windows": ["-Djava.library.path=./native/windows"]
              },
              "classpath": ["starfarer_obf.jar", "starfarer.api.jar"]
            }
            """.trimIndent()
        )

        val config = VanillaLaunchConfig.parse(file, "linux")

        assertEquals(listOf("-Xms4g", "-XX:+UseG1GC"), config.commonArgs)
        assertEquals(listOf("-Djava.library.path=./native/linux"), config.osArgs)
        assertEquals(listOf("starfarer_obf.jar", "starfarer.api.jar"), config.classpath)
    }

    @Test
    fun `缺失文件与缺字段时显式报错`() {
        val missing = dir.resolve("nope.json")
        val ex1 = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            VanillaLaunchConfig.parse(missing, "linux")
        }
        assertTrue(ex1.message!!.contains("launch-config.json 不存在"))

        val bad = dir.resolve("bad.json")
        bad.writeText("""{"classpath": []}""")
        val ex2 = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            VanillaLaunchConfig.parse(bad, "linux")
        }
        assertTrue(ex2.message!!.contains("jvmArgs"))
    }
}

class JavaRuntimeResolverImplTest {

    @Test
    fun `探测当前 JVM 可执行文件`() {
        val javaExt = if (JavaRuntimeResolverImpl.osKey() == "windows") ".exe" else ""
        val current = File(System.getProperty("java.home"), "bin/java$javaExt")

        val runtime = JavaRuntimeResolverImpl.probe(current)

        assertNotNull(runtime)
        assertNotNull(runtime!!.majorVersion)
        assertTrue(runtime.versionLine.isNotBlank())
        assertNotNull(runtime.vendor)
    }

    @Test
    fun `不兼容参数过滤：JDK 版本与 JBR 判定`() {
        val jdk17 = JavaRuntime(File("/x/java"), 17, "openjdk 17", false, "openjdk")
        val jdk25 = JavaRuntime(File("/x/java"), 25, "openjdk 25", false, "zulu")
        val jbr17 = JavaRuntime(File("/x/java"), 17, "openjdk 17 jbr", true, "jetbrains")
        val args = listOf("-Xms4g", "-XX:+UseCompactObjectHeaders", "-XX:+AllowEnhancedClassRedefinition")

        val (kept17, removed17) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jdk17)
        assertEquals(listOf("-Xms4g"), kept17)
        assertEquals(2, removed17.size)

        val (kept25, _) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jdk25)
        assertEquals(listOf("-Xms4g", "-XX:+UseCompactObjectHeaders"), kept25)

        val (keptJbr, _) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jbr17)
        assertEquals(listOf("-Xms4g", "-XX:+AllowEnhancedClassRedefinition"), keptJbr)
    }

    @Test
    fun `增强重定义参数：JBR 自动附加 其他 vendor 原样`() {
        val jbr25 = JavaRuntime(File("/x/java"), 25, "openjdk 25 jbr", true, "jetbrains")
        val zulu25 = JavaRuntime(File("/x/java"), 25, "openjdk 25 zulu", false, "zulu")
        val args = listOf("-Xms4g")

        assertEquals(
            listOf("-Xms4g", "-XX:+AllowEnhancedClassRedefinition"),
            JavaRuntimeResolverImpl.withEnhancedRedefinition(args, jbr25),
            "JBR 未声明时自动附加",
        )
        assertEquals(
            args + "-XX:+AllowEnhancedClassRedefinition",
            JavaRuntimeResolverImpl.withEnhancedRedefinition(
                args + "-XX:+AllowEnhancedClassRedefinition", jbr25,
            ),
            "已声明时不重复附加",
        )
        assertEquals(args, JavaRuntimeResolverImpl.withEnhancedRedefinition(args, zulu25), "非 JBR 原样返回")
    }

    @TempDir
    lateinit var fakeJdkDir: File

    /** 假 java：-version 输出给定版本与 vendor 行（与 RunFunctionalTest 的 fake-jre 同款模式）。 */
    private fun fakeJava(name: String, versionLine: String, vendorLine: String): File {
        val java = fakeJdkDir.resolve("$name/bin/java")
        java.parentFile.mkdirs()
        java.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "-version" ]; then
                echo '$versionLine'
                echo '$vendorLine'
                exit 0
            fi
            echo "FAKE-$name"
            """.trimIndent()
        )
        java.setExecutable(true)
        return java
    }

    @Test
    fun `probe 解析主版本号与 vendor`() {
        val zulu25 = fakeJava(
            "zulu25",
            """openjdk version "25.0.2" 2026-01-20 LTS""",
            "OpenJDK Runtime Environment Zulu25.48+15-CA (build 25.0.2+9-LTS)",
        )

        val runtime = JavaRuntimeResolverImpl.probe(zulu25)

        assertNotNull(runtime)
        assertEquals(25, runtime!!.majorVersion)
        assertEquals("zulu", runtime.vendor)
        assertEquals(false, runtime.isJetBrainsRuntime)
    }

    @Test
    fun `resolve 按主版本号过滤候选`() {
        val jdk17 = fakeJava("jdk17", """openjdk version "17.0.12" 2024-07-16""", "OpenJDK Runtime Environment Temurin-17.0.12+7")
        val jdk25 = fakeJava("jdk25", """openjdk version "25.0.2" 2026-01-20 LTS""", "OpenJDK Runtime Environment Zulu25.48+15-CA")

        val runtime = JavaRuntimeResolverImpl().resolve(
            gameDir = fakeJdkDir,
            configuredJava = listOf(jdk17, jdk25),
            configuredJavaHomes = emptyList(),
            requiredVersion = 25,
        )

        assertEquals(jdk25, runtime.executable)
    }

    @Test
    fun `resolve 按 vendor 过滤且 jbr 别名映射 jetbrains`() {
        val zulu = fakeJava("zulu25", """openjdk version "25.0.2" 2026-01-20 LTS""", "OpenJDK Runtime Environment Zulu25.48+15-CA")
        val jbr = fakeJava("jbr25", """openjdk version "25.0.2" 2026-01-20 LTS""", "OpenJDK Runtime Environment JBR-25.0.2+9 (build 25.0.2+9)")

        val runtime = JavaRuntimeResolverImpl().resolve(
            gameDir = fakeJdkDir,
            configuredJava = listOf(zulu, jbr),
            configuredJavaHomes = emptyList(),
            requiredVersion = 25,
            requiredVendor = "jbr",
        )

        assertEquals(jbr, runtime.executable)
        assertEquals("jetbrains", runtime.vendor)
    }

    @Test
    fun `过滤后无候选时报错并列出探测结果`() {
        val jdk17 = fakeJava("jdk17", """openjdk version "17.0.12" 2024-07-16""", "OpenJDK Runtime Environment Temurin-17.0.12+7")

        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            JavaRuntimeResolverImpl().resolve(
                gameDir = fakeJdkDir,
                configuredJava = listOf(jdk17),
                configuredJavaHomes = emptyList(),
                requiredVersion = 99,
            )
        }

        assertTrue(ex.message!!.contains("要求主版本 99"))
        assertTrue(ex.message!!.contains("jdk17"))
    }

    @TempDir
    lateinit var tierDir: File

    private val bundledDirName = when (JavaRuntimeResolverImpl.osKey()) {
        "windows" -> "zulu25_win"
        "mac" -> "zulu25_mac"
        else -> "zulu25_linux"
    }

    /** 造自带 zulu25 游戏目录与隔离 userHome（`.jdks` 下可放假 JBR）。 */
    private fun tierFixture(jbrName: String?, jbrVersionLine: String, jbrVendorLine: String): Pair<File, File> {
        val gameDir = tierDir.resolve("game")
        val zulu = gameDir.resolve("$bundledDirName/bin/java")
        zulu.parentFile.mkdirs()
        zulu.writeText(
            """
            #!/bin/sh
            echo 'openjdk version "25.0.2" 2026-01-20 LTS'
            echo 'OpenJDK Runtime Environment Zulu25.48+15-CA (build 25.0.2+9-LTS)'
            """.trimIndent()
        )
        zulu.setExecutable(true)

        val home = tierDir.resolve("home")
        if (jbrName != null) {
            val jbr = home.resolve(".jdks/$jbrName/bin/java")
            jbr.parentFile.mkdirs()
            jbr.writeText(
                """
                #!/bin/sh
                echo '$jbrVersionLine'
                echo '$jbrVendorLine'
                """.trimIndent()
            )
            jbr.setExecutable(true)
        }
        return gameDir to home
    }

    @Test
    fun `JBR25 默认优先于游戏自带 zulu25`() {
        val (gameDir, home) = tierFixture(
            "jbr-25.0.2", """openjdk version "25.0.2" 2026-01-20""",
            "OpenJDK Runtime Environment JBR-25.0.2+9 (build 25.0.2+9)",
        )

        val runtime = JavaRuntimeResolverImpl(userHome = home).resolve(
            gameDir = gameDir,
            configuredJava = emptyList(),
            configuredJavaHomes = emptyList(),
            requiredVersion = 25,
        )

        assertEquals("jetbrains", runtime.vendor)
        assertTrue(runtime.executable.path.contains("jbr-25.0.2"), "应选中 ~/.jdks 的 JBR25: ${runtime.executable}")
    }

    @Test
    fun `JBR17 被保护栏拦截 不抢自带 zulu25（无版本约束亦不选旧 JBR）`() {
        val (gameDir, home) = tierFixture(
            "jbr-17.0.14", """openjdk version "17.0.14" 2025-01-21""",
            "OpenJDK Runtime Environment JBR-17.0.14+7 (build 17.0.14+7)",
        )

        val runtime = JavaRuntimeResolverImpl(userHome = home).resolve(
            gameDir = gameDir,
            configuredJava = emptyList(),
            configuredJavaHomes = emptyList(),
            requiredVersion = null,
        )

        assertEquals("zulu", runtime.vendor)
        assertTrue(runtime.executable.path.contains(bundledDirName), "应选中自带 zulu25: ${runtime.executable}")
    }

    @Test
    fun `显式 vendor=zulu 时自带运行时优先于 JBR25`() {
        val (gameDir, home) = tierFixture(
            "jbr-25.0.2", """openjdk version "25.0.2" 2026-01-20""",
            "OpenJDK Runtime Environment JBR-25.0.2+9 (build 25.0.2+9)",
        )

        val runtime = JavaRuntimeResolverImpl(userHome = home).resolve(
            gameDir = gameDir,
            configuredJava = emptyList(),
            configuredJavaHomes = emptyList(),
            requiredVersion = 25,
            requiredVendor = "zulu",
        )

        assertEquals("zulu", runtime.vendor)
        assertTrue(runtime.executable.path.contains(bundledDirName), "显式 zulu 应选中自带运行时: ${runtime.executable}")
    }

    @Test
    fun `显式配置的 java 优先于 JBR 层`() {
        val (gameDir, home) = tierFixture(
            "jbr-25.0.2", """openjdk version "25.0.2" 2026-01-20""",
            "OpenJDK Runtime Environment JBR-25.0.2+9 (build 25.0.2+9)",
        )
        val configured = fakeJava(
            "configured25", """openjdk version "25.0.2" 2026-01-20 LTS""",
            "OpenJDK Runtime Environment Temurin-25.0.2+9",
        )

        val runtime = JavaRuntimeResolverImpl(userHome = home).resolve(
            gameDir = gameDir,
            configuredJava = listOf(configured),
            configuredJavaHomes = emptyList(),
            requiredVersion = 25,
        )

        assertEquals(configured, runtime.executable)
    }
}
