# 炮塔位置模式 PD 伺服（SwivelBearing 模式）

> 适用场景：炮塔方向机控制、任何使用 RotaryConstraint 的旋转伺服系统
> 参考实现：`com.simibubi.create.content.kinetics.swivel.SwivelBearingBlockEntity`
> 确认日期：2026-06-10

---

## 核心原理

每 tick 使用 **position mode** 的 `setMotor()` 直接设定目标角度，不依赖 RPM 累积或角度增量。

```java
// 每 tick:
swivelBearingHandle.setMotor(
    RotaryConstraintHandle.DEFAULT_AXIS,  // 旋转轴
    goal,           // 目标角度（弧度）
    kP,             // 位置刚度
    kD,             // 速度阻尼
    false,          // position mode（关键！）
    0.0
);
```

## 为什么 position mode 而非 velocity mode

通过实测验证：
- **`setMotor(axis, goal, kP, kD, false, 0)` = position mode** → ✅ 正常工作，每 tick 响应
- **`setMotor(axis, goal, kP, kD, true, 0)` = velocity mode** → ❌ 在 RotaryConstraint 上无效，炮塔不旋转

velocity mode 对 RotaryConstraint 无效的原因推测：RotaryConstraint 只有 1 个旋转自由度，velocity mode 可能被约束的默认行为覆盖/忽略。

## 实现架构

```
AimController（每 server tick）:
  └─ 读取砂轮 pose → 计算当前朝向（Z 轴正向）
  └─ atan2 算最短夹角 yawErr
  └─ 绝对目标角度 = -(currentYawRad + yawErr) + offsetRad
  └─ tb.setTargetYawAbsolute(Math.toDegrees(targetYawRad))

TurretBaseBE（每 server tick）:
  └─ updateYawServo():
       ├─ angleLerp(1.0, lastTarget, target) — partialTick 插值
       └─ setMotor(DEFAULT_AXIS, goal, kP=200, kD=16, false, 0)
```

## 关键设计决策

### 1. 绝对角度 vs 增量调节

```java
// ❌ 增量调节（抽象齿轮模式）：
this.targetAngleDegrees += angularSpeed;  // 开环累积

// ✅ 绝对角度（最终方案）：
public void setTargetYawAbsolute(double degrees) {
    this.targetAngleDegrees = degrees;  // 直接覆写
}
```

绝对角度方案下，AimController 每 tick 从当前 pose 算出目标角度，直接覆写。不累积、不回积误差。

### 2. atan2 符号取反

```java
// atan2 逆时针为正，约束顺时针为正 → 取反
double targetYawRad = -(currentYawRad + yawErr) + offsetRad;
```

不取反时：目标在 90° 时炮塔指向 270°，0°/180° 正确但 90°/270° 镜像。

### 3. 无目标时保持位置

当 AimController 检测到 `|yawErr| < DEAD` 时不清除目标，也不改变 `targetAngleDegrees`。BE 的 PD 伺服自然保持当前位置——因为目标角度不变，setMotor 每 tick 以相同目标调用，位置保持。

### 4. 不归一化角度

`targetAngleDegrees` 不归一化到 [0,360)，保持连续性防止跨 0° 时走远路。

## 代码模板

```java
// === TurretBaseBlockEntity ===

private double targetAngleDegrees = 0;
private double lastTargetAngleDegrees = 0;

private static final double SERVO_STIFFNESS = 200.0;
private static final double SERVO_DAMPING = 16.0;

@Override
public void tick() {
    super.tick();
    if (level == null || level.isClientSide) return;
    if (assembled && swivelBearingHandle != null && swivelBearingHandle.isValid()) {
        updateYawServo();
    }
}

private void updateYawServo() {
    float goal = AngleHelper.rad(AngleHelper.angleLerp(1.0f,
            (float) lastTargetAngleDegrees, (float) targetAngleDegrees));
    swivelBearingHandle.setMotor(
            RotaryConstraintHandle.DEFAULT_AXIS,
            goal, SERVO_STIFFNESS, SERVO_DAMPING, false, 0.0);
    this.lastTargetAngleDegrees = this.targetAngleDegrees;
}

public void setTargetYawAbsolute(double degrees) {
    this.lastTargetAngleDegrees = this.targetAngleDegrees;
    this.targetAngleDegrees = degrees;
}
```

## 参数参考

| 参数 | 值 | 说明 |
|------|:---:|------|
| SERVO_STIFFNESS | 200.0 | 位置刚度 kP |
| SERVO_DAMPING | 16.0 | 速度阻尼 kD |
| YAW_GAIN | 80.0 | AimController 比例增益 |
| YAW_DEAD | 0.5° | 到位死区 |
| YAW_MAX_STEP | 3.0°/tick | 增量限幅（~60°/s）|

## 注意事项

1. **setMotor 在 SmartBlockEntity 下无效**：必须使用 KineticBlockEntity 或其子类
2. **setMotor velocity mode 无效**：实测 RotaryConstraint 不接受 velocity mode
3. **每 tick 必须调用 setMotor**：漏掉一次约束就会回到默认位置
4. **angleLerp 做插值**：参考 SwivelBearing，用 lastTarget/target 做 partialTick 平滑
5. **PD 参数不需要很大**：kP=200/kD=16 足够稳定，过大反而导致超调
