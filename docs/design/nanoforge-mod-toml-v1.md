# nanoforge.mod.toml v1 契约

> 状态：草案 v1 · 2026-08-02
> 定位：NanoForge 环境下模组的**权威元数据**；本文件是 SDG（SectorDevGradle 内部缩写，对外 DSL
> 名为 `starsector`、插件 id 为 `io.github.nanoforged.sectordevgradle.*`）生成端与 NanoForge（读取端，
> 待 N1 实现）的跨项目契约。初版完整覆盖 `mod_info.json` 全部字段，并新增 `[libraries]`
> 依赖库元数据；后续版本发展为模组元数据超集（见 architecture.md 2.5）。

## 1. 文件位置与优先级

- 位于 mod jar 根目录 `nanoforge.mod.toml`。
- NanoForge 环境：以本文件为权威，**不读** `mod_info.json`。
- 非 NanoForge（原版）环境：游戏原生加载器读 `mod_info.json`（由同一 DSL 派生生成，随 jar
  或 mod 目录一并分发）。

## 2. 与 mod_info.json 的字段映射

| mod_info.json | nanoforge.mod.toml | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | `id` | string | 是 | 模组唯一 id，原样映射 |
| `name` | `name` | string | 是 | 展示名 |
| `version` | `version` | string | 是 | 模组版本 |
| `author` | `author` | string | 否 | 作者 |
| `description` | `description` | string | 否 | 描述 |
| `gameVersion` | `gameVersion` | string | 否 | 目标游戏版本（如 `0.98a-RC8`） |
| `modPlugin` | `modPlugin` | string | 否 | `BaseModPlugin` 入口类全限定名 |
| `jars` | `jars` | array&lt;string&gt; | 否 | mod 目录内 jar 相对路径，构建期按产物枚举 |
| `dependencies[]` | `[[dependencies]]` | array&lt;table&gt; | 否 | 见 2.1 |

### 2.1 [[dependencies]]

| mod_info.json | toml | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | `id` | string | 是 | 依赖模组 id |
| `name` | `name` | string | 否 | 展示名 |
| `version` | `version` | string | 否 | 版本约束（原版格式照收；超集化时可引入区间语法） |

## 3. 新增：[libraries] 依赖库元数据

deobf 形态模组的第三方库**不 shadow**，由本段声明、NanoForge 运行时统一解析
（解析器为 NanoForge 侧 N1 项，本契约只定义数据面）。

```toml
[[libraries]]
group = "it.unimi.dsi"
artifact = "fastutil"
version = "8.5.15"
sha256 = "<构建期解析的 jar 摘要>"
# providedBy = "game"   # 可选：标记由游戏自带 classpath 提供，运行时跳过解析
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group` / `artifact` / `version` | string | 是 | maven 坐标 |
| `sha256` | string | 是 | jar 摘要，构建期由 SDG 解析写入，运行时校验用 |
| `providedBy` | string | 否 | 目前仅定义值 `"game"`：游戏自带库，不参与解析 |

规则：
- obf 形态忽略本段（第三方库 shadow 进 jar）。
- 与游戏自带库冲突的判定在 SDG 构建期完成（`providedBy = "game"` 由 DSL 显式标记，
  参考 NanoForge `deployToGame` 的文件名白名单思路），不写进 jar 也不进运行时解析。

## 4. 通用规则

- 未知顶层键：**WARN 不拒绝**（与 NanoForge `CoreModMetaParser` 的向前兼容约定一致），
  为超集化预留空间。
- 缺必填键 / 类型错误：读取端必须显式报错，不得静默兜底。
- 生成端唯一事实源是 SDG 的 Gradle DSL；`nanoforge.mod.toml` 与 `mod_info.json`
  均为构建期派生产物，**不手写**。

## 5. 完整示例

```toml
id = "ssoptimizer"
name = "SSOptimizer"
version = "0.1.10"
author = "Hikari_Nova"
description = "Starsector rendering & performance optimizer"
gameVersion = "0.98a-RC8"
modPlugin = "github.kasuminova.ssoptimizer.SSOptimizerModPlugin"
jars = ["jars/SSOptimizer.jar"]

[[dependencies]]
id = "MagicLib"
name = "MagicLib"

[[libraries]]
group = "it.unimi.dsi"
artifact = "fastutil"
version = "8.5.15"
sha256 = "…"

[[libraries]]
group = "com.thoughtworks.xstream"
artifact = "xstream"
version = "1.4.21"
sha256 = "…"
providedBy = "game"
```
