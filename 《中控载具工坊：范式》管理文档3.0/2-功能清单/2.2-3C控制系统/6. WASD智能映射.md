---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 6. WASD 智能映射（v3 最终实现）

> **状态**：✅ 完成于 2026-06-12
> **设计目标**：建立"玩家意图 → 多轮协调"的输入抽象层。玩家无需逐个方块绑键，一键 Auto 配置即可正常驾驶。

---

## 架构总览

```
ClientEvents (读取物理按键)
    │ 使用 getActiveKey*() → smartKey 优先 / manualKey 回退
    ▼
VehicleControlC2SPacket (发送每 tick WASD/刹车状态)
    │
    ▼
SuspensionTestBlockEntity.applyControlInput()
    ├─ 写入 throttleForward/Backward  ← 按键层
    ├─ 写入 targetSteeringYaw, braking
    ▼
CockpitBE.tick()
    ├─ scanSubLevel() 汇总 throttle direction  ← 引擎层
    ├─ smartMappingReversed? → direction = -direction  ← 反转方向解读
    ├─ 油门调整 (三段式：加速/减速/滑行)
    └─ getWheelOutput() → wheelRpm = (cmdRPM / ratio) × sign
         └─ smartMappingReversed? → sign = -sign  ← 反转齿比符号
    ▼
SuspensionTestBlockEntity.physicsTick()
    └─ P 控制器: speedError = targetSpeed - forwardSpeed
```

**三层耦合架构（重点）**：
- **按键层** (SuspensionTestBE)：`getActiveKey*()` → smartKey 优先 / manualKey 回退 → `applyControlInput()` → `throttleForward/Backward`
- **引擎层** (CockpitBE)：`scanSubLevel()` 汇总方向 → `tick()` 调油门 → `getWheelOutput()` 算轮端 RPM + 扭矩
- **物理层** (SuspensionTestBE)：P 控制器追踪 `targetRpm` → 施加轮端力 → 摩擦圆约束

反转方向需要在**全部三层**同步反转，否则按键交换后引擎层会错误解读（详见下方 §REVERSE）。

三个组件协作：

1. **VehicleOrientationData** — 被动数据容器（Record）
2. **VehicleOrientationScreen** — C 键交互式 GUI
3. **SmartMapC2SPacket** — C→S 命令，驱动服务端逻辑

---

## VehicleOrientationData（朝向统计数据）

```java
public record VehicleOrientationData(int north, int south, int east, int west)
```

- 统计 SubLevel 中所有悬挂方块的 `HORIZONTAL_FACING` 分布
- `getWidthAxis()` — 多数朝向的轴向 = 宽度轴（轮子伸出方向）
- `getForwardAxis()` — 垂直于宽度轴的方向 = 前进轴
- `total()` — 悬挂方块总数

## VehicleOrientationScreen（朝向信息界面）

**触发方式**：上车后按 C 键，或下车间隔 2 秒内对准驾驶舱按 C 键

**界面布局**：
```
┌────────────────────────────────┐
│   载具朝向信息                  │
│                                │
│   东 (EAST):   2               │
│   西 (WEST):   2               │
│   北 (NORTH):  1               │
│   南 (SOUTH):  1               │
│   总数:        6               │
│   宽度轴: 东西 (X)              │
│   前进轴: 南/北 (Z)             │
│                                │
│   ┌──────────┐  ┌──────────┐  │
│   │ 汽车模式  │  │ 反转方向  │  │
│   └──────────┘  └──────────┘  │
│         ┌──────────┐           │
│         │ 智能映射  │ (ON/OFF) │
│         └──────────┘           │
│                                │
│   状态: §a已启用                │
└────────────────────────────────┘
```

**三个按钮**：
| 按钮 | 行为 | 客户端动作 | 服务端动作 |
|------|------|-----------|-----------|
| 汽车模式 | 自动分配 WASD | 同步 `smartMappingActive=true` + 发送 CAR_MODE 包 | `applyCarMode()` |
| 反转方向 | 调换 W↔S, A↔D | 发送 REVERSE 包 | `applyReverse()` |
| 智能映射 | 开启/关闭 | 翻转本地状态 + 发送 TOGGLE_SMART | `toggleSmartMapping()` |

