# TranForLanguage 开发文档

本文面向两类读者：

- **程序开发者**：用 C、C++、Python、Rust、JavaScript、Go、Ruby、PHP 或 Java 编写在 Minecraft 服务端运行的程序（下文称“插件”）。见第 1–9 节。
- **项目贡献者**：修改 TranForLanguage 本身（平台适配、语言适配器、事件）。见第 10 节。

协议的逐字段定义见 [协议文档](protocol.md)，平台差异与验证范围见 [兼容说明](compatibility.md)。

## 1. 工作原理

```
Minecraft 服务端（Spigot / Fabric / Forge / NeoForge）
        │  游戏事件                         ▲ 游戏操作（在服务器线程执行）
        ▼                                   │
  平台适配层 ──► ProgramManager ──► 每个插件一个独立进程
                    │   ▲            stdin：事件（JSON Lines）
                    │   │            stdout：动作（JSON Lines）
                    ▼   │            stderr：日志
                 ProgramBus（插件之间的调用与消息）
```

- 每个插件是一个**独立的操作系统进程**，语言不限。插件崩溃或编译失败不会影响其他插件。
- 服务端通过标准输入发送事件，每行一个 JSON；插件通过标准输出发送动作，每行一个 JSON。各语言 SDK 已封装好这些细节。
- 所有游戏操作（广播、私信、执行命令）都排队到服务器线程执行，插件不会直接接触游戏对象。
- 插件之间的调用由核心的 `ProgramBus` 在 IO 线程路由，不经过游戏线程。

## 2. 快速开始

1. 找到程序目录：

   | 平台 | 程序目录 |
   | --- | --- |
   | Spigot | `plugins/TranForLanguage/programs/` |
   | Fabric / Forge / NeoForge | `config/tranforlanguage/programs/` |

2. 为插件新建子目录，目录名就是**插件 ID**，例如 `programs/hello/`。
3. 放入入口文件，例如 `programs/hello/main.py`：

   ```python
   from tranforlanguage import on, run, send_message

   @on("PlayerJoin")
   def joined(player):
       send_message(player, "Hello, " + player)

   run()
   ```

4. 在游戏内或控制台执行 `/tranforlanguage reload hello`（无需重启服务器或其他插件）。
5. 执行 `/tranforlanguage list` 确认状态为 `RUNNING`。

SDK 由服务端自动提供，不需要复制任何 SDK 文件，也不需要手写构建配置。

## 3. 程序目录与 `program.json`

### 3.1 目录规则

