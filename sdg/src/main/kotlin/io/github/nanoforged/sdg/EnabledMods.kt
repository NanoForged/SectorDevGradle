package io.github.nanoforged.sdg

import java.io.File

/**
 * `mods/enabled_mods.json` 维护：deploy 时将本模组标记为启用。
 */
interface EnabledMods {

    /**
     * 将 [modId] 加入 `<gameDir>/mods/enabled_mods.json` 的 `enabledMods` 列表。
     *
     * @return true = 文件被修改；false = 文件不存在（不创建，游戏首次启动会自建）或已包含该 id
     * @throws EnabledModsException 文件存在但格式非法
     */
    fun enable(gameDir: File, modId: String): Boolean
}

/** enabled_mods.json 格式非法。 */
class EnabledModsException(message: String) : RuntimeException(message)
