/*
*这个文件是悬挂测试方块实体类，包含了大量的编译时常量和运行时状态字段，用于控制悬挂系统的物理行为和渲染位置。
注释中详细解释了每个常量的物理意义和调参效果，以及力学模型的概述。
该类还实现了 BlockEntitySubLevelActor 接口，与 Sable 物理引擎集成，实现了复杂的悬挂物理模拟。
结构如下：
1. 视觉偏移常量：控制弹簧、转向轴、轮轴的渲染位置，修改后重编生效。
2. 力学参数常量：包括悬挂弹簧参数、驱动参数、轮胎摩擦参数、刹车参数、二次方速度阻尼、转向参数等，修改后重编生效。
3. 轮胎物理参数：编译时默认值和运行时可编辑的参数，控制轮胎的物理行为。
4. 常量访问器：供渲染器使用的静态方法，返回上述常量值。
5. 按键绑定默认值：定义了默认的控制按键。
6. 运行时状态字段：包括持有的物品、目标转向角、物理状态、力等，以及按键绑定和控制输入的状态。
*/
package com.hainabaichuan75.iac_p.content.blocks.suspension_test;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.events.SubLevelScanner;
import com.hainabaichuan75.iac_p.index.ModBlockEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * 悬挂测试方块实体。
 *
 * <h3>视觉偏移常量（编译时配置）</h3>
 * 以下常量控制弹簧、转向轴、轮轴的渲染位置。
 * 修改后重新编译即可生效，适用于不同轮子类型的适配。
 *
 * <pre>
 * 坐标约定（方块局部坐标系，旋转到 facing 方向后）：
 *   X = 侧向（垂直于 facing 的水平方向）
 *   Y = 垂直（向上为正）
 *   Z = 纵深（沿 facing 方向向外为正）
 * 单位：格（1 格 = 1.0，如 7/16 = 0.4375）
 * </pre>
 *
 * <h3>力学参数常量（编译时配置）</h3>
 * 除视觉偏移外，还包含轮胎摩擦系数、动力参数（RPM/扭矩）、
 * 转向角度范围、转向速率等力学参数。同样通过修改常量后重编生效。
 *
 * @see SuspensionTestRenderer 使用这些常量的渲染器
 */
public class SuspensionTestBlockEntity extends SmartBlockEntity implements BlockEntitySubLevelActor {

    // ====================================================================
    //  视觉偏移常量 —— 修改后重编即可生效
    // ====================================================================

    // ---- 弹簧（两个端点） ----
    /** 弹簧在方块侧的附着点（X=侧向, Y=垂直, Z=纵深） */
    private static final double SPRING_BLOCK_X = 7.0 / 16.0;
    private static final double SPRING_BLOCK_Y = 7.0 / 16.0;
    private static final double SPRING_BLOCK_Z = 0.0;

    /** 弹簧在轮子侧的附着点（X=侧向, Y=垂直, Z=纵深偏移量） */
    private static final double SPRING_WHEEL_X = 0.0;
    private static final double SPRING_WHEEL_Y = -2.0 / 16.0;
    private static final double SPRING_WHEEL_Z = 12.0 / 16.0;

    // ---- 转向轴/望远镜管（两个端点） ----
    /** 转向轴在方块侧的支点 */
    private static final double PIVOT_BLOCK_X = 0.0;
    private static final double PIVOT_BLOCK_Y = -6.0 / 16.0;
    private static final double PIVOT_BLOCK_Z = 0.0;

    /** 转向轴在轮子侧的连接点（相对轮轴） */
    private static final double PIVOT_WHEEL_X = 0.0;
    private static final double PIVOT_WHEEL_Y = 0.0;
    private static final double PIVOT_WHEEL_Z = 0.0;

    // ---- 轮轴 ----
    /** 轮轴支点在方块侧的位置 */
    private static final double WHEEL_PIVOT_X = 0.0;
    private static final double WHEEL_PIVOT_Y = 0.0;
    private static final double WHEEL_PIVOT_Z = 10.0 / 16.0;

    /** 轮子最终渲染位置（相对方块侧） */
    private static final double WHEEL_POS_X = 0.0;
    private static final double WHEEL_POS_Y = 0.0;
    private static final double WHEEL_POS_Z = 22.0 / 16.0;

    // ====================================================================
    //  力学参数常量 —— 修改后重编即可生效
    //
    //  ╔═══════════════════════════════════════════════════════════════╗
    //  ║  摩擦圆模型 (Friction Circle / Coulomb Friction)            ║
    //  ║                                                              ║
    //  ║  每个物理 tick：                                              ║
    //  ║    1. 悬挂弹簧产生法向冲量 N（支撑载具重量）                    ║
    //  ║    2. 摩擦预算 F_max = μ × N（纵向+侧向共享预算）               ║
    //  ║    3. 驱动力+侧向力需求被摩擦圆约束：√(F_long² + F_lat²) ≤ F_max ║
    //  ║    4. 超出预算时两个分量等比例缩减 = 轮子进入滑移/空转状态     ║
    //  ╚═══════════════════════════════════════════════════════════════╝
    // ====================================================================

    // ---- [A] 悬挂弹簧参数 ----
    //
    // ═══════════════════════════════════════════════════════════════════
    //  线性弹簧-阻尼器模型：
    //    F_spring = springK × 压缩量
    //    F_damp   = dampingC × 压缩速度（负号引入方向）
    //    F_total  = F_spring + F_damp  （以冲量形式施加到引擎）
    //
    //  摩擦预算只使用 F_spring 的静载分量，排除阻尼瞬态（见 §摩擦圆）。
    //
    //  质量自适应：轻车线性缩放保持柔顺，重车封顶防止触底。
    //  有效刚度(N/m) = min(nm, MASS_THRESHOLD) × STIFFNESS_PER_NM
    //  有效阻尼(N·s/m) = min(nm, MASS_THRESHOLD) × DAMPING_PER_NM
    //
    //  ╔════════════════════════════════════════════════════════════════╗
    //  ║  调参参考：                                                   ║
    //  ║  • STIFFNESS_PER_NM  调大 → 所有车辆悬挂更硬                  ║
    //  ║  • DAMPING_COEFF_PER_NM  调大 → 震荡衰减更快，路感更清晰      ║
    //  ║  • MASS_THRESHOLD  调大 → 更重的车才锁定刚度上限              ║
    //  ║                                                               ║
    //  ║  当前值效果：                                                  ║
    //  ║    轻车(nm=1)→ springK=400 N/m, dampingC=10 N·s/m（柔软）    ║
    //  ║    重车(nm≥5)→ springK=2000 N/m, dampingC=50 N·s/m（硬朗）   ║
    //  ╚════════════════════════════════════════════════════════════════╝
    /** 弹簧刚度基数（N/m per nm 单位质量）。
     *  重车（nm ≥ MASS_THRESHOLD）刚度 = THRESHOLD × 此值 = 2000 N/m。
     *  原等效值 es=5, nms=min(nm/5,1)×10, ss=es×nms×40 → 400×min(nm,5) */
    private static final double SPRING_STIFFNESS_PER_NM = 400.0;

    /** 阻尼系数基数（N·s/m per nm 单位质量）。
     *  重车封顶阻尼 = THRESHOLD × 此值 = 50 N·s/m。
     *  原等效值 ds = es × nms = 10 × min(nm, 5) */
    private static final double DAMPING_COEFF_PER_NM = 10.0;

    /** 质量自适应悬挂的过渡阈值（nm 单位质量）。
     *  nm ≥ 此值时刚度/阻尼封顶，nm < 此值时按比例线性缩放。
     *  原等效值 = SUSPENSION_STRENGTH(=5) */
    private static final double SUSPENSION_MASS_THRESHOLD = 5.0;

    /** 悬挂最大伸展长度（格）。轮子自然下垂时的最大长度。 */
    private static final double MAX_EXT = 0.65;
    /** 无轮子时的悬挂长度（格）。 */
    private static final double NO_WHEEL_EXT = 0.5;

    // ---- [B] 驱动参数（由座舱动力系统提供） ----
    //
    //  ╔══════════════════════════════════════════════════════════════════════╗
    //  ║  从 v4 开始，DRIVE_RPM/DRIVE_TORQUE 不再使用固定编译时常量。        ║
    //  ║  悬挂方块从载具驾驶舱 (CockpitBlockEntity) 的动力系统中读取：        ║
    //  ║    • 目标轮端 RPM  = 发动机RPM / 当前档位齿比                       ║
    //  ║    • 轮端可用扭矩  = 发动机扭矩 × 齿比 / 轮数（均摊）               ║
    //  ║                                                                     ║
    //  ║  若载具无驾驶舱（降级兼容），回退到以下固定值：                      ║
    //  ╚══════════════════════════════════════════════════════════════════════╝
    /** 降级回退：主动驱动时的目标 RPM（无驾驶舱时使用）。 */
    private static final double FALLBACK_DRIVE_RPM = 400.0;

    /** 降级回退：驱动 P 控制器增益倍率（无驾驶舱时使用）。 */
    private static final double FALLBACK_DRIVE_TORQUE = 80.0;

    // ---- [C] 轮胎与地面摩擦 ----
    /** 轮胎摩擦系数（0.0 = 光滑打滑，1.0 = 标准抓地，>1.0 = 高附着力）
     *  最终摩擦力 = TIRE_FRICTION_COEFFICIENT × 地面摩擦系数 × 法向冲量
     *  典型地面摩擦系数：草/土≈0.6，石头≈0.8，冰≈0.1 */
    private static final double TIRE_FRICTION_COEFFICIENT = 0.9;