- 程序目录下的每个**直接子目录**是一个插件；程序目录根部本身也可放一个程序，ID 为 `@root`。
- 以 `.` 或 `@` 开头的目录、符号链接目录、空目录不会被加载。
- 每个插件目录只放一个程序入口。需要多语言时，分成多个插件目录，再通过[程序间调用](#7-程序间调用)协作。
- `compile.log`：编译输出。`.tranforlanguage/`：自动维护的 SDK 与构建缓存，不要放手写代码，可随时删除。

### 3.2 语言识别

没有 `program.json` 或其中没有 `language` 时，按入口文件自动识别；同一目录出现多种语言会报错，此时必须在 `program.json` 中指定。

| 语言 | `language` | 自动识别的文件 | 入口 / 编译范围 | 工具链（默认命令 / JVM 参数） |
| --- | --- | --- | --- | --- |
| C | `c` | 任意 `.c` | 目录下全部顶层 `.c` | `gcc`（C11），`-Dtranforlanguage.c.compiler=` |
| C++ | `cpp` | 任意 `.cpp` | 目录下全部顶层 `.cpp` | `g++`（C++17），`-Dtranforlanguage.cpp.compiler=` |
| Python | `python` | `main.py` | `entrypoint`，默认 `main.py` | `python3`（3.8+），`-Dtranforlanguage.python=` |
| Rust | `rust` | `main.rs` | `entrypoint`，默认 `main.rs` | `cargo`（1.85+），`-Dtranforlanguage.cargo=` |
| JavaScript | `javascript` | `main.js` | `entrypoint`，默认 `main.js`（CommonJS） | `node`（16+），`-Dtranforlanguage.node=` |
| Go | `go` | `main.go` | 目录下全部顶层 `.go` | `go`（1.18+），`-Dtranforlanguage.go=` |
| Ruby | `ruby` | `main.rb` | `entrypoint`，默认 `main.rb` | `ruby`（3.0+），`-Dtranforlanguage.ruby=` |
| PHP | `php` | `main.php` | `entrypoint`，默认 `main.php` | `php`（7.4+），`-Dtranforlanguage.php=` |
| Java | `java` | `Main.java` | 目录下全部顶层 `.java`，主类 `mainClass` | 服务器 JVM 旁的 `javac` / `java`（JDK 8+），`-Dtranforlanguage.javac=` / `-Dtranforlanguage.java=` |
| 其他 | `process` | — | `command` 数组 | 自行提供 |

### 3.3 字段

```json
{
  "protocol": 1,
  "language": "python",
  "entrypoint": "scripts/main.py",
  "events": ["PlayerJoin", "PlayerChat", "message"]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `protocol` | 数字 | 协议版本，目前只能是 `1`；省略视为 `1`。 |
| `language` | 字符串 | 见上表；省略时自动识别。 |
| `entrypoint` | 字符串 | Python / Rust / JavaScript / Ruby / PHP 的入口文件，必须位于插件目录内。 |
| `mainClass` | 字符串 | Java 主类，默认 `Main`，可带包名，如 `com.example.Main`。 |
| `command` | 字符串数组 | `process` 语言的完整命令，直接执行，不经过 shell。 |
| `events` | 字符串数组 | 只接收列出的事件；省略表示接收全部；空数组表示不接收游戏事件（仍会收到 `shutdown`）。要接收其他插件发布的消息，需要包含 `"message"`。程序间调用不受此过滤影响。 |

### 3.4 第三方依赖（可选）

TFL 在插件启动 / 重载的**准备阶段**检查依赖声明。未声明依赖的现有插件保持原来的执行方式；声明了依赖时，根据语言使用各自生态的工具，**无需在服务器上手动安装 Python/Node 等库**。首次安装通常需要访问对应的包仓库，依赖安装失败时插件显示 `FAILED`，具体输出位于 `compile.log`。

| 语言 | 插件目录中的依赖声明 | 处理方式与要求 |
| --- | --- | --- |
| Python | `requirements.txt` | 系统需有带 `venv`/`pip` 的 Python 3.8+；为每个插件建立 `.tranforlanguage/python-venv/`，通过 `pip install -r` 安装 |
| JavaScript | `package.json`（可附 `package-lock.json`） | 需要 npm；在独立的 `node-project/` 安装 `node_modules`；有 lock 文件时使用 `npm ci`，否则 `npm install` |
| Rust | `Cargo.toml`（可附 `Cargo.lock`） | 使用 Cargo 的依赖声明；TFL 在生成的构建清单中注入内置 SDK，构建缓存由 Cargo 管理 |
| Go | `go.mod`（可附 `go.sum`） | 复制清单到构建目录，并通过 `go mod edit` 强制将 `tranforlanguage` 指向内置 SDK；使用 Go 模块缓存 |
| Ruby | `Gemfile`（可附 `Gemfile.lock`） | 需要 Bundler；在独立的 `ruby-project/` 安装 gems，使用 `bundle exec` 运行 |
| PHP | `composer.json`（可附 `composer.lock`） | 需要 Composer；在独立的 `php-project/` 安装 `vendor`，启动时先加载 SDK 与 Composer autoload |
| Java | `pom.xml` 或插件目录中的 `lib/*.jar` | `pom.xml` 需要 Maven；通过 Maven Dependency Plugin 解析运行时依赖，加入 `javac` 和 Java 进程 classpath；`lib/*.jar` 可直接使用 |
| C / C++ | `program.json` 中的 `pkgConfig` | 需要 `pkg-config` 和**已由服主安装**的开发库；编译时自动读取 `--cflags` / `--libs` |
| process | 自定义 `command` | 仍由作者自行管理依赖 |

Python 示例：

```text
programs/hello/
├── main.py
└── requirements.txt   # 例如：requests==2.32.3
```

C / C++ 示例（需在服务器上先安装 zlib 的开发包）：

```json
{
  "language": "c",
  "pkgConfig": ["zlib"]
}
```

**缓存与安全：**

- Python、JavaScript、Ruby、PHP 依赖目录位于各插件自己的 `.tranforlanguage/` 下，依赖清单及锁文件不变时重载会复用现有安装；变更后重新创建，避免残留已移除的依赖。其他编译型语言使用其官方构建缓存。
- TFL 将依赖声明复制到独立的构建目录，不修改作者提供的 `requirements.txt`、`package.json`、`Cargo.toml`、`go.mod` 等文件。需要通过相对路径引用**本地包**时，注意 npm / Cargo / Bundler / Composer 的清单将在 `.tranforlanguage/` 下解析；优先使用正式的仓库依赖或明确的绝对路径。
- npm 使用 `--ignore-scripts`，Composer 使用 `--no-scripts --no-plugins`；依赖有构建脚本 / 插件时，可能需要自行预先构建兼容的包。其它安装器仍可能执行依赖包含的代码，请只安装可信依赖。
- 不会自动以系统权限安装 C/C++ 库，不会自动重启其他插件。缺失包管理器、下载超时、依赖不兼容均会阻止**该插件**启动。
- 可通过 JVM 参数指定可执行文件：`-Dtranforlanguage.python=`、`-Dtranforlanguage.npm=`、`-Dtranforlanguage.bundle=`、`-Dtranforlanguage.composer=`、`-Dtranforlanguage.maven=`、`-Dtranforlanguage.pkg-config=`，以及原有语言工具链参数。

## 4. 生命周期与命令

| 状态 | 含义 |
| --- | --- |
| `STARTING` | 正在编译或启动 |
| `RUNNING` | 进程运行中 |
| `STOPPED` | 已停止或进程自行退出（退出码写入日志，不会自动重启） |
| `FAILED` | 配置错误或编译失败，原因写入日志和 `compile.log` |

| 命令 | 作用 |
| --- | --- |
| `/tranforlanguage list` | 列出所有插件及状态 |
| `/tranforlanguage reload` | 重新扫描目录并逐个启动全部插件 |
| `/tranforlanguage reload <id>` / `start <id>` | 只重新编译并启动指定插件 |
| `/tranforlanguage stop <id>` | 只停止指定插件 |
| `/tranforlanguage version` | 显示版本 |

Spigot 权限：`tranforlanguage.use`（list）、`tranforlanguage.reload`（启动/停止/重载）、`tranforlanguage.version`。模组端要求操作员等级 2。

关闭流程：服务端发送 `shutdown` 事件；SDK 的 `run` 会调用你注册的 `shutdown` 处理器后返回。进程 1 秒内未退出会被终止，再过 1 秒强制结束。重载会丢失进程内存中的状态，需要持久化的数据请自行写文件。

## 5. 事件参考

所有参数都是**字符串**（数字、布尔值写成文本，如 `"3"`、`"true"`）。下表“模组”指 Fabric、Forge 和 NeoForge。

### 5.1 玩家

| 事件 | 参数 | Spigot | 模组 | 说明 |
| --- | --- | --- | --- | --- |
| `PlayerJoin` | 玩家 | ✓ | ✓ | |
| `PlayerQuit` | 玩家 | ✓ | ✓ | |
| `PlayerChat` | 玩家, 消息 | ✓ | ✓ | |
| `PlayerRespawn` | 玩家 | ✓ | ✓ | |
| `PlayerDeath` | 玩家, 死亡消息 | ✓ | ✓ | 同时会有 `EntityDeath` |
| `PlayerMove` | 玩家 | ✓ | ✓ | 只在位置改变时；默认每名玩家每 100ms 最多一次（`-Dtranforlanguage.move-interval-ms`） |
| `PlayerInteract` | 玩家, 动作, 手持物品 | ✓（全部交互类型） | ✓（仅 `RIGHT_CLICK_BLOCK`） | 空手时物品为 `null` |
| `PlayerDropItem` | 玩家, 物品 | ✓ | ✓ | |
| `PlayerPickupItem` | 玩家, 物品 | ✓ | ✓ | |
| `PlayerChangeWorld` | 玩家, 原世界, 新世界 | ✓ | ✓ | 包括传送门和跨维度重生 |
| `PlayerGameModeChange` | 玩家, 新模式 | ✓ | ✓ | `SURVIVAL` / `CREATIVE` / `ADVENTURE` / `SPECTATOR` |
| `PlayerLevelChange` | 玩家, 原等级, 新等级 | ✓ | ✓ | 经验等级 |
| `PlayerToggleSneak` | 玩家, `true`/`false` | ✓ | ✓ | |
| `PlayerToggleSprint` | 玩家, `true`/`false` | ✓ | ✓ | |
| `PlayerToggleFlight` | 玩家, `true`/`false` | ✓ | ✓ | |
| `PlayerBedEnter` | 玩家 | ✓ | ✓ | 只在真正入睡时 |
| `PlayerBedLeave` | 玩家 | ✓ | ✓ | |
| `PlayerKick` | 玩家, 原因 | ✓ | — | |
| `PlayerItemConsume` | 玩家, 物品 | ✓ | — | 吃、喝 |
| `PlayerAdvancement` | 玩家, 进度 ID | ✓（1.12+） | — | 如 `minecraft:story/mine_stone` |

### 5.2 方块、实体、物品栏

| 事件 | 参数 | Spigot | 模组 | 说明 |
| --- | --- | --- | --- | --- |
| `BlockBreak` | 玩家, 方块 | ✓ | ✓ | |
| `BlockPlace` | 玩家, 方块 | ✓ | ✓ | |
| `BlockIgnite` | 玩家, 方块 | ✓ | ✓ | 非玩家点燃时玩家为 `null` |
| `EntityDamage` | 实体, 伤害值 | ✓ | ✓ | 包括玩家 |
| `EntityDeath` | 实体 | ✓ | ✓ | 包括玩家 |
| `EntitySpawn` | 实体类型 | ✓（版本支持时） | ✓ | 模组端包含区块加载出的实体 |
| `HangingBreak` | 实体类型, 原因 | ✓ | ✓ | 画、物品展示框等 |
| `InventoryOpen` | 玩家 | ✓ | ✓ | |
| `InventoryClose` | 玩家 | ✓ | ✓ | |
| `InventoryClick` | 玩家, 槽位, 物品 | ✓ | ✓ | 空槽：Spigot 为 `AIR`，模组为 `minecraft:air` |

### 5.3 世界与服务器

| 事件 | 参数 | Spigot | 模组 | 说明 |
| --- | --- | --- | --- | --- |
| `WorldLoad` | 世界 | ✓ | ✓ | |
| `WeatherChange` | 世界, 是否下雨 | ✓ | ✓ | |
| `ThunderChange` | 世界, 是否雷暴 | ✓ | ✓ | |
| `ServerCommand` | 发送者, 命令 | ✓（控制台命令） | ✓（含玩家命令） | |

### 5.4 特殊事件

| 事件 | 来源 | 说明 |
| --- | --- | --- |
| `shutdown` | 服务端 | 关闭或重载前的最后一个事件，处理后应退出 |
| `message` | 其他插件 | 其他插件 `publish` 的消息，附带 `from` 与 `topic` |
| `call` / `reply` | 其他插件 | 程序间调用；SDK 自动处理，通常不需要直接使用 |

### 5.5 值的格式与平台差异

- **名称格式因平台而异**：Spigot 使用 Bukkit 名称（方块 `STONE`、世界 `world_nether`）；模组使用游戏内部表示（方块 `Block{minecraft:stone}`、世界 `ResourceKey[minecraft:dimension / minecraft:the_nether]`）。跨平台插件请不要依赖具体字符串格式，或自行做兼容。
- **状态类事件的含义**：Spigot 上这些事件来自 Bukkit 事件，只在变化真正生效时发送（被其他插件取消的不发送）；模组端每个服务器 tick 比较一次玩家/世界状态，变化后发送。因此模组端在一个 tick 内发生又恢复的变化不会被报告，玩家加入时的初始状态也不会作为“变化”发送。
- 某平台缺少对应回调时，事件不会被伪造（上表中的 “—”）。

## 6. 发送动作

| 动作 | 作用 |
| --- | --- |
| `broadcast(message)` | 向全服广播（Spigot 支持 MiniMessage 格式，模组端为纯文本） |
| `sendMessage(player, message)` | 给指定玩家发私信（玩家不在线时忽略） |
| `console(message)` | 写入服务器日志（带 `[插件ID]` 前缀） |
| `executeCommand(command)` | 以**控制台权限**执行命令，不带开头的 `/` |

各语言的函数名见[第 8 节](#8-各语言-sdk-速查)。所有动作都是异步排队的，函数返回不代表已经在游戏中执行。

## 7. 程序间调用

插件可以按 ID 调用其他插件，不论语言：

- **调用**：`call(目标ID, 方法, 参数...)`，等待并返回字符串结果；目标出错时抛出 / 返回该语言的错误。
- **暴露方法**：`handle(方法, 函数)`，函数返回值作为结果；抛出的异常会作为错误返回给调用方。
- **单向通知**：`notify(目标ID, 方法, 参数...)`，不等待结果。
- **发布 / 订阅**：`publish(主题, 参数...)` 发给除自己外所有接收 `message` 的插件；`subscribe(主题, 函数)` 接收，函数参数为 `(发送者ID, 参数...)`。

要点：

- 参数和结果都是字符串；结构化数据请用 JSON 编码后传递。
- 目标未运行、方法不存在、目标在回复前停止、超时（默认 30 秒，`-Dtranforlanguage.call-timeout-ms`）时，调用会立即失败而不是卡住。
- 等待结果期间收到的调用会被处理，所以 A 调用 B、B 再回调 A 不会死锁。
- Python / Ruby / PHP / Java / Rust / C / C++ 请在事件处理器或主线程里调用；Go 和 JavaScript 可以并发调用。
- 插件 ID 可从环境变量 `TRANFORLANGUAGE_PROGRAM_ID` 读取（各 SDK 中的 `program_id` / `programId` / `ProgramID`）。

完整示例：[examples/interop](../examples/interop)。

## 8. 各语言 SDK 速查

### Python

```python
from tranforlanguage import (on, handle, subscribe, run, call, notify, publish,
                             broadcast, send_message, console, dispatch_command,
                             program_id, CallError)

@on("PlayerChat")
def chat(player, message): ...

@handle("greet")
def greet(name): return "hi " + name

@subscribe("news")
def news(sender, text): ...

run()   # 阻塞直到 shutdown
```

### JavaScript（Node.js，CommonJS）

```js
const t = require('tranforlanguage');
t.on('PlayerChat', (player, message) => { /* 可以是 async */ });
t.handle('greet', async (name) => 'hi ' + name);
t.subscribe('news', (sender, text) => {});
const result = await t.call('other', 'greet', 'Alex');  // 返回 Promise，失败时抛出 t.CallError
t.broadcast('x'); t.sendMessage(p, 'x'); t.console('x'); t.dispatchCommand('say x');
t.run();
```

### Go

```go
import t "tranforlanguage"

