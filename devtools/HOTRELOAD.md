# HBM's NTM（Minecraft 1.7.10）开发环境 + 热重载手册

> 状态：**已搭好并实测通过**
> - `setupDecompWorkspace` ✅（反编译工作区就绪）
> - `compileJava` ✅（4755 个 class）
> - `runClient` ✅（客户端正常启动，HBM's NTM + NEI + CodeChickenCore 全部加载，能进世界）
> - `build` ✅ → `build/libs/HBM-NTM-[1.0.27_X5863].jar`（55 MB）

---

## 1. 环境清单

| 组件 | 位置 / 版本 | 说明 |
| --- | --- | --- |
| 编译 / Gradle JDK | 系统 **OpenJDK 1.8.0_504** | ForgeGradle 1.2 只认 Java 8 |
| 运行 / 调试 JDK | **TravaJDK 8u282 DCEVM** → `~/.jdks/trava-jdk-8-dcevm-8u282` | 热替换可「增删方法 / 字段」（普通 Java 8 只能改方法体） |
| Gradle | wrapper **4.6** | 原来 4.4.1，IDEA 2026 要求 ≥ 4.5.1 |
| 构建插件 | `com.anatawa12.forge:ForgeGradle:1.2-1.0.12` | FG 1.2 社区维护 fork |
| Forge | `1.7.10-10.13.4.1614-1.7.10` | 与你现在正式客户端一致 |
| 运行目录 | `eclipse/`（`runDir`） | 存档、config、日志；已复制你的 `options.txt`（zh_CN） |
| 依赖缓存 | `~/.gradle/caches`（≈1.5 GB） | 删掉要重跑 `setupDecompWorkspace` |

开发客户端加载的模组：**HBM's NTM + NotEnoughItems + CodeChickenCore**（`build.gradle` 里的依赖，方便调试兼容性），**没有 OptiFine**。

---

## 2. 常用命令

```bash
./gradlew runClient                 # 启动客户端（游戏里就是你的模组）
./gradlew runServer                 # 启动服务端
./gradlew build                     # 出包 → build/libs/HBM-NTM-[1.0.27_X5863].jar
./gradlew compileJava               # 只编译（约 10 秒，热编译反馈）
./devtools/run-client.sh            # 封装版：带 DCEVM + UTF-8
./devtools/run-client.sh -Pxmx=6G   # 大内存
./devtools/run-client.sh -PdebugPort=5005   # 额外开 JDWP，供 IDEA Attach
./devtools/run-client.sh --online   # 需要联网时（新增依赖 / 首次下载）
./devtools/hot-reload.sh            # 自动热重载（另一个终端，见 3.2.2）
./gradlew --offline runClient        # 断网时
```

> ⚠️ `./devtools/watch.sh` 是 Gradle **持续编译**（`-t`），它会**一直停在
> "Waiting for changes to input files..."** 并占着工程的 Gradle 锁：
> 这期间 IDEA 同步、别的 `gradlew` 命令都会排队等待，看起来像「卡死」。
> 日常用 IDEA 的 `Ctrl+F9` 就够了，**不要**一边开 watch 一边让 IDEA 同步。
> 真要用，等 IDEA 同步完再开，用完 `Ctrl+C`。

---

## 3. 热重载怎么用

### 3.0 先搞清楚：改什么能热更，什么不能

| 改动内容 | 生效方式 | 重启？ |
| --- | --- | --- |
| 贴图 png / 模型 json / lang / ogg 音效 | 同步资源 + 游戏内 **F3+T** | 否 |
| 方法体、常量、**增删字段、增删方法** | IDEA **Debug** + `Ctrl+F9`（DCEVM） | 否 |
| 构造器、父类 / 接口、注解、枚举项 | — | **要** |
| 注册表：新方块 / 新物品 / 配方 / 创造标签页 / GUI 注册 | — | **要**（1.7.10 只在启动时读一次） |

### 3.1 资源热重载（贴图 / 模型 / 语言 / 音效）—— F3+T

资源加载链路：

```
src/main/resources/ --processResources--> build/resources/main/ --classpath--> 游戏
```

1. 改 `src/main/resources/assets/hbm/...` 下的文件；
2. `./gradlew processResources`（几秒钟；见上面关于 `watch.sh` 的警告）；
3. 进游戏按 **F3+T**。

