# 三、SubLevel 坐标：两种变换要分清

| 你要做什么 | 用哪种方式 |
|-----------|-----------|
| 找出 SubLevel 内有哪些方块（内容扫描） | `getLoadedChunks()` + chunk 偏移 |
| 确定 SubLevel 在世界中的物理位置 | `logicalPose().transformPosition()` |
| 获取渲染用的平滑插值位置 | `clientSubLevel.renderPose(partialTick)` |
| 给 SubLevel 施加物理力 | `logicalPose().transformPosition()` 定位力施加点 |

```java
// ✅ 方块访问
for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
    int chunkMinX = chunk.getPos().getMinBlockX();
    int chunkMinZ = chunk.getPos().getMinBlockZ();
    BlockPos worldPos = new BlockPos(x + chunkMinX, y, z + chunkMinZ);
}

// ✅ 空间定位
Pose3dc pose = subLevel.logicalPose();
pose.transformPosition(new Vector3d(localX, localY, localZ), temp);
```