    /** 最小摩擦基数乘数。
     *  摩擦基数 = max(弹簧静载冲量, nm × dt × MIN_IMPULSE_MULTIPLIER)
     *  弹簧静载冲量 ≈ nm × g × dt（重量冲量），
     *  最小值 = nm × dt × MULTIPLIER。
     *
     *  物理含义：摩擦预算 = μ × 摩擦基数，决定了摩擦圆能传递的最大水平冲量。
     *  最大可用加速度（不被 P 控制器限制时）= μ × MULTIPLIER（m/s² 级）。
     *  参考：
     *    MULTIPLIER = 30  → 最大 ~27 m/s²（运动型）— 当前值
     *    MULTIPLIER = 10  → 最大 ~9 m/s²（≈ μ×g，接近物理真实）
     *
     *  06-08 从 500 → 30：消除 45g 摩擦预算，可感受抓地边界。
     *  保持 30 而非继续降低到 10：因为 MIN_IMPULSE 服务于两个目的——
     *  (a) 离地轮下限（翻车所需）和 (b) 正常行驶时轻车的抓地力基数。
     *  将 30 降至 10 在无其他补偿的情况下会使正常行驶也「一直飘移」。
     *  离地轮减载应通过载荷转移实现（loadTransfer < -1.0 → adjustedSpringImpulse→0），
     *  而非通过降低 MIN_IMPULSE 全局摩擦地板。两者是正交的。
     *
     *  ⚠ 摩擦预算不能基于 sf（含阻尼），因为阻尼在回弹行程会抵消弹簧力，
     *    导致摩擦预算瞬间塌陷、驱动力归零。必须只用弹簧静载分量。 */
    private static final double MIN_IMPULSE_MULTIPLIER = 30;

    // ---- [D] 刹车参数 ----
    /** 刹车强度倍数（0.0 = 无刹车，1.0 = 完全利用摩擦预算刹车） */
    private static final double BRAKE_STRENGTH = 0.5;

    /** 滚动阻力系数（无输入时的自然减速）。
     *  已迁移到轮胎物理模型，由 crrBase / crrDeformationGain / 胎压联合计算。
     *  旧常量 ROLLING_RESISTANCE=0.4 已在 06-08 清理。 */

    /**
     * 侧偏刚度系数（Brush 轮胎模型）。
     * 决定漂移临界点：值越大，侧偏角达到峰值抓地所需的转角越小。
     * 物理含义：侧向力在侧偏角 α = π/(2 × K) 弧度时达到峰值。
     *   K = 10 → 峰值在 ~9°（标准乘用车）
     *   K = 15 → 峰值在 ~6°（跑车/热熔胎，高初始抓地）
     *   K = 6  → 峰值在 ~15°（越野胎/雪地胎，宽容度高）
     *
     *  06-08 从 10 改为 20：MIN_IMPULSE_MULTIPLIER 从 500 降到 30 后
     *  法向力基数大幅降低 → 侧偏刚度（= K × μ × N）同步下降。
     *  提高 K 保持可接受的直线回正能力，同时峰值角从 9° 收窄到 4.5°，
     *  让漂移临界点更早到来但仍可控。
     */
    private static final double CORNERING_STIFFNESS = 20.0;

    /**
     * 侧滑阻尼系数（仅极低速时使用）。
     * 当纵向速度接近零时，侧偏角无法定义，改用阻尼防止原地自旋。
     * 减速比率 = SIDE_SLIP_DAMPING × dt
     * 6.0 × 0.05 = 0.30 → 每 tick 衰减 30%
     */
    private static final double SIDE_SLIP_DAMPING = 6.0;

    // ====================================================================
    //  二次方速度阻尼（Drag）
    // ====================================================================
    //
    //  模拟高速时与速度平方成正比的自然阻力（真实世界中来自空气阻力）。
    //  与滚动阻力（线性）配合构成完整的阻力模型：
    //
    //     F_total = -v × Crr × m × g  +  -v × |v| × C_drag
    //                ↑ 滚动阻力（低速主导）  ↑ 二次方阻尼（高速主导）
    //
    //  极速收敛条件：引擎出力 = 滚动阻力 + 二次方阻尼
    //  调大 C_drag → 极速降低，加速感不变（仅影响高速区）。
    //
    //  目标极速 120 km/h (33.3 m/s) 标定值：
    //    C_drag = 0.0009（每轮，70kg 车，5档满油）

    /** 二次方速度阻尼系数（每轮）。F_drag = -v × |v| × C_drag，冲量形式 = F_drag × dt
     *  06-08 0.0009→0.002→0.003→0.0045：
     *  配合 crrBase 调整（0.015→0.035, 移除/0.4）重新校准极速到 ~120 km/h */
    private static final double DRAG_COEFFICIENT = 0.0045;

    // ---- [E] 转向参数 ----
    /** 最大转向角（度）。左正右负，范围 0~180。
     *  例如 30.0 表示左右各可转 30° */
    private static final double MAX_STEERING_ANGLE = 30.0;

    /** 转向速率（度/tick）。转向时以匀速向目标转向角转动。
     *  例如 1.5 表示每秒约转 30°（20 tick × 1.5°/tick） */
    private static final double STEERING_SPEED = 10;

    /** 是否自动归正。true = 无转向输入时自动回中；false = 保持在当前角度 */
    private static final boolean AUTO_CENTER = true;

    // ---- [E2] 差速器参数 ----
    /**
     * 差速器滚动半径比。控制转弯时内外轮 RPM 差异幅度。
     *
     *  公式：diffOffset = chasingYaw × (localPosX/HALF_TRACK) × 此值
     *  物理含义：
     *    • 30° 转向、轮距 1 格：diffOffset ≈ 0.524 × 1.0 × 0.3 ≈ ±16%
     *    • 15° 转向、轮距 0.5 格：diffOffset ≈ 0.262 × 0.5 × 0.3 ≈ ±4%
     *
     *  调大 → 转弯更灵活（减小转向阻力），但内侧轮打滑风险增加
     *  调小 → 更像锁止差速器（越野好，转弯阻力大）
     *  设为 0 → 完全锁止差速器（旧行为）
     */

    //人工修改：曾经为0.3，现在改为0.37以增加转弯时的内外轮速差，提升转弯性能和响应性。
    private static final double DIFFERENTIAL_RATIO = 0.37;

   

    // ====================================================================
    //  轮胎物理参数 —— 运行时配置，NBT 持久化，C 键菜单编辑
    // ====================================================================
    //
    //  ╔══════════════════════════════════════════════════════════════════════╗
    //  ║  物理模型概述：                                                    ║
    //  ║                                                                    ║
    //  ║  1. 负重 → 胎体形变 → 有效半径减小 → 接触面积增大                  ║
    //  ║     接触面积 = f(垂直负载, 胎压, 胎体刚度, 胎面宽度)               ║
    //  ║     抓地力 = μ × 胎压 × 接触面积（最终等价于 μ × 法向冲量）        ║
    //  ║                                                                    ║
    //  ║  2. 胎压受负载影响：P_eff = P_nominal + k × (F_normal / A_contact) ║
    //  ║    负重增大 → 形变增大 → 胎内压力上升 → 爆胎风险增大               ║
    //  ║                                                                    ║
    //  ║  3. 滚动阻力来自形变滞后：                                        ║
    //  ║    胎压越足 → 形变越小 → 滚动阻力越小 → 发动机负担减轻             ║
    //  ║    负重越大 → 形变越大 → 滚动阻力越大 → 发动机负担增加             ║
    //  ║                                                                    ║
    //  ║  4. 爆胎条件：有效胎压 × 大气衰减因子 > 最大安全胎压               ║
    //  ║    大气压随海拔升高而降低 → 高海拔更容易爆胎                       ║
    //  ║    胎压越足 → 爆胎风险越高（颠簸中更容易超过极限）                 ║
    //  ╚══════════════════════════════════════════════════════════════════════╝

    // ---- 编译时默认值（真实 SI 单位，典型乘用车轮胎） ----
    /** 胎面宽度（米）。典型值：0.20~0.35 m */
    private static final double DEFAULT_TREAD_WIDTH = 0.25;
    /** 胎体刚度（N/m）。侧壁抵抗形变的能力 */
    private static final double DEFAULT_CARCASS_STIFFNESS = 200000.0;
    /** 最大安全胎压（Pa）。超过此值爆胎 ≈ 3.5 bar */
    private static final double DEFAULT_MAX_PRESSURE = 350000.0;
    /** 标称胎压（Pa）。空载推荐充气压力 ≈ 2.2 bar */
    private static final double DEFAULT_NOMINAL_PRESSURE = 220000.0;
    /** 胎内容积（m³）。约 50 升 */
    private static final double DEFAULT_TIRE_VOLUME = 0.05;
    /** 基础滚动阻力系数（无量纲）。来自轴承摩擦、胎面滞后的基础值。
     *  06-08 从 0.015 改为 0.035：移除了 `/0.4` 向后兼容系数后，
     *  提高 crrBase 保持滚动阻力总量与之前大致相同。
     *  新公式：F_rr = v × Crr_effective × m（无 `/0.4` 放大）
     *  旧公式：F_rr = v × Crr_effective × m / 0.4 */
    private static final double DEFAULT_CRR_BASE = 0.035;
    /** 形变附加滚动阻力系数（无量纲）。形变越大滚动阻力越大的增益系数 */
    private static final double DEFAULT_CRR_DEFORMATION_GAIN = 0.08;

    // ---- 运行时轮胎参数（NBT 持久化，C 键菜单可编辑） ----
    // 胎压（打多少气）是玩家唯一可调的运行时参数。
    // 其余轮胎物理属性由轮胎款式决定（见 DEFAULT_* 编译时常量），
    // 未来将随 TireType 注册表扩展为每种轮胎的预设值。
    private double nominalPressure = DEFAULT_NOMINAL_PRESSURE;

    // ====================================================================
    //  常量访问器（供渲染器使用）
    // ====================================================================

    // 弹簧
    public static double springBlockX() { return SPRING_BLOCK_X; }
    public static double springBlockY() { return SPRING_BLOCK_Y; }
    public static double springBlockZ() { return SPRING_BLOCK_Z; }
    public static double springWheelX() { return SPRING_WHEEL_X; }
    public static double springWheelY() { return SPRING_WHEEL_Y; }
    public static double springWheelZ() { return SPRING_WHEEL_Z; }

    // 转向轴
    public static double pivotBlockX() { return PIVOT_BLOCK_X; }
    public static double pivotBlockY() { return PIVOT_BLOCK_Y; }
    public static double pivotBlockZ() { return PIVOT_BLOCK_Z; }
    public static double pivotWheelX() { return PIVOT_WHEEL_X; }
    public static double pivotWheelY() { return PIVOT_WHEEL_Y; }
    public static double pivotWheelZ() { return PIVOT_WHEEL_Z; }

