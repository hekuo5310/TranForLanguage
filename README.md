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

### C / C++

只放 `.c` 或 `.cpp` 源文件即可；**无需复制 SDK 头文件，也无需写 `#include "tranforcpp_api.h"`**。构建器从发行 JAR 提取 SDK 到 `.tranforcpp/sdk/`，用编译器 `-include` 自动注入。旧 C++ 程序的 `#include "tranforcpp_api.h"` 仍可用。C 和 C++ 分开编译，不在同一次构建中混编。

```cpp
int main() {
    broadcast("Hello from C++!");
    // 继续读取事件；完整循环见 examples/cpp/main.cpp。
}
```

需要 `gcc`（C11）或 `g++`（C++17），均需 pthread 支持（Windows 可使用 MinGW-w64）。目录中的同语言源文件合并为一个进程。JVM 参数 `-Dtranforcpp.c.compiler=/path/to/gcc` / `-Dtranforcpp.compiler=/path/to/clang++` 可指定 GCC 参数兼容编译器。SDK 提供消息与命令发送函数；C/C++ 程序仍应实现 `main()` 并读取协议事件，见 [C 示例](examples/c)和 [C++ 示例](examples/cpp)。

### Python

放入 `main.py`，无需另外安装 Python SDK：

```python
from tranforcpp import on, run, send_message

@on("PlayerJoin")
def joined(player):
    send_message(player, "Welcome from Python!")

run()
```

需要 Python 3.8+（默认 `python3`），可用 `-Dtranforcpp.python=/path/to/python` 指定解释器。启动器自动以 UTF-8、无缓冲模式运行，并提供内置模块。见 [Python 示例](examples/python)。

### Rust

放入 `main.rs`，无需复制 SDK 或手写 Cargo 清单：

```rust
use tranforcpp::{run, broadcast};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    run(|event| {
        if event.event == "PlayerJoin" {
            broadcast("Welcome from Rust!").unwrap();
        }
    })
}
```

需要 Rust 1.85+ 工具链及 Cargo，默认通过 `cargo build --release` 编译；可用 `-Dtranforcpp.cargo=/path/to/cargo` 指定 Cargo。自动生成的 Cargo 工程和锁文件位于 `.tranforcpp/rust-project/`，内置 SDK 位于 `.tranforcpp/sdk/rust/`。首次构建需要从 crates.io 下载 `serde` / `serde_json` 及其依赖，之后由 Cargo 缓存；不会自动安装 Rust。见 [Rust 示例](examples/rust)。

### 语言选择和其他语言

仅有一种语言时自动识别 `.cpp`、`.c`、`main.py` 或 `main.rs`。存在多种语言时需用 `program.json` 明确选择，例如：

```json
{"protocol": 1, "language": "python", "entrypoint": "scripts/main.py"}
```

`python` / `rust` 的 `entrypoint` 必须是程序目录内已有文件，分别默认 `main.py` / `main.rs`；`c` / `cpp` 编译程序目录下全部对应源文件。一个目录仍只运行一个程序进程；Rust 内置适配器面向源码入口，不接管用户已有 Cargo 工程。如需自定义 Cargo 依赖或其他语言，先自行构建后用原有 `process` 适配器启动。参见[协议与语言扩展](docs/protocol.md)。编译输出统一写入程序目录的 `compile.log`，`.tranforcpp/` 是自动维护的构建缓存，不要存放手写代码。

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
mvn verify                       # 需要 gcc / g++ / Python / Rust + Cargo；运行四语言真实进程测试
python3 scripts/test-versions.py  # 版本范围和 Java 边界
```

## 赞助商

- [ZawaZawa](https://www.zawa.uk/) — 视频、专栏与动态社区
- [Nodeloc](https://www.nodeloc.com/invites/QUeeBS9RU3) — 自由、平等、友好、开放、有趣的互联网交流社区
