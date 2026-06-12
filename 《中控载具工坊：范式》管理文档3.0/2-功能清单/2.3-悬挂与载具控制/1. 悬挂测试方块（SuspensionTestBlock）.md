---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 1. 悬挂测试方块 (SuspensionTestBlock)

### 设计目的
创建独立于 Create 动力系统的悬挂+轮子物理测试方块，用于验证：
1. 悬挂弹簧-阻尼力对 SubLevel 刚体的作用
2. 轮子与地面的交互（射线检测、摩擦力、被动滚动）
3. 视觉偏移的可配置性（编译时常量）

### 与 Offroad WheelMountBlock 的差异

| 特性 | WheelMountBlock（offroad） | SuspensionTestBlock（本项目） |
|------|---------------------------|------------------------------|
| 动力输入 | 需要 Create 转速网络 | ❌ 无，由座舱动力系统提供 |
| 红石转向 | 两侧红石信号差值控制 | ❌ 无 |
| 顶部滚轮 UI | 悬挂强度调节 | ❌ 无 |
| 右键交互 | 任意面可装卸轮子 | ✅ 仅限于悬挂面（facing 方向） |
| 悬挂强度 | 运行时滚轮调节 | ✅ 编译时常量 |
| 视觉偏移 | 固定硬编码 | ✅ 编译时常量，4 组 19 个值 |
| 方块模型 | 有纹理 | ✅ 全透明（`block/air`） |
| 方块实体基类 | `KineticBlockEntity` | ✅ `SmartBlockEntity`（轻量） |

### 视觉偏移常量（4 组 19 个值）

所有偏移在 `SuspensionConstants` 中定义为 `static final`。

坐标系：旋转到 facing 方向后，**X=侧向、Y=垂直、Z=纵深**。

| 组 | 常量 | 默认值 | 含义 |
|----|------|--------|------|
| 弹簧 | `SPRING_BLOCK_X/Y/Z` | 7/16, 7/16, 0 | 弹簧在方块侧的附着点 |
| 弹簧 | `SPRING_WHEEL_X/Y/Z` | 0, -2/16, 12/16 | 弹簧在轮子侧的附着点 |
| 转向轴 | `PIVOT_BLOCK_X/Y/Z` | 0, -6/16, 0 | 转向轴在方块侧的支点 |
| 转向轴 | `PIVOT_WHEEL_X/Y/Z` | 0, 0, 0 | 转向轴在轮子侧的连接点 |
| 轮轴 | `WHEEL_PIVOT_X/Y/Z` | 0, 0, 10/16 | 轮轴支点 |
| 轮轴 | `WHEEL_POS_X/Y/Z` | 0, 0, 22/16 | 轮子最终渲染位置 |
| 减震 | `SPRING_STIFFNESS_PER_NM` | 400 | 弹簧刚度基数（N/m per nm），重车封顶 2000 N/m |

> 单位为 Minecraft 格（1 格 = 1.0）。常用分数值如 `7.0/16.0 = 0.4375`。

### 运行时参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `throttleForward` | boolean | 是否正在加油前进 |
| `throttleBackward` | boolean | 是否正在加油后退 |
| `braking` | boolean | 是否正在刹车 |
| `targetSteeringYaw` | double | 当前目标转向角（弧度） |
| `currentSteeringYaw` | double | 当前实际转向角（匀速过渡） |

### 外部接口

```java
setTargetSteeringYaw(double radians)  // 设置目标转向角，自动钳制 ±MAX_STEERING_ANGLE
getTargetSteeringYaw()                // 获取当前目标转向角
hasThrottle()                         // 是否有任一方向油门
isLifted()                            // 是否因悬挂拉伸被抬起
```
