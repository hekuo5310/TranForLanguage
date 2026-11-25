# 多程序共存与互相调用

## 多语言插件共存

在[程序目录](installation.md#程序目录)下，为每个插件建立独立子目录：

| 插件 ID | 入口示例 | 运行方式 |
| --- | --- | --- |
| `welcome` | `welcome/main.py` | 独立 Python 进程 |
| `native` | `native/main.cpp` | 独立 C++ 进程 |
| `tools` | `tools/main.c` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 C 进程 |
| `rules` | `rules/main.rs` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 Rust 进程 |
| `economy` | `economy/main.go` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 Go 进程 |
| `greeter` | `greeter/main.js` | 独立 Node.js 进程 |
| `@root` | 程序目录根部的 `main.cpp` 等入口 | 根目录程序，可与子目录程序同时运行 |

每个子目录可有自己的 `program.json`、日志、SDK 缓存和依赖。子目录还可以在 `program.json` 中填写 `repository` 绑定 Git 仓库，由 TFL 按间隔拉取并热重载（见[开发文档](development.md#35-从-git-仓库自动更新)）。根目录配置的嵌套 `entrypoint` 所在顶层目录归根程序使用，不会再次启动为子插件。只扫描直接子目录；点开头、`@` 开头及符号链接目录不作为插件加载。空目录不启动进程。一个插件编译失败或退出，不会停止其他插件；退出码写入带插件 ID 的日志，状态变为 `STOPPED`，不自动重启。编译/配置失败标记 `FAILED`，由管理员修复后重新启动。

游戏事件只序列化一次，再投递到各插件独立的有界队列。可在每个插件的 `program.json` 中指定 `"events": ["PlayerJoin", "PlayerChat"]`，只接收需要的事件（全部事件及参数见[开发文档](development.md#5-事件参考)）；省略则保留全部事件，空数组不接收游戏事件（仍接收关闭通知）。Spigot 专属的全量 Bukkit 事件需要显式加入 `"BukkitEvent"`，不会随省略 `events` 自动开启。Spigot 移动事件只报告位置改变，默认每名玩家最多每 100ms 一次；可用 `-Dtranforlanguage.move-interval-ms=0` 关闭限频。

## 程序间互相调用

同时运行的插件可以按**插件 ID**（子目录名，根目录程序为 `@root`）互相调用，不论语言。服务端核心负责路由：调用不经过游戏线程，每次调用的 ID 由核心重写，程序只能回复发给自己的调用。

| 能力 | Python | JavaScript | Go | Rust | C++ |
| --- | --- | --- | --- | --- | --- |
| 暴露方法 | `@handle("m")` | `handle('m', fn)` | `t.Handle("m", fn)` | `handle("m", \|a\| Ok(..))` | `tranforlanguage::handle("m", fn)` |
| 调用并等待结果 | `call("id", "m", *args)` | `await call('id', 'm', ...args)` | `t.Call("id", "m", args...)` | `call("id", "m", &[..])` | `tranforlanguage::call("id", "m", {..})` |
| 单向通知 | `notify(...)` | `notify(...)` | `t.Notify(...)` | `notify(...)` | `tranforlanguage::notify(...)` |
| 发布 / 订阅 | `publish` / `@subscribe` | `publish` / `subscribe` | `t.Publish` / `t.Subscribe` | `publish` / `run` 中的 `message` 事件 | `tranforlanguage::publish` / `run` 中的 `message` 事件 |

Ruby（`TranForLanguage.handle` / `TranForLanguage.call`）、PHP（`TranForLanguage::handle` / `TranForLanguage::call`）、Java（`TranForLanguage.handle` / `TranForLanguage.call`）、Lua（`t.handle` / `t.call`）和 C（`tranforlanguage_handle` / `tranforlanguage_call` / `tranforlanguage_run`）提供同样的功能。例如 Go 程序 `economy` 暴露方法，Python 程序调用它：

```go
t.Handle("balance", func(args []string) (string, error) { return "100", nil })
```

```python
coins = call("economy", "balance", "Alex")   # "100"
```

- 参数和返回值都是字符串；结构化数据请自行用 JSON 编码。方法抛出的异常 / 返回的错误会在调用方变成该语言的错误（`CallError` 等）。
- 目标未运行、方法不存在、目标在回复前停止或超时（默认 30 秒，`-Dtranforlanguage.call-timeout-ms=`）时调用失败，不会卡住。例外：调用方自己的输入队列（每个程序 2000 条）已满时，失败回复可能被丢弃（服务器日志会记录），没有本地超时的 SDK 会一直等待；请及时处理事件，或用 `events` 只订阅需要的事件。
- 等待结果期间收到的调用会被**重入处理**，因此 A 调用 B、B 再回调 A 不会死锁；期间到达的游戏事件会在当前处理器返回后按序处理；JavaScript 例外，它的处理器并发执行，一个处理器在 `await call(...)` 时，后续事件的处理器已经可以运行。Python / Ruby / PHP / Java / Rust / C / C++ 应在事件处理器或主线程中调用；Go 和 JavaScript 可并发调用。
- `publish(topic, ...)` 向除自己以外、接收 `message` 事件的所有插件投递 `message` 事件。若插件 `program.json` 设置了 `events` 过滤，需要把 `"message"` 加入列表；直接调用和回复不受 `events` 过滤。
- 每个程序可从环境变量 `TRANFORLANGUAGE_PROGRAM_ID`（各 SDK 中的 `program_id` / `programId` / `ProgramID`）得知自己的插件 ID。
- C/C++ 程序要使用调用功能，需改用 `tranforlanguage_run` / `tranforlanguage::run` 读取事件，不要再自行读取标准输入。

完整示例见 [examples/interop](../examples/interop)：Go 记账、Python 商店、JavaScript 欢迎程序互相调用。协议细节见[协议文档](protocol.md#程序之间的调用)。
