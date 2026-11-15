# 跨语言互相调用示例

把 `economy/`、`shop/`、`greeter/` 三个目录复制到程序目录（Spigot：`plugins/C++ Plugins/`，模组端：`config/tranforcpp/programs/`）后执行 `/tranforlanguage reload`。

| 插件 ID | 语言 | 作用 |
| --- | --- | --- |
| `economy` | Go | 保存余额，提供 `balance`、`spend` 方法 |
| `shop` | Python | 提供 `price` 方法；聊天 `!buy apple` 时调用 `economy.spend`，成功后发布 `purchase` |
| `greeter` | JavaScript | 玩家加入时并行调用 `economy.balance` 和 `shop.price`；订阅 `purchase` 并广播 |

调用目标就是插件 ID（目录名）。参数和返回值都是字符串，需要结构化数据时自行用 JSON 编码。
