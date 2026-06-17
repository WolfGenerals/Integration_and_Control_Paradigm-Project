# 控制与动力系统架构文档

## 四层结构

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: 键盘输入层 (ClientEvents.java)                        │
│  检测按键按下/抬起，打包发送到服务端                              │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ 每2 ticks 执行一次:                                      │   │
│  │                                                          │   │
│  │  [A] 原始 W/S 检测                                       │   │
│  │  └─ InputConstants.isKeyDown(GLFW_KEY_W/S)               │   │
│  │     → throttleDirection = +1 / -1 / 0                    │   │
│  │                                                          │   │
│  │  [B] 每个悬挂的智能键检测                                  │   │
│  │  └─ 对每个悬挂方块:                                       │   │
│  │     suspension.getActiveKeyForward()  → fwd (bool)       │   │
│  │     suspension.getActiveKeyBackward() → bwd (bool)       │   │
│  │     suspension.getActiveKeyLeft()     → left (bool)      │   │
│  │     suspension.getActiveKeyRight()    → right (bool)     │   │
│  │     suspension.getActiveKeyBrake()    → brake (bool)     │   │
│  │                                                          │   │
│  │  结果: VehicleControlC2SPacket {                         │   │
│  │    throttleDirection: +1/-1/0,   ← [A]                  │   │
│  │    entries: [                                            │   │
│  │      (pos, fwd, bwd, left, right, brake),  ← [B]        │   │
│  │      ...                                                  │   │
│  │    ]                                                      │   │
│  │  }                                                       │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                            │ 通过网络发送
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 2: 数据包处理层 (VehicleControlC2SPacket.java)           │
│  服务端接收，分发到对应的 BlockEntity                            │
│                                                                 │
│  handler():                                                     │
│    ├─ entries 循环: 每个方块 → be.applyControlInput(...)       │
│    │               → 设置 throttleForward/Backward/braking      │
│    │               → 设置 targetSteeringYaw (转向角)            │
│    │                                                           │
│    └─ 油门方向: 找到驾驶舱 → cockpit.setRawThrottleDirection() │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────────────┐
│  Layer 3: 动力系统层 (CockpitBlockEntity.tick)                     │
│  每 tick 运行，管理发动机油门和变速箱                               │
│                                                                    │
│  tick():                                                           │
│    ├─ scanSubLevel(sl) → SubLevelScanResult                       │
│    │   ├─ throttleDirection: (已废弃，不再使用)                    │
│    │   ├─ loadFactor: 从所有悬挂的 pControllerDemand 计算         │
│    │   └─ avgWheelRpm: 从所有悬挂的 currentWheelRpm 计算          │
│    │                                                              │
│    ├─ direction = this.rawThrottleDirection  ← Layer 2 设置       │
│    ├─ throttleLevel = EngineModel.updateThrottle(方向)            │
│    ├─ engineRpm = EngineModel.computeRpmUpdate(...)               │
│    └─ engineRpm = EngineModel.applyEngineCoupling(轮速, 齿比)    │
│                                                                    │
│  getWheelOutput(totalWheels):                                      │
│    └─ TransmissionModel.computeWheelOutput(档位, 油门, RPM, ...)   │
│       ├─ throttleLevel > 0.01: 指令转速 = 怠速 + 油门×范围        │
│       ├─ 否则: 用实际 engineRpm                                   │
│       ├─ 乘齿比符号 (= 前进档正 / 倒档负)                         │
│       └─ 返回 (wheelRpm, wheelTorque)                             │
└────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 4: 悬挂物理层 (SuspensionTestBlockEntity.physicsTick)        │
│  每物理 tick 运行，计算轮子力输出                                    │
│                                                                     │
│  physicsTick():                                                     │
│    ├─ 刹车模式: 纯滑动摩擦，切断驱动力                              │
│    │                                                                │
│    ├─ 正常模式: 计算 targetRpm 和 torqueGain                        │
│    │  ├─ isStrafeWheel?                                            │
│    │  │   ├─ throttleForward  → +STRAFE_RPM                        │
│    │  │   ├─ throttleBackward → -STRAFE_RPM                        │
│    │  │   └─ 其他 → 0                                              │
│    │  │                                                             │
│    │  ├─ 有驾驶舱? (preCockpit != null)                            │
│    │  │   ├─ targetRpm = getWheelOutput().wheelRpm()                │
│    │  │   ├─ 差速器: targetRpm *= (1.0 + diffOffset)               │
│    │  │   └─ 方向映射: throttleBackward → -targetRpm               │
│    │  │                                                             │
│    │  └─ 无驾驶舱? (降级回退)                                      │
│    │      ├─ throttleForward  → +FALLBACK_DRIVE_RPM                │
│    │      ├─ throttleBackward → -FALLBACK_DRIVE_RPM                │
│    │      └─ 其他 → 0                                             │
│    │                                                                │
│    └─ P 控制器: force = f(targetRpm - actualSpeed)                 │
│       → 摩擦圆约束 → 施加到刚体                                    │
└─────────────────────────────────────────────────────────────────────┘
```

## 数据流全景

```
  手指
  W/S/A/D/Q/E/空格
    │
    ├──┬ GLFW_KEY_W/S ───── rawW/rawS ──→ throttleDirection(+1/-1/0)
    │  │                                         │
    │  │                                         ▼  (包内)
    │  │                                   ┌──────────────┐
    │  │                                   │ 驾驶舱BE      │
    │  │                                   │ .rawThrottle  │
    │  │                                   │ Direction     │
    │  │                                   │   ↓           │
    │  │                                   │ updateThrottle│
    │  │                                   │   ↓           │
    │  │                                   │ throttleLevel │
    │  │                                   │   ↓           │
    │  │                                   │ getWheelOutput│
    │  │                                   │ → targetRpm   │
    │  │                                   └──────┬───────┘
    │  │                                          │
    │  └── 每个悬挂: smartKey 转译后的 5 个布尔值 ──┤
    │       (fwd/bwd/left/right/brake)            │
    │       经 VehicleControlC2SPacket 发送         │
    │       到每个悬挂 BE: applyControlInput()      │
    │                                             │
    │       ┌─────────────────────────────────────┐│
    │       │ 悬挂BE (每轮一个)                    ││
    │       │  throttleForward/Backward ────────→─┤│
    │       │  targetSteeringYaw                  ││
    │       │  braking                            ││
    │       │                                     ▼▼
    │       │  physicsTick 三路 RPM 选择 ────────────────→ P控制器 → 力
    │       │    isStrafe?  →  ±STRAFE_RPM
    │       │    有驾驶舱?   → 变速箱RPM × 方向映射
    │       │    都没有?     →  ±FALLBACK_DRIVE_RPM
    │       └─────────────────────────────────────┘
    │
    └──── 原版 KeyMapping (Q=丢弃, E=物品栏)
          → ClientTickEvent.Pre 尝试压制但可能失效
          → 随玩家登录状态自动触发
