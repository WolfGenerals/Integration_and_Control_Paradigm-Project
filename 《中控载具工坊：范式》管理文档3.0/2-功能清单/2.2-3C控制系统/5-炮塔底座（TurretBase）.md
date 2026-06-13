---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 5. 炮塔底座 (TurretBase)

### 概述
地毯形方块，右键交互在附近生成**物理化砂轮 + 避雷针 SubLevel**，并通过 **RotaryConstraint（旋转轴承）** 将砂轮锚定到载具上。

### 装配流程
```
① 右键 → 判断地毯是否在 SubLevel 上
   ├─ 在载具上：计算地毯世界坐标 + 获取载具姿态
   └─ 在主世界：使用地毯本身坐标

② 计算砂轮生成位置
   ├─ 在载具上：定位点（carpetWorld − anchor）+ 载具姿态
   └─ 在主世界：空位 + identity

③ 创建砂轮 SubLevel
   └─ allocateNewSubLevel(Pose3d) → initSingleBlockSubLevel → teleport → updateLastPose

④ 建立 RotaryConstraint（仅载具上）
   ├─ pos1 = getCenterBlock() + (0.5,0.5,0.5) — 砂轮方块底层实际坐标
   ├─ pos2 = this.worldPosition + (0.5,0.5,0.5) — 地毯方块底层实际坐标
   ├─ normal1 = normal2 = (0, 1, 0) — 局部 Y 轴旋转
   └─ setContactsEnabled(false) — 砂轮穿透载具

⑤ 创建避雷针 SubLevel（在附近空位）
```

### 关键安全原则
| 原则 | 说明 |
|------|------|
| 两端坐标相同 | pos1 和 pos2 经过各 SubLevel pose 变换到世界后为同一点 → 零力 |
| 砂轮归位 | 创建后 teleport 到定位点，使 anchor 对齐地毯世界坐标 |
| 姿态一致 | 砂轮生成时即使用载具姿态，避免瞬转 |
| 嵌入层坐标 | 约束位置使用底层绝对方块坐标（`getCenterBlock()`），不是世界坐标 |

### 锚点 A 坐标系统
- 三个可配置 double 坐标（默认 0,0,0），表示砂轮 SubLevel 局部空间中的锚点位置
- NBT 持久化 + S2C 包推送
- C 键 GUI（GrindstoneConfigScreen）编辑
- 渲染时通过 `pose.transformPosition(anchor)` 计算锚点世界坐标

### 三色轴线渲染
| 元素 | 颜色 | 长度 | 说明 |
|------|:----:|:----:|------|
| SubLevel 轴线 | 红X / 绿Y / 蓝Z | 100 格 | 每个 SubLevel 原点位置 + 旋转方向 |
| 地毯焦点 | 红X / 绿Y / 蓝Z | 3 格双向 | 每个地毯位置独立渲染的三色十字线 |

渲染方案：Sable debug render 模式（PoseStack × modelViewMatrix, MultiBufferSource + RenderType.LINES）。

---

## 自动瞄准系统（06-10 最终方案）

### 概述
炮塔底座物理装配完成后，增加自动瞄准能力：上车后通过射线检测获取目标位置，驱动方向机（Y 轴旋转）和高低机（X 轴俯仰）指向目标。

### 控制架构（最终：位置模式 PD 伺服）

```
每 client tick（持续自动瞄准）
    ↓  WeaponOverlay.performRaycast() — 1000 格三重检测
    ↓  TurretTargetC2SPacket (C→S)
TurretAimController.setTarget(gsUUID, x, y, z)
    ↓  TARGETS ConcurrentHashMap<UUID, AimTarget>
PlayerMountTracker.tick() 每 server tick
    ↓  TurretAimController.tick(level)

AimController.drive(每 server tick):
    └─ 从砂轮 pose 算当前朝向（Z 轴正向）
    └─ atan2 算最短夹角 yawErr
    └─ targetYawRad = -(currentYawRad + yawErr) + offsetRad
    └─ tb.setTargetYawAbsolute(Math.toDegrees(targetYawRad))

TurretBaseBE.updateYawServo()（每 server tick）:
    └─ angleLerp(1.0, lastTarget, target) 插值
    └─ setMotor(DEFAULT_AXIS, goal, kP=200, kD=16, false, 0)
       └─ position mode — 参考 SwivelBearingBlockEntity
```

