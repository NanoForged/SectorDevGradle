package io.github.nanoforged.sdg

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * R1 功能测试：三种游戏依赖来源的端到端装配验证。
 * 消费方构建注册 printCp 任务打印 compileClasspath，断言游戏/mod jar 已正确进入。
 */
class SdgModPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun writeBuild(script: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testmod"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sectordevgradle.mod") }

            $script

            tasks.register("printCp") {
                doLast {
                    println("CP=" + configurations["compileClasspath"].resolve()
                        .map { it.name }.sorted().joinToString(","))
                }
            }
            """.trimIndent()
        )
    }

    private fun runBuild(): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("printCp", "--console=plain")
            .build()
            .output

    private fun writeEmptyJar(file: File) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).close()
    }

    /** 构造最小可解析的 SNAPSHOT maven 仓条目（localCopy 布局，指向非时间戳 jar）。 */
    private fun writeFakeNamedArtifact(repo: File, artifact: String, version: String) {
        val dir = repo.resolve("starsector/named/$artifact/$version")
        writeEmptyJar(dir.resolve("$artifact-$version.jar"))
        dir.resolve("$artifact-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>starsector.named</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )
        dir.resolve("maven-metadata.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>starsector.named</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <versioning>
                <snapshot><localCopy>true</localCopy></snapshot>
                <lastUpdated>20260802000000</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>$version</value>
                    <updated>20260802000000</updated>
                  </snapshotVersion>
                </snapshotVersions>
                <versions><version>$version</version></versions>
              </versioning>
            </metadata>
            """.trimIndent()
        )
    }

    /** 构造 `starsector.game` 组（游戏自带第三方库透传 jar）的最小可解析 SNAPSHOT 仓条目。 */
    private fun writeFakeGameLibrary(repo: File, artifact: String, version: String) {
        val dir = repo.resolve("starsector/game/$artifact/$version")
        writeEmptyJar(dir.resolve("$artifact-$version.jar"))
        dir.resolve("$artifact-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>starsector.game</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )
        dir.resolve("maven-metadata.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>starsector.game</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <versioning>
                <snapshot><localCopy>true</localCopy></snapshot>
                <lastUpdated>20260802000000</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>$version</value>
                    <updated>20260802000000</updated>
                  </snapshotVersion>
                </snapshotVersions>
                <versions><version>$version</version></versions>
              </versioning>
            </metadata>
            """.trimIndent()
        )
    }

    @Test
    fun `NAMED_REPO 模式：SourceSector 仓四个 named 坐标进入 compileClasspath`() {
        val repo = projectDir.resolve("repo")
        SdgExtension.NAMED_GAME_ARTIFACTS.forEach { writeFakeNamedArtifact(repo, it, "0.98a-RC8-SNAPSHOT") }
        writeFakeGameLibrary(repo, "xstream-1.4.21_miko", "0.98a-RC8-SNAPSHOT")
        writeBuild(
            """
            starsector {
                modId.set("testmod")
                gameVersion.set("0.98a-RC8")
                sourceRepo.set(layout.projectDirectory.dir("repo"))
            }
            """.trimIndent()
        )

        val output = runBuild()

        SdgExtension.NAMED_GAME_ARTIFACTS.forEach {
            assertTrue(output.contains("$it-0.98a-RC8-SNAPSHOT.jar"), "缺少 $it 的 named jar：\n$output")
        }
    }

    @Test
    fun `GAME_DIR 模式：游戏根目录与 starfarer-core 的 jar 进入 compileClasspath`() {
        val gameDir = projectDir.resolve("game")
        writeEmptyJar(gameDir.resolve("game-lib.jar"))
        writeEmptyJar(gameDir.resolve("starfarer-core/starfarer_obf.jar"))
        writeBuild(
            """
            starsector {
                modId.set("testmod")
                gameDependencyMode.set(io.github.nanoforged.sdg.GameDependencyMode.GAME_DIR)
                gameDir.set(layout.projectDirectory.dir("game"))
            }
            """.trimIndent()
        )

        val output = runBuild()

        assertTrue(output.contains("game-lib.jar"), "缺少游戏根目录 jar：\n$output")
        assertTrue(output.contains("starfarer_obf.jar"), "缺少 starfarer-core jar：\n$output")
    }

    @Test
    fun `第三方 mod 依赖桥：扫描 mods 目录 jars 字段并跳过自身`() {
        val gameDir = projectDir.resolve("game")
        writeEmptyJar(gameDir.resolve("starfarer-core/starfarer_obf.jar"))
        val otherMod = gameDir.resolve("mods/LazyLib")
        otherMod.mkdirs()
        otherMod.resolve("mod_info.json").writeText(
            """
            {
                # 宽松格式：注释与尾逗号
                "id": "lw_lazylib",
                "jars": ["jars/LazyLib.jar",],
            }
            """.trimIndent()
        )
        writeEmptyJar(otherMod.resolve("jars/LazyLib.jar"))
        val selfMod = gameDir.resolve("mods/TestMod")
        selfMod.mkdirs()
        selfMod.resolve("mod_info.json").writeText("""{"id": "testmod", "jars": ["jars/Self.jar"]}""")
        writeEmptyJar(selfMod.resolve("jars/Self.jar"))
        writeBuild(
            """
            starsector {
                modId.set("testmod")
                gameDependencyMode.set(io.github.nanoforged.sdg.GameDependencyMode.GAME_DIR)
                gameDir.set(layout.projectDirectory.dir("game"))
            }
            """.trimIndent()
        )

        val output = runBuild()

        assertTrue(output.contains("LazyLib.jar"), "第三方 mod jar 未进入 compileClasspath：\n$output")
        assertTrue(!output.contains("Self.jar"), "自身已部署 jar 不应进入 compileClasspath：\n$output")
    }

    @Test
    fun `NAMED_REPO 模式：starsector_game 组第三方库全部进入 compileClasspath`() {
        val repo = projectDir.resolve("repo")
        SdgExtension.NAMED_GAME_ARTIFACTS.forEach { writeFakeNamedArtifact(repo, it, "0.98a-RC8-SNAPSHOT") }
        writeFakeGameLibrary(repo, "xstream-1.4.21_miko", "0.98a-RC8-SNAPSHOT")
        writeFakeGameLibrary(repo, "janino-3.1.11", "0.98a-RC8-SNAPSHOT")
        writeBuild(
            """
            starsector {
                modId.set("testmod")
                gameVersion.set("0.98a-RC8")
                sourceRepo.set(layout.projectDirectory.dir("repo"))
            }
            """.trimIndent()
        )

        val output = runBuild()

        assertTrue(
            output.contains("xstream-1.4.21_miko-0.98a-RC8-SNAPSHOT.jar"),
            "缺少 starsector.game 第三方库：\n$output",
        )
        assertTrue(
            output.contains("janino-3.1.11-0.98a-RC8-SNAPSHOT.jar"),
            "缺少 starsector.game 第三方库：\n$output",
        )
    }

    @Test
    fun `NAMED_REPO 模式：starsector_game 组缺失时硬失败并提示先发布`() {
        val repo = projectDir.resolve("repo")
        SdgExtension.NAMED_GAME_ARTIFACTS.forEach { writeFakeNamedArtifact(repo, it, "0.98a-RC8-SNAPSHOT") }
        writeBuild(
            """
            starsector {
                modId.set("testmod")
                gameVersion.set("0.98a-RC8")
                sourceRepo.set(layout.projectDirectory.dir("repo"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("printCp", "--console=plain")
            .buildAndFail()

        assertTrue(
            result.output.contains("starsector/game 组不存在或为空"),
            "应提示先运行 SourceSector 发布：\n${result.output}",
        )
    }
}
