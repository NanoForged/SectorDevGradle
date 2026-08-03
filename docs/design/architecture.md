# SectorDevGradle（SDG）设计分析

> 版本：v1 · 2026-08-02
> 依据：NanoForge（feat/coremod-skeleton，R1–R4 已落地）、SourceSector（mapping 工作流）、
> Asteria_Directorate / SSOptimizer 两个直接消费者的构建现状调查。
> 命名（B1 改名，2026-08-03）：**SDG = SectorDevGradle 内部缩写**（模块名、包名与类名沿用）；
> 对外 DSL 名为 `starsector`（`starsector { ... }`），插件 id 为
> `io.github.nanoforged.sectordevgradle.mod` / `io.github.nanoforged.sectordevgradle.nanoforge`，
> Gradle 属性统一 `starsector.*` 前缀。

## 1. 生态位与现状事实

### 1.1 三项目分工（沿用 NanoForge architecture.md §1）

```
SourceSector   ── mapping 工作流：obf → (intermediary) → named，产出 named jar + sources jar + 全量 tiny 表
NanoForge      ── 运行时：CoreMod 加载器（RFB + Mixin + EventBus + coremod.toml + NFBP patch + 运行时 remap）
SectorDevGradle── 构建侧：mod 作者 Gradle 工具链（本项目）
```

### 1.2 SourceSector 已铺好的路（SDG 直接消费）

- 本地 Maven 仓：`SourceSector/build/named-game-repo/windows/`，坐标
  `starsector.named:{starfarer_obf, starfarer.api, fs.common_obf, fs.sound_obf}:0.98a-RC8-SNAPSHOT`，
  均带 `-sources` 分类器（Vineflower 反编译产物），IDE 同步自动附加源码。
- 全量映射表：Tiny v2 三命名空间 `obf / intermediary / named`，目标名规则 `named ?: intermediary`；
  `MappingDirection` 双向支持（含 `named→obf`，即 reobf），工具入口 `JarRemapCli`（batch/single）。
- 平台模型：**windows 汉化版为唯一基准**，linux/macos obf jar 不进任何处理流程；跨平台是运行时
  环境层（natives/启动脚本/OS 属性），与字节码正交。
- 发布门禁：named jar 链接校验断裂 0、sanitize 审计结论为**无需 sanitize 层**（非法标识符仅
  `EngineSlot` 5 个字段且零引用，游戏 jar 内反射调用点为 0）。

### 1.3 NanoForge 现状（SDG 的复用资产与缺口）

可复用资产：

- `launch-spec` 子模块：独立发布的纯 Java 库 `io.github.nanoforged:launch-spec:0.1.0-SNAPSHOT`，
  提供 `GameInstallProbe` / `ClasspathAssembler` / `JvmArgsTemplate` / `LaunchPrecheck`，
  `PrecheckReport` 可直接作为启动输入（classpath + jvmArgs + ready 门）。
- Patch 开发侧工具：`PatchGenCli` / `PatchGenerator`（NFBP 类级 bin patch，badiff 实现，生成即回验）。
  **已决定迁移至 SourceSector**（见 3.1），迁移后 NanoForge 仅保留运行时 `PatcherManager`。
- 运行时 remap：`NanoRemapTransformer` 已承担 obf 编译的 mod 字节码 → named 运行时的重映射，
  **SDG 的 deobf 工作区无需再做运行时 remap**。
- coremod.toml 装配体系：元数据解析、Kahn 拓扑排序、统一 transformer 管线均已落地。

**缺口（SDG 需求中指向但 NanoForge 尚不存在的部分）：**

1. **`nanoforge.mod.toml` 不存在**。NanoForge 当前唯一元数据是 coremod jar 内的 `coremod.toml`；
   普通模组加载在设计文档中被**明确排除**（"游戏原生 mod 加载器已覆盖"）。
   需求中"nanoforge.mod.toml 作为权威元数据、向下兼容 mod_info.json"意味着 NanoForge 的管辖面
   要从 coremod 扩展到普通 mod 的**元数据层**（版本/依赖校验、依赖库解析），这是需要与
   NanoForge 路线图对齐的新增项，SDG 先把元数据格式定义为跨项目契约。
2. **依赖库元数据解析不存在**。coremod 的 `depends` 只有排序语义；没有任何"第三方库统一解析"
   机制。deobf 模组的"不 shadow、声明式依赖库"需要 NanoForge 侧新增运行时解析器 + SDG 侧生成
   元数据，两侧协同设计。