### 迭代史（完整演化）

| 阶段 | 方案 | 结果 | 根因 |
|:----:|------|:----:|------|
| I | 扭矩脉冲 / 基础 PD / 角速度 P 控制 | ❌ | forward 方向 Bug + setMotor 不响应 |
| II | 速度伺服 + SNAP teleport | ❌ 到位不稳 | solver substep 过冲 |
| III | 抽象齿轮模式（RPM → 角度累积 → PD） | ❌ 震荡 | RPM 是阶跃变化，开环+闭环=双重积分器 |
| IV | 位置模式 PD 伺服（SwivelBearing 模式） | ✅ 稳定 | 放弃 RPM 累积，直接 position-mode |
| **V** | **限速轨迹 + 超高刚度 PD** | **✅ 瞬停无过冲** | **06-13：限速 + kP×1000 暴力抑制惯性** |

### 关键诊断发现

通过 **DebugGearBlock**（N 键切换，每 tick 打印 RPM）实测确认：

**Create 应力网络 RPM 变化是瞬时的**：0→±16、±16→±88、±88→0 均在 **1 tick** 内完成，无任何渐变过程。

这一发现否定了"抽象齿轮模式"的理论基础——RPM 阶跃导致角度累积（开环）+ PD 伺服（闭环）构成双重积分器系统，必然震荡。

### 最终控制参数

| 参数 | 值 | 说明 |
|------|:---:|------|
| SERVO_STIFFNESS | 200000.0 | 方向机 PD 刚度（06-13 从 200 提升 1000×）|
| SERVO_DAMPING | 50.0 | 方向机 PD 阻尼 |
| PITCH_SERVO_STIFFNESS | 600000.0 | 高低机 PD 刚度（06-13 从 6000 提升 1000×）|
| PITCH_SERVO_DAMPING | 1200.0 | 高低机 PD 阻尼 |
| YAW_DEAD | 0.5° | 到位死区 |
| PITCH_DEAD | 0.5° | 俯仰死区 |
| PITCH_CLAMP | 60° | 俯仰最大角 |
| MAX_RAY_DISTANCE | 1000 格 | 射线最大距离 |
| TURRET_YAW_OFFSET | configurable | 偏航校准（-180~180°）|
| TURRET_YAW_SPEED_DPS | configurable（默认 90°/s） | 方向机最大转速 |
| TURRET_PITCH_SPEED_DPS | configurable（默认 40°/s） | 高低机最大转速 |

### 坐标镜像（重要！）

`atan2(cross, dot)` 以**逆时针**为正，RotaryConstraint.setMotor 以**顺时针**为正。因此：
```java
double targetYawRad = -(currentYawRad + yawErr) + offsetRad;
//                      ^ 取反解决符号镜像
```

测试验证：0°/180° 和 90°/270° 四个方向均正确。

### 射线检测 (WeaponOverlay)

| 检测阶段 | 方法 | 说明 |
|---------|------|------|
| 方块地形 | `mc.level.clip(ClipContext)` | 传统 Minecraft 方块检测 |
| 实体 | `ProjectileUtil.getEntityHitResult()` | 可拾取实体 |
| SubLevel AABB | `rayAABBIntersection()` Slab 算法 | 其他 SubLevel 物理结构 |
| 过滤 | `MIN_RAY_DISTANCE = 2.0` | 排除载具自身 AABB |
| **瞄空气** | 无命中返回射线末端 | 炮塔持续指向鼠标方向 |