t.On("PlayerChat", func(args []string) {})
t.Handle("greet", func(args []string) (string, error) { return "hi " + args[0], nil })
t.Subscribe("news", func(from string, args []string) {})
result, err := t.Call("other", "greet", "Alex")   // err 为 *t.CallError
t.Broadcast("x"); t.SendMessage(p, "x"); t.Console("x"); t.DispatchCommand("say x")
t.Run()
```

### Rust

```rust
use tranforlanguage::{run, handle, call, notify, publish, broadcast, send_message, console, dispatch_command, program_id};

handle("greet", |args| Ok(format!("hi {}", args[0])));
run(|event| {
    match event.event.as_str() {
        "PlayerChat" => { let _ = call("other", "greet", &[&event.args[0]]); }
        "message" => { /* event.topic、event.from */ }
        _ => {}
    }
})?;
```

### Ruby

```ruby
require 'tranforlanguage'
TranForLanguage.on('PlayerChat') { |player, message| }
TranForLanguage.handle('greet') { |name| "hi #{name}" }
TranForLanguage.subscribe('news') { |sender, text| }
TranForLanguage.call('other', 'greet', 'Alex')   # 失败时抛出 TranForLanguage::CallError
TranForLanguage.broadcast('x'); TranForLanguage.send_message(p, 'x')
TranForLanguage.console('x'); TranForLanguage.dispatch_command('say x')
TranForLanguage.run
```

### PHP

```php
<?php  // TranForLanguage 类已预加载
TranForLanguage::on('PlayerChat', function ($player, $message) {});
TranForLanguage::handle('greet', fn($name) => "hi $name");
TranForLanguage::subscribe('news', function ($sender, $text) {});
TranForLanguage::call('other', 'greet', 'Alex');  // 失败时抛出 TranForLanguageCallError
TranForLanguage::broadcast('x'); TranForLanguage::sendMessage($p, 'x');
TranForLanguage::console('x'); TranForLanguage::dispatchCommand('say x');
TranForLanguage::run();
```

### Java

```java
import tranforlanguage.TranForLanguage;