注意：新建文件第一次可能要按两次；已打开的 GUI 里缓存的贴图要关掉界面重开。

### 3.2 代码热重载（Java）—— 推荐流程（实测可用）

**方式 A（推荐，最稳）：脚本启动 + IDEA Attach**

```bash
# 终端 1
./devtools/run-client.sh -PdebugPort=5005
```

```text
# IDEA 里
Run > Attach to Process...   （或直接运行预置配置 "Attach to Dev Client (5005)"）
```

Attach 上以后：

1. 改任意 `.java` 文件；
2. `Ctrl+F9`（Build Project，只编译改动的类，几秒）；
3. IDEA 自动把变化的类热替换进正在跑的游戏 —— 不重启、不掉存档。

**方式 B：让 IDEA 直接跑 Gradle 任务**

预置配置 **"Run Client (Gradle)"** 用 **Debug** 启动（IDEA 会自动附加调试器到 Gradle fork 出来的游戏进程），然后同上。

> DCEVM 现在由 **`build.gradle`** 直接注入（以前只写在 `devtools/hotreload.gradle`
> 这个 init script 里，必须带 `-I` 才生效，直接 `./gradlew runClient -PdebugPort=5005`
> 是不认的，游戏会用默认 JVM 启动，热替换只能改方法体）。
>
> 现在任何方式启动都走同一套配置，日志会打印：
>
> ```
> [hotreload] runClient: JVM = ~/.jdks/trava-jdk-8-dcevm-8u282/bin/java (DCEVM)
> [hotreload] runClient: JDWP 127.0.0.1:5005
> ```
>
> 常用写法：
>
> ```bash
> ./gradlew runClient --offline                       # DCEVM，但不开 JDWP
> ./gradlew runClient --offline -PdebugPort=5005      # DCEVM + JDWP，IDEA Attach 后可热替换
> ./gradlew runClient --offline -Pxmx=6G              # 加大堆
> ./gradlew runClient --offline -PdcevmJava=/path/to/dcevm/bin/java
> ```
>
> `devtools/hotreload.gradle` 已改成空壳（保留只为兼容旧的 `-I` 写法），
> 里面不再重复加 JVM 参数——否则 `-agentlib:jdwp` 会出现两次，第二个绑不上端口。

**热替换能力边界**

- ✅ 方法体、常量值、新增 / 删除方法、新增 / 删除字段（新字段是默认值 `null`/`0`/`false`，小心 NPE）
- ⚠️ 已存在的对象不会重跑构造器；`static {}` 只执行一次
- ❌ 构造器签名、父类 / 接口、注解、枚举项增删
- ❌ 注册表内容（`GameRegistry`、`CraftingManager` 配方、创造标签页）

**判断是否真的在增强模式**：如果 IDEA 报
`class redefinition failed: attempted to add a method`，说明跑的不是 DCEVM；
检查日志里有没有上面那行 `[hotreload] 游戏 JVM: ... (DCEVM...)`。

### 3.2.1 这套热替换在本机实测过

| 测试 | 过程 | 结果 |
| --- | --- | --- |
| 合成测试（证明可增删方法） | DCEVM 上跑一个循环打印 `value`，然后用 JDI 把 `value()` 换成 `2 + extra()`，`extra()` 是**新方法** | 打印从 `value=1` 直接变成 `value=102`，进程没重启 |
| 真实游戏测试 | 启动开发客户端（JDWP 5009），attach 后用 JDI 重定义 `com.hbm.main.MainRegistry`，字节码里**多了一个原版没有的 `hotswapProbe()` 方法** | `REDEFINED com.hbm.main.MainRegistry`，VM 接受，游戏继续运行 |

也就是说：**mod 的类可以在跑着的游戏里被换掉，连加方法都行**。
（普通的 Java 8 HotSwap 只允许改方法体，加方法会直接报错 —— 所以这里必须用 DCEVM。）

### 3.2.2 自动热重载（推荐，不依赖 IDEA 的 Debug）

IDEA 的增量编译/热替换对「只改了一行常量」这种情况经常不给力，而且失败时没有提示。
用这个脚本可以完全绕开 IDE：

