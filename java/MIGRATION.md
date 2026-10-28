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
| 工作分配、农业、伐木、采石、基础税收 | 有限实现 | 农民/伐木工/采石工，固定默认闲置工作、中性天气和 20% 税率 |
| 基础储存、大学与科研工作 | 有限实现 | 基础窝棚与大学、教授岗位和自动补员；图书馆与后续科研尚未迁移 |
| 银行、投资与银行家 | 有限实现 | 基础资金扩容、有限岗位与吃饱时税收加成；金库、市场和后续金融尚未迁移 |
| 铜铁采矿与工具 | 有限实现 | 矿井、有限矿工、铜/铁斧与镐，固定无地质/供电影响 |
| 基础熔炉与工业分配 | 有限实现 | 木材燃料、铁增产与炉位控制；煤/油、钢及后续冶炼尚未迁移 |
| 水泥与锯木厂 | 有限实现 | 水泥厂、有限水泥工、铁锯与锯木厂；原料短缺按 fastLoop 处理 |
| 工业、政体、战斗 | 未迁移 | 保留原版源码供对照 |
| 太空、星际、星系、地狱、伊甸、Truepath | 未迁移 | 保留原版源码供对照 |
| ARPA、CRISPR、声望、成就、重置、总督、随机事件 | 未迁移 | 不能把数据提取等同于玩法移植 |

`UPSTREAM_INVENTORY.json` 记录原始模块、源码行数、浏览器引用和静态 action ID。它是清单，不是玩法覆盖率报告。

## 后续 Minecraft GUI 接入方式

| 位置 | 负责事项 |
| --- | --- |
| 服务端会话管理 | 决定按玩家、团队或方块保存一局；维护实例生命周期和访问权限 |
| 服务端 tick | 在实例所属线程调用 `advanceTicks(1)`；不能在 GUI 渲染时推进 |
| C2S 操作消息 | 操作为稳定 action ID 与次数，工作为 job ID，熔炉为分配 ID 与有界增减数量；服务端重新检查权限、解锁、容量、人数和费用 |
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

当前工作与基础生产已经打通窝棚、大学、银行与投资、铜铁采矿与工具、木材燃料熔炉，以及水泥与锯木厂。除工作外，已支持服务端校验的燃料/铁产物炉位分配。最新状态使用 schema 9 / `evolve-1.4.10/cement-1`；旧版 Java 存档直接拒绝。

下一批继续补农舍人口、强化仓储、铸造/制造材料链与图书馆，并补煤矿、钢及其他燃料分配；再迁移基础市场、士气、日历/天气和其他种族的初始化与特性。随后扩展工业/政体/军事、太空各层级，以及声望与重置。每个系统补原版场景对照并单独提交。按当前开发阶段约定升级存档版本，不实现旧版兼容。

## 当前批次：水泥与锯木厂

新增 `tech-cement`（mining 1 / storage 1 / science 1，500 知识）和水泥厂。首座消耗 3000 资金、1800 木材、2000 石头，后续分别乘 1.5 / 1.36 / 1.32 并取整；每座提供两个有限水泥工岗位，复用工作目标和自动补员。水泥工在知识结算后、熔炉及采石生产前运行，每人每秒消耗 3 石头、基础产出 0.4 水泥；每 fastLoop 先按已有石头的 0.75 单位门槛决定开工人数。饥饿与宿主倍率只调整产出，原料消耗保持不变，本轮采石供下轮使用。石头快照显示净速率。

新增 `tech-iron_saw`（axe 1 / mining 3，3375 知识 / 400 铁）和锯木厂。首座消耗 3000 资金、400 铁、420 水泥，后续各乘 1.26 并取整。每座立即增加 200 木材容量，伐木产出乘 `1 + 0.05 × 锯木厂数`，再乘伐木场倍率；midLoop 保留容量。当前未迁移供电与后续锯具。

水泥初始隐藏、容量 100；建成水泥厂显示资源，已有窝棚在下一 midLoop 每座增加 100 容量，后建窝棚立即生效。读档保留待重算容量。存档升级为 schema 9 / `evolve-1.4.10/cement-1`，水泥资源和水泥工人数/目标必需；检查科技/建筑依赖、可见性、仓储和岗位上限，拒绝 schema 1–8 与旧 ruleset。当前没有其他水泥来源，已建锯木厂要求存在水泥厂。

