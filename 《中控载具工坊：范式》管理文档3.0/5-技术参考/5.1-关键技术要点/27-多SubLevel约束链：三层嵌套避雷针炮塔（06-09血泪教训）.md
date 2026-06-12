# 27. 多 SubLevel 约束链：三层嵌套避雷针炮塔（06-09 血泪教训）

### 问题描述
将避雷针（炮管）作为独立的 SubLevel 叠加在砂轮（炮塔底座）上，砂轮再通过 RotaryConstraint 连接到载具，形成三层约束链：
```
载具 ── RotaryConstraint(Y) ── 砂轮 ── GenericConstraint(全锁定) ── 避雷针
```
避雷针反复出现：消失、飞走、撕裂载具、拉向地心。

### 核心教训

#### 1️⃣ 两个 SubLevel 完全重叠是可行的 ✅
```
之前: 认为"两个刚体在同一位置 = 数值奇点"，偏移 1 格    ❌
现在: 完全同一位置，setContactsEnabled(false) 禁碰撞     ✅
```
**原因**：Rust 侧 `joint.tick()` 计算 `local_anchor = pos_a - center_of_mass`。对于单方块 SubLevel，`pos_a = getCenterBlock()+0.5`，`center_of_mass ≈ getCenterBlock()+0.5`，所以 `local_anchor = (0,0,0)`。两个锚点都在各自质心 → 零偏移 → 零静态力。

#### 2️⃣ 所有 SubLevel 必须使用 **完全一致的 pose** ⚠️
```
pipeline.teleport(rodSL, grindstoneSpawnVec, grindstoneOrient);  // ✅ 与砂轮同位置同姿态
pipeline.teleport(rodSL, grindstoneSpawnVec, identity);           // ❌ 姿态不同 = 约束轴错位
```
约束系统将 `normal`/`pos` 通过各 SubLevel 的 `logicalPose()` 变换到世界空间。**姿态不同则两个 normal 指向不同方向 → 约束自相矛盾 → 撕碎载具。** 所有共享约束轴的 SubLevel 必须保持 orientation 一致。

#### 3️⃣ 不需要在间接连接的体之间加额外约束 🧹
```
之前: 避雷针↔载具 FreeConstraint（防碰撞）    ❌ 多余
现在: 避雷针只连接砂轮，约束链自然传递         ✅ 干净
```
物理引擎的 impulse solver 会通过约束链自然传递力。中间体加的额外约束可能引入意外冲量。

#### 4️⃣ GenericConstraint 比 FixedConstraint 更稳定 🔧
| 约束类型 | Rust 实现 | rotation_a | tick 中更新旋转 | 稳定性 |
|---------|-----------|:----------:|:--------------:|:------:|
| `FixedConstraint` | `FixedJointBuilder::new()` | `None` | ❌ | ❌ 飞走 |
| `GenericConstraint`(全锁定) | `GenericJointBuilder::new(all)` | `Some(identity)` | ✅ | ✅ 稳定 |

`FixedJointBuilder` 是 Rapier 专用 0-DOF 关节，不更新 `local_frame.rotation`。而 `GenericJointBuilder` 与 `RotaryConstraint`（`RevoluteJointBuilder`）同属通用关节家族，tick 中会正确更新旋转帧。

### 完整成功配方