3. **reobf 产物链无现成实现**。SourceSector 的 `MappingDirection` 与 `JarRemapCli` 支持
   named→obf，但没有面向"模组 jar"的封装；NanoForge 侧 `NAMED_TO_OBFUSCATED` 仅是预留枚举。
   SDG 需自行实现 `reobfJar` 任务。

### 1.4 两个消费者的现状（SDG 要收敛的手工经验）

| 维度 | Asteria_Directorate（obf 代表） | SSOptimizer（deobf 代表） |
|---|---|---|
| 构建体系 | buildSrc 自研插件 `starsector-mod-plugin` | 纯 Gradle 多模块，无插件 |
| 游戏依赖 | 动态扫描 `gameDir` + `starfarer-core` + `mods/*/mod_info.json.jars`（compileOnly） | SourceSector 本地 maven 仓 `starsector.named:*` + sources |
| 元数据 | `mod_info.json` 构建期生成（gradle.properties 驱动） | 手写 `mod_info.json` + `coremod.toml`（版本号构建期注入） |
| 第三方库 | 不打包，运行时靠游戏目录 mods | 手工 shade（已踩坑：须排除 `module-info.class`、`META-INF/versions/**`，否则 RFB 误判命名模块） |
| 部署 | `deployMod` → `mods/ASTD/` | `installDevMod` → `mods/` + `mods/coremods/` 双落位 + 维护 `enabled_mods.json` |
| 启动 | `launchGame` JavaExec（含 JVM 探测与不兼容参数过滤） | 无内置启动，手动跑游戏目录 `launch_nanoforge_ss.sh` |
| DataGen 等价物 | 构建期生成贴图 PNG + classgraph 扫描生成 CSV（`:ss-csv`） | 无 |

结论：两个项目已经各自"手搓"了一遍 SDG 的一半，能力互补、形态互补，是 SDG 前两个验证用户。

## 2. SDG 功能设计

### 2.1 双形态产物模型（核心）

**开发态统一 named**。所有模组（包括 obf 形态）都在 deobf 工作区中针对 named 游戏 jar 编译，
源码阅读、调试、IDE 导航体验一致；obf 只是**发布期**的字节码目标。

- **deobf 产物（默认）**：named 字节码 jar，运行于 NanoForge 环境。第三方库**不 shadow**，
  生成依赖库元数据（见 2.4），由 NanoForge 运行时统一解析。
- **obf 产物**：`reobfJar` 任务用全量 tiny 表做 named→obf 重映射（复用 SourceSector 表，
  `named ?: intermediary` 目标规则反向即 `obf`），第三方库 **shadow** 进 jar
  （排除 `module-info.class`、`META-INF/versions/**`——SSOptimizer 已验证的 RFB 坑）。
  适用于非 NanoForge 的原版游戏 `mods/` 目录。
- 两种产物都必须携带 `mod_info.json`（原版加载器的唯一入口，Asteria 的构建期生成模式是现成参考）；
  NanoForge 环境下 `nanoforge.mod.toml` 为权威，`mod_info.json` 仅作向下兼容兜底。

### 2.2 工作区与依赖解析

SDG 提供两种游戏依赖来源，按配置择优：

1. **SourceSector maven 仓**（首选，SSOptimizer 模式）：`starsector.named:*` 坐标 +
   `cacheChangingModulesFor(0)`，sources 自动附加。SDG 负责把仓路径解析为 convention
   （`-Psourcesector.namedRepo=` 可覆盖）。
2. **本机游戏目录扫描**（Asteria 模式）：`starsector.gameDir` 驱动的 `gameDir/*.jar` +
   `starsector-core/*.jar` compileOnly，外加 `mods/*/mod_info.json` 的 `jars` 字段扫描
   （模组间 compileOnly 依赖，自动跳过自身）。此模式下无 named jar，仅供 obf 兼容场景与
   第三方 mod 依赖解析，不是 deobf 工作区的主路径。

模组间依赖声明统一走 Gradle 依赖机制，SDG 仅提供从 `mod_info.json`/`nanoforge.mod.toml`
推导 compileOnly 的桥。

### 2.3 构建 / 部署 / 运行 / 调试

- **发布产物**：`assembleRelease` 产出标准 mod 目录布局（`build/mod_production/`，Asteria 模式）
  + `zip` 发布包；coremod 形态追加 `mods/coremods/` 落位（SSOptimizer 模式）。
- **deploy**：`deployMod` 覆盖式同步到 `gameDir/mods/<deployDirName>/`，可选维护
  `enabled_mods.json`；deployDirName 与 mod id 解耦（Asteria 的大小写冲突经验）。
