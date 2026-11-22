# 贡献指南

感谢你愿意改进 TranForLanguage！本文说明如何搭建开发环境、项目如何组织、怎样做常见改动，以及提交 PR 前要检查什么。

想用 TranForLanguage 写程序而不是修改它本身，请看[开发文档](docs/development.md)。

## 报告问题

提交 Issue 时请附上：

- 平台与版本：Spigot / Fabric / Forge / NeoForge、Minecraft 版本、加载器版本、服务器 Java 版本；
- 程序所用语言、`program.json`，以及能复现问题的最小程序；
- 服务器日志中带插件 ID 的相关行，以及程序目录下的 `compile.log`；
- 程序是在服务器 JVM 内运行还是独立进程（日志中会出现 `Running translated program inside the server JVM` 或 `running as a separate process`）。

**安全问题请不要公开提交 Issue**，请私下联系维护者。

## 开发环境

| 用途 | 需要 |
| --- | --- |
| Spigot 插件与核心测试 | JDK 17+（建议 21 或 25，JRuby 相关测试需要 21+）、Maven 3.9+ |
| 模组构建 | `python3 scripts/configure-build.py` 输出的 Gradle 版本，以及目标游戏的 JDK 17 / 21 / 25 |
| 各语言集成测试 | gcc / g++、Python 3.8+、Rust 1.85+ 与 Cargo、Node.js 16+、Go 1.21+、Ruby 3.0+、PHP 7.4+、Lua 5.4 |
| 在 JVM 内运行的测试 | clang、lld、wasi-libc、与 clang 主版本一致的 `libclang-rt-<版本>-dev-wasm32`，以及 `rustup target add wasm32-wasip1` |
| 构建脚本（`scripts/*.py`） | Python 3.11+（`check-artifact.py` 使用 `tomllib`） |
| 实际开服验证（可选） | Node.js 22+，见 [validation/README.md](validation/README.md) |

Debian / Ubuntu 示例：

```bash
sudo apt-get install -y gcc g++ python3 nodejs ruby php-cli lua5.4 clang lld wasi-libc
sudo apt-get install -y "libclang-rt-$(clang -dumpversion | cut -d. -f1)-dev-wasm32"
rustup target add wasm32-wasip1
```

完整的 `mvn verify` 需要上表**全部十种语言的运行工具链**：`InteropTest` 会启动所有语言，任何一种启动失败都会让测试（以及 `mvn verify`）失败，不会跳过。只缺少在 JVM 内运行所需的 WASM 工具链时，相应程序会回退为独立进程，`JvmRuntimeTest` 中对应的测试会跳过；CI 使用 `-Dtranforlanguage.test.requireJvm=true`，此时没能在 JVM 内运行会被视为失败。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `core/src/main/java` | 与平台无关的核心（Java 8）：`ProgramManager`（插件管理）、`ProgramRuntime`（进程与协议）、`ProgramBus`（程序间调用）、`language/`（各语言适配器）、`jvm/`（在 JVM 内运行的框架） |
| `core/src/engine/java` | 驱动翻译器（Chicory、ASM、JRuby、LuaJ）的胶水代码，以 Java 11 编译，运行时与下载的库一起在隔离类加载器中加载 |
| `core/src/main/resources/sdk/` | 各语言 SDK，随 JAR 发行，运行时提取到程序目录的 `.tranforlanguage/sdk/` |
| `core/src/test/` | 核心测试与 `interop/<语言>/` 互调测试程序 |
| `src/` | Spigot 插件（`ProcessManager` 监听 Bukkit 事件，`SpigotServerApi` 实现 `@server`） |
| `adapters/minecraft/` | 三个模组加载器共用的代码：命令、`ModEvents`（按 tick 检查状态）、`MinecraftPlatform`、Mixin |
| `adapters/fabric/`、`adapters/forge/`、`adapters/neoforge/` | 各加载器的入口与构建脚本；共用逻辑在 `scripts/generate-adapter.gradle` |
| `scripts/` | 版本锁定、构建配置、产物检查 |
| `compatibility/` | 锁定的游戏 / 加载器版本矩阵 |
| `validation/` | 真实服务端冒烟测试 |
| `examples/` | 各语言示例 |
| `docs/` | 用户与开发者文档 |

## 构建与测试

```bash
mvn verify                                    # Spigot 插件 + 全部核心测试
mvn test -Dtest=InteropTest                   # 只跑 10 种语言同时运行、互相调用的测试
python3 scripts/test-versions.py              # 版本范围与 Java 边界
python3 scripts/check-artifact.py spigot 1.8 8 'target/tranforlanguage-spigot-*.jar'

python3 scripts/configure-build.py fabric 1.21.1
gradle -p adapters/fabric build               # 构建单个模组目标
python3 scripts/check-artifact.py fabric 1.21.1 21 'adapters/fabric/build/libs/*.jar'
```

核心测试会真实编译并运行各语言程序。测试下载的翻译器库缓存在 `target/runtimes/`。

## 常见改动

### 添加一种语言

