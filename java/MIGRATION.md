# Minecraft Java 迁移记录

## 目标与约束

- 工作位置：Evolve 当前仓库；目标 mod 仓库仅用于核实环境，当前不做 mod 注册或整合。
- 目标：Minecraft 1.21.1 / NeoForge / Java 21。
- 确定路线：原生 Java 核心、独立 GUI 接口、独立存档接口。
- 最终入口是 Minecraft GUI。服务端拥有游戏状态并执行规则，客户端只显示数据、发送意图。
- 每个部分单独提交，提交前完成对应检查。
- 原版完整游戏尚未完成 Java 重写；必须逐系统记录已迁移、有限支持和未迁移范围。

## 接口边界

`GameEngine` 是宿主接口，`GameCommand` 表达客户端意图，`GameSnapshot` 是深度不可变的 GUI 数据。`GameSave` 是与 NBT、JSON、文件和网络都无关的版本化存档模型。`SaveCodec<T>` 负责格式转换，`SaveStore` 负责持久化。

资源名、按钮标题和提示以 `TextRef` 返回，包含翻译 key 和参数；core 不返回 HTML、控件或绘制指令。`assets/evolve/lang/en_us.json` 与 `zh_cn.json` 可供后续 Minecraft 客户端使用，仍使用独立的 `evolve` 命名空间。

一个游戏实例由创建它的线程拥有。后续应在服务器线程创建、加载、推进和执行实例；快照及导出的存档可以安全地交给其他线程处理。

## 迁移范围

| 部分 | 状态 | 边界 |
| --- | --- | --- |
| Java 工程、快照、指令、存档契约 | 已建立 | 无 MC 或脚本运行时依赖 |
| 演化规则 | 开发中 | 从 RNA/DNA 到物种与文明交接点 |
| JSON 与文件适配器 | 待验证 | 不复用浏览器 localStorage |
| GUI 预览 | 待验证 | 验证接口；MC Screen 后续接入 |
| 文明、人口、工作、科技、工业、战斗 | 未迁移 | 保留原版源码供对照 |
| 太空、星际、星系、地狱、伊甸、Truepath | 未迁移 | 保留原版源码供对照 |
| ARPA、CRISPR、声望、成就、重置、总督、随机事件 | 未迁移 | 不能把数据提取等同于玩法移植 |

`UPSTREAM_INVENTORY.json` 记录原始模块、源码行数、浏览器引用和静态 action ID。它是清单，不是玩法覆盖率报告。

## 开发记录

### 2026-10-28：构建与基础契约

建立 Java 21 独立工程，划分 core、storage-json、desktop；定义 GUI 与存档边界。静态导出原版 57 个物种、21 个种属和中英文文本；物种与种属目录不代表所有种族特殊能力已实现。
