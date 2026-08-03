package io.github.nanoforged.sdg

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

/**
 * R3 功能测试：OBF 形态全链路——真实调用 SourceSector mapping 工具（mavenLocal）执行 reobf，
 * shade 合并第三方库，verifyObfJar 质量门通过。
 */
class ReobfFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** 用测试 JVM 的编译器把单文件源码编译为 class，打进指定 jar。 */
    private fun compileAndJar(className: String, source: String, jarFile: File) {
        val workDir = projectDir.resolve("compile-${className.replace('.', '-')}")
        val srcDir = workDir.resolve("src")
        val outDir = workDir.resolve("out")
        srcDir.mkdirs()
        outDir.mkdirs()
        val sourceFile = srcDir.resolve("${className.substringAfterLast('.')}.java")
        sourceFile.writeText(source)

        val compiler = ToolProvider.getSystemJavaCompiler()
        val rc = compiler.run(null, null, null, "-d", outDir.absolutePath, sourceFile.absolutePath)
        check(rc == 0) { "测试夹具类编译失败：$className" }

        jarFile.parentFile.mkdirs()
        ZipOutputStream(jarFile.outputStream()).use { zip ->
            val entryName = "${className.replace('.', '/')}.class"
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(outDir.resolve(entryName).readBytes())
            zip.closeEntry()
        }
    }

    private fun writeFakeNamedArtifact(repo: File, artifact: String, version: String, withNamedClass: Boolean) {
        val dir = repo.resolve("starsector/named/$artifact/$version")
        if (withNamedClass) {
            compileAndJar(
                "com.fs.starfarer.NamedClass",
                "package com.fs.starfarer; public class NamedClass {}",
                dir.resolve("$artifact-$version.jar"),
            )
        } else {
            dir.mkdirs()
            ZipOutputStream(dir.resolve("$artifact-$version.jar").outputStream()).close()
        }
        dir.resolve("$artifact-$version.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                |<groupId>starsector.named</groupId><artifactId>$artifact</artifactId>
                |<version>$version</version><packaging>jar</packaging></project>""".trimMargin()
        )
        dir.resolve("maven-metadata.xml").writeText(
            """<metadata><groupId>starsector.named</groupId><artifactId>$artifact</artifactId>
                |<version>$version</version><versioning>
                |<snapshot><localCopy>true</localCopy></snapshot><lastUpdated>20260802000000</lastUpdated>
                |<snapshotVersions><snapshotVersion><extension>jar</extension><value>$version</value>
                |<updated>20260802000000</updated></snapshotVersion></snapshotVersions>
                |<versions><version>$version</version></versions></versioning></metadata>""".trimMargin()
        )
    }

    private fun writeMappingsArtifact(repo: File, version: String) {
        val dir = repo.resolve("starsector/named/mappings-windows/$version")
        dir.mkdirs()
        dir.resolve("mappings-windows-$version.tiny").writeText(
            "tiny\t2\t0\tobf\tintermediary\tnamed\n" +
                "c\taaa/bbb/ObfClass\taaa/bbb/ObfClass\tcom/fs/starfarer/NamedClass\n"
        )
        dir.resolve("mappings-windows-$version.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                |<groupId>starsector.named</groupId><artifactId>mappings-windows</artifactId>
                |<version>$version</version><packaging>jar</packaging></project>""".trimMargin()
        )
        dir.resolve("maven-metadata.xml").writeText(
            """<metadata><groupId>starsector.named</groupId><artifactId>mappings-windows</artifactId>
                |<version>$version</version><versioning>
                |<snapshot><localCopy>true</localCopy></snapshot><lastUpdated>20260802000000</lastUpdated>
                |<snapshotVersions>
                |<snapshotVersion><extension>tiny</extension><value>$version</value>
                |<updated>20260802000000</updated></snapshotVersion>
                |<snapshotVersion><extension>pom</extension><value>$version</value>
                |<updated>20260802000000</updated></snapshotVersion>
                |</snapshotVersions>
                |<versions><version>$version</version></versions></versioning></metadata>""".trimMargin()
        )
    }

    /** 构造 `starsector.game` 组（游戏自带第三方库透传 jar）最小仓条目，wireGameLibraries 硬门禁需要。 */
    private fun writeFakeGameLibrary(repo: File, artifact: String, version: String) {
        val dir = repo.resolve("starsector/game/$artifact/$version")
        dir.mkdirs()
        ZipOutputStream(dir.resolve("$artifact-$version.jar").outputStream()).close()
        dir.resolve("$artifact-$version.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                |<groupId>starsector.game</groupId><artifactId>$artifact</artifactId>
                |<version>$version</version><packaging>jar</packaging></project>""".trimMargin()
        )
        dir.resolve("maven-metadata.xml").writeText(
            """<metadata><groupId>starsector.game</groupId><artifactId>$artifact</artifactId>
                |<version>$version</version><versioning>
                |<snapshot><localCopy>true</localCopy></snapshot><lastUpdated>20260802000000</lastUpdated>
                |<snapshotVersions><snapshotVersion><extension>jar</extension><value>$version</value>
                |<updated>20260802000000</updated></snapshotVersion></snapshotVersions>
                |<versions><version>$version</version></versions></versioning></metadata>""".trimMargin()
        )
    }

    @Test
    fun `OBF 形态：reobf 重映射 + shade 合并 + 质量门通过`() {
        val version = "0.98a-RC8-SNAPSHOT"
        val repo = projectDir.resolve("repo")
        SdgExtension.NAMED_GAME_ARTIFACTS.forEach {
            writeFakeNamedArtifact(repo, it, version, withNamedClass = it == "starfarer_obf")
        }
        writeMappingsArtifact(repo, version)
        writeFakeGameLibrary(repo, "xstream-1.4.21_miko", version)

        // 模组源码：引用 named 游戏类
        val modSrc = projectDir.resolve("src/main/java/com/example/Mod.java")
        modSrc.parentFile.mkdirs()
        modSrc.writeText(
            "package com.example;\n" +
                "public class Mod { public static final Class<?> GAME = com.fs.starfarer.NamedClass.class; }"
        )
        // 第三方库：应被 shade 进 obf 产物
        compileAndJar(
            "org.example.lib.LibClass",
            "package org.example.lib; public class LibClass {}",
            projectDir.resolve("libs/lib.jar"),
        )

        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "testmod"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.nanoforged.sectordevgradle.mod") }

            version = "1.0"

            dependencies { implementation(files("libs/lib.jar")) }

            starsector {
                modId.set("testmod")
                gameVersion.set("0.98a-RC8")
                artifactMode.set(io.github.nanoforged.sdg.ArtifactMode.OBF)
                sourceRepo.set(layout.projectDirectory.dir("repo"))
            }
            """.trimIndent()
        )

        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("modProduction", "--console=plain")
            .build()
            .output

        assertTrue(output.contains("obf 产物校验通过"), "verifyObfJar 未执行或未通过：\n$output")

        val obfJar = projectDir.resolve("build/mod_production/jars/testmod-1.0.jar")
        assertTrue(obfJar.isFile, "obf 主产物未进入产物布局：\n$output")
        ZipFile(obfJar).use { jar ->
            val modEntry = jar.getEntry("com/example/Mod.class")
            assertTrue(modEntry != null, "模组类缺失")
            val modBytes = jar.getInputStream(modEntry).readBytes()
            assertTrue(modBytes.containsSubarray("aaa/bbb/ObfClass".toByteArray()), "named→obf 重映射未生效")
            assertFalse(modBytes.containsSubarray("com/fs/starfarer/NamedClass".toByteArray()), "残留 named 符号")
            assertTrue(jar.getEntry("org/example/lib/LibClass.class") != null, "第三方库未 shade 进产物")
        }
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            if (this[i] != needle[0]) continue
            for (j in 1 until needle.size) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
