# 九、setPos vs teleportTo

```java
// ✅ setPos() — 不触发位置同步包，无 lerpSteps 插值
player.setPos(center.x(), seatY, center.z());

// ❌ teleportTo() — 发送 ClientboundPlayerPositionPacket → 3 tick 插值抖动
player.teleportTo(center.x(), seatY, center.z());
```
