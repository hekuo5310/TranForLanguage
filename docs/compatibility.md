# 兼容范围与验证

## 构建方式

| 平台 | 构建 | 游戏版本目标 | 安装位置 |
| --- | --- | --- | --- |
| Spigot / 常规 Paper 分支 | Maven | 官方 Maven 已发布的正式版 API，1.8 至 26.3 | `plugins/` |
| Fabric | Gradle + Loom | 1.20 至 26.3 的正式版，逐版本独立构建 | `mods/`，需要同版本 Fabric API |
| NeoForge | Gradle + ModDevGradle | 1.21 至 26.3 的正式版，逐版本独立构建 | `mods/` |
| Forge | Gradle + ForgeGradle | 1.20.x、1.21.x 中官方发布的版本，逐版本独立构建 | `mods/` |

`compatibility/spigot-versions.json` 包含 68 个 Spigot API 编译目标，`compatibility/versions.json` 包含 58 个加载器/游戏构建组合。不存在官方加载器或 API 的版本不会虚构构建号。Spigot 1.8 之前、测试版、快照版和未来版本不在已建立的矩阵内，不能把“全版本目标”表述为每个历史版本已经实测。

Spigot 发行 JAR 按最旧的 1.8 API 编译、输出 Java 8 字节码，移除 Paper 专属入口、消息方法和插件元数据方法。未指定 `api-version` 以允许旧版 Bukkit 加载；现代服务端可能显示 legacy plugin 提示。Gson / Adventure 被打包并重定位，避免依赖服务端自带库版本。不存在的新版 Bukkit 事件通过可选注册处理。Folia 的区域线程模型尚未实现，移除之前没有验证依据的 `folia-supported` 声明。

模组按精确游戏版本写入元数据，使用官方映射并由加载器工具链处理重映射。不能把一个版本的 JAR 改宽版本范围后分发给全部版本。1.20–1.20.4 游戏使用 Java 17，1.20.5–1.21.11 使用 Java 21，26.x 使用 Java 25；旧 Gradle 工具链在 JDK 21 上运行，新 26.x 工具链在 JDK 25 上运行。

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
| 放置方块 | 有 | 尚未接入 | 有 |
| 物品栏打开、关闭 | 有 | 尚未接入 | 有 |
| 命令执行 | 有，Bukkit 服务器命令 | 尚未接入 | 有，包含玩家命令 |
| 移动、掉落、拾取、物品栏点击、天气、点燃、悬挂实体破坏、伤害 | 有（生成/拾取按版本可选） | 尚未接入 | 尚未接入 |

模组中的物品/方块/实体值来自其平台表示，不能假定与 Bukkit Material 枚举名称完全相同。现有 Bukkit `GenericTranforCEvent` Java 事件保持可用，但不是跨加载器 Java API。

## 自动检查与边界

CI 对锁定矩阵进行 API 编译/打包，Spigot 最旧目标运行共用核心集成测试，产物按平台和精确游戏版本命名；发布仅由 `v*` 标签触发，必须先通过构建。

共用核心测试实际编译并运行小型 C++ 程序、运行 Python 程序，检查单条事件及时传输、UTF-8/JSON、服务器线程调度、重载后拒绝旧操作、未知语言、扩展适配器及关闭。编译通过只能证明 API 和打包检查成功，不能替代服务端启动、真实玩家和各游戏事件的运行验收。

维护版本锁文件：

```bash
python3 scripts/resolve-versions.py --matrix --output compatibility/versions.json
python3 scripts/resolve-versions.py --spigot --output compatibility/spigot-versions.json
python3 scripts/test-versions.py
```

更新后检查 diff 和 CI；不得在失败时仅放宽模组元数据来绕过兼容问题。