```bash
# 终端 1：带调试端口启动游戏
./devtools/run-client.sh -PdebugPort=5005

# 终端 2：开始监听
./devtools/hot-reload.sh
```

然后正常改 `.java` 保存即可，脚本会：**增量编译 → 按文件哈希找出真正变了的类 → 用 JDI 重定义进游戏**，
并逐个打印结果：

```
[hot-reload] 检测到改动，编译中…
[hot-reload] OK   com.hbm.main.MainRegistry
[hot-reload] [hotswap] 替换成功 1 个，未加载 0 个，失败 0 个
```

- `OK` → 已生效
- `PEND ...（游戏里还没加载这个类）` → **不是失败，也不用管**：磁盘上的 `.class` 就是它
  第一次被加载时读到的东西，改动已经在里面了。
  - 懒加载的类（渲染器 / 工具类 / 某个 GUI 才用到的类）第一次用到时自然就是新版本；
  - 只有**靠 init 期注册才会加载**的类（新实体 / 方块 / 物品 / 配方 / 渲染注册）才必须重启 ——
    注册那一步早就跑完了，注册表里根本没有它，这跟热替换无关。
- `FAIL` → JVM 拒绝（构造器签名/继承关系等），只能重启

> 主推送结束后还会为 PEND 的类补推几轮（`HOT_RETRY_ROUNDS`，默认 5 轮 × 300ms）：
> 分批推送期间游戏可能刚好把它加载起来，那一次才算真换。
> `HOT_RETRY_ROUNDS=0` 关掉补推，`HOT_RETRY_MS=1000` 拉长间隔。

**为什么这样连常量都能改**：`public static final int CHARGE_TIME = 5 * 60 * 1;` 这种编译期常量
会被 javac 内联到**每一个引用它的类**里，只重编译定义它的类没用。Gradle 的增量编译会连带重编译
依赖方，脚本再按内容哈希把这些类一起推过去 —— 所以常量也能真正生效。

实际跑出来的样子（把 `RefStrings.VERSION` 改了一个数字）：

```
[hot-reload] 有 6 个类内容变了，分块替换（每批 25 个，批间隔 100ms）
[hot-reload] OK   [1/6] com.hbm.handler.HTTPHandler
[hot-reload] PEND [2/6] com.hbm.main.NEIConfig   (还没被加载：不用管，磁盘上已是新版本，它第一次被用到时就是新的)
[hot-reload] PEND [5/6] com.hbm.lib.RefStrings   (还没被加载：不用管，磁盘上已是新版本，它第一次被用到时就是新的)
[hot-reload] OK   [6/6] com.hbm.main.MainRegistry
[hot-reload] [hotswap] 共 6 个：已替换 4，未加载 2，失败 0（每批 25 个）
```

注意 **`RefStrings` 自己反而是 PEND** —— 纯常量类根本没被加载，真正让改动生效的是引用它的那 4 个类。
这就是「改常量没反应」的真相：要换的是**引用方**，不是常量所在的类。

推送按**块**做，默认每批 25 个、批间隔 100ms。

为什么不是「一个类一个类」或者是「全部一次」：

- 每次 `redefineClasses` 都是一次 JVM 级操作，必须让**所有线程停在 safepoint** 并把已编译代码去优化。
- 一个类一次调用 = 每次改动 N 个类就付 N 次停顿 → 游戏卡顿；
- 几千个类一次调用 = 一次超长停顿 → 直接卡成 PPT。
- 所以折中：一次调用提交 25 个（只付一次 safepoint），批与批之间停 100ms 让游戏跑几帧。

可以按需调：

```bash
HOT_SWAP_CHUNK=100 HOT_SWAP_DELAY_MS=50 ./devtools/hot-reload.sh   # 更快，停顿更明显
HOT_SWAP_CHUNK=1   HOT_SWAP_DELAY_MS=200 ./devtools/hot-reload.sh  # 最温柔
```

**波及面过大时会自动放弃推送**（默认阈值 300 个类）：

```
[hot-reload] ✗ 本次有 4127 个类的字节码变了（阈值 300），已跳过推送
[hot-reload]   受影响最多的包：
[hot-reload]          3821 com/hbm/blocks
[hot-reload]           201 com/hbm/items
[hot-reload]   建议直接重启客户端
```

