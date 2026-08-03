package io.github.nanoforged.sdg.nanoforge

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * R5 功能测试：nanoforge.mod.toml 生成（字段完整性、[libraries] sha256）、
 * coremod.toml 生成与校验、mods/coremods 双落位部署。
 */
class NanoForgeFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--console=plain")

    /** 假本地 maven 仓里的 test-lib jar；测试用它验证 [libraries] 解析与 sha256。 */
    private lateinit var testLibJar: File

    private fun writeProject(nanoforgeBlock: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testmod"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sectordevgradle.nanoforge") }

            version = "1.0"

            repositories {
                maven { url = uri("repo") }
            }

            starsector {
                modId.set("testmod")
                modName.set("Test Mod")
                author.set("Tester")
                description.set("功能测试模组")
                gameVersion.set("0.98a-RC8")
                modPlugin.set("com.example.TestModPlugin")
                dependency("lw_lazylib", "LazyLib")
                gameDependencyMode.set(io.github.nanoforged.sdg.GameDependencyMode.GAME_DIR)
                gameDir.set(layout.projectDirectory.dir("game"))
            }

            nanoforge {
                $nanoforgeBlock
            }
            """.trimIndent()
        )
        projectDir.resolve("game/starfarer-core").mkdirs()
        projectDir.resolve("game/mods").mkdirs()

        // 假本地 maven 仓：g:test-lib:1.0
        testLibJar = projectDir.resolve("repo/g/test-lib/1.0/test-lib-1.0.jar")
        testLibJar.parentFile.mkdirs()
        testLibJar.writeBytes("fake test-lib jar bytes".toByteArray())
        projectDir.resolve("repo/g/test-lib/1.0/test-lib-1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId>
                <artifactId>test-lib</artifactId>
                <version>1.0</version>
                <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )
    }

    private fun jarEntries(jarPath: File): List<String> =
        ZipFile(jarPath).use { zf -> zf.entries().toList().map { it.name } }

    @Test
    fun `modProduction 生成 nanoforge_mod_toml 且字段完整、libraries 带 sha256`() {
        writeProject(
            """
            library("g:test-lib:1.0")
            library("g:test-lib:1.0", providedBy = "game")
            """.trimIndent()
        )

        runner("modProduction").build()

        val toml = projectDir.resolve("build/mod_production/nanoforge.mod.toml")
        assertTrue(toml.isFile, "nanoforge.mod.toml 未生成")
        val content = toml.readText()
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(testLibJar.readBytes())
            .joinToString("") { "%02x".format(it) }
        val expected = """
            id = "testmod"
            name = "Test Mod"
            version = "1.0"
            author = "Tester"
            description = "功能测试模组"
            gameVersion = "0.98a-RC8"
            modPlugin = "com.example.TestModPlugin"
            jars = ["jars/testmod-1.0.jar"]

            [[dependencies]]
            id = "lw_lazylib"
            name = "LazyLib"

            [[libraries]]
            group = "g"
            artifact = "test-lib"
            version = "1.0"
            sha256 = "$expectedSha"

            [[libraries]]
            group = "g"
            artifact = "test-lib"
            version = "1.0"
            sha256 = "$expectedSha"
            providedBy = "game"
        """.trimIndent() + "\n"
        assertEquals(expected, content)

        // 契约 §1：nanoforge.mod.toml 位于 mod jar 根
        val entries = jarEntries(projectDir.resolve("build/libs/testmod-1.0.jar"))
        assertTrue("nanoforge.mod.toml" in entries, "jar 根缺少 nanoforge.mod.toml：$entries")
    }

    @Test
    fun `coremod 形态：coremod_toml 打进 jar 根且 deployMod 双落位`() {
        writeProject(
            """
            coremod.set(true)
            pluginClass.set("com.example.TestCorePlugin")
            authors.add("Tester")
            asmTransformers.add("com.example.MyTransformer")
            mixinConfigs.add("testmod.mixins.json")
            patchEntries.add("patches/demo_MyClass.binpatch")
            """.trimIndent()
        )

        runner("deployMod").build()

        val jar = projectDir.resolve("build/libs/testmod-1.0.jar")
        ZipFile(jar).use { zf ->
            val entry = zf.getEntry("coremod.toml")
            assertTrue(entry != null, "jar 根缺少 coremod.toml")
            val content = zf.getInputStream(entry).bufferedReader().readText()
            val expected = """
                id = "testmod"
                name = "Test Mod"
                version = "1.0"
                authors = ["Tester"]
                description = "功能测试模组"
                pluginClass = "com.example.TestCorePlugin"

                [asm]
                transformers = ["com.example.MyTransformer"]

                [mixin]
                configs = ["testmod.mixins.json"]

                [patch]
                entries = ["patches/demo_MyClass.binpatch"]
            """.trimIndent() + "\n"
            assertEquals(expected, content)
        }

        // 双落位：mods/testmod/（普通 mod 通道）+ mods/coremods/（NanoForge 通道）
        assertTrue(projectDir.resolve("game/mods/testmod/jars/testmod-1.0.jar").isFile, "普通 mod 落位缺失")
        assertTrue(projectDir.resolve("game/mods/coremods/testmod-1.0.jar").isFile, "coremods 落位缺失")
    }

    @Test
    fun `非 coremod 形态：不生成 coremod_toml 也不做 coremods 落位`() {
        writeProject("")

        runner("deployMod").build()

        val entries = jarEntries(projectDir.resolve("build/libs/testmod-1.0.jar"))
        assertFalse("coremod.toml" in entries, "非 coremod 形态不应生成 coremod.toml：$entries")
        assertFalse(projectDir.resolve("game/mods/coremods").exists(), "非 coremod 形态不应有 coremods 落位")
        assertTrue(projectDir.resolve("game/mods/testmod/jars/testmod-1.0.jar").isFile, "普通 mod 落位缺失")
    }

    @Test
    fun `coremod 形态缺 pluginClass 时显式报错`() {
        writeProject("coremod.set(true)")

        val result = runner("jar").buildAndFail()

        assertTrue(
            result.output.contains("nanoforge.pluginClass 未设置"),
            "应提示 pluginClass 必填：\n${result.output}",
        )
    }
}