---

## SmartMapC2SPacket（智能映射数据包）

**方向**：C→S  
**协议**：`CustomPacketPayload` + `StreamCodec`

```java
public enum Action {
    CAR_MODE,    // 汽车模式
    REVERSE,     // 反转方向盘
    TOGGLE_SMART // 开关智能映射
}
```

### CAR_MODE：汽车模式

1. **扫描**：遍历 SubLevel 所有悬挂方块，记录位置和 FACING
2. **投票**：东西朝向多 → 宽度轴=X，前进轴=Z；南北朝向多 → 宽度轴=Z，前进轴=X
3. **质心**：计算所有悬挂方块的 Z 坐标平均值（`centroidZ`）
4. **分配**（每轮独立）：
   - 前进键 = W，后退键 = S（对于东西向轮子；南北向轮子同理）
   - **南侧轮**（posZ >= centroidZ）：左转=A，右转=D
   - **北侧轮**（posZ < centroidZ）：左转=D，右转=A（镜像）
5. 调用 `SuspensionTestBlockEntity.setSmartKeyBindings(fwd, bwd, left, right, brake)`
6. 标记驾驶舱 `smartMappingActive = true`

### REVERSE：反转方向盘

反转方向盘需要在**三个独立层级**同步反转：

| 层级 | 反转内容 | 位置 |
|------|---------|------|
| 按键层 | `smartKeyForward`↔`smartKeyBackward`, `smartKeyLeft`↔`smartKeyRight` | `SuspensionTestBlockEntity` |
| 引擎层 | `direction = -direction`（油门解读反转）、`ratioSign = -ratioSign`（齿比符号反转） | `CockpitBlockEntity` (`smartMappingReversed`) |
| 客户端缓存 | `ClientMountHandler.localSwapSmartKeys()` 立即交换本地智能键，消除同步延迟 | `ClientMountHandler` |

**引擎层反转的根因**：
- 按键层 W↔S 交换后，W 触发 `throttleBackward=true` → `direction=-1`
- 引擎层硬编码 `direction<0` = 松油门，导致油门下降而非上升
- 同时前进档齿比为正 → 车轮仍往原始正向转，与玩家预期相反
- 引入 `smartMappingReversed` 标志后：`direction=-(-1)=+1`（油门上升），`ratioSign` 反转（车轮反向）

**客户端同步延迟修复**：
- 发送 REVERSE 包后服务端交换智能键再 `sendData()` 同步回客户端需 1-2 tick
- 这期间客户端 BE 仍是旧 smartKey → `getActiveKeyForward()`=旧值 → 发给服务端的 fwd/bwd 错误
- 引入 `localSwapSmartKeys()` 在**发送包的同一帧**立即交换客户端本地 BE 的智能键

### TOGGLE_SMART：开关智能映射

| 当前状态 | 动作 |
|---------|------|
| 关闭 | 调用 `SuspensionTestBlockEntity.resetSmartKeys()` 清除所有智能键，标记禁用，重置 `smartMappingReversed=false` |
| 开启 | 调用 `applyCarMode()` 重新分配，标记启用，重置 `smartMappingReversed=false` |

---

## Smart Key 存储与回退

### SuspensionTestBlockEntity 新增字段

```java
private String smartKeyForward   = "";  // 默认空串 = 不使用智能映射
private String smartKeyBackward  = "";
private String smartKeyLeft      = "";
private String smartKeyRight     = "";
private String smartKeyBrake     = "";
```

**NBT 键**：`TAG_SMART_KEY_FORWARD` 等，向后兼容（旧存档无此数据）

### getActiveKey*() 回退逻辑

```java
public String getActiveKeyForward() {
    return smartKeyForward.isEmpty() ? keyForward : smartKeyForward;
}
```

- `smartKey` 非空时优先使用（智能映射激活）
- `smartKey` 为空时回退到手动配置的手动键（`keyForward` 等）
- ClientEvents 的 `sendVehicleControlInput()` 统一使用 `getActiveKey*()` 方法

### 工具方法

```java
void setSmartKeyBindings(fwd, bwd, left, right, brake)
    // 设置+标记脏数据+同步

void resetSmartKeys()
    // 全部清空+标记脏数据+同步
```