出现这种情况，说明你改的东西在**字节码层面**真的影响了这么多类，通常是：
- 改了被大量 `import` 并内联的 `public static final` 常量；
- 改了公共接口 / 父类的方法签名；
- 改了 `@Mod` 之类会被写进很多类的元信息。

这时热替换没有意义（光去优化就要几十秒），直接重启。确认无误想硬推：
`HOT_MAX_PUSH=99999 ./devtools/hot-reload.sh`。

> 顺带说明：这个判断不是"编译时间变了"，而是逐字节 md5 比较 —— 实测把整个
> `build/classes` 删掉全量重编译，4756 个类的哈希 **0 差异**，所以报出来的数量是可信的。

> 监听只认 `*.java` 和 `src/main/resources/` 下的真实文件，自动忽略编辑器临时文件
> （Kate 的 `.xxx.java.kate-swp`、`*~`、`*.swp`、`.#*`、隐藏文件等），不会因为编辑时的交换文件被反复触发。

> ⚠️ 编译产物按 **md5** 判断变化，不是按编译时间。Gradle 增编时经常会把一大片内容没变的类重新写一遍，
> 按时间判断会把几千个类一次性推给游戏，瞬间卡成 PPT（我第一版就是这么把客户端搞卡的）。
> 如果你一次改了基类导致 `>100` 个类真的变了，脚本会警告，建议直接重启客户端。

**两个实际会踩到的坑：**

- **`public static final String` 常量改了可能不生效**：编译期常量会被内联进所有引用它的类，
  只重编译常量所在的类没用 —— 要么改的是方法/逻辑，要么让 IDEA 重新编译引用方。
- **`RefStrings` 这种纯常量类可能根本没被加载**（引用处已内联），热替换会提示「class not loaded」。
  这种类改了本来也不需要热替换。

### 3.3 看服务端 / 客户端日志

1.7.10 单人游戏里的「服务端」是**内置服务端**，没有独立控制台窗口，它的输出和客户端写在同一份日志里，
靠线程名区分：

```
[21:39:17] [Client thread/INFO]: ...
[21:39:17] [Server thread/INFO]: ...
```

日志文件都在运行目录：

| 文件 | 内容 |
| --- | --- |
| `eclipse/logs/latest.log` | 全部日志（客户端 + 内置服务端），最常看 |
| `eclipse/logs/fml-client-latest.log` | FML/Forge 的完整加载日志、模组列表、transform 记录 |
| `eclipse/logs/<日期>-N.log.gz` | 历史日志（每次启动滚动一次） |

实时看：

```bash
./devtools/log.sh              # 全部
./devtools/log.sh server       # 只看服务端线程 ← 你要的
./devtools/log.sh err          # 只看 WARN / ERROR / 异常栈
./devtools/log.sh hbm          # 只看包含某个关键字的行
```

也可以直接用系统工具：

```bash
tail -f eclipse/logs/latest.log
tail -f eclipse/logs/latest.log | grep -E '\[Server'
```

**要看 DEBUG 级别**：把 `devtools/log4j2-debug.xml` 复制过去再重启：

```bash
cp devtools/log4j2-debug.xml eclipse/log4j2.xml
```

里面只给 NTM（logger 名 `HBM`）开了 debug；想连 FML 一起看就把注释掉的那段打开。
不想用了删掉 `eclipse/log4j2.xml` 即可。

**真正的专用服务端**（多人测试）用：

```bash
./devtools/run-server.sh          # 控制台直接打印，日志同样在 eclipse/logs/latest.log
./devtools/run-server.sh --online # 需要联网时
```

服务端崩溃/卡死的堆栈会直接打在上面这个日志里；想看某个值的实时变化，最省事的是在服务端线程里
`System.out.println` 或 `LogManager.getLogger("HBM").info(...)`，都会落到这份日志。

### 3.4 什么时候只能重启

改了注册表/配方/新方块这类东西，直接：

```bash
./devtools/run-client.sh -Pxmx=6G
```

`eclipse/` 目录会保留，存档（`eclipse/saves`）、配置、按键设置都不丢。

---

## 4. IDEA 配置（打开项目后检查一次）

