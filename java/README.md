# Evolve Java 迁移工程

目标环境：Minecraft 1.21.1、NeoForge、Java 21。当前工程独立于 mod 仓库，原网页源码作为规则对照保留。

游戏在未来的 Minecraft GUI 中运行。规则由服务端持有，GUI 消费不可变快照并提交操作意图，不直接修改资源或推进时间。

## 模块

| 模块 | 职责 | 运行时依赖 |
| --- | --- | --- |
| `core` | 游戏规则、操作、快照、可移植存档模型与接口 | 仅 Java 21 |
| `storage-json` | 版本化 JSON 与文件保存适配器 | core、Gson |
| `desktop` | 独立 GUI 预览，用于验证未来 MC GUI 使用的同一套接口 | storage-json、JDK Swing |

`core` 不引用 Minecraft、NeoForge、AE2、Swing、DOM、JavaScript 引擎、计时器或文件系统。之后接入 mod 时，无需携带 desktop。

当前支持原生演化，以及普通人类在 grassland / standard 下的聚落：手动采集、基础科技与住房、农业/灌溉/石斧/采矿/货币研究、农田/伐木场/采石场、工作分配、人口与饥饿、在岗人口税收、基础/强化窝棚储存、农舍人口、大学/教授、图书馆/论文/科研补助、银行/投资/银行家、铜铁采矿和铜/铁斧与镐、木材燃料熔炉及炉位分配、水泥厂/水泥工、铁锯/锯木厂、铸造厂/工匠/学徒/木工及胶合板/砖块/锻铁的手动与自动制造、驻军自动训练与食物/皮毛狩猎。工匠共享总岗位，矿工、水泥工、教授和银行家有岗位上限和自动补员。其他物种暂留在文明交接点；钢与其他燃料、后续科研与金融、高阶材料制造、士气/天气/政体仍待迁移。完整边界和下一批顺序见 [MIGRATION.md](MIGRATION.md)。

当前 Java 存档为 schema 14 / `evolve-1.4.10/carpentry-1`。项目处于开发阶段，不维护旧版存档兼容；版本不符会拒绝读取并保留当前游戏。

## 构建

在本目录使用 JDK 21 执行：

```powershell
.\gradlew.bat build
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:smokeTest
```

原版规则对照与生成资源校验：

```powershell
.\gradlew.bat verifyGeneratedResources verifyUpstreamFixtures verifyCivilizationFixtures
```

`smokeTest` 不打开窗口，渲染演化、文明起步、工作分配、大学科研、银行投资、铜工具、铁工具、木材熔炉、水泥/锯木厂、农舍/强化窝棚、铸造制造、驻军狩猎、图书馆科研、学徒/木工十四个阶段。输出位于 `desktop/build/preview*.png`，包括新增的 `preview-carpentry.png`。验证真实按钮、生产收入、扩容与科研、铜铁工具、有限岗位、熔炉分配/停炉、水泥原料生产、锯木厂、农舍与强化仓储的延迟生效、制造配方与共享工匠岗位、训练进度和狩猎、图书馆制造成本与科研升级延迟生效、配方分工和木工倍率、保存/读取与读档后的操作，并检查各阶段快照的中英文翻译。

GUI 存档默认为当前工作目录下的 `evolve.save.json`。可使用参数指定位置或默认语言：

```powershell
.\gradlew.bat :desktop:run --args="--english --save=my-game.save.json"
```

首次使用 wrapper 需要下载固定的 Gradle 发行版；测试和 JSON 适配器依赖从 Maven Central 解析。运行 core 不需要 Node.js、浏览器或脚本引擎。

资源提取脚本只在维护时运行，生成结果已纳入版本控制：

```powershell
node tools/export-upstream.cjs
```

脚本读取原版静态物种、种属和中英文文本，清理 HTML，并生成 `assets/evolve/lang` 中 Minecraft 使用的翻译资源。它不把 JS 函数伪装成 Java 规则。详细范围、规则差异、接口示例和下一步迁移顺序见 [MIGRATION.md](MIGRATION.md)。

## 许可证

移植规则和原作数据保留 MPL-2.0，见仓库根目录 `LICENSE` 和本目录 `NOTICE`。打包任务将它们放入 jar 的 `META-INF`。
