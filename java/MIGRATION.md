# Minecraft Java 迁移记录

## 目标与约束

- 工作位置：Evolve 当前仓库；目标 mod 仓库仅用于核实环境，当前不做 mod 注册或整合。
- 目标：Minecraft 1.21.1 / NeoForge / Java 21。
- 确定路线：原生 Java 核心、独立 GUI 接口、独立存档接口。
- 最终入口是 Minecraft GUI。服务端拥有游戏状态并执行规则，客户端只显示数据、发送意图。
- 每个部分单独提交，提交前完成对应检查。
- 原版完整游戏尚未完成 Java 重写；必须逐系统记录已迁移、有限支持和未迁移范围。
- 开发阶段不维护旧版 Java 存档兼容。新增持久化状态时升级 schema/ruleset，明确拒绝旧格式，不提供兼容迁移器。

## 接口边界

`GameEngine` 是宿主接口，`GameCommand` 表达客户端意图，`GameSnapshot` 是深度不可变的 GUI 数据。`GameSave` 是与 NBT、JSON、文件和网络都无关的版本化存档模型。`SaveCodec<T>` 负责格式转换，`SaveStore` 负责持久化。

资源名、按钮标题和提示以 `TextRef` 返回，包含翻译 key 和参数；core 不返回 HTML、控件或绘制指令。`assets/evolve/lang/en_us.json` 与 `zh_cn.json` 可供后续 Minecraft 客户端使用，仍使用独立的 `evolve` 命名空间。

一个游戏实例由创建它的线程拥有。后续应在服务器线程创建、加载、推进和执行实例；快照及导出的存档可以安全地交给其他线程处理。

## 迁移范围

| 部分 | 状态 | 边界 |
| --- | --- | --- |
| Java 工程、快照、指令、存档契约 | 已建立 | 无 MC 或脚本运行时依赖 |
| 演化规则 | 已实现，范围见下 | 从 RNA/DNA 到物种与文明交接点 |
| JSON 与文件适配器 | 已实现并验证 | 不复用浏览器 localStorage |
| GUI 预览 | 已实现并验证 | 验证接口；MC Screen 后续接入 |
| 文明初始化、基础资源、原始科技、住房与人口 | 有限实现 | 仅普通人类、grassland、standard；详见本批边界 |
| 工作分配、农业、货币、工业、政体、战斗 | 未迁移 | 当前只有默认闲置人口；保留原版源码供对照 |
| 太空、星际、星系、地狱、伊甸、Truepath | 未迁移 | 保留原版源码供对照 |
| ARPA、CRISPR、声望、成就、重置、总督、随机事件 | 未迁移 | 不能把数据提取等同于玩法移植 |

`UPSTREAM_INVENTORY.json` 记录原始模块、源码行数、浏览器引用和静态 action ID。它是清单，不是玩法覆盖率报告。

## 后续 Minecraft GUI 接入方式

| 位置 | 负责事项 |
| --- | --- |
| 服务端会话管理 | 决定按玩家、团队或方块保存一局；维护实例生命周期和访问权限 |
| 服务端 tick | 在实例所属线程调用 `advanceTicks(1)`；不能在 GUI 渲染时推进 |
| C2S 操作消息 | 仅传稳定 action ID 与重复次数，服务端重新检查权限、解锁和费用 |
| S2C 数据消息 | 编码 `GameSnapshot`，客户端据此绘制资源、按钮、费用、进度和提示 |
| Minecraft Screen/Menu | 处理滚动、布局、焦点和按钮，使用快照中的 `enabled()` 控制展示；展示状态不能代替服务端检查 |
| SavedData/NBT | 保存 `GameSave`；加载时先验证，再在服务器线程建立实例 |

当前工程提供核心与数据契约，尚未实现这些 MC 注册、数据包、NBT 编码器或 Screen。权限必须由宿主确定，不能用客户端传来的 UUID、会话 ID 或文件路径直接授权。多人 GUI 的命令应路由到服务端拥有的正确实例，不能使用全局静态单例。

最小核心调用示例：

```java
GameEngine game = EvolutionEngine.newGame(2);
game.advanceTicks(1);
CommandResult result = game.execute(new GameCommand.Action("evolution-rna"));
GameSnapshot snapshot = game.snapshot();
GameSave save = game.exportSave();
GameEngine restored = EvolutionEngine.restore(save);
```

