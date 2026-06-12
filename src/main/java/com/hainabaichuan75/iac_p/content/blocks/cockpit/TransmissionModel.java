/*
 * 变速箱模型 —— 档位管理、换挡逻辑、动力输出计算。
 *
 * 不持有状态，所有方法均为纯函数（除齿轮同步的 engineRpm 外，
 * 换挡方法接收并返回更新后的 engineRpm）。
 */
package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import net.minecraft.util.Mth;

/**
 * 变速箱模型 —— 纯档位计算，不涉及发动机状态。
 */
public final class TransmissionModel {

    /**
     * 动力系统输出记录 —— 每个轮子应获得的目标 RPM 和可用扭矩。
     * <p>
     * 由 {@link #computeWheelOutput} 返回，供悬挂 BE 的 P 控制器使用。
     *
     * @param wheelRpm    目标轮端 RPM（含方向符号）
     * @param wheelTorque 每轮可用扭矩（Nm，均摊后）
     */
    public record PowertrainOutput(double wheelRpm, double wheelTorque) {}

    /**
     * 计算每个轮子的目标 RPM 和可用扭矩。
     *
     * <p>核心逻辑：
     * <ul>
     *   <li>空档或倒车简单处理</li>
     *   <li>有油门时用指令转速（油门位置决定），松油时用实际发动机转速</li>
     *   <li>智能映射反转时反转齿比符号</li>
     *   <li>差速均摊：各轮同转速，扭矩平分</li>
     * </ul>
     *
     * @param currentGear       当前档位（-1=R, 0=N, 1~5）
     * @param throttleLevel     油门踏板位置 0.0~1.0
     * @param engineRpm         当前发动机 RPM
     * @param effectiveTorque   质量自适应扭矩（Nm）
     * @param smartMappingActive  智能映射是否启用
     * @param smartMappingReversed 智能映射是否反转
     * @param totalWheels       轮子总数
     * @return 动力输出（空档时 wheelRpm=0, wheelTorque=0）
     */
    public static PowertrainOutput computeWheelOutput(
            int currentGear, double throttleLevel,
            double engineRpm, double effectiveTorque,
            boolean smartMappingActive, boolean smartMappingReversed,
            int totalWheels) {

        if (currentGear == 0 || totalWheels <= 0) {
            return new PowertrainOutput(0.0, 0.0);
        }

        double ratio = PowertrainConstants.getCurrentRatio(currentGear);
        double absRatio = Math.abs(ratio);
        double effectiveRatio = absRatio * PowertrainConstants.FINAL_DRIVE_RATIO;

        // ── 目标轮端转速 ──
        double wheelRpm;
        if (throttleLevel > 0.01) {
            // 油门踩下：指令转速 = 油门位置对应的期望转速
            double commandedRpm = PowertrainConstants.ENGINE_IDLE_RPM
                    + (PowertrainConstants.ENGINE_MAX_RPM - PowertrainConstants.ENGINE_IDLE_RPM) * throttleLevel;
            double ratioSign = Math.signum(ratio);
            if (smartMappingActive && smartMappingReversed) {
                ratioSign = -ratioSign;
            }
            wheelRpm = (commandedRpm / effectiveRatio) * ratioSign;
        } else {
            // 松油：实际发动机转速（跟随耦合）
            double ratioSign = Math.signum(ratio);
            if (smartMappingActive && smartMappingReversed) {
                ratioSign = -ratioSign;
            }
            wheelRpm = (engineRpm / effectiveRatio) * ratioSign;
        }

        // ── 扭矩分配 ──
        double perWheelTorque;
        if (throttleLevel > 0.01) {
            double totalWheelTorque = effectiveTorque * effectiveRatio;
            perWheelTorque = totalWheelTorque / totalWheels;
        } else {
            perWheelTorque = 0.0;
        }

        return new PowertrainOutput(wheelRpm, perWheelTorque);
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
     * <p>
     * 升档时转速下降（eg. 1档 6000RPM → 2档：6000×2.5/4.0=3750 RPM），
     * 降档时转速上升，超转时钳制到红线。
     *
     * @param engineRpm  换挡前发动机 RPM
     * @param oldRatio   换挡前齿比（0 表示空/N→R/R→N 等无意义场景）
     * @param newGear    新档位
     * @return 同步后的发动机 RPM
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