原版 fixture 为 2 组初始化、30 组聚落/费用、47 组生产、22 组工作分配及 8 组熔炉控制。新增原始水泥生产及锯木厂容量代码块，覆盖石头 0 / 0.749 / 0.75 / 1.499 / 1.5 边界、饥饿、宿主倍率和多厂倍率。真实指令与第九阶段 GUI 打通研究、建厂、工作、锯木厂扩容、保存/读取和读档后按钮绑定；JSON 覆盖原料耗尽与待重算存储、未来生产一致和坏档拒绝。`desktop/build/preview-cement.png` 已渲染并目视检查，不提交构建产物。

最终验证全部通过，JUnit 1084 项，零失败、零错误；九阶段 GUI、生成资源及两套原版 fixture 校验通过：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 木材燃料熔炉与工业控制批次（历史记录）

本批提交为 `23ddfeea`（`feat(core): add wood smelting and authority-checked furnace controls`）。

新增 `tech-smelting`（mining 3，4050 知识）和 `city-smelter`（起始 1000 资金 / 500 铁，分别按 1.32 / 1.33 递增取整）。每座熔炉增加一炉位，并按原版立即配置一木材燃料位和一铁产物位；当前基础大学需要八所才能研究冶炼。

新增 `GameCommand.AdjustSmelter(allocationId, delta)`，支持 `Wood` 和 `Iron`，增减范围为 -100…-1 / 1…100。核心重新检查阶段、暂停、已建熔炉和分配上限；部分应用返回 `NO_AVAILABLE_CAPACITY` 与实际 `applied()`。木材位增加时同步增加铁位，减少时只释放超过剩余燃料数的铁位；铁位可独立减少，下一 fastLoop 在没有其他已解锁产物时自动补回一个未分配位。当前阶段没有其他燃料/产物转换，不接受 `Coal`、`Steel` 等尚未支持的 ID。

熔炉在 fastLoop 的知识结算之后、木材生产之前运行。每个有足够燃料的木材位每秒消耗 3 木材，单次 fastLoop 必须预先具备 0.75 木材；本轮伐木产出留到下一轮使用。燃料消耗不乘饥饿或宿主生产倍率，产物不足分配时也保留原版燃料消耗。按原版分配和缺燃料停炉顺序得出有效铁位，每位让矿工铁产出倍率增加 0.1；它不凭空生产铁，也不增加铜产出。木材快照速率包含熔炉消耗和伐木产出的净值。

新增不可变 `SmelterState(wood, iron)`，作为文明状态的一部分；`GameSnapshot.smelter()` 提供总炉位、配置的木材/铁数量与可操作状态。缺燃料导致的临时停炉只影响本轮产出，不改变已保存的配置。工作分配、死亡人数归一化和容量重算都保留工业配置。

存档升级为 schema 8 / `evolve-1.4.10/smelting-1`，`civilization.smelter` 是所有阶段必需字段；JSON 严格检查精确整数、未知字段和缺失字段，核心检查冶炼/建筑依赖及 `0 ≤ iron ≤ wood ≤ smelter count`。演化和文明交接必须是空工业状态。拒绝 schema 1–7 与旧 ruleset，不维护兼容。

原版 fixture 增为 2 组初始化、26 组聚落/费用、39 组生产、18 组工作分配及 8 组熔炉控制场景；开发脚本执行原始 `addSmelter`、燃料配置、工业面板 add/sub 方法和完整熔炉 fastLoop 代码块。覆盖 0 / 0.749 / 0.75 / 1.499 / 1.5 木材边界、部分燃料、饥饿与宿主倍率、逐轮铁位补回和控制截断。准备阶段改为建造失败即报错，先建少量窝棚/银行再扩容，避免高阶 fixture 因仓储不足静默少建。

正常操作测试和第八阶段 GUI 均从原有聚落扩容至八所大学、研究并建成熔炉，再通过工作与工业按钮实现木材自持、铁增产、停炉、部分分配、暂停以及保存/读取后的正确绑定。扩容流程结合窝棚与采石场，不绕过递增费用。JSON 验证配置缺口与燃料耗尽前后的循环边界，坏档不会替换当前实例。`desktop/build/preview-smelting.png` 已渲染并目视检查，不提交构建产物。

