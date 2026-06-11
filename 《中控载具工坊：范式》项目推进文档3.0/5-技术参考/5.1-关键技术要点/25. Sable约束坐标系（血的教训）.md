# 二十五、Sable 约束坐标系（血的教训）

### 核心原则
**约束配置的 pos1/pos2 使用 SubLevel 底层（underlying level）的绝对方块坐标，不是世界坐标，也不是嵌入层相对坐标！**

### 坐标系对照

| 坐标系 | 示例 | 说明 |
|--------|------|------|
| ❌ 世界坐标 | `grindstoneSpawnVec` | 物理引擎在错误位置施力 → 撕碎载具 |
| ❌ 嵌入层相对 | `(0.5, 0.5, 0.5)` | 约束系统可能不应用嵌入层偏移 |
| ✅ 底层绝对方块坐标 | `getCenterBlock() + (0.5, 0.5, 0.5)` | 正确！ |

### 为什么 `(0.5, 0.5, 0.5)` 会误导
`EmbeddedPlotLevelAccessor` 把 `BlockPos.ZERO` 映射到 `plot.getCenterBlock()`——这是访问器层的约定。**但约束系统的坐标解释可能不走这个访问器**，所以 `(0.5, 0.5, 0.5)` 实际上指向了底层坐标 (0.5, 0.5, 0.5)，而非预期的 `getCenterBlock() + (0.5, 0.5, 0.5)`。

### RotaryConstraint 成功配方

```java
// 1. 砂轮生成：teleport 到定位点，姿态与载具一致
pipeline.teleport(grindstoneSL, grindstoneTarget, vehicleOrient);
grindstoneSL.updateLastPose();

// 2. 计算底层绝对方块坐标
BlockPos gc = grindstoneSL.getPlot().getCenterBlock();     // 砂轮方块底层坐标
Vector3d pos1 = new Vector3d(gc.getX()+0.5, gc.getY()+0.5, gc.getZ()+0.5);

Vector3d pos2 = new Vector3d(                              // 地毯方块底层坐标
    this.worldPosition.getX()+0.5,
    this.worldPosition.getY()+0.5,
    this.worldPosition.getZ()+0.5
);

// 3. 创建约束
RotaryConstraintConfiguration config = new RotaryConstraintConfiguration(
    pos1, pos2,
    new Vector3d(0, 1, 0),  // normal1: 砂轮局部 Y 轴
    new Vector3d(0, 1, 0)   // normal2: 载具局部 Y 轴
);
handle = pipeline.addConstraint(grindstoneSL, vehicleSL, config);
handle.setContactsEnabled(false);
```

### 约束类型速查

| 类型 | 配置参数 | 自由度 | 用途 |
|------|---------|:------:|------|
| `FreeConstraint` | `(pos1, pos2, orientation)` | 6 | 仅禁用碰撞 |
| `FixedConstraint` | `(pos1, pos2, orientation)` | 0 | 刚性连接 |
| `RotaryConstraint` | `(pos1, pos2, normal1, normal2)` | 1 旋转 | 铰链/旋转轴承 |
| `GenericConstraint` | `(pos1, pos2, ori1, ori2, lockedAxes)` | 自定义 | 任意轴锁定组合 |