    // 轮轴
    public static double wheelPivotX() { return WHEEL_PIVOT_X; }
    public static double wheelPivotY() { return WHEEL_PIVOT_Y; }
    public static double wheelPivotZ() { return WHEEL_PIVOT_Z; }
    public static double wheelPosX()   { return WHEEL_POS_X; }
    public static double wheelPosY()   { return WHEEL_POS_Y; }
    public static double wheelPosZ()   { return WHEEL_POS_Z; }

    // ---- 力学参数访问器（供渲染器/外部使用） ----
    /** @return 轮胎摩擦系数 */
    public static double tireFrictionCoefficient() { return TIRE_FRICTION_COEFFICIENT; }
    /** @return 最大转向角（度） */
    public static double maxSteeringAngle() { return MAX_STEERING_ANGLE; }
    /** @return 转向速率（度/tick） */
    public static double steeringSpeed() { return STEERING_SPEED; }
    /** @return 是否启用自动归正 */
    public static boolean autoCenter() { return AUTO_CENTER; }

    // ====================================================================
    //  按键绑定默认值
    // ====================================================================

    public static final String DEFAULT_KEY_FORWARD   = "key.keyboard.w";
    public static final String DEFAULT_KEY_BACKWARD  = "key.keyboard.s";
    public static final String DEFAULT_KEY_LEFT      = "key.keyboard.a";
    public static final String DEFAULT_KEY_RIGHT     = "key.keyboard.d";
    public static final String DEFAULT_KEY_BRAKE     = "key.keyboard.space";

    // ====================================================================
    //  运行时状态字段
    // ====================================================================

    // ---- 轮子物品 ----
    private ItemStack heldItem = ItemStack.EMPTY;

    /** 外部设置的目标转向角（弧度），由 WASD 控制等外部系统写入 */
    private double targetSteeringYaw = 0.0;

    // 物理状态
    private double extension = NO_WHEEL_EXT, lastExt = extension;
    private double chasingYaw, lastChasingYaw;
    private double lastAngle, angle;
    private double angVel;
    private double touchFriction = 1.0;
    private boolean lifted;

    // 力
    private final Vector3d forcePos = new Vector3d();
    private final Vector3d forceVec = new Vector3d();
    private final ForceTotal forceTotal = new ForceTotal();

    // ===== 载具按键绑定（每个方块独立配置，持久化到 NBT） =====
    private String keyForward   = DEFAULT_KEY_FORWARD;
    private String keyBackward  = DEFAULT_KEY_BACKWARD;
    private String keyLeft      = DEFAULT_KEY_LEFT;
    private String keyRight     = DEFAULT_KEY_RIGHT;
    private String keyBrake     = DEFAULT_KEY_BRAKE;

    // ===== 智能映射按键（WASD 智能映射系统分配，不与手动按键冲突） =====
    // 当 smartKey* 非空时优先使用，否则回退到手动 key*
    private String smartKeyForward   = "";
    private String smartKeyBackward  = "";
    private String smartKeyLeft      = "";
    private String smartKeyRight     = "";
    private String smartKeyBrake     = "";

    // ===== 运行时控制输入（由 VehicleControlC2SPacket 写入，物理 tick 读取） =====
    /** 油门正向（前进）是否激活。不再直接将 activeRpm 设为固定值，
     * 而是由物理 tick 从座舱动力系统读取目标 RPM。 */
    private boolean throttleForward = false;
    /** 油门反向（后退）是否激活。 */
    private boolean throttleBackward = false;
    /** 是否正在刹车 */
    private boolean braking = false;

    // ===== 引擎负载报告（供 CockpitBE 读取） =====
    /** P 控制器原始力需求（摩擦圆约束前），供座舱计算引擎负载 */
    private double pControllerDemand = 0.0;
    /** 滚动阻力幅值，供座舱计算引擎负载 */
    private double rollingResistanceMag = 0.0;

    // ===== 缓存优化：避免每物理 tick 全量 SubLevel 扫描 =====
    /** 缓存的驾驶舱引用。null = 需要首次扫描刷新。
     *  驾驶过程中 SubLevel 构成不变，缓存后无需定期失效。 */
    private CockpitBlockEntity cachedCockpit = null;

    /** 缓存的轮子总数。0 = 需要首次扫描刷新。
     *  与 cachedCockpit 同时刷新，确保一致性。 */
    private int cachedWheelCount = 0;

    /**
     * 失效缓存。下次访问时触发重新扫描。
     * <p>
     * 调用时机：
     * <ul>
     *   <li>上下车时（由外部系统调用）</li>
     *   <li>部件损坏时（未来扩展：通过 {@link #registerInvalidationHandler} 注册）</li>
     * </ul>
     */
    public void invalidateCache() {
        this.cachedCockpit = null;
        this.cachedWheelCount = 0;
    }

    /** 可扩展的缓存失效事件列表。
     *  其他系统（如未来的部件损坏系统）可注册 Runnable，
     *  在需要刷新 SuspensionBE 缓存时被回调。 */
    private static final java.util.List<java.util.function.Consumer<java.util.UUID>> INVALIDATION_HANDLERS = new java.util.ArrayList<>();

    /**
     * 注册缓存失效处理器。
     * 当指定 SubLevel 内的悬挂缓存需要刷新时，传入的 consumer 会被调用。
     * @param handler 接收 SubLevel UUID 的消费者
     */
    public static void registerInvalidationHandler(java.util.function.Consumer<java.util.UUID> handler) {
        INVALIDATION_HANDLERS.add(handler);
    }

    /**
     * 通知所有处理器：SubLevel 内的悬挂缓存需要失效。
     * 由外部系统（PlayerMountTracker、未来部件损坏系统）调用。
     * @param subLevelUUID 发生变化的 SubLevel UUID
     */
    public static void notifySubLevelChanged(java.util.UUID subLevelUUID) {
        for (var handler : INVALIDATION_HANDLERS) {
            handler.accept(subLevelUUID);
        }
    }

    /**
     * 扫描指定 SubLevel，使其中所有 SuspensionTestBlockEntity 的缓存失效。
     * 由外部系统（PlayerMountTracker）在 mount/dismount 时调用。
     */
    public static void invalidateCachesInSubLevel(net.minecraft.world.level.Level level, java.util.UUID subLevelUUID) {
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;
        dev.ryanhcode.sable.sublevel.SubLevel sl = container.getSubLevel(subLevelUUID);
        if (sl == null) return;

        SubLevelScanner.forEachBlock(sl, level, (worldPos, state, be) -> {
            if (be instanceof SuspensionTestBlockEntity sbe) {
                sbe.invalidateCache();
            }
        });
    }

    /**
     * 总水平力需求 / 摩擦预算 比率。
     * <ul>
     *   <li>&lt; 1.0 = 有抓地余量</li>
     *   <li>= 1.0 = 摩擦圆刚好饱和</li>
     *   <li>&gt; 1.0 = 力需求超过抓地极限 → 轮子空转/打滑</li>
     * </ul>
     * 通过 NBT 同步到客户端，用于调试覆盖层显示动力盈余。
     */
    private double frictionDemandRatio = 0.0;
    /** NBT 同步节流计数器（控制摩擦需求比同步频率） */
    private int frictionSyncCooldown = 0;
    /** 上次同步到客户端的摩擦需求比值，用于差值检测 */
    private double lastSyncedFrictionRatio = -1.0;

    // ===== 发动机-轮速耦合数据 =====
    /**
     * 本轮当前实际轮端 RPM（由 physicsTick 从物理速度推算）。
     * 供 CockpitBE 读取以计算 coupledRpm = avgWheelRpm × gearRatio，
     * 实现发动机转速与车轮转速的刚性耦合。
     */
    private double currentWheelRpm = 0.0;

    // ===== 载荷转移状态 =====
    /**
     * 上一 tick 的局部坐标系速度，用于计算本 tick 的加速度。
     * 载荷转移 = f(加速度, 轮位) → 动态调整各轮抓地力。
     */
    private final Vector3d prevLocalVelocity = new Vector3d();
    /** 是否有上一 tick 的速度数据 */
    private boolean hasPrevVelocity = false;

    // ---- 载荷转移参数 ----
    /**
     * 载荷转移灵敏度：每 g 加速度转移的载荷比例。
     * 0.3 意味着 0.5g 加速时转移 15% 的静载从前轴到后轴。
     * 调大 → 加速/刹车/转弯时抓地力变化更明显。 */
    private static final double LOAD_TRANSFER_SENSITIVITY = 0.3;
    /**
     * 估算重心高度（格）。用于载荷转移幅度计算。
     * 载具引擎/驾驶舱一般在 y+0.5~1.0 格处。 */
    private static final double COG_HEIGHT = 0.8;
    /**
     * 估算半轴距（格）。用于将轮位归一化到 [-1, 1]。
     * 实际值由 SubLevel 中悬挂方块的分布决定，这里用默认值
     * 作为特征长度参考。 */
    private static final double HALF_WHEELBASE = 1.5;
    /**
     * 估算半轮距（格）。同上。 */
    private static final double HALF_TRACK = 1.0;

    // ====================================================================
    //  轮胎参数访问器（供配置屏幕和外部使用）
    // ====================================================================

    /** @return 当前胎压（Pa），玩家可调的唯一轮胎参数 */
    public double getNominalPressure() { return nominalPressure; }

    /**
     * 设置胎压（由 PressureConfigC2SPacket 调用）。
     * 保存后标记脏数据并同步到客户端。
     */
    public void setNominalPressure(double nominalPressure) {
        this.nominalPressure = nominalPressure;
        setChanged();
        sendData();
    }

    /**
     * @return P 控制器原始力需求（摩擦圆约束前），正值表示正向驱动力需求
     */
    public double getPControllerDemand() { return pControllerDemand; }

    /** @return 滚动阻力幅值（绝对值，仅大小） */
    public double getRollingResistanceMag() { return rollingResistanceMag; }

    /**
     * @return 总引擎负载力需求 = |P控制器需求| + 滚动阻力
     *         座舱用此值计算引擎负载因子
     */
    public double getTotalEngineLoad() {
        return Math.abs(pControllerDemand) + rollingResistanceMag;
    }

    /** @return 本轮当前实际轮端 RPM（由物理速度推算），供发动机-轮速耦合 */
    public double getCurrentWheelRpm() { return currentWheelRpm; }

    /** @return 总水平力需求 / 摩擦预算比率（可超过 1.0，表示打滑程度） */
    public double getFrictionDemandRatio() { return frictionDemandRatio; }

