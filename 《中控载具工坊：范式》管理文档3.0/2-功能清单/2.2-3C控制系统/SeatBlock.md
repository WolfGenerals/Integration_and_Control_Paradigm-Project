---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 核心标记方块 (SeatBlock)

### 概述
半砖形状的标记方块，作为载具核心概念的**最早原型**。已被 `CockpitBlock`（通用驾驶舱）取代，保留作为技术参考模板。

### 差异对比

| 特性 | SeatBlock（初代） | CockpitBlock（当前） |
|------|:-----------------:|:--------------------:|
| 形状 | 半砖（单方块） | 双方块结构（炼药锅+脚手架） |
| 结构完整性 | ❌ 无检查 | ✅ 下格+上格完整性检查 |
| 状态 | 保留为模板 | 当前标准实现 |

### 参考
- 当前标准实现：`CockpitBlock` / `CockpitBlockEntity`
- 注册：`ModBlocks.SEAT`
- 源码：`content/blocks/seat/SeatBlock.java`
