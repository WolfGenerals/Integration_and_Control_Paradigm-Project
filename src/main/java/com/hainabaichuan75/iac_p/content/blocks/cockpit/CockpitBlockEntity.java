package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.index.ModCockpitBlockEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 驾驶舱方块实体 —— 载具动力系统的大脑。
 *
 * <h3>油门同步机制（重要）</h3>
 * <strong>不再使用共享 Map。</strong>
 * CockpitBE 在 {@code tick()} 中<strong>直接扫描 SubLevel 内所有悬挂方块</strong>
 * 的 {@code throttleForward}/{@code throttleBackward} 字段获取油门状态，
 * 零时序冲突，无中间商。详见 {@link #scanThrottleDirection(SubLevel)}。
 *
 * <h3>动力系统架构</h3>
 * <pre>
 * 玩家输入 (W/S 油门)
 *   ↓
 * 发动机 (Engine) — 质量自适应扭矩 + 扭矩曲线 + 连续油门 + 负载模型 + 轮速耦合
 *   ├─ 怠速 RPM:  ENGINE_IDLE_RPM
 *   ├─ 红线 RPM:  ENGINE_MAX_RPM
 *   ├─ 有效扭矩:  totalMass × g × TORQUE_WEIGHT_RATIO（质量自适应）
 *   ├─ 扭矩曲线:  中段 ~3400 RPM 峰值，两端衰减至 80%（06-08 平坦化后）
 *   ├─ 连续油门:  throttleLevel 0.0~1.0 渐进式
 *   ├─ 负载模型:  RPM 变化率 = f(扭矩余额 = 1 - loadFactor)
 *   └─ 轮速耦合:  engineRPM = max(wheelRPM × gearRatio, idle) 在档位中强制
 *   ↓
 * 变速箱 (Gearbox) — 6 档 + 倒档 + 空档，理想无损耗
 *   ├─ 输入: 发动机 RPM & 有效扭矩
 *   ├─ 输出: 轮端 RPM = 发动机RPM / 齿比
 *   └─ 输出: 轮端扭矩 = 有效扭矩 × 齿比
 *   ↓
 * 差速分配 (Distribution) — 均摊扭矩，同转速
 *   ├─ N 个悬挂方块: 各得 RPM_轮端（相同）
 *   └─ N 个悬挂方块: 各得 扭矩_轮端 / N（均摊）
 * </pre>
 *
 * <h3>变速箱档位</h3>
 * <pre>
 *  档位    齿比    说明
 *  ───────────────────────────
 *  R      -3.5    倒车档
 *  N       -      空档（无动力输出）
 *  1st     4.0    低速高扭（起步/爬坡）
 *  2nd     2.5
 *  3rd     1.6
 *  4th     1.2
 *  5th     1.0    直接档（原为 0.7 超比档，06-04 消除超音速极速后改为 1.0）
 * </pre>
 *
 * <h3>换挡操作</h3>
 * <ul>
 *   <li>Q 键 = 升档 (gearUp): R→N→1→2→3→4→5</li>
 *   <li>E 键 = 降档 (gearDown): 5→4→3→2→1→N→R</li>
 *   <li>空档 (N) 时无动力输出，可原地轰油</li>
 * </ul>
 */
public class CockpitBlockEntity extends SmartBlockEntity {

    // ====================================================================
    //  发动机参数（编译时常量）
    // ====================================================================

    /** 发动机怠速转速（RPM）。无油门时稳定在此转速。 */
    public static final double ENGINE_IDLE_RPM = 800.0;

    /** 发动机红线转速（RPM）。最大转速上限。 */
    public static final double ENGINE_MAX_RPM = 6000.0;

    /** 基准发动机扭矩（Nm）—— 仅作为最小回退值和覆盖层显示参考。
     *  实际有效扭矩从车辆质量推导：effectiveTorque = totalMass × g × TORQUE_WEIGHT_RATIO
     *  轻车扭矩小、重车扭矩大，保证一致的功率/重量比。 */
    public static final double ENGINE_TORQUE = 30.0;

    /**
     * 扭矩/重量比（Nm 扭矩每 N 车重）。
     * 0.02 意味着 1000 kg 的车获得 1000×9.81×0.02 = 196 Nm 有效扭矩。
     * 调大 → 加速更猛，调小 → 加速更肉。
     */
    private static final double TORQUE_WEIGHT_RATIO = 0.02;

    // ====================================================================
    //  扭矩曲线参数（Torque Curve）
    // ====================================================================
    //
    //  真实发动机的扭矩不是恒定值，而是随 RPM 变化的曲线：
    //
    //    扭矩 ├──╮
    //         │  ╲╱╲        ← 峰值扭矩区 (~3000-3500 RPM)
    //         │ ╱  ╲╱╲
    //         │╱       ╲    ← 高转扭矩衰减
    //         └─────────────→ RPM
    //         idle     redline
    //
    //  采用 sin() 曲線模拟：怠速低扭 → 中段峰值 → 红线回落。
    //  这激励玩家在峰值附近换挡，而非一味拉到红线。

    /** 怠速时扭矩占峰值的比例。0.80 = 怠速时仍有峰值扭矩的 80%。
     *  06-08 从 0.35 改为 0.80：原值 35% 导致发动机在怠速和红线两端
     *  同时扭矩不足，而极速时耦合恰好把转速压在怠速附近 → 双重打击。
     *  平坦曲线让发动机在整个 RPM 范围内都有可用扭矩，
     *  极速限制由 DRAG_COEFFICIENT 单独负责。 */
    private static final double TORQUE_IDLE_FRACTION = 0.80;

    /**
     * 扭矩曲线形状参数。值越大曲线越"尖"，峰值区越窄。
     * 1.0 = 标准正弦，2.0 = 更集中在中段。
     */
    private static final double TORQUE_CURVE_SHARPNESS = 1.0;

    /** 油门加速率（RPM/游戏tick）。每秒最多升高 200×20=4000 RPM */
    private static final double RPM_ACCEL = 200.0;

    /** 松油减速率（RPM/游戏tick）。每秒最多降低 150×20=3000 RPM */
    private static final double RPM_DECEL = 150.0;

    /**
     * 负载减速率（RPM/游戏tick/负载因子）。
     * 负载因子 = 实际力需求 / 引擎能提供的最大力。
     * 负载因子 > 0.3 时开始产生阻力，> 1.0 时强制拉低转速。
     */
    private static final double RPM_LOAD_DECEL = 80.0;

    /**
     * 油门位置变化率（/tick）。每秒从 0→1 需要 1÷0.025 = 40 tick（约 2 秒）。
     * 降低速率使加速更柔和、更有代入感。
     */
    private static final double THROTTLE_RATE = 0.025;

    /**
     * 油门自动衰减率（/tick）。无油门输入时逐渐归零，实现滑行效果。
     * 从 1→0 需要 1÷0.015 ≈ 67 tick（约 3.3 秒）。
     * 慢速衰减让滑行更自然，不会松油即停。
     */
    private static final double THROTTLE_DECAY = 0.015;

    /**
     * S 键主动减油门速率（/tick）。比自动衰减更快，用于主动减速/倒车准备。
     * 从 1→0 需要 1÷0.04 = 25 tick（约 1.25 秒）。
     */
    private static final double THROTTLE_BRAKE_RATE = 0.04;

    // ====================================================================
    //  变速箱齿比
    // ====================================================================

    /** 前进档齿比（1档到5档）。齿比越大 → 轮端扭矩越大、转速越低。
     *  5 档从 0.7 改为 1.0（直接档）：超比档(齿比<1)导致轮端转速超过发动机转速，
     *  配合高扭矩产生超音速极速。直接档下轮端 RPM = 发动机 RPM，物理更直觉。 */
    public static final double[] GEAR_RATIOS = { 4.0, 2.5, 1.6, 1.2, 1.0 };

    /** 倒车档齿比（负值表示反向旋转）。 */
    public static final double REVERSE_RATIO = -3.5;

    /**
     * 主减速比（Final Drive Ratio）。
     *
     *  所有档位的齿比都要乘以这个值才是最终传动比。
     *  作用：
     *  - 让发动机在极速时处于合理的 RPM 区间（而非被耦合压在怠速）
     *  - 放大所有档位的轮端扭矩
     *  - 降低各档位的目标速度，使高档位极速 > 低档位极速
     *
     *  取值 10.0 的理由：
     *    目标极速 ~120 km/h (33.3 m/s)，轮半径 0.5625m
     *    wheelRPM = 33.3 / (2π × 0.5625/60) = 566 RPM
     *    希望发动机在 5 档极速时在合理区间：
     *      engineRPM = 566 × (1.0 × 10.0) ≈ 5660 RPM ✓
     *    5 档目标速度 = 6000 / (1.0 × 10.0) × 0.0589 = 35.3 m/s ≈ 127 km/h
     *
     *  06-08 新增：替代旧版「改齿比压极速」的思路。
     *  06-08 10.0→12.0→14.0：实测极速 140 km/h，继续压到 ~120。
     */
    public static final double FINAL_DRIVE_RATIO = 14.0;

    /** 前进档数量 */
    public static final int NUM_FORWARD_GEARS = GEAR_RATIOS.length;

    // ====================================================================
    //  运行时状态
    // ====================================================================

    /**
     * 当前档位：
     * <ul>
     *   <li>-1 = 倒车档 (R)</li>
     *   <li> 0 = 空档 (N)</li>
     *   <li> 1～5 = 前进档</li>
     * </ul>
     */
    private int currentGear = 0;

    /** 发动机当前转速（RPM），介于 ENGINE_IDLE_RPM 和 ENGINE_MAX_RPM 之间。 */
    private double engineRpm = ENGINE_IDLE_RPM;

    /**
     * 油门踏板位置 0.0（全松）~ 1.0（全踩）。
     * W 增加，S 减少，松开按键后保持在当前位置（无自动衰减）。
     */
    private double throttleLevel = 0.0;

    /**
     * 质量自适应有效扭矩（Nm）。
     * 根据车辆实际质量计算，轻车小扭矩、重车大扭矩，
     * 保证一致的功率/重量比。通过 NBT 同步到客户端供覆盖层显示。
     */
    private double effectiveTorque = ENGINE_TORQUE;

    /**
     * WASD 智能映射是否已启用。
     * 为 true 时使用 smartKey*，为 false 时回退到手动配置的 key*。
     * 通过 NBT 同步到客户端，供朝向信息界面显示开关状态。
     */
    private boolean smartMappingActive = false;

    /**
     * WASD 智能映射方向是否已反转。
     * 为 true 时引擎层反转方向解读，使按键交换后的驾驶行为与交换前一致。
     */
    private boolean smartMappingReversed = false;

    // ====================================================================
    //  构造
    // ====================================================================

    public CockpitBlockEntity(BlockPos pos, BlockState state) {
        super(ModCockpitBlockEntityTypes.COCKPIT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    // ====================================================================
    //  动力系统接口
    // ====================================================================

    /**
     * 直接设置发动机转速（用于外部强制复位）。
     */
    public void setEngineRpm(double rpm) {
        this.engineRpm = Mth.clamp(rpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);
    }

    /**
     * 获取动力系统输出 —— 每个轮子的目标 RPM 和可用扭矩。
     *
     * @param totalWheels 载具上的悬挂方块总数
     * @return 每个轮子的动力输出（空档时 RPM=0, Torque=0）
     */
    public @NotNull PowertrainOutput getWheelOutput(int totalWheels) {
        if (currentGear == 0 || totalWheels <= 0) {
            // 空档或没有轮子 → 无动力输出
            return new PowertrainOutput(0.0, 0.0);
        }

        double ratio;
        if (currentGear == -1) {
            ratio = REVERSE_RATIO; // 负值，使输出 RPM 反向
        } else {
            ratio = GEAR_RATIOS[currentGear - 1]; // 正值
        }

        double absRatio = Math.abs(ratio);
        double effectiveRatio = absRatio * FINAL_DRIVE_RATIO;

        // ═══════════════════════════════════════════════════════════════════
        //  目标轮端转速
        // ═══════════════════════════════════════════════════════════════════
        //
        //  ⚠ 这里必须区分「油门指令转速」和「实际发动机转速」：
        //
        //    有油门时 → 用油门指令转速（无视耦合约束）
        //      目的：让 P 控制器看到明确的 speedError = 指令目标 - 实际速度
        //      如果改用实际 engineRPM（被耦合约束到轮速附近），
        //      换挡后 targetSpeed ≈ forwardSpeed，speedError ≈ 0 → 不加速
        //
        //    松油时 → 用实际发动机转速（被耦合拖到轮速，模拟 engine braking）
        //      P 控制器 targetSpeed ≈ forwardSpeed → 不输出驱动力 → 滑行
        //
        //  ╔══════════════════════════════════════════════════════════════╗
        //  ║  最终传动比 = 变速箱齿比 × FINAL_DRIVE_RATIO               ║
        //  ║  5 档 (1.0×14.0=14.0) 目标速度:                           ║
        //  ║    6000/14.0 × 0.0589 = 25.2 m/s = 91 km/h (P误差抬到~120)║
        //  ║  1 档 (4.0×14.0=56.0) 目标速度:                            ║
        //  ║    6000/56.0 × 0.0589 = 6.3 m/s = 23 km/h                 ║
        //  ╚══════════════════════════════════════════════════════════════╝
        double wheelRpm;
        if (this.throttleLevel > 0.01) {
            // 油门踩下：指令转速 = 油门位置对应的期望转速
            double commandedRpm = ENGINE_IDLE_RPM
                    + (ENGINE_MAX_RPM - ENGINE_IDLE_RPM) * this.throttleLevel;
            double ratioSign = Math.signum(ratio);
            // 智能映射反转时：反转齿比符号，使车轮朝相反方向旋转
            if (smartMappingActive && smartMappingReversed) {
                ratioSign = -ratioSign;
            }
            wheelRpm = (commandedRpm / effectiveRatio) * ratioSign;
        } else {
            // 松油：实际发动机转速（跟随耦合，≈ 轮速 × 齿比 × 主减速比）
            double ratioSign = Math.signum(ratio);
            // 智能映射反转时：反转齿比符号，使车轮朝相反方向旋转
            if (smartMappingActive && smartMappingReversed) {
                ratioSign = -ratioSign;
            }
            wheelRpm = (engineRpm / effectiveRatio) * ratioSign;
        }

        // 差速分配：各轮同转速，均摊扭矩
        // ⚠ 无油门时零扭矩输出（纯滑行 / engine braking）
        // 此时 P 控制器 torqueGain=0 → 不输出驱动力 → 车辆靠滚动阻力和 engine braking 减速
        double perWheelTorque;
        if (this.throttleLevel > 0.01) {
            // 最终扭矩 = 引擎扭矩 × 变速箱齿比 × 主减速比
            double totalWheelTorque = this.effectiveTorque * effectiveRatio;
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
     * 升档：R → N → 1 → 2 → 3 → 4 → 5
     * <p>
     * 换挡时按齿比比例调整发动机转速，模拟离合器接合后的转速同步：
     * <pre>
     *   engineRPM_new = engineRPM_old × ratio_new / ratio_old
     * </pre>
     * 例如 1档(4.0) 6000RPM → 2档(2.5)：6000 × 2.5 / 4.0 = 3750 RPM ✅
     * 转速回到扭矩峰值区，避免红线低扭 × 高档低齿比的双重打击。
     */
    public void gearUp() {
        int old = this.currentGear;
        double oldRatio = getRatioForGear(old);
        switch (this.currentGear) {
            case -1 -> this.currentGear = 0;  // R → N
            case 0  -> this.currentGear = 1;  // N → 1
            default -> {
                if (this.currentGear < NUM_FORWARD_GEARS) {
                    this.currentGear++;
                }
            }
        }
        if (old != this.currentGear) {
            // 换挡同步：按齿比比例调整发动机转速
            // 仅当 oldRatio > 0（从前进档升档）且 gearUp 升档时适用
            if (oldRatio > 0 && this.currentGear >= 1) {
                double newRatio = GEAR_RATIOS[this.currentGear - 1];
                this.engineRpm = this.engineRpm * newRatio / oldRatio;
                this.engineRpm = Mth.clamp(this.engineRpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);
            }
            // 从 N→1：oldRatio=0 无法计算，保持当前转速
            // 从 R→N：无齿比变化，保持当前转速

            setChanged();
            sendData();
        }
    }

    /**
     * 降档：5 → 4 → 3 → 2 → 1 → N → R
     * <p>
     * 与 gearUp 同理，降档时发动机转速按比例升高。
     * 超转时钳制到 ENGINE_MAX_RPM（模拟红线断油保护）。
     */
    public void gearDown() {
        int old = this.currentGear;
        double oldRatio = getRatioForGear(old);
        switch (this.currentGear) {
            case 0  -> this.currentGear = -1; // N → R
            case 1  -> this.currentGear = 0;  // 1 → N
            default -> {
                if (this.currentGear > 1) {
                    this.currentGear--;
                }
            }
        }
        if (old != this.currentGear) {
            // 换挡同步：按齿比比例调整发动机转速
            if (oldRatio > 0 && this.currentGear >= 1) {
                double newRatio = GEAR_RATIOS[this.currentGear - 1];
                this.engineRpm = this.engineRpm * newRatio / oldRatio;
                this.engineRpm = Mth.clamp(this.engineRpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);
            }
            // 从 N→R：oldRatio=0 无法计算，保持当前转速

            setChanged();
            sendData();
        }
    }

    /** @return 指定档位的齿比绝对值，空档/倒车返回 0 */
    private static double getRatioForGear(int gear) {
        if (gear <= 0) return 0;
        if (gear > NUM_FORWARD_GEARS) return 0;
        return GEAR_RATIOS[gear - 1];
    }

    /** @return 当前档位代号：-1=R, 0=N, 1-5=前进档 */
    public int getCurrentGear() { return currentGear; }

    /** @return 发动机当前转速（RPM） */
    public double getEngineRpm() { return engineRpm; }

    /** @return 质量自适应有效扭矩（Nm），由 getWheelOutput() 使用 */
    public double getEffectiveTorque() { return effectiveTorque; }

    public boolean isSmartMappingActive() { return smartMappingActive; }
    public void setSmartMappingActive(boolean active) {
        this.smartMappingActive = active;
        setChanged();
        sendData();
    }

    public boolean isSmartMappingReversed() { return smartMappingReversed; }
    public void setSmartMappingReversed(boolean reversed) {
        this.smartMappingReversed = reversed;
        setChanged();
        sendData();
    }

    /** @return 当前档位的人类可读名称 */
    public String getGearDisplayName() {
        return gearName(this.currentGear);
    }

    /** 将发动机重置到怠速。下车/断线时调用。 */
    public void resetEngineToIdle() {
        this.engineRpm = ENGINE_IDLE_RPM;
    }

    // ====================================================================
    //  每 tick 更新
    // ====================================================================

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;

        SubLevel sl = Sable.HELPER.getContaining(this);
        double prev = this.engineRpm;

        if (sl != null) {
            // ===== 连续油门系统（无自动衰减） =====
            //
            // ╔══════════════════════════════════════════════════════════════════╗
            // ║  油门踏板位置（0.0~1.0）取代旧的布尔油门。                    ║
            // ║  • W 按下 → throttleLevel += THROTTLE_RATE（增加）            ║
            // ║  • S 按下 → throttleLevel -= THROTTLE_RATE（减少）            ║
            // ║  • 松  开 → throttleLevel 保持不变（无自动衰减）              ║
            // ║  • 升和降使用相同速率（效率一样）                              ║
            // ║                                                               ║
            // ║  转速 = 怠速 + (红线 - 怠速) × throttleLevel                   ║
            // ║  再经负载耦合修正（高负载拖低目标转速）                       ║
            // ╚══════════════════════════════════════════════════════════════════╝

            // ── 质量自适应扭矩 ──
            if (sl instanceof ServerSubLevel ssl) {
                try {
                    double totalMass = ssl.getMassTracker().getMass();
                    double computedTorque = totalMass * 9.81 * TORQUE_WEIGHT_RATIO;
                    this.effectiveTorque = Math.max(computedTorque, ENGINE_TORQUE * 0.5);
                } catch (Exception e) {
                    this.effectiveTorque = ENGINE_TORQUE;
                }

                // ═══════════════════════════════════════════════════════════
                //  扭矩曲线（Torque Curve）
                // ═══════════════════════════════════════════════════════════
                //
                //  物理直觉：发动机在不同转速下能输出的扭矩不同。
                //  低速扭矩不足（拖档感），中段峰值（发力区），
                //  高转回落（过了发力点该换挡了）。
                //
                //  曲线公式（sin 拟合）：
                //    rpmRatio = (currentRPM - idle) / (redline - idle)
                //    multiplier = idle_frac + (1 - idle_frac) × sin(π × rpmRatio^sharpness)
                //
                //      idle 800 RPM → rpmRatio=0   → multiplier=0.35  → 怠速低扭
                //      ~3400 RPM    → rpmRatio≈0.5 → multiplier=1.0  → 峰值扭矩
                //      redline 6000 → rpmRatio=1.0 → multiplier=0.35 → 高转衰减
                //
                //  效果：低转换挡有力，高转换挡收益递减。
                //  驾驶策略：在 ~3400 RPM 附近换挡获得最佳加速。
                // ═══════════════════════════════════════════════════════════
                double rpmRange = ENGINE_MAX_RPM - ENGINE_IDLE_RPM;
                double rpmRatio = rpmRange > 0 ? (this.engineRpm - ENGINE_IDLE_RPM) / rpmRange : 0.0;
                rpmRatio = Mth.clamp(rpmRatio, 0.0, 1.0);

                double shapedRatio = Math.pow(rpmRatio, TORQUE_CURVE_SHARPNESS);
                double curveMultiplier = TORQUE_IDLE_FRACTION
                        + (1.0 - TORQUE_IDLE_FRACTION) * Math.sin(Math.PI * shapedRatio);
                curveMultiplier = Math.max(curveMultiplier, 0.15);

                this.effectiveTorque *= curveMultiplier;
            }

            // ═══════════════════════════════════════════════════════════════
            //  单次 SubLevel 扫描：收集所有悬挂方块数据
            //  合并 scanThrottleDirection + calculateLoadFactor + getAverageWheelRpm
            //  将 3 次独立全量扫描减少为 1 次，每 tick 节省约 3 倍扫描开销
            // ═══════════════════════════════════════════════════════════════
            SubLevelScanResult scan = scanSubLevel(sl);
            int direction = scan.throttleDirection;

            // 智能映射反转时：反转方向解读，使按键交换后的驾驶行为与交换前一致
            if (smartMappingActive && smartMappingReversed) {
                direction = -direction;
            }

            // ── 油门调整（三段式：加速 / 主动减速 / 自动滑行衰减）──
            if (direction > 0) {
                // W 按下：柔和加速
                this.throttleLevel = Math.min(1.0, this.throttleLevel + THROTTLE_RATE);
            } else if (direction < 0) {
                // S 按下：主动减油（比自动衰减快）
                this.throttleLevel = Math.max(0.0, this.throttleLevel - THROTTLE_BRAKE_RATE);
            } else {
                // 无输入：自动衰减模拟滑行（缓慢归零）
                this.throttleLevel = Math.max(0.0, this.throttleLevel - THROTTLE_DECAY);
            }

            // ── 计算目标转速与扭矩平衡 ──
            //
            //  ╔══════════════════════════════════════════════════════════════╗
            //  ║  扭矩平衡转速模型                                           ║
            //  ║                                                             ║
            //  ║  替换了旧版「以固定速率向目标逼近」的模型。                ║
            //  ║  转速变化率直接由扭矩余额决定：                            ║
            //  ║                                                             ║
            //  ║    torqueRatio = 1.0 - loadFactor                          ║
            //  ║      > 0 → 引擎扭矩有富余 → 转速上升                      ║
            //  ║      = 0 → 扭矩刚好平衡 → 转速稳住                        ║
            //  ║      < 0 → 扭矩不足 → 转速被负载拖降（引擎憋屈感）       ║
            //  ║                                                             ║
            //  ║  物理直觉（以 5 档停车放手刹为例）：                       ║
            //  ║  • 手刹拉着 → P控制器=0 → loadFactor=0 → 自由轰油 ✅      ║
            //  ║  • 松手刹 → P控制器误差374m/s → 力需求爆增               ║
            //  ║    → loadFactor=93 → torqueRatio=-92                       ║
            //  ║    → 转速以 -800 RPM/tick 暴跌到怠速                     ║
            //  ║    → 引擎瞬间被「憋熄火」→ 符合 5 档停车起步的直觉 ✅   ║
            //  ╚══════════════════════════════════════════════════════════════╝
            double targetRpm;
            if (this.throttleLevel > 0.01) {
                targetRpm = ENGINE_IDLE_RPM + (ENGINE_MAX_RPM - ENGINE_IDLE_RPM) * this.throttleLevel;

                double netAccel = RPM_ACCEL; // 默认：全速加速

                if (currentGear != 0) {
                    // 使用合并扫描的结果（不再单独调用 calculateLoadFactor）
                    double torqueRatio = 1.0 - Mth.clamp(scan.loadFactor, 0, 5);
                    netAccel = RPM_ACCEL * torqueRatio;
                }

                targetRpm = Mth.clamp(targetRpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);

                // ── 按扭矩余额驱动转速 ──
                if (netAccel > 0 && this.engineRpm < targetRpm) {
                    // 有剩余扭矩且低于目标 → 加速
                    this.engineRpm = Math.min(this.engineRpm + netAccel, targetRpm);
                } else if (netAccel < 0) {
                    // 扭矩不足 → 转速被负载拖降
                    this.engineRpm = Math.max(this.engineRpm + netAccel, ENGINE_IDLE_RPM);
                } else if (this.engineRpm > targetRpm) {
                    // 高于目标 → 自然回落
                    this.engineRpm = Math.max(this.engineRpm - RPM_ACCEL, targetRpm);
                }
                // netAccel > 0 && engineRpm >= targetRpm → 保持
            } else {
                // 无油门 → 看档位决定行为
                targetRpm = ENGINE_IDLE_RPM;
                if (currentGear != 0) {
                    // 在档位中：不主动降 RPM，交给耦合逻辑处理（engine braking）
                    // 避免与耦合「强制跟随轮速」打架
                } else {
                    // 空档：正常回怠速
                    if (this.engineRpm > targetRpm) {
                        this.engineRpm = Math.max(this.engineRpm - RPM_ACCEL, targetRpm);
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  发动机-轮速耦合（Engine-Wheel Coupling）
            // ═══════════════════════════════════════════════════════════════
            //
            //  物理直觉：在档位中且离合器结合时，发动机转速与车轮转速
            //  通过变速箱刚性耦合：engineRPM = wheelRPM × gearRatio
            //
            //  当前状态（06-08 前）：发动机 RPM 独立计算，与轮速解耦。
            //  后果：可在 5 档踩到 6000 RPM 而车轮慢转——真实世界不可能。
            //
            //  耦合方式：发动机转速不能低于「轮速×齿比」太多。
            //  如果发动机试图低于耦合转速，说明车轮在拖拽发动机
            //  （engine braking / 滑行减速），此时强制发动机跟随轮速。
            //  如果发动机高于耦合转速，则正常加速（发动机在驱动车轮）。
            //
            //  ⚠ 升档后发动机转速高于耦合值的情况，由 gearUp() 在换挡
            //    瞬间按齿比比例同步，不在每 tick 的耦合中处理。
            //    否则会与 P 控制器的加速循环冲突。
            // ═══════════════════════════════════════════════════════════════
            if (currentGear != 0) {
                double absRatio = Math.abs(getCurrentRatio());
                if (absRatio > 0.01) {
                    // 使用合并扫描的结果（不再单独调用 getAverageWheelRpm）
                    double coupledRpm = Math.abs(scan.avgWheelRpm) * absRatio * FINAL_DRIVE_RATIO;

                    // 发动机不能被车轮拖到低于怠速（离合器滑磨/半联动模拟）
                    double minRpm = Math.max(coupledRpm, ENGINE_IDLE_RPM);

                    if (this.engineRpm < minRpm - 50.0) {
                        // 车轮在拖发动机（engine braking / 滑行下坡）
                        // 强制发动机跟随轮速
                        this.engineRpm = Math.min(minRpm, ENGINE_MAX_RPM);
                    }
                    // 如果 engineRpm >= minRpm - 50：正常，发动机在驱动或自由转动
                    // 如果 engineRpm 远大于 coupledRpm：车轮打滑/空转
                }
            }

            // 日志已移除（每 tick debug 日志造成严重性能开销）
        }
    }

    // calculateLoadFactor 已合并到 scanSubLevel() 中
    // 保留原文档参考：
    // 负载因子 = Σ(P控制器力需求 + 滚动阻力) / Σ(引擎扭矩 × 齿比 / 轮半径)
    // < 0.3：引擎轻松，全力加速；0.3~1.0：有负担；> 1.0：过载拖转速

    /** @return 当前档位的齿比（含符号），空档返回 0 */
    private double getCurrentRatio() {
        if (currentGear == 0) return 0;
        if (currentGear == -1) return REVERSE_RATIO;
        return GEAR_RATIOS[currentGear - 1];
    }

    /**
     * 单次扫描 SubLevel 内所有悬挂方块，收集全部所需数据。
     * <p>
     * 原先的 scanThrottleDirection / calculateLoadFactor / getAverageWheelRpm
     * 各自独立做了全量 SubLevel 扫描，每 tick 3 次 → 合并为 1 次。
     */
    private record SubLevelScanResult(int throttleDirection, double loadFactor, double avgWheelRpm) {}

    private SubLevelScanResult scanSubLevel(SubLevel sl) {
        LevelPlot plot = sl.getPlot();
        if (plot == null) return new SubLevelScanResult(0, 0, 0);

        boolean anyForward = false;
        boolean anyBackward = false;
        double totalDemand = 0;
        double totalMaxForce = 0;
        double totalRpm = 0;
        int count = 0;

        double ratio = getCurrentRatio();
        double absRatio = Math.abs(ratio) * FINAL_DRIVE_RATIO;

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic localBounds = chunk.getBoundingBox();
            if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

            int chunkMinX = chunk.getPos().getMinBlockX();
            int chunkMinZ = chunk.getPos().getMinBlockZ();

            for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                    for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                        BlockPos worldPos = new BlockPos(x + chunkMinX, y, z + chunkMinZ);
                        BlockState state = level.getBlockState(worldPos);
                        if (state.getBlock() instanceof SuspensionTestBlock) {
                            BlockEntity be = level.getBlockEntity(worldPos);
                            if (be instanceof SuspensionTestBlockEntity sbe) {
                                // 油门方向
                                if (sbe.isThrottleForward()) anyForward = true;
                                if (sbe.isThrottleBackward()) anyBackward = true;
                                // 负载因子（仅当在档位中才需要）
                                if (currentGear != 0) {
                                    totalDemand += sbe.getTotalEngineLoad();
                                    double wheelRadius = sbe.getWheelRadius();
                                    if (wheelRadius > 0.01) {
                                        totalMaxForce += (this.effectiveTorque * absRatio) / wheelRadius;
                                    }
                                }
                                // 轮速耦合
                                totalRpm += sbe.getCurrentWheelRpm();
                                count++;
                            }
                        }
                    }
                }
            }
        }

        // 油门方向：同时按下或都没按 → 0（互斥）
        int direction = (anyForward == anyBackward) ? 0 : (anyForward ? +1 : -1);

        // 负载因子
        double loadFactor = 0;
        if (count > 0 && totalMaxForce > 0) {
            loadFactor = totalDemand / (totalMaxForce / count);
        }

        // 平均轮速
        double avgWheelRpm = count > 0 ? totalRpm / count : 0.0;

        return new SubLevelScanResult(direction, loadFactor, avgWheelRpm);
    }

    // ====================================================================
    //  NBT 持久化 & 同步
    // ====================================================================

    private static final String TAG_GEAR = "CurrentGear";
    private static final String TAG_RPM  = "EngineRpm";
    private static final String TAG_THROTTLE_LEVEL = "ThrottleLevel";
    private static final String TAG_EFFECTIVE_TORQUE = "EffectiveTorque";
    private static final String TAG_SMART_MAPPING = "SmartMappingActive";
    private static final String TAG_SMART_MAPPING_REVERSED = "SmartMappingReversed";

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(TAG_GEAR, this.currentGear);
        tag.putDouble(TAG_RPM, this.engineRpm);
        tag.putDouble(TAG_THROTTLE_LEVEL, this.throttleLevel);
        tag.putDouble(TAG_EFFECTIVE_TORQUE, this.effectiveTorque);
        tag.putBoolean(TAG_SMART_MAPPING, this.smartMappingActive);
        tag.putBoolean(TAG_SMART_MAPPING_REVERSED, this.smartMappingReversed);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(TAG_GEAR)) this.currentGear = tag.getInt(TAG_GEAR);
        if (tag.contains(TAG_RPM))  this.engineRpm  = tag.getDouble(TAG_RPM);
        if (tag.contains(TAG_THROTTLE_LEVEL)) this.throttleLevel = tag.getDouble(TAG_THROTTLE_LEVEL);
        if (tag.contains(TAG_EFFECTIVE_TORQUE)) this.effectiveTorque = tag.getDouble(TAG_EFFECTIVE_TORQUE);
        if (tag.contains(TAG_SMART_MAPPING)) this.smartMappingActive = tag.getBoolean(TAG_SMART_MAPPING);
        if (tag.contains(TAG_SMART_MAPPING_REVERSED)) this.smartMappingReversed = tag.getBoolean(TAG_SMART_MAPPING_REVERSED);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    // ====================================================================
    //  发动机-轮速耦合
    // ====================================================================

    // getAverageWheelRpm 已合并到 scanSubLevel() 中

    // ====================================================================
    //  工具
    // ====================================================================

    /** 将档位代号转为人名可读的名称。 */
    private static String gearName(int gear) {
        return switch (gear) {
            case -1 -> "R";
            case 0  -> "N";
            default -> String.valueOf(gear);
        };
    }

    /**
     * 动力系统输出记录 —— 每个轮子应获得的目标 RPM 和可用扭矩。
     */
    public record PowertrainOutput(double wheelRpm, double wheelTorque) {}
}
