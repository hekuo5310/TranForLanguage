# 命令、权限与运行边界

## 命令

- `/tranforlanguage list`：查看每个程序的 ID 和状态。
- `/tranforlanguage reload`：重新扫描目录，移除已删除插件，并依次准备所有插件；健康插件不因其他插件失败而停止。
- `/tranforlanguage reload <id>` / `start <id>`：独立准备并启动指定插件，新子目录无需重启其他插件。若指定插件已在运行，会先停止旧进程再准备并启动新进程；期间不向该插件投递事件，进程内未保存的状态会丢失。
- `/tranforlanguage stop <id>`：只停止指定插件。
- `/tranforlanguage version`：显示桥接版本。
- 只有 `/tranforlanguage` 一个主命令（没有别名）。Spigot 权限：`list` 需要 `tranforlanguage.use`，启动/停止/重载需要 `tranforlanguage.reload`，`version` / `ver` 需要 `tranforlanguage.version`。
- 模组端命令要求操作员级别 2；ID 占据命令剩余部分，中文、空格及 `@root` 可直接输入，无需加引号。`@root` 指代根目录程序。

## 程序权限与运行边界

**只安装你信任的程序。这里没有进程沙箱或低权限执行环境。** 源码、`program.json`、编译配置和 `.tranforlanguage/` 缓存都应由服务端管理员控制。程序以服务器进程的宿主权限运行，可读写其能访问的文件并自行发起网络请求；`executeCommand` / `dispatchCommand` 按现有协议拥有游戏控制台权限。命令接口的操作员权限只保护管理命令，不约束被安装程序。Rust 首次构建还会执行 Cargo 依赖的构建步骤；请审核依赖并保留锁文件。在服务器 JVM 内运行的程序与服务器共享内存和 CPU；其中 Java 和 Ruby 程序可通过反射等方式访问 JDK 的全部能力，WebAssembly 程序只能访问自己的程序目录。

程序可请求广播、私信、控制台日志和控制台命令，所有游戏操作通过服务器线程执行。Spigot 保留 MiniMessage 格式，模组端当前使用纯文本。
