---
updated: 2026-06-14
status: current
maintainer: @项目协作者
---

# 三十二、下车坐标系：玩家位置 vs SubLevel 底层坐标

## 背景

手刹下车和 F 下车时，玩家被传送到错误位置——手刹下车掉到车底，F 下车飞到百米高空。

## 根因

`findGroundDismountPosition()` 和 `findVehicleTopDismountPosition()` 混用了两套坐标系：

| 来源 | 含义 | 问题 |
|------|------|------|
| `logicalPose().position()` | SubLevel 在物理世界空间中的位置（随物理引擎运动） | 只代表 SubLevel 原点的世界位置 |
| `collectSubLevelBlockPositions()` | SubLevel plot chunk 的**底层固定世界坐标**（不随物理运动更新） | 当 SubLevel 掉落/被抬升后，Y 值与物理位置相差数百格 |

当 SubLevel 物理移动后（如掉下悬崖或被抬升），两套 Y 坐标偏差巨大，搜索中心与碰撞检测使用不同坐标系，导致下车位置完全错误。

## 解决方案

### 1. 搜索锚点统一用 `player.position()`

玩家在骑乘时每 tick 通过 `logicalPose.transformPosition(cockpitLocalPos)` 精确同步到驾驶舱的世界位置，这是最可靠的世界空间参考点。改用 `player.position()` 替代 `getSubLevelPosition()` 作为搜索锚点。

### 2. 新增 `collectSubLevelBlockPositionsWorldSpace()`

通过 `logicalPose.transformPosition()` 将 SubLevel 方块从底层坐标变换到世界空间：

```java
var pose = subLevel.logicalPose();
SubLevelScanner.forEachBlockState(subLevel, level, (worldPos, state) -> {
    if (!state.isAir()) {
        Vector3d localCenter = new Vector3d(
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        Vector3d worldCenter = new Vector3d();
        pose.transformPosition(localCenter, worldCenter);
        BlockPos wp = BlockPos.containing(worldCenter.x(), worldCenter.y(), worldCenter.z());
        positions.add(wp);
        positions.add(wp.above());
    }
});
```

### 3. 手刹下车增加向上遮挡检测

找到候选地面位置后，向上 5 格检查是否有车辆结构挡住。如果有，跳过该位置继续搜索。

### 4. 新增实验性直传模式

由 `Config.java` 运行时配置 `DISMOUNT_EXPERIMENTAL_DIRECT` 控制。开启后 F 下车跳过所有安全搜索，直接通过 `collectSubLevelBlockPositionsWorldSpace()` 找到最高方块，将玩家传送到 `topY + 0.99`（轻微碰撞重叠防虚空坠落）。

## 关键代码文件

| 文件 | 职责 |
|------|------|
| `events/ServerMountHandler.java` | 下车位置搜索全部逻辑 |
| `Config.java` | 运行时配置 `DISMOUNT_EXPERIMENTAL_DIRECT` |

## 相关方法

| 方法 | 用途 |
|------|------|
| `findGroundDismountPosition()` | 手刹下车：从 `player.position()` 出发搜索地面 + 向上 5 格遮挡检测 |
| `findVehicleTopDismountPosition()` | 普通 F 下车：世界空间找最高方块 + 安全检测 + 下沉 0.4 格 |
| `findDirectTopPosition()` | 实验性直传：世界空间找最高方块 → 直接站 `topY + 0.99` |
| `collectSubLevelBlockPositionsWorldSpace()` | 将底层坐标通过 `logicalPose` 变换到世界空间 |
| `fallbackDismountPosition()` | 回退逻辑：以玩家当前位置为中心水平/竖直搜索 |

## 参考

- `5-技术参考/5.1-关键技术要点/3-SubLevel坐标：两种变换要分清.md`
- `ServerMountHandler.java`
