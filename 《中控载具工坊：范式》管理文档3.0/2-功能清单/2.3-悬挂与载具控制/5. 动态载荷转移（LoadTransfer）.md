---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---
# 5. 动态载荷转移 (Load Transfer)

### 设计动机

改进前：所有轮子的抓地力相同，无论加速/刹车/转弯状态。车辆的姿态变化对操控无影响。

改进后（本 Session 实现）：惯性力使重心偏移，动态改变各轮法向载荷，直接影响抓地力。

### 物理直觉

```
加速 ──→ 重心后移 ──→ 后轮抓地↑ 前轮抓地↓
刹车 ──→ 重心前移 ──→ 前轮抓地↑ 后轮抓地↓
左转 ──→ 重心右移 ──→ 右轮抓地↑ 左轮抓地↓
```

### 实现原理

每 tick 从 `prevLocalVelocity` 计算局部坐标系加速度，通过驾驶舱位置确定本轮相对位置：

```java
// 确定轮位
double localPosZ = worldDx * fwdD.x() + worldDz * fwdD.z(); // 纵向 (+前)
double localPosX = worldDx * sideD.x() + worldDz * sideD.z(); // 侧向 (+右)
double normZ = clamp(localPosZ / HALF_WHEELBASE, -1, 1);
double normX = clamp(localPosX / HALF_TRACK, -1, 1);

// 载荷转移增量（占静载比例）
double longTransfer = -accelZ * COG_HEIGHT / (g * HALF_WHEELBASE) * normZ;
double latTransfer  = -accelX * COG_HEIGHT / (g * HALF_TRACK) * normX;
loadTransfer = (longTransfer + latTransfer) * LOAD_TRANSFER_SENSITIVITY;
loadTransfer = clamp(loadTransfer, -0.8, 0.8);

// 调整后的摩擦基数
double adjustedSpringImpulse = springImpulse * (1.0 + loadTransfer);
```

### 参数

| 常量 | 当前值 | 含义 |
|------|--------|------|
| `LOAD_TRANSFER_SENSITIVITY` | 0.3 | 每 g 加速度转移的载荷比例 |
| `COG_HEIGHT` | 0.8 | 估算重心高度（格） |
| `HALF_WHEELBASE` | 1.5 | 半轴距（归一化参考） |
| `HALF_TRACK` | 1.0 | 半轮距（归一化参考） |

### ⚠ 注意事项

- **需要驾驶舱**：载荷转移依赖驾驶舱位置作为原点。无驾驶舱时不生效。
- **侧向转移符号已修复 ✅**（06-08）：原 `-accelX` 已改为 `+accelX`。右转时右轮正确减载、左轮增载。
- **钳制 ±0.8**：防止单轮完全离地（即载荷转移 > 100% 的情况暂未模拟）。
