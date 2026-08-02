package io.github.nanoforged.sdg

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

/**
 * R4 功能测试：IDEA 配置生成、双启动模式的装配与错误面、decompileDependencies 真实反编译。
 */
class RunFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--console=plain")

    private fun writeProject(sdgBlock: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testmod"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sdg.mod") }

            sdg {
                modId.set("testmod")
                gameVersion.set("0.98a-RC8")
                gameDependencyMode.set(io.github.nanoforged.sdg.GameDependencyMode.GAME_DIR)
                gameDir.set(layout.projectDirectory.dir("game"))
                $sdgBlock
            }
            """.trimIndent()
        )
        projectDir.resolve("game/starfarer-core").mkdirs()
        projectDir.resolve("game/mods").mkdirs()
    }

    @Test
    fun `genIdeaRuns 生成运行与 Attach 配置`() {
        writeProject("")

        runner("genIdeaRuns").build()

        val runXml = projectDir.resolve(".run/SDG-runGame.run.xml")
        val attachXml = projectDir.resolve(".run/SDG-Attach.run.xml")
        assertTrue(runXml.isFile && runXml.readText().contains("runGame"))
        assertTrue(attachXml.isFile && attachXml.readText().contains("\"PORT\" value=\"5005\""))
    }

    @Test
    fun `NANOFORGE 模式：前置检查失败时携带失败项明细`() {
        writeProject("")

        val result = runner("runGame").buildAndFail()

        assertTrue(
            result.output.contains("启动前置检查失败"),
            "应输出 launch-spec 前置检查失败明细：\n${result.output}",
        )
    }

    @Test
    fun `VANILLA 模式：缺 launch-config_json 时显式报错`() {
        writeProject("launchMode.set(io.github.nanoforged.sdg.LaunchMode.VANILLA)")

        val result = runner("runGame").buildAndFail()

        assertTrue(
            result.output.contains("launch-config.json 不存在"),
            "应提示 launch-config.json 缺失：\n${result.output}",
        )
    }

    @Test
    fun `VANILLA 模式：显式 javaExec 生效且不被项目 toolchain 冲突拦截`() {
        // 假 java：-version 探测输出版本行，其余调用回显标记即退出。
        // Asteria 迁移实测：项目配置 java toolchain 时 JavaExec 的 javaLauncher convention
        // 与 executable 并存触发 Gradle toolchain 一致性校验，本用例防回归。
        val fakeJava = projectDir.resolve("fake-jre/bin/java")
        fakeJava.parentFile.mkdirs()
        fakeJava.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "-version" ]; then
                echo 'openjdk version "17.0.12" 2024-07-16'
                exit 0
            fi
            echo "FAKE-JAVA-EXECUTED"
            """.trimIndent()
        )
        fakeJava.setExecutable(true)
        projectDir.resolve("launch-config.json").writeText(
            """{"jvmArgs": {"common": ["-Xmx1g"], "linux": []}, "classpath": ["starfarer-res.jar"]}"""
        )
        writeProject("launchMode.set(io.github.nanoforged.sdg.LaunchMode.VANILLA)")
        projectDir.resolve("build.gradle.kts").apply {
            writeText(
                readText() + "\n" + """
                java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
                """.trimIndent()
            )
        }

        val result = runner("runGame", "-Pstarsector.javaExec=${fakeJava.absolutePath}").build()

        assertTrue(
            result.output.contains("FAKE-JAVA-EXECUTED"),
            "runGame 应执行 starsector.javaExec 指定的 java：\n${result.output}",
        )
    }

    @Test
    fun `decompileDependencies 真实反编译 compileOnly 依赖`() {
        // 编译一个带方法体的夹具类进游戏目录，作为 compileOnly 依赖来源
        val workDir = projectDir.resolve("fixture")
        val srcDir = workDir.resolve("src")
        val outDir = workDir.resolve("out")
        srcDir.mkdirs()
        outDir.mkdirs()
        val src = srcDir.resolve("GameLib.java")
        src.writeText("public class GameLib { public static int answer() { return 42; } }")
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", outDir.absolutePath, src.absolutePath)
        check(rc == 0) { "夹具类编译失败" }
        val gameJar = projectDir.resolve("game/game-lib.jar")
        gameJar.parentFile.mkdirs()
        ZipOutputStream(gameJar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("GameLib.class"))
            zip.write(outDir.resolve("GameLib.class").readBytes())
            zip.closeEntry()
        }
        writeProject("")

        val result = runner("decompileDependencies").build()

        assertTrue(result.output.contains("反编译 game-lib.jar"), "应执行反编译：\n${result.output}")
        val decompiled = projectDir.resolve("dev-resources/sources/GameLib.java")
        assertTrue(decompiled.isFile, "反编译产物缺失：\n${result.output}")
        assertTrue(decompiled.readText().contains("answer"), "反编译内容异常")
    }
}
