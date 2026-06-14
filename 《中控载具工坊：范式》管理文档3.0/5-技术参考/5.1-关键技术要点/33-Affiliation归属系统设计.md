---
updated: 2026-06-14 (v2：新增事件机制、调试命令、性能监控、非组内衍生收集)
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
| VEHICLE_BODY | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| TURRET_BASE | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| TURRET_YAW | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| TURRET_PITCH | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| WEAPON_CANNON | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| WEAPON_MACHINEGUN | PENETRATE_AABB | PENETRATE_AABB | PENETRATE_AABB |
| PROJECTILE | IGNORE | IGNORE | IGNORE |
| SENSOR | IGNORE | IGNORE | IGNORE |

**效果**：穿过所有物理结构的 SubLevel 碰撞箱表面（灰色/红色线框），但保留命中内部方块自身碰撞箱的能力。弹射物和传感器等无内部方块的虚拟结构则完全穿透。

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

---

## 八、06-14 增强功能

### 8.1 AffiliationChangeEvent（归属变更事件）

**文件**：`affiliation/AffiliationChangeEvent.java`

当注册表状态发生变化时，通过 `NeoForge.EVENT_BUS.post()` 广播事件，解耦其他模块：

| ChangeType | 触发时机 | 携带数据 |
|-----------|---------|---------|
| `REGISTER` | 新 SubLevel 注册归属标签 | subLevelUUID, tag |
| `UNREGISTER` | SubLevel 注销归属标签 | subLevelUUID, oldTag |
| `UNREGISTER_GROUP` | 整组注销 | groupId |
| `PLAYER_BIND` | 玩家-载具绑定/解绑 | playerUUID, vehicleUUID |

**监听示例**：
```java
@SubscribeEvent
public static void onAffiliationChange(AffiliationChangeEvent event) {
    if (event.getChangeType() == AffiliationChangeEvent.ChangeType.UNREGISTER_GROUP) {
        IACP.LOGGER.info("炮塔组 {} 已被摧毁", event.getGroupId().toString().substring(0, 8));
    }
}
```

**设计决策**：
- 事后通知（post-notification）语义：事件在 Registry 状态变更后抛出
- 监听器不应在事件处理中再次修改同一个 Registry，以免递归
- 使用工厂方法（`ofSubLevel`/`ofGroup`/`ofPlayerBind`）构造事件，避免构造器参数混淆

### 8.2 AffiliationCommand（调试命令）

**文件**：`affiliation/AffiliationCommand.java`

通过 `RegisterCommandsEvent` 注册以下命令，需 OP 权限等级 2：

| 子命令 | 功能 |
|-------|------|
| `/iacp affiliation list` | 列出当前注册总数和玩家-载具绑定概览 |
| `/iacp affiliation check <uuid>` | 显示指定 SubLevel 的完整归属信息（角色、阵营、祖先、组内成员） |
| `/iacp affiliation verify` | 执行一致性检查并报告当前阈值 |
| `/iacp affiliation slowquery <nanos>` | 动态调整慢查询阈值（0=禁用，默认 100,000ns=100μs） |
| `/iacp affiliation player <玩家>` | 查看某玩家的载具及其所有衍生结构 |

### 8.3 性能监控系统

**位置**：`AffiliationRegistry.java §性能监控辅助`

在四个热路径上嵌入纳秒计时：

```java
// 示例：resolvePolicy 的慢查询日志输出
[AffiliationRegistry] 慢查询: resolvePolicy [WEAPON_DAMAGE/TURRET_YAW] 耗时 235 μs
```

- 默认阈值 **100 μs**（100,000 ns），超过时输出 DEBUG 级别日志
- 可通过 `setSlowQueryThreshold(nanos)` 动态调整
- 设为 0 或负值可完全禁用
- `resolvePolicy` 重构为 `resolvePolicy()`（包裹计时）→ `resolvePolicyImpl()`（核心逻辑）两层

### 8.4 getOwnAffiliatedSet() 非组内收集增强

**背景**：原实现只收集 `VEHICLE_TO_GROUPS` 索引中的组内成员。如果一个衍生结构设置了 `vehicleId` 但没有 `groupId`（不属于任何逻辑组），它不会被纳入射线排除集合，可能导致武器误伤。

**修复**：在 `getOwnAffiliatedSet()` 中追加扫描 `SUBLEVEL_TO_TAG` 全表，收集所有 `vehicleId == vehicleUUID && groupId == null` 的散落条目：

```java
// 补充收集所有 vehicleId 指向此载具但不在任何组中的散落衍生结构
for (Map.Entry<UUID, AffiliationTag> entry : SUBLEVEL_TO_TAG.entrySet()) {
    UUID slUUID = entry.getKey();
    AffiliationTag tag = entry.getValue();
    if (tag.vehicleId() != null
            && tag.vehicleId().equals(vehicleUUID)
            && tag.groupId() == null
            && !slUUID.equals(vehicleUUID)) {
        result.add(slUUID);
    }
}
```

### 8.5 ComponentHost 客户端守卫

**修复**：`registerComponent()` 添加 `level.isClientSide()` 检查，与 `reregisterComponent()` 行为一致。防止纯客户端联机时在客户端侧误注册部件。之前仅有注释说"仅在服务端执行"但没有实际限制。
