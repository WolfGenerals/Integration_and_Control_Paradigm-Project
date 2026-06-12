# 二十二、自定义世界渲染（NeoForge 1.21.1）

### 尝试过但未成功的方式
| 方式 | 结果 | 可能原因 |
|------|:----:|---------|
| `Tesselator` + `BufferUploader.drawWithShader` | ❌ 不可见 | 渲染管道已关闭或着色器绑定错误 |
| `RenderType.LINES` + `bufferSource.endBatch()` | ❌ 不可见 | 格式不兼容或 bufferSource 已刷新 |
| `RenderLevelStageEvent.AFTER_LEVEL` | — | 事件被注册到错误总线，修复后仍不显示 |

### 可尝试的替代方案
- **BlockEntityRenderer**：在 TurretBaseBlock 的 BER 中渲染（渲染上下文稳定）
- **DebugRenderer**：通过 `renderDebug` 钩子绘制
- **LevelRenderer Mixin**：直接注入 `renderLevel` 方法
- **粒子/实体方案**：在锚点位置生成 marker entity 或持续粒子流

### 关键 API
```java
// RenderLevelStageEvent — 正确的注册方式
NeoForge.EVENT_BUS.register(AxisLineRenderer.class);

// 可用阶段（按执行顺序）
// AFTER_SOLID_BLOCKS → AFTER_CUTOUT_BLOCKS → AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS
// → AFTER_ENTITIES → AFTER_BLOCK_ENTITIES → AFTER_TRIPWIRE_BLOCKS
// → AFTER_PARTICLES → AFTER_WEATHER → AFTER_LEVEL
```
