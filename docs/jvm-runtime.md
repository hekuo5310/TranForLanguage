# 在服务器 JVM 内运行（预先翻译的字节码）

2.3.0 起，C、Rust、Go、Java、Ruby 和 Lua 程序在**准备阶段一次性翻译成 JVM class 字节码**，启动时只加载已保存的类，在服务器 JVM 内运行，不再另起进程。C、Rust、Go、Ruby、Lua 在源码不变时，重载直接复用已保存的翻译结果；Java 每次准备都会重新 `javac` 编译并改写字节码（`lib/` 中不变的 JAR 会复用改写结果）。

| 语言 | 翻译方式 | 要求 |
| --- | --- | --- |
| C | `clang --target=wasm32-wasi` 编译为 WebAssembly，再由 [Chicory](https://github.com/dylibso/chicory) 翻译为字节码 | 服务器 Java 11+；clang、lld、WASI sysroot（Debian/Ubuntu：`apt install clang lld wasi-libc libclang-rt-<clang 主版本>-dev-wasm32`）。使用 `pkgConfig` 的程序保持独立进程 |
| Rust | `cargo build --target wasm32-wasip1`，再由 Chicory 翻译 | Java 11+；`rustup target add wasm32-wasip1`。依赖原生代码的 crate 会自动回退为进程 |
| Go | `GOOS=wasip1 GOARCH=wasm go build`，再由 Chicory 翻译 | Java 11+；Go 1.21+ |
| Java | `javac` 编译后用 ASM 改写字节码：`System.in/out/err`、`System.exit`、`System.getenv`、`Runtime.exit/halt` 改为指向该程序自己的流、环境变量和退出码 | Java 11+ |
| Ruby | JRuby 的 `jrubyc` 编译为 class，`require` / `require_relative` 加载编译后的类 | Java 21+；没有 `Gemfile`；不使用 `__FILE__` / `__dir__` / `$0` |
| Lua | LuaJ 的 `luajc` 编译为 class（Lua 5.2），`require` 加载编译后的类；`io.open` 等相对路径以程序目录为准 | Java 11+ |

C++、JavaScript、Python 和 PHP 仍以独立进程运行：WASI 的 C++ 标准库没有异常支持（SDK 依赖异常），Rhino 不支持 `class` / `async`，Python 3 与 PHP 没有维护中的字节码编译器。

- 翻译器（Chicory、ASM、JRuby、LuaJ）**不打包进插件/模组 JAR**。某种语言第一次需要时，从 Maven Central 下载到程序目录下的 `.runtimes/`，逐个校验固定的 SHA-256，之后离线复用。可用 `-Dtranforlanguage.runtime.repositories=https://镜像1/,https://镜像2/` 指定镜像，`-Dtranforlanguage.runtimes=/路径` 指定存放位置。
- `program.json` 的 `"runtime"` 选择运行方式：`"auto"`（默认，能翻译就在 JVM 内运行，缺少工具链、Java 版本过低或不支持时自动回退为独立进程，原因写入日志）、`"jvm"`（必须在 JVM 内运行，否则报错：包括 C++ / JavaScript / Python / PHP、带 `Gemfile` 的 Ruby、使用 `pkgConfig` 的 C 等只能以进程运行的情况）、`"process"`（始终独立进程）。JVM 参数 `-Dtranforlanguage.runtime=process` 可为全部程序设置默认值。
- 程序与服务器共享内存和 CPU，**没有进程隔离**：死循环或耗尽内存会影响服务器。停止程序时会中断它的线程并关闭它的输入输出；忽略中断的线程无法被强制结束。需要隔离的程序请设置 `"runtime": "process"`。
