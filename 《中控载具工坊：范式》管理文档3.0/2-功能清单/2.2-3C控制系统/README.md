# 3C 控制系统

3C 指：**载具** (Cockpit/Cabin)、**摄像机** (Camera)、**控制** (Control)。

## 功能一览

| 功能 | 状态 | 说明 | 文档 |
|------|------|------|------|
| ① 核心标记方块 (SeatBlock) | ✅ 完成 | 半砖原型，已被Cockpit替代 | [SeatBlock](SeatBlock.md) |
| ② 通用驾驶舱 (Cockpit) | ✅ 完成 | 双方块结构，替代初代 SeatBlock | [1. 通用驾驶舱](1-通用驾驶舱（Cockpit）.md) |
| ③ 上车/下车系统 | ✅ 完成 | F 键 → 射线检测 → SubLevel → 上车 | [2. 上车与下车系统](2-上车与下车系统.md) |
| ④ 轨道摄像机 | ✅ 完成 | CameraMixin 球面环绕 + 极点奇点修复 | [3. 轨道摄像机](3-轨道摄像机（Plan%20B1）.md) |
| ⑤ 自适应摄像机 | ✅ 完成 | 根据载具物理边框自动调整高度和距离 | [4. 自适应摄像机](4-自适应摄像机.md) |
| ⑥ 炮塔底座 (TurretBase) | ✅ 完成 | 地毯形方块，生成物理化砂轮+避雷针 | [5. 炮塔底座](5-炮塔底座（TurretBase）.md) |
| ⑦ WASD 智能映射 | ✅ 完成 | FACING 投票前进轴 + 质心转向分离 + Car Mode/Reverse/Toggle + 智能键存储回退 | [6. WASD智能映射](6-WASD智能映射.md) |
