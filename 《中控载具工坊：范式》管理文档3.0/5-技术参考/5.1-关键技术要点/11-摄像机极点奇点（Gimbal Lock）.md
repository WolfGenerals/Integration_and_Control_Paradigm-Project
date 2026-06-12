# 十一、摄像机极点奇点（Gimbal Lock）

### 现象
俯仰角到达 ±90°（看向正上/正下方）时，画面突然水平旋转对齐世界网格。

### 原因
球坐标系中当 `pitch = ±90°`：
```java
double dx = sin(yaw) * cos(pitch) * distance;  // cos(±90°) = 0
double dz = -cos(yaw) * cos(pitch) * distance;  // cos(±90°) = 0
// → cameraPos = (focusX, focusY ± distance, focusZ)
// → lookX = 0, lookZ = 0 → horizontalDist = 0
// → lookYaw = atan2(0, 0) * RAD_TO_DEG - 90 = -90°
```
这是**欧拉角的固有问题**，任何基于 `atan2(0,0)` 的计算都会产生不定值。

### 修复
```java
if (horizontalDist < 1e-4) {
    lookYaw = entity.getYRot(); // 沿用当前偏航，防止突变
} else {
    lookYaw = atan2(lookZ, lookX) * RAD_TO_DEG - 90.0F;
}
```
在极点附近不重新计算偏航角，而是沿用玩家当前的 `yRot`。由于俯仰 ±90° 时偏航角不影响视线方向，这个近似在视觉上不可察觉。
