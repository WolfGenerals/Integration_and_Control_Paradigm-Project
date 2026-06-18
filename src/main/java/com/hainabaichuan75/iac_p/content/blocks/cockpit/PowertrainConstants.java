/*
 * 动力系统编译时常量 —— 发动机、变速箱、换挡系统全部参数。
 *
 * ====== 架构说明 ======
 *
 * 空档（测试架模式）：
 *   油门直控 RPM：RPM = IDLE + throttle × (MAX - IDLE)
 *   扭矩 = ENGINE_TORQUE × torqueCurve(RPM)  —— 纯 RPM 函数
 *   变速箱断开，无负载反射。
 *
 * 在档（运动学约束模式）：
 *   引擎RPM = 平均轮端RPM × 齿比 × 主减速比
 *   引擎扭矩 - 内部摩擦 = 净扭矩 → 变速箱 × 齿比 → 轮端
 *   松油 → engineTorque归零 → 引擎摩擦成为制动力 → 发动机制动
 *   换挡真空期 6 tick → 动力中断
 *
 * 轮胎是唯一的扭矩限幅器。摩擦圆截掉的部分 = 打滑/空转。
 *
 * 参考：iRacing / Assetto Corsa / rFactor 2 均采用锁止离合器 + 轮胎滑移限扭架构。
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

/**
 * 动力系统所有编译时常量的集中存放处。
 * <p>
 * 修改后重新编译即可生效，无需运行时修改。
 */
public final class PowertrainConstants {

    // ====================================================================
    //  时间步长
    // ====================================================================

    /** 引擎物理时间步长（秒/tick）。Minecraft 固定 20 TPS → 0.05s。 */
    public static final double DT = 1.0 / 20.0;

    // ====================================================================
    //  发动机参数
    // ====================================================================

    /** 发动机怠速转速（RPM）。无油门时稳定在此转速。 */
    public static final double ENGINE_IDLE_RPM = 800.0;

    /** 发动机红线转速（RPM）。最大转速上限。 */
    public static final double ENGINE_MAX_RPM = 6000.0;

    /** 发动机熄火转速阈值 — RPM ≤ 此值时熄火。 */
    public static final double ENGINE_STALL_RPM = 50.0;

    /** 发动机旋转惯量 (kg·m²)。
     *  值越小 → 油门响应越灵敏，负载拖拽越敏感。
     *  仅用于在档打滑时的 RPM 演化。空档时 RPM 由油门直控。 */
    public static final double ENGINE_INERTIA = 0.3;

    /** 基础发动机扭矩（Nm）。经扭矩曲线修正后的峰值扭矩。
     *  与车辆质量解耦——轻车加速快、重车加速慢，天然产生驾驶差异。
     *  120kg 测试车配 4 轮：20 Nm 峰值 → 怠速 16 Nm → 驱动平稳。 */
    public static final double ENGINE_TORQUE = 5;

    // ====================================================================
    //  扭矩曲线参数
    // ====================================================================
    //  真实发动机的扭矩随 RPM 变化：怠速低扭 → 中段峰值 → 红线回落。
    //  采用 sin() 曲线模拟，激励玩家在峰值附近换挡。

    /** 怠速时扭矩占峰值的比例。0.80 = 怠速时仍有峰值扭矩的 80%。 */
    public static final double TORQUE_IDLE_FRACTION = 0.80;

    /** 扭矩曲线形状参数。值越大曲线越"尖"，峰值区越窄。 */
    public static final double TORQUE_CURVE_SHARPNESS = 1.0;

    // ====================================================================
    //  连续油门参数
    // ====================================================================

    /** 油门位置变化率（/tick）。每秒 0→1 需要 40 tick（约 2 秒）。 */
    public static final double THROTTLE_RATE = 0.025;

    /** S 键主动减油门速率（/tick）。1→0 约 1.25 秒。 */
    public static final double THROTTLE_BRAKE_RATE = 0.04;

    // ====================================================================
    //  变速箱齿比
    // ====================================================================

    /** 前进档齿比（1档到5档）。5 档为 1.0 直接档。 */
    public static final double[] GEAR_RATIOS = { 4.0, 2.5, 1.6, 1.2, 1.0 };

    /** 倒车档齿比（负值表示反向旋转）。 */
    public static final double REVERSE_RATIO = -3.5;

    /** 主减速比。所有档位的齿比都要乘以此值才是最终传动比。
     *  取值 14.0 使极速约 120 km/h。 */
    public static final double FINAL_DRIVE_RATIO = 14.0;

    /** 前进档数量 */
    public static final int NUM_FORWARD_GEARS = GEAR_RATIOS.length;

    // ====================================================================
    //  发动机内部摩擦与发动机制动
    // ====================================================================
    //
    //  引擎内部摩擦 = 活塞环/曲轴轴承/气门机构的机械摩擦 +
    //                节气门关闭时的泵气损失（发动机制动）。
    //
    //  锁止时引擎摩擦直接成为轮端制动力：
    //    torquePerWheel = (engineTorque - frictionTorque) × ratio / count
    //  松油时 engineTorque ≈ 0 → frictionTorque > 0 → 轮端负扭矩 = 发动机制动。

    /** 发动机内部摩擦系数（Nm / RPM 高于怠速）。
     *  0.015 → 在 3000 RPM 时 frictionTorque = 0.015 × (3000-800) = 33 Nm。
     *  松油时这就是发动机制动力的来源。 */
    public static final double ENGINE_FRICTION_PER_RPM = 0.015;

    /** 怠速维持扭矩（Nm）。
     *  无油门输入且引擎RPM接近怠速时，提供此最小扭矩防止熄火。
     *  取值 16 Nm = 峰值 20 Nm × 扭矩曲线怠速比例 0.80，精确匹配无额外拧。 */
    public static final double IDLE_MAINTAIN_TORQUE = 4;

    // ====================================================================
    //  换挡参数
    // ====================================================================

    /** 换挡耗时（tick）。6 tick ≈ 300ms，足够产生动力中断感但不会令人烦躁。 */
    public static final int SHIFT_TIME_TICKS = 6;

    // ====================================================================
    //  辅助方法
    // ====================================================================

    /** @return 指定档位的齿比绝对值，空档/倒车返回 0 */
    public static double getRatioForGear(int gear) {
        if (gear <= 0) return 0;
        if (gear > NUM_FORWARD_GEARS) return 0;
        return GEAR_RATIOS[gear - 1];
    }

    /** @return 当前齿比（含符号），空档返回 0 */
    public static double getCurrentRatio(int currentGear) {
        if (currentGear == 0) return 0;
        if (currentGear == -1) return REVERSE_RATIO;
        return GEAR_RATIOS[currentGear - 1];
    }

    /** @return 档位的人类可读名称 */
    public static String gearName(int gear) {
        return switch (gear) {
            case -1 -> "R";
            case 0 -> "N";
            default -> String.valueOf(gear);
        };
    }

    /** @return 有效传动比 = |齿比| × 主减速比 */
    public static double getEffectiveRatio(int gear) {
        if (gear == 0) return 0;
        double ratio = getCurrentRatio(gear);
        return Math.abs(ratio) * FINAL_DRIVE_RATIO;
    }

    private PowertrainConstants() {}
}
