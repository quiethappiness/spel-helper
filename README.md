# Spel-helper

给**自定义注解里的 SpEL 表达式**加上高亮、代码补全和 Ctrl+点击跳转。

自己写的工具包里常有这种注解,属性里写 SpEL 来取方法参数或参数内部的字段:

```java
@WrenchDistributedLockAndGet(lockKey = "#user.code")
public Map<String, Object> lockAndGet(@RequestBody UserInfo user) { ... }
```

平时写这种表达式没有任何提示 —— 参数名记错、字段名记错都得等运行时才炸。这个插件就是来补这一块的。

---

## 能做什么

| 能力 | 说明 |
|---|---|
| **语法高亮** | 注解里的表达式按 SpEL 着色,和普通字符串区分开 |
| **变量补全** | `"#"` 之后列出方法参数、外层类的 public 字段,以及 `#root` / `#this` |
| **属性/方法补全** | `"#user."` 之后列出 `UserInfo` 的属性、方法 |
| **Ctrl+点击跳转** | `#user` 跳到方法参数,`.code` 跳到 getter 或字段 |
| **查找引用 / 重命名** | 因为是真正的 PSI 引用,IDE 原生的这些功能都能用 |
| **类型引用补全** | `T(` 之后补类全名 |

## 环境要求

- **IDEA Ultimate**

  表达式高亮所依赖的 SpEL 语言,是 **Ultimate 自带 Spring 插件**提供的。Community 版没有这个插件,装上也只会静默不生效。

- IDE 2025.2 及以上

## 安装

1. `Settings` → `Plugins` → 齿轮图标 ⚙ → **Install Plugin from Disk**
2. 选 `spel-helper-<版本>.zip`
3. **重启 IDE**(注入器只在启动时注册,不重启不生效)

## 配置

`Settings` 里搜索 **SpEL Helper**,加规则:

| 注解全类名 | 属性名(留空 = 全部) |
|---|---|
| `com.yourcompany.toolkit.MySpel` | `value` |
| `com.yourcompany.toolkit.OtherSpel` | *(留空)* |

- **属性名留空** 表示"这个注解下所有 String 属性都算 SpEL"。
- 配置是**应用级**的,配一次所有项目生效。
- 还有一个开关:**注解里以 `#` 开头的字符串自动识别**。默认开着,好处是新增注解不用先配就能用;关掉之后只有配置过的注解才生效,行为更可预测。

## 支持的写法

```java
// 裸写法:整个内容就是表达式
@MySpel("#user.code")

// 包装写法:#{...} 内部只写裸变量名,和上面等价
@MySpel("#{user.code}")

// 数组属性里的每一项各自处理
@UniqueIdentifier(fieldPathsOrSpels = {"#{user.code}", "#info"})
```

包装写法里**不要**再写 `#`(`#{#user.code}` 是另一种含义,不是这里的等价形式)。

其余 SpEL 语法按标准来:

```java
"#user.address.city"        // 属性链
"#user.getName()"           // 方法调用
"#user['key']"              // Map 取值
"#user?.address"            // 安全访问
"T(java.lang.Math).PI"      // 静态类型引用
"@someBean"                 // Spring bean 引用(尽力而为,见下)
"#a + #b"                   // 运算符:每个操作数各自解析
```

## 已知限制

- **只支持 Java 宿主**,不处理 Kotlin 注解。
- **文本块和含转义序列的字符串不处理**(`"a\n#user"` 这种)。源码偏移和解析出来的值对不上,与其提示错位不如不提示。
- **`@bean` 是尽力而为**:按类名(首字母大写)找同名类。推不出类型时,`@xxx.` 之后不再给候选。
- **`#root` / `#this` 指向外层类**,是借 Spring 的语义做的近似。
- 补全列表**只包含**上面说的那些来源 —— 这是刻意的。SpEL 语言自带的补全贡献者在同一个片段里会按它自己的上下文给一堆无关候选(别的注解的属性名、Spring bean 列表),我们已经把它屏蔽掉了。

## 怎么实现的

一句话:**不解析 SpEL,但自己解析根变量。**

| 环节 | 谁做 |
|---|---|
| 把 SpEL 注入注解属性 | 本插件(`MultiHostInjector`) |
| 语法解析、高亮 | 平台自带的 SpEL 语言 |
| **`#变量` → 方法参数、属性链 → getter/字段** | **本插件**(`SpelResolver`) |
| 补全 | 本插件(注册给 `SpEL` 语言) |
| 引用解析 / 跳转 | 本插件注册的 `PsiReferenceProvider` |

为什么不直接用平台那套:SpEL 的 EL 上下文是**由执行注入的一方**在注入时建立的(Spring 自己的注入器做的事),而它只对硬编码的那批 Spring 注解生效。自定义注解拿不到上下文,`#user` 就永远解析不了 —— 所以 root 这一层必须自己做。

代码结构:

```
src/main/kotlin/io/github/quiethappiness/spelhelper/
├── detect/SpelHosts.kt              判定「这个字面量要不要处理」的唯一入口
├── expr/SpelText.kt                 纯文本解析(无 PSI 依赖,可单测)
├── resolve/SpelResolver.kt          名字 → Java PSI
├── inject/SpelAnnotationInjector.kt 注入 SpEL
├── completion/                     补全
├── ref/                            引用(消除「未解析」并支持跳转)
├── nav/                            Ctrl+点击(兜底)
└── settings/                        设置页与持久化
```

## 开发

```bash
gradlew.bat buildPlugin   # 出 build/distributions/*.zip
gradlew.bat test          # 跑解析器单测(纯逻辑,秒级)
gradlew.bat runIde        # 起一个沙箱 IDE 调试验证
```

改 `expr/SpelText.kt` 之后**先跑 `test`** —— 那里有 23 个用例覆盖属性链、方法/索引段、`?.`、运算符边界、`T(` 内前缀、`#{...}` 片段提取,几十毫秒,能挡住大部分低级错误。

### 两处不要动

看起来像多余配置,实际是承重的:

- `plugin.xml` 里 `completion.contributor` 上的 **`order="first"`**,配合代码里的 `result.stopHere()` —— 少了它,补全列表每项出现两次,还会混进 SpEL 自带贡献者的噪音。
- 引用注册时的 **`PsiReferenceRegistrar.HIGHER_PRIORITY`** —— SpEL 自带贡献者会先返回一个**解析不了**的引用占位,不抢先的话「未解析变量」的黄线去不掉。