本批仍只支持普通人类 / grassland / standard、木材燃料与铁增产；煤矿/煤燃料、油、钢、其他种族的默认燃料、火属性和高阶熔炉仍待迁移。最终验证全部通过，JUnit 963 项，零失败、零错误；八阶段 GUI、生成资源及原版 fixture 校验通过：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 铁矿与铁工具批次（历史记录）

本批提交为 `8c71db52`（`feat(core): add iron mining and iron tool progression`）。

新增 `tech-iron_mining`（mining 2，2500 知识），将 mining 升为 3 并显示铁。原版矿工同时生产铜和铁，当前人类环境下铁的基础产出为每人 `1 / 4` 每秒，随后乘镐倍率、饥饿与宿主生产倍率；铁解锁前产出为零。铜镐增加 15%，铁镐使两类矿物的镐倍率达到 1.30，没有替换或减少铜产出。

新增 `tech-iron_axes`（axe 2 / mining 3，2700 知识 / 250 铁）与 `tech-iron_pickaxe`（pickaxe 1 / mining 3，3200 知识 / 250 铁）。铁斧使伐木倍率达到 1.70，再乘伐木场等已支持倍率。原版依赖、单次原子扣费、研究不可重复和知识容量门槛保持不变；铁斧需要至少六所当前基础大学，铁镐需要七所。

铁初始隐藏、容量 100，每间已生效窝棚增加 125。与铜一样，解锁前已有窝棚在下一 midLoop 生效，解锁后新建窝棚立即生效；读取存档保留尚未重算的容量。抽取共用材料容量验证，分别检查铜的 90 与铁的 125 增量、可见性、隐藏资源的零数量/速率和实际窝棚上限。

存档升级为 schema 7 / `evolve-1.4.10/iron-1`，文明资源新增必需的 `Iron`，校验铁采矿与铁工具等级依赖。拒绝 schema 1–6 及旧 ruleset，无兼容迁移器。仍不计算地质、供电、熔炉、铁过敏、其他种族和外星输入；铁锯/锯木厂依赖水泥链，尚未迁移。

原版 fixture 覆盖 2 组初始化、24 组聚落/费用、31 组生产和 18 组分配；新增原始铁采矿、铁工具 action 和矿工铁生产分支，验证饥饿及工具倍率。正常命令流程扩为十人口、五农民自持生产，按实际费用建造银行、窝棚与七所大学，再完成铁采矿和两种铁工具，未注入资金、知识或矿物。JSON 测试覆盖容量待重算状态、继续生产和伪造资源/解锁的拒绝。

第七阶段 GUI smoke test 使用实际按钮完成同一扩容/研究路线，检查读档不会提前应用铁容量、读档后矿工按钮绑定与三种生产速率。所有预览阶段遍历中英文快照文本，检查缺失翻译；`desktop/build/preview-iron.png` 已渲染并目视检查，不提交构建产物。

本批最终检查通过，JUnit 881 项，零失败、零错误；七阶段 GUI、生成资源与两套原版 fixture 校验通过：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 铜矿与铜工具批次（历史记录）

本批提交为 `89621732`（`feat(core): add copper mines miners and copper tools`）。

新增 `tech-metal_working`（mining 1，350 知识）、`city-mine`，以及 `tech-copper_axes`（axe 1 / mining 2，540 知识 / 25 铜）和 `tech-copper_pickaxe`（mining 2，675 知识 / 25 铜）。保留矿井资金/木材递增取整费用。矿井建成后解锁铜和矿工，每座提供一个有限岗位；矿工复用已有手动分配、死亡目标保留和每 fastLoop 一人的自动补员。

当前普通人类、grassland、standard 下，矿工基础产铜为每人 `1 / 7` 每秒，铜镐使矿工基础产出增加 15%；铜斧使伐木工产出增加 35%，再乘伐木场倍率。所有生产继续应用饥饿和宿主全局倍率。尚未迁移行星生成/地质，明确固定无地质额外倍率；矿井当前没有供电、传送带、铁矿分支、熔炉或外星输入。

