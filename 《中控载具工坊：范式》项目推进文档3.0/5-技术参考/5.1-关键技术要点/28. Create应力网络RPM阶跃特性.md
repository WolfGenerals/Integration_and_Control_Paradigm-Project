# Create 应力网络 RPM 阶跃特性

> 适用场景：任何依赖 Create RPM 进行控制的系统（齿轮驱动、PD 伺服、抽象齿轮模式等）
> 验证日期：2026-06-10
> 验证工具：DebugGearBlock（N 键切换，每 tick 打印 RPM）

---

## 核心事实

**Create 应力网络的 RPM 变化是瞬时的（阶跃），不存在任何渐变/加减速过程。**

```
tick=7509 speed=+0.0  angular=+0.00°/t RPM=+0     ← 静止
tick=7510 speed=-16.0 angular=-2.29°/t RPM=-16    ← 第1 tick 突然 -16
tick=7526 speed=-88.0 angular=-12.60°/t RPM=-88   ← 第17 tick 突然 -88
tick=7554 speed=+0.0  angular=+0.00°/t RPM=+0     ← 下1 tick 突然归零
```

## 实际含义

| 你期望的行为 | 实际行为 | 影响 |
|------------|---------|------|
| 启动加速：0 → 80 RPM 需要 N tick 渐变 | 0 → ±80 RPM 在 **1 tick** 内完成 | 不能用于"渐变驱动"的控制逻辑 |
| 停止减速：80 → 0 RPM 缓慢滑行 | ±80 → 0 RPM 在 **1 tick** 内完成 | 角度累积无法自然收敛 |
| RPM 变化速率可控 | RPM 变化速率**不可控**（完全由应力网络决定） | 不能依赖 RPM 作为"平滑驱动"的输入 |

## 对控制架构的影响

### ❌ 抽象齿轮模式被证伪

抽象齿轮模式依赖：
```
RPM → 角度累积（开环积分器） + PD 伺服（闭环控制器） = 双重积分器系统
```

由于 RPM 是阶跃变化：
1. 误差大 → RPM=±80 → 角度高速累积
2. 误差小 → RPM=0 → 累积停止
3. **问题**：从±80→0 的瞬间，物理惯性仍在转动 → 超过目标 → PD 回调 → 震荡

### ✅ 位置模式 PD 伺服（SwivelBearing 模式）

```
setMotor(DEFAULT_AXIS, goal, kP, kD, false, 0)  // position mode
```

不依赖 RPM，不累积角度，每 tick 直接设定位置目标。无论 RPM 如何变化，PD 伺服始终保持在目标位置。

## 验证方法

使用 `DebugGearBlock`：
1. 放置 DebugGearBlock + 创造马达
2. 对准齿轮按 N 键开启调试输出
3. 观察游戏日志 `[DebugGear]` 输出
4. 调整马达速度/连接/断开，观察 RPM 变化模式

## 注意事项

- `getSpeed()` 返回的 float 值 1:1 对应 RPM（1 speed = 1 RPM，非角度单位）
- `convertToAngular(speed)` 将 speed 转为 °/tick（Minecraft 游戏刻）
- DebugGear 每 tick 全速打印，建议在测试时使用 `/gamerule randomTickSpeed 0` 减少干扰
