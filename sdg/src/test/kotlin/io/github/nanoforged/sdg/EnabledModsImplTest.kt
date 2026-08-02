package io.github.nanoforged.sdg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnabledModsImplTest {

    @TempDir
    lateinit var gameDir: File

    private val enabledMods = EnabledModsImpl()

    private fun writeEnabledModsFile(content: String): File {
        val file = gameDir.resolve("mods/enabled_mods.json")
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `追加新 id 并保留其他键`() {
        val file = writeEnabledModsFile("""{"enabledMods": ["other_mod"], "custom": 1}""")

        val changed = enabledMods.enable(gameDir, "my_mod")

        assertTrue(changed)
        val text = file.readText()
        assertTrue(text.contains("\"my_mod\""))
        assertTrue(text.contains("\"other_mod\""))
        assertTrue(text.contains("\"custom\""))
    }

    @Test
    fun `已存在 id 幂等不修改`() {
        writeEnabledModsFile("""{"enabledMods": ["my_mod"]}""")

        assertFalse(enabledMods.enable(gameDir, "my_mod"))
    }

    @Test
    fun `文件不存在返回 false 且不创建`() {
        assertFalse(enabledMods.enable(gameDir, "my_mod"))
        assertFalse(gameDir.resolve("mods/enabled_mods.json").exists())
    }

    @Test
    fun `格式非法显式报错`() {
        writeEnabledModsFile("""not json""")

        assertThrows(EnabledModsException::class.java) {
            enabledMods.enable(gameDir, "my_mod")
        }
    }
}
