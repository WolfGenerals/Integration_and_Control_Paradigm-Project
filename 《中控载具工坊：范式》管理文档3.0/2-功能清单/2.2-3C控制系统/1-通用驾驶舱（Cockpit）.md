---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 1. 通用驾驶舱 (Cockpit)

### 结构
- **下格** (`CockpitBlock`)：炼药锅形状（底部实心 + 四壁）
- **上格** (`CockpitUpperBlock`)：脚手架形状（底部薄 slab + 四壁 + 顶面）
- **物品**：仅下格有 `BlockItem`，上格无物品（类似木门）

### 行为
- **放置**：对着方块顶部或侧面放置，自动向上延伸两格，需上方一格空气
- **破坏**：破坏任一部分 → 连带破坏另一半（35 flag 抑制重复掉落），仅下格战利品表产生掉落
- **硬度/抗爆**：3.0f（信标级）

### 驾驶舱组定义

使用直接方块实例比较（`Set<Block>`），不依赖 Tag 系统：

```java
// 两个驾驶舱组：通用驾驶舱 和 初代 SeatBlock
private static final Set<Block> GROUP_GENERAL = Set.of(ModBlocks.COCKPIT.get(), ModBlocks.COCKPIT_UPPER.get());
private static final Set<Block> GROUP_CORE_0 = Set.of(ModBlocks.SEAT.get());
private static final List<Set<Block>> ALL_COCKPIT_GROUPS = List.of(GROUP_GENERAL, GROUP_CORE_0);

// 被视为"核心下半截"的方块（一个组内只能有一个）
private static final Set<Block> CORE_LOWER_HALVES = Set.of(ModBlocks.COCKPIT.get(), ModBlocks.SEAT.get());
```

这种设计便于后续扩展：添加新驾驶舱类型只需在新的 `Set<Block>` 中注册。
