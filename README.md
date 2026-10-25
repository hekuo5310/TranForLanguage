# TranforC++

让 Minecraft 服务端通过进程协议调用程序，默认保留现有 C++ 插件用法，并为其他语言提供统一扩展入口。

## 平台与构建

| 产物 | 目标 | 构建工具 |
| --- | --- | --- |
| `tranforcpp-spigot-1.2.0.jar` | Spigot / 常规 Paper 分支，官方 API 1.8–26.3 | Maven |
| `tranforcpp-fabric-<游戏版本>-1.2.0.jar` | Fabric 1.20–26.3，逐版本构建 | Gradle + Loom |
| `tranforcpp-neoforge-<游戏版本>-1.2.0.jar` | NeoForge 1.21–26.3，逐版本构建 | Gradle + ModDevGradle |
| `tranforcpp-forge-<游戏版本>-1.2.0.jar` | Forge 1.20.x–1.21.x，按官方已发布版本构建 | Gradle + ForgeGradle |

目标范围与运行实测是不同的检查。[兼容说明](docs/compatibility.md)列出版本矩阵、尚未接入的模组事件和验证边界。Spigot 1.8 之前及 Folia 尚未建立兼容保障。

### Spigot

需要 Maven 3.9 和 JDK 17 或以上。输出 Java 8 字节码，服务器仍需使用其 Minecraft 版本要求的 Java。

```bash
mvn clean verify
```

安装 `target/tranforcpp-spigot-1.2.0.jar` 到 `plugins/`。不再为同一插件维护 Maven / Gradle 两份构建。

### Fabric / Forge / NeoForge

先从已锁定版本清单选择精确游戏版本：

```bash
python3 scripts/configure-build.py fabric 1.20
# 按脚本输出安装对应 Gradle。Gradle 8 工具链运行于 JDK 21，Gradle 9 工具链运行于 JDK 25。
gradle -p adapters/fabric clean build

python3 scripts/configure-build.py neoforge 26.3
gradle -p adapters/neoforge clean build

python3 scripts/configure-build.py forge 1.21
gradle -p adapters/forge clean build
```

构建还需要目标游戏的 Java toolchain（17 / 21 / 25），见[版本锁文件](compatibility/versions.json)中的 `java` 与 `gradle` 字段。CI 自动安装这些 JDK 和正确的 Gradle。产物在相应 `adapters/<加载器>/build/libs/`，仅安装与服务器加载器及 Minecraft 版本完全一致的发行 JAR 到 `mods/`。Fabric 额外安装对应游戏版本的 Fabric API。

也可从 GitHub Actions 下载按平台/游戏版本区分的产物；发布由 `v*` 标签触发，所有构建必须通过。

## 安装程序

| 平台 | 程序目录 |
| --- | --- |
| Spigot | `plugins/C++ Plugins/`（保留旧路径） |
| 模组加载器 | `config/tranforcpp/programs/` |

### 现有 C++ 程序

将 `.cpp` 和 [`tranforcpp_api.h`](tranforcpp_api.h) 放在程序目录。需要安装 `g++` / MinGW-w64（C++17、pthread），目录中所有 `.cpp` 编译为一个程序，编译记录写入 `compile.log`。程序应实现 `main()`，按行读取 JSON 并分发事件，收到 `shutdown` 后退出。头文件只提供发送辅助函数，不自动生成事件读取循环。

可使用 JVM 参数 `-Dtranforcpp.compiler=/path/to/clang++` 指定 GCC 参数兼容编译器。

### 其他语言

核心不要求程序必须是 C/C++。参见可直接运行的 [Python 示例](examples/python)及[语言扩展与通信协议](docs/protocol.md)。例如：

```json
{
  "protocol": 1,
  "language": "process",
  "command": ["python3", "-u", "main.py"]
}
```

将它保存为程序目录内的 `program.json`，再放入 `main.py`。也可启动 Node.js、Java 或预编译 Rust/Go 程序，只要它实现相同 JSON Lines 协议。语言适配器可通过 `LanguageProvider` 注册编译流程，共用核心不包含游戏平台依赖。

## 命令

- `/tranforcpp reload`：后台准备并重启程序；失败会明确反馈。
- `/tranforcpp version`：显示桥接版本。
- Spigot 还保留 `/cpp`、`version` 的 `ver` 别名与 `tranforcpp.use/reload/version` 权限节点。
- 模组命令要求操作员级别 2。

程序可请求广播、私信、控制台日志和控制台命令，所有游戏操作通过服务器线程执行。Spigot 保留 MiniMessage 格式，模组端当前使用纯文本。

## 开发

- [协议与语言扩展](docs/protocol.md)
- [平台事件覆盖与兼容验证](docs/compatibility.md)
- [原有 Bukkit 映射指南](mapping.md)

```bash
mvn verify                       # 共用核心实际 C++ / Python 进程集成测试
python3 scripts/test-versions.py  # 版本范围和 Java 边界
```

## 赞助商

- [ZawaZawa](https://www.zawa.uk/) — 视频、专栏与动态社区
- [Nodeloc](https://www.nodeloc.com/invites/QUeeBS9RU3) — 自由、平等、友好、开放、有趣的互联网交流社区
