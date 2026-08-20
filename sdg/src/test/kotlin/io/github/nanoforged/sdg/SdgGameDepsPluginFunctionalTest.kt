package io.github.nanoforged.sdg

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * gamedeps 轻量插件功能测试：独立 apply（非 mod 工程场景）后的依赖装配验证——
 * gameLibraries 配置创建与排除项、named 4 jar compileOnly、`-P` 属性覆盖、enabled 开关。
 */
class SdgGameDepsPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun writeBuild(script: String, taskName: String = "printDeps") {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testdeps"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sectordevgradle.gamedeps") }

            $script

            tasks.register("$taskName") {
                doLast {
                    println("GL_EXISTS=" + configurations.names.contains("gameLibraries"))
                    val glDeps = configurations["gameLibraries"].allDependencies
                        .map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                    println("GL=" + glDeps.sorted().joinToString(","))
                    val cpDeps = configurations["compileClasspath"].allDependencies
                        .map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                        .sorted()
                    println("CP=" + cpDeps.joinToString(","))
                }
            }
            """.trimIndent()
        )
    }

    private fun runBuild(vararg extraArgs: String): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*extraArgs, "--console=plain")
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

    /** 标准夹具：4 个 named 坐标 + 两个 game 库（含默认应被排除的 xstream-1.4.10）。 */
    private fun writeFakeRepo(repo: File, version: String = "0.98a-RC8-SNAPSHOT") {
        SdgGameDepsPlugin.NAMED_GAME_ARTIFACTS.forEach { writeFakeNamedArtifact(repo, it, version) }
        writeFakeGameLibrary(repo, "xstream-1.4.21_miko", version)
        writeFakeGameLibrary(repo, "xstream-1.4.10", version)
        writeFakeGameLibrary(repo, "janino-3.1.11", version)
    }

    @Test
    fun `gameLibraries 配置创建、named 4 jar 进 compileClasspath、默认排除 xstream-1_4_10`() {
        val repo = projectDir.resolve("repo")
        writeFakeRepo(repo)
        writeBuild(
            """
            starsectorDeps {
                sourceRepo.set(layout.projectDirectory.dir("repo"))
                gameVersion.set("0.98a-RC8")
            }
            """.trimIndent()
        )

        val output = runBuild("printDeps")

        assertTrue(output.contains("GL_EXISTS=true"), "gameLibraries 配置未创建：\n$output")
        assertTrue(
            output.contains("GL=starsector.game:janino-3.1.11:0.98a-RC8-SNAPSHOT,starsector.game:xstream-1.4.21_miko:0.98a-RC8-SNAPSHOT"),
            "gameLibraries 应含 miko 版 xstream 与 janino，不含 xstream-1.4.10：\n$output",
        )
        assertFalse(output.contains("starsector.game:xstream-1.4.10"), "默认排除项未生效：\n$output")
        SdgGameDepsPlugin.NAMED_GAME_ARTIFACTS.forEach {
            assertTrue(
                output.contains("starsector.named:$it:0.98a-RC8-SNAPSHOT"),
                "缺少 $it 的 named 依赖：\n$output",
            )
        }
    }

    @Test
    fun `-Psourcesector_namedRepo 与 -Pstarsector_gameVersion 覆盖默认值生效`() {
        val repo = projectDir.resolve("repo")
        writeFakeRepo(repo)
        writeBuild("")

        val output = runBuild(
            "printDeps",
            "-Psourcesector.namedRepo=repo",
            "-Pstarsector.gameVersion=0.98a-RC8",
        )

        SdgGameDepsPlugin.NAMED_GAME_ARTIFACTS.forEach {
            assertTrue(output.contains("starsector.named:$it:0.98a-RC8-SNAPSHOT"), "缺少 $it：\n$output")
        }
        assertTrue(
            output.contains("starsector.game:janino-3.1.11:0.98a-RC8-SNAPSHOT"),
            "-P 覆盖路径未生效：\n$output",
        )
    }

    @Test
    fun `enabled=false 时跳过装配且不报错`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testdeps"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sectordevgradle.gamedeps") }

            starsectorDeps {
                enabled.set(false)
                sourceRepo.set(layout.projectDirectory.dir("不存在的仓"))
            }

            tasks.register("checkDisabled") {
                doLast {
                    println("GL_EXISTS=" + configurations.names.contains("gameLibraries"))
                    println("CP_EMPTY=" + configurations["compileClasspath"].allDependencies.isEmpty())
                }
            }
            """.trimIndent()
        )

        val output = runBuild("checkDisabled")

        assertTrue(output.contains("GL_EXISTS=false"), "禁用后不应创建 gameLibraries：\n$output")
        assertTrue(output.contains("CP_EMPTY=true"), "禁用后 compileClasspath 不应有装配依赖：\n$output")
    }
}
