# 实际开服验证

这些脚本在临时目录中运行真实 Minecraft 服务端和四种语言的子进程。它们不会连接现有服务器；只监听 `127.0.0.1`，使用离线测试玩家，不要求微软账户。不要对生产服使用测试目录、配置或离线模式。

先准备与目标游戏匹配的 Java、gcc、g++、Python 3、Rust/Cargo 和 Node.js 22+。先完成 `mvn verify` / 对应加载器 Gradle 构建，并检查发行 JAR；游戏服务端和加载器从各自官方发行渠道安装。Fabric 还需要完全匹配的 Fabric API。原版游戏程序及依赖不随仓库再分发。

```bash
npm ci --prefix validation --ignore-scripts
# 先阅读 Minecraft EULA；同意后显式传入 --accept-eula。
python3 validation/prepare.py /tmp/tfl-fabric-1.20 --loader fabric \
  --artifact adapters/fabric/build/libs/tranforlanguage-fabric-1.20-2.1.0.jar --accept-eula
python3 validation/install.py /tmp/tfl-fabric-1.20 --minecraft 1.20
# 安装器会从官方渠道安装服务端和精确版本的 Fabric API。
node validation/run.cjs /tmp/tfl-fabric-1.20 -- java -Xmx2G -jar fabric-server-launch.jar nogui
```

`prepare.py` 只接受不存在的新目录，写入隔离标记、测试配置和 C++（兼容根目录 @root）、C、Python、Rust 四个测试程序。C/C++ 不包含 SDK 头文件，实际验证自动注入。已有目录会被拒绝，避免覆盖世界或用户配置。用 `--loader spigot|forge|neoforge` 选择其他平台；Forge/NeoForge 可把安装器生成的 `@libraries/.../unix_args.txt` 传在 Java 后面。命令用参数数组执行，不经 shell。

`run.cjs` 启动游戏，等待四个真实程序就绪，并验证 SDK 控制台命令、成功事件及协议字符串参数。支持 Mineflayer 的游戏版本还会连接测试玩家，实际移动、转头、聊天、丢弃/拾取物品、打开/点击/关闭箱子、放置/挖掘方块、死亡/重生和退出。启动后独立停止 C、重载 @root、重新启动 C，并确认其他语言进程 PID 不变；新建中文/空格 ID 插件并从游戏控制台启动，故意添加编译失败的插件验证隔离。停服后要求四个程序均接到 shutdown 且没有残留进程。

对于测试客户端尚不支持的最新协议，可加 `--no-player` 执行真实服务端启动、四语言共存、命令、天气、实体、点火、伤害、悬挂破坏和独立生命周期验证；结果会明确标为 `server-only`，不算真实玩家验收。脚本会在测试目录保存 `smoke-results.json`、完整服务器日志及每种语言收到的 `events.jsonl`。进程启动、Mixin 应用、事件、断言或清理失败均返回非零。

## 新接入模组事件的参数

| 事件 | 字符串参数 | 通知点 |
| --- | --- | --- |
| PlayerMove | 玩家名 | tick 结束时位置变化，按玩家限频 |
| PlayerDropItem | 玩家名、物品 | 主动丢弃成功 |
| PlayerPickupItem | 玩家名、物品 | 库存成功插入后，包括部分拾取 |
| InventoryClick | 玩家名、槽位、点击前物品 | 服务器处理容器点击的开始 |
| InventoryOpen / InventoryClose | 玩家名 | 服务器菜单打开成功 / 关闭 |
| BlockPlace | 玩家名、方块 | Fabric 放置成功；Forge 系沿用加载器事件 |
| WeatherChange | 世界、是否降雨 | tick 结束时降雨状态发生变化 |
| BlockIgnite | 点火者（未知为 null）、方块 | 从非火成功变成火，排除火年龄变化 |
| HangingBreak | 实体类型、移除原因 | 悬挂实体销毁，不含卸载/切维度 |
| EntityDamage | 实体名、传入伤害 | 接受伤害尝试；嵌套 super 调用合并 |
| ServerCommand | 执行者、命令 | 命令执行尝试，包含玩家命令 |

这些测试不替代全部版本逐个开服或模组组合兼容测试。具体通过的平台、游戏版本、模式及检查项应按实际结果记录；编译矩阵通过不能填写为玩家实测通过。

## CI

CI 在所有编译/产物检查通过后，对 Spigot/Paper 1.20.1、Fabric 1.20、Forge 1.20.1、NeoForge 1.21.5 执行真实玩家测试，并对 Fabric/NeoForge 26.3 执行 server-only 测试。使用发行 JAR，不使用开发环境类路径；不会上传 Minecraft 程序。发布依赖这些测试通过。可用 `ci.py` 在本地复现同一入口，示例：

```bash
python3 validation/ci.py --loader fabric --minecraft 1.20 --artifact-directory adapters/fabric/build/libs --directory /tmp/tfl-smoke --accept-eula
```