### CockpitBlockEntity 新增

```java
private boolean smartMappingActive = false;    // NBT 持久化
private boolean smartMappingReversed = false;  // NBT 持久化
```

- `TAG_SMART_MAPPING_ACTIVE` — 服务端持久化智能映射启用状态
- `TAG_SMART_MAPPING_REVERSED` — 服务端持久化引擎层方向反转状态
- `setSmartMappingActive(boolean)` / `isSmartMappingActive()` — getter/setter
- `setSmartMappingReversed(boolean)` / `isSmartMappingReversed()` — getter/setter（toggle：`!isSmartMappingReversed()`）

**tick() 中的反转逻辑**：
```java
if (smartMappingActive && smartMappingReversed) {
    direction = -direction;  // 油门方向反转
}
```

**getWheelOutput() 中的反转逻辑**：
```java
double ratioSign = Math.signum(ratio);
if (smartMappingActive && smartMappingReversed) {
    ratioSign = -ratioSign;  // 齿比符号反转 → 车轮反向旋转
}
wheelRpm = (commandedRpm / effectiveRatio) * ratioSign;
```

---

## 客户端缓存（ClientMountHandler 新增）

| 字段 | 类型 | 用途 |
|------|------|------|
| `ORIENTATION_CACHE` | `Map<UUID, VehicleOrientationData>` | 按 SubLevel UUID 缓存 FACING 统计 |
| `smartMappingActive` | `static boolean` | 从驾驶舱 BE 缓存的智能映射状态 |
| `smartMappingReversed` | `static boolean` | 从驾驶舱 BE 缓存的引擎层反转状态 |

```java
static void scanOrientation(SubLevel subLevel, Level level)
    // 扫描 SubLevel 所有悬挂方块 → 生成 VehicleOrientationData → 存入缓存

static void syncSmartMappingState(Level level, UUID subLevelUUID)
    // 查找驾驶舱 BE → 读取 smartMappingActive + smartMappingReversed → 缓存到本地静态字段

static void localSwapSmartKeys()
    // 客户端本地立即交换所有悬挂方块的智能键（W↔S, A↔D）
    // 发送 REVERSE 包后立即调用，无需等待服务端同步
    // 同步 smartMappingReversed = !smartMappingReversed
```

---

## VehicleKeyConfigScreen 的交互

C 键对准单个悬挂方块时，配置界面右侧新增一列：

```
左侧（手动键）        右侧（智能映射）
前进: [  W  ]    →   前进: §agreen_key
后退: [  S  ]    →   后退: §agreen_key
左转: [  A  ]    →   左转: §agreen_key
右转: [  D  ]    →   右转: §agreen_key
刹车: [空格]     →   刹车: §7(未设置)
```

- 绿色 = 该键已被智能映射覆盖（smartKey 非空）
- 灰色 = 未设置智能键（使用手动键回退）

---

## 使用流程

```
1. 放置驾驶舱 + 悬挂方块 + 轮子
2. 按 F 上车
3. 按 C → 弹出朝向信息界面
4. 点击"汽车模式" → 自动分配 WASD
5. 感觉方向反了？点击"反转方向"
6. 不满意？点击"智能映射"关闭，恢复到手动配置
7. 按 ESC 关闭界面，开始驾驶
```

---

## 设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 智能键 vs 手动键分离 | 独立 `smartKey*` 字段 + `getActiveKey*()` 回退 | Car Mode 不覆盖手动配置，玩家可混合使用 |
| FACING 投票 vs 质心方向 | FACING 投票决定轴向，质心 Z 决定半区 | 适应不对称轮位布局 |
| 质心 Z vs 坐标正负 | 质心 Z 动态计算，不依赖坐标系方向 | 载具可任意旋转放置 |
| 南北半区转向镜像 | 北侧左转=D，南侧左转=A | 镜像转向实现原地转圈效果 |
| `smartMappingActive` NBT 持久化 | 存入 CockpitBE NBT | 服务端重启/断线重连后恢复状态 |
| 按钮客户端同步 | Car Mode 按钮本地同步 `smartMappingActive=true` | 避免 Toggle 按钮显示错误状态 |
