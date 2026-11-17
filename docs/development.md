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

TFL 使用各语言的常见依赖清单；**没有清单时沿用原先的运行方式**。有清单的依赖在插件准备/重载阶段处理，安装失败会显示为 `FAILED`，详情见插件目录下的 `compile.log`。安装工具和网络需由服主提供；请只运行可信插件。

| 语言 | 插件目录中的依赖声明 | 行为与要求 |
| --- | --- | --- |
| Python | `requirements.txt` | `python -m venv` 创建 `.tranforlanguage/python-venv/`，用该环境的 `pip install -r` 安装。内容变更后重新创建环境；需 `venv` 和 `pip` |
| JavaScript | `package.json`（可选 `package-lock.json`） | 在插件目录下安装独立的 `node_modules/`，以保留 `file:` 本地依赖的相对路径；有锁文件时使用 `npm ci`，否则 `npm install --no-package-lock`。通过 `NODE_PATH` 加载，不改动用户的入口源码或清单 |
| Rust | `Cargo.toml` | 使用 TOML 解析器修改托管副本：重定位相对路径依赖、保留 SDK 依赖原有 features，并创建独立工作区；插件目录内的本地路径 crate 复制到托管工作区，避免触碰原始清单及其 Cargo 工作区发现规则。原工作区的共享配置可继承 |
| Go | `go.mod`（可选 `go.sum`） | 将文件复制进托管构建目录，使用 `go mod edit` 注入 TFL SDK 的本地 `replace`；原始文件保持不变 |
| Ruby | `Gemfile`（可选 `Gemfile.lock`） | 通过 Bundler `bundle install` 安装到 `.tranforlanguage/ruby-gems/`，使用 `bundle exec ruby` 启动；需安装 `bundle` |
| PHP | `composer.json`（可选 `composer.lock`） | 通过 Composer 安装到插件自己的 `vendor/`；需安装 `composer`，用户代码按 Composer 规范自行 `require __DIR__ . '/vendor/autoload.php';` |
| Java | `lib/*.jar`、可选 `pom.xml` | 将 `lib/` 的 JAR 放入编译和运行 classpath；如果有 `pom.xml`，使用 Maven `dependency:copy-dependencies` 下载运行时依赖到 `.tranforlanguage/java-deps/`；需 `mvn` |
| C/C++ | `program.json` 中的 `pkgConfig` 字符串数组 | 使用系统 `pkg-config --cflags --libs` 查找已经安装的本地开发库，并将参数传给 gcc/g++；不会自动安装操作系统软件包 |

C/C++ 示例：

```json
{"language":"cpp","pkgConfig":["libcurl"]}
```

- 所有依赖操作都在服务端的插件准备线程中进行，不在 Minecraft 游戏线程中执行；安装时间取决于本机缓存与网络。
- Python、JavaScript、Ruby、PHP 和 Java 使用依赖指纹缓存。Python 还跟踪本地 `-r` / `-c` 递归引用，Ruby 会调用 `bundle check`，PHP 检查 Composer autoload 文件；安装失败会使旧成功标记失效。其他语言使用其官方构建/包管理器增量处理。
- 这些语言的外部包可能执行构建脚本或原生代码，TFL 不提供沙箱或自动审计。生产环境建议锁定依赖版本。
- 调整清单后执行 `/tranforlanguage reload <id>`。只改变 `requirements.txt` 等依赖文件不会自动触发热重载。
- `pkg-config` 的引号和反斜杠转义会被解析成完整参数（不执行 shell）。若查找进程超时，TFL 会停止它并报告失败。

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
| `PlayerItemHeld` | 玩家, 原槽位, 新槽位 | ✓ | ✓ | 快捷栏槽位 0–8 |
| `PlayerFoodLevelChange` | 玩家, 原饥饿值, 新饥饿值 | ✓ | ✓ | Spigot 只报告进食、消耗等自然变化；模组报告任何变化 |
| `PlayerVehicleEnter` | 玩家, 载具类型 | ✓ | ✓ | 船、矿车、马、猪等 |
| `PlayerVehicleExit` | 玩家, 载具类型 | ✓ | ✓ | |
| `PlayerToggleGlide` | 玩家, `true`/`false` | ✓（1.9+） | ✓ | 鞘翅滑翔 |
| `PlayerToggleSwim` | 玩家, `true`/`false` | ✓（1.13+） | ✓ | 游泳姿势 |
| `PlayerCraftItem` | 玩家, 物品 | ✓ | ✓ | 从合成栏取出成品 |
| `PlayerItemConsume` | 玩家, 物品 | ✓ | ✓ | 吃、喝等使用完成 |
| `PlayerAdvancement` | 玩家, 进度 ID | ✓（1.12+） | ✓ | 进度完成时一次，如 `minecraft:story/mine_stone`；包括配方解锁进度 |
| `PlayerKick` | 玩家, 原因 | ✓ | — | |

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
- **模组端的实现方式**：状态类事件（切换世界、模式、等级、潜行/疾跑/飞行/滑翔/游泳、睡觉、快捷栏、饥饿值、载具、雷暴）每 tick 比较一次状态；食用、合成、进度通过 Mixin 在游戏逻辑中直接通知。
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

### 6.1 游戏操作（`@server`）

