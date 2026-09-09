# JavaScript 子集限制与应对

MQuickJS 永远运行在 stricter mode，只支持接近 ES5 的一个子集。完整规则以上游 README 的 "JavaScript Subset Reference" 为准（`native/mquickjs/README.md`），这里只列使用方最先撞上的几条，以及在 Kotlin 侧的处理方式。

## 语法与语义

| 限制 | 表现 | 应对 |
|---|---|---|
| 只允许 strict mode | 没有 `with`，全局变量必须 `var` 声明 | 脚本按 strict 写 |
| 数组不能有空洞 | `a[10] = 2` 越过末尾抛 TypeError，`[1, , 3]` 是语法错误 | 需要稀疏结构用普通对象 |
| 只有间接 eval | `eval('x')` 禁止，`(1, eval)('x')` 可用，且访问不到局部变量 | 避免 eval |
| 没有值装箱 | `new Number(1)` 不支持 | 不写 |
| `for in` 只遍历自有属性 | 不走原型链 | 改用 `for of Object.keys(obj)` |
| `for of` 只支持数组 | 没有自定义迭代器 | 不依赖 iterator 协议 |
| 全局对象上不能定义 getter/setter | 直接往 `globalThis` 挂的属性对脚本不可见为全局变量 | 用 `var` 声明 |

## 文本与日期

这三条对多语言业务影响最大，脚本自身没有办法绕过，必须由 Kotlin 侧准备好数据再喂给脚本。

- **大小写转换只认 ASCII**：`"ÉCOLE".toLowerCase()` 得到 `"École"`。归一化用 Kotlin 的 `String.lowercase(Locale)` 做完再传入。
- **正则忽略大小写只对 ASCII 生效**，且永远按 Unicode 码点匹配（等价于始终带 `u` 标志），`u` 模式下不支持 `\p{...}` 属性类。从别处搬来的正则要复核。
- **Date 只有 `Date.now()`**：没有 `new Date()`、`getFullYear`、`Date.parse`、`toLocale*`。时间计算与格式化全部放在 Kotlin 侧（`kotlinx-datetime`），脚本只接收时间戳数字或格式化好的字符串。

## 判断是否该用 MQuickJS

脚本本身必须处理多语言文本或日期，说明业务不在 MQuickJS 的目标场景，换 QuickJS 更省事。MQuickJS 的价值点是毫秒级实例化与固定可控的内存，适合规则引擎、动态配置表达式这类小而高频的场景。