- **runGame**：依赖 launch-spec，`LaunchPrecheck.check(gameRoot, jvmOptions)` → `ready()` 门 →
  `classpath()` + `jvmArgs()` 组装 JavaExec。launch-spec **刻意不覆盖**的部分由 SDG 补全：
  RFB 主类与 `--tweakClass`、工作目录（游戏根）、`-Djava.library.path` 的 OS 分支、
  `mesa_glthread=false` 环境变量。JVM 参数基线注意按 OS/JDK 覆写（launch-spec 基线面向
  linux + Java 25，含 `--enable-preview`、`-Dcom.fs.starfarer.settings.linux=true`）。
  Asteria 的 JVM 探测与参数过滤逻辑（JBR/zulu/系统 JDK、按 JDK 版本剔除不兼容参数）直接迁移。
- **IDEA Debug Attach**：`runGame` 支持 `-Pstarsector.debug=true` 注入 JDWP `agentlib:jdwp`（suspend 可配），
  并生成 `.run/*.run.xml`（genIntellijRuns 等价物），一键 remote attach。
- **IDEA Sources**：named jar 的 `-sources` 分类器经 Gradle 依赖自动附加（零成本）；
  另提供 `decompileDependencies`（Vineflower，SHA-256 增量，Asteria 的 `DecompileSourcesTask` 迁移）
  覆盖第三方 obf mod 依赖的源码阅读。

### 2.4 动态依赖声明（deobf 形态专属）

- SDG 提供 DSL 声明运行时第三方库（maven 坐标），构建期解析坐标 + 校验和，生成
  **依赖库元数据**（建议嵌入 `nanoforge.mod.toml` 的 `[libraries]` 段，与元数据同一份文件），
  打进 mod jar。
- NanoForge 运行时按元数据统一下载/校验/注入 classpath（**NanoForge 侧新增解析器，协同开发项**）。
- obf 形态忽略该元数据，走 shadow（见 2.1）。
- 与游戏自带库的冲突判定复用 NanoForge `deployToGame` 的文件名白名单思路，元数据中标记
  `provided-by: game` 的库不进运行时解析。

### 2.5 元数据生成

- `nanoforge.mod.toml`（新格式，SDG 与 NanoForge 的跨项目契约）：**初版完整覆盖 `mod_info.json`
  的全部字段**（id/name/version/gameVersion/description/author/modPlugin/jars/depends 等，
  toml 化表达），并在此基线上新增 `[libraries]` 依赖库元数据（2.4）；**后续版本发展为
  模组元数据超集**，渐进吸纳 coremod 能力声明（asm transformers / mixin configs / patch
  entries 的引用）等扩展段。
- `mod_info.json`：由同一 DSL 派生生成（向下兼容 + obf 产物必需）。初版两者字段一一对应，
  保证同源一致；超集化后 mod_info.json 仅生成其子集字段。
- coremod 形态继续生成/校验 `coremod.toml`（NanoForge 装配的权威输入，`[patch] entries` 指向
  jar 内 `.binpatch`）。
- 全部构建期生成（Asteria `generateModInfoJson` 模式），DSL 为唯一事实源。

## 3. 决策点结论

### 3.1 SDG 的服务对象：不只面向 Mod/CoreMod 开发者

**结论：SDG 以"游戏工作区管理"为通用层，CoreMod/Mod 开发者是主要用户，NanoForge 自身是
可组合能力的内部用户（dogfooding），但不作为第一阶段目标。**

理由：

- NanoForge 的 `build.gradle` 里已经有大量 SDG 职责内的手工任务：`deployToGame`、
  `packFullMapping`、`extractGameNatives`、`unsealLwjgl`、`.run/` 运行配置。
  这些与 SDG 的"deobf 工作区 + 运行游戏 + 部署"能力同构——NanoForge 本身就是
  一个"针对 named 游戏 jar 开发、需要部署进游戏目录运行"的项目。
- **Patch 工作流归属 SourceSector**。`PatchGenCli` 是开发侧构建工具（输入 named jar + patched
  class 目录，输出 `.binpatch`），其全部输入（named jar、mapping 表、字节码工具链 ASM）都在
  SourceSector 的版图内；且 SourceSector 本就是 remap/链接校验等字节码工具链的所在地，
  patch 生成与 `JarRemapCli` 是同一性质的工具。NanoForge 运行时侧只保留 `PatcherManager`。
  SDG 的 patch 插件消费 SourceSector 发布的 patch tool 构件，封装 patch 编译、生成、回验、
  写入 `coremod.toml [patch] entries` 全链路；做游戏类 patch 的 coremod 都是用户。
