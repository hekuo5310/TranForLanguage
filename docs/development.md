# TranForLanguage 开发文档

本文面向两类读者：

- **程序开发者**：用 C、C++、Python、Rust、JavaScript、Go、Ruby、PHP、Java 或 Lua 编写在 Minecraft 服务端运行的程序（下文称“插件”）。见第 1–9 节。
- **项目贡献者**：修改 TranForLanguage 本身（平台适配、语言适配器、事件）。见[贡献指南](../CONTRIBUTING.md)。

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
- 配置 `repository` 后，`.git/` 是自动更新使用的工作树，不要手动改写其中的 Git 状态。

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
| Lua | `lua` | `main.lua` | `entrypoint`，默认 `main.lua`；目录下全部 `.lua` 作为可 `require` 的模块 | Java 11+ 时由 LuaJ 编译在 JVM 内运行；否则 `lua`（5.1–5.4），`-Dtranforlanguage.lua=` |
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
| `entrypoint` | 字符串 | Python / Rust / JavaScript / Ruby / PHP / Lua 的入口文件，必须位于插件目录内。 |
| `mainClass` | 字符串 | Java 主类，默认 `Main`，可带包名，如 `com.example.Main`。 |
| `command` | 字符串数组 | `process` 语言的完整命令，直接执行，不经过 shell。 |
| `events` | 字符串数组 | 只接收列出的事件；省略表示接收全部；空数组表示不接收游戏事件（仍会收到 `shutdown`）。要接收其他插件发布的消息，需要包含 `"message"`。程序间调用不受此过滤影响。 |
| `repository` | 字符串 | 该插件的 Git 仓库地址（GitHub / GitLab 等）。首次加载时检出到插件目录；之后按 `updateInterval` 拉取，有新提交时热重载。见 [3.5 节](#35-从-git-仓库自动更新)。 |
| `branch` | 字符串 | 拉取的分支；省略时使用仓库的默认分支。 |
| `updateInterval` | 数字 | 自动拉取间隔（秒），默认 `3600`；`0` 表示只在首次加载时检出，不自动更新。 |

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

### 3.5 从 Git 仓库自动更新

在插件目录的 `program.json` 中填写仓库地址，TFL 会把该目录作为 Git 工作树管理：

```json
{
  "repository": "https://github.com/example/my-plugin.git",
  "branch": "main",
  "updateInterval": 3600
}
```

- 首次加载（或执行 `/tranforlanguage reload <id>`）时，如果插件目录中还没有 `.git`，TFL 会初始化仓库、拉取并检出到插件目录，然后正常准备程序。
- 之后默认每 3600 秒在后台拉取一次，不占用服务器线程。远端提交与当前检出版本不同时，TFL 更新文件并热重载该插件；没有新提交则不做任何事。服务器重启后，已有检出的插件会在启动完成后立即在后台检查一次。
- `branch` 省略时使用仓库的默认分支。`updateInterval` 的单位是秒；设为 `0` 表示只保留首次检出，不自动更新。
- 使用系统的 `git` 命令（可用 `-Dtranforlanguage.git=/path/to/git` 指定其他可执行文件）。私有仓库通过 git 自身的凭据助手或 SSH 配置认证；更新过程是非交互的，不会提示输入密码，凭据需提前配置好。
- 若仓库根目录也有 `program.json`，以仓库中的程序配置为准，本地文件只保留 `repository`、`branch`、`updateInterval` 三项更新设置；若仓库没有 `program.json`，则直接使用本地文件。
- 更新会执行 `git reset --hard`，插件目录中未提交的源码修改会被丢弃；检出不会清理已有的未跟踪文件。请把修改提交并推送到远端，而不是直接编辑受管理的源码。
- 插件目录会出现 `.git/`；`.tranforlanguage/` 等未跟踪的构建缓存会保留。仓库负责整个插件目录，不要把其他插件放进同一个工作树。
- 自动更新会直接运行远端仓库的新提交，TFL 不审核内容也不验证签名。只绑定你信任的仓库和分支，并参考[运行边界](administration.md#程序权限与运行边界)。

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
| `EntitySpawn` | 实体类型 | ✓ | ✓ | Spigot 覆盖 `EntitySpawnEvent` 的生物、物品、投掷物以及载具创建；模组端包含区块加载出的实体 |
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
| `ServerCommand` | 发送者, 命令 | ✓（控制台与玩家命令） | ✓（含玩家命令） | |

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

### 5.6 全量 Bukkit 事件（`BukkitEvent`，仅 Spigot）

除了上表的稳定事件，Spigot 还可以把当前服务器 API 中**所有可注册的 Bukkit 事件**作为通用事件转发。程序必须在 `events` 中显式列出 `"BukkitEvent"`；省略 `events` 不会自动开启，避免大量事件突然涌入：

```json
{
  "language": "python",
  "events": ["BukkitEvent", "PlayerJoin"]
}
```

每条消息的 `args` 为两个字符串：Bukkit 事件类的简单名称（如 `BlockBurnEvent`）和 JSON 负载：

```json
{"event":"BukkitEvent","args":["BlockBurnEvent","{\"event\":\"BlockBurnEvent\",\"cancelled\":false,\"block\":{\"type\":\"STONE\",\"world\":\"world\",\"x\":10,\"y\":64,\"z\":-3}}"]}
```

负载包含 `event`、`cancelled`，以及事件上可用的常见字段：`player`、`entity`、`block`、`world`、`location`、`item`、`inventory`、`type`、`action`、`cause`、`reason`、`amount`、`damage`、`message`、`command`、`from`、`to`、`slot` 等。值的格式：

- 实体：`{"uuid":"...","type":"ZOMBIE","name":"Zombie"}`；玩家为 `{"name":"Alex","uuid":"..."}`。
- 方块：`{"type":"STONE","world":"world","x":0,"y":64,"z":0}`；位置：`{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}`。
- 物品：`{"type":"DIAMOND","amount":3,"durability":0}`；集合与映射最多保留前 100 项。
- 枚举使用名称（如 `DamageCause.ENTITY_ATTACK` → `"ENTITY_ATTACK"`）。

该流覆盖运行时服务器 API 中存在的事件类（服务器以可扫描的 JAR 打包时自动发现；否则使用内置的 1.8 事件清单加常见新事件清单）。它不替代上表的稳定事件；需要跨平台、参数固定的事件时请继续使用上表。高频事件（移动、物理、液体流动等）会为每条事件生成 JSON，请只订阅真正需要的程序。

## 6. 发送动作

| 动作 | 作用 |
| --- | --- |
| `broadcast(message)` | 向全服广播（Spigot 支持 MiniMessage 格式，模组端为纯文本） |
| `sendMessage(player, message)` | 给指定玩家发私信（玩家不在线时忽略） |
| `console(message)` | 写入服务器日志（带 `[插件ID]` 前缀） |
| `executeCommand(command)` | 以**控制台权限**执行命令，不带开头的 `/` |

各语言的函数名见[第 8 节](#8-各语言-sdk-速查)。所有动作都是异步排队的，函数返回不代表已经在游戏中执行。

### 6.1 游戏操作（`@server`）

需要结果或错误反馈的游戏操作通过保留调用目标 `@server` 完成。未标注“仅 Spigot”的方法在三个平台行为一致；标注的方法只在 Spigot 实现，模组端调用会返回未知方法错误。各 SDK 提供 `server(方法, 参数...)`（Go 为 `Server`，C 为 `tranforlanguage_server`），等价于 `call("@server", 方法, 参数...)`：成功时返回结果字符串，失败时抛出 / 返回该语言的调用错误。操作在服务器线程执行。

超时只计算操作在服务器线程排队等待的时间：排队期间超时的调用会收到超时错误，并且**永远不会再执行**，因此收到超时后可以放心重试，不会重复给物品等；已经开始执行的操作不会被超时打断，调用方会收到它的真实结果；但调用方自身在此期间停止、重载、服务器正在关闭，或调用方的输入队列已满时，不保证收到任何回复（已开始的操作仍可能已经生效）。发出调用的程序在操作执行前被停止或重载时，操作同样不会执行。

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
| `sendTitle` | 玩家, 标题[, 副标题] | — | Spigot 全版本；1.8 自动使用数据包 |
| `sendActionBar` | 玩家, 文本 | — | Spigot 全版本；旧版自动使用 Bungee Chat 或数据包 |
| `setPlayerListHeaderFooter` | 玩家, 头部[, 尾部] | — | 仅 Spigot；1.8–1.12 自动使用数据包 |
| `setPlayerListName` | 玩家, 名称 | — | 仅 Spigot |
| `playSound` | 玩家, 声音[, 音量[, 音调]] | — | 仅 Spigot；默认音量 1、音调 1 |
| `getPing` | 玩家 | 毫秒数 | 仅 Spigot；旧版从服务器内部读取 |
| `giveExp` | 玩家, 数量 | — | 仅 Spigot；可为负数 |
| `setLevel` | 玩家, 等级 | — | 仅 Spigot；不能为负 |
| `setFlying` / `setAllowFlight` | 玩家, `true`/`false` | — | 仅 Spigot |
| `setSneaking` / `setSprinting` | 玩家, `true`/`false` | — | 仅 Spigot |
| `setTime` | 世界, 时间 | — | 仅 Spigot；游戏刻 |
| `setWeather` | 世界, `clear`/`rain`/`thunder`[, 持续时间] | — | 仅 Spigot；持续时间为 tick |
| `getWorlds` | — | JSON 数组 | 仅 Spigot；世界名 |
| `getWorldInfo` | 世界 | JSON 对象 | 仅 Spigot；环境、时间、天气、难度、出生点 |
| `setDifficulty` | 世界, 难度 | — | 仅 Spigot；`peaceful`/`easy`/`normal`/`hard` |
| `spawnEntity` | 世界, x, y, z, 实体类型 | UUID | 仅 Spigot；如 `minecraft:zombie` |
| `strikeLightning` | 世界, x, y, z | UUID | 仅 Spigot |
| `getNearbyEntities` | 世界, x, y, z, 半径 | JSON 数组 | 仅 Spigot；遍历世界实体，返回 `uuid`、`type`、`name` |
| `getEntityInfo` | 世界, UUID | JSON 对象 | 仅 Spigot；类型、名称、位置、有效性、生命值（可伤害实体） |
| `teleportEntity` | 世界, UUID, x, y, z | — | 仅 Spigot |
| `damageEntity` | 世界, UUID, 伤害 | — | 仅 Spigot；实体必须可伤害 |
| `removeEntity` | 世界, UUID | — | 仅 Spigot |
| `getItem` | 玩家, 槽位 | JSON 对象或 `null` | 仅 Spigot；槽位范围随版本（现代版本含盔甲/副手），返回 `type`、`amount`、`durability` |
| `setItem` | 玩家, 槽位, 物品 ID[, 数量] | — | 仅 Spigot；`air` 清空槽位 |
| `getInventory` | 玩家 | JSON 数组 | 仅 Spigot；物品栏全部槽位的 `slot` 与 `item` |
| `getPotionEffects` | 玩家 | JSON 数组 | 仅 Spigot；类型、持续时间、等级、环境/粒子标志 |
| `addPotionEffect` | 玩家, 效果, 持续时间[, 等级[, 环境]] | — | 仅 Spigot；持续时间为 tick，等级从 0 开始 |
| `removePotionEffect` | 玩家, 效果 | — | 仅 Spigot |
| `clearPotionEffects` | 玩家 | — | 仅 Spigot |
| `getServerInfo` | — | JSON 对象 | 仅 Spigot；版本、在线人数、最大人数、世界 |
| `getWorldBorder` | 世界 | JSON 对象 | 仅 Spigot；大小、中心、警告距离、伤害 |
| `setWorldBorder` | 世界, 大小[, 中心X, 中心Z[, 警告距离[, 伤害]]] | — | 仅 Spigot |
| `sendPluginMessage` | 玩家, 频道, 数据 | — | 仅 Spigot；数据按 UTF-8 编码，频道需由其他插件注册 |
| `setPlayerTime` | 玩家, 时间[, 相对] | — | 仅 Spigot；相对默认 `true` |
| `resetPlayerTime` | 玩家 | — | 仅 Spigot |
| `setPlayerWeather` | 玩家, `clear`/`downfall` | — | 仅 Spigot |
| `resetPlayerWeather` | 玩家 | — | 仅 Spigot |
| `setFlySpeed` / `setWalkSpeed` | 玩家, 速度 | — | 仅 Spigot；范围 -1 到 1 |
| `setInvulnerable` | 玩家, `true`/`false` | — | 仅 Spigot；1.9+ |
| `sendBossBar` | 玩家, 标题[, 进度[, 颜色[, 样式]]] | — | 仅 Spigot；1.9+，进度 0–1 |
| `removeBossBar` | 玩家 | — | 仅 Spigot；1.9+ |
| `spawnParticle` | 玩家, 粒子, x, y, z[, 数量[, 偏移X, 偏移Y, 偏移Z, 速度]] | — | 仅 Spigot；1.9+ |
| `playEffect` | 玩家, 效果, x, y, z[, 数据] | — | 仅 Spigot；使用玩家当前世界 |
| `sendSignChange` | 玩家, 世界, x, y, z, 第一行, 第二行, 第三行, 第四行 | — | 仅 Spigot |
| `sendResourcePack` | 玩家, URL | — | 仅 Spigot |
| `hasPermission` | 玩家, 权限 | `true`/`false` | 仅 Spigot |
| `getWhitelist` | — | JSON 对象 | 仅 Spigot；是否启用及玩家列表 |
| `setWhitelist` | 启用 | — | 仅 Spigot；服务器级设置 |
| `createGui` | 槽位, 标题 | GUI ID | 仅 Spigot；槽位为 9–54 的 9 的倍数 |
| `openGui` | 玩家, GUI ID | — | 仅 Spigot |
| `closeGui` | 玩家 | — | 仅 Spigot |
| `removeGui` | GUI ID | — | 仅 Spigot |
| `createMap` | 世界 | 地图 ID | 仅 Spigot |
| `giveMap` | 玩家, 地图 ID | — | 仅 Spigot；放不下的掉落 |
| `removeMap` | 地图 ID | — | 仅 Spigot |
| `invoke` | 目标, 方法[, 参数...] | JSON 字符串 | 仅 Spigot；通用 Bukkit 调用，见 6.2 |
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
- **文本**：Spigot 的标题、动作栏、Tab 列表、告示牌和踢出原因支持 MiniMessage 格式；模组端为纯文本。
- **仅 Spigot**：`setPlayerListHeaderFooter`、`setPlayerListName`、`playSound`、`getPing`、`giveExp`、`setLevel`、`setFlying`、`setAllowFlight`、`setSneaking`、`setSprinting`、`setTime`、`setWeather`、`getWorlds`、`getWorldInfo`、`setDifficulty`、`spawnEntity`、`strikeLightning`、`getNearbyEntities`、`getEntityInfo`、`teleportEntity`、`damageEntity`、`removeEntity`、`getItem`、`setItem`、`getInventory`、`getPotionEffects`、`addPotionEffect`、`removePotionEffect`、`clearPotionEffects`、`getServerInfo`、`getWorldBorder`、`setWorldBorder`、`sendPluginMessage`、`setPlayerTime`、`resetPlayerTime`、`setPlayerWeather`、`resetPlayerWeather`、`setFlySpeed`、`setWalkSpeed`、`setInvulnerable`、`sendBossBar`、`removeBossBar`、`spawnParticle`、`playEffect`、`sendSignChange`、`sendResourcePack`、`hasPermission`、`getWhitelist`、`setWhitelist`、`createGui`、`openGui`、`closeGui`、`removeGui`、`createMap`、`giveMap`、`removeMap`、`invoke` 目前只在 Spigot 实现；模组端会报告未知方法。
- 这些操作不会触发 `ServerCommand` 事件；会改变状态的操作照常触发相应事件（例如 `setGameMode` 触发 `PlayerGameModeChange`）。

### 6.2 通用 Bukkit API 调用（`invoke`，仅 Spigot）

`invoke` 通过反射调用目标对象的**公开 Bukkit API**方法。方法查找基于 Bukkit 接口/类，不会暴露 CraftBukkit 或 NMS 成员。它适合尚未提供专用快捷方法的 API，例如 Scoreboard、地图、GUI 物品操作和自定义监听逻辑。

```python
# 服务器信息
server("invoke", "server", "getVersion")
server("invoke", "bukkit", "getVersion")

# Scoreboard：创建计分板、显示在侧边栏、设置分数
server("invoke", "scoreboard", "registerNewObjective", "tfl", "dummy")
server("invoke", "objective:tfl", "setDisplaySlot", "SIDEBAR")
server("invoke", "score:tfl,Alex", "setScore", "42")

# GUI：创建 27 格箱子、放入物品、打开给玩家
gui = server("createGui", "27", "TFL GUI")
server("invoke", "gui:" + gui, "setItem", "0", "minecraft:diamond,3")
server("openGui", "Alex", gui)
server("invoke", "gui:" + gui, "getItem", "0")

# 地图：创建地图、设置缩放、交给玩家
map = server("createMap", "world")
server("invoke", "map:" + map, "setScale", "CLOSEST")
server("giveMap", "Alex", map)
```

目标：

| 目标 | 说明 |
| --- | --- |
| `server` | `org.bukkit.Server` |
| `bukkit` | `org.bukkit.Bukkit` 的静态方法 |
| `player:<玩家>` | 在线玩家 |
| `offlineplayer:<名称或 UUID>` | 离线玩家 |
| `world:<世界>` | 世界 |
| `block:<世界>,<x>,<y>,<z>` | 方块 |
| `blockstate:<世界>,<x>,<y>,<z>` | 方块状态 |
| `entity:<世界>,<UUID>` 或 `entity:<UUID>` | 实体（跨世界扫描需较长） |
| `inventory:<玩家或 GUI ID>` | 玩家物品栏或 GUI |
| `gui:<GUI ID>` | GUI 物品栏 |
| `scoreboard` / `scoreboard:<玩家>` | 主计分板 / 玩家计分板 |
| `objective:<名称>` / `team:<名称>` / `score:<目标>,<条目>` | 计分板对象 |
| `worldborder:<世界>` | 世界边界 |
| `map:<地图 ID>` | 地图视图 |

参数按目标方法的参数类型自动转换：字符串、数字、布尔、字符、枚举（名称）、UUID、`world`（名称）、`player`（名称）、`command sender`（`console`/`server` 或玩家名）、`location`（`世界,x,y,z[,yaw,pitch]`）、`vector`（`x,y,z`）、`block`/`blockstate`（`世界,x,y,z`）、`item`（`材质[,数量]`）、`material`、`potion effect`、`entity`（`世界,UUID`）、`inventory`（玩家名或 GUI ID）、`scoreboard`（`main` 或玩家名）、`objective`/`team`/`score`、`worldborder`（世界名）、`map`（地图 ID）。重载按参数可转换性和具体程度选择；可变参数方法不参与。

返回值统一为 JSON 字符串；`void` 方法返回空字符串，`null` 返回 `"null"`。调用失败（方法不存在、参数不匹配、目标不存在、方法抛异常）会作为该次 `@server` 调用的错误返回。

限制与安全：

- 只暴露 Bukkit API 接口/类的公开方法；`getClass`、`wait`、`notify` 等被拒绝，NMS/CraftBukkit 成员不可达。
- 不支持对象链式调用；每次调用都从上面的目标描述符开始。需要深层对象时使用新增的专用目标（如 `inventory`、`scoreboard`、`worldborder`、`map`）。
- 不支持可变参数和集合/映射参数；有对应固定参数重载时可以直接使用。
- 程序仍以服务端宿主权限运行；`invoke` 可以调用 `kickPlayer`、`shutdown`、`setOp` 等强 API，信任边界与 `executeCommand` 相同，只运行可信程序。

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
- Python / Ruby / PHP / Java / Lua / Rust / C / C++ 请在事件处理器或主线程里调用；Go 和 JavaScript 可以并发调用。
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

### Lua

```lua
local t = require('tranforlanguage')
t.on('PlayerChat', function(player, message) end)
t.handle('greet', function(name) return 'hi ' .. name end)
t.subscribe('news', function(sender, text) end)
local ok, err = pcall(t.call, 'other', 'greet', 'Alex')  -- 失败时抛出错误对象：t.is_call_error(err)，tostring(err) 为原因
t.server('giveItem', 'Alex', 'minecraft:diamond', 3)
t.broadcast('x'); t.send_message(p, 'x')
t.console('x'); t.dispatch_command('say x')
t.run()
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
| 收不到 `BukkitEvent` | 仅 Spigot 提供；确认 `program.json` 的 `events` 显式包含 `"BukkitEvent"`（省略 `events` 不会开启），并查看日志中注册的事件类数量 |
| 输出乱码或动作无效 | **标准输出只能用于协议**。调试信息请写到标准错误（Python `print(..., file=sys.stderr)`、JS `console.error`、Go `fmt.Fprintln(os.Stderr, ...)` 等），标准错误会带 `[插件ID]` 前缀写入服务器日志 |
| 首次 Rust 构建很慢 | Cargo 需要下载依赖，之后会缓存 |
| 仓库自动更新失败 | 在日志中搜索 `repository update failed`；确认 `git` 可用、仓库地址和分支正确、凭据已提前配置，并检查插件目录中的 `.git`。 |
| `Multiple program languages found` | 同目录有多种语言的入口，在 `program.json` 中指定 `language` |
| 事件队列已满的警告 | 插件处理太慢；每个插件最多排队 2000 条，满了会丢弃新事件。用 `events` 只订阅需要的事件，或把耗时操作移到其他线程 |

**安全**：插件以服务器进程的权限运行，没有沙箱，`executeCommand` 拥有控制台权限。只安装你信任的程序。

## 10. 参与开发 TranForLanguage

仓库结构、开发环境、构建与测试、添加语言 / 事件 / `@server` 方法的步骤、代码约定和发布流程，见[贡献指南](../CONTRIBUTING.md)。
