package com.hainabaichuan75.iac_p.affiliation;

/**
 * SubLevel 内部功能部件的角色枚举。
 * <p>
 * 描述一个方块在载具中的功能定位，用于 {@link ComponentRegistry} 的索引和查询。
 * <p>
 * <b>与 {@link AffiliationRole} 的区别</b>：
 * <ul>
 * <li>{@link AffiliationRole} 描述物理结构（SubLevel）在归属系统中的角色</li>
 * <li>{@link ComponentRole} 描述单个方块在物理结构内部的功能角色</li>
 * </ul>
 * <p>
 * <b>扩展方式</b>：直接在此枚举中添加新值，不破坏已有逻辑。 建议遵循 {@code CATEGORY_SPECIFIC} 的命名模式。
 */
public enum ComponentRole {

    // ==================================================================
    //  核心功能部件
    // ==================================================================
    /**
     * 驾驶舱：载具动力系统的控制中心和驾驶员接口
     */
    COCKPIT,
    /**
     * 悬挂/轮子：载具移动装置，负责地面物理交互
     */
    SUSPENSION,
    /**
     * 机枪底座：武器系统的安装基座
     */
    MACHINE_GUN_BASE,
    /**
     * 方向机（砂轮 SubLevel 对应方块的占位标记）：负责炮塔水平旋转
     */
    MACHINE_GUN_YAW,
    /**
     * 高低机（避雷针 SubLevel 对应方块的占位标记）：负责炮塔俯仰
     */
    MACHINE_GUN_PITCH,
    /**
     * 霰弹枪底座：霰弹枪的安装基座
     */
    SHOTGUN_BASE,
    /**
     * 霰弹枪方向机：负责枪塔水平旋转
     */
    SHOTGUN_YAW,
    /**
     * 霰弹枪高低机：负责枪塔俯仰
     */
    SHOTGUN_PITCH,
    // ==================================================================
    //  武器类别（未来扩展）
    // ==================================================================

    /**
     * 加农炮：直射高伤害武器
     */
    WEAPON_CANNON,
    /**
     * 机枪：快速连射低伤害武器
     */
    WEAPON_MACHINEGUN,
    /**
     * 导弹发射器：曲射/追踪武器（预留）
     */
    WEAPON_MISSILE,
    /**
     * 激光武器：持续伤害/精确瞄准（预留）
     */
    WEAPON_LASER,
    // ==================================================================
    //  性能修饰部件（未来扩展）
    // ==================================================================

    /**
     * 引擎增强器：提升发动机扭矩/功率
     */
    MODIFIER_ENGINE,
    /**
     * 悬挂增强器：提升弹簧刚度/阻尼
     */
    MODIFIER_SUSPENSION,
    /**
     * 装甲板：降低部件伤害
     */
    MODIFIER_ARMOR,
    /**
     * 轻量化框架：降低质量系数
     */
    MODIFIER_LIGHTWEIGHT,
    /**
     * 燃料箱/能量核心：续航相关（预留）
     */
    MODIFIER_POWER,
    // ==================================================================
    //  特殊用途
    // ==================================================================

    /**
     * 传感器/探测设备（预留）
     */
    SENSOR,
    /**
     * 货舱/运载空间（预留）
     */
    CARGO,
    /**
     * 普通结构件：无特殊功能，仅增加质量和结构完整性
     */
    STRUCTURE,
    /**
     * 未归类
     */
    UNKNOWN;

    /**
     * 判断此角色是否为武器类别。
     */
    public boolean isWeapon() {
        return this == MACHINE_GUN_BASE || this == MACHINE_GUN_YAW || this == MACHINE_GUN_PITCH
                || this == SHOTGUN_BASE || this == SHOTGUN_YAW || this == SHOTGUN_PITCH
                || this == WEAPON_CANNON || this == WEAPON_MACHINEGUN
                || this == WEAPON_MISSILE || this == WEAPON_LASER;
    }

    /**
     * 判断此角色是否为性能修饰部件。
     */
    public boolean isModifier() {
        return this == MODIFIER_ENGINE || this == MODIFIER_SUSPENSION
                || this == MODIFIER_ARMOR || this == MODIFIER_LIGHTWEIGHT
                || this == MODIFIER_POWER;
    }

    /**
     * 安全地从字符串解析，未知值返回 UNKNOWN。
     */
    public static ComponentRole fromString(String name) {
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
