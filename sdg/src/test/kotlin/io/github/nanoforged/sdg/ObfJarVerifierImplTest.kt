package io.github.nanoforged.sdg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ObfJarVerifierImplTest {

    @TempDir
    lateinit var dir: File

    private val verifier = ObfJarVerifierImpl()

    private fun writeMapping(content: String): File =
        dir.resolve("full.tiny").apply { writeText(content) }

    private fun writeJar(name: String, entries: Map<String, ByteArray>): File {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `class 条目残留 named 符号时报出违规`() {
        val mapping = writeMapping(
            "tiny\t2\t0\tobf\tintermediary\tnamed\n" +
                "c\taaa/Obf\taaa/Obf\tcom/fs/starfarer/NamedClass\n"
        )
        val jar = writeJar(
            "mod.jar",
            mapOf("com/example/Mod.class" to "PREFIX\u0001Lcom/fs/starfarer/NamedClass;SUFFIX".toByteArray()),
        )

        val violations = verifier.verify(jar, mapping, 500)

        assertEquals(1, violations.size)
        assertTrue(violations[0].contains("com/example/Mod.class"))
        assertTrue(violations[0].contains("com/fs/starfarer/NamedClass"))
    }

    @Test
    fun `无残留时通过；无 named 列的条目不参与校验`() {
        val mapping = writeMapping(
            "tiny\t2\t0\tobf\tintermediary\tnamed\n" +
                "c\taaa/Obf\taaa/Obf\tcom/fs/starfarer/NamedClass\n" +
                "c\tbbb/Obf2\tbbb/Obf2\n"
        )
        val jar = writeJar(
            "mod.jar",
            mapOf(
                "com/example/Mod.class" to "aaa/Obf 与 bbb/Obf2 均为 obf 名".toByteArray(),
                "data.txt" to "com/fs/starfarer/NamedClass 出现在非 class 条目不影响".toByteArray(),
            ),
        )

        assertTrue(verifier.verify(jar, mapping, 500).isEmpty())
    }

    @Test
    fun `映射表无 named 类条目时显式失败`() {
        val mapping = writeMapping("tiny\t2\t0\tobf\tintermediary\tnamed\nc\taaa/Obf\taaa/Obf\n")
        val jar = writeJar("mod.jar", mapOf("A.class" to byteArrayOf(1)))

        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(jar, mapping, 500)
        }
    }
}
