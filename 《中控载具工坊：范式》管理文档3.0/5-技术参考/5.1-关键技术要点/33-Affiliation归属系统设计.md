---
updated: 2026-06-14
status: current
maintainer: @项目协作者
---

# Affiliation 归属系统设计

> 统一管理游戏中所有物理结构（SubLevel）的归属关系、逻辑分组、射线交互策略和伤害归属链。
> 取代原有散落在 SubLevelOwnership / GRINDSTONE_OWNER_MAP / 各 raycast 站点中的硬编码排除逻辑。

---

## 一、核心概念

### 身份证（AffiliationTag）

每个物理结构（SubLevel）携带一张"身份证"，在注册表中以 `SubLevel UUID → AffiliationTag` 形式索引：

```java
public record AffiliationTag(
    @Nullable UUID ownerId,       // 归属主 ID（玩家 UUID 或载具 UUID）
    @Nullable UUID vehicleId,     // 所属载具 SubLevel UUID（衍生结构有此值）
    @Nullable UUID groupId,       // 逻辑组 ID（如炮塔组，共享耐久池）
    Role role,                    // 角色枚举
    int faction                   // 阵营（未来扩展：0=中立, 1=红, 2=蓝...）
) {}
```

### 角色（Role）

描述物理结构在归属中的角色：

| Role | 说明 | 举例 |
|------|------|------|
| `VEHICLE_BODY` | 载具主体结构 | 驾驶舱、悬挂方块等主结构方块 |
| `TURRET_BASE` | 炮塔底座 | 地毯形底座 |
| `TURRET_YAW` | 方向机 | 砂轮 SubLevel |
| `TURRET_PITCH` | 高低机 | 避雷针 SubLevel |
| `PROJECTILE` | 弹射物（预留） | 未来子弹/导弹 |
| `SENSOR` | 传感器（预留） | 未来探测设备 |
| `UNKNOWN` | 未归类 | 临时/未知结构 |

### 逻辑组（Group）

一组共享生命周期的 SubLevel。组内任意成员被摧毁时，整个组被一并移除。

| 组 | 成员 |
|----|------|
| 炮塔组 G456 | `{炮塔底座, 砂轮(方向机), 避雷针(高低机)}` |

### 射线交互策略（RayPolicy）

针对不同类型的射线，定义与不同角色的 SubLevel 的交互方式：

| 策略 | 含义 |
|------|------|
| `BLOCK` | 正常阻挡（默认） |
| `PENETRATE_AABB` | 穿透外层 AABB，但保留内部方块碰撞检测 |
| `IGNORE` | 完全无视，射线穿透 |
| `DAMAGE` | 阻挡并造成伤害（武器专用） |

---

## 二、架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                      AffiliationRegistry（集中索引）                   │
│                                                                     │
│  subLevelToTag:  Map<UUID, AffiliationTag>    ← 身份证查询           │
│  groupToMembers: Map<UUID, Set<UUID>>         ← 组内成员查询         │
│  playerToVehicle: Map<UUID, UUID>             ← 玩家→载具           │
│  vehicleToGroups: Map<UUID, Set<UUID>>         ← 载具→所有组        │
│                                                                     │
│  getAffiliation(subLevelUUID) → AffiliationTag                       │
│  getAllInGroup(groupId) → Set<UUID>                                  │
│  getVehicleOfPlayer(playerUUID) → UUID                               │
│  getOwnAffiliatedSet(vehicleUUID) → Set<UUID>  ← 载具及其全部衍生    │
│  shouldInteract(rayType, tag) → RayPolicy       ← 策略查询           │
└──────────────────────┬──────────────────────────────────────────────┘
                       │ 世界加载时重建
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   BlockEntity NBT（分布式持久化）                      │
│                                                                     │
│  每个参与归属的 BlockEntity 在自己的 NBT 中记录：                     │
│    - ownerId (玩家/载具UUID)                                         │
│    - vehicleId (所属载具UUID)                                        │
│    - groupId (逻辑组UUID)                                            │
│    - role (角色名)                                                   │
│                                                                     │
│  NBT 是唯一事实来源，Registry 是运行时缓存                             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 三、数据流

### 注册流程（以炮塔装配为例）

```
TurretBaseBlockEntity.assemble()
  │
  ├─ 1. 生成炮塔 groupId（新 UUID，仅服务端）
  │
  ├─ 2. 创建砂轮 SL → AffiliationRegistry.register(
  │      砂轮UUID, Tag{vehicleId, groupId, TURRET_YAW, faction})
  │
  ├─ 3. 创建避雷针 SL → AffiliationRegistry.register(
  │      避雷针UUID, Tag{vehicleId, groupId, TURRET_PITCH, faction})
  │
  ├─ 4. 底座 BE NBT 存储: groupId, vehicleId, role=TURRET_BASE
  ├─ 5. 砂轮 BE NBT 存储: groupId, vehicleId, role=TURRET_YAW  ← 未来
  └─ 6. 避雷针 BE NBT 存储: groupId, vehicleId, role=TURRET_PITCH ← 未来
```

### 查询流程（以武器射线为例）

```
WeaponFireC2SPacket.handle()
  │
  ├─ 1. playerId → registry.getVehicleOfPlayer(playerId) → vehicleUUID
  │
  ├─ 2. vehicleUUID → registry.getOwnAffiliatedSet(vehicleUUID)
  │      → { 载具自身, 底座, 砂轮, 避雷针, ... }
  │
  └─ 3. SableBlockHelper.rayTraceSubLevels(level, from, to,
             exclusions=affiliatedSet, rayType=WEAPON_DAMAGE)
```

