---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 一、BlockEntity 接口选择

这是**代价最高**的教训之一。

| 接口 | 提供 ticker？ | 适用于 |
|------|:-----------:|--------|
| `EntityBlock`（原版） | ❌ 不提供 | 不需要每 tick 更新的纯数据 BE |
| `IBE<T>`（Create） | ✅ `getTicker()` | 需要 `tick()` 的 `SmartBlockEntity` |

**症状**：`tick()` 中加 `LOGGER.debug()` 没输出，断点打不进。

**规则**：在任何需要 `tick()` 的 Create BE 上，**必须用 `IBE`**。写完后先看一眼日志能否打出 tick 内容。

```java
// ✅ 正确用法
public class CockpitBlock extends Block implements IBE<CockpitBlockEntity> {
    @Override
    public Class<CockpitBlockEntity> getBlockEntityClass() { return CockpitBlockEntity.class; }

    @Override
    public BlockEntityType<? extends CockpitBlockEntity> getBlockEntityType() {
        return ModCockpitBlockEntityTypes.COCKPIT_BE.get();
    }
}
```
