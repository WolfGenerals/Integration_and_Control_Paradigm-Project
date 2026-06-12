---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 3. 轨道摄像机 (Plan B1)

### 为什么需要 Plan B
早期方案采用"服务端每 tick 传送玩家到 SubLevel 逻辑位置"，导致：
1. 玩家实体位置每 tick 被强制设置 → 破坏客户端插值连续性
2. SubLevel 的渲染位置 (`renderPose`) 有自己的插值逻辑
3. 两者不同步 → 画面抖动

### 最终方案：轨道摄像机
上车后相机不跟随玩家实体，而是直接绑定到 SubLevel 的 `renderPose()`（Create Simulated 已处理好的平滑插值变换）。

```java
// CameraMixin.java - @Inject(method = "setup", at = @At("TAIL"))
ClientSubLevel clientSubLevel = ClientMountHandler.getMountedClientSubLevel();
Pose3dc renderPose = clientSubLevel.renderPose(partialTick); // 平滑插值位姿

// 球坐标 → 世界坐标
double dx = sin(yaw) * cos(pitch) * distance;
double dy = sin(pitch) * distance; // 受 CAMERA_INVERT_Y 控制
double dz = -cos(yaw) * cos(pitch) * distance;

this.setPosition(new Vec3(focusX + dx, focusY + dy, focusZ + dz));
this.setRotation(lookYaw, lookPitch); // 始终看向焦点
```

### 极点奇点修复
当俯仰角达到 ±90°（看向正上方/正下方）时，`cos(pitch)=0` 导致摄像机水平位置 `dx=dz=0`，进而 `horizontalDist=0` → `atan2(0,0)=0` → 偏航角突变为 `-90°`。

```java
if (horizontalDist < 1e-4) {
    lookYaw = entity.getYRot(); // 沿用当前偏航，防止突变
} else {
    lookYaw = atan2(lookZ, lookX) * RAD_TO_DEG - 90.0F;
}
```

### 球坐标符号说明
```
Minecraft 坐标系 (Yaw 0 = 南/+Z, Yaw 顺时针增加):
  dx = +sin(yaw) * cos(pitch) * distance
  dz = -cos(yaw) * cos(pitch) * distance
  → yaw=0 时 dz=-distance（北侧，即"背后"）✅
```

### 朝向计算中的 `-90°` 修正
```java
float lookYaw = (float) Mth.atan2(lookZ, lookX) * Mth.RAD_TO_DEG - 90.0F;
```
源于 `atan2` 的零度方向（东/+X）与 Minecraft 偏航零度方向（南/+Z）的差异。

### 约束
- 上车时强制 `THIRD_PERSON_BACK`，F5 被锁定
- 下车时恢复 `FIRST_PERSON`

### 相关文档
- 自适应摄像机系统详见 `4. 自适应摄像机.md`
- 极点奇点技术分析详见 `5-技术参考/5.1-关键技术要点/11. 摄像机极点奇点（Gimbal Lock）.md`
