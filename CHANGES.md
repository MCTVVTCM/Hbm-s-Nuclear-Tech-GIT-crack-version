# Fork changes — `portal-gun` branch

This branch of [Hbm's Nuclear Tech Mod](https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT) adds a
Rick-and-Morty style portal gun, fixes the threaded MK5 explosion raytracer, and ships a small
hot-reload workflow for 1.7.10 development.

Everything below is additive: the upstream content, recipes and worldgen are untouched unless a
section says otherwise.

---

## 1. 传送门枪 / Portal gun

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `items/weapon/ItemPortalGun.java` | 物品逻辑：射线检测、放置、配对、清门 |
| `entity/effect/EntityPortal.java` | 传送门实体：朝向、判定、传送、动量 |
| `render/entity/effect/RenderPortal.java` | 传送门渲染（矩阵朝向 + 漩涡贴图 + 发光核心） |
| `render/item/weapon/ItemRenderPortalGun.java` | 第一/第三人称 OBJ 模型渲染 |
| `render/HudPortalSpeed.java` | 右下角实时速度 HUD |
| `assets/hbm/models/portal_gun.obj` | 枪模型（828 面，5 个分组） |
| `assets/hbm/textures/models/portal_gun.png` | 枪贴图集 |
| `assets/hbm/textures/entity/portal.png` | 传送门漩涡贴图 |
| `assets/hbm/textures/items/portal_gun.png` | 物品图标 |
| `tools/gen_portal_gun.py` | 模型与贴图生成脚本，改形状后重跑即可 |

### 机制

- **两个槽位**：右键交替放置 A / B，第三发会移动 A；蹲下 + 右键关闭整对。
- **贴面放置**：支持全部六个面；墙面门底对齐方块底面，地板/天花板门贴地，
  打在高草等无碰撞方块上会自动下探到实体地面。
- **配对**：entity id 存在枪的 NBT 里，实体失效时自动与最近的另一个门重新配对。
- **传送判定**：
  - 墙面：距离门平面 0.55 格内即触发（墙是实心的，无法靠“穿过平面”触发）
  - 地板/天花板：进入开口、落地预测、位移线段扫掠三种判定
  - 出口位置被方块挡住时会尝试沿法线外推、邻域搜索、门心回退
- **动量**：入射速度分解到源门坐标系，法向分量取反、切向保留，再映射到目标门坐标系。
  出射速度 = 入射速度 × `SPEED_GAIN` + 沿出口法线的 `EXIT_BOOST`。
- **摔落免伤**：传送后 2 秒内免疫摔落；玩家在门框上方/前方坠落时坠落距离持续清零。
- **视角**：出射时 yaw 随两门法线差旋转，保证“前方”始终是飞出方向。

### 可调参数

`EntityPortal.java` 顶部：

```java
private static final double EXIT_BOOST   = 0.35D;  // 出口固定推力（格/tick）
private static final double SPEED_GAIN   = 1.00D;  // 每趟倍率，1.0 = 严格守恒
private static final int    TELEPORT_COOLDOWN = 3; // 同一实体两次传送最小间隔（tick）
private static final long   EXIT_GUARD_TIME   = 40L; // 出口保护时间（tick）
```

`ItemPortalGun.RANGE` 控制射程（默认 64）。原版 `rayTraceBlocks` 有 200 步上限，
这里做了分段续接，最大有效距离 512 格；未加载区块仍然打不到。

### 合成

```
PEP
MGM
 B
```

`P` 枪金属板、`E` 末影珍珠、`M` 枪金属机构、`G` 玻璃、`B` 钢块。

---

## 2. 多线程核爆修复 / Threaded MK5 blast

`explosion/ExplosionNukeRayParallelized.java` 原本存在但被禁用（`EntityNukeExplosionMK5`
无条件走旧的 `ExplosionNukeRayBatched`）。本次修改：

- 修复 **停等丢任务竞态**：主线程在 `snapshots.get()` 与 `waitQueue.add()` 之间摘走等待队列时，
  射线会被塞进一个没人再读的孤儿队列，latch 永远减不到 0，爆炸实体永不消失。
  改为 `ConcurrentHashMap.compute` 原子交接，并在插入后复查快照。
- 修复 **空 sub-chunk 位图不清零** 导致的 `destructionMap` 永不排空。
- 每个射线只 `countDown` 一次（`finished` 标记）。
- 快照改为原始 12-bit 方块 ID 数组拷贝，实测比逐方块查调色板快 **约 52 倍**，主线程开销大幅下降。
- 射线热路径优化：跨 sub-chunk 才查一次 map、复用 `SubChunkKey`、按 `Block` 缓存抗性、
  缓存当前 chunk 的输出容器、工作线程数可用 `-Dhbm.blastThreads=N` 限制。
- `EntityNukeExplosionMK5` 按 `hbm.cfg` 的 `6.06_explosionAlgorithm` 选择实现
  （0 = 旧算法，1/2 = 多线程），启动失败时自动回退，不会让爆炸消失。

---

## 3. 开发工具

`devtools/`（不参与模组打包）：

| 脚本 | 用途 |
| --- | --- |
| `run-client.sh` | 离线启动开发客户端（DCEVM JDK 8 + UTF-8 + 可选 JDWP） |
| `hot-reload.sh` | 增量编译并通过 JDWP 把变化的 class 重定义进运行中的游戏 |
| `watch.sh` | Gradle 持续编译（会占用工程锁，注意别和 IDEA 同步同时开） |
| `log.sh` | 跟踪 `eclipse/logs/fml-client-latest.log` |

> 注意：`./gradlew jar` 会重写客户端正在加载的 `build/libs/HBM-NTM-*.jar`，
> 运行中重打包会让 JVM 加载尚未加载的类时抛 `NoClassDefFoundError`。
> 改代码请优先热重载，需要重打包时先关游戏。

---

## 4. 构建

- **JDK 8**（ForgeGradle 1.2 + Gradle 4.6，不接受更高版本）
- Gradle wrapper 4.6

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk
./gradlew setupDecompWorkspace      # 首次
./gradlew build                     # 产物在 build/libs/
./gradlew runClient                 # 开发客户端
```

---

## 5. 已知限制

- **射线只看已加载区块**：远处未加载区域在射线里等同空气，射程再大也打不到。
- **地面摩擦**：面对面的墙面门在平地上循环时，落地摩擦会在 2 格内吃掉水平速度；
  想跑循环请把门建在空中，或拉开高度差。
- **出口保护 2 秒**：刚出来的门在 2 秒内不会再次触发（防止来回弹）。
- **速度太快会穿方块**：超过约 10 格/tick 后碰撞检测可能漏判。

---

## 6. 许可

与上游一致：见 `LICENSE` 与 `LICENSE.LESSER`。本分支的改动同样适用。
