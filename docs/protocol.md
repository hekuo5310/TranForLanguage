# 程序桥接协议 v1

核心不依赖 C、C++、Bukkit 或 Minecraft。`Platform` 负责服务端操作，`ProgramRuntime` 负责进程、队列、重载和 UTF-8 JSON Lines，`LanguageProvider` 负责语言工具链。

## 目录与启动配置

- Spigot 默认继续使用 `plugins/C++ Plugins/`，兼容现有插件。
- Fabric / Forge / NeoForge 使用 `config/tranforcpp/programs/`。
- 没有 `program.json` 时使用 `cpp` 适配器，编译目录下所有 `.cpp` 为一个进程，保留原有行为。
- 一个目录对应一个程序进程。多个 `.cpp` 仍属于一个程序，不会变成相互隔离的插件；目前不支持同一目录同时运行多个语言进程。

Python 配置示例（同样适用于 Node.js、Java、预编译 Rust/Go 等）：

```json
{
  "protocol": 1,
  "language": "process",
  "command": ["python3", "-X", "utf8", "-u", "main.py"]
}
```

`command` 必须是字符串数组，直接传给 `ProcessBuilder`，不拼接 shell 命令。可执行文件需要已安装或提供绝对路径。程序必须实现下述协议；普通命令行程序不会自动成为 Minecraft 插件。标准输出专用于协议，诊断写到标准错误，每条消息后刷新输出。

C++ 适配器使用 `g++ -std=c++17 -pthread`。可通过 JVM 参数 `-Dtranforcpp.compiler=/path/to/clang++` 切换兼容编译器；不再错误地声称 GCC 参数可用于 MSVC。编译日志在程序目录 `compile.log`。

## 服务端 → 程序

每行一个对象，事件参数始终为字符串：

```json
{"event":"PlayerJoin","args":["Alex"]}
{"event":"PlayerChat","args":["Alex","你好"]}
{"event":"shutdown","args":[]}
```

每个进程最多排队 2000 条事件；队列满时丢弃新事件，告警最多每 5 秒汇总一次，关闭时补充尚未输出的丢弃计数。

接到 `shutdown` 应停止读取并退出。服务端等待 1 秒后终止进程，再等待 1 秒后强制终止。重载会先关闭旧进程，再准备和启动新程序；新进程启动失败时不会继续使用旧程序。启动和编译在后台执行，等待启动期间的游戏事件不会缓存或回放。

## 程序 → 服务端

```json
{"action":"broadcast","message":"hello"}
{"action":"sendMessage","player":"Alex","message":"hello"}
{"action":"console","message":"log line"}
{"action":"executeCommand","command":"say hello"}
```

`dispatchCommand` 仍作为 `executeCommand` 的兼容别名接受。游戏操作统一进入服务器线程；旧 `sync` 字段被兼容接受，但不允许程序要求在 IO 线程执行 Minecraft 命令。重载前积压的旧进程操作会被丢弃。

Spigot 使用 MiniMessage 转 Bukkit 传统颜色字符串；模组端当前将消息作为纯文本发送，不能宣称两端富文本格式完全一致。

## 添加语言适配器

实现 `com.github.tranforcpp.core.language.LanguageProvider`：

- `id()`：唯一语言标识。
- `prepare(directory, configuration, logger)`：进行需要的编译或准备，返回不可变的 `LaunchCommand`（工作目录与 argv）；没有程序时返回 `null`。
- 不在此处访问游戏 API，也不实现重复的事件队列或进程协议。

可以在启动前调用 `runtime.languages().register(provider)`，或通过同一加载器可见的 Java SPI 文件 `META-INF/services/com.github.tranforcpp.core.language.LanguageProvider` 打包适配器。服务加载器使用核心类加载器；外部放置 JAR 不会自动安装，当前没有动态扩展目录扫描功能。未知语言和协议版本会明确报错。
