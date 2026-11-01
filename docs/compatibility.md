# 兼容范围与验证

## 构建方式

| 平台 | 构建 | 游戏版本目标 | 安装位置 |
| --- | --- | --- | --- |
| Spigot / 常规 Paper 分支 | Maven | 官方 Maven 已发布的正式版 API，1.8 至 26.3 | `plugins/` |
| Fabric | Gradle + Loom | 1.20 至 26.3 的正式版，逐版本独立构建 | `mods/`，需要同版本 Fabric API |
| NeoForge | Gradle + ModDevGradle | 1.21 至 26.3 的正式版，逐版本独立构建 | `mods/` |
| Forge | Gradle + ForgeGradle | 1.20.x、1.21.x 中官方发布的版本，逐版本独立构建 | `mods/` |

`compatibility/spigot-versions.json` 包含 68 个 Spigot API 编译目标，`compatibility/versions.json` 包含 58 个加载器/游戏构建组合。不存在官方加载器或 API 的版本不会虚构构建号。Spigot 1.8 之前、测试版、快照版和未来版本不在已建立的矩阵内，不能把“全版本目标”表述为每个历史版本已经实测。

Spigot 发行 JAR 按最旧的 1.8 API 编译、输出 Java 8 字节码，移除 Paper 专属入口、消息方法和插件元数据方法。声明 `api-version: '1.13'` 以避免现代服务端将材质转换为 `LEGACY_*`；旧版 Spigot 会忽略这个当时尚不存在的描述字段，基线 1.8 的描述文件解析测试验证该行为。Gson / Adventure 被打包并重定位，避免依赖服务端自带库版本。不存在的新版 Bukkit 事件通过可选注册处理。Folia 的区域线程模型尚未实现，移除之前没有验证依据的 `folia-supported` 声明。

模组按精确游戏版本写入元数据，并声明构建时选用的最低加载器版本，使用官方映射并由加载器工具链处理重映射。不能把一个版本的 JAR 改宽版本范围后分发给全部版本。1.20–1.20.4 游戏使用 Java 17，1.20.5–1.21.11 使用 Java 21，26.x 使用 Java 25；Gradle 8 工具链在 JDK 21 上运行，Gradle 9 工具链在 JDK 25 上运行（包括 Fabric 1.21.11 的新重映射工具链）。

## 事件接入情况

跨平台共享的是协议和生命周期，不保证所有平台拥有 Bukkit 的同名事件。缺少回调时不会伪造事件。

| 事件 | Spigot | Fabric | Forge / NeoForge |
| --- | --- | --- | --- |
| 加入、退出、聊天、重生 | 有 | 有 | 有 |
| 方块破坏 | 有 | 有 | 有 |
| 实体死亡、玩家死亡 | 有 | 有 | 有 |
| 实体进入世界 | 新版有生成事件 | `ENTITY_LOAD`，包含区块加载的实体 | `EntityJoinLevelEvent`，包含区块加载的实体 |
| 右键方块 | 有，含其他交互类型 | 有 | 有 |
| 世界加载 | 有 | 有 | 有 |
| 放置方块 | 有 | 有 | 有 |
| 物品栏打开、关闭 | 有 | 有 | 有 |
| 命令执行 | 有，Bukkit 服务器命令 | 有，包含玩家命令 | 有，包含玩家命令 |
| 移动、掉落、拾取、物品栏点击、天气、点燃、悬挂实体破坏、伤害 | 有（生成/拾取按版本可选） | 有 | 有 |

模组中的物品/方块/实体值来自其平台表示，不能假定与 Bukkit Material 枚举名称完全相同。现有 Bukkit `GenericTranforCEvent` Java 事件保持可用（仅在存在监听器时创建），但不是跨加载器 Java API。`EntityDamage` / `EntityDeath` 包括玩家，`PlayerDeath` 是额外的玩家专用通知；事件名不同，订阅时按需选择，不能当作相同事件去重。Spigot `PlayerMove` 忽略仅转头事件，并默认按玩家限频至每 100ms 一次。

## 自动检查与边界

CI 对锁定矩阵进行 API 编译/打包，Spigot 最旧目标运行共用核心集成测试，产物按平台和精确游戏版本命名；常规版本通过与项目版本一致的 `v*` 标签发布；本次 2.1.0 在带 `[release-v2.1.0]` 标记的 main 合并提交完整构建通过后创建标签并发布，拒绝移动已有标签。

共用核心测试实际编译并运行 C、C++、Python、Rust 程序，检查多语言插件并行、独立停止/重载、启动失败隔离、事件订阅、退出码与关闭时取消准备，检查单条事件及时传输、UTF-8/JSON、服务器线程调度、重载后拒绝旧操作、未知语言、扩展适配器及关闭。编译通过只能证明 API 和打包检查成功，不能替代服务端启动、真实玩家和各游戏事件的运行验收。

维护版本锁文件：

```bash
python3 scripts/resolve-versions.py --matrix --output compatibility/versions.json
python3 scripts/resolve-versions.py --spigot --output compatibility/spigot-versions.json
python3 scripts/test-versions.py
```

更新后检查 diff 和 CI；不得在失败时仅放宽模组元数据来绕过兼容问题。

## 运行模块清理

移除两套强制 GC/无实际缓存使用者的 MemoryOptimizer，以及无任务调用方的 SmartThreadOptimizer。启动直接创建 ProcessManager，不再初始化空的 StartupManager / SmartEventDispatcher；插件列表使用 Bukkit 下一 tick 调度和程序状态快照，不创建额外的定时线程。实际的有界事件队列、进程生命周期与主线程操作约束保留。

## 2.1 模组事件语义

模组端使用必需的 Mixin 钩子；入口或映射不匹配时启动失败，不会静默禁用事件。旧 Forge 包含重映射 refmap，Fabric 由 Loom 处理映射，新版 Forge/NeoForge 使用官方运行时名称。

移动和天气在每次服务端 tick 结束时比较状态，只通知位置变化和降雨状态变化，不通知转头或初始状态。移动使用同样的每玩家默认 100ms 限频；退出及停服清除记录。同一 tick 内先改变后恢复的天气/位置不会生成通知。

`PlayerDropItem` 观察玩家主动丢弃，`PlayerPickupItem` 使用库存插入后的成功回调，包含整组与部分拾取。`InventoryClick` 在服务端开始处理容器操作时携带点击前的物品；`ServerCommand` 是执行尝试通知，两者均不承诺操作最后成功，也不提供取消接口。打开/关闭事件覆盖服务端菜单，不把仅在客户端打开的个人背包画面当作服务器菜单。

`BlockIgnite` 在服务器成功从非火方块变成普通火或灵魂火后发送，未知点火者为字符串 `null`；火的年龄变化不算再次点火。`HangingBreak` 只报告悬挂实体被销毁，原因使用加载器的 `KILLED` / `DISCARDED` 等移除原因；区块卸载、传送和切换维度不算破坏。`EntityDamage` 报告原版伤害方法接受的尝试，数值是传入的原始伤害，不是最终扣血；处理护甲、吸收和模组钩子的结果与 Bukkit 事件不能完全等同。覆盖生物、盔甲架、掉落物、载具、末影水晶、物品展示框及潜影贝子弹，同一实体方法的 super 调用只生成一条通知。

事件参数清单与实际开服验证步骤见 [验证说明](../validation/README.md)。所有事件仍遵守程序准备期间不缓存、不回放的规则；例如初始世界加载早于程序就绪时不会补发 `WorldLoad`。
