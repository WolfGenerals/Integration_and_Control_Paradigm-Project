---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# SubLevel 射线追踪与归属管理

## 背景

武器系统需要精确命中 SubLevel 物理结构中的方块，同时对战车自身的衍生结构（砂轮、避雷针）免疫伤害。断线重连后炮塔约束需自动恢复。

## 两个子问题

### 问题 A：AABB 表面交点无法映射到 SubLevel 内部方块

武器 `raycastGeneric` 返回的命中点是射线与 SubLevel 物理 AABB 表面的交点。用 `pose.transformPositionInverse(hitPos)` 将该点变换到 SubLevel 局部空间后，落在方块外部的空气上——因为表面交点不在任何方块内部。

### 问题 B：射线包含自身衍生结构

炮塔系统的砂轮（方向机）和避雷针（枪管）是独立的 SubLevel，它们的物理 BB 在射线路径上但不包含射线起点，不会被朴素排除逻辑跳过。

## 解决方案

### 方案 A：SableBridge 完整射线追踪（解决 A）

将完整射线变换到 SubLevel 局部空间后重新 clip，而不是用单点做逆变换。

```java
// 每个 SubLevel：
Pose3dc pose = sl.logicalPose();
Vec3 localFrom = pose.transformPositionInverse(rayFrom);
Vec3 localTo = pose.transformPositionInverse(rayTo);

// 在局部空间做 clip → 精确的方块面命中
BlockHitResult localHit = level.clip(
    new ClipContext(localFrom, localTo, COLLIDER, NONE, ...)
);

// 将命中位置变换回世界空间
Vec3 worldHit = pose.transformPosition(localHit.getLocation());
```

关键区别：
- **旧方案**：给定一个 AABB 表面点，找它属于哪个 SubLevel → ❌ 映射到空气
- **新方案**：给定完整射线，变换到局部空间后重新 clip → ✅ 精确方块面

### 方案 B：SubLevelOwnership 归属管理（解决 B）

新建 `SubLevelOwnership` 系统追踪"载具→衍生 SubLevel"的归属关系：

```
SubLevelOwnership (静态 Map)
  register(subLevelUUID, ownerVehicleUUID)    // assemble() 时调用
  unregister(subLevelUUID)                     // disassemble() 时调用
  getAllOwnedByVehicle(vehicleUUID) → Set<UUID> // 含载具自身 + 所有衍生
```

三重排除保障：
1. **排除集合**（显式 UUID）— 载具自身 + 砂轮 + 避雷针
2. **射线起点 BB 含含**（隐式）— 任何 BB 包含射线的 SubLevel 自动跳过
3. **物理 AABB 快速剔除**（性能）— 射线不经过的 SubLevel 不处理

### 方案 C：断线重连约束重建

`TurretBaseBlockEntity.read()` 中检测 `assembled=true && !clientPacket` 时：
1. 重新注册 `SubLevelOwnership`（砂轮+避雷针→载具）
2. 调用 `reestablishConstraints()` 重建三个约束：
   - 方向机 RotaryConstraint（砂轮↔载具）
   - 高低机 GenericConstraint（避雷针↔砂轮，ANGULAR_X 自由）
   - 碰撞禁用 FreeConstraint（避雷针↔载具）

## 关键文件

| 文件 | 职责 |
|------|------|
| `events/SableBlockHelper.java` | `rayTraceSubLevels()` 核心实现 + `findSubLevelAt()` 回退 |
| `events/PartDamageCache.java` | 5 击摧毁系统 + 双入口（射线追踪 / 回退） |
| `events/SubLevelOwnership.java` | 归属管理静态 Map |
| `network/packets/WeaponFireC2SPacket.java` | 携带射线起点 + 命中点 |
| `client/WeaponOverlay.java` | `fireAllTurrets()` 发送射线起点 |
| `content/blocks/turret/TurretBaseBlockEntity.java` | `assemble/disassemble/read` 中注册/注销/重建 |

## 参考

- `30-SableAPI查询方式：Vector3dc vs SubLevel遍历.md`（攻关全记录，archived）
- `SableBridge.clipSubLevelsInner()`（tacz_aero_compat 参考实现）