    /** @return 轮子半径（米），无轮子时返回默认 0.5 */
    public double getWheelRadius() {
        if (heldItem.isEmpty()) return 0.5;
        TireLike tire = heldItem.get(OffroadDataComponents.TIRE);
        return tire != null ? tire.radius() : 0.5;
    }

    /** 重置胎压为默认值 */
    public void resetPressureToDefault() {
        setNominalPressure(DEFAULT_NOMINAL_PRESSURE);
    }

    public SuspensionTestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntityTypes.SUSPENSION_TEST.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    // ===== 轮子物品 =====

    public ItemStack getHeldItem() { return heldItem; }
    public void setHeldItem(ItemStack s) {
        this.heldItem = s.copyWithCount(1);
        setChanged();
        sendData();
    }

    // ===== 按键绑定存取 =====

    public String getKeyForward()   { return keyForward; }
    public String getKeyBackward()  { return keyBackward; }
    public String getKeyLeft()      { return keyLeft; }
    public String getKeyRight()     { return keyRight; }
    public String getKeyBrake()     { return keyBrake; }

    /**
     * 批量设置 5 个按键绑定（由 VehicleKeyConfigC2SPacket 调用）。
     * 保存后标记脏数据并同步到客户端。
     */
    public void setKeyBindings(String forward, String backward, String left, String right, String brake) {
        this.keyForward   = forward;
        this.keyBackward  = backward;
        this.keyLeft      = left;
        this.keyRight     = right;
        this.keyBrake     = brake;
        setChanged();
        sendData();
    }

    // ===== 智能映射按键存取 =====

    public String getSmartKeyForward()   { return smartKeyForward; }
    public String getSmartKeyBackward()  { return smartKeyBackward; }
    public String getSmartKeyLeft()      { return smartKeyLeft; }
    public String getSmartKeyRight()     { return smartKeyRight; }
    public String getSmartKeyBrake()     { return smartKeyBrake; }

    /**
     * @return 生效的前进键：智能映射键非空时优先，否则回退到手动配置
     */
    public String getActiveKeyForward() {
        return smartKeyForward.isEmpty() ? keyForward : smartKeyForward;
    }

    /** @return 生效的后退键 */
    public String getActiveKeyBackward() {
        return smartKeyBackward.isEmpty() ? keyBackward : smartKeyBackward;
    }

    /** @return 生效的左转键 */
    public String getActiveKeyLeft() {
        return smartKeyLeft.isEmpty() ? keyLeft : smartKeyLeft;
    }

    /** @return 生效的右转键 */
    public String getActiveKeyRight() {
        return smartKeyRight.isEmpty() ? keyRight : smartKeyRight;
    }

    /** @return 生效的刹车键 */
    public String getActiveKeyBrake() {
        return smartKeyBrake.isEmpty() ? keyBrake : smartKeyBrake;
    }

    /**
     * 批量设置智能映射按键（由 WASD 智能映射系统调用）。
     * 设置后对应方块将使用 smartKey 而非手动 key。
     */
    public void setSmartKeyBindings(String forward, String backward, String left, String right, String brake) {
        this.smartKeyForward   = forward;
        this.smartKeyBackward  = backward;
        this.smartKeyLeft      = left;
        this.smartKeyRight     = right;
        this.smartKeyBrake     = brake;
        setChanged();
        sendData();
    }

    /**
     * 清除所有智能映射按键，回退到手动配置。
     * 下车或重新扫描方向时调用。
     */
    public void resetSmartKeys() {
        this.smartKeyForward   = "";
        this.smartKeyBackward  = "";
        this.smartKeyLeft      = "";
        this.smartKeyRight     = "";
        this.smartKeyBrake     = "";
        setChanged();
        sendData();
    }

    // ===== 控制输入（由 VehicleControlC2SPacket 每 tick 写入） =====

    /**
     * @return 刹车踏板是否被踩下
     */
    public boolean isBraking() { return braking; }

    /**
     * 将所有控制输入重置为松开状态（可选保留刹车）。
     * 玩家下车时调用，防止输入状态残留导致载具自行运动。
     * 如果玩家下车前拉了手刹（空格），则保留刹车让载具保持静止。
     */
    public void resetControlInput(boolean keepBrake) {
        this.throttleForward = false;
        this.throttleBackward = false;
        if (!keepBrake) this.braking = false;
        this.targetSteeringYaw = 0.0;
    }

    /**
     * 应用控制输入状态。
     * 客户端检测到按键按下/抬起变化后发送此数据，
     * 服务端据此调整油门状态和转向目标。
     * <p>
     * 油门状态写入共享 Map，由座舱在 {@code tick()} 中读取，
     * 不受 BE tick 顺序或物理 tick 时序影响。
     */
    public void applyControlInput(boolean forward, boolean backward, boolean left, boolean right, boolean brake) {
        // 油门状态：forward/backward 互斥，同时按下时视为无输入
        if (forward && !backward) {
            this.throttleForward = true;
            this.throttleBackward = false;
        } else if (backward && !forward) {
            this.throttleForward = false;
            this.throttleBackward = true;
        } else {
            this.throttleForward = false;
            this.throttleBackward = false;
        }

        // 不再需要通过共享 Map 报告油门——CockpitBE.tick() 现在直接扫描
        // 悬挂方块的 throttleForward/Backward 字段，零时序冲突。

        // 转向：left/right 互斥，同时按下时回中
        if (left && !right) {
            setTargetSteeringYaw(Math.toRadians(MAX_STEERING_ANGLE));
        } else if (right && !left) {
            setTargetSteeringYaw(Math.toRadians(-MAX_STEERING_ANGLE));
        } else {
            setTargetSteeringYaw(0.0);
        }

        // 刹车
        this.braking = brake;
    }

    /** @return 是否有正向油门输入 */
    public boolean isThrottleForward() { return throttleForward; }
    /** @return 是否有反向油门输入 */
    public boolean isThrottleBackward() { return throttleBackward; }
    /** @return 是否有任何油门输入 */
    public boolean hasThrottle() { return throttleForward || throttleBackward; }

    // ===== NBT =====

    private static final String TAG_KEY_FORWARD  = "KeyForward";
    private static final String TAG_KEY_BACKWARD = "KeyBackward";
    private static final String TAG_KEY_LEFT     = "KeyLeft";
    private static final String TAG_KEY_RIGHT    = "KeyRight";
    private static final String TAG_KEY_BRAKE    = "KeyBrake";

    // 智能映射按键 NBT 标签
    private static final String TAG_SMART_KEY_FORWARD  = "SmartKeyForward";
    private static final String TAG_SMART_KEY_BACKWARD = "SmartKeyBackward";
    private static final String TAG_SMART_KEY_LEFT     = "SmartKeyLeft";
    private static final String TAG_SMART_KEY_RIGHT    = "SmartKeyRight";
    private static final String TAG_SMART_KEY_BRAKE    = "SmartKeyBrake";

    // 轮胎参数 NBT 标签（仅保留胎压，其余由轮胎款式决定）
    private static final String TAG_NOMINAL_PRESSURE      = "NominalPressure";
    private static final String TAG_FRICTION_DEMAND_RATIO = "FrictionDemandRatio";

    @Override
    protected void write(CompoundTag t, HolderLookup.Provider r, boolean cp) {
        super.write(t, r, cp);
        t.put("HeldItem", this.heldItem.saveOptional(r));
        // 持久化按键绑定
        t.putString(TAG_KEY_FORWARD,  this.keyForward);
        t.putString(TAG_KEY_BACKWARD, this.keyBackward);
        t.putString(TAG_KEY_LEFT,     this.keyLeft);
        t.putString(TAG_KEY_RIGHT,    this.keyRight);
        t.putString(TAG_KEY_BRAKE,    this.keyBrake);
        // 持久化智能映射按键
        t.putString(TAG_SMART_KEY_FORWARD,  this.smartKeyForward);
        t.putString(TAG_SMART_KEY_BACKWARD, this.smartKeyBackward);
        t.putString(TAG_SMART_KEY_LEFT,     this.smartKeyLeft);
        t.putString(TAG_SMART_KEY_RIGHT,    this.smartKeyRight);
        t.putString(TAG_SMART_KEY_BRAKE,    this.smartKeyBrake);
        // 持久化胎压（玩家唯一可调的运行时参数）
        t.putDouble(TAG_NOMINAL_PRESSURE,     this.nominalPressure);
        t.putDouble(TAG_FRICTION_DEMAND_RATIO, this.frictionDemandRatio);
    }

    @Override
    protected void read(CompoundTag t, HolderLookup.Provider r, boolean cp) {
        super.read(t, r, cp);
        if (t.contains("HeldItem"))
            this.heldItem = ItemStack.parseOptional(r, t.getCompound("HeldItem"));
        // 恢复按键绑定
        if (t.contains(TAG_KEY_FORWARD))  this.keyForward   = t.getString(TAG_KEY_FORWARD);
        if (t.contains(TAG_KEY_BACKWARD)) this.keyBackward  = t.getString(TAG_KEY_BACKWARD);
        if (t.contains(TAG_KEY_LEFT))     this.keyLeft      = t.getString(TAG_KEY_LEFT);
        if (t.contains(TAG_KEY_RIGHT))    this.keyRight     = t.getString(TAG_KEY_RIGHT);
        if (t.contains(TAG_KEY_BRAKE))    this.keyBrake     = t.getString(TAG_KEY_BRAKE);
        // 恢复智能映射按键（兼容旧档——无此标签时保持空字符串）
        if (t.contains(TAG_SMART_KEY_FORWARD))  this.smartKeyForward   = t.getString(TAG_SMART_KEY_FORWARD);
        if (t.contains(TAG_SMART_KEY_BACKWARD)) this.smartKeyBackward  = t.getString(TAG_SMART_KEY_BACKWARD);
        if (t.contains(TAG_SMART_KEY_LEFT))     this.smartKeyLeft      = t.getString(TAG_SMART_KEY_LEFT);
        if (t.contains(TAG_SMART_KEY_RIGHT))    this.smartKeyRight     = t.getString(TAG_SMART_KEY_RIGHT);
        if (t.contains(TAG_SMART_KEY_BRAKE))    this.smartKeyBrake     = t.getString(TAG_SMART_KEY_BRAKE);
        // 恢复胎压（兼容旧档——无此标签时保持默认值，旧版其他轮胎参数标签被忽略）
        if (t.contains(TAG_NOMINAL_PRESSURE))     this.nominalPressure    = t.getDouble(TAG_NOMINAL_PRESSURE);
        if (t.contains(TAG_FRICTION_DEMAND_RATIO)) this.frictionDemandRatio = t.getDouble(TAG_FRICTION_DEMAND_RATIO);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider r) { return saveWithoutMetadata(r); }

