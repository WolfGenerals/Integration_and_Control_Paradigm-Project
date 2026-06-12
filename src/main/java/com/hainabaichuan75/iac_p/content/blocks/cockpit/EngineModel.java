/*
 * 发动机模型 —— 质量自适应扭矩、扭矩曲线、油门/负载驱动的转速变化。
 *
 * 所有方法均为纯静态函数，不持有状态。状态由 CockpitBlockEntity 管理，
 * 本类负责数学计算。
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import net.minecraft.util.Mth;

/**
 * 发动机物理计算工具类。
 * <p>
 * 包含：质量自适应扭矩、扭矩曲线修正、连续油门控制、负载驱动转速。
 */
public final class EngineModel {

    /**
     * 计算质量自适应有效扭矩。
     *
     * <p>effectiveTorque = totalMass × g × TORQUE_WEIGHT_RATIO
     * 重车扭矩大、轻车扭矩小，保证一致的功率/重量比。
     *
     * @param totalMass 车辆总质量（kg）
     * @return 有效扭矩（Nm），下限为 ENGINE_TORQUE × 0.5
     */
    public static double computeMassAdaptiveTorque(double totalMass) {
        double computedTorque = totalMass * 9.81 * PowertrainConstants.TORQUE_WEIGHT_RATIO;
        return Math.max(computedTorque, PowertrainConstants.ENGINE_TORQUE * 0.5);
    }

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

    /**
     * 油门调整结果。
     *
     * @param throttleLevel 调整后的油门踏板位置 [0.0, 1.0]
     */
    public record ThrottleResult(double throttleLevel) {}

    /**
     * 计算油门踏板位置的三段式调整。
     *
     * <ul>
     *   <li>direction > 0（W 按下）→ 柔和加速</li>
     *   <li>direction < 0（S 按下）→ 主动减油</li>
     *   <li>direction = 0（松手）→ 自动滑行衰减</li>
     * </ul>
     *
     * @param throttleLevel 当前油门位置
     * @param direction     油门方向：+1=前进, -1=后退, 0=无输入
     * @return 调整后的油门位置
     */
    public static double updateThrottle(double throttleLevel, int direction) {
        if (direction > 0) {
            return Math.min(1.0, throttleLevel + PowertrainConstants.THROTTLE_RATE);
        } else if (direction < 0) {
            return Math.max(0.0, throttleLevel - PowertrainConstants.THROTTLE_BRAKE_RATE);
        } else {
            return Math.max(0.0, throttleLevel - PowertrainConstants.THROTTLE_DECAY);
        }
    }

    /**
     * 发动机转速更新结果。
     *
     * @param engineRpm      更新后的发动机 RPM
     * @param targetRpm      目标 RPM
     */
    public record RpmUpdateResult(double engineRpm, double targetRpm) {}

    /**
     * 根据扭矩平衡模型计算发动机转速变化。
     *
     * <p>转速变化率由扭矩余额决定：torqueRatio = 1 - loadFactor
     * <ul>
     *   <li>&gt; 0 → 引擎扭矩有富余 → 转速上升</li>
     *   <li>= 0 → 扭矩刚好平衡 → 转速稳住</li>
     *   <li>&lt; 0 → 扭矩不足 → 转速被负载拖降</li>
     * </ul>
     *
     * @param engineRpm     当前发动机 RPM
     * @param throttleLevel 油门位置 [0.0, 1.0]
     * @param currentGear   当前档位（用于判断是否在档位中）
     * @param loadFactor    负载因子（来自悬挂 BE 扫描）
     * @return 更新结果
     */
    public static RpmUpdateResult computeRpmUpdate(
            double engineRpm, double throttleLevel, int currentGear, double loadFactor) {

        double targetRpm;

        if (throttleLevel > 0.01) {
            targetRpm = PowertrainConstants.ENGINE_IDLE_RPM
                    + (PowertrainConstants.ENGINE_MAX_RPM - PowertrainConstants.ENGINE_IDLE_RPM) * throttleLevel;

            double netAccel = PowertrainConstants.RPM_ACCEL;
            if (currentGear != 0) {
                double torqueRatio = 1.0 - Mth.clamp(loadFactor, 0, 5);
                netAccel = PowertrainConstants.RPM_ACCEL * torqueRatio;
            }

            targetRpm = Mth.clamp(targetRpm, PowertrainConstants.ENGINE_IDLE_RPM, PowertrainConstants.ENGINE_MAX_RPM);

            if (netAccel > 0 && engineRpm < targetRpm) {
                engineRpm = Math.min(engineRpm + netAccel, targetRpm);
            } else if (netAccel < 0) {
                engineRpm = Math.max(engineRpm + netAccel, PowertrainConstants.ENGINE_IDLE_RPM);
            } else if (engineRpm > targetRpm) {
                engineRpm = Math.max(engineRpm - PowertrainConstants.RPM_ACCEL, targetRpm);
            }
        } else {
            targetRpm = PowertrainConstants.ENGINE_IDLE_RPM;
            if (currentGear == 0) {
                // 空档：正常回怠速
                if (engineRpm > targetRpm) {
                    engineRpm = Math.max(engineRpm - PowertrainConstants.RPM_ACCEL, targetRpm);
                }
            }
            // 在档位中：交给耦合逻辑处理（engine braking）
        }

        return new RpmUpdateResult(engineRpm, targetRpm);
    }

    /**
     * 计算发动机-轮速耦合后的最小允许 RPM。
     *
     * <p>在档位中且离合器结合时，发动机转速不能低于轮速×齿比太多。
     * 如果发动机试图低于耦合转速，说明车轮在拖拽发动机（engine braking）。
     *
     * @param currentGear  当前档位
     * @param avgWheelRpm  平均轮端 RPM
     * @param engineRpm    当前发动机 RPM
     * @return 耦合调整后的发动机 RPM（保持不变或强制跟随轮速）
     */
    public static double applyEngineCoupling(int currentGear, double avgWheelRpm, double engineRpm) {
        if (currentGear == 0) return engineRpm;

        double absRatio = Math.abs(PowertrainConstants.getCurrentRatio(currentGear));
        if (absRatio <= 0.01) return engineRpm;

        double coupledRpm = Math.abs(avgWheelRpm) * absRatio * PowertrainConstants.FINAL_DRIVE_RATIO;
        double minRpm = Math.max(coupledRpm, PowertrainConstants.ENGINE_IDLE_RPM);

        if (engineRpm < minRpm - 50.0) {
            // 车轮在拖发动机（engine braking）
            return Math.min(minRpm, PowertrainConstants.ENGINE_MAX_RPM);
        }
        return engineRpm;
    }

    private EngineModel() {}
}
