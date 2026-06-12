---
updated: 2026-06-13
status: current
maintainer: @项目协作者
---

# 3. 手刹物理模型 (Handbrake)

### 最终方案（v3）

手刹 = **轮子抱死**：

1. 驱动力切断
2. 滚动阻力切断（轮子不转，无滚动摩擦）
3. 侧滑阻尼切断（轮子不转，无回正力矩）
4. **滑动摩擦力**沿**总速度反方向**施加
5. 摩擦力幅值 = `BRAKE_STRENGTH × μ × springImpulse` ← **真实法向冲量！**

```java
if (this.braking) {
    double totalSpeed = sqrt(fwdSpeed² + latSpeed²);
    if (totalSpeed > 1e-8) {
        double brakeMag = BRAKE_STRENGTH * mu * springImpulse; // 关键：不用 frictionBasis！
        longForce = -(forwardSpeed / totalSpeed) * brakeMag;
        latForce  = -(lateralSpeed / totalSpeed) * brakeMag;
    }
}
```

### 修复历程（三次迭代）

| 轮次 | 方案 | 问题 |
|:----:|------|:----:|
| v1 | 速度比例 P 控制（目标速度=0） | 只作用于纵向 |
| v2 | 轮子抱死 + 滑动摩擦 | `brakeMag` 误用 `frictionBasis`（含 51 倍膨胀）→ 18g 钉地 |
| **v3** | **轮子抱死 + `springImpulse` 真实法向力** | ✅ 减速度 ≈ 0.35g，不钉地 |

### 物理直觉对应
- 急刹车 → 轮胎抱死 → **车体靠滑动摩擦自然减速**
- 斜坡上 → 摩擦力 ≤ μ·mg·cosθ，若重力分力更大，车会**慢慢滑下去**
- 转向中刹车 → 摩擦力沿**当前运动方向**反方向 → 真实的漂移感觉
- **减速度固定 ≈ 0.35g（`BRAKE_STRENGTH=0.5`），与车重无关**