需要结果或错误反馈的游戏操作通过保留调用目标 `@server` 完成，三个平台行为一致。各 SDK 提供 `server(方法, 参数...)`（Go 为 `Server`，C 为 `tranforlanguage_server`），等价于 `call("@server", 方法, 参数...)`：成功时返回结果字符串，失败时抛出 / 返回该语言的调用错误。操作在服务器线程执行。

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `players` | — | JSON 数组 | 在线玩家名 |
| `playerInfo` | 玩家 | JSON 对象 | `name`、`uuid`、`world`、`x`、`y`、`z`、`health`、`food`、`level`、`gameMode` |
| `teleport` | 玩家, x, y, z[, 世界] | — | 省略世界时在当前世界内传送 |
| `giveItem` | 玩家, 物品 ID[, 数量] | — | 如 `minecraft:diamond`，默认 1 个；放不下的掉落在脚下 |
| `clearInventory` | 玩家 | — | 清空物品栏 |
| `setHealth` | 玩家, 血量 | — | 不超过最大生命值 |
| `setFood` | 玩家, 饥饿值 | — | 0–20 |
| `setGameMode` | 玩家, 模式 | — | `survival` / `creative` / `adventure` / `spectator` |
| `sendTitle` | 玩家, 标题[, 副标题] | — | |
| `sendActionBar` | 玩家, 文本 | — | Spigot 需要 1.9+ |
| `getBlock` | 世界, x, y, z | 方块 ID | 如 `minecraft:stone` |
| `setBlock` | 世界, x, y, z, 方块 ID | — | |
| `kick` | 玩家[, 原因] | — | |

```python
import json, sys
from tranforlanguage import server, CallError

info = json.loads(server("playerInfo", "Alex"))
server("teleport", "Alex", 0, 80, 0)
server("giveItem", "Alex", "minecraft:diamond", 3)
try:
    server("setBlock", "world", 0, 64, 0, "minecraft:not_a_block")
except CallError as error:
    print(error, file=sys.stderr)   # 未知方块等错误会返回给调用方
```

- **世界名**：Spigot 使用 Bukkit 世界名（`world`、`world_nether`）；模组使用维度 ID（`minecraft:overworld`、`minecraft:the_nether`），也接受事件里报告的 `ResourceKey[...]` 形式。
- **物品 / 方块 ID**：统一使用命名空间 ID（`minecraft:diamond`，可省略 `minecraft:`）。Spigot 1.13 以下按旧版材质名匹配。
- **文本**：Spigot 的标题、动作栏和踢出原因支持 MiniMessage 格式；模组端为纯文本。
- 这些操作不会触发 `ServerCommand` 事件；会改变状态的操作照常触发相应事件（例如 `setGameMode` 触发 `PlayerGameModeChange`）。

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
from tranforlanguage import (on, handle, subscribe, run, call, server, notify, publish,
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
const info = JSON.parse(await t.server('playerInfo', 'Alex'));
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
info, err := t.Server("playerInfo", "Alex")
t.Broadcast("x"); t.SendMessage(p, "x"); t.Console("x"); t.DispatchCommand("say x")
t.Run()
```

### Rust

```rust
use tranforlanguage::{run, handle, call, server, notify, publish, broadcast, send_message, console, dispatch_command, program_id};
// server("teleport", &["Alex", "0", "80", "0"])?;

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
TranForLanguage.server('giveItem', 'Alex', 'minecraft:diamond', 3)
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
TranForLanguage::server('setGameMode', 'Alex', 'creative');
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
TranForLanguage.server("teleport", "Alex", 0, 80, 0);
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
/* 游戏操作：tranforlanguage_server("players", 0, NULL, &out); */
```

### C++

```cpp
int main() {
    tranforlanguage::handle("greet", [](const tranforlanguage::Args& a) { return "hi " + a.at(0); });
    return tranforlanguage::run([](const tranforlanguage_event& e) {
        if (std::string(e.event) == "PlayerJoin") {
            std::string r = tranforlanguage::call("other", "greet", {e.args[0]});  // 失败抛出 tranforlanguage::CallError
            tranforlanguage::server("giveItem", {e.args[0], "minecraft:bread"});
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
2. **模组**：优先在 `ModEvents.tick` 中比较状态（不需要新 Mixin，跨版本最稳定）；需要拦截游戏逻辑时再加 Mixin（`adapters/minecraft/src/main/mixins/`，并加入 `generate-adapter.gradle` 的 Mixin 列表）。版本差异用 `.template` 文件和 `generate-adapter.gradle` 中的替换处理；只是成员名或可见性变化时，放进 `Versions.java.template`。

### 10.5 添加一个 `@server` 方法

1. Spigot：在 `SpigotServerApi` 中实现；模组：在 `MinecraftPlatform.serverCall` 中实现。参数用 `ServerArgs` 读取，以保证错误信息一致；失败时抛出 `IllegalArgumentException`。
2. 模组端涉及跨版本变化的 API 时，优先通过 Brigadier 执行原版命令（`command(...)`），并严格校验拼入命令的参数。
3. 在 `ProgramManagerTest` / `validation/run.cjs` 中加入测试，更新本文 6.1 节。
3. 事件名使用 PascalCase，参数全部是字符串，同一事件在各平台的参数顺序必须一致。
4. 更新本文第 5 节和 [兼容说明](compatibility.md) 的事件表。

### 10.6 发布

版本号在 `pom.xml` 中定义，模组构建也读取它。CI 只在推送到 main 的提交信息包含 `[release-v<版本>]` 标记时创建标签并发布；合并 PR 前请确认最终提交信息里有这个标记。