`GameCommand.Action` 的次数范围为 1–100。批量操作逐次重新计算费用和条件；`CommandResult.applied()` 表示实际完成次数，`status()` 表示全部完成或停止原因。单次失败不会扣除部分费用。发送资源量、费用或科技等级都不属于此接口。

`TextRef` 在 MC 客户端可映射为 `Component.translatable(text.key(), text.arguments().toArray())`。把 `core/src/main/resources/assets/evolve/lang` 复制到宿主资源包的对应位置；不要假设一个普通依赖 jar 会自动成为 Minecraft 资源包。提取器将原作 `%0` 形式转换为 Minecraft `%1$s`，并转义字面百分号。

磁盘保存可以先在服务器线程导出不可变 `GameSave`，再交给同一会话的串行 IO 队列处理；不要在后台线程调用 `snapshot()` 或 `exportSave()`。JSON 解码可以在 IO 线程进行，`EvolutionEngine.restore()` 则必须在未来推进该实例的线程执行。

`core` jar 可独立使用；NBT 路线不需要 Gson。JSON 路线额外使用 storage-json 和兼容的 Gson。desktop、Node.js 工具和 JUnit 测试不随 mod 打包。

## 当前停止点与下一批顺序

当前已从 `CIVILIZATION_READY` 增加受限的 `PRIMITIVE` 文明阶段。普通人类在 grassland / standard 环境下可执行 `civilization-start`，再采集、研究住房并增长人口；其他物种和环境仍停留在交接点。未修改目标 mod。

下一批优先补全工作分配与生产、农业、货币及其原始科技/建筑依赖，打通第六间住房的货币条件；再迁移士气、日历/天气和其他种族的初始化与特性。随后扩展工业/政体/军事、太空各层级，以及声望与重置。每个系统补原版场景对照并单独提交。按当前开发阶段约定升级存档版本，不实现旧版兼容。

## 当前批次：原始文明

### 核心与存档

新增 `PRIMITIVE` 阶段与 `civilization-start` 操作，继续使用同一个线程拥有的 `GameEngine`、不可变快照和服务端操作校验。初始化前保持交接状态，不推进时间；初始化不能重复执行。`CivilizationState` 保存主种属和闲置人口，`GameSnapshot.jobs()` 提供不可变工作视图。

已移植的玩法：

- 普通人类在 grassland / standard 下的基础文明资源与种族特性。演化交接时先保存原版 `evo_humanoid >= 2` 的主种属判定；初始化时按 `sentience()` 对非主种属 traits 降一级。物种特性保持原始等级。
- 每次手动采集 1 单位木材、食物或石头；资源容量与原作一致，满仓停止，后两种采集分别要求 primitive 1/2。
- 木棒、骨制工具、日晷、住房四项研究，对应 primitive 1/2/3 和 housing 1。研究按原版扣费并解锁资源/建筑，不能重复购买；单次失败不部分扣费。
- 基础住房采用原版 `round(10 * 1.23^count)` 木材费用，每间增加 1 人口容量；第六间开始还需要 `round(20 * 1.17^count)` 货币。目前货币尚未解锁，因此保留费用并禁用第六间住房，最多建成五间。
- fastLoop 每 5 tick 处理食物消耗、饥饿死亡、人口增长和日晷知识产出。所有人口暂为 unemployed，每人消耗 0.5 食物/s。工作人数在单次原子 tick 结束时与人口同步；原版把饥饿造成的闲置人数差异留到下一次 fastLoop 校正，在当前无其他工作时不影响资源计算。

明确未覆盖：`sentience()` 中的季节/活动、挑战、声望奖励、外邦、政体、队列、特殊种族配置，以及日晷开启的完整日历/天气。没有把所有 `sentience()` 的宿主或其他系统副作用宣称为完成。其他种族、非 standard 宇宙和非 grassland 环境不开放文明按钮。

原版人口随机性来自 `Math.random()`；Java 沿用已持久化的确定性 PRNG，复用相同的概率表达式与计算顺序。它不复现浏览器历史随机序列。当前知识产出使用宿主提供的 `EvolutionContext.productionMultiplier`，默认 1；士气、声望等倍率计算仍未迁移，食物消耗不乘生产倍率。