    // ===== 物理 tick =====

    /**
     * 设置外部目标转向角（弧度）。由 WASD 控制等外部系统调用。
     * 值会被钳制在 ±MAX_STEERING_ANGLE 范围内。
     */
    public void setTargetSteeringYaw(double radians) {
        this.targetSteeringYaw = Mth.clamp(radians, Math.toRadians(-MAX_STEERING_ANGLE), Math.toRadians(MAX_STEERING_ANGLE));
    }

    @Override
    public void sable$physicsTick(ServerSubLevel sl, RigidBodyHandle handle, double dt) {
        TireLike tire = this.heldItem.get(OffroadDataComponents.TIRE);
        if (tire == null) return;

        BlockPos bp = getBlockPos();
        float rad = tire.radius();
        double rest = MAX_EXT;
        MassData md = sl.getMassTracker();
        Direction f = getBlockState().getValue(SuspensionTestBlock.HORIZONTAL_FACING);
        Vec3 lp = bp.relative(f).getCenter();
        forcePos.set(lp.x, lp.y, lp.z);

        double nm = 1.0 / md.getInverseNormalMass(forcePos, new Vector3d(0, 1, 0));
        // 有效弹簧刚度(N/m)和阻尼系数(N·s/m)
        // 轻车（nm < 阈值）线性缩放保持柔顺，重车封顶防止触底
        double limitedNm = Math.min(nm, SUSPENSION_MASS_THRESHOLD);
        double springK = limitedNm * SPRING_STIFFNESS_PER_NM;
        double dampingC = limitedNm * DAMPING_COEFF_PER_NM;

        Pose3dc pose = sl.logicalPose();
        Direction.Axis axis = f.getAxis();
        Vector3dc sideD = rotAxis(axis);
        Vector3dc fwdD = rotPerp(axis);

        // 预计算本轮侧向/纵向位置（用于差速器 + 载荷转移）
        // 通过 SubLevel 区块坐标与驾驶舱位置的偏移确定轮位
        // localPosZ > 0 = 前轮，localPosX > 0 = 右轮
        double localPosX = 0;
        double localPosZ = 0;
        CockpitBlockEntity preCockpit = findCockpitInSubLevel(sl);
        if (preCockpit != null) {
            BlockPos cockpitPos = preCockpit.getBlockPos();
            double worldDx = bp.getX() - cockpitPos.getX();
            double worldDz = bp.getZ() - cockpitPos.getZ();
            localPosZ = worldDx * fwdD.x() + worldDz * fwdD.z(); // 纵向 (+前)
            localPosX = worldDx * sideD.x() + worldDz * sideD.z(); // 侧向 (+右)
        }

        var terr = rayTerrain(fwdD, pose);
        double me = terr.maxExtension();
        this.extension = Mth.lerp(1.0, this.extension, me);
        if (me > rest + rad + 0.25) { this.extension = rest; return; }

        double d = (rest / 6.0) + this.extension;
        double slen = Mth.clamp(d - rad, 0.0, rest);
        Vector3d vel = Sable.HELPER.getVelocity(this.level, JOMLConversion.toJOML(lp));
        Vector3d lv = pose.transformNormalInverse(vel);

        double df = -lv.y * dampingC;
        double sf = ((rest - slen) * springK + df) * dt;

        Vec3i hn = terr.normal().getNormal();
        Vec3 lf = new Vec3(sf * hn.getX(), sf * hn.getY(), sf * hn.getZ());
        if (terr.subLevel() != null) lf = terr.subLevel().logicalPose().transformNormal(lf);
        lf = pose.transformNormalInverse(lf);
        forceVec.set(lf.x, lf.y, lf.z);

        // ===== 摩擦圆模型 =====
        // 核心约束：√(纵向力² + 侧向力²) ≤ μ × 法向冲量
        // 法向冲量来自悬挂弹簧的静载分量（不含冲击阻尼），
        // 确保落地时不会瞬间获得巨大抓地力，行驶时又有足够牵引力。
        {
            // 1. 地面摩擦系数
            if (terr.minInteractingBlock() != null)
                this.touchFriction = fudge(PhysicsBlockPropertyHelper.getFriction(
                        this.level.getBlockState(terr.minInteractingBlock())));
            else this.touchFriction = 1.0;

            // 2. 综合摩擦系数 μ = 轮胎系数 × 地面系数
            double mu = TIRE_FRICTION_COEFFICIENT * this.touchFriction;

            // 3. 法向冲量 = 弹簧静载分量（仅 |弹簧压缩 × 刚度 × dt|，排除阻尼瞬态）
            //    ⚠ 不能使用 sf（含阻尼），因为阻尼在回弹行程会抵消弹簧力，
            //    导致摩擦预算瞬间塌陷 → 驱动力被摩擦圆削减 → 轮子空转车不动。
            //    弹簧静载冲量在稳态 ≈ nm × g × dt，是摩擦预算的可靠下限。
            double springImpulse = Math.abs((rest - slen) * springK * dt);

            // ═══════════════════════════════════════════════════════════════
            //  动态载荷转移（Load Transfer）
            // ═══════════════════════════════════════════════════════════════
            //
            //  物理直觉：车辆加速/刹车/转弯时，惯性力使重心偏移，
            //  改变各轮法向载荷。载荷直接决定抓地力（摩擦预算）。
            //
            //    ╔══════════════════════════════════════════════════════╗
            //    ║  加速 ──→ 重心后移 ──→ 后轮抓地↑ 前轮抓地↓      ║
            //    ║  刹车 ──→ 重心前移 ──→ 前轮抓地↑ 后轮抓地↓      ║
            //    ║  左转 ──→ 重心右移 ──→ 右轮抓地↑ 左轮抓地↓      ║
            //    ║                                                     ║
            //    ║  每个 tick 从速度变化计算惯性加速度，按轮位分配   ║
            //    ║  载荷调整量附加到 springImpulse 上（± 系数）。     ║
            //    ╚══════════════════════════════════════════════════════╝
            //
            //  ═══════════════════════════════════════════════════════════════
            //  ⚠ 关键修正（06-08）：引入稳态离心加速度
            //  ═══════════════════════════════════════════════════════════════
            //
            //  旧代码：accelX = (lv.x - prev_lv.x) / dt
            //    → 仅捕捉「瞬态」侧向加速度。在定圆稳态转弯中，
            //      局部坐标系速度不变（始终≈向前），accelX→0。
            //    → 后果：转弯全程无离心载荷转移，车身不侧倾、不翻车。
            //
            //  新代码：accelX = 瞬态 + 稳态
            //    稳态离心加速度 = forwardSpeed × yawRate
            //    偏航率 yawRate ≠ 0 时，转弯全程产生持续的侧向载荷转移。
            //    物理推导：在旋转参考系中，dv_x/dt = ω_y × v_z（科里奥利项）
            //    即稳态加速度 = 偏航率 × 前进速度
            //
            //    左转 (yawRate>0) → 稳态 accelX>0 → 离心力向右(正X)
            //      → 右轮增载、左轮减载 ✅
            //    右转 (yawRate<0) → 稳态 accelX<0 → 离心力向左(负X)
            //      → 左轮增载、右轮减载 ✅
            //
            double loadTransfer = 0.0;
            if (this.hasPrevVelocity) {
                // 首先计算前进速度（用于稳态离心加速度）
                double fwdSpeed = lv.dot(fwdD);

                // 瞬态加速度分量（来自速度变化）
                double accelZ = (lv.z() - this.prevLocalVelocity.z()) / dt; // 纵向 (+前进)
                double accelX = (lv.x() - this.prevLocalVelocity.x()) / dt; // 侧向瞬态 (+右)

                // 稳态离心加速度分量（来自偏航率）
                // 定圆转弯时瞬态分量≈0，但离心力持续存在
                Vector3d angVel = handle.getAngularVelocity(new Vector3d());
                double yawRate = angVel.y(); // rad/s, 正=左转(CCW)
                double steadyLatAccel = fwdSpeed * yawRate; // v × ω
                accelX += steadyLatAccel;

                // 使用 physicsTick 顶部预计算的轮位（localPosX/Z, preCockpit）
                if (preCockpit != null) {
                    // 归一化到 [-1, 1]
                    double normZ = Mth.clamp(localPosZ / HALF_WHEELBASE, -1.0, 1.0);
                    double normX = Mth.clamp(localPosX / HALF_TRACK, -1.0, 1.0);

                    // 载荷转移增量（占静载比例）
                    // 纵向：加速 → 前轮减载(负) 后轮增载(正)
                    //       公式：-accelZ × CoG_h / (g × halfWb) × normZ
                    //       前轮 normZ>0 → 负调整，后轮 normZ<0 → 正调整 ✓
                    // 侧向：左转(yawRate>0, steadyLatAccel>0) → 离心力向右(正X)
                    //       公式：+accelX × CoG_h / (g × halfTrack) × normX
                    //       右轮 normX>0 → 正调整(增载) ✓
                    //       左轮 normX<0 → 负调整(减载) ✓
                    double g = 9.81;
                    double longTransfer = -accelZ * COG_HEIGHT / (g * HALF_WHEELBASE) * normZ;
                    double latTransfer  = +accelX * COG_HEIGHT / (g * HALF_TRACK) * normX;

                    loadTransfer = (longTransfer + latTransfer) * LOAD_TRANSFER_SENSITIVITY;
                    loadTransfer = Mth.clamp(loadTransfer, -0.8, 0.8);
                }
            }
            // 保存本次速度供下一 tick 使用
            this.prevLocalVelocity.set(lv.x(), lv.y(), lv.z());
            this.hasPrevVelocity = true;

            // 应用载荷转移：抓地力 = 弹簧静载 ± 惯性载荷系数
            double adjustedSpringImpulse = springImpulse * (1.0 + loadTransfer);

            // 最小摩擦基数：确保轻质载具即使弹簧压缩极小也有足够抓地力
            // min = nm × dt × 20 ≈ 2 × nm × g × dt（约 2 倍重量冲量）
            double minImpulse = nm * dt * MIN_IMPULSE_MULTIPLIER;
            double frictionBasis = Math.max(adjustedSpringImpulse, minImpulse);
            // 摩擦预算 = μ × 有效法向冲量 —— 轮胎能传递的最大水平冲量
            double frictionBudget = mu * frictionBasis;

            // 4. 速度分解
            double forwardSpeed = lv.dot(fwdD);
            double lateralSpeed = lv.dot(sideD);
            double longForce = 0;
            double latForce = 0;

            // 轮胎状态（跨越刹车/正常驾驶共用）
            double effectivePressure = nominalPressure;
            double tireDeflection = 0.0;

            if (this.braking) {
                // ===== 手刹模式：轮子抱死，纯滑动摩擦 =====
                //
                // ╔══════════════════════════════════════════════════════════════╗
                // ║  物理模型：                                                ║
                // ║  1. 轮子抱死，不再滚动                                     ║
                // ║  2. 车辆依靠轮胎-地面滑动摩擦减速                          ║
                // ║  3. 驱动力 → 切断                                          ║
                // ║  4. 滚动阻力 → 切断（轮子不转，无滚动摩擦）                 ║
                // ║  5. 侧滑阻尼 → 切断（轮子不转，无回正力矩）                ║
                // ║  6. 摩擦力沿总速度反方向，而非仅纵向                        ║
                // ║  7. 摩擦力幅值 = BRAKE_STRENGTH × μ × springImpulse        ║
                // ║                                                             ║
                // ║  ⚠ 关键：必须用 springImpulse（真实法向冲量），             ║
                // ║     不能用 frictionBasis！后者被 MIN_IMPULSE_MULTIPLIER     ║
                // ║     (=500) 膨胀了 ~51 倍，导致减速度高达 18g，              ║
                // ║     轻车一脚刹车直接钉在地上。                              ║
                // ║                                                             ║
                // ║  正确值：brakeMag = 0.5 × 0.7 × nm × 0.49 = nm × 0.17      ║
                // ║  减速度 ≈ 3.4 m/s² ≈ 0.35g ✓                              ║
                // ╚══════════════════════════════════════════════════════════════╝
                double totalSpeed = Math.sqrt(forwardSpeed * forwardSpeed + lateralSpeed * lateralSpeed);
                if (totalSpeed > 1e-8) {
                    // 使用弹簧静载冲量（真实法向力），而非膨胀后的 frictionBasis
                    double brakeMag = BRAKE_STRENGTH * mu * springImpulse;
                    // 沿总速度反方向分解摩擦力
                    longForce = -(forwardSpeed / totalSpeed) * brakeMag;
                    latForce  = -(lateralSpeed / totalSpeed) * brakeMag;
                }
                // totalSpeed ≈ 0：车辆已静止，无需额外力
                // 刹车时引擎不驱动轮子，负载报告归零
                this.pControllerDemand = 0.0;
                this.rollingResistanceMag = 0.0;
            } else {
                // ===== 正常驾驶模式 =====

                // 4a. 从座舱动力系统获取轮端目标 RPM 和可用扭矩
                //     若 SubLevel 内无驾驶舱（降级兼容），使用固定回退值
                double targetRpm;
                double torqueGain;
                if (preCockpit != null) {
                    // 统计 SubLevel 内悬挂方块总数（用于扭矩均摊）
                    int totalWheels = countSuspensionBlocksInSubLevel(sl);
                    var output = preCockpit.getWheelOutput(totalWheels);
                    targetRpm = output.wheelRpm();
                    torqueGain = output.wheelTorque();

                    // ═══ 差速器：转弯时内外轮允许转速差 ═══
                    // 开放式差速器模拟：锁止差速器强制内外轮同转速，
                    // 转向时轮胎被地面拖拽产生额外阻力。
                    // 这里按轮位和转向角微调目标 RPM：
                    //   左转(chasingYaw>0) → 左轮(内侧)减速，右轮(外侧)加速
                    //   右转(chasingYaw<0) → 右轮(内侧)减速，左轮(外侧)加速
                    //   偏移量 = chasingYaw × (localPosX/HALF_TRACK) × DIFF_RATIO
                    double normX = Mth.clamp(localPosX / HALF_TRACK, -1.0, 1.0);
                    double diffOffset = this.chasingYaw * normX * DIFFERENTIAL_RATIO;
                    diffOffset = Mth.clamp(diffOffset, -0.3, 0.3);
                    targetRpm *= (1.0 + diffOffset);
                } else {
                    // 降级兼容：无驾驶舱时使用固定值
                    if (this.throttleForward) {
                        targetRpm = FALLBACK_DRIVE_RPM;
                    } else if (this.throttleBackward) {
                        targetRpm = -FALLBACK_DRIVE_RPM;
                    } else {
                        targetRpm = 0.0;
                    }
                    torqueGain = FALLBACK_DRIVE_TORQUE;
                }

                // 4b. 主动驱动：P 控制器追踪目标车速 v = ω × r
                if (Math.abs(targetRpm) > 0.1) {
                    double targetSpeed = targetRpm * Math.PI * 2.0 / 60.0 * rad; // m/s
                    double speedError = targetSpeed - forwardSpeed;               // m/s
                    double baseGain = torqueGain * 0.2 * dt;
                    double rawPCmd = speedError * baseGain * nm;

                    // ═══ 扭矩上限 ═══
                    // P 控制器力不能超过引擎扭矩能提供的极限：F_max = τ / r
                    // 这确保：
                    //   • 引擎 30 Nm × 5档齿比 0.7 ÷ 4轮 = 5.25 Nm/轮
                    //   • F_max = 5.25 / 0.5 = 10.5 N（再大力引擎也出不来）
                    //   • 对于重车（nm 大），P控制器容易超限 → 被截断 → 
                    //     负载因子飙升 → 转速暴跌 → 憋熄火感
                    //   • 对于轻车（nm 小），P控制器在限内 → 自由通过
                    double maxWheelForce = torqueGain / Math.max(rad, 0.01);
                    rawPCmd = Mth.clamp(rawPCmd, -maxWheelForce, maxWheelForce);

                    longForce += rawPCmd;
                    // 记录 P 控制器原始力需求（摩擦圆约束前），供引擎负载计算
                    this.pControllerDemand = rawPCmd;
                } else {
                    this.pControllerDemand = 0.0;
                }

                // 4c. 滚动阻力（轮胎物理模型）
                //
                // ╔══════════════════════════════════════════════════════════════╗
                // ║  轮胎物理滚动阻力模型：                                    ║
                // ║                                                           ║
                // ║  Crr = Crr_base                                          ║
                // ║       + Crr_deformation × (δ_tire / radius)              ║
                // ║       + 0.06 × max(0, P_nominal/P_eff - 1)²              ║
                // ║                                                           ║
                // ║  其中 δ_tire = 轮胎形变量 = F_normal / (P_eff × t_width) ║
                // ║                                                           ║
                // ║  • 负重越大 → 形变越大 → Crr 越大 → 发动机负担增加 ✅    ║
                // ║  • 胎压越高 → 形变越小 → Crr 越小 → 发动机负担减轻 ✅    ║
                // ║  • 亏气严重 → (P_nominal/P_eff - 1)² 暴增 → 阻力暴增 ✅ ║
                // ╚══════════════════════════════════════════════════════════════╝
                //
                // 计算轮胎形变和负载附加胎压
                double normalForce = springImpulse / dt; // 法向力 (N)
                if (tire != null && normalForce > 0) {
                    // 轮胎形变：法向力 / (胎压 × 胎面宽度)
                    // 胎压越高越难压扁，胎面越宽压力分布越均匀
                    tireDeflection = normalForce / (nominalPressure * DEFAULT_TREAD_WIDTH);
                    // 钳制形变不超过轮胎半径的 30%（物理合理范围）
                    tireDeflection = Mth.clamp(tireDeflection, 0, rad * 0.3);

                    // 负载引起的胎压升高：形变挤占内部容积
                    // ΔP = k × F_normal / A_contact（简化模型）
                    double contactLength = 2 * Math.sqrt(rad * rad - (rad - tireDeflection) * (rad - tireDeflection));
                    double contactArea = contactLength * DEFAULT_TREAD_WIDTH;
                    if (contactArea > 1e-10) {
                        double loadPressureGain = normalForce / contactArea * 0.15; // 15% 压力传递到胎压
                        effectivePressure = nominalPressure + loadPressureGain;
                    }
                }

                // 计算形变比和胎压比
                double deformationRatio = tireDeflection / Math.max(rad, 0.01);
                double pressureRatio = nominalPressure / Math.max(effectivePressure, 1.0);
                // 亏气附加阻力：当有效胎压低于标称胎压时急剧增加
                double underInflationPenalty = 0.06 * Math.max(0, pressureRatio - 1.0) * pressureRatio;

                // 综合滚动阻力系数
                double crrEffective = DEFAULT_CRR_BASE
                        + DEFAULT_CRR_DEFORMATION_GAIN * deformationRatio
                        + underInflationPenalty;

                // 滚动阻力 = -v × Crr_effective × m × dt（阻尼形式）
                // 06-08: 移除了 `/0.4` 向后兼容系数。crrBase 已从 0.015 调整为 0.035，
                // 滚动阻力总量与移除前基本一致，但公式含义更清晰。
                double rrForce = -forwardSpeed * crrEffective * nm * dt;
                longForce += rrForce;
                // 记录滚动阻力幅值，供引擎负载计算
                this.rollingResistanceMag = Math.abs(rrForce);

                // 4d. 二次方速度阻尼（速度平方阻力）
                //
                // ╔══════════════════════════════════════════════════════════════╗
                // ║  F_drag = -v × |v| × C_drag                               ║
                // ║                                                             ║
                // ║  物理作用：                                                ║
                // ║  • 低速时 ≈ 0（滚动阻力主导）                              ║
                // ║  • 高速时急剧增长，自然限制极速                            ║
                // ║  • 加速曲线平滑收敛到渐近线                                ║
                // ║                                                             ║
                // ║  与滚动阻力的关键区别：                                    ║
                // ║  滚动阻力 ∝ v（线性）→ 太弱，无法限制极速                 ║
                // ║  二次方阻力 ∝ v² → 随速度急剧上升，自然收敛               ║
                // ║                                                             ║
                // ║  ⚠ 这是外部环境力，叠加到 longForce 但不影响               ║
                // ║     engine load 计算——P控制器会自然增加输出               ║
                // ║     来补偿，负载因子自动反映总需求。                      ║
                // ╚══════════════════════════════════════════════════════════════╝
                double dragImpulse = -forwardSpeed * Math.abs(forwardSpeed) * DRAG_COEFFICIENT * dt;
                longForce += dragImpulse;

                // 5. 侧向力（Brush 轮胎侧偏模型）
                //
                // ╔══════════════════════════════════════════════════════════════╗
                // ║  替换了旧的纯阻尼模型：Fy = -Vy × 阻尼 × m × dt                   ║
                // ║  旧模型永远增大侧向力跟随速度 → 无法模拟失控临界点                    ║
                // ║                                                              ║
                // ║  Brush 轮胎模型（刷子模型）：                                    ║
                // ║    α = atan2(Vy, |Vx|)  ─ 侧偏角                              ║
                // ║    Fy = -μ × N × sin(K × Cα × tan|α| / (μ × N)) × sgn(α)     ║
                // ║                                                              ║
                // ║  物理行为：                                                    ║
                // ║  • α → 0°：线性区，Fy = -Cα × α（方向感清晰）                     ║
                // ║  • α ≈ 9°：峰值抓地（对 CORNERING_STIFFNESS=10）                ║
                // ║  • α > 峰值：力下降 → 轮胎突破极限 → 甩尾/漂移 🔥                  ║
                // ║  • + 载荷转移联动：加速时后轮增载 → 后轮峰值更高 →                  ║
                // ║    后轮抓地 > 前轮 → 转向不足（推头）✅                           ║
                // ║                                                              ║
                // ║  ⚠ 防抖措施：                                                  ║
                // ║  • 侧偏角钳制 ±45° → 防止 tan(α) 爆炸                            ║
                // ║  • 纵向速度 < 1.0 m/s → 纯阻尼（防自旋）                          ║
                // ║  • 1.0~2.0 m/s → Brush 与阻尼线性混合（平滑过渡）                 ║
                // ╚══════════════════════════════════════════════════════════════╝
                double forwardSpeedAbs = Math.abs(forwardSpeed);
                double totalSpeed = Math.sqrt(forwardSpeed * forwardSpeed + lateralSpeed * lateralSpeed);
                if (totalSpeed > 1.0) {
                    // ═══ Brush 轮胎模型 ═══
                    double slipAngle = Math.atan2(lateralSpeed, forwardSpeedAbs);
                    // 钳制侧偏角 ±45°，防止 tan(α) 爆炸导致力震荡
                    slipAngle = Mth.clamp(slipAngle, Math.toRadians(-45.0), Math.toRadians(45.0));
                    double absSlip = Math.abs(slipAngle);

                    // 法向力 (N) = 摩擦基数 / dt（含载荷转移的调整值）
                    double latNormalForce = frictionBasis / dt;
                    // 侧偏刚度：Cα = CORNERING_STIFFNESS × μ × N
                    double peakForce = mu * latNormalForce; // 侧向峰值力 (N)
                    double corneringStiffness = CORNERING_STIFFNESS * peakForce;

                    // Brush 模型输入：t = Cα × tan(α) / (μ × N)
                    double input = corneringStiffness * Math.tan(absSlip) / Math.max(peakForce, 1.0);
                    input = Mth.clamp(input, 0.0, Math.PI * 0.85); // 限制到略超 π/2 保留后峰值区

                    double fyRatio;
                    if (input <= Math.PI / 2.0) {
                        fyRatio = Math.sin(input);             // 峰值前：经典 Brush
                    } else {
                        // 峰值后：线性下降模拟抓地崩溃
                        fyRatio = 1.0 - (input - Math.PI / 2.0) * 0.25;
                        fyRatio = Math.max(fyRatio, 0.1);       // 残余抓地 ≥ 10%（原25%，降低以减少低速抖动）
                    }

                    double fyNewtons = peakForce * fyRatio;
                    // 转换为冲量（与 longForce、frictionBudget 同单位）
                    double brushImpulse = -Math.signum(slipAngle) * fyNewtons * dt;

                    // 平滑过渡：当 totalSpeed 在 1.0~2.0 m/s 时 Brush 与阻尼混合
                    double dampingImpulse = -lateralSpeed * SIDE_SLIP_DAMPING * nm * dt;
                    double blend = Mth.clamp((totalSpeed - 1.0) / 1.0, 0.0, 1.0);
                    latForce = Mth.lerp(1.0 - blend, dampingImpulse, brushImpulse);
                } else {
                    // 低速：纯阻尼 → 防止原地自旋和微小扰动放大
                    latForce = -lateralSpeed * SIDE_SLIP_DAMPING * nm * dt;
                }
            }

            // 6. 摩擦圆约束：√(long² + lat²) ≤ μ × N_spring
            //    当需求超过预算时按比例缩减两个分量 —— 轮子进入滑移/空转状态
            double totalDemand = Math.sqrt(longForce * longForce + latForce * latForce);
            if (totalDemand > frictionBudget && totalDemand > 1e-10) {
                double scale = frictionBudget / totalDemand;
                longForce *= scale;
                latForce *= scale;
                // 饱和 —— 驱动力无法达到目标速度，等价于轮子空转/侧滑
            }

            // 记录力需求/预算比（用于覆盖层显示，不参与物理）。
            //
            //  ⚠ 不使用 frictionBudget 做分母！因为 frictionBasis 被
            //     MIN_IMPULSE_MULTIPLIER=500 放大了 ~50 倍，导致显示值
            //     永远偏低（如 17%），无法反映真实的打滑/动力盈余程度。
            //
            //  改用「自然摩擦预算」—— 仅基于弹簧静载冲量 + 载荷转移 + 轻量下限
            //  （~2 倍重量），不加 500× 安全放大器：
            //     displayBudget = μ × max(adjustedSpringImpulse, nm × dt × 20.0)
            //
            //  这样当 P 控制器力需求超过自然抓地力时，显示值 >100%，
            //  直观反映"轮子在空转/引擎力气大于轮胎抓地"。
            //  加入 adjustedSpringImpulse 后，加速时前轮抓地↓→摩擦需求↑→提示推头，
            //  刹车时前轮抓地↑→摩擦需求↓→提示制动稳定。
            double displayBasis = Math.max(adjustedSpringImpulse, nm * dt * 20.0);
            double displayBudget = mu * displayBasis;
            this.frictionDemandRatio = totalDemand > 1e-10 ? totalDemand / Math.max(displayBudget, 1.0) : 0.0;

            // 节流同步显示值（仅当值有明显变化时，最多每 5 tick 一次）
            if (--this.frictionSyncCooldown <= 0
                    && Math.abs(this.frictionDemandRatio - this.lastSyncedFrictionRatio) > 0.02) {
                this.lastSyncedFrictionRatio = this.frictionDemandRatio;
                this.frictionSyncCooldown = 5;
                setChanged();
                sendData();
            }

            // 7. 应用水平力
            forceVec.fma(longForce, fwdD);
            forceVec.fma(latForce, sideD);

            // 8. 爆胎检测
            //
            // ╔══════════════════════════════════════════════════════════════════╗
            // ║  爆胎条件：                                                    ║
            // ║    effectivePressure × atmFactor > maxPressure                ║
            // ║                                                               ║
            // ║  其中 atmFactor = P_atm / P_seaLevel                          ║
            // ║  大气压随海拔升高而指数衰减：                                 ║
            // ║    P_atm = 101325 × exp(-(y - 63) / 8400)                    ║
            // ║                                                               ║
            // ║  物理直觉：                                                   ║
            // ║  • 胎压越足 → 越容易在颠簸中达到极限 → 爆胎 ✅               ║
            // ║  • 负重越大 → 胎压升高 → 爆胎风险增大 ✅                     ║
            // ║  • 海拔越高 → 大气压越低 → 爆胎更容易 ✅                     ║
            // ║  • 胎体刚度越大 → maxPressure 越高 → 耐艹 ✅                ║
            // ╚══════════════════════════════════════════════════════════════════╝
            if (tire != null && !heldItem.isEmpty()) {
                // 海拔气压：海平面 y=63，标高 8400m
                double altitude = bp.getY() - 63.0;
                double atmPressure = 101325.0 * Math.exp(-altitude / 8400.0);
                double atmFactor = atmPressure / 101325.0;

                // 爆胎阈值 = 最大安全胎压 + 大气压的辅助支撑
                double burstThreshold = DEFAULT_MAX_PRESSURE * (0.7 + 0.3 * atmFactor);

                if (effectivePressure > burstThreshold) {
                    // 爆胎！清空轮子，触发服务端同步
                    this.heldItem = ItemStack.EMPTY;
                    setChanged();
                    sendData();
                    IACP.LOGGER.info("[TireBurst] 轮胎爆裂 at {} (P_eff={}, 阈值={}, 海拔={})",
                            bp, String.format("%.0f", effectivePressure),
                            String.format("%.0f", burstThreshold), String.format("%.0f", altitude));
                }
            }

            // 从当前物理速度推算本轮实际轮端 RPM（供发动机-轮速耦合）
            // forwardSpeed 为局部坐标系纵向速度 (m/s)
            // wheelRpm = forwardSpeed × 60 / (2π × radius)
            this.currentWheelRpm = forwardSpeed * 60.0 / (2.0 * Math.PI * Math.max(rad, 0.01));
        }

        this.forceTotal.applyImpulseAtPoint(sl, this.forcePos, this.forceVec);
        handle.applyForcesAndReset(this.forceTotal);
    }

