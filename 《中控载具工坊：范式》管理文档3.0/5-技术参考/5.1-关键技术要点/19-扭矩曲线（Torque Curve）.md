# 十九、扭矩曲线（Torque Curve）

### 设计动机

改进前（06-07 及之前）：有效扭矩与 RPM 无关，恒定为 `totalMass × g × TORQUE_WEIGHT_RATIO`。
- 任何时候踩油门，扭矩输出相同
- 低转换挡和高转换挡感觉一样
- 缺乏"找发力点"的驾驶策略

改进后（06-08）：扭矩随 RPM 变化，中段峰值，两端衰减。

### 核心公式

```java
// rpmRatio: 0.0(怠速) ~ 1.0(红线)
double rpmRange = ENGINE_MAX_RPM - ENGINE_IDLE_RPM;
double rpmRatio = (engineRpm - ENGINE_IDLE_RPM) / rpmRange;
rpmRatio = clamp(rpmRatio, 0.0, 1.0);

// 形状修正（sharpness > 1 使峰值区更窄）
double shapedRatio = pow(rpmRatio, TORQUE_CURVE_SHARPNESS);

// sin 拟合曲线
double curveMultiplier = TORQUE_IDLE_FRACTION
        + (1.0 - TORQUE_IDLE_FRACTION) * sin(π * shapedRatio);
curveMultiplier = max(curveMultiplier, 0.15);

// 应用到质量自适应扭矩
this.effectiveTorque *= curveMultiplier;
```

### 参数

| 常量 | 值 | 含义 |
|------|-----|------|
| `TORQUE_IDLE_FRACTION` | **0.80** (06-08 从 0.35 调整) | 怠速/红线时扭矩为峰值的 80%。平坦化后极速不再因耦合压低转速而扭矩崩溃 |
| `TORQUE_CURVE_SHARPNESS` | 1.0 | 峰值区宽度（1.0=标准正弦） |

### 曲线形状（06-08 平坦化后）

```
扭矩倍率
 1.0 ┼     ╱╲
     │   ╱╱  ╲╲
 0.80┼──╱─────╲───
     │ ╱       ╲
     │╱         ╲
     └──────────────→ RPM
     800  3400  6000
     idle peak  redline
```

### 驾驶策略变化（06-08 平坦化后）

| 行为 | 平坦化前 (TORQUE_IDLE_FRACTION=0.35) | 平坦化后 (0.80) |
|------|:-----------------------------------:|:----------------:|
| 怠速起步 | 低扭不足，需踩油门升转速 | 低扭充足，无需高转起步 ✅ |
| 低转换挡 | 扭矩快速爬升 | 已有足够扭矩，换挡更平滑 |
| 红线换挡 | 扭矩衰减至 35%，"光吼不走" | 仍有 80% 扭矩，红线仍有发力 |
| 极速巡航 | 耦合压低转速→扭矩崩溃→双重打击 | 极速限制由 DRAG_COEFFICIENT 单独负责 |