TranForLanguage.on("PlayerChat", args -> {});
TranForLanguage.handle("greet", args -> "hi " + args[0]);
TranForLanguage.subscribe("news", (from, args) -> {});
String r = TranForLanguage.call("other", "greet", "Alex");  // 失败时抛出 TranForLanguage.CallError
TranForLanguage.broadcast("x"); TranForLanguage.sendMessage(p, "x");
TranForLanguage.console("x"); TranForLanguage.dispatchCommand("say x");
TranForLanguage.run();
```

### C

```c
/* 无需 #include，SDK 自动注入 */
static void on_event(const tranforlanguage_event *e, void *user) {
    if (!strcmp(e->event, "PlayerJoin")) sendMessage(e->args[0], "hi");
}
static int greet(int argc, char **args, char **result, void *user) {
    *result = tranforlanguage_strdup("hi"); return 1;   /* 返回 0 表示错误，*result 为错误信息 */
}
int main(void) {
    tranforlanguage_handle("greet", greet, NULL);
    return tranforlanguage_run(on_event, NULL);
}
/* 调用：char *out; int ok = tranforlanguage_call("other", "greet", 1, (const char *[]){"Alex"}, &out); free(out); */
```

### C++

```cpp
int main() {
    tranforlanguage::handle("greet", [](const tranforlanguage::Args& a) { return "hi " + a.at(0); });
    return tranforlanguage::run([](const tranforlanguage_event& e) {
        if (std::string(e.event) == "PlayerJoin") {
            std::string r = tranforlanguage::call("other", "greet", {e.args[0]});  // 失败抛出 tranforlanguage::CallError
            broadcast(r.c_str());
        }
    });
}
```

C / C++ 也可以不使用 `tranforlanguage_run`，自行逐行读取标准输入（见 [examples/c](../examples/c)），但那样无法使用程序间调用。

## 9. 调试与排错

| 现象 | 排查 |
| --- | --- |
| `list` 显示 `FAILED` | 查看服务器日志中的原因，以及插件目录下的 `compile.log` |
| 显示 `STOPPED` | 进程自行退出；日志中有退出码。检查是否忘了调用 `run()` |
| 收不到事件 | 检查 `program.json` 的 `events` 过滤；确认事件在当前平台受支持（第 5 节） |
| 收不到 `publish` 的消息 | `events` 中加入 `"message"`；发送者自己不会收到 |
| 输出乱码或动作无效 | **标准输出只能用于协议**。调试信息请写到标准错误（Python `print(..., file=sys.stderr)`、JS `console.error`、Go `fmt.Fprintln(os.Stderr, ...)` 等），标准错误会带 `[插件ID]` 前缀写入服务器日志 |
| 首次 Rust 构建很慢 | Cargo 需要下载依赖，之后会缓存 |
| `Multiple program languages found` | 同目录有多种语言的入口，在 `program.json` 中指定 `language` |
| 事件队列已满的警告 | 插件处理太慢；每个插件最多排队 2000 条，满了会丢弃新事件。用 `events` 只订阅需要的事件，或把耗时操作移到其他线程 |

**安全**：插件以服务器进程的权限运行，没有沙箱，`executeCommand` 拥有控制台权限。只安装你信任的程序。

## 10. 参与开发 TranForLanguage

### 10.1 仓库结构

| 路径 | 内容 |
| --- | --- |
| `core/` | 与平台无关的核心：`ProgramManager`（插件管理）、`ProgramRuntime`（进程与协议）、`ProgramBus`（程序间调用）、`language/`（各语言适配器）、`resources/sdk/`（各语言 SDK） |
| `src/` | Spigot 插件（`ProcessManager` 监听 Bukkit 事件） |
| `adapters/minecraft/` | 三个模组加载器共用的代码：命令、`ModEvents`（按 tick 检查状态）、Mixin |
| `adapters/fabric/`、`adapters/forge/`、`adapters/neoforge/` | 各加载器的入口模板与构建脚本 |
| `scripts/` | 版本锁定、构建配置、产物检查 |
| `compatibility/` | 锁定的游戏/加载器版本矩阵 |
| `validation/` | 真实服务端冒烟测试 |
| `examples/` | 各语言示例 |

### 10.2 构建与测试

```bash
mvn verify                                    # Spigot 插件 + 全部核心测试（需要 9 种语言的工具链）
python3 scripts/test-versions.py              # 版本范围测试
python3 scripts/configure-build.py fabric 1.21.1
gradle -p adapters/fabric build               # 构建单个模组目标
python3 scripts/check-artifact.py fabric 1.21.1 21 'adapters/fabric/build/libs/*.jar'
```

核心测试会真实编译和运行各语言程序，`InteropTest` 让 9 种语言同时运行并互相调用。

### 10.3 添加一种语言

1. 在 `core/src/main/java/.../core/language/` 实现 `LanguageProvider`：`id()` 返回语言标识，`prepare()` 完成编译并返回 `LaunchCommand`（工作目录、参数、附加环境变量）。不要在这里访问游戏 API。
2. 在 `LanguageProviders` 的构造函数中注册，并在 `detectLanguage()` 中加入自动识别的文件名。
3. 在 `core/src/main/resources/sdk/<语言>/` 提供 SDK，实现 `on` / `run`、四个动作，以及 `handle` / `call` / `notify` / `publish`（等待回复时要处理收到的 `call`，避免互相调用死锁）。
4. 在 `BuiltinLanguagesTest` 加入中文往返测试，在 `core/src/test/resources/interop/<语言>/` 加入互调测试程序，并把语言加入 `InteropTest.LANGUAGES`。
5. 在 `scripts/check-artifact.py` 中加入 SDK 文件，在 CI 中加入工具链检查，并更新文档。

### 10.4 添加一个事件

1. **Spigot**：在 `ProcessManager` 中添加 `@EventHandler`。只在新版本才有的 Bukkit 事件用 `optional(...)` 按类名注册，并通过反射访问新 API，保持 1.8 基线可编译。
2. **模组**：优先在 `ModEvents.tick` 中比较状态（不需要新 Mixin，跨版本最稳定）；需要拦截游戏逻辑时再加 Mixin（`adapters/minecraft/src/main/mixins/`），版本差异用 `.template` 文件和 `generate-adapter.gradle` 中的替换处理。
3. 事件名使用 PascalCase，参数全部是字符串，同一事件在各平台的参数顺序必须一致。
4. 更新本文第 5 节和 [兼容说明](compatibility.md) 的事件表。

### 10.5 发布

版本号在 `pom.xml` 中定义，模组构建也读取它。CI 只在推送到 main 的提交信息包含 `[release-v<版本>]` 标记时创建标签并发布；合并 PR 前请确认最终提交信息里有这个标记。