### 世界重载恢复

```
世界加载 → 各 BE.read() NBT
  │
  ├─ 底座 BE: 读到 groupId, vehicleId, role → registry.register(底座UUID, tag)
  ├─ 砂轮 BE: 读到 groupId, vehicleId, role → registry.register(砂轮UUID, tag)
  ├─ 避雷针 BE: 读到 groupId, vehicleId, role → registry.register(避雷针UUID, tag)
  │
  └─ 约束重建后 → 全部就绪
```

---

## 四、射线策略矩阵

### 摄像机瞄准射线（CAMERA_AIM）

| ↓目标角色 \ 关系→ | 同车 | 同阵营 | 敌对/中立 |
|:---|---:|:---:|:---:|
| VEHICLE_BODY | PENETRATE_AABB | BLOCK | BLOCK |
| TURRET_BASE | PENETRATE_AABB | BLOCK | BLOCK |
| TURRET_YAW | PENETRATE_AABB | BLOCK | BLOCK |
| TURRET_PITCH | IGNORE | BLOCK | BLOCK |
| PROJECTILE | IGNORE | IGNORE | IGNORE |

**效果**：自己的炮管不挡准星，自己的车体 AABB 不挡准星，但碰到内部方块时停。

### 武器伤害射线（WEAPON_DAMAGE）

| ↓目标角色 \ 关系→ | 同车 | 同阵营 | 敌对/中立 |
|:---|---:|:---:|:---:|
| VEHICLE_BODY | IGNORE | IGNORE | BLOCK+DAMAGE |
| TURRET_BASE | IGNORE | IGNORE | BLOCK+DAMAGE |
| TURRET_YAW | IGNORE | IGNORE | BLOCK+DAMAGE |
| TURRET_PITCH | IGNORE | IGNORE | BLOCK+DAMAGE |
| PROJECTILE | IGNORE | IGNORE | IGNORE |

**效果**：不打自己和友军。

---

## 五、接口设计

### AffiliationRegistry（核心类）

```java
public final class AffiliationRegistry {

    // === 注册/注销 ===
    static void register(UUID subLevelUUID, AffiliationTag tag);
    static void unregister(UUID subLevelUUID);
    static void unregisterGroup(UUID groupId);

    // === 查询 ===
    @Nullable static AffiliationTag getAffiliation(UUID subLevelUUID);
    @Nullable static UUID getOwner(UUID subLevelUUID);
    @Nullable static UUID getVehicle(UUID subLevelUUID);
    @Nullable static UUID getGroup(UUID subLevelUUID);
    @Nullable static Role getRole(UUID subLevelUUID);
    static Set<UUID> getAllInGroup(@Nullable UUID groupId);
    static Set<UUID> getOwnAffiliatedSet(@Nullable UUID vehicleUUID);

    // === 玩家-载具绑定 ===
    static void setPlayerVehicle(UUID playerUUID, @Nullable UUID vehicleUUID);
    @Nullable static UUID getPlayerVehicle(UUID playerUUID);

    // === 交互策略 ===
    static RayPolicy resolvePolicy(RayType rayType, AffiliationTag viewer, AffiliationTag target);

    // === 生命周期 ===
    static void onWorldLoad();   // 清空，等待 NBT 重建
    static void clearAll();
}
```

### NBT 序列化

```java
// AffiliationTag 序列化到 CompoundTag
CompoundTag tag = new CompoundTag();
tag.putUUID("OwnerId", ownerId);
tag.putUUID("VehicleId", vehicleId);
tag.putUUID("GroupId", groupId);
tag.putString("Role", role.name());
tag.putInt("Faction", faction);
```

---

## 六、整合路径

### 阶段一：新建 Affiliation 包（当前实施）
- 创建 `affiliation/` 包
- 实现 `AffiliationTag`、`Role`、`RayType`、`RayPolicy`
- 实现 `AffiliationRegistry`
- 编译通过 ✅

### 阶段二：改造 SableBlockHelper.rayTraceSubLevels()
- 新增接受 `RayType` 参数的重载
- 内部调用 `resolvePolicy()` 决定排除/穿透/阻挡
- 保留向后兼容

### 阶段三：改造 TurretBaseBlockEntity
- 装配时生成 groupId
- 注册砂轮/避雷针时附带正确的 Role
- 自己的 NBT 持久化 groupId/vehicleId/role
- 拆卸时通过 `unregisterGroup()` 整组清理

### 阶段四：替换散落逻辑
- `SubLevelOwnership` → 保留为 Registry 的委派
- `WeaponOverlay.raycastGeneric()` → 使用 Registry 查询排除集合
- `WeaponFireC2SPacket` → 使用 Registry 的 `getOwnAffiliatedSet()`

---

## 七、扩展预留

| 扩展方向 | 预留位置 |
|---------|---------|
| 新 Role 类型 | `Role` 枚举直接加，不破坏已有逻辑 |
| 多阵营（红蓝对抗） | `faction` int 字段，0=中立，>0 同值=友军 |
| 弹射物归属 | 发射时传入发射者的 vehicleId + groupId |
| 传感器/探测 | `RayType.SENSOR_SCAN` + `Role.SENSOR` |
| 部件硬度系统 | 在 `AffiliationTag` 或组级别加硬度/伤害减免 |
| 骑乘AI队友 | `faction` + `ownerId` 组合判断 |
