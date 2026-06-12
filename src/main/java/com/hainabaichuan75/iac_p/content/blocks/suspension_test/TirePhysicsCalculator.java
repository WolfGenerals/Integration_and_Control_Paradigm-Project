/*
 * 轮胎物理计算工具类。
 *
 * 包含轮胎形变、滚动阻力、爆胎检测、轮速推算等纯计算函数。
 * 所有方法均为无状态的静态方法，便于测试和推理。
 */
package com.hainabaichuan75.iac_p.content.blocks.suspension_test;

import net.minecraft.util.Mth;

/**
 * 轮胎物理计算工具类 —— 纯函数，无状态。
 */
public final class TirePhysicsCalculator {

    /**
     * 轮胎形变和负载胎压计算结果。
     *
     * @param tireDeflection   轮胎形变量（米）
     * @param effectivePressure 当前有效胎压（Pa，含负载升高）
     * @param contactArea       轮胎-地面接触面积（m²）
     */
    public record TireDeflectionResult(
            double tireDeflection,
            double effectivePressure,
            double contactArea
    ) {}

    /**
     * 计算轮胎形变、负载胎压升高和接触面积。
     *
     * @param normalForce     法向力（N）
     * @param nominalPressure 标称胎压（Pa）
     * @param treadWidth      胎面宽度（米）
     * @param radius          轮胎半径（米）
     * @return 形变/胎压/接触面积结果
     */
    public static TireDeflectionResult calculateTireDeflection(
            double normalForce, double nominalPressure, double treadWidth, double radius) {
        if (normalForce <= 0 || nominalPressure <= 0) {
            return new TireDeflectionResult(0, nominalPressure, 0);
        }

        // 轮胎形变：法向力 / (胎压 × 胎面宽度)
        double tireDeflection = normalForce / (nominalPressure * treadWidth);
        // 钳制形变不超过轮胎半径的 30%（物理合理范围）
        tireDeflection = Mth.clamp(tireDeflection, 0, radius * 0.3);

        // 负载引起的胎压升高：形变挤占内部容积
        double contactLength = 2 * Math.sqrt(
                Math.max(0, radius * radius - (radius - tireDeflection) * (radius - tireDeflection)));
        double contactArea = contactLength * treadWidth;

        double effectivePressure = nominalPressure;
        if (contactArea > 1e-10) {
            double loadPressureGain = normalForce / contactArea * 0.15; // 15% 压力传递到胎压
            effectivePressure = nominalPressure + loadPressureGain;
        }

        return new TireDeflectionResult(tireDeflection, effectivePressure, contactArea);
    }

    /**
     * 滚动阻力计算结果。
     *
     * @param rrForce        滚动阻力（N）
     * @param crrEffective   有效滚动阻力系数
     */
    public record RollingResistanceResult(
            double rrForce,
            double crrEffective
    ) {}

    /**
     * 计算滚动阻力。
     *
     * <p>模型：</p>
     * <pre>
     *   Crr = Crr_base
     *       + Crr_deformation × (δ_tire / radius)
     *       + 0.06 × max(0, P_nominal/P_eff - 1)²
     *
     *   F_rr = -v × Crr × m × dt
     * </pre>
     *
     * @param forwardSpeed          纵向速度（m/s）
     * @param nm                    单位质量（kg）
     * @param dt                    物理时间步长（s）
     * @param tireDeflection        轮胎形变量（米）
     * @param radius                轮胎半径（米）
     * @param nominalPressure       标称胎压（Pa）
     * @param effectivePressure     有效胎压（Pa，含负载升高）
     * @param crrBase               基础滚动阻力系数
     * @param crrDeformationGain    形变附加滚动阻力系数
     * @return 滚动阻力结果
     */
    public static RollingResistanceResult calculateRollingResistance(
            double forwardSpeed, double nm, double dt,
            double tireDeflection, double radius,
            double nominalPressure, double effectivePressure,
            double crrBase, double crrDeformationGain) {

        // 形变比和胎压比
        double deformationRatio = tireDeflection / Math.max(radius, 0.01);
        double pressureRatio = nominalPressure / Math.max(effectivePressure, 1.0);

        // 亏气附加阻力：当有效胎压低于标称胎压时急剧增加
        double underInflationPenalty = 0.06 * Math.max(0, pressureRatio - 1.0) * pressureRatio;

        // 综合滚动阻力系数
        double crrEffective = crrBase + crrDeformationGain * deformationRatio + underInflationPenalty;

        // 滚动阻力 = -v × Crr × m × dt（冲量形式）
        double rrForce = -forwardSpeed * crrEffective * nm * dt;

        return new RollingResistanceResult(rrForce, crrEffective);
    }

    /**
     * 爆胎检测结果。
     *
     * @param burst          是否爆胎
     * @param burstThreshold 爆胎阈值（Pa）
     * @param atmFactor      大气压因子
     */
    public record BurstCheckResult(boolean burst, double burstThreshold, double atmFactor) {}

    /**
     * 检测是否爆胎。
     *
     * <p>爆胎条件：effectivePressure × atmFactor > maxPressure。
     * 大气压随海拔升高指数衰减：P_atm = 101325 × exp(-(y - 63) / 8400)。</p>
     *
     * @param effectivePressure 当前有效胎压（Pa）
     * @param maxPressure       最大安全胎压（Pa）
     * @param altitude          海拔高度（方块 Y - 63）
     * @return 爆胎检测结果
     */
    public static BurstCheckResult checkBurst(double effectivePressure, double maxPressure, double altitude) {
        double atmPressure = 101325.0 * Math.exp(-altitude / 8400.0);
        double atmFactor = atmPressure / 101325.0;

        // 爆胎阈值 = 最大安全胎压 + 大气压的辅助支撑
        double burstThreshold = maxPressure * (0.7 + 0.3 * atmFactor);

        return new BurstCheckResult(effectivePressure > burstThreshold, burstThreshold, atmFactor);
    }

    /**
     * 从纵向速度推算轮端 RPM。
     *
     * @param forwardSpeed 纵向速度（m/s）
     * @param radius       轮胎半径（米）
     * @return 轮端 RPM
     */
    public static double calculateWheelRpm(double forwardSpeed, double radius) {
        return forwardSpeed * 60.0 / (2.0 * Math.PI * Math.max(radius, 0.01));
    }

    /**
     * 计算二次方速度阻尼冲量。
     *
     * @param forwardSpeed   纵向速度（m/s）
     * @param dragCoefficient 阻尼系数
     * @param dt             物理时间步长（s）
     * @return 阻尼冲量（N·s）
     */
    public static double calculateDragImpulse(double forwardSpeed, double dragCoefficient, double dt) {
        return -forwardSpeed * Math.abs(forwardSpeed) * dragCoefficient * dt;
    }

    private TirePhysicsCalculator() {}
}
