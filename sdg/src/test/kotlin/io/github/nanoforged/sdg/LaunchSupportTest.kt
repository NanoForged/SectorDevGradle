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
    }

    @Test
    fun `不兼容参数过滤：JDK 版本与 JBR 判定`() {
        val jdk17 = JavaRuntime(File("/x/java"), 17, "openjdk 17", false)
        val jdk25 = JavaRuntime(File("/x/java"), 25, "openjdk 25", false)
        val jbr17 = JavaRuntime(File("/x/java"), 17, "openjdk 17 jbr", true)
        val args = listOf("-Xms4g", "-XX:+UseCompactObjectHeaders", "-XX:+AllowEnhancedClassRedefinition")

        val (kept17, removed17) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jdk17)
        assertEquals(listOf("-Xms4g"), kept17)
        assertEquals(2, removed17.size)

        val (kept25, _) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jdk25)
        assertEquals(listOf("-Xms4g", "-XX:+UseCompactObjectHeaders"), kept25)

        val (keptJbr, _) = JavaRuntimeResolverImpl.filterIncompatibleArgs(args, jbr17)
        assertEquals(listOf("-Xms4g", "-XX:+AllowEnhancedClassRedefinition"), keptJbr)
    }
}