- 依赖方向必须保持单向，避免循环：NanoForge / SourceSector 源码发布 **tool 构件**
  （`launch-spec` 已是；patch 生成逻辑迁移至 SourceSector 后同样以独立 tool 坐标发布）→
  SDG 插件依赖 tool 构件 → NanoForge 构建可选应用 SDG。第一阶段 SDG 以 Asteria/SSOptimizer
  为验证用户；NanoForge 保持自举构建，SDG 稳定后再评估迁移（迁移收益：删除手工任务，
  统一 run/部署体验）。

### 3.2 DataGen：提供机制，不提供生成器集合

**结论：需要"类 DataGen 的挂载机制"，不需要 MC DataGen 那样的开箱生成器库。优先级 P2。**

理由：

- StarSector 的内容数据是 CSV/json 配置/贴图/脚本注册，与 MC 的模型/配方/战利品表完全不同，
  没有可预置的通用生成器集合。
- 但**机制需求是真实的**：Asteria 已经自建了两个 DataGen 等价物
  （`generateRingTextureToContents` 构建期生成贴图；`:ss-csv` classgraph 扫描 Kotlin object
  生成 CSV）。这证明"代码 → 构建期 → 数据文件 → 并入 mod 产物"的链路是内容向模组的刚需。
- SDG 的第一阶段只需提供：注册式数据源（`starsector.dataGen` DSL 挂 JavaExec/Worker 任务）+
  输出目录自动并入 `processResources` / mod 产物布局 + 增量缓存约定。
  生成器本身永远由模组自定义。

## 4. 模块划分草案

SDG 为单仓库 Gradle 插件工程，按能力分层（插件 id 暂定）：

```
sdg (plugin: io.github.nanoforged.sectordevgradle.mod)
├─ 工作区：游戏依赖解析（SourceSector 仓 / gameDir 扫描）、第三方 mod 依赖桥
├─ 产物：jar / reobfJar（named→obf）/ mod 目录布局 / zip / mod_info.json 生成
├─ 部署与运行：deployMod / runGame（launch-spec）/ debug 注入 / IDEA run 配置生成
└─ 源码体验：sources 附加 / decompileDependencies（Vineflower）

sdg-nanoforge (plugin: io.github.nanoforged.sectordevgradle.nanoforge)
├─ nanoforge.mod.toml 生成（含 [libraries] 依赖库元数据）
├─ coremod.toml 生成与校验、coremods 落位
└─ patch 工作流：binpatch 生成（依赖 SourceSector patch tool 构件）、[patch] entries 装配
```

依赖方向（单向）：

```
NanoForge   ──发布──> launch-spec（纯 Java 构件）
SourceSector──发布──> named-game-repo + 全量 tiny 表 + patch-tool 构件
SDG         ──消费──> 上述构件与产物
Asteria（sectordevgradle.mod, obf 产物）/ SSOptimizer（sectordevgradle.mod + sectordevgradle.nanoforge）──应用──> SDG
```

## 5. 待与 NanoForge / SourceSector 对齐的协同项

1. **`nanoforge.mod.toml` 格式定义**（SDG 起草契约 → NanoForge 实现读取与运行时依赖库解析）；
   初版字段集 = mod_info.json 全字段的 toml 化 + `[libraries]`，超集化路线另行立项。
2. **patch 生成逻辑迁移至 SourceSector**（`PatchGenerator` / `PatchGenCli` / NFBP 格式定义），
   以独立 tool 坐标发布供 SDG 与 NanoForge 自身构建共用；NanoForge 保留 `PatcherManager` 与
   `PatchFormat` 读侧（或读侧也随格式定义收敛到 SourceSector 发布的构件，运行时以依赖引入）。
3. **deobf 产物分发：初版仅 local maven**（`named-game-repo` 随 SourceSector 本地构建产出，
   跨机器先跑 SourceSector 构建或拷贝仓库目录）；reobfJar 所需的全量 tiny 表应一并纳入
   local maven 发布（当前仅 named jar 入仓，表只在 `mapping/versions/` 有入库归档副本）；
   第三方 CDN 分发后续再评估。
4. **测试能力边界：初版仅 deobf 产物**。runGame/冒烟链路只保证 deobf（named）产物在
   NanoForge 环境的可运行性；reobf 后的 obf 产物不提供任何测试/冒烟能力，后续按需立项。