铜初始隐藏、容量 100。已有窝棚在铜解锁后的下一个 midLoop 才各增加 90 容量；铜已解锁时，新建窝棚立即增加 90。核心按当前容量保存这一时序，读取不会提前重算；容量验证要求 `100 + 90 × 已生效窝棚数`，已生效数不得超过实际窝棚数，隐藏铜必须保持基础容量、零数量和零速率。正常操作与 JSON 测试覆盖解锁后、重算前、追加窝棚和重算后的差异。

存档升级为 schema 6 / `evolve-1.4.10/copper-1`，文明资源新增必需的 `Copper`，工作及目标新增 `miner`。验证矿井、采矿科技、铜工具、岗位和资源可见性/容量依赖，拒绝 schema 1–5 与旧 ruleset。铜镐需要至少两所大学的知识容量；未绕过原版费用或容量条件。

原版 fixture 扩为 2 组初始化、21 组聚落/费用、28 组生产和 18 组分配，增加原始金属加工、矿井、铜工具 action 和矿工生产代码块；覆盖饥饿、铜工具倍率、有限岗位自动补员和容量时序。核心正常操作打通矿井、铜斧和铜镐；第六阶段 GUI smoke test 使用真实按钮完成相同流程，验证存档与读取后的矿工按钮。`desktop/build/preview-copper.png` 已渲染并目视检查，不提交构建产物。

本批最终检查全部通过，JUnit 823 项，零失败、零错误；六阶段 GUI、生成资源及两套原版 fixture 校验通过：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 银行与投资批次（历史记录）

本批提交为 `ada97918`（`feat(core): add banks investment and bounded banker jobs`）。

新增 `tech-banking`（currency 1，90 知识）、`city-bank` 和 `tech-investing`（banking 1，2500 资金 / 900 知识）。银行保留原版资金/木材/石头的逐次取整费用，每座立即增加 1800 资金容量，并由 midLoop 依原始 `bank_vault()` 基础分支重算。当前知识容量需要至少两所大学才能研究投资；第一间银行将资金容量从 1000 扩为 2800，解除投资及第三所大学的资金容量限制，其他资源容量仍独立检查。

投资后开放 `banker`，每间银行提供一个岗位。银行家本身也属于在岗纳税人口；在吃饱的 fastLoop 中，每名银行家使基础税收乘数增加 0.1，随后应用宿主倍率并逐次按原版保留两位小数。饥饿时银行家加成失效，税收仍按既有规则减半。与教授共用有限岗位检查和每 fastLoop 一人的目标补员机制，死亡按当前宿主的反向工作顺序释放，目标保留。

存档升级为 schema 5 / `evolve-1.4.10/banking-1`。文明工作/目标集合新增必需的 `banker`；验证银行/研究依赖、资金容量、投资解锁和工作目标上限。旧 schema 1–4 及旧 ruleset 均拒绝，不维护兼容。金库、后续银行科技、市场交易、动态税率和政体仍未迁移。

原版 fixture 扩为 2 组初始化、18 组聚落/费用、24 组生产和 14 组分配，覆盖银行 action、原始金库容量函数、投资、吃饱与饥饿收入，以及多个有限岗位同时补员。正常操作测试从已有聚落完成银行、第二所大学、投资、第七间住房和银行家；GUI 使用真实按钮走完同一路径，验证满岗禁用、保存/读取与按钮实例绑定。`desktop/build/preview-banking.png` 已渲染并目视检查，不提交构建产物。

本批最终检查全部通过，JUnit 755 项，零失败、零错误；五阶段 GUI smoke test、生成资源和原版 fixture 校验通过：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 基础仓储与科研批次（历史记录）

本批提交为 `210ede77`（`feat(core): add basic storage universities and bounded research jobs`）。

新增 `tech-storage`（primitive 3 / currency 1，20 知识）和 `city-shed`。窝棚费用按原作逐次递增取整，每座增加木材和石头容量各 300，立即生效并在 midLoop 重算时保留；当前只提供基础窝棚，后续强化、谷仓、仓库、箱子和集装箱尚未迁移。其他未解锁的原版资源不作为已迁移资源暴露。