```

## 关键数据结构

### SuspensionTestBlockEntity (每个悬挂一个)

| 字段 | 来源 | 用途 |
|------|------|------|
| `keyForward/Backward/Left/Right/Brake` | 手动配置/NBT | 无智能映射时的回退键 |
| `smartKeyForward/Backward/Left/Right/Brake` | `setSmartKeyBindings()` (Car Mode) | 智能映射键，非空时优先 |
| `throttleForward/Backward` | `applyControlInput()` | 当前 tick 的油门方向状态 |
| `isStrafeWheel` | `setStrafeWheel()` (Car Mode/NS轮) | 标记横移轮，不走变速箱 |
| `targetSteeringYaw` | `applyControlInput()` (转向) | 目标转向角 |
| `braking` | `applyControlInput()` | 刹车状态 |

### CockpitBlockEntity (每车一个)

| 字段 | 来源 | 用途 |
|------|------|------|
| `smartMappingActive` | `setSmartMappingActive()` | 智能映射总开关 |
| `rawThrottleDirection` | `setRawThrottleDirection()` (包处理) | 油门方向 +1/-1/0 |
| `throttleLevel` | `tick()` → `updateThrottle()` | 油门深度 0.0~1.0 |
| `currentGear` | `gearUp()/gearDown()` | 当前档位 -1/R, 0/N, 1~5 |
| `engineRpm` | `tick()` → 发动机模型 | 发动机转速 |
| `effectiveTorque` | `tick()` → `computeEngineTorque()` | 引擎输出扭矩(含曲线×油门) |

### VehicleControlC2SPacket (每 2 ticks 发送一次)

```
{
  throttleDirection: int     ← 原始 W/S 方向 (+1/-1/0)
  entries: [
    { pos, forward, backward, left, right, brake },  ← 每轮的 5 个键状态
    ...
  ]
}
```

### 智能映射的键分配 (SmartMapC2SPacket.applyCarMode)

```
EW 轮 (facing EAST/WEST):
  smartKeyForward  = W
  smartKeyBackward = S
  smartKeyLeft     = A (正半区) 或 D (负半区)
  smartKeyRight    = D (正半区) 或 A (负半区)
  isStrafeWheel    = false

