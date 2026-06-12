/*
 * 动力系统编译时常量 —— 发动机、变速箱、扭矩曲线、油门系统的全部硬编码参数。
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

/**
 * 动力系统所有编译时常量的集中存放处。
 * <p>
 * 修改后重新编译即可生效，无需运行时修改。
 */
public final class PowertrainConstants {

    // ====================================================================
    //  发动机参数
    // ====================================================================

    /** 发动机怠速转速（RPM）。无油门时稳定在此转速。 */
    public static final double ENGINE_IDLE_RPM = 800.0;

    /** 发动机红线转速（RPM）。最大转速上限。 */
    public static final double ENGINE_MAX_RPM = 6000.0;

    /** 基准发动机扭矩（Nm）—— 最小回退值和覆盖层显示参考。
     *  实际有效扭矩从车辆质量推导：effectiveTorque = totalMass × g × TORQUE_WEIGHT_RATIO */
    public static final double ENGINE_TORQUE = 30.0;

    /** 扭矩/重量比（Nm 扭矩每 N 车重）。0.02 → 1000 kg 的车获得 196 Nm。 */
    public static final double TORQUE_WEIGHT_RATIO = 0.02;

    // ====================================================================
    //  扭矩曲线参数
    // ====================================================================
    //  真实发动机的扭矩随 RPM 变化：怠速低扭 → 中段峰值 → 红线回落。
    //  采用 sin() 曲线模拟，激励玩家在峰值附近换挡。

    /** 怠速时扭矩占峰值的比例。0.80 = 怠速时仍有峰值扭矩的 80%。 */
    public static final double TORQUE_IDLE_FRACTION = 0.80;

    /** 扭矩曲线形状参数。值越大曲线越"尖"，峰值区越窄。 */
    public static final double TORQUE_CURVE_SHARPNESS = 1.0;

    /** 油门加速率（RPM/游戏tick）。每秒最多升高 200×20=4000 RPM */
    public static final double RPM_ACCEL = 200.0;

    /** 松油减速率（RPM/游戏tick）。每秒最多降低 150×20=3000 RPM */
    public static final double RPM_DECEL = 150.0;

    /** 负载减速率（RPM/游戏tick/负载因子）。 */
    public static final double RPM_LOAD_DECEL = 80.0;

    // ====================================================================
    //  连续油门参数
    // ====================================================================

    /** 油门位置变化率（/tick）。每秒 0→1 需要 40 tick（约 2 秒）。 */
    public static final double THROTTLE_RATE = 0.025;

    /** 油门自动衰减率（/tick）。无油门输入时逐渐归零，1→0 约 3.3 秒。 */
    public static final double THROTTLE_DECAY = 0.015;

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

    private PowertrainConstants() {}
}
