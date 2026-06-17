/*
 * 发动机模型 —— 扭矩源模型。
 *
 * 核心物理关系：
 *   1. 发动机扭矩 = 基础扭矩 × 扭矩曲线 × 油门位置
 *   2. 净扭矩 = 发动机扭矩 - 离合器扭矩 - 内部摩擦
 *   3. engineRpm += 净扭矩 / 旋转惯量 × dt
 *   4. RPM ≤ STALL_RPM → 熄火
 *
 * 这是一个物理正确的扭矩源模拟器：
 *   - 发动机不"命令"转速，只"提供"扭矩
 *   - 转速由净扭矩自然演化
 *   - 熄火从物理定律中涌现，而非游戏规则
 *   - 与车辆质量解耦：轻车加速快、重车加速慢
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import net.minecraft.util.Mth;

/**
 * 发动机物理计算工具类。
 * <p>
 * 所有方法均为纯静态函数，不持有状态。状态由 CockpitBlockEntity 管理。
 */
public final class EngineModel {

    // ==================================================================
    //  扭矩源核心
    // ==================================================================

    /**
     * 计算发动机在当前转速和油门下的实际输出扭矩。
     *
     * <p>T_engine = baseTorque × torqueCurve(RPM) × throttleLevel
     *
     * @param engineRpm     当前发动机 RPM
     * @param throttleLevel 油门位置 [0.0, 1.0]
     * @return 发动机输出扭矩（Nm）
     */
    public static double computeEngineTorque(double engineRpm, double throttleLevel) {
        double baseTorque = PowertrainConstants.ENGINE_TORQUE;
        double curveMultiplier = computeTorqueCurveMultiplier(engineRpm);
        return baseTorque * curveMultiplier * throttleLevel;
    }

    /**
     * 发动机转速更新结果。
     *
     * @param rpm     更新后的发动机 RPM
     * @param stalled 是否已熄火（RPM ≤ STALL_RPM）
     */
    public record EngineRpmResult(double rpm, boolean stalled) {}

    /**
     * 根据净扭矩更新发动机转速。
     *
     * <p>RPM += (T_net / I_engine) × dt
     *
     * <ul>
     *   <li>T_net &gt; 0 → 扭矩盈余 → 升转</li>
     *   <li>T_net = 0 → 扭矩平衡 → 稳转</li>
     *   <li>T_net &lt; 0 → 扭矩不足 → 降转（可能熄火）</li>
     * </ul>
     *
     * @param engineRpm 当前发动机 RPM
     * @param torqueNet 净扭矩（Nm）
     * @return 更新结果
     */
    public static EngineRpmResult updateRpm(double engineRpm, double torqueNet) {
        double rpmDelta = torqueNet / PowertrainConstants.ENGINE_INERTIA * PowertrainConstants.DT;
        double newRpm = engineRpm + rpmDelta;

        if (newRpm <= PowertrainConstants.ENGINE_STALL_RPM) {
            return new EngineRpmResult(0.0, true);
        }
        return new EngineRpmResult(
                Math.min(newRpm, PowertrainConstants.ENGINE_MAX_RPM),
                false
        );
    }

    // ==================================================================
    //  发动机内部摩擦
    // ==================================================================

    /**
     * 计算发动机内部摩擦扭矩。
     *
     * <p>发动机活塞环、曲轴轴承、气门机构等机械摩擦：
     * <pre>
     *   frictionTorque = ENGINE_FRICTION_PER_RPM × max(0, RPM - IDLE_RPM)
     * </pre>
     * 怠速以下无额外摩擦 (RPM ≤ IDLE_RPM 时摩擦为 0)。
     *
     * @param engineRpm 当前发动机 RPM
     * @return 内部摩擦扭矩（Nm）
     */
    public static double computeFrictionTorque(double engineRpm) {
        return PowertrainConstants.ENGINE_FRICTION_PER_RPM
                * Math.max(0, engineRpm - PowertrainConstants.ENGINE_IDLE_RPM);
    }

    // ==================================================================
    //  测试架油门直控模式 — 油门直接指定转速，扭矩由曲线决定
    // ==================================================================

