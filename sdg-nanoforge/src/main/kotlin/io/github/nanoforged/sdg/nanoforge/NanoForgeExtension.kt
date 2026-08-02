package io.github.nanoforged.sdg.nanoforge

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * SDG NanoForge DSL（`nanoforge { ... }`）。
 *
 * coremod 段字段与 NanoForge `CoreModMetaParser` 的读取键一一对应
 * （必填 id/name/version/pluginClass 中前三个继承 sdg 核心 DSL，不在此重复）；
 * `[libraries]` 依赖库声明见 docs/design/nanoforge-mod-toml-v1.md §3。
 */
abstract class NanoForgeExtension @Inject constructor(objects: ObjectFactory) {

    /** 是否 coremod 形态：启用 coremod.toml 生成与 `mods/coremods/` 落位。默认 false。 */
    val coremod: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** coremod.toml `pluginClass`（`INanoCorePlugin` 实现类全限定名）。coremod 形态必填。 */
    val pluginClass: Property<String> = objects.property(String::class.java)

    /** coremod.toml `authors`；未设置且 sdg.author 已配置时回落为单元素列表。 */
    val authors: ListProperty<String> = objects.listProperty(String::class.java)

    /** coremod.toml `priority`（升序先加载）；未设置则不输出该键（运行时默认 0）。 */
    val priority: Property<Int> = objects.property(Int::class.java)

    /** coremod.toml `depends`（coremod 硬依赖，缺失即启动失败）。 */
    val depends: ListProperty<String> = objects.listProperty(String::class.java)

    /** coremod.toml `[asm] transformers`。 */
    val asmTransformers: ListProperty<String> = objects.listProperty(String::class.java)

    /** coremod.toml `[asm] transformerExclusions`。 */
    val asmTransformerExclusions: ListProperty<String> = objects.listProperty(String::class.java)

    /** coremod.toml `[mixin] configs`。 */
    val mixinConfigs: ListProperty<String> = objects.listProperty(String::class.java)

    /** coremod.toml `[patch] entries`（jar 内 `.binpatch` 路径列表；R6 patch 工作流自动装配，也可手动声明）。 */
    val patchEntries: ListProperty<String> = objects.listProperty(String::class.java)

    /** nanoforge.mod.toml `[libraries]` 依赖库声明。 */
    val libraries: ListProperty<NanoForgeLibrary> = objects.listProperty(NanoForgeLibrary::class.java)

    /**
     * 声明一条依赖库（坐标 `group:artifact:version`）。
     *
     * 构建期解析坐标写入 jar 的 sha256；[providedBy] 目前仅支持 `"game"`（游戏自带库，运行时跳过解析）。
     */
    fun library(notation: String, providedBy: String? = null) {
        libraries.add(NanoForgeLibrary.parse(notation, providedBy))
    }
}
