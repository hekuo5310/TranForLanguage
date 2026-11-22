# TranForLanguage

让 Minecraft 服务端通过统一进程协议运行 C、C++、Python、Rust、JavaScript、Go、Ruby、PHP、Java 和 Lua 程序。不同语言的插件同时运行，并能[互相调用](#程序间互相调用)。

编写插件请看 **[开发文档](docs/development.md)**：快速开始、`program.json` 参考、全部 41 个事件及参数、`@server` 游戏操作（传送、给物品、改方块等）、第三方依赖管理、各语言 SDK 速查和排错。

**可选的插件依赖**：Python `requirements.txt`、Node.js `package.json`、Rust `Cargo.toml`、Go `go.mod`、Ruby `Gemfile`、PHP `composer.json`、Java `lib/*.jar`/`pom.xml`，以及 C/C++ 的 `pkg-config`。无清单时保持原来的零配置行为；详情见[依赖管理说明](docs/development.md#34-第三方依赖可选)。

## 平台与构建

| 产物 | 目标 | 构建工具 |
| --- | --- | --- |
| `tranforlanguage-spigot-2.3.0.jar` | Spigot / 常规 Paper 分支，官方 API 1.8–26.3 | Maven |
| `tranforlanguage-fabric-<游戏版本>-2.3.0.jar` | Fabric 1.20–26.3，逐版本构建；需要 Fabric Loader 0.17.0+ | Gradle + Loom |
| `tranforlanguage-neoforge-<游戏版本>-2.3.0.jar` | NeoForge 1.21–26.3，逐版本构建 | Gradle + ModDevGradle |
| `tranforlanguage-forge-<游戏版本>-2.3.0.jar` | Forge 1.20.x–1.21.x，按官方已发布版本构建 | Gradle + ForgeGradle |

目标范围与运行实测是不同的检查。[兼容说明](docs/compatibility.md)列出版本矩阵、尚未接入的模组事件和验证边界。Spigot 1.8 之前及 Folia 尚未建立兼容保障。

### Spigot

需要 Maven 3.9 和 JDK 17 或以上。输出 Java 8 字节码，服务器仍需使用其 Minecraft 版本要求的 Java。

```bash
mvn clean verify
```

安装 `target/tranforlanguage-spigot-2.3.0.jar` 到 `plugins/`。不再为同一插件维护 Maven / Gradle 两份构建。

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

也可从 GitHub Actions 下载按平台/游戏版本区分的产物。常规版本通过与项目版本一致的 `v*` 标签发布；本次 2.3.0 由 main 分支上带 `[release-v2.3.0]` 标记的提交在完整构建通过后自动创建标签并发布，不覆盖指向其他提交的标签。标记必须出现在最终推送到 main 的提交信息里，合并 PR 前请检查 GitHub 显示的提交信息；该版本不能通过手动推送标签发布。

## 安装程序

| 平台 | 程序目录 |
| --- | --- |
| Spigot | `plugins/TranForLanguage/programs/` |
| 模组加载器 | `config/tranforlanguage/programs/` |

### C / C++

只放 `.c` 或 `.cpp` 源文件即可；**无需复制 SDK 头文件，也无需写 `#include "tranforlanguage.hpp"`**。构建器从发行 JAR 提取 SDK 到 `.tranforlanguage/sdk/`，用编译器 `-include` 自动注入；显式写 `#include "tranforlanguage.hpp"`（C++）或 `#include "tranforlanguage.h"`（C）也可以。C 和 C++ 分开编译，不在同一次构建中混编。

```cpp
int main() {
    broadcast("Hello from C++!");
    // 继续读取事件；完整循环见 examples/cpp/main.cpp。
}
```

需要 `gcc`（C11）或 `g++`（C++17），均需 pthread 支持（Windows 可使用 MinGW-w64）。目录中的同语言源文件合并为一个进程。JVM 参数 `-Dtranforlanguage.c.compiler=/path/to/gcc` / `-Dtranforlanguage.cpp.compiler=/path/to/clang++` 可指定 GCC 参数兼容编译器。SDK 提供消息与命令发送函数；C/C++ 程序仍应实现 `main()` 并读取协议事件，见 [C 示例](examples/c)和 [C++ 示例](examples/cpp)。

### Python

放入 `main.py`，无需另外安装 Python SDK：

```python
from tranforlanguage import on, run, send_message

@on("PlayerJoin")
def joined(player):
    send_message(player, "Welcome from Python!")

run()
```

需要 Python 3.8+（默认 `python3`），可用 `-Dtranforlanguage.python=/path/to/python` 指定解释器。启动器自动以 UTF-8、无缓冲模式运行，并提供内置模块。见 [Python 示例](examples/python)。

### Rust

放入 `main.rs`，无需复制 SDK 或手写 Cargo 清单：

```rust
use tranforlanguage::{run, broadcast};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    run(|event| {
        if event.event == "PlayerJoin" {
            broadcast("Welcome from Rust!").unwrap();
        }
    })
}
```

需要 Rust 1.85+ 工具链及 Cargo，默认通过 `cargo build --release` 编译；可用 `-Dtranforlanguage.cargo=/path/to/cargo` 指定 Cargo。自动生成的 Cargo 工程和锁文件位于 `.tranforlanguage/rust-project/`，内置 SDK 位于 `.tranforlanguage/sdk/rust/`。首次构建需要从 crates.io 下载 `serde` / `serde_json` 及其依赖，之后由 Cargo 缓存；不会自动安装 Rust。见 [Rust 示例](examples/rust)。

### JavaScript (Node.js)

放入 `main.js`（CommonJS），`require('tranforlanguage')` 由服务端自动解析到内置 SDK：

```js
const { on, run, sendMessage } = require('tranforlanguage');
on('PlayerJoin', (player) => sendMessage(player, 'Welcome from JavaScript!'));
run();
```

需要 Node.js 16+（默认 `node`，`-Dtranforlanguage.node=/path/to/node`）。SDK 通过 `NODE_PATH` 注入，不写入 `node_modules`；`call()` 返回 Promise。见 [JavaScript 示例](examples/javascript)。

### Go

放入 `main.go`（`package main`），导入 `tranforlanguage`：

```go
package main

import t "tranforlanguage"

func main() {
	t.On("PlayerJoin", func(args []string) { t.SendMessage(args[0], "Welcome from Go!") })
	t.Run()
}
```

需要 Go 1.18+（默认 `go`，`-Dtranforlanguage.go=/path/to/go`）。目录下全部顶层 `.go` 文件复制到 `.tranforlanguage/go-project/` 中自动生成的模块编译，`tranforlanguage` 通过 `replace` 指向内置 SDK，不需要网络，也不会写用户的 `go.mod`。SDK 只依赖标准库，`Call` 可在任意 goroutine 中使用。见 [Go 示例](examples/go)。

### Ruby

放入 `main.rb`：

```ruby
require 'tranforlanguage'
TranForLanguage.on('PlayerJoin') { |player| TranForLanguage.send_message(player, 'Welcome from Ruby!') }
TranForLanguage.run
```

需要 Ruby 3.0+（默认 `ruby`，`-Dtranforlanguage.ruby=/path/to/ruby`），SDK 目录通过 `-I` 加入加载路径。见 [Ruby 示例](examples/ruby)。

### PHP

放入 `main.php`，`TranForLanguage` 类由 `auto_prepend_file` 预加载，无需 `require`：

```php
<?php
TranForLanguage::on('PlayerJoin', fn($player) => TranForLanguage::sendMessage($player, 'Welcome from PHP!'));
TranForLanguage::run();
```

需要 PHP 7.4+ CLI（默认 `php`，`-Dtranforlanguage.php=/path/to/php`）。见 [PHP 示例](examples/php)。

### Java

放入 `Main.java`，导入 `tranforlanguage.TranForLanguage`：

```java
import tranforlanguage.TranForLanguage;
public class Main {
    public static void main(String[] args) {
        TranForLanguage.on("PlayerJoin", e -> TranForLanguage.sendMessage(e[0], "Welcome from Java!"));
        TranForLanguage.run();
    }
}
```

目录下全部顶层 `.java` 文件与内置 SDK 一起用 `javac` 编译到 `.tranforlanguage/java-classes/`；服务器为 Java 11+ 时改写字节码后在**服务器 JVM 内**运行（见下方[在服务器 JVM 内运行](#在服务器-jvm-内运行预先翻译的字节码)），否则以独立 JVM 运行。默认使用服务器 JVM 同目录的 `javac` / `java`（不存在时用 `PATH`），可用 `-Dtranforlanguage.javac=` / `-Dtranforlanguage.java=` 指定；需要 JDK 8+。主类默认 `Main`，可在 `program.json` 用 `"mainClass": "com.example.Main"` 修改。见 [Java 示例](examples/java)。

### Lua

放入 `main.lua`，`require('tranforlanguage')` 由服务端自动解析到内置 SDK：

```lua
local t = require('tranforlanguage')
t.on('PlayerJoin', function(player) t.send_message(player, 'Welcome from Lua!') end)
t.run()
```

服务器为 Java 11+ 时，程序目录下全部 `.lua` 文件和 SDK 由 LuaJ 的 `luajc` 编译成 JVM 类，在服务器 JVM 内运行（LuaJ 实现 Lua 5.2；`require('lib.util')` 直接加载 `lib/util.lua` 编译后的类）。否则用 Lua 5.1–5.4 解释器运行（默认 `lua`，`-Dtranforlanguage.lua=/path/to/lua`），SDK 通过 `LUA_PATH` 注入。`call()` 失败时抛出错误对象，用 `pcall` 捕获、`t.is_call_error(err)` 判断、`tostring(err)` 取得原因；`t.json_encode` / `t.json_decode` 可编码结构化参数。

### 语言选择和其他语言

仅有一种语言时自动识别 `.cpp`、`.c`、`main.py`、`main.rs`、`main.js`、`main.go`、`main.rb`、`main.php`、`Main.java` 或 `main.lua`。存在多种语言时需用 `program.json` 明确选择，例如：

```json
{"protocol": 1, "language": "python", "entrypoint": "scripts/main.py"}
```

`python` / `rust` / `javascript` / `ruby` / `php` 的 `entrypoint` 必须是程序目录内已有文件，分别默认 `main.py` / `main.rs` / `main.js` / `main.rb` / `main.php`；`c` / `cpp` / `go` / `java` 编译程序目录下全部对应顶层源文件。每个插件子目录运行独立进程，同目录仍只放一个程序入口；Rust 内置适配器面向源码入口，不接管用户已有 Cargo 工程。如需自定义 Cargo 依赖或其他语言，先自行构建后用原有 `process` 适配器启动。参见[协议与语言扩展](docs/protocol.md)。编译输出统一写入程序目录的 `compile.log`，`.tranforlanguage/` 是自动维护的构建缓存，不要存放手写代码。

### 在服务器 JVM 内运行（预先翻译的字节码）

2.3.0 起，C、Rust、Go、Java、Ruby 和 Lua 程序在**准备阶段一次性翻译成 JVM class 字节码**，启动时只加载已保存的类，在服务器 JVM 内运行，不再另起进程，也不会在每次启动时重新翻译。源码不变时，重载直接复用翻译结果。

| 语言 | 翻译方式 | 要求 |
| --- | --- | --- |
| C | `clang --target=wasm32-wasi` 编译为 WebAssembly，再由 [Chicory](https://github.com/dylibso/chicory) 翻译为字节码 | 服务器 Java 11+；clang、lld、WASI sysroot（Debian/Ubuntu：`apt install clang lld wasi-libc libclang-rt-18-dev-wasm32`）。使用 `pkgConfig` 的程序保持独立进程 |
| Rust | `cargo build --target wasm32-wasip1`，再由 Chicory 翻译 | Java 11+；`rustup target add wasm32-wasip1`。依赖原生代码的 crate 会自动回退为进程 |
| Go | `GOOS=wasip1 GOARCH=wasm go build`，再由 Chicory 翻译 | Java 11+；Go 1.21+ |
| Java | `javac` 编译后用 ASM 改写字节码：`System.in/out/err`、`System.exit`、`System.getenv`、`Runtime.exit/halt` 改为指向该程序自己的流、环境变量和退出码 | Java 11+ |
| Ruby | JRuby 的 `jrubyc` 编译为 class，`require` / `require_relative` 加载编译后的类 | Java 21+；没有 `Gemfile`；不使用 `__FILE__` / `__dir__` / `$0` |
| Lua | LuaJ 的 `luajc` 编译为 class（Lua 5.2），`require` 加载编译后的类；`io.open` 等相对路径以程序目录为准 | Java 11+ |

C++、JavaScript、Python 和 PHP 仍以独立进程运行：WASI 的 C++ 标准库没有异常支持（SDK 依赖异常），Rhino 不支持 `class` / `async`，Python 3 与 PHP 没有维护中的字节码编译器。

- 翻译器（Chicory、ASM、JRuby、LuaJ）**不打包进插件/模组 JAR**。某种语言第一次需要时，从 Maven Central 下载到程序目录下的 `.runtimes/`，逐个校验固定的 SHA-256，之后离线复用。可用 `-Dtranforlanguage.runtime.repositories=https://镜像1/,https://镜像2/` 指定镜像，`-Dtranforlanguage.runtimes=/路径` 指定存放位置。
- `program.json` 的 `"runtime"` 选择运行方式：`"auto"`（默认，能翻译就在 JVM 内运行，缺少工具链、Java 版本过低或不支持时自动回退为独立进程，原因写入日志）、`"jvm"`（必须在 JVM 内运行，否则报错）、`"process"`（始终独立进程）。JVM 参数 `-Dtranforlanguage.runtime=process` 可为全部程序设置默认值。
- 程序与服务器共享内存和 CPU，**没有进程隔离**：死循环或耗尽内存会影响服务器。停止程序时会中断它的线程并关闭它的输入输出；忽略中断的线程无法被强制结束。需要隔离的程序请设置 `"runtime": "process"`。

## 多语言插件共存

在上面的程序目录下，为每个插件建立独立子目录：

| 插件 ID | 入口示例 | 运行方式 |
| --- | --- | --- |
| `welcome` | `welcome/main.py` | 独立 Python 进程 |
| `native` | `native/main.cpp` | 独立 C++ 进程 |
| `tools` | `tools/main.c` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 C 进程 |
| `rules` | `rules/main.rs` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 Rust 进程 |
| `economy` | `economy/main.go` | 服务器 JVM 内（WebAssembly → 字节码），否则独立 Go 进程 |
| `greeter` | `greeter/main.js` | 独立 Node.js 进程 |
| `@root` | 程序目录根部的 `main.cpp` 等入口 | 根目录程序，可与子目录程序同时运行 |

每个子目录可有自己的 `program.json`、日志、SDK 缓存和依赖。根目录配置的嵌套 `entrypoint` 所在顶层目录归根程序使用，不会再次启动为子插件。只扫描直接子目录；点开头、`@` 开头及符号链接目录不作为插件加载。空目录不启动进程。一个插件编译失败或退出，不会停止其他插件；退出码写入带插件 ID 的日志，状态变为 `STOPPED`，不自动重启。编译/配置失败标记 `FAILED`，由管理员修复后重新启动。

游戏事件只序列化一次，再投递到各插件独立的有界队列。可在每个插件的 `program.json` 中指定 `"events": ["PlayerJoin", "PlayerChat"]`，只接收需要的事件（全部事件及参数见[开发文档](docs/development.md#5-事件参考)）；省略则保留全部事件，空数组不接收游戏事件（仍接收关闭通知）。Spigot 移动事件只报告位置改变，默认每名玩家最多每 100ms 一次；可用 `-Dtranforlanguage.move-interval-ms=0` 关闭限频。

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
- 目标未运行、方法不存在、目标在回复前停止或超时（默认 30 秒，`-Dtranforlanguage.call-timeout-ms=`）时调用立即失败，不会卡住。
- 等待结果期间收到的调用会被**重入处理**，因此 A 调用 B、B 再回调 A 不会死锁；期间到达的游戏事件会在当前处理器返回后按序处理。Python / Ruby / PHP / Java / Rust / C / C++ 应在事件处理器或主线程中调用；Go 和 JavaScript 可并发调用。
- `publish(topic, ...)` 向除自己以外、接收 `message` 事件的所有插件投递 `message` 事件。若插件 `program.json` 设置了 `events` 过滤，需要把 `"message"` 加入列表；直接调用和回复不受 `events` 过滤。
- 每个程序可从环境变量 `TRANFORLANGUAGE_PROGRAM_ID`（各 SDK 中的 `program_id` / `programId` / `ProgramID`）得知自己的插件 ID。
- C/C++ 程序要使用调用功能，需改用 `tranforlanguage_run` / `tranforlanguage::run` 读取事件，不要再自行读取标准输入。

完整示例见 [examples/interop](examples/interop)：Go 记账、Python 商店、JavaScript 欢迎程序互相调用。协议细节见[协议文档](docs/protocol.md#程序之间的调用)。

## 命令

- `/tranforlanguage list`：查看每个程序的 ID 和状态。
- `/tranforlanguage reload`：重新扫描目录，移除已删除插件，并依次准备所有插件；健康插件不因其他插件失败而停止。
- `/tranforlanguage reload <id>` / `start <id>`：独立准备并启动指定插件，新子目录无需重启其他插件。若指定插件已在运行，会先停止旧进程再准备并启动新进程；期间不向该插件投递事件，进程内未保存的状态会丢失。
- `/tranforlanguage stop <id>`：只停止指定插件。
- `/tranforlanguage version`：显示桥接版本。
- 只有 `/tranforlanguage` 一个主命令（没有别名）。Spigot 权限：`list` 需要 `tranforlanguage.use`，启动/停止/重载需要 `tranforlanguage.reload`，`version` / `ver` 需要 `tranforlanguage.version`。
- 模组端命令要求操作员级别 2；ID 占据命令剩余部分，中文、空格及 `@root` 可直接输入，无需加引号。`@root` 指代根目录程序。

## 程序权限与运行边界

**只安装你信任的程序。这里没有进程沙箱或低权限执行环境。** 源码、`program.json`、编译配置和 `.tranforlanguage/` 缓存都应由服务端管理员控制。程序以服务器进程的宿主权限运行，可读写其能访问的文件并自行发起网络请求；`executeCommand` / `dispatchCommand` 按现有协议拥有游戏控制台权限。命令接口的操作员权限只保护管理命令，不约束被安装程序。Rust 首次构建还会执行 Cargo 依赖的构建步骤；请审核依赖并保留锁文件。在服务器 JVM 内运行的程序与服务器共享内存和 CPU；其中 Java 和 Ruby 程序可通过反射等方式访问 JDK 的全部能力，WebAssembly 程序只能访问自己的程序目录。

程序可请求广播、私信、控制台日志和控制台命令，所有游戏操作通过服务器线程执行。Spigot 保留 MiniMessage 格式，模组端当前使用纯文本。

## 从 2.1 及更早版本升级（不兼容变更）

2.2.0 起所有名称统一为 **TranForLanguage**，旧名称全部移除，不再提供兼容别名：

| 项目 | 旧名称（已移除） | 新名称 |
| --- | --- | --- |
| Spigot 程序目录 | `plugins/C++ Plugins/` | `plugins/TranForLanguage/programs/` |
| 模组程序目录 | `config/tranforcpp/programs/` | `config/tranforlanguage/programs/` |
| 命令 | `/tranforcpp`、`/cpp` | `/tranforlanguage` |
| Spigot 权限 | `tranforcpp.use` / `.reload` / `.version` | `tranforlanguage.use` / `.reload` / `.version` |
| 模组 ID | `tranforcpp` | `tranforlanguage` |
| Java 包 / Spigot 主类 | `com.github.tranforcpp.TranforCPlusPlus` | `com.github.tranforlanguage.TranForLanguage` |
| Bukkit 事件 | `ProcessManager.GenericTranforCEvent` | `ProcessManager.TranForLanguageEvent` |
| JVM 参数 | `-Dtranforcpp.*`、`-Dtranforcpp.compiler` | `-Dtranforlanguage.*`、`-Dtranforlanguage.cpp.compiler` |
| 程序环境变量 | `TRANFORCPP_PROGRAM_ID` | `TRANFORLANGUAGE_PROGRAM_ID` |
| 构建缓存 | `.tranforcpp/` | `.tranforlanguage/`（旧目录可删除） |
| C / C++ SDK | `tranforcpp.h` / `tranforcpp_api.h`，`tranforcpp_*` 函数，`tranforcpp::` 命名空间 | `tranforlanguage.h` / `tranforlanguage.hpp`，`tranforlanguage_*`，`tranforlanguage::` |
| Python / Rust / Go / JavaScript | 模块 `tranforcpp` | 模块 `tranforlanguage` |
| Ruby / PHP / Java | `Tranfor` 类（Java 包 `tranforcpp`） | `TranForLanguage` 类（Java 包 `tranforlanguage`） |

升级步骤：

1. 停服，替换 JAR（不要同时安装新旧两份）。
2. 把旧程序目录移动到新位置，例如 `mv "plugins/C++ Plugins" plugins/TranForLanguage/programs`，或 `mv config/tranforcpp config/tranforlanguage`。
3. 在程序源码中把 `tranforcpp` 导入 / `Tranfor` 类 / `tranforcpp_*` 函数改为新名称，删除各程序目录下的 `.tranforcpp/`。
4. 权限插件中的 `tranforcpp.*` 节点改为 `tranforlanguage.*`；JVM 启动参数改为 `-Dtranforlanguage.*`。
5. 其他 Bukkit 插件若依赖旧插件名 `TranforCPlusPlus` 或旧事件类，改为 `TranForLanguage` 和新类名。

## 开发

- [开发文档](docs/development.md)：插件开发与参与本项目
- [协议与语言扩展](docs/protocol.md)
- [平台事件覆盖与兼容验证](docs/compatibility.md)
- [原有 Bukkit 映射指南](mapping.md)

```bash
mvn verify                       # 需要 gcc / g++ / Python / Rust + Cargo / Node.js / Go / Ruby / PHP / JDK；运行九语言真实进程与互相调用测试
python3 scripts/test-versions.py  # 版本范围和 Java 边界
```

## 赞助商

- [ZawaZawa](https://www.zawa.uk/) — 视频、专栏与动态社区
- [Nodeloc](https://www.nodeloc.com/invites/QUeeBS9RU3) — 自由、平等、友好、开放、有趣的互联网交流社区
