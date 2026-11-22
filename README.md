# TranForLanguage

在 Minecraft 服务端运行 C、C++、Python、Rust、JavaScript、Go、Ruby、PHP、Java 和 Lua 程序。不同语言的程序同时运行，接收游戏事件、执行游戏操作，并能按插件 ID 互相调用。

- **四个平台**：Spigot / Paper（API 1.8–26.3）、Fabric、Forge、NeoForge，逐个游戏版本构建。
- **零配置**：放入 `main.py`、`main.go`、`Main.java` 等入口文件即可，SDK 自动注入；需要第三方库时使用各语言自己的依赖清单。
- **在服务器 JVM 内运行**：C、Rust、Go、Java、Ruby、Lua 程序在准备时一次性翻译成 JVM 字节码，启动只加载已保存的类；其他语言以独立进程运行。
- **41 个游戏事件**与 `@server` 游戏操作（传送、给物品、改方块等），各平台参数一致。

## 快速开始

1. 从 [Releases](https://github.com/hekuo5310/TranForLanguage/releases) 下载与服务器平台和游戏版本对应的 JAR，放入 `plugins/`（Spigot）或 `mods/`（模组加载器）。
2. 在程序目录（Spigot：`plugins/TranForLanguage/programs/`，模组：`config/tranforlanguage/programs/`）下新建子目录，例如 `welcome/main.py`：

   ```python
   from tranforlanguage import on, run, send_message

   @on("PlayerJoin")
   def joined(player):
       send_message(player, "Welcome from Python!")

   run()
   ```

3. 执行 `/tranforlanguage reload`，再用 `/tranforlanguage list` 查看状态。

## 文档

| 文档 | 内容 |
| --- | --- |
| [安装与构建](docs/installation.md) | 各平台产物、从源码构建、程序目录 |
| [语言指南](docs/languages.md) | 每种语言的入口文件、工具链与示例 |
| [在服务器 JVM 内运行](docs/jvm-runtime.md) | 预先翻译成字节码的方式、`runtime` 选项、翻译器下载 |
| [多程序共存与互相调用](docs/multi-program.md) | 插件子目录、事件订阅、跨语言调用与发布 / 订阅 |
| [命令、权限与运行边界](docs/administration.md) | 管理命令、权限节点、安全说明 |
| [开发文档](docs/development.md) | 编写程序的完整参考：`program.json`、事件、`@server`、依赖管理、SDK 速查、排错 |
| [协议文档](docs/protocol.md) | JSON Lines 协议与语言适配器扩展 |
| [兼容说明](docs/compatibility.md) | 版本矩阵、平台事件覆盖与验证范围 |
| [从 2.1 及更早版本升级](docs/upgrading.md) | 2.2.0 起的改名与迁移步骤 |
| [贡献指南](CONTRIBUTING.md) | 开发环境、项目结构、常见改动、提交流程 |

示例程序见 [examples/](examples)。

**只安装你信任的程序**：程序以服务器的权限运行，没有沙箱，详见[运行边界](docs/administration.md#程序权限与运行边界)。

## 赞助商

- [ZawaZawa](https://www.zawa.uk/) — 视频、专栏与动态社区
- [Nodeloc](https://www.nodeloc.com/invites/QUeeBS9RU3) — 自由、平等、友好、开放、有趣的互联网交流社区

## 许可证

[Apache License 2.0](LICENSE)
