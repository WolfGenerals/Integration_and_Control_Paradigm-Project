package com.hainabaichuan75.iac_p.affiliation;

/**
 * 物理结构在归属中的角色枚举。
 * <p>
 * 每个 SubLevel 根据其在载具/系统中的功能被赋予一个 Role， 用于决定射线交互策略、组关系等。
 * <p>
 * <b>扩展方式</b>：直接在此枚举中添加新值，不破坏已有逻辑。
 */
public enum AffiliationRole {

    /**
     * 载具主体结构：驾驶舱、悬挂方块等主结构方块
     */
    VEHICLE_BODY,
    /**
     * 炮塔底座（地毯形方块）
     */
    TURRET_BASE,
    /**
     * 方向机：砂轮 SubLevel，负责水平旋转
     */
    TURRET_YAW,
    /**
     * 高低机：避雷针 SubLevel，负责俯仰
     */
    TURRET_PITCH,
    /**
     * 弹射物（预留）：未来子弹/导弹/炮弹
     */
    PROJECTILE,
    // ==================================================================
    //  武器类别（06-14 扩展）
    // ==================================================================

    /**
     * 加农炮 SubLevel：直射高伤害武器
     */
    WEAPON_CANNON,
    /**
     * 机枪 SubLevel：快速连射低伤害武器
     */
    WEAPON_MACHINEGUN,
    /**
     * 导弹发射器 SubLevel（预留）
     */
    WEAPON_MISSILE,
    /**
     * 激光武器 SubLevel（预留）
     */
    WEAPON_LASER,
    /**
     * 传感器（预留）：未来探测/雷达设备
     */
    SENSOR,
    /**
     * 未归类：临时或未知结构
     */
    UNKNOWN;

    /**
     * 安全地从字符串解析 Role，未知值返回 UNKNOWN。
     */
    public static AffiliationRole fromString(String name) {
        if (name == null || name.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
