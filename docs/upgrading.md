# 从 2.1 及更早版本升级（不兼容变更）

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
