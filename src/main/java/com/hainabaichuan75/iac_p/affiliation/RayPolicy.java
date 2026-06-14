package com.hainabaichuan75.iac_p.affiliation;

/**
 * 射线与 SubLevel 的交互策略枚举。
 * <p>
 * 定义不同类型的射线命中不同归属的 SubLevel 时应如何处理。
 */
public enum RayPolicy {

    /**
     * 正常阻挡：射线在此命中并停止
     */
    BLOCK,
    /**
     * 穿透外层 AABB：射线不在此 SubLevel 的物理 AABB 处停止， 但继续前进后如果命中内部方块仍会停止。
     * <p>
     * 适用于"自车结构不挡准星，但实心方块仍会遮挡"的效果。
     */
    PENETRATE_AABB,
    /**
     * 完全无视：射线与此 SubLevel 无任何交互，直接穿透
     */
    IGNORE,
    /**
     * 阻挡并造成伤害：武器专用，命中后触发伤害逻辑
     */
    DAMAGE
}
