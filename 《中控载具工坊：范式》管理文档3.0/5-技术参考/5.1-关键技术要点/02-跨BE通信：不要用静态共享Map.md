# 二、跨 BE 通信：不要用静态共享 Map

```java
// ❌ 错误：putIfAbsent 陷阱
public static void reportThrottle(UUID uuid, boolean active) {
    if (active) {
        THROTTLE_STATES.put(uuid, TRUE);         // ✅ 油门：无条件覆盖
    } else {
        THROTTLE_STATES.putIfAbsent(uuid, FALSE); // ❌ 松油：不会覆盖已有值！
    }
}
```

配合 `enqueueWork` 的写入时序晚于 BE tick 写入，导致 RPM 永远不降。

**最终方案**：放弃所有跨 BE 共享状态。CockpitBE.tick() 直接扫描 SubLevel 所有悬挂方块，没有中间商。

```java
// ✅ 正确：直接扫描
private boolean scanSuspensionThrottle(SubLevel sl) {
    for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
        // ... 遍历 chunk 内方块 ...
        if (be instanceof SuspensionTestBlockEntity sbe && sbe.hasThrottle()) {
            return true;
        }
    }
    return false;
}
```