```java
// ═══════════════════════════════════════════════════════
//  SubLevel 1: 砂轮（炮塔底座）
// ═══════════════════════════════════════════════════════
Pose3d pose = new Pose3d();
pose.position().set(grindstoneSpawnVec);
pose.orientation().set(grindstoneOrient);
ServerSubLevel grindstoneSL = (ServerSubLevel) container.allocateNewSubLevel(pose);
initSingleBlockSubLevel(grindstoneSL, GRINDSTONE_BLOCKSTATE);
pipeline.teleport(grindstoneSL, grindstoneSpawnVec, grindstoneOrient);
grindstoneSL.updateLastPose();

// 砂轮↔载具 RotaryConstraint (Y 轴方向机)
BlockPos gc = grindstoneSL.getPlot().getCenterBlock();
Vector3d pos1 = new Vector3d(gc.getX()+0.5, gc.getY()+0.5, gc.getZ()+0.5);
Vector3d pos2 = new Vector3d(carpetPos.getX()+0.5, carpetPos.getY()+0.5, carpetPos.getZ()+0.5);
RotaryConstraintConfiguration rotaryConfig = new RotaryConstraintConfiguration(
    pos1, pos2,
    new Vector3d(0, 1, 0),
    new Vector3d(0, 1, 0)
);
handle = pipeline.addConstraint(grindstoneSL, vehicleSL, rotaryConfig);
handle.setContactsEnabled(false);

// ═══════════════════════════════════════════════════════
//  SubLevel 2: 避雷针（炮管）— 同一位置，同一姿态
// ═══════════════════════════════════════════════════════
Pose3d poseB = new Pose3d();
poseB.position().set(grindstoneSpawnVec);          // ← 与砂轮相同！
poseB.orientation().set(grindstoneOrient);         // ← 与砂轮相同！
ServerSubLevel rodSL = (ServerSubLevel) container.allocateNewSubLevel(poseB);
initSingleBlockSubLevel(rodSL, ROD_BLOCKSTATE);
pipeline.teleport(rodSL, grindstoneSpawnVec, grindstoneOrient);  // ← 与砂轮相同！
rodSL.updateLastPose();

// 避雷针↔砂轮 GenericConstraint（全轴锁定 = 刚性连接）
BlockPos rc = rodSL.getPlot().getCenterBlock();
Vector3d posA = new Vector3d(rc.getX()+0.5, rc.getY()+0.5, rc.getZ()+0.5);
BlockPos gc2 = grindstoneSL.getPlot().getCenterBlock();
Vector3d posB = new Vector3d(gc2.getX()+0.5, gc2.getY()+0.5, gc2.getZ()+0.5);
GenericConstraintConfiguration bindConfig = new GenericConstraintConfiguration(
    posA, posB,
    new Quaterniond(), new Quaterniond(),
    EnumSet.allOf(ConstraintJointAxis.class)
);
barrelHandle = pipeline.addConstraint(rodSL, grindstoneSL, bindConfig);
barrelHandle.setContactsEnabled(false);

// ✅ 不需要避雷针↔载具的任何约束！
// 物理引擎通过约束链自行处理：载具─Rotary─砂轮─Generic─避雷针
```

### 运行时微调：四组约束锚点偏移配置

所有约束锚点坐标 = `getCenterBlock() + 0.5 + 配置偏移`。可在 `config/iac_p-common.toml` 中微调：

```toml
[turret.constraint_anchors.rod_anchor]
offsetX = 0.0  # 避雷针(炮管)端 X 偏移
offsetY = 0.0  # Y 偏移（正=上/负=下）
offsetZ = 0.0  # Z 偏移

[turret.constraint_anchors.grindstone_rod_anchor]
offsetX = 0.0  # 砂轮端(避雷针侧) X 偏移
offsetY = 0.0
offsetZ = 0.0

[turret.constraint_anchors.grindstone_swivel]
offsetX = 0.0  # 砂轮端(方向机) X 偏移
offsetY = 0.0
offsetZ = 0.0

[turret.constraint_anchors.vehicle_carpet_anchor]
offsetX = 0.0  # 载具(地毯)端 X 偏移
offsetY = 0.0
offsetZ = 0.0
```

各锚点含义（见 `Config.java` 中 `turret.constraint_anchors` 节）：

| 配置路径 | 对应约束 | 备注 |
|---------|---------|------|
| `rod_anchor` | rod↔grindstone GenericConstraint pos1 | 避雷针上的约束点 |
| `grindstone_rod_anchor` | rod↔grindstone GenericConstraint pos2 | 砂轮上连接避雷针的点 |
| `grindstone_swivel` | grindstone↔vehicle RotaryConstraint pos1 | 砂轮 Y 轴旋转中心 |
| `vehicle_carpet_anchor` | grindstone↔vehicle RotaryConstraint pos2 | 载具地毯端的旋转中心 |

### 关键检查清单

当添加新的叠加 SubLevel 时，逐一检查：

1. [ ] **pose 一致性**：新 SubLevel 的 position 和 orientation 是否与相邻 SubLevel 完全一致？
2. [ ] **contact 禁用**：是否调用了 `setContactsEnabled(false)`？
3. [ ] **无多余约束**：是否只在直接相邻的 SubLevel 之间加约束？（不要跨级加）
4. [ ] **约束坐标**：是否都用了 `getCenterBlock() + 0.5` + 配置偏移？
5. [ ] **Generic 优先于 Fixed**：刚性连接优先用 `GenericConstraint(全锁定)` 而非 `FixedConstraint`
