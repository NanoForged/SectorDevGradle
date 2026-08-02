package io.github.nanoforged.sdg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModJarIndexImplTest {

    @TempDir
    lateinit var modsDir: File

    private val index = ModJarIndexImpl()

    private fun writeMod(dirName: String, modInfo: String, jars: List<String> = emptyList()) {
        val modDir = modsDir.resolve(dirName)
        modDir.mkdirs()
        modDir.resolve("mod_info.json").writeText(modInfo)
        jars.forEach { jar ->
            modDir.resolve(jar).apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf())
            }
        }
    }

    @Test
    fun `扫描 jars 字段，仅收录磁盘存在的 jar`() {
        writeMod(
            "LazyLib",
            """{"id": "lw_lazylib", "name": "LazyLib", "jars": ["jars/LazyLib.jar", "jars/Missing.jar"]}""",
            jars = listOf("jars/LazyLib.jar"),
        )

        val result = index.scan(modsDir, excludeModId = null) {}

        assertEquals(1, result.size)
        assertEquals(
            listOf(File(modsDir, "LazyLib/jars/LazyLib.jar")),
            result["lw_lazylib"],
        )
    }

    @Test
    fun `宽松解析：井号注释与尾逗号`() {
        writeMod(
            "MagicLib",
            """
            {
                # 行内注释
                "id": "MagicLib",
                "jars": [
                    "jars/MagicLib.jar",
                ],
            }
            """.trimIndent(),
            jars = listOf("jars/MagicLib.jar"),
        )

        val result = index.scan(modsDir, excludeModId = null) {}

        assertEquals(listOf(File(modsDir, "MagicLib/jars/MagicLib.jar")), result["MagicLib"])
    }

    @Test
    fun `excludeModId 跳过自身`() {
        writeMod("Self", """{"id": "my_mod", "jars": ["jars/Self.jar"]}""", jars = listOf("jars/Self.jar"))
        writeMod("Other", """{"id": "other", "jars": ["jars/Other.jar"]}""", jars = listOf("jars/Other.jar"))

        val result = index.scan(modsDir, excludeModId = "my_mod") {}

        assertEquals(setOf("other"), result.keys)
    }

    @Test
    fun `解析失败与缺 id 走告警且跳过`() {
        writeMod("Broken", """{"id": "broken", "jars": [}""")
        writeMod("NoId", """{"name": "NoId"}""")
        writeMod("Good", """{"id": "good", "jars": ["g.jar"]}""", jars = listOf("g.jar"))

        val warnings = mutableListOf<String>()
        val result = index.scan(modsDir, excludeModId = null) { warnings += it }

        assertEquals(setOf("good"), result.keys)
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.contains("解析") })
        assertTrue(warnings.any { it.contains("缺少 id") })
    }

    @Test
    fun `无 mod_info 的目录直接跳过`() {
        modsDir.resolve("NoInfo").mkdirs()
        val result = index.scan(modsDir, excludeModId = null) {}
        assertTrue(result.isEmpty())
    }
}
