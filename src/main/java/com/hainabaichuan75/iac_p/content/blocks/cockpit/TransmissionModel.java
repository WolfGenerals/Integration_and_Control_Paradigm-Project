/*
 * 变速箱模型 —— 纯比率变换：扭矩放大 + 转速减速 + 档位管理。
 *
 * 变速箱是纯数学变换器，不对发动机状态做任何假设：
 *   1. 扭矩b = 扭矩a × 齿比（扭矩放大）
 *   2. 转速b = 转速a / 齿比（转速减速）
 *   3. 换挡真空期：6 tick 输出归零
 *
 * 发动机转速不受变速箱影响——在档时由轮速运动学约束，
 * 空档时由油门直控（见 EngineModel.computeThrottleControlledRun）。
 *
 * 所有方法均为纯函数。档位状态由 CockpitBlockEntity 管理。
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import net.minecraft.util.Mth;

/**
 * 变速箱模型 —— 纯扭矩/转速变换，不涉及离合器或发动机状态。
 */
public final class TransmissionModel {

    // ==================================================================
    //  变速箱变换
    // ==================================================================

    /**
     * 变速箱输出结果。
     *
     * @param torqueB 输出扭矩（Nm）= torqueA × 有效齿比
     * @param rpmB    输出转速（RPM）= rpmA / 有效齿比 × 方向符号
     */
    public record TransmissionOutput(double torqueB, double rpmB) {}

    /**
     * 执行变速箱纯比率变换。
     *
     * <p>将发动机侧扭矩/转速通过当前档位齿比变换到变速箱输出侧。
     * 空档或无效档位返回零。
     *
     * @param inputTorque 输入扭矩（Nm，已扣摩擦的净扭矩）
     * @param inputRpm    输入转速（RPM）
     * @param gear        当前档位（-1=R, 0=N, 1~5）
     * @return 变速箱输出（空档时全零）
     */
    public static TransmissionOutput computeOutput(double inputTorque, double inputRpm, int gear) {
        if (gear == 0) return new TransmissionOutput(0, 0);
        double effectiveRatio = computeEffectiveRatio(gear);
        double sign = Math.signum(PowertrainConstants.getCurrentRatio(gear));
        return new TransmissionOutput(
                inputTorque * effectiveRatio * sign,
                inputRpm / effectiveRatio * sign
        );
    }

    // ==================================================================
    //  辅助方法
    // ==================================================================

    /**
     * 计算变速箱输出侧的目标转速。
     *
     * <p>wheelRpm = engineRpm / |ratio| / finalDrive × sign(ratio)
     *
     * @param gear      当前档位
     * @param engineRpm 发动机 RPM
     * @return 输出侧 RPM（含方向），空档返回 0
     */
    public static double computeTargetWheelRpm(int gear, double engineRpm) {
        if (gear == 0 || engineRpm <= 0) return 0;
        double ratio = PowertrainConstants.getCurrentRatio(gear);
        double effectiveRatio = Math.abs(ratio) * PowertrainConstants.FINAL_DRIVE_RATIO;
        return (engineRpm / effectiveRatio) * Math.signum(ratio);
    }

    /** 计算有效传动比 = |齿比| × 主减速比 */
    private static double computeEffectiveRatio(int gear) {
        double ratio = PowertrainConstants.getCurrentRatio(gear);
        return Math.abs(ratio) * PowertrainConstants.FINAL_DRIVE_RATIO;
    }

    /**
     * 获取方向符号。
     *
     * @param gear 当前档位
     * @return +1 前进, -1 倒车, 0 空档
     */
    public static double getDirectionSign(int gear) {
        if (gear == 0) return 0;
        return Math.signum(PowertrainConstants.getCurrentRatio(gear));
    }

    // ====================================================================
    //  换挡操作
    // ====================================================================

    /**
     * 升档：R → N → 1 → 2 → 3 → 4 → 5。
     * <p>
     * 换挡时按齿比比例调整发动机转速：engineRpm_new = engineRpm_old × ratio_new / ratio_old。
     *
     * @param currentGear 当前档位
     * @param engineRpm   当前发动机 RPM
     * @return 换挡结果（新档位 + 同步后的 engineRpm）
     */
    public static GearShiftResult gearUp(int currentGear, double engineRpm) {
        int old = currentGear;
        double oldRatio = PowertrainConstants.getRatioForGear(old);
        int newGear = currentGear;

        switch (currentGear) {
            case -1 -> newGear = 0;  // R → N
            case 0  -> newGear = 1;  // N → 1
            default -> {
                if (currentGear < PowertrainConstants.NUM_FORWARD_GEARS) {
                    newGear++;
                }
            }
        }

        if (old != newGear) {
            engineRpm = syncRpmOnShift(engineRpm, oldRatio, newGear);
        }
        return new GearShiftResult(newGear, engineRpm);
    }

    /**
     * 降档：5 → 4 → 3 → 2 → 1 → N → R。
     *
     * @param currentGear 当前档位
     * @param engineRpm   当前发动机 RPM
     * @return 换挡结果（新档位 + 同步后的 engineRpm）
     */
    public static GearShiftResult gearDown(int currentGear, double engineRpm) {
        int old = currentGear;
        double oldRatio = PowertrainConstants.getRatioForGear(old);
        int newGear = currentGear;

        switch (currentGear) {
            case 0  -> newGear = -1; // N → R
            case 1  -> newGear = 0;  // 1 → N
            default -> {
                if (currentGear > 1) {
                    newGear--;
                }
            }
        }

        if (old != newGear) {
            engineRpm = syncRpmOnShift(engineRpm, oldRatio, newGear);
        }
        return new GearShiftResult(newGear, engineRpm);
    }

    /**
     * 换挡转速同步：按齿比比例调整发动机转速。
     */
    private static double syncRpmOnShift(double engineRpm, double oldRatio, int newGear) {
        if (oldRatio > 0 && newGear >= 1) {
            double newRatio = PowertrainConstants.GEAR_RATIOS[newGear - 1];
            engineRpm = engineRpm * newRatio / oldRatio;
            engineRpm = Mth.clamp(engineRpm, PowertrainConstants.ENGINE_IDLE_RPM, PowertrainConstants.ENGINE_MAX_RPM);
        }
        return engineRpm;
    }

    /**
     * 换挡结果。
     *
     * @param gear      新档位
     * @param engineRpm 同步后的发动机 RPM
     */
    public record GearShiftResult(int gear, double engineRpm) {}

    private TransmissionModel() {}
}