1. `File > Open` 选 `build.gradle` → *Open as Project*（已把 `.idea/gradle.xml` 指向 wrapper + Gradle JVM 1.8）；
2. `File > Project Structure > Project`：SDK 选 **1.8（Java 8）**；
3. `Settings > Build, Execution, Deployment > Build Tools > Gradle`：
   - **Use Gradle from**：`gradle-wrapper.properties`（Gradle 4.6）
   - **Gradle JVM**：**1.8**（系统 JDK，不要用 Gradle 9）
4. 运行配置里已经有：`Run Client (Gradle)`、`Run Server (Gradle)`、`Attach to Dev Client (5005)`。

> 不要用 `./gradlew genIntellijRuns`：它会把整棵依赖树的 sources/javadoc 下载一遍，
> 还会触发一次完整反编译（我第一次跑就是因为这个卡了 20 分钟）。

---

## 5. 和现有正式客户端的关系

你机器上已经有现成的 1.7.10 客户端：

```
~/.minecraft/versions/1.7.10   （Forge 10.13.4.1614 + NTM X5863 + NEI + OptiFine）
```

开发环境是**独立的**（运行目录 `eclipse/`），不会动到它。两种配合方式：

- 想快速验证打包结果：
  ```bash
  ./gradlew build
  cp "build/libs/HBM-NTM-[1.0.27_X5863].jar" "~/.minecraft/versions/1.7.10/mods/"
  ```
- 想在开发客户端里用你的存档：`ln -s ~/.minecraft/versions/1.7.10/saves eclipse/saves`
  （**注意**：开发环境没有 OptiFine，且存档里如果有别的模组内容可能不兼容，建议先备份）

---

## 6. 第一次为什么慢 / 踩过的坑

| 卡点 | 原因 | 现状 |
| --- | --- | --- |
| `setupDecompWorkspace` 8 分 40 秒 | 反编译 1.7.10 的 MC + Forge（fernflower 单线程）+ 下载 686 个资源对象 | 已完成，之后不会重跑 |
| 系统 `gradle` 起不来 | 系统装的是 Gradle 9.8，需要 Java 17，而项目要 Java 8 | 一律用 `./gradlew`（Gradle 4.6） |
| IDEA 之前打不开项目 | `.idea/gradle.xml` 指向 `/usr/share/java/gradle`（9.8）+ Gradle JVM 1.8 | 已改成 wrapper |
| `genIntellijRuns` 卡 20 分钟 | 下载全部依赖的 sources/javadoc，并再次触发反编译 | 已放弃该任务，改成手写运行配置 |
| 客户端启动后看不见窗口 | 用 Wayland/XWayland（`DISPLAY=:1`），已实测可正常渲染 | 正常 |
| `JDWP ... bind failed: 地址已在使用` | 调试端口被占（上局游戏没退干净 / IDEA 还连着） | 换个端口：`-PdebugPort=5011` |
| **等很久没反应，日志停在 `Evaluating root project`** | 构建脚本原来用动态版本 `1.2-1.0.+` + `changing=true`，每次构建都要联网校验；代理抖动时配置阶段会卡死几分钟 | 已固定为 `1.2-1.0.12`；`devtools/*.sh` 默认加 `--offline` |

---

## 7. 回滚

```bash
cd <repo>
git checkout gradle/wrapper/gradle-wrapper.properties   # Gradle 4.6 → 4.4.1
rm -rf devtools .idea/runConfigurations
rm -rf ~/.jdks/trava-jdk-8-dcevm-8u282
```

---

## 8. 本次改动清单

| 位置 | 改动 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.properties` | `4.4.1` → `4.6` |
| `build.gradle` | ForgeGradle 插件 `1.2-1.0.+`(changing) → 固定 `1.2-1.0.12`，去掉构建期联网校验 |
| `devtools/`（新增） | 本手册 + 热重载脚本 |
| `.idea/gradle.xml`（gitignore） | 指向 wrapper、Gradle JVM 1.8 |
| `.idea/runConfigurations/`（gitignore） | 3 个运行配置 |
| `eclipse/`（gitignore） | 运行目录，含 `options.txt`、`resourcepacks` 软链 |
| `~/.jdks/trava-jdk-8-dcevm-8u282` | DCEVM JDK |
| `~/.gradle/gradle.properties` | `org.gradle.jvmargs=-Xmx4g ...` |
