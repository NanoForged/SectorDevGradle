package io.github.nanoforged.sdg

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * R2 功能测试：mod 产物布局、mod_info.json 生成、zip 打包、deployMod 与 enabled_mods.json 维护。
 */
class ModProductionFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun runBuild(vararg args: String): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--console=plain")
            .build()
            .output

    private fun prepareProject() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testmod"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sdg.mod") }

            version = "1.0"

            sdg {
                modId.set("testmod")
                modName.set("Test Mod")
                author.set("Tester")
                description.set("功能测试模组")
                gameVersion.set("0.98a-RC8")
                modPlugin.set("com.example.TestModPlugin")
                dependency("lw_lazylib", "LazyLib")
                dependency("MagicLib", version = "1.4.0")
                gameDependencyMode.set(io.github.nanoforged.sdg.GameDependencyMode.GAME_DIR)
                gameDir.set(layout.projectDirectory.dir("game"))
                manageEnabledMods.set(true)
            }
            """.trimIndent()
        )
        // 静态内容
        projectDir.resolve("contents/data/config.txt").apply {
            parentFile.mkdirs()
            writeText("key=value")
        }
        // 假游戏目录
        val gameDir = projectDir.resolve("game")
        gameDir.resolve("starfarer-core").mkdirs()
        gameDir.resolve("mods/enabled_mods.json").apply {
            parentFile.mkdirs()
            writeText("""{"enabledMods": []}""")
        }
    }

    @Test
    fun `modProduction 组装产物布局与 mod_info_json`() {
        prepareProject()

        runBuild("modProduction")

        val production = projectDir.resolve("build/mod_production")
        assertTrue(production.resolve("data/config.txt").isFile, "静态内容未同步")
        val jars = production.resolve("jars").listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf("testmod-1.0.jar"), jars)

        @Suppress("UNCHECKED_CAST")
        val modInfo = JsonSlurper().parse(production.resolve("mod_info.json")) as Map<String, Any?>
        assertEquals("testmod", modInfo["id"])
        assertEquals("Test Mod", modInfo["name"])
        assertEquals("1.0", modInfo["version"])
        assertEquals("Tester", modInfo["author"])
        assertEquals("功能测试模组", modInfo["description"])
        assertEquals("0.98a-RC8", modInfo["gameVersion"])
        assertEquals("com.example.TestModPlugin", modInfo["modPlugin"])
        assertEquals(listOf("jars/testmod-1.0.jar"), modInfo["jars"])

        @Suppress("UNCHECKED_CAST")
        val deps = modInfo["dependencies"] as List<Map<String, String>>
        assertEquals(
            listOf(
                mapOf("id" to "lw_lazylib", "name" to "LazyLib"),
                mapOf("id" to "MagicLib", "version" to "1.4.0"),
            ),
            deps,
        )
    }

    @Test
    fun `mod_info_json 的 jars 字段主 jar 排首位`() {
        prepareProject()
        // 附加 classifier jar：字典序下 "-agent" 会排在主 jar 名前（'-' < '.'），
        // 本用例验证排序按“无 classifier 主 jar 优先”而非纯字典序。
        projectDir.resolve("build.gradle.kts").apply {
            writeText(
                readText() + """
                |
                |val agentJar = tasks.register<Jar>("agentJar") { archiveClassifier.set("agent") }
                |val sourcesJar = tasks.register<Jar>("sourcesJar") { archiveClassifier.set("sources") }
                """.trimMargin()
            )
        }

        runBuild("modProduction")

        @Suppress("UNCHECKED_CAST")
        val modInfo = JsonSlurper().parse(projectDir.resolve("build/mod_production/mod_info.json"))
            as Map<String, Any?>
        assertEquals(
            listOf(
                "jars/testmod-1.0.jar",
                "jars/testmod-1.0-agent.jar",
                "jars/testmod-1.0-sources.jar",
            ),
            modInfo["jars"],
        )
    }

    @Test
    fun `zipModProduction 产出发布 zip 且由 assemble 触发`() {
        prepareProject()

        runBuild("assemble")

        val zip = projectDir.resolve("build/Test Mod-1.0.zip")
        assertTrue(zip.isFile, "zip 产物不存在")
        ZipFile(zip).use { zf ->
            assertTrue(zf.getEntry("mod_info.json") != null, "zip 缺少 mod_info.json")
            assertTrue(zf.getEntry("jars/testmod-1.0.jar") != null, "zip 缺少模组 jar")
            assertTrue(zf.getEntry("data/config.txt") != null, "zip 缺少静态内容")
        }
    }

    @Test
    fun `deployMod 覆盖式部署并维护 enabled_mods_json`() {
        prepareProject()
        // 预置旧部署残留，验证覆盖语义
        val stale = projectDir.resolve("game/mods/testmod/old.txt")
        stale.parentFile.mkdirs()
        stale.writeText("stale")

        runBuild("deployMod")

        val deployed = projectDir.resolve("game/mods/testmod")
        assertTrue(deployed.resolve("mod_info.json").isFile, "mod_info.json 未部署")
        assertTrue(deployed.resolve("jars/testmod-1.0.jar").isFile, "模组 jar 未部署")

        @Suppress("UNCHECKED_CAST")
        val enabled = JsonSlurper().parse(projectDir.resolve("game/mods/enabled_mods.json"))
            as Map<String, Any?>
        assertEquals(listOf("testmod"), enabled["enabledMods"])
    }
}