存档升级为 `schemaVersion=2`、`ruleset=evolve-1.4.10/primitive-1`，新增 `civilization`。JSON 和核心按阶段校验资源集合、容量、可见性、科技/住房依赖、人口整数与工作人数、traits 和主种属。按用户明确要求不做 v1 兼容，旧版本、旧 ruleset 和缺失文明字段直接拒绝。读取失败仍保留当前游戏。

原版对照由 `tools/civilization-fixtures.cjs` 在开发时执行原始 `sentience()`、资源默认值、特性等级、所选四项研究/采集/住房操作、费用函数及 food/growth/knowledge 代码块。UI 与未迁移系统使用显式 stub，随机源替换为持久化 PRNG，宿主倍率显式传入；覆盖 2 组初始化、8 组科技/住房场景、8 组生产场景及其多帧结果，不代表完整文明覆盖率。

核心与存档验证：JUnit 516 项通过；涵盖原始 JS 场景对照、新局通过真实演化操作进入人类文明、批量递增费用、失败扣费、第五/第六间住房边界、人口/工作一致、暂停、分批 tick、JSON 读档后的随机序列和损坏存档拒绝。

```powershell
.\gradlew.bat :core:test :storage-json:test verifyUpstreamFixtures verifyCivilizationFixtures
```

核心与存档已提交为 `5a3bd118`（`feat(core): add bounded human primitive civilization rules`）。

### GUI 预览

预览根据快照动态建立资源卡片，人类人口优先显示；资源列表可滚动，解锁新资源或切换存档后重新绑定可见卡片。文明阶段显示聚落操作、人口容量和默认闲置工作，采集、研究及住房按钮仍只发送 `GameCommand.Action`。

补齐中英文基础资源、研究、住房和工作文本；费用使用资源翻译名，零消耗按钮不再统一显示“＋ RNA”。缺少尚未解锁资源时提示先解锁资源，容量不足时提示扩容，避免把第六间住房的货币条件误报成容量不足。

GUI 合约测试覆盖文明按钮、动态资源、读档后的实例绑定、坏档保留和全过程中英文 key 检查。`smokeTest` 同时验证演化界面及从有效人类交接 fixture 开始的聚落流程，点击真实研究按钮、建满五间住房、增长人口、保存/读取后继续操作，并生成：

- `desktop/build/preview.png`：演化阶段。
- `desktop/build/preview-civilization.png`：人类聚落、人口、资源与工作。

两张图片均已渲染并目视检查，属于本地构建产物，不提交。真实演化进入人类文明的路径由核心测试覆盖；GUI fixture 不宣称复现整局浏览器游戏。GUI 仍为独立 Swing 预览，Minecraft Screen、NBT、网络消息和 mod 注册尚未接入。

本批最终验证：JUnit 520 项，零失败、零错误；构建、生成资源、两套原版 fixture 校验和双阶段 GUI smoke test 通过。

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 开发记录

### 2026-10-28：构建与基础契约

建立 Java 21 独立工程，划分 core、storage-json、desktop；定义 GUI 与存档边界。静态导出原版 57 个物种、21 个种属和中英文文本；物种与种属目录不代表所有种族特殊能力已实现。

### 2026-10-28：原生演化规则

实现 RNA/DNA 手动操作、五种细胞结构、线性费用、自动生产、按原作顺序逐步解锁、容量重算、植物/真菌/动物及后续种属分支、随机物种选择、有限的直接物种选择。`omnivore` 仍遵守原版不可获得的 `locked` 条件，不额外开放。

支持显式传入演化阶段所用的成就/血液/技能等级、快速变异和全局生产倍率。这些只作为上游系统输入；当前工程没有实现获得这些奖励的声望/成就系统。默认新局全部为零、倍率为一。

物种确定后返回 `CIVILIZATION_READY`，包含物种、种属、基础种属/物种 traits；不推进文明阶段，也不宣称已经完成原作 `sentience()` 的全部副作用。季节特性、挑战、合并特性、合成种族额外配置、自定义/混合种族与声望派生初始化仍待迁移。`synth` 不提供需要额外配置的直接选择按钮；随机选择的物种信息仍可交给未来文明模块。

