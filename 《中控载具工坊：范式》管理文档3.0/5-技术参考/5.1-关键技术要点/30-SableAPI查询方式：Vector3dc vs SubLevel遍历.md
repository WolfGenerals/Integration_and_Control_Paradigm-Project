---
updated: 2026-06-13
status: archived
maintainer: @项目协作者
---

# Sable API 查询方式：06-13 攻关完整记录

## 问题

部件损坏系统需要根据武器命中位置（世界坐标）找到被击中的 SubLevel 方块。多轮方案均无法实现。

## 已排除的根因

### ✅ 根因 1：getContaining(Vector3dc) 浮点坐标查询

`Sable.HELPER.getContaining(Level, Vector3dc)` 以精确 3D 浮点坐标查空间索引 → 命中点在 AABB 表面，不在索引中 → 返回 null。

### ✅ 根因 2：getContaining(Vec3i) 整数坐标 vs 物理世界坐标

`Sable.HELPER.getContaining(Level, Vec3i)` 以整数 BlockPos 查 **plot grid**。SubLevel 的 plot chunk 坐标（约 2000 万）与物理世界坐标（约 500）不同。武器命中在物理世界坐标(this is where the vehicle appears visually)，plot grid 在 chunk 坐标(this is where blocks are stored)→ 查找失败。

**客户端 P 键测试证实**：
- Minecraft clip 命中 plot chunk 坐标 `(20483080, 129, 20577288)` → `getContaining(BlockPos)` ✅ 找到 SubLevel
- 武器服务端命中物理世界坐标 `(530, 4, 309)` → `getContaining(BlockPos)` ❌ 不在 plot grid 中

### ✅ 根因 3：getAllIntersecting(点BB) 物理 BB 不包含命中点

`getAllIntersecting(BoundingBox3d(hitPos, hitPos))` 以微小 BB 查物理世界。SubLevel 的物理碰撞箱（boundingBox）可能与炮口射线命中点不精确重合 → 遗漏。

### 🆕 根因 4：pose.transformPositionInverse 映射到空气（06-13 最新发现）

即使通过 `SubLevelContainer` 遍历找到了包含 hitPos 的 SubLevel，`pose.transformPositionInverse(hitPos)` 变换后的局部坐标处仍是**空气**。

```
日志证据：
  hitPos=(529.55, 4.40, 309.71)
  SubLevel 2be5c95d 物理 BB 包含该点 ✅
  pose.transformPositionInverse(hitPos) → localBP=(20483080, 127, 20579335)
  level.getBlockState(localBP) → AIR ❌
  
  对比：P 键测试中 cockpit_upper 在 (20483080, 129, 20577288)
  → 变换后的坐标与真实方块位置不匹配（Y:127≠129, Z:20579335≠20577288）
```

**推测原因**：武器 `raycastGeneric` 返回的 hitPos 是射线与 SubLevel AABB 表面的交点（`rayAABBIntersection` 计算得出）。这个表面点变换到局部空间后，落在 SubLevel 边界外的空气上。

## 尝试过的方案

| # | 方案 | 原理 | 结果 |
|---|------|------|------|
| ① | `getContaining(Vector3dc)` 浮点坐标 | 查 Sable 空间索引 | ❌ 表面命中不在索引中 |
| ② | `getContaining(Vector3dc)` + 3×3×3 邻域 | 同上，膨胀搜索 | ❌ 表面命中映射到外部空气 |
| ③ | `SubLevelContainer` 遍历 + `SubLevelScanner.forEachBlock()` | 遍历所有 SubLevel 方块 | ❌ plot chunk 坐标 vs 物理坐标不匹配 |
| ④ | `SableBlockHelper.findSubLevelAt()` 用 `getContaining(Vec3i)` + 26 邻域 | 整数 BlockPos 查 plot grid | ❌ 物理世界坐标不在 plot grid 中 |
| ⑤ | `SableBlockHelper.findSubLevelAt()` 用 `getAllIntersecting(点BB)` + pose 变换 | 物理 BB 判含 + 坐标变换 | ❌ hitPos 不在物理 BB 内 |
| ⑥ | `SableBlockHelper.findSubLevelAt()` 用 `SubLevelContainer` + 物理 BB + pose 变换 | 遍历 + 物理 BB + 坐标变换 | ❌ 变换后局部坐标为空气 |

## 真正有效的路径（参考 SableBridge）

```java
// SableBridge.clipSubLevelsInner() 的做法：
// 1. 用射线 from→to 的完整 BB 获取所有途经 SubLevel
BoundingBox3d bb = new BoundingBox3d(rayFrom, rayTo);
var intersecting = Sable.HELPER.getAllIntersecting(level, bb);

// 2. 对每个 SubLevel，将完整射线变换到局部空间
Vec3 localFrom = pose.transformPositionInverse(rayFrom);
Vec3 localTo = pose.transformPositionInverse(rayTo);

// 3. 在 SubLevel 的 Level 中做 Clip（不是用命中点查）
BlockHitResult localHit = sl.getLevel().clip(
    new ClipContext(localFrom, localTo, COLLIDER, NONE, null)
);

// 4. 将命中位置变换回世界空间
Vec3 worldHit = pose.transformPosition(localHit.getLocation());
```

**关键区别**：不做"给定一个点，找它属于哪个 SubLevel"，而是做**完整射线追踪**——将整个射线变换到 SubLevel 局部空间后重新做 Clip，这样能得到精确的 SubLevel 内部方块命中。

**当前限制**：武器系统（`WeaponFireC2SPacket`）只发送了命中位置（hitPos），未发送射线起点。服务端无法恢复完整的射线。

## 当前的实现文件

- `events/SableBlockHelper.java` — SubLevel 查询工具（包含 `findSubLevelAt` 和 `rayTraceSubLevels`）
- `events/PartDamageCache.java` — 5 击破坏系统
- `client/ClientEvents.java` — P 键测试调试工具

## 参考代码

- `桌面/新建文件夹(2)/tacz_aero_compat-1.8.0.jar` → `SableBridge.clipSubLevelsInner()` — 射线追踪方案
- `sentry_src/euphy/upo/sentrymechanicalarm/compat/AeronauticsHelper.class` — Sable 坐标变换 API
- `sable-companion-common-1.21.1-1.6.0.jar` → `SableCompanion` / `SubLevelAccess` / `ActiveSableCompanion`
