package io.github.nanoforged.sdg

import java.io.File

/**
 * 第三方 mod 依赖索引：扫描游戏 mods 目录下各模组的 `mod_info.json`，
 * 汇总各模组声明的 jar 文件，供 compileOnly 依赖桥使用。
 */
interface ModJarIndex {

    /**
     * 扫描 [modsDir] 下所有模组目录。
     *
     * @param excludeModId 需要跳过的模组 id（通常是本模组，避免把已部署的自身 jar 扫入）
     * @param onWarn 非致命问题（解析失败、缺 id）的告警出口
     * @return modId → 磁盘上存在的 jar 文件列表
     */
    fun scan(modsDir: File, excludeModId: String?, onWarn: (String) -> Unit): Map<String, List<File>>
}