时钟采用 20 tick/s，fastLoop 每 5 tick、容量每 20 tick 重算。演化阶段没有伪造的文明日历或随机事件。暂停时不积攒补偿时间；单次最多推进 1200 tick，越界报错，绝不静默丢弃。分批推进与逐 tick 推进产生相同状态，余数及循环相位写入存档。

GUI 速率为最近一个 fastLoop 的资源变化折算值，包含溢出前的生产；不等同于原版浏览器中按不同窗口更新的 `diff`。

验证：35 组原始 JS 场景生成的 fixture，对比可见操作、费用、执行结果、生产与容量；再验证新局通过真实操作完成一条演化路径、失败扣费、暂停、读档相位、实例隔离、线程约束。当前共 428 项测试通过。fixture 只在开发时通过 Node 执行原始函数，Java 测试读取已提交的结果，产品运行不使用 JS。

```powershell
node tools/reference-fixtures.cjs --check
.\gradlew.bat :core:test
```

### 2026-10-28：独立存档适配器

`JsonSaveCodec` 实现 `SaveCodec<String>`，JSON 带 `schemaVersion=1` 和 `ruleset=evolve-1.4.10/evolution-1`。持久化资源、结构、科技、进度、种子、暂停、时间余数、循环相位、演化上下文和交接 traits。读取时检查字段、类型、精确整数、数值范围、重复键、输入大小、嵌套深度以及阶段约束；拒绝未知版本，不会把损坏存档静默当作新局。

`AtomicFileSaveStore` 使用同目录临时文件、文件 force、优先原子替换，保存上一份有效存档为 `.bak`。文件系统不支持原子移动时回退到普通替换，因此不承诺所有文件系统上的断电事务性。它不覆盖已损坏的主存档，`loadBackup()` 供宿主显式恢复。每个文件应只有一个 writer/store；未来多人模式使用独立、由服务端授权绑定的会话。

原版 LZString 浏览器存档不是本格式；当前没有无损导入器，也不能把客户端提供的 JSON 直接作为服务器状态。接入 MC 时可以实现 `SaveCodec<CompoundTag>` 或直接将 `GameSave` 映射到 SavedData/NBT，不必使用文件适配器。

验证覆盖保存/读取后继续模拟一致、时间相位保留、损坏与版本拒绝、溢出整数、重复字段、备份保留和临时文件清理。

### 2026-10-28：GUI 预览与接口验证

建立独立 Swing 预览：显示资源量、容量、速率、演化按钮、费用、数量、悬浮说明和进度；提供暂停、新局、保存、读取与中英文切换。预览只通过 `GameCommand` 操作核心，通过 `GameSnapshot` 显示数据。读取成功前不替换现有实例；加载后按钮使用新实例。

这个窗口用于在移植前验证 GUI 所需的数据和交互，不是 Minecraft 的 Screen，也不作为 mod 依赖。后续重写绘制与网络适配层时保留核心接口。GUI 预览中的 Swing Timer 每次推进一个逻辑 tick；窗口卡顿不会触发无限补算。

`smokeTest` 在不打开窗口的情况下点击真实按钮、完成部分细胞建设、保存/读取并渲染界面到 `desktop/build/preview.png`。该图片是本地构建产物，不纳入源码提交。另验证读档后按钮指向正确会话，以及坏档不会替换当前进度。

## 演化批次提交与最终验证（历史记录）

| 提交 | 内容 |
| --- | --- |
| `722959d3` | Java 构建、GUI/存档契约与静态数据 |
| `c92940f7` | 原生演化规则与原版对照测试 |
| `44c2f40d` | JSON 格式、文件保存与备份 |
| `f9dfc9b4` | 独立 GUI 预览、翻译与交互验证 |
| 文档提交 | 接入说明、覆盖边界与验证记录 |

验证环境：JDK 21、Gradle 8.14.3。`build`、`verifyGeneratedResources`、`verifyUpstreamFixtures` 与 GUI `smokeTest` 均通过。JUnit 共 434 项，零失败、零错误。core 的运行依赖通过 jdeps 检查；发布 jar 带原始许可证与署名。目标 mod 工作区没有变动。

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures
.\gradlew.bat :desktop:smokeTest
```
