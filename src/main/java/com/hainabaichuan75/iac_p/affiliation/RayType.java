package com.hainabaichuan75.iac_p.affiliation;

/**
 * 射线类型枚举。
 * <p>
 * 描述射线的用途，与 {@link RayPolicy} 配合决定射线与不同归属的 SubLevel 的交互方式。
 * <p>
 * <b>扩展方式</b>：直接在此枚举中添加新值。
 */
public enum RayType {

    /**
     * 摄像机瞄准射线（玩家准星）：应穿透自身结构避免遮挡准星
     */
    CAMERA_AIM,
    /**
     * 武器伤害射线：应完全忽略自身和友军结构
     */
    WEAPON_DAMAGE,
    /**
     * 传感器扫描射线（预留）：未来探测/索敌用途
     */
    SENSOR_SCAN
}
