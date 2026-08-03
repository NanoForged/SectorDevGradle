package io.github.nanoforged.sdg

import java.io.File

/**
 * 产物 jars 目录在元数据（mod_info.json / nanoforge.mod.toml `jars` 字段）中的排序规则：
 * 无 classifier 的主 jar 文件名最短，排首位保证游戏加载顺序主 jar 优先；
 * 附加 classifier jar 按字典序稳定排列。
 *
 * 两个插件（sectordevgradle.mod / sectordevgradle.nanoforge）生成元数据时必须走同一规则，保证字段一致。
 */
object ModJarOrdering {

    /** [files] 为产物 jars 目录内容，返回 `jars/<name>` 形式的排序清单。 */
    fun mainFirst(files: Collection<File>): List<String> =
        mainFirstNames(files.filter { it.extension == "jar" }.map { it.name })

    /** [names] 为 jar 文件名清单（任务图推导路径），返回 `jars/<name>` 形式的排序清单。 */
    fun mainFirstNames(names: Collection<String>): List<String> =
        names
            .sortedWith(compareBy({ it.length }, { it }))
            .map { "jars/$it" }
}
