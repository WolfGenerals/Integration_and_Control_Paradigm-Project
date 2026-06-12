---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 7. 轮胎物理参数系统（Tire Physics）

### 设计原则

```
轮胎款式决定所有物理属性（半径/胎宽/刚度/滚阻/摩擦系数等）
                          ↓
            玩家唯一可调 = 胎压（打多少气）
```

### 设计动机与演进

- **初始阶段（06-01~06-07）**：7 个轮胎参数全部作为运行时 NBT 持久化字段（treadWidth、carcassStiffness、maxPressure、nominalPressure、tireVolume、crrBase、crrDeformationGain），C 键菜单可任意编辑
- **问题**：这是早期开发调试的遗留功能。普通玩家面对 7 个陌生物理参数（胎面宽度、胎体刚度、容积…）不知所措
- **06-09 精简**：移除 6 个运行时参数，改回编译时常量。**仅保留 `nominalPressure`（胎压）** 作为玩家唯一可调的运行时参数

### 爆胎模型

```
有效胎压 = 标称胎压 + 负载压升
爆胎条件：有效胎压 × 大气衰减因子 > 最大安全胎压

大气衰减因子 = P_atm / P_seaLevel = exp(-海拔 / 8400)
```

- 胎压越高 → 形变越小 → 滚阻越小 → 省油但爆胎风险增大
- 胎压越低 → 形变越大 → 滚阻越大 → 费油（underInflationPenalty）
- 海拔越高 → 大气压越低 → 爆胎更容易
- 负重越大 → 胎压升高 → 爆胎风险增大

### 滚动阻力模型

```
Crr_effective = Crr_base
              + Crr_deformation × (形变 / 半径)
              + 0.06 × max(0, P_nominal / P_eff - 1)²   ← 亏气惩罚

滚动阻力冲量 = -v × Crr_effective × nm × dt
```

### 编译时默认值（当前 4 种轮胎的通用默认值）

| 常量 | 默认值 | 物理含义 |
|------|--------|---------|
| `DEFAULT_TREAD_WIDTH` | 0.25 m | 胎面宽度 |
| `DEFAULT_CARCASS_STIFFNESS` | 200,000 N/m | 胎体刚度 |
| `DEFAULT_MAX_PRESSURE` | 350,000 Pa (3.5 bar) | 最大安全胎压 |
| `DEFAULT_NOMINAL_PRESSURE` | 220,000 Pa (2.2 bar) | 默认胎压 |
| `DEFAULT_TIRE_VOLUME` | 0.05 m³ | 胎内容积 |
| `DEFAULT_CRR_BASE` | 0.035 | 基础滚动阻力系数 |
| `DEFAULT_CRR_DEFORMATION_GAIN` | 0.08 | 形变附加滚阻增益 |

### ⚠ 踩坑教训

- **不要将轮胎物理属性全部暴露给玩家**。初期 7 个可调参数属于调试残留，普通玩家不需要也不应该面对胎体刚度/胎内容积等参数
- **轮胎款式应决定物理属性**。未来扩展 TireType 注册表时，每种轮胎（越野胎/赛车胎/重载胎）应内置完整的物理规格，玩家只需选胎和打气
- **NBT 字段膨胀要警惕**。每个运行时字段都是持久化负担、网络同步开销和未来兼容包袱。能用编译时常量的，就不要做成运行时字段