    // ===== 客户端 tick（含转向与轮子旋转） =====

    @Override
    public void tick() {
        // === 油门状态由 CockpitBE.tick() 直接扫描 throttleForward/Backward 字段获取 ===
        // 不再使用共享 Map，消除 putIfAbsent 导致的状态覆盖时序问题。
        SubLevel sl = Sable.HELPER.getContaining(this);

        TireLike tire = this.heldItem.get(OffroadDataComponents.TIRE);

        // === 转向更新（服务端 + 客户端均执行） ===
        this.lastChasingYaw = this.chasingYaw;

        // 计算目标转向角：
        // applyControlInput() 通过 setTargetSteeringYaw() 写入 targetSteeringYaw
        // 当无转向输入时，targetSteeringYaw = 0，转向自动回中
        double target = this.targetSteeringYaw;
        if (target == 0.0 && !AUTO_CENTER) {
            target = this.chasingYaw; // 保持当前角度
        }

        // 匀速转向：每 tick 最多转动 STEERING_SPEED 度
        double yawDiff = target - this.chasingYaw;
        double maxStep = Math.toRadians(STEERING_SPEED);
        if (Math.abs(yawDiff) <= maxStep) {
            this.chasingYaw = target;
        } else {
            this.chasingYaw += Math.signum(yawDiff) * maxStep;
        }

        // 服务端：转向已完成，其余视觉更新仅在客户端执行
        if (!this.level.isClientSide) return;

        // === 客户端视觉更新 ===

        if (tire == null) {
            this.angle = 0; this.lastAngle = 0;
            this.lastExt = this.extension;
            this.extension = Mth.lerp(0.6, this.extension, NO_WHEEL_EXT);
            return;
        }

        Direction f = getBlockState().getValue(SuspensionTestBlock.HORIZONTAL_FACING);
        float rad = tire.radius();
        // sl 已在 tick() 开头定义，此处复用
        this.lastExt = this.extension;
        this.extension = Mth.lerp(0.7, this.extension, compMaxExt(rad));

        // 轮子旋转：被动摩擦滚动 + 主动 RPM（从动力系统读取）
        double visualRpm = getVisualRpm();
        if (sl == null || this.lifted) {
            // 悬空时：仅主动 RPM 驱动旋转
            // 取负以匹配渲染器的符号约定（renderer 使用 -angle，正 RPM 应产生正向视觉旋转）
            double rpmAV = -visualRpm * Math.PI * 2.0 / 60.0 / 20.0;
            this.angVel = rpmAV;
            this.lastAngle = this.angle;
            this.angle += this.angVel;
            return;
        }

        Vector3d vel = Sable.HELPER.getVelocity(this.level, JOMLConversion.atCenterOf(this.getBlockPos().relative(f)));
        Vector3d lv = sl.logicalPose().transformNormalInverse(vel).div(20.0);
        Vector3dc fwdD = rotPerp(f.getAxis());

        double trans = lv.dot(fwdD);
        double circ = Math.PI * rad * 2.0;
        // 被动摩擦滚动产生的角增量（地面相对速度 / 周长 × 2π）
        double frictionDelta = -trans / circ * Math.PI * 2.0;

        // 主动 RPM 产生的角增量（从动力系统读取）
        // 取负以匹配渲染器的符号约定（renderer 使用 -angle，正 RPM 应产生正向视觉旋转）
        double rpmDelta = -visualRpm * Math.PI * 2.0 / 60.0 / 20.0;

        // 视觉轮子旋转：
        //   - 刹车时：轮子锁死，不旋转（combinedDelta=0），车辆靠滑动摩擦减速
        //   - 有主动驱动且未刹车：显示引擎转速（rpmDelta），表现加速中的正常滑转
        //   - 无主动驱动（松油门/滑行）：纯地面摩擦滚动（frictionDelta）
        double combinedDelta;
        if (this.braking) {
            combinedDelta = 0.0; // 手刹锁轮：轮子不转，车辆滑行
        } else if (Math.abs(visualRpm) > 0.1) {
            combinedDelta = rpmDelta; // 引擎驱动时轮子按引擎转速旋转
        } else {
            combinedDelta = frictionDelta; // 被动滑行时按地面速度滚动
        }

        this.lastAngle = this.angle;
        this.angle += combinedDelta;
        this.angVel = combinedDelta;
    }

