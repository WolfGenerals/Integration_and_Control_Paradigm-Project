# 二十一、客户端事件注册：@EventBusSubscriber vs NeoForge.EVENT_BUS.register

### 教训
`RenderLevelStageEvent`（以及所有游戏运行时事件）触发于 **游戏事件总线**（`NeoForge.EVENT_BUS`）。`@EventBusSubscriber(modid = IACP.MODID)` 默认注册到**模组事件总线**，两总线独立。

### 项目内的正确做法

```java
// ✅ 正确：在 IACPClient 构造函数中手动注册
NeoForge.EVENT_BUS.register(ClientEvents.class);
NeoForge.EVENT_BUS.register(AxisLineRenderer.class);
NeoForge.EVENT_BUS.register(VehicleDebugOverlay.class);

// ❌ 错误：只靠 @EventBusSubscriber 注解（注册到模组总线，收不到游戏事件）
@EventBusSubscriber(modid = IACP.MODID, value = Dist.CLIENT) // ← 无用！除非 bus = Bus.GAME
```

### 诊断方法
在事件处理方法中加日志输出，观察是否被调用。如果没有任何日志，99% 是注册总线不对。
