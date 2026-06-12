---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---
# 6. Brush 轮胎侧偏模型 (Brush Tire)

### 设计动机

改进前（v3）：侧向力 = `-lateralSpeed × SIDE_SLIP_DAMPING × nm × dt`（纯阻尼）
- 永远随速度增大侧向力 → 无法模拟失控临界点
- 漂移/甩尾缺乏"突破阈值"的感觉

改进后：Brush 刷子模型，峰值抓地后力下降，模拟漂移。

### 06-08 调整：CORNERING_STIFFNESS 10 → 20

`MIN_IMPULSE_MULTIPLIER` 从 500 降到 30 后，法向力基数大幅降低，侧偏刚度（= K × μ × N）同步下降，导致直线回正力不足（车会自己打滑）。将 K 从 10 提高到 20：
- 侧向回正力翻倍，恢复直线稳定性
- 峰值抓地角从 9° 收窄到 4.5°，漂移临界点更清晰

### 核心公式

```java
// 侧偏角
double slipAngle = atan2(lateralSpeed, |forwardSpeed|);
slipAngle = clamp(slipAngle, -45°, +45°); // 防止 tan(α) 爆炸

// 法向力
double latNormalForce = frictionBasis / dt;
double peakForce = μ × latNormalForce;

// Brush 模型
double input = K × peakForce × tan(|α|) / peakForce; // K = CORNERING_STIFFNESS
input = clamp(input, 0, π × 0.85);

if (input ≤ π/2) fyRatio = sin(input);           // 峰值前：经典 Brush
else fyRatio = 1.0 - (input - π/2) × 0.25;       // 峰值后：线性下降
fyRatio = max(fyRatio, 0.1);                      // 残余抓地 ≥ 10%

double fyNewtons = peakForce × fyRatio;
double brushImpulse = -sign(slipAngle) × fyNewtons × dt;
```

### 物理行为

| 侧偏角 α | 行为 | 感觉 |
|:---------:|------|------|
| α ≈ 0° | 线性区，Fy ≈ -K·α | 方向感清晰 |
| α ≈ 9° | 峰值抓地 | 最大过弯能力 |
| α > 9° | 力下降 | 突破极限 → 甩尾/漂移 🔥 |

### 平滑过渡与防抖

| 速度范围 | 模型 | 说明 |
|:---------:|------|------|
| totalSpeed < 1.0 m/s | 纯阻尼 | 侧滑阻尼 = 6.0 × nm × dt，防自旋 |
| 1.0 ~ 2.0 m/s | 线性混合 | `lerp(阻尼, Brush, blend)` 平滑过渡 |
| > 2.0 m/s | 纯 Brush | 完整侧偏模型 |

### 参数（06-08 更新）

| 常量 | 当前值 | 含义 |
|------|--------|------|
| `CORNERING_STIFFNESS` | **20.0** (从 10.0 调整) | 侧偏刚度。`MIN_IMPULSE_MULTIPLIER` 降低后回正刚度不足，翻倍补偿 |
| `SIDE_SLIP_DAMPING` | 6.0 | 极低速时阻尼系数 |
| 侧偏角钳制 | ±45° | 防止 tan(α) 发散 |
| 残余抓地 | 10% | 过峰值后最低抓地 |