    // ===== 工具 =====

    private static double fudge(double v) { return v < 1 ? 0.1 + 0.9 * v : v; }

    private double compMaxExt(float rad) {
        SubLevel sl = Sable.HELPER.getContaining(this);
        if (sl == null) return MAX_EXT;
        Direction f = getBlockState().getValue(SuspensionTestBlock.HORIZONTAL_FACING);
        var r = rayTerrain(rotPerp(f.getAxis()), sl.logicalPose());
        double u = r.maxExtension - rad;
        this.lifted = u > MAX_EXT;
        this.touchFriction = r.minInteractingBlock() == null ? 1.0
                : fudge(PhysicsBlockPropertyHelper.getFriction(this.level.getBlockState(r.minInteractingBlock())));
        return Mth.clamp(u, -0.45, MAX_EXT);
    }

    private record TerrainCastResult(double maxExtension, Direction normal,
                                      @Nullable SubLevel subLevel, @Nullable BlockPos minInteractingBlock) {}

    private TerrainCastResult rayTerrain(Vector3dc nd, Pose3dc pose) {
        Direction f = getBlockState().getValue(SuspensionTestBlock.HORIZONTAL_FACING);
        Vec3 c = this.getBlockPos().relative(f).getCenter();
        double minE = 5.0; Direction minN = Direction.UP; SubLevel minSL = null; BlockPos minBP = null;

        for (int i = -1; i <= 1; i++) {
            Vec3 o = c.add(JOMLConversion.toMojang(nd).scale(i));
            ClipContext ctx = new ClipContext(o, o.subtract(0, 5, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            ((ClipContextExtension) ctx).sable$setIgnoredSubLevel(Sable.HELPER.getContaining(this));
            BlockHitResult hit = this.level.clip(ctx);
            if (hit.getType() == HitResult.Type.MISS) continue;

            SubLevel hsl = Sable.HELPER.getContaining(this.level, hit.getLocation());
            Vec3 lh = pose.transformPositionInverse(
                    hsl == null ? hit.getLocation() : hsl.logicalPose().transformPosition(hit.getLocation()));
            if (lh.y > c.y || o.distanceTo(lh) < 0.05) continue;
            double d = c.y - lh.y;
            if (d <= 1e-5) continue;

            Vector3d hn = new Vector3d(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
            if (hsl != null) hsl.logicalPose().transformNormal(hn);
            pose.transformNormalInverse(hn);
            if (hn.dot(0, 1, 0) < 0.5) continue;
            if (d < minE) { minE = d; minN = hit.getDirection(); minSL = hsl; minBP = hit.getBlockPos(); }
        }
        return new TerrainCastResult(minE, minN, minSL, minBP);
    }

    private @NotNull Vector3dc rotAxis(Direction.Axis a) {
        Vec3i d = Direction.get(Direction.AxisDirection.POSITIVE, a).getNormal();
        Vector3d n = new Vector3d(d.getX(), d.getY(), d.getZ());
        n.rotateY(this.chasingYaw);
        return n;
    }

    private @NotNull Vector3dc rotPerp(Direction.Axis a) {
        Vector3d n = a == Direction.Axis.X ? new Vector3d(0, 0, 1) : new Vector3d(1, 0, 0);
        n.rotateY(this.chasingYaw);
        return n;
    }

    // ===== 渲染插值 =====

    /** @return 当前转向角（弧度），已包含 STEERING_SPEED 匀速过渡 */
    /** @return 轮子是否离地（悬挂完全伸展，无地面接触） */
    public boolean isLifted() { return lifted; }

    public double getChasingYaw() { return chasingYaw; }
    /** @return 当前目标转向角（弧度），供外部系统（WASD 控制）读写 */
    public double getTargetSteeringYaw() { return targetSteeringYaw; }
    public double getLerpedYaw(double pt) { return Mth.lerp(pt, this.lastChasingYaw, this.chasingYaw); }
    public float getLerpedAngle(float pt) { return (float) Mth.lerp(pt, this.lastAngle, this.angle); }
    public double getLerpedExtension(float pt) { return Mth.lerp(pt, this.lastExt, this.extension); }

    // ====================================================================
    //  动力系统辅助方法
    // ====================================================================

    /**
     * 获取用于客户端视觉轮子旋转的 RPM 值。
     * 优先从座舱动力系统读取，降级回退到油门状态决定的固定值。
     */
    private double getVisualRpm() {
        SubLevel sl = Sable.HELPER.getContaining(this);
        if (sl != null) {
            CockpitBlockEntity cockpit = findCockpitInSubLevel(sl);
            if (cockpit != null) {
                int totalWheels = countSuspensionBlocksInSubLevel(sl);
                var output = cockpit.getWheelOutput(totalWheels);
                return output.wheelRpm();
            }
        }
        // 降级：无驾驶舱时根据油门状态
        if (this.throttleForward) return FALLBACK_DRIVE_RPM;
        if (this.throttleBackward) return -FALLBACK_DRIVE_RPM;
        return 0.0;
    }

    /**
     * 在当前 SubLevel 中查找驾驶舱的 BlockEntity。
     * 遍历 SubLevel 的所有已加载 chunk，寻找 CockpitBlock。
     */
    @Nullable
    private CockpitBlockEntity findCockpitInSubLevel(SubLevel sl) {
        // 缓存命中且 SubLevel 仍有效（未移除）时直接返回
        if (this.cachedCockpit != null) {
            if (!this.cachedCockpit.isRemoved()) {
                return this.cachedCockpit;
            }
            // 驾驶舱已被移除 → 失效缓存
            this.cachedCockpit = null;
        }

        SubLevelScanner.forEachBlock(sl, level, (worldPos, state, be) -> {
            if (this.cachedCockpit != null) return; // 已找到，跳过
            if (state.getBlock() instanceof CockpitBlock && be instanceof CockpitBlockEntity cockpit) {
                this.cachedCockpit = cockpit;
            }
        });

        return this.cachedCockpit;
    }

    /**
     * 统计当前 SubLevel 内悬挂测试方块的总数（用于动力分配扭矩均摊）。
     * 使用 {@link SubLevelScanner} 统一遍历。
     */
    private int countSuspensionBlocksInSubLevel(SubLevel sl) {
        // 缓存命中时直接返回（0 表示未缓存）
        if (this.cachedWheelCount > 0) {
            return this.cachedWheelCount;
        }

        int[] count = {0};
        SubLevelScanner.forEachBlockState(sl, level, (worldPos, state) -> {
            if (state.getBlock() instanceof SuspensionTestBlock) {
                count[0]++;
            }
        });
        this.cachedWheelCount = Math.max(count[0], 1); // 至少返回 1，避免除零
        return this.cachedWheelCount;
    }
}
