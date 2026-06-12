/*
 * Brush 轮胎侧偏模型 —— 纯函数计算。
 *
 * 替换了旧的纯阻尼模型，模拟轮胎侧偏角达到峰值后抓地崩溃（漂移）。
 * 包含防抖措施：侧偏角钳制 ±45° + 低速纯阻尼 + 平滑过渡混合。
 */
package com.hainabaichuan75.iac_p.content.blocks.suspension_test;

import net.minecraft.util.Mth;

/**
 * Brush 轮胎侧偏模型计算。
 * <p>
 * 物理行为：
 * <ul>
 *   <li>α → 0°：线性区，Fy = -Cα × α（方向感清晰）</li>
 *   <li>α ≈ 4.5°：峰值抓地（对 CORNERING_STIFFNESS=20）</li>
 *   <li>α > 峰值：力下降 → 轮胎突破极限 → 甩尾/漂移</li>
 * </ul>
 */
public final class BrushTireModel {

    /**
     * Brush 模型输出：侧向冲量和混合权重。
     *
     * @param lateralImpulse Brush 模型侧向冲量（N·s）
     * @param blendWeight    Brush 模型的混合权重 [0,1]，1=纯Brush，0=纯阻尼
     */
    public record BrushResult(double lateralImpulse, double blendWeight) {}

    /**
     * 计算 Brush 轮胎侧向力（冲量形式）。
     *
     * <p>核心公式：Fy = -μ × N × sin(Cα × tan|α| / (μ × N)) × sgn(α)</p>
     *
     * @param forwardSpeed      纵向速度（m/s）
     * @param lateralSpeed      侧向速度（m/s）
     * @param frictionBasis     摩擦基数（N·s）
     * @param mu                综合摩擦系数
     * @param corneringStiffness 侧偏刚度系数
     * @param sideSlipDamping   侧滑阻尼系数
     * @param nm                单位质量（kg）
     * @param dt                物理时间步长（s）
     * @return Brush 模型计算结果（冲量形式）
     */
    public static BrushResult calculateLateralForce(
            double forwardSpeed, double lateralSpeed,
            double frictionBasis, double mu,
            double corneringStiffness, double sideSlipDamping,
            double nm, double dt) {

        double forwardSpeedAbs = Math.abs(forwardSpeed);
        double totalSpeed = Math.sqrt(forwardSpeed * forwardSpeed + lateralSpeed * lateralSpeed);

        if (totalSpeed <= 1.0) {
            // 极低速：纯阻尼 → 防止原地自旋和微小扰动放大
            double dampingImpulse = -lateralSpeed * sideSlipDamping * nm * dt;
            return new BrushResult(dampingImpulse, 0.0);
        }

        // Brush 轮胎模型
        double slipAngle = Math.atan2(lateralSpeed, forwardSpeedAbs);
        // 钳制侧偏角 ±45°，防止 tan(α) 爆炸导致力震荡
        slipAngle = Mth.clamp(slipAngle, Math.toRadians(-45.0), Math.toRadians(45.0));
        double absSlip = Math.abs(slipAngle);

        // 法向力 (N) = 摩擦基数 / dt（含载荷转移的调整值）
        double latNormalForce = frictionBasis / dt;
        double peakForce = mu * latNormalForce; // 侧向峰值力 (N)
        double corneringStiffnessTotal = corneringStiffness * peakForce;

        // Brush 模型输入：t = Cα × tan(α) / (μ × N)
        double input = corneringStiffnessTotal * Math.tan(absSlip) / Math.max(peakForce, 1.0);
        input = Mth.clamp(input, 0.0, Math.PI * 0.85); // 限制到略超 π/2 保留后峰值区

        double fyRatio;
        if (input <= Math.PI / 2.0) {
            fyRatio = Math.sin(input);             // 峰值前：经典 Brush
        } else {
            // 峰值后：线性下降模拟抓地崩溃
            fyRatio = 1.0 - (input - Math.PI / 2.0) * 0.25;
            fyRatio = Math.max(fyRatio, 0.1);       // 残余抓地 ≥ 10%
        }

        double fyNewtons = peakForce * fyRatio;

        // 转换为冲量（与 longForce、frictionBudget 同单位）
        double brushImpulse = -Math.signum(slipAngle) * fyNewtons * dt;

        // 平滑过渡：当 totalSpeed 在 1.0~2.0 m/s 时 Brush 与阻尼混合
        double dampingImpulse = -lateralSpeed * sideSlipDamping * nm * dt;
        double blend = Mth.clamp((totalSpeed - 1.0) / 1.0, 0.0, 1.0);
        double lateralImpulse = Mth.lerp(1.0 - blend, dampingImpulse, brushImpulse);

        return new BrushResult(lateralImpulse, blend);
    }

    private BrushTireModel() {}
}
