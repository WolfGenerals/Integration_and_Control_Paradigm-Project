---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 4. 载具按键控制系统 (ControlSystem)

### 按键绑定（每方块独立持久化）

| 索引 | 动作 | 默认键 | NBT 字段 |
|:----:|------|:------:|----------|
| 0 | 前进 | W | KeyForward |
| 1 | 后退 | S | KeyBackward |
| 2 | 左转 | A | KeyLeft |
| 3 | 右转 | D | KeyRight |
| 4 | 刹车 | 空格 | KeyBrake |

### 配置界面
- **快捷键**：C（`key.iac_p.vehicle_config`）
- **触发条件**：对着悬挂测试方块（5 格范围内）按下 C 键
- **界面内容**：5 个可点击按钮 + 「应用」按钮 + 「清空」按钮
- **操作方式**：点击按钮后按下任意键即设置，再次点击取消监听

### 输入机制
- **客户端检测**：每 2 ticks 扫描骑乘 SubLevel 内所有悬挂方块
- **服务端执行**：收到 `VehicleControlC2SPacket` 后调用 `applyControlInput()`
- **发送优化**：仅按键状态变化时才发送数据包

### 物理响应

| 输入 | 效果 |
|------|------|
| 前进 | `throttleForward = true` → CockpitBE 升速 → 变速箱 → 差速分配驱动力 |
| 后退 | `throttleBackward = true` → 同上，倒挡齿比 (-3.5) 反转轮向 |
| 左转 | `targetSteeringYaw = +MAX_STEERING_ANGLE` → 匀速左转 |
| 右转 | `targetSteeringYaw = -MAX_STEERING_ANGLE` → 匀速右转 |
| 刹车 | 发动机怠速，轮子抱死 + 滑动摩擦 |
| 无输入 | 油门清除 → 发动机怠速，滑行减速，转向回中 |

### 驱动常量（降级模式备选）

```java
private static final double FALLBACK_DRIVE_RPM = 400.0;    // 降级驱动 RPM
private static final double FALLBACK_DRIVE_TORQUE = 80.0;  // 降级驱动扭矩
```

> 有驾驶舱时，这些值会被座舱动力系统覆盖。

### 差速器参数（编译时常量）

差速器在转向时允许内外轮有转速差，减小转弯阻力：

| 常量 | 当前值 | 含义 |
|------|:------:|------|
| `DIFFERENTIAL_RATIO` | **0.37** (06-09 从 0.3 调整) | 差速器滚动半径比。`diffOffset = chasingYaw × (localPosX/HALF_TRACK) × 此值`。30°转向、轮距1格时约 ±19% 轮速差。值越大转弯越灵活，内侧轮打滑风险增加 |

```java
// 转弯时按轮位和转向角微调目标 RPM
double diffOffset = this.chasingYaw * normX * DIFFERENTIAL_RATIO;
targetRpm *= (1.0 + diffOffset);
```