NS 轮 (facing NORTH/SOUTH):
  smartKeyForward  = E
  smartKeyBackward = Q
  smartKeyLeft     = "" (回退 manual key)
  smartKeyRight    = "" (回退 manual key)
  isStrafeWheel    = true
```

## 已知问题

### 1. Q/E 泄漏 (丢弃物品 + 物品栏)
`ClientTickEvent.Pre` 压制 `keyInventory.setDown(false)` 和 `keyDrop.setDown(false)` 在部分 NeoForge 版本中可能因事件序问题无效。Minecraft 的输入处理在 `Pre` 之前已读取 GLFW 状态并更新 KeyMapping。

### 2. scanSubLevel 的废弃 throttleDirection
`scanSubLevel()` 仍在扫描所有轮子的 `isThrottleForward/Backward` 计算出 `throttleDirection`，但这个值在 `tick()` 中被 `rawThrottleDirection` 替换后彻底不用了。扫描仍在执行，浪费 CPU。

### 3. 扫描包含了横移轮
`scanSubLevel()` 的 loadFactor 和 avgWheelRpm 计算没有排除横移轮，导致：
- `totalMaxForce` 用变速箱齿比算横移轮的力上限 → 数值错误
- `avgWheelRpm` 把 strafe RPM 和驱动轮 RPM 混在一起 → 错误
- `getTotalEngineLoad()` 读横移轮的 P 控制器需求 → 使引擎负载虚高

### 4. 方向映射的语义模糊
`throttleBackward` 在 `physicsTick` 中被复用：
- 驱动轮 + 驾驶舱：`throttleBackward → -targetRpm` (反转变速箱方向)
- 横移轮：`throttleBackward → -STRAFE_RPM` (直接决定横移方向)
- 驱动轮 + 无驾驶舱：`throttleBackward → -FALLBACK_DRIVE_RPM`

同一字段承载了"请求方向"和"映射覆盖"两种含义。

### 5. W/S 的双路径
W 同时触发两条路径：
- 原始检测 → `rawThrottleDirection=+1` → 引擎油门增大
- 智能键检测 → `getActiveKeyForward()=W` → `throttleForward` → 决定轮子方向

它们语义不同但物理结果耦合：引擎给更多 RPM，轮子决定往哪转。
反转后 W 仍是油门增大（因为 raw 检测），但 `getActiveKeyForward()` 变 S → `throttleBackward` → 方向映射反。

### 6. ClientEvents.sendVehicleControlInput 的有状态变化检测
`lastControlStates` Map 跟踪每个轮子的 5 布尔值是否变化，只有变化时才发包。
但 `throttleDirection` 的变化不触发发包。如果 W 从按住→松开，`throttleDirection` 从 +1 → 0，但 per-wheel entries 没有变化 → 包不发 → cockpit 不知道油门该降。

**这是当前油门无响应的根因之一**：按住 W 时 `throttleDirection=+1` 一直发，松开 W 时如果所有轮子的键状态没变（比如没按其他键），包压根不发，cockpit 的 `rawThrottleDirection` 永远停留在 +1。