**关键过滤**：跳过当前乘坐的载具 SubLevel（`mountedUUID.equals()`），防止射线命中自身载具 AABB 表面。

**距离扩展**：`MAX_RAY_DISTANCE` 从 500 格扩展到 **1000 格**，提供更远距离的瞄准能力。

### 连续性
- **持续火控**：取消旧 5 tick 冷却，每 client tick 执行 `performRaycast()`
- **瞄空气**：无任何命中时返回 1000 格末端点（类型 `"Air"`），炮塔持续指向鼠标方向
- 松开鼠标后保留最后瞄准位置

### 调试齿轮 (DebugGearBlock)
- 小型齿轮方块，`RotatedPillarKineticBlock + ICogWheel`
- N 键切换调试输出
- 每 tick 打印 `[DebugGear] speed/angular/RPM`
- **诊断结论**：Create RPM 阶跃变化

### 限速轨迹规划（06-13 新增）

**核心改进**：在 `TurretBaseBlockEntity.setTargetYawAbsolute()` / `setTargetPitchAbsolute()` 中
加入限速逻辑，防止 AimController 输出的目标角度阶跃变化导致 PD 过冲。

```java
delta = degrees - this.targetAngleDegrees;
delta = Math.IEEEremainder(delta, 360.0);  // 归一化 [-180, 180]
double step = Math.copySign(Math.min(Math.abs(delta), maxStep), delta);
this.targetAngleDegrees += step;           // 每 tick 限速推进
```

**效果**：
- 距离远时全速旋转（maxStep 由 Config °/s ÷ 20 换算）
- 最后 1 tick 精确到位、不超调
- 配合超高刚度 PD（kP=200000），实现物理引擎瞬停

### 超高刚度 PD 发现（06-13）

通过 DebugSwivelBearing 测试发现，SwivelBearing 在重载下仍能瞬间刹停，
证明 PD 伺服本身不存在问题。炮塔过冲的根因是目标角度阶跃变化，而非 PD 参数。

**解决方案**（非常规但有效）：
1. 限速轨迹规划消除目标阶跃
2. 暴力提升 kP 至 200000（方向机）/ 600000（高低机）
3. kD 相应提升至 50 / 1200

**副作用**：极高刚度可能导致与其他约束交互时物理不稳定，但实测中未出现。

### 可配置校准
`Config.java` `[turret.aim]` 节：
```toml
yawOffset = 0.0      # 范围 -180~180°，默认 0°
yawSpeedDPS = 90.0   # 方向机最大转速（度/秒）
pitchSpeedDPS = 40.0 # 高低机最大转速（度/秒）
```

### 高低机控制（位置模式 PD 伺服 ✅ 06-13 完成）
- GenericConstraint 锁定 `LINEAR_XYZ + ANGULAR_YZ`，保留 `ANGULAR_X` 自由
- 避雷针绕局部 X 轴俯仰
- 与方向机统一 position-mode，每 tick `setMotor(ANGULAR_X, goal, kP, kD, false, 0)`
- 06-13 更新：`PITCH_SERVO_STIFFNESS=600000`, `PITCH_SERVO_DAMPING=1200`（与方向机同比例 ×1000）
- 同样加入限速轨迹规划，最大俯仰速度默认为 40°/s（Config 可调）

### 已解决问题的完整列表
1. ~~**forward 方向错误** — (0,1,0)=UP → (0,0,1) Z 轴正向~~ ✅
2. ~~**setMotor 后续调用不响应** — SmartBlockEntity → KineticBlockEntity~~ ✅
3. ~~**到位震荡** — 抽象齿轮双重积分器 → 位置模式 PD 伺服~~ ✅
4. ~~**方向镜像** — atan2 取反 `-(currentYawRad + yawErr)`~~ ✅
5. ~~**车体撕裂** — SubLevel 质量 0.001~~ ✅
6. ~~**速度伺服到位不稳** — solver substep 过冲~~ ✅
