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

## 构建

在本目录使用 JDK 21 执行：

```powershell
.\gradlew.bat build
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:smokeTest
```

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