新增 `tech-science`（housing 1，65 知识）与 `city-university`。大学的资金、木材、石头费用保留原版取整后减去常量的计算顺序；每座增加 500 知识容量和 1 个教授岗位。知识产出为日晷 1/s 加上每位教授 0.5/s，再应用当前宿主倍率；饥饿仅将教授产出减半，不影响日晷。第一所大学可以通过正常操作建成；第三所大学的资金需求超过当前 1000 基础容量，仍需要未来银行/贸易系统继续推进。图书馆、科学家、论文和后续科研依赖尚未迁移。

教授岗位在建成第一所大学后解锁，`jobs().capacity()` 返回大学数，分配命令与 GUI 同时检查岗位余量。新增不可变 `CivilizationState.assigned` 保存各工作的手动分配目标；死亡保留目标，教授在每次 fastLoop 生产前最多从闲置人口自动补回一人。撤回实际工人时目标同步降低。三种无限岗位仍遵守原版 `max = -1` 不参与这一自动补员分支。继续沿用既有的 tick 结束劳动人数归一化约定，未声称迁移完整劳动压力/异常修复。

存档升级为 `schemaVersion=4` / `evolve-1.4.10/science-1`，所有文明工作集合增加 `professor` 和必需的 `assigned`。JSON 严格检查两个工作集合及精确整数；核心验证研究、建筑、资源容量和岗位上限/目标依赖。演化与文明交接存档必须没有工作或目标。拒绝 schema 1–3 及旧 ruleset，不提供兼容迁移器。

原版 fixture 现覆盖 2 组初始化、15 组聚落/费用、20 组生产和 10 组分配场景，新增原始窝棚/大学 action、`knowVal()`、仓储/知识容量和有限岗位补员代码块。验证包括正常操作建成第一所大学、批量岗位截断、满岗与锁定拒绝、饥饿知识产出、死亡目标保留、逐 fastLoop 补员及 JSON 读取后继续模拟一致。

GUI 显示教授的当前人数/岗位上限，满岗时禁用分配；新增中英文科技、建筑、岗位和效果文字。四阶段 smoke test 使用真实按钮从演化预览、文明起步、工作聚落推进到仓储/大学，验证保存/读取后教授按钮绑定当前实例。`desktop/build/preview-science.png` 已渲染并目视检查，属于不提交的构建产物。

本批最终检查全部通过，JUnit 687 项，零失败、零错误：

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 工作与基础生产批次（历史记录）

### 核心与存档

新增 `GameCommand.AssignJob(jobId, delta)`，正数从闲置人口分配，负数撤回；单次范围为 -100…-1 或 1…100。支持 `farmer`、`lumberjack`、`quarry_worker`，服务器重新检查阶段、暂停、解锁和当前人数。超过可调配人数时只应用实际可调配数量，返回 `NO_AVAILABLE_WORKERS` 与实际 `applied()`，不会凭空增加人口。默认工作固定为 unemployed，暂不提供切换默认工作或其他职业。

工作解锁按原作：农田建成后开放农民，石斧研究完成即开放伐木工，采石场建成后开放采石工。三类工作没有固定岗位上限，`jobs().capacity()` 为 -1。农田内农民基础产出为 `0.82 + 0.65`，灌溉后为 `0.82 + 1.15`；超过农田数量的农民按 0.82 计算，均乘 grassland 的 1.2 倍率。

新增农业、灌溉、石斧、采矿、货币五项研究；新增农田、伐木场、采石场三类建筑。保留原版解锁依赖、逐次递增并取整的费用，以及农田第四座、伐木场第六座、采石场第三座起的货币要求。伐木场和采石场每座分别增加对应产出 2% 与容量 100。农业只解锁生产，尚未研究 `farm_house`，农田本身不会增加人口容量。

在岗人口每人消耗 1 食物/s，闲置人口每人消耗 0.5 食物/s。食物产出与消耗先结算，再判断饥饿、营养死亡阈值及人口增长，随后处理知识、木材、石头与收入。饥饿使木材、石头与税收减半，日晷知识不受这一惩罚。货币研究解锁资金资源，20% 默认税率下每名在岗人口产生 0.4 资金/s；每次 fastLoop 的入账保留两位小数。正常操作测试已完成三农民、一伐木工、一采石工自持生产，再积累资金建成第六间住房。

