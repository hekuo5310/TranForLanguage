# 语言指南

每种语言放入什么文件、需要什么工具链。程序放在[程序目录](installation.md#程序目录)下；完整 API 见[开发文档](development.md#8-各语言-sdk-速查)。

需要第三方库时，可选地放入各语言自己的依赖清单：Python `requirements.txt`、Node.js `package.json`、Rust `Cargo.toml`、Go `go.mod`、Ruby `Gemfile`、PHP `composer.json`、Java `lib/*.jar` / `pom.xml`，以及 C/C++ 的 `pkg-config`。没有清单时保持零配置；详见[依赖管理说明](development.md#34-第三方依赖可选)。

## C / C++

只放 `.c` 或 `.cpp` 源文件即可；**无需复制 SDK 头文件，也无需写 `#include "tranforlanguage.hpp"`**。构建器从发行 JAR 提取 SDK 到 `.tranforlanguage/sdk/`，用编译器 `-include` 自动注入；显式写 `#include "tranforlanguage.hpp"`（C++）或 `#include "tranforlanguage.h"`（C）也可以。C 和 C++ 分开编译，不在同一次构建中混编。

```cpp
int main() {
    broadcast("Hello from C++!");
    // 继续读取事件；完整循环见 examples/cpp/main.cpp。
}
```

需要 `gcc`（C11）或 `g++`（C++17），均需 pthread 支持（Windows 可使用 MinGW-w64）。目录中的同语言源文件合并为一个进程。JVM 参数 `-Dtranforlanguage.c.compiler=/path/to/gcc` / `-Dtranforlanguage.cpp.compiler=/path/to/clang++` 可指定 GCC 参数兼容编译器。SDK 提供消息与命令发送函数；C/C++ 程序仍应实现 `main()` 并读取协议事件，见 [C 示例](../examples/c)和 [C++ 示例](../examples/cpp)。

## Python

放入 `main.py`，无需另外安装 Python SDK：

```python
from tranforlanguage import on, run, send_message

@on("PlayerJoin")
def joined(player):
    send_message(player, "Welcome from Python!")

run()
```

需要 Python 3.8+（默认 `python3`），可用 `-Dtranforlanguage.python=/path/to/python` 指定解释器。启动器自动以 UTF-8、无缓冲模式运行，并提供内置模块。见 [Python 示例](../examples/python)。

## Rust

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

需要 Rust 1.85+ 工具链及 Cargo，默认通过 `cargo build --release` 编译；可用 `-Dtranforlanguage.cargo=/path/to/cargo` 指定 Cargo。自动生成的 Cargo 工程和锁文件位于 `.tranforlanguage/rust-project/`，内置 SDK 位于 `.tranforlanguage/sdk/rust/`。首次构建需要从 crates.io 下载 `serde` / `serde_json` 及其依赖，之后由 Cargo 缓存；不会自动安装 Rust。见 [Rust 示例](../examples/rust)。

## JavaScript (Node.js)

放入 `main.js`（CommonJS），`require('tranforlanguage')` 由服务端自动解析到内置 SDK：

```js
const { on, run, sendMessage } = require('tranforlanguage');
on('PlayerJoin', (player) => sendMessage(player, 'Welcome from JavaScript!'));
run();
```

需要 Node.js 16+（默认 `node`，`-Dtranforlanguage.node=/path/to/node`）。SDK 通过 `NODE_PATH` 注入，不写入 `node_modules`；`call()` 返回 Promise。见 [JavaScript 示例](../examples/javascript)。

## Go

放入 `main.go`（`package main`），导入 `tranforlanguage`：

```go
package main

import t "tranforlanguage"

func main() {
	t.On("PlayerJoin", func(args []string) { t.SendMessage(args[0], "Welcome from Go!") })
	t.Run()
}
```

需要 Go 1.18+（默认 `go`，`-Dtranforlanguage.go=/path/to/go`）。目录下全部顶层 `.go` 文件复制到 `.tranforlanguage/go-project/` 中自动生成的模块编译，`tranforlanguage` 通过 `replace` 指向内置 SDK，不需要网络，也不会写用户的 `go.mod`。SDK 只依赖标准库，`Call` 可在任意 goroutine 中使用。见 [Go 示例](../examples/go)。

## Ruby

放入 `main.rb`：

```ruby
require 'tranforlanguage'
TranForLanguage.on('PlayerJoin') { |player| TranForLanguage.send_message(player, 'Welcome from Ruby!') }
TranForLanguage.run
```

需要 Ruby 3.0+（默认 `ruby`，`-Dtranforlanguage.ruby=/path/to/ruby`），SDK 目录通过 `-I` 加入加载路径。见 [Ruby 示例](../examples/ruby)。

## PHP

放入 `main.php`，`TranForLanguage` 类由 `auto_prepend_file` 预加载，无需 `require`：

```php
<?php
TranForLanguage::on('PlayerJoin', fn($player) => TranForLanguage::sendMessage($player, 'Welcome from PHP!'));
TranForLanguage::run();
```

需要 PHP 7.4+ CLI（默认 `php`，`-Dtranforlanguage.php=/path/to/php`）。见 [PHP 示例](../examples/php)。

## Java

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

目录下全部顶层 `.java` 文件与内置 SDK 一起用 `javac` 编译到 `.tranforlanguage/java-classes/`；服务器为 Java 11+ 时改写字节码后在**服务器 JVM 内**运行（见下方[在服务器 JVM 内运行](jvm-runtime.md)），否则以独立 JVM 运行。默认使用服务器 JVM 同目录的 `javac` / `java`（不存在时用 `PATH`），可用 `-Dtranforlanguage.javac=` / `-Dtranforlanguage.java=` 指定；需要 JDK 8+。主类默认 `Main`，可在 `program.json` 用 `"mainClass": "com.example.Main"` 修改。见 [Java 示例](../examples/java)。

## Lua

放入 `main.lua`，`require('tranforlanguage')` 由服务端自动解析到内置 SDK：

```lua
local t = require('tranforlanguage')
t.on('PlayerJoin', function(player) t.send_message(player, 'Welcome from Lua!') end)
t.run()
```

服务器为 Java 11+ 时，程序目录下全部 `.lua` 文件和 SDK 由 LuaJ 的 `luajc` 编译成 JVM 类，在服务器 JVM 内运行（LuaJ 实现 Lua 5.2；`require('lib.util')` 直接加载 `lib/util.lua` 编译后的类）。否则用 Lua 5.1–5.4 解释器运行（默认 `lua`，`-Dtranforlanguage.lua=/path/to/lua`），SDK 通过 `LUA_PATH` 注入。`call()` 失败时抛出错误对象，用 `pcall` 捕获、`t.is_call_error(err)` 判断、`tostring(err)` 取得原因；`t.json_encode` / `t.json_decode` 可编码结构化参数。

## 语言选择和其他语言

仅有一种语言时自动识别 `.cpp`、`.c`、`main.py`、`main.rs`、`main.js`、`main.go`、`main.rb`、`main.php`、`Main.java` 或 `main.lua`。存在多种语言时需用 `program.json` 明确选择，例如：

```json
{"protocol": 1, "language": "python", "entrypoint": "scripts/main.py"}
```

`python` / `rust` / `javascript` / `ruby` / `php` 的 `entrypoint` 必须是程序目录内已有文件，分别默认 `main.py` / `main.rs` / `main.js` / `main.rb` / `main.php`；`c` / `cpp` / `go` / `java` 编译程序目录下全部对应顶层源文件。每个插件子目录运行独立进程，同目录仍只放一个程序入口；Rust 内置适配器面向源码入口，不接管用户已有 Cargo 工程。如需自定义 Cargo 依赖或其他语言，先自行构建后用原有 `process` 适配器启动。参见[协议与语言扩展](protocol.md)。编译输出统一写入程序目录的 `compile.log`，`.tranforlanguage/` 是自动维护的构建缓存，不要存放手写代码。
