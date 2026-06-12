# 十八、Brush 轮胎侧偏模型

### 核心公式

```java
double slipAngle = atan2(lateralSpeed, |forwardSpeed|);
slipAngle = clamp(slipAngle, -45°, +45°);           // 防 tan 发散

double latNormalForce = frictionBasis / dt;
double peakForce = μ × latNormalForce;
double corneringStiffness = K × peakForce;           // K = CORNERING_STIFFNESS

double input = corneringStiffness × tan(|α|) / max(peakForce, 1);
input = clamp(input, 0, π × 0.85);                  // 略超 π/2

if (input ≤ π/2) fyRatio = sin(input);              // 峰值前
else fyRatio = 1.0 - (input - π/2) × 0.25;          // 峰值后线性下降
fyRatio = max(fyRatio, 0.1);                         // ≥ 10% 残余

double fyNewtons = peakForce × fyRatio;
double brushImpulse = -sign(α) × fyNewtons × dt;
```

### 混合过渡
```java
// totalSpeed < 1.0 → 纯阻尼
// 1.0 ~ 2.0 → lerp(阻尼, Brush)
// > 2.0 → 纯 Brush
double blend = clamp((totalSpeed - 1.0) / 1.0, 0, 1);
latForce = lerp(1 - blend, dampingImpulse, brushImpulse);
```

### 行为特征
| 侧偏角 | 力状态 | 车辆行为 |
|:------:|:------:|---------|
| 0°~5° | 线性增长 | 正常转弯 |
| ~9° (旧) / ~4.5° (06-08) | 峰值 | 最大抓地。06-08 CORNERING_STIFFNESS 从 10 调整为 20，峰值提前 |
| > 9° | 下降 | 突破 → 甩尾/漂移 |
| 任意值 | 残余 ≥ 10% | 完全失去抓地前有最低保底 |

### 参数
| 常量 | 值 | 说明 |
|------|-----|------|
| `CORNERING_STIFFNESS` | **20.0** (06-08 从 10.0 调整) | 侧偏刚度（峰值在 ~4.5°） |
| `SIDE_SLIP_DAMPING` | 6.0 | 极低速阻尼系数 |
| 侧偏角钳制 | ±45° | 防止 tan(α) 发散 |
| 残余抓地 | 10% | 过峰值后下限 |
| 低速阈值 | totalSpeed > 1.0 m/s | 切换阻尼↔Brush |