    /**
     * 油门直控模式的发动机状态结果。
     *
     * @param rpm          目标转速（RPM）= IDLE + throttle × (MAX - IDLE)
     * @param engineTorque 发动机当前输出扭矩（Nm），纯 RPM 函数
     */
    public record ThrottleControlledResult(
            double rpm,
            double engineTorque
    ) {}

    /**
     * 油门直控模式：油门直接决定目标转速，扭矩仅由 RPM 通过扭矩曲线决定。
     *
     * <p>油门 100% 已包含内部损耗后的输油系数，不再需要单独摩擦项。
     *
     * <p>不同于旧扭矩源模型（油门衰减扭矩 → 积分推转速），此模式下：
     * <ul>
     *   <li>RPM = IDLE + throttle × (MAX - IDLE) ← 油门直接定位</li>
     *   <li>Torque = ENGINE_TORQUE × torqueCurve(RPM) ← 纯 RPM 函数</li>
     *   <li>油门不影响扭矩幅值，只决定发动机工作在扭矩曲线的哪个点</li>
     * </ul>
     *
     * @param throttleLevel 油门位置 [0.0, 1.0]
     * @return 油门直控结果
     */
    public static ThrottleControlledResult computeThrottleControlledRun(double throttleLevel) {
        double rpm = PowertrainConstants.ENGINE_IDLE_RPM
                + throttleLevel * (PowertrainConstants.ENGINE_MAX_RPM - PowertrainConstants.ENGINE_IDLE_RPM);
        double torque = PowertrainConstants.ENGINE_TORQUE * computeTorqueCurveMultiplier(rpm);
        return new ThrottleControlledResult(rpm, torque);
    }

    // ==================================================================
    //  扭矩曲线
    // ==================================================================

    /**
     * 计算扭矩曲线修正乘数。
     *
     * <p>使用 sin() 曲线模拟引擎扭矩随 RPM 的变化：
     * <pre>
     *   rpmRatio = (rpm - idle) / (redline - idle)
     *   multiplier = idle_frac + (1 - idle_frac) × sin(π × rpmRatio^sharpness)
     * </pre>
     *
     * @param engineRpm 当前发动机 RPM
     * @return 扭矩修正乘数 [0.15, 1.0]
     */
    public static double computeTorqueCurveMultiplier(double engineRpm) {
        double rpmRange = PowertrainConstants.ENGINE_MAX_RPM - PowertrainConstants.ENGINE_IDLE_RPM;
        double rpmRatio = rpmRange > 0
                ? (engineRpm - PowertrainConstants.ENGINE_IDLE_RPM) / rpmRange
                : 0.0;
        rpmRatio = Mth.clamp(rpmRatio, 0.0, 1.0);

        double shapedRatio = Math.pow(rpmRatio, PowertrainConstants.TORQUE_CURVE_SHARPNESS);
        double multiplier = PowertrainConstants.TORQUE_IDLE_FRACTION
                + (1.0 - PowertrainConstants.TORQUE_IDLE_FRACTION) * Math.sin(Math.PI * shapedRatio);
        return Math.max(multiplier, 0.15);
    }

    // ==================================================================
    //  油门控制
    // ==================================================================

    /**
     * 计算油门位置的双向调整。
     *
     * <ul>
     *   <li>direction > 0（Home 按下）→ 柔和加速</li>
     *   <li>direction < 0（End 按下）→ 主动减油</li>
     *   <li>direction = 0（无输入）→ 保持当前油门，无自行衰减</li>
     * </ul>
     *
     * @param throttleLevel 当前油门位置
     * @param direction     油门方向：+1=加油, -1=减油, 0=保持不变
     * @return 调整后的油门位置
     */
    public static double updateThrottle(double throttleLevel, int direction) {
        if (direction > 0) {
            return Math.min(1.0, throttleLevel + PowertrainConstants.THROTTLE_RATE);
        } else if (direction < 0) {
            return Math.max(0.0, throttleLevel - PowertrainConstants.THROTTLE_BRAKE_RATE);
        } else {
            return throttleLevel; // 无自行衰减，保持当前值
        }
    }

    private EngineModel() {}
}