1. 在 `core/src/main/java/.../core/language/` 实现 `LanguageProvider`：`id()` 返回语言标识，`prepare()` 完成编译并返回 `LaunchCommand`（工作目录、参数、附加环境变量）。不要在这里访问游戏 API。
2. 能预先翻译成 JVM 字节码的语言，用 `InJvm.attempt(...)` 先尝试 `LaunchCommand.inJvm(...)`，失败时回退为进程。翻译在 `prepare` 中完成并缓存，启动时不再翻译。翻译器库加入 `RuntimeLibraries`（固定版本与 SHA-256，**不打包进 JAR**），胶水代码放在 `core/src/engine/java`，并在 `pom.xml` 与 `scripts/generate-adapter.gradle` 中以 compile-only 引用。详见[协议文档](docs/protocol.md#在-jvm-内运行的翻译器)。
3. 在 `LanguageProviders` 的构造函数中注册，并在 `detectLanguage()` 中加入自动识别的文件名。
4. 在 `core/src/main/resources/sdk/<语言>/` 提供 SDK，实现 `on` / `run`、四个动作、`server`，以及 `handle` / `call` / `notify` / `publish`。等待回复时要处理收到的 `call`，避免互相调用死锁。
5. 测试：在 `BuiltinLanguagesTest` 或 `JvmRuntimeTest` 加入中文往返测试；在 `core/src/test/resources/interop/<语言>/` 加入互调测试程序，并把语言加入 `InteropTest.LANGUAGES`。
6. 在 `scripts/check-artifact.py` 中加入 SDK 文件，在 CI 中安装工具链，并更新 [语言指南](docs/languages.md)、[开发文档](docs/development.md) 的语言表与 SDK 速查。

### 添加一个事件

1. **Spigot**：在 `ProcessManager` 中添加 `@EventHandler`。只在新版本才有的 Bukkit 事件用 `optional(...)` 按类名注册，并通过反射访问新 API，保持 1.8 基线可编译。
2. **模组**：优先在 `ModEvents.tick` 中比较状态（不需要新 Mixin，跨版本最稳定）；需要拦截游戏逻辑时再加 Mixin（`adapters/minecraft/src/main/mixins/`，并加入 `generate-adapter.gradle` 的 Mixin 列表）。版本差异用 `.template` 文件和 `generate-adapter.gradle` 中的替换处理；只是成员名或可见性变化时，放进 `Versions.java.template`。
3. 事件名使用 PascalCase，参数全部是字符串，同一事件在各平台的参数顺序必须一致。
4. 更新[开发文档](docs/development.md#5-事件参考)第 5 节和[兼容说明](docs/compatibility.md)的事件表。

### 添加一个 `@server` 方法

1. Spigot：在 `SpigotServerApi` 中实现；模组：在 `MinecraftPlatform.serverCall` 中实现。参数用 `ServerArgs` 读取，以保证错误信息一致；失败时抛出 `IllegalArgumentException`。
2. 模组端涉及跨版本变化的 API 时，优先通过 Brigadier 执行原版命令（`command(...)`），并严格校验拼入命令的参数。
3. 在 `ProgramManagerTest` / `validation/run.cjs` 中加入测试，更新[开发文档](docs/development.md#61-游戏操作server)第 6.1 节。

## 代码约定

- **核心保持 Java 8**（Spigot 1.8 基线）。只有 `core/src/engine/java` 可以使用 Java 11，且只在 Java 11+ 的服务器上加载。
- **不访问游戏 API 的线程不碰游戏对象**：所有游戏操作通过 `Platform.execute` 在服务器线程执行。
- **不打包第三方运行时**：翻译器在首次使用时下载并校验固定的 SHA-256；改版本时同时更新哈希。
- **不修改用户源码目录**：生成的工程、缓存和 SDK 都放在程序目录的 `.tranforlanguage/` 下。现有例外：程序带 `package.json` 时，JavaScript 适配器在程序目录创建 `node_modules/`（Node 的模块解析需要），重新安装依赖前会先删除它；不要再新增这类例外。
- 跨平台行为保持一致：同一事件、动作和 `@server` 方法在 Spigot 与三个模组加载器上参数顺序和含义相同；做不到时在[兼容说明](docs/compatibility.md)中写明。
- 注释说明“为什么”，而不是复述代码；文档使用中文。

## 提交与 Pull Request

1. 从 `main` 新建分支，一个 PR 只做一件事。
2. 提交前运行 `mvn verify`；改动模组代码时至少构建一个 Fabric 目标并运行 `check-artifact.py`。
3. 行为变化必须同步更新文档（[`docs/`](docs/) 下对应页面）和测试。
4. 提交信息用一行英文祈使句概括改动，正文说明原因和影响。
5. PR 描述写清改动内容、测试方式，以及未能验证的部分（例如没有在真实服务器上跑过）。
6. 等 CI 全部通过（包括真实服务端冒烟测试）且审查意见处理完后再合并。

### 发布

版本号在 `pom.xml` 中定义，模组构建也读取它。CI 只在推送到 `main` 的提交信息包含 `[release-v<版本>]` 标记时创建标签并发布；标记必须出现在最终推送到 `main` 的提交信息中（合并提交的标题），合并前请确认 GitHub 显示的提交信息。发布前请同步更新 `.github/workflows/build.yml` 中的版本标记、README 与文档中的版本号。

## 许可证

提交贡献即表示你同意以 [Apache License 2.0](LICENSE) 授权你的贡献。
