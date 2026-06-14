---
updated: 2026-06-14 (v3：新增设计缺口与改进方向)
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

---

## 九、设计缺口与改进方向

> 以下条目来自 2026-06-14 设计审查，标识了当前架构中已知的设计缺口。按优先级排列。

### 9.1 🔴 `onWorldLoad()` 未接入事件总线

**背景**：`AffiliationRegistry.onWorldLoad()` 和 `ComponentRegistry.onWorldLoad()` 均已定义，负责世界加载时清空注册表等待 NBT 重建，但未被任何事件处理器调用。

**风险**：世界重载/维度切换后注册表不会自动重置，旧 SubLevel UUID 数据残留可能导致查询返回过期结果。

**改进方向**：在 `IACP` 构造函数或通过 `@EventBusSubscriber` 监听世界加载事件（如 `LevelEvent.LOAD`），自动调用 `onWorldLoad()`。参考 `PlayerMountTracker` 的 `NeoForge.EVENT_BUS.register()` 注册模式。

### 9.2 🔴 `register()` 非原子操作

**背景**：
```java
public static void register(UUID subLevelUUID, AffiliationTag tag) {
    unregister(subLevelUUID);           // 步骤 1
    SUBLEVEL_TO_TAG.put(subLevelUUID, tag); // 步骤 2（非原子）
    // 组索引维护...
}
```

**风险**：`unregister` 和 `put` 是两个独立操作。多线程场景下可能出现 A 线程 unregister 后 B 线程抢先注册，然后 A 线程 put 覆盖 B 的竞态条件。

**改进方向**：使用 `synchronized` 块或将整个注册过程封装到 `ConcurrentHashMap.compute()` 原子操作中。

### 9.3 🔴 `faction` 阵营系统未实际启用

**背景**：`isSameFaction()` 要求双方 `faction != FACTION_NEUTRAL(0)` 才认为同阵营，而所有当前代码传入的都是 `FACTION_NEUTRAL`。

**影响**：
- `resolvePolicy(WEAPON_DAMAGE)` 中的 `sameFaction` 永远为 `false`
- 友军伤害屏蔽分支从未被执行
- 阵营扩展预留停留在设计文档层面

**改进方向**：实现简单的阵营分配机制（载具创建时分配、玩家选择、或匹配队列），或明确标记 faction 为"未启用"。

### 9.4 🟡 缺少客户端-服务端安全隔离

**背景**：`AffiliationRegistry` 是全局静态单例，没有区分客户端/服务端实例。`register()` 等写方法在客户端调用时可能导致状态不一致。

**改进方向**：在 `register()` 等写方法中添加服务端守卫（通过 `LogicalSide` 检查），或拆分为 `ClientAffiliationRegistry` / `ServerAffiliationRegistry`。

### 9.5 🟡 NBT 缺少版本号

**背景**：`AffiliationTag.writeToNbt()` / `readFromNbt()` 没有版本控制。未来添加/修改字段时，旧存档 NBT 会静默解析失败（`!tag.contains(TAG_ROLE)` 直接返回 null）。

**改进方向**：添加 `AffiliationVersion` int 字段到 NBT，`readFromNbt()` 中做版本兼容分支。在 Javadoc 中记录每个字段的添加版本。

### 9.6 🟡 `ComponentRegistry` 的 NBT 持久化未实现

**背景**：`ComponentEntry` 有 `writeToNbt()` / `readFromNbt()` 方法，但 `ComponentRegistry` 本身没有 `saveToNbt()` / `loadFromNbt()` 的实现，也没有被任何生命周期钩子调用。

**影响**：世界重载后 ComponentRegistry 完全依赖 BE 的 `onLoad()` 重建，缺乏快照恢复机制。

**改进方向**：实现 `ComponentRegistry.saveToNbt()` / `loadFromNbt()`，在世界保存/加载时调用。

### 9.7 🟡 缺少级联清理机制

**背景**：注销一个 SubLevel 时，其他 SubLevel 中 `vehicleId` 指向该已注销目标的条目不会被自动更新或清理。

**影响**：载具主体被摧毁后，炮塔部件的 `vehicleId` 仍然指向已销毁的 UUID，成为悬挂引用。

**改进方向**：实现级联清理——`unregister()` 时自动查找所有 `vehicleId` 指向当前 UUID 的条目并注销或标记为孤立。

### 9.8 🟢 性能监控仅记录不降级

**背景**：慢查询日志记录了热路径耗时，但没有自适应降级机制。

**改进方向**：可引入 LRU 缓存缓存 `resolvePolicy()` 结果（注意缓存失效策略），或在阈值超限后自动切换到简化路径。

### 9.9 🟢 CAMERA_AIM 矩阵文档与代码不一致

**背景**：文档策略矩阵显示 CAMERA_AIM 对所有角色均为 `PENETRATE_AABB`，但代码对 `PROJECTILE` 和 `SENSOR` 实际返回 `IGNORE`。

**改进方向**：更新文档矩阵以匹配代码实现（弹射物和传感器无内部方块，应完全穿透）。

### 9.10 🟢 缺少测试覆盖

**背景**：核心基础设施（12 个文件、5 个 ConcurrentHashMap、事件系统、NBT 序列化）没有单元测试或集成测试。

**改进方向**：至少为以下场景编写测试：
- 注册/注销/整组清理的基本路径
- `getOwnAffiliatedSet()` 的组内+非组内双重收集
- `resolvePolicy()` 的 3×5 策略矩阵全覆盖
- NBT 序列化/反序列化往返
- 世界重载重建场景

### 9.11 🟢 `SubLevelOwnership` 委派层的 Role 退化

**背景**：旧 `SubLevelOwnership.register()` 委派时使用 `AffiliationRole.UNKNOWN`，然后 `TurretBaseBlockEntity` 稍后重新用精确角色覆盖。UNKNOWN 注册期的查询结果不可靠。

**改进方向**：弃用 `SubLevelOwnership` 层直接调用 `AffiliationHelper`，或在委派方法中新增 `AffiliationRole` 参数。

### 9.12 🟢 `list` 调试命令输出有限

**背景**：`/iacp affiliation list` 只显示注册总数和玩家绑定提示，没有列出每个 SubLevel 的概览。`appendPlayerBindings()` 注释说明"无法直接遍历私有 Map"。

**改进方向**：暴露 `SUBLEVEL_TO_TAG.entrySet()` 的不可变快照视图供调试用，或添加专用 `getAllEntries()` 调试方法。