修正上一批遗漏的文明 midLoop 容量：资源定义阶段食物容量为 250，进入文明后的第一次全局 20 tick 容量重算变为 1000，随后保持该基础值；伐木场/采石场容量同样重算。`capacitiesInitialized` 持久化这一差异，读档不会把尚未发生的容量重算提前。

与原版时序的明确差异：浏览器延迟到下个 fastLoop 的劳动人数修复不作为合法存档状态保留。当前 Java 在 tick 的资源结算结束后立即保持“闲置人数 + 各工作人数 = 人口”，死亡时先减少闲置人口，再按采石工、伐木工、农民顺序释放岗位；这一调度约定与原版场景测试的宿主归一化一致。三种无限岗位暂不维护原版受限职业的 `assigned` 自动补员目标，也不宣称完成完整劳动异常/工作压力系统。

本批仍只支持普通人类 / grassland / standard。天气固定中性，税率固定 20%，全局生产倍率继续由宿主 `EvolutionContext.productionMultiplier` 提供，尚未计算士气、工作压力、天气、声望和政体倍率。知识容量保持 100；大学/教授、储存建筑、农舍、市场交易及后续文明系统留待下一批。

存档升级为 `schemaVersion=3` / `ruleset=evolve-1.4.10/settlement-1`，文明字段增加不可变 `workers` 与 `capacitiesInitialized`。JSON 严格检查工作集合、精确整数和布尔类型，核心检查工作/资源/建筑/科技依赖、总人口、容量与可见性。按开发阶段约定拒绝 schema 1/2，不提供兼容迁移器。演化及交接存档必须没有工作分配和文明容量标记。

原版 fixture 扩展为 2 组初始化、12 组聚落/费用、16 组生产及 6 组工作分配场景。开发脚本执行原始研究/建筑/采集 action、费用函数、`farmerValue`、Vue 工作 add/sub 方法、food/growth/knowledge/lumber/stone/income 代码块和相关 midLoop 基础/储存容量块；天气、税率与未移植系统的输入明确固定，劳动人数归一化是宿主约定。Java 测试读取提交的 fixture，产品运行仍不使用 JS。

验证包括原版对照、真实操作打通第六间住房、全员工作与闲置人口的税收、饥饿死亡后岗位一致、不可变工作数据、非法/越界/锁定/暂停操作、部分分配、跨 JSON 读档后生产与随机序列一致。核心与存档 616 项 JUnit 通过，生成资源和两套原版 fixture 校验通过：

```powershell
.\gradlew.bat :core:test :storage-json:test verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures
```

本批核心与存档提交为 `dd4cc649`（`feat(core): add settlement jobs farming and tax income`）。

### GUI 工作控制与最终验证

工作视图从只读文字改为固定在操作区域下方的工作面板，避免资源列表变长后需要先滚动到底才能分配。新职业随快照解锁，每行显示实时人数和分配/撤回按钮，闲置人口单独显示。按钮按暂停、可调配人数及当前职业人数禁用，操作仍只发送 `AssignJob`，核心重新校验。按钮提示已本地化，无障碍名称包含职业名，读档后使用当前实例。

补齐新增科技、建筑及职业的中英文文本；测试遍历完整已支持科技/建筑流程检查翻译 key。GUI 合约测试覆盖没有闲置人口、撤回零人、暂停禁用、保存/读取后分配与坏档保留；smoke test 用真实按钮从交接点完成农业、三农民、一伐木工、一采石工、货币税收、第六间住房和灌溉，再验证工作存档与读档后的按钮绑定。

新增 `desktop/build/preview-jobs.png` 展示工作聚落；与演化、文明起步两张预览一起渲染并目视检查，构建产物不提交。Minecraft Screen、网络协议和 NBT/宿主注册仍未接入。

本批最终检查：构建、生成资源、两套原版场景校验与三个 GUI 场景通过；JUnit 621 项，零失败、零错误。

```powershell
.\gradlew.bat build verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures :desktop:smokeTest
```

## 原始文明起步批次（历史记录）

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
