# 安装与构建

本页说明各平台的产物、如何从源码构建，以及插件/模组安装后程序放在哪里。各语言程序的写法见[语言指南](languages.md)。

## 产物与目标平台

| 产物 | 目标 | 构建工具 |
| --- | --- | --- |
| `tranforlanguage-spigot-2.3.0.jar` | Spigot / 常规 Paper 分支，官方 API 1.8–26.3 | Maven |
| `tranforlanguage-fabric-<游戏版本>-2.3.0.jar` | Fabric 1.20–26.3，逐版本构建；需要 Fabric Loader 0.17.0+ | Gradle + Loom |
| `tranforlanguage-neoforge-<游戏版本>-2.3.0.jar` | NeoForge 1.21–26.3，逐版本构建 | Gradle + ModDevGradle |
| `tranforlanguage-forge-<游戏版本>-2.3.0.jar` | Forge 1.20.x–1.21.x，按官方已发布版本构建 | Gradle + ForgeGradle |

目标范围与运行实测是不同的检查。[兼容说明](compatibility.md)列出版本矩阵、尚未接入的模组事件和验证边界。Spigot 1.8 之前及 Folia 尚未建立兼容保障。

## 从源码构建

### Spigot

需要 Maven 3.9 和 JDK 17 或以上。核心输出 Java 8 字节码；在 JVM 内运行程序的翻译器胶水代码（`core/jvm/engine`）为 Java 11 字节码，只在 Java 11+ 的服务器上加载，在 Java 8 服务器上，默认的 `"runtime": "auto"` 会让程序以独立进程运行，`"runtime": "jvm"` 则会报错（见[在服务器 JVM 内运行](jvm-runtime.md)）。服务器仍需使用其 Minecraft 版本要求的 Java。

```bash
mvn clean verify
```

安装 `target/tranforlanguage-spigot-2.3.0.jar` 到 `plugins/`。不再为同一插件维护 Maven / Gradle 两份构建。

### Fabric / Forge / NeoForge

先从已锁定版本清单选择精确游戏版本：

```bash
python3 scripts/configure-build.py fabric 1.20
gradle -p adapters/fabric clean build
```

`configure-build.py` 只写入构建配置并**输出**该目标需要的 Gradle 与 JDK 版本，不会切换你的环境。每次换目标都要按输出切换：Gradle 8 运行于 JDK 21，Gradle 9 运行于 JDK 25。例如 Fabric 1.20 使用 Gradle 8，而 NeoForge 26.3 需要先切换到 Gradle 9 与 JDK 25：

```bash
python3 scripts/configure-build.py neoforge 26.3
# 按输出切换到 Gradle 9.x 与 JDK 25 后再构建
gradle -p adapters/neoforge clean build

python3 scripts/configure-build.py forge 1.21
# 同样按输出切换工具链
gradle -p adapters/forge clean build
```

构建还需要目标游戏的 Java toolchain（17 / 21 / 25），见[版本锁文件](../compatibility/versions.json)中的 `java` 与 `gradle` 字段。CI 自动安装这些 JDK 和正确的 Gradle。产物在相应 `adapters/<加载器>/build/libs/`，仅安装与服务器加载器及 Minecraft 版本完全一致的发行 JAR 到 `mods/`。Fabric 额外安装对应游戏版本的 Fabric API。

也可从 GitHub Actions 下载按平台/游戏版本区分的产物。常规版本通过与项目版本一致的 `v*` 标签发布；本次 2.3.0 由 main 分支上带 `[release-v2.3.0]` 标记的提交在完整构建通过后自动创建标签并发布，不覆盖指向其他提交的标签。标记必须出现在最终推送到 main 的提交信息里，合并 PR 前请检查 GitHub 显示的提交信息；该版本不能通过手动推送标签发布。

## 程序目录

| 平台 | 程序目录 |
| --- | --- |
| Spigot | `plugins/TranForLanguage/programs/` |
| 模组加载器 | `config/tranforlanguage/programs/` |
