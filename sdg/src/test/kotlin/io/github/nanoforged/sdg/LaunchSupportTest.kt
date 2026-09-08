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
}
