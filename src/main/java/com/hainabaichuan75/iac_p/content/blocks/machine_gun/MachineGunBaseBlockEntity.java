package com.hainabaichuan75.iac_p.content.blocks.machine_gun;

import com.hainabaichuan75.iac_p.Config;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.AffiliationHelper;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRegistry;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRole;
import com.hainabaichuan75.iac_p.affiliation.AffiliationTag;

import com.hainabaichuan75.iac_p.index.ModBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.math.AngleHelper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.free.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.generic.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.rotary.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import com.hainabaichuan75.iac_p.network.packets.AnchorDataS2CPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * MachineGunBaseBlockEntity —— 机枪底座 BE。
 * <p>
 * 底座放置时通过 {@link MachineGunBaseBlock#setPlacedBy} 自动装配。 右键（空手）可切换拆卸/重新装配。
 * <p>
 * 装配流程：在底座附近生成砂轮 SubLevel（方向机/水平旋转）和 末地烛 SubLevel（高低机/俯仰），通过
 * RotaryConstraint（方向机） 和 GenericConstraint（高低机，ANGULAR_X 自由）约束连接。
 */
public class MachineGunBaseBlockEntity extends KineticBlockEntity implements com.hainabaichuan75.iac_p.affiliation.ComponentHost {

    // ==================================================================
    //  静态注册表：砂轮 SubLevel UUID → 机枪底座位置（供网络包查找）
    // ==================================================================
    private static final java.util.Map<java.util.UUID, BlockPos> GRINDSTONE_OWNER_MAP = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, BlockPos> ROD_OWNER_MAP = new java.util.HashMap<>();

    /**
     * 客户端锚点缓存：砂轮 SubLevel UUID → [anchorX, anchorY, anchorZ] 在 read()
     * 收到客户端同步数据时更新，供配置界面使用
     */
    private static final java.util.Map<java.util.UUID, double[]> GRINDSTONE_ANCHOR_MAP = new java.util.HashMap<>();

    /**
     * 客户端线条渲染缓存：SubLevel UUID → [ox,oy,oz, xx,xy,xz, yx,yy,yz, zx,zy,zz]
     * 世界坐标，由服务端在 write() 中计算，供 AxisLineRenderer 直接使用
     */
    private static final java.util.Map<java.util.UUID, double[]> GRINDSTONE_LINE_CACHE = new java.util.HashMap<>();

    /**
     * 客户端静态缓存：包含地毯的 SubLevel UUID → 该 SubLevel 上所有地毯的局部 BlockPos 列表
     * 支持同一个物理结构上放置多个机枪地毯，每个地毯独立渲染三色焦点标记 在 read() 客户端同步时通过
     * Sable.HELPER.getContaining(this) 获取
     */
    private static final java.util.Map<java.util.UUID, java.util.List<BlockPos>> CARPET_LOCAL_POS_MAP = new java.util.HashMap<>();

    /**
     * 获取地毯位置缓存（客户端）
     */
    public static java.util.Map<java.util.UUID, java.util.List<BlockPos>> getCarpetLocalPosMap() {
        return CARPET_LOCAL_POS_MAP;
    }

    /**
     * 获取客户端的锚点数据（供配置界面使用）
     */
    public static java.util.Map<java.util.UUID, double[]> getAnchorMap() {
        return GRINDSTONE_ANCHOR_MAP;
    }

    /**
     * 获取客户端的线条缓存（供渲染器使用）
     */
    public static java.util.Map<java.util.UUID, double[]> getLineCache() {
        return GRINDSTONE_LINE_CACHE;
    }

    /**
     * 根据砂轮 SubLevel UUID 查找拥有它的底座位置
     */
    @Nullable
    public static BlockPos findOwnerByGrindstoneUUID(UUID uuid) {
        return GRINDSTONE_OWNER_MAP.get(uuid);
    }

    /**
     * 根据末地烛 SubLevel UUID 查找拥有它的底座位置
     */
    @Nullable
    public static BlockPos findOwnerByRodUUID(UUID uuid) {
        return ROD_OWNER_MAP.get(uuid);
    }

    // ==================================================================
    //  运行时状态
    // ==================================================================
    /**
     * 是否已装配
     */
    private boolean assembled = false;

    /**
     * 机枪组 UUID（共享耐久池的组标识）。
     * <p>
     * 在 {@link #assemble()} 中生成，持久化到 NBT。 同一机枪的底座/砂轮/末地烛共享同一 groupId。
     */
    @Nullable
    private UUID groupId;

    /**
     * 砂轮 SubLevel 的 UUID
     */
    @Nullable
    private UUID grindstoneSubLevelId;

    /**
     * 末地烛 SubLevel 的 UUID
     */
    @Nullable
    private UUID lightningRodSubLevelId;

    /**
     * 炮管(末地烛)↔砂轮 俯仰约束句柄（RotaryConstraint，局部 X 轴旋转 = 高低机）
     */
    @Nullable
    private PhysicsConstraintHandle barrelPitchHandle;

    /**
     * 车体 SubLevel 的 UUID（用于瞄准时获取车体姿态，null = 放在主世界）
     */
    @Nullable
    private UUID vehicleSubLevelId;

    /**
     * (已移除) 末地烛↔载具 不再直接约束——物理引擎通过约束链自行处理
     */
    /**
     * 末地烛↔载具 FreeConstraint：仅用于禁用碰撞（防止俯仰时被车体卡住）
     */
    @Nullable
    private PhysicsConstraintHandle rodVehicleFreeHandle;
    /**
     * 砂轮↔载具 旋转轴承约束句柄（RotaryConstraint = 铰链，保留一个旋转自由度）
     */
    @Nullable
    private PhysicsConstraintHandle swivelBearingHandle;

    /**
     * 锚点 A 在砂轮 SubLevel 局部空间中的坐标（默认零点 = 砂轮方块中心）
     */
    private double anchorX = 0.0;
    private double anchorY = 0.0;
    private double anchorZ = 0.0;

    /**
     * 延迟重建约束的 tick 倒计时。
     * <p>
     * 在 {@link #read(CompoundTag, HolderLookup.Provider, boolean)} 中设置为正数， 在
     * {@link #tick()} 中递减，到 0 时调用 {@link #reestablishConstraints()}。 避免在 chunk
     * 加载时 SubLevel 容器未完全就绪就尝试重建约束。
     * <p>
     * 值 = -1 表示无待处理的重建任务。
     */
    private int deferredRebuildTicks = -1;

    /**
     * 约束重建已连续失败的次数。成功重建时重置为 0。
     */
    private int rebuildRetryCount = 0;

    /**
     * 约束重建最大重试次数。超过此值后自动拆卸机枪，防止无限重试。
     * <p>
     * 假设每次重试间隔 2 秒，10 次 ≈ 20 秒。 如果 20 秒后仍无法重建，说明 SubLevel 数据可能已损坏，自动拆卸比无限等待更合理。
     */
    private static final int MAX_REBUILD_RETRIES = 10;

    // ====== 约束锚点偏移常量（可在此调整机枪铰链位置） ======
    private static final double ANCHOR_ROD_X = 0.0;       // 末地烛端 X（炮管约束点）
    private static final double ANCHOR_ROD_Y = 0.0;       // 末地烛端 Y（0=中心）
    private static final double ANCHOR_ROD_Z = -0.5;      // 末地烛端 Z（你在配置节调好的 −0.5）
    private static final double ANCHOR_GS_ROD_X = 0.0;    // 砂轮端(末地烛侧) X
    private static final double ANCHOR_GS_ROD_Y = 0.1;    // 砂轮端(末地烛侧) Y（你在配置节调好的 +0.1）
    private static final double ANCHOR_GS_ROD_Z = 0.0;    // 砂轮端(末地烛侧) Z
    private static final double ANCHOR_GS_SWIVEL_X = 0.0; // 砂轮端(方向机) X
    private static final double ANCHOR_GS_SWIVEL_Y = 0.0; // 砂轮端(方向机) Y
    private static final double ANCHOR_GS_SWIVEL_Z = 0.0; // 砂轮端(方向机) Z
    private static final double ANCHOR_VEHICLE_X = 0.0;   // 载具(地毯)端 X
    private static final double ANCHOR_VEHICLE_Y = 0.0;   // 载具(地毯)端 Y
    private static final double ANCHOR_VEHICLE_Z = 0.0;   // 载具(地毯)端 Z

    // ==================================================================
    //  齿轮驱动：位置模式 PD 伺服
    //  参考 SwivelBearingBlockEntity.updateServoCoefficients()
    //  每 tick 用 position-mode setMotor 保持/驱动目标角度
    //  目标角度由 TurretAimController 按比例增量调节
    // ==================================================================
    /**
     * 当前目标偏航角度（度），由 AimController 增量调节
     */
    private double targetAngleDegrees = 0;

    /**
     * 上一 tick 的目标角度（度），用于 partialTick 插值
     */
    private double lastTargetAngleDegrees = 0;

    //这玩意修改太大会导致不稳定
    /**
     * PD 伺服刚度（P 增益）
     */
    private static final double SERVO_STIFFNESS = 5000.0;

    /**
     * PD 伺服阻尼（D 增益）。从 16 提升到 100 以强力抑制过冲。
     * <p>
     * 代价：运动时略感粘滞，但配合限速轨迹规划（4.5°/tick） 应该影响不大。如果觉得太肉可以调回 50 左右。
     */
    private static final double SERVO_DAMPING = 20.0;

    /**
     * 方向机最大转速（度/游戏刻），从 Config 的度/秒值自动换算。
     * <p>
     * 限速轨迹规划：每 tick 最多移动此角度，最后 1 tick 精确到位、不超调。
     */
    private static double yawSpeedPerTick() {
        return Config.MACHINE_GUN_YAW_SPEED_DPS.get() / 20.0;
    }

    // ====== 高低机（Pitch）位置模式 PD 伺服 ======
    /**
     * 当前目标俯仰角度（度），正直向上
     */
    private double targetPitchAngleDegrees = 0;

    /**
     * 上一 tick 的目标俯仰角度（度）
     */
    private double lastTargetPitchAngleDegrees = 0;

    /**
     * 俯仰 PD 刚度。从 6000 提升到 600000（×1000，与方向机一致），超强瞬停。
     */
    private static final double PITCH_SERVO_STIFFNESS = 5000.0;

    /**
     * 俯仰 PD 阻尼。对应提升到 1200 以抑制过冲。
     */
    private static final double PITCH_SERVO_DAMPING = 20.0;

    /**
     * 高低机最大转速（度/游戏刻），从 Config 的度/秒值自动换算。
     */
    private static double pitchSpeedPerTick() {
        return Config.MACHINE_GUN_PITCH_SPEED_DPS.get() / 20.0;
    }

    // ==================================================================
    //  ComponentHost 实现
    // ==================================================================
    @Override
    public com.hainabaichuan75.iac_p.affiliation.ComponentRole getComponentRole() {
        return com.hainabaichuan75.iac_p.affiliation.ComponentRole.MACHINE_GUN_BASE;
    }

    @Override
    public void onChunkUnloaded() {
        cleanupStaticMaps();
        com.hainabaichuan75.iac_p.affiliation.ComponentHost.unregisterComponent(this);
        super.onChunkUnloaded();
    }

    // ==================================================================
    public MachineGunBaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntityTypes.MACHINE_GUN_BASE.get(), pos, state);
    }

    /**
     * BE 加载完成回调。
     * <p>
     * 在 chunk 加载完成、BE 被添加到世界时调用。此时 SubLevel 容器通常已就绪，
     * 是重建约束的合适时机。如果在此处重建失败（SubLevel 尚未加载）， tick() 中的
     * {@link #deferredRebuildTicks} 机制会持续重试。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        // 注册到 ComponentRegistry
        com.hainabaichuan75.iac_p.affiliation.ComponentHost.registerComponent(this, getComponentRole());
        if (this.level != null && !this.level.isClientSide && this.assembled
                && this.grindstoneSubLevelId != null) {
            if (this.swivelBearingHandle == null) {
                IACP.LOGGER.info("[MachineGunBase] onLoad() 触发约束重建 @ {}", this.worldPosition);
                reestablishConstraints();
            }
            // 检查重建结果：无论上一行是否执行了重建，都检查当前句柄状态
            boolean yawOk = this.swivelBearingHandle != null && this.swivelBearingHandle.isValid();
            boolean pitchOk = this.barrelPitchHandle != null && this.barrelPitchHandle.isValid();
            if (yawOk && pitchOk) {
                // onLoad() 已成功重建，清除 read() 可能设置的延迟重建
                this.deferredRebuildTicks = -1;
                IACP.LOGGER.debug("[MachineGunBase] onLoad() 约束已就绪 @ {}", this.worldPosition);
            } else {
                // 重建失败（SubLevel 容器未就绪或 SubLevel 尚未加载），
                // 启动 deferredRebuildTicks 持续重试直到成功
                this.deferredRebuildTicks = 10; // 0.5 秒后首次重试
                IACP.LOGGER.info("[MachineGunBase] onLoad() 重建未完全成功 (方向机={}, 高低机={})，启动延迟重试 @ {}",
                        yawOk, pitchOk, this.worldPosition);
            }
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 目前无特殊行为
    }

    // ==================================================================
    //  KineticBlockEntity 重写：位置模式 PD 伺服
    //  参考 SwivelBearingBlockEntity — 每 tick position-mode setMotor
    // ==================================================================
    @Override
    public void tick() {
        super.tick(); // KineticBlockEntity.tick() → SmartBlockEntity.tick()

        if (level == null || level.isClientSide) {
            return;
        }

        // ====== 延迟约束重建（断线重连修复） ======
        // 在 read() 或 onLoad() 中设置，经过若干 tick 后执行，
        // 确保 SubLevel 容器和 SubLevel 已完全加载。
        // 持续重试直到所有约束句柄都有效，或达到最大重试次数。
        // 如果达到上限仍未成功，自动拆卸机枪并输出错误日志。
        if (deferredRebuildTicks > 0) {
            deferredRebuildTicks--;
            if (deferredRebuildTicks == 0) {
                IACP.LOGGER.info("[MachineGunBase] 延迟 tick 触发约束重建 (第 {} 次) @ {}",
                        rebuildRetryCount + 1, this.worldPosition);
                reestablishConstraints();
                // 检查重建是否完全成功：如果方向机或高低机句柄仍无效，继续重试
                boolean yawOk = swivelBearingHandle != null && swivelBearingHandle.isValid();
                boolean pitchOk = barrelPitchHandle != null && barrelPitchHandle.isValid();
                if (!yawOk || !pitchOk) {
                    rebuildRetryCount++;
                    if (rebuildRetryCount >= MAX_REBUILD_RETRIES) {
                        IACP.LOGGER.error("[MachineGunBase] 约束重建重试 {} 次仍失败，自动拆卸 @ {}",
                                MAX_REBUILD_RETRIES, this.worldPosition);
                        // 自动拆卸：让玩家可以重新放置机枪来修复
                        disassemble();
                        rebuildRetryCount = 0;
                    } else {
                        deferredRebuildTicks = 40; // 2 秒后再次重试
                        IACP.LOGGER.warn("[MachineGunBase] 约束重建未完全成功 (方向机={}, 高低机={})，{}/{} 次后重试 @ {}",
                                yawOk, pitchOk, rebuildRetryCount, MAX_REBUILD_RETRIES, this.worldPosition);
                    }
                } else {
                    rebuildRetryCount = 0; // 成功，重置计数
                }
            }
        }

        // 每 tick 更新方向机 PD 伺服（即使无目标也保持位置）
        if (assembled && swivelBearingHandle != null && swivelBearingHandle.isValid()) {
            updateYawServo();
        }

        // 每 tick 更新高低机 PD 伺服（位置模式，与方向机一致）
        if (assembled && barrelPitchHandle != null && barrelPitchHandle.isValid()) {
            updatePitchServo();
        }
    }

    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
    }

    // 零应力消耗（纯被动旋转件，同 SwivelBearing）
    @Override
    public float calculateStressApplied() {
        return 0;
    }

    /**
     * 方向机位置模式 PD 伺服。
     * <p>
     * 每 tick 调用 setMotor(position mode)，与 SwivelBearing 完全相同的模式。
     * 无目标时保持当前角度（targetAngleDegrees 不变 → 位置保持）。 有目标时由 AimController 增量调节
     * targetAngleDegrees → 平滑转动。
     */
    private void updateYawServo() {
        if (!assembled || swivelBearingHandle == null || !swivelBearingHandle.isValid()) {
            return;
        }

        // 用 angleLerp 做 partialTick 插值（与 SwivelBearing 一致）
        float goal = net.createmod.catnip.math.AngleHelper.rad(
                net.createmod.catnip.math.AngleHelper.angleLerp(1.0f,
                        (float) lastTargetAngleDegrees, (float) targetAngleDegrees));

        swivelBearingHandle.setMotor(
                dev.ryanhcode.sable.api.physics.constraint.rotary.RotaryConstraintHandle.DEFAULT_AXIS,
                goal, // 目标角度（弧度）
                SERVO_STIFFNESS, // kP — 位置刚度
                SERVO_DAMPING, // kD — 速度阻尼
                false, // position mode
                0.0
        );
        swivelBearingHandle.setContactsEnabled(false);

        // 推进 lastTarget 用于下一 tick 插值
        this.lastTargetAngleDegrees = this.targetAngleDegrees;
    }

    /**
     * 设置绝对目标偏航角度（度），由 AimController 每 tick 调用。
     * <p>
     * 直接设目标角度，不限速。平滑物理旋转由 PD 伺服（{@link #updateYawServo()}）的 position-mode
     * setMotor 自然保证，无需软件层额外限速。
     */
    public void setTargetYawAbsolute(double degrees) {
        this.lastTargetAngleDegrees = this.targetAngleDegrees;
        this.targetAngleDegrees = degrees;
    }

    /**
     * 获取当前目标偏航角度，供 AimController 读取。
     */
    public double getTargetYawAngle() {
        return this.targetAngleDegrees;
    }

    // ==================================================================
    //  高低机（Pitch）位置模式 PD 伺服
    // ==================================================================
    /**
     * 高低机位置模式 PD 伺服。
     * <p>
     * 每 tick 调用 setMotor(position mode)，与方向机完全相同的模式。
     * 无目标时保持当前俯仰角度（targetPitchAngleDegrees 不变 → 位置保持）。
     */
    private void updatePitchServo() {
        if (!assembled || barrelPitchHandle == null || !barrelPitchHandle.isValid()) {
            return;
        }

        float goal = AngleHelper.rad(
                AngleHelper.angleLerp(1.0f,
                        (float) lastTargetPitchAngleDegrees, (float) targetPitchAngleDegrees));

        barrelPitchHandle.setMotor(
                ConstraintJointAxis.ANGULAR_X,
                goal,
                PITCH_SERVO_STIFFNESS,
                PITCH_SERVO_DAMPING,
                false, // position mode
                0.0
        );
        barrelPitchHandle.setContactsEnabled(false);

        this.lastTargetPitchAngleDegrees = this.targetPitchAngleDegrees;
    }

    /**
     * 设置绝对目标俯仰角度（度），由 AimController 每 tick 调用。
     * <p>
     * 与方向机相同：直接设目标角度，不限速。
     */
    public void setTargetPitchAbsolute(double degrees) {
        this.lastTargetPitchAngleDegrees = this.targetPitchAngleDegrees;
        this.targetPitchAngleDegrees = degrees;
    }

    /**
     * 获取当前目标俯仰角度（度），供 AimController 读取。
     */
    public double getTargetPitchAngle() {
        return this.targetPitchAngleDegrees;
    }

    // ==================================================================
    //  立即驱动：设目标角度 + 立即执行 setMotor（消除 1-tick 延迟）
    // ==================================================================
    /**
     * 立即驱动机枪到指定角度 —— 设目标 + 立即调用 servo 更新。
     * <p>
     * 相比先调用 {@link #setTargetYawAbsolute} / {@link #setTargetPitchAbsolute} 然后等
     * {@link #tick()} 中的 servo 更新，此方法在设目标后
     * <b>立即执行 setMotor</b>，消除了一整个 server tick（0~50ms）的等待延迟。
     * <p>
     * {@link #tick()} 中的 servo 调用不会重复执行（lastTarget == target 时 angleLerp(1.0,
     * x, x) = x，无副作用）。
     */
    public void driveImmediate(float yawDeg, float pitchDeg) {
        // 设置方向机目标
        this.lastTargetAngleDegrees = this.targetAngleDegrees;
        this.targetAngleDegrees = yawDeg;
        // 设置高低机目标
        this.lastTargetPitchAngleDegrees = this.targetPitchAngleDegrees;
        this.targetPitchAngleDegrees = pitchDeg;
        // 立即执行 servo 更新（不等待 tick()）
        if (assembled && swivelBearingHandle != null && swivelBearingHandle.isValid()) {
            updateYawServo();
        }
        if (assembled && barrelPitchHandle != null && barrelPitchHandle.isValid()) {
            updatePitchServo();
        }
    }

    // ==================================================================
    //  装配 / 拆卸
    // ==================================================================
    /**
     * 装配：在底座附近找一个空位，生成一个包含原版砂轮的物理化 SubLevel。
     */
    public void assemble() {
        if (this.assembled) {
            IACP.LOGGER.info("[MachineGunBase] assemble() 跳过：已装配 @ {}", this.worldPosition);
            return;
        }
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        IACP.LOGGER.info("[MachineGunBase] ====== 开始装配 @ {} ======", this.worldPosition);

        ServerLevel serverLevel = (ServerLevel) this.level;
        ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
        if (container == null) {
            IACP.LOGGER.error("[MachineGunBase] SubLevelContainer 为空！");
            return;
        }
        SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        if (physicsSystem == null) {
            IACP.LOGGER.error("[MachineGunBase] physicsSystem 为空！");
            return;
        }
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        if (pipeline == null) {
            IACP.LOGGER.error("[MachineGunBase] pipeline 为空！");
            return;
        }

        // 判断地毯是否在物理结构（SubLevel）上
        SubLevel containingSubLevel = Sable.HELPER.getContaining(this);
        BlockPos searchOrigin;

        if (containingSubLevel != null) {
            // 在物理结构上：将 SubLevel 局部坐标转换到主世界网格坐标
            Vector3d localCenter = new Vector3d(
                    this.worldPosition.getX() + 0.5,
                    this.worldPosition.getY() + 0.5,
                    this.worldPosition.getZ() + 0.5
            );
            containingSubLevel.logicalPose().transformPosition(localCenter);
            searchOrigin = BlockPos.containing(
                    Math.floor(localCenter.x),
                    Math.floor(localCenter.y),
                    Math.floor(localCenter.z)
            );
            // 缓存车体 SubLevel UUID，供 AimController 做局部坐标系稳定瞄准
            this.vehicleSubLevelId = containingSubLevel.getUniqueId();
            IACP.LOGGER.info("[MachineGunBase] 地毯在 SubLevel 上，主世界网格坐标 = {}，车体 UUID={}",
                    searchOrigin, this.vehicleSubLevelId);
        } else {
            // 在主世界：使用地毯本身的坐标
            searchOrigin = this.worldPosition;
            IACP.LOGGER.info("[MachineGunBase] 地毯在主世界，坐标 = {}", searchOrigin);
        }

        // ================================================================
        //  找两个不重合的空位：砂轮位置 + 末地烛位置（水平偏移 1 格）
        // ================================================================
        BlockPos spotA = findEmptySpot(serverLevel, searchOrigin);
        if (spotA == null) {
            IACP.LOGGER.error("[MachineGunBase] 找不到第一个空位（砂轮）！");
            return;
        }
        IACP.LOGGER.info("[MachineGunBase] 砂轮目标位置 = {}", spotA);

        // 炮管（末地烛）不再需要独立空位——将与砂轮在同一位置叠加，
        // 通过 RotaryConstraint（X 轴俯仰）绑定到砂轮上，形成机枪总成
        Quaterniond identity = new Quaterniond();

        // ================================================================
        //  计算砂轮生成位置与姿态
        //  - 在载具上：定位点（anchor 对齐地毯）+ 载具姿态
        //  - 在主世界：空位 + 无旋转（原有逻辑）
        //  这样砂轮生成时就在正确位置，省去二次 teleport
        // ================================================================
        Quaterniond grindstoneOrient = new Quaterniond();
        Vector3d grindstoneSpawnVec;
        if (containingSubLevel instanceof ServerSubLevel vehicleSL_pre) {
            var vPose = vehicleSL_pre.logicalPose();
            Vector3d carpetWorld = vPose.transformPosition(new Vector3d(
                    this.worldPosition.getX() + 0.5,
                    this.worldPosition.getY() + 0.5,
                    this.worldPosition.getZ() + 0.5
            ));
            grindstoneSpawnVec = new Vector3d(
                    carpetWorld.x - anchorX,
                    carpetWorld.y - anchorY,
                    carpetWorld.z - anchorZ
            );
            grindstoneOrient.set(vPose.orientation());
            IACP.LOGGER.info("[MachineGunBase] 砂轮目标: 定位点+载具姿态 pos=({},{},{})",
                    grindstoneSpawnVec.x, grindstoneSpawnVec.y, grindstoneSpawnVec.z);
        } else {
            grindstoneSpawnVec = new Vector3d(spotA.getX() + 0.5, spotA.getY() + 0.5, spotA.getZ() + 0.5);
            IACP.LOGGER.info("[MachineGunBase] 砂轮目标: 空位 pos=({},{},{})",
                    grindstoneSpawnVec.x, grindstoneSpawnVec.y, grindstoneSpawnVec.z);
        }

        // ================================================================
        //  创建砂轮 SubLevel
        // ================================================================
        ServerSubLevel grindstoneSL;
        try {
            Pose3d pose = new Pose3d();
            pose.position().set(grindstoneSpawnVec);
            pose.orientation().set(grindstoneOrient);
            grindstoneSL = (ServerSubLevel) container.allocateNewSubLevel(pose);
            initSingleBlockSubLevel(grindstoneSL, Blocks.GRINDSTONE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE,
                            net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
            pipeline.teleport(grindstoneSL, grindstoneSpawnVec, grindstoneOrient);
            grindstoneSL.updateLastPose();
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 砂轮 SubLevel 创建失败！", e);
            return;
        }
        IACP.LOGGER.info("[MachineGunBase] 砂轮 SubLevel UUID={}", grindstoneSL.getUniqueId());
        this.grindstoneSubLevelId = grindstoneSL.getUniqueId();
        GRINDSTONE_OWNER_MAP.put(grindstoneSL.getUniqueId(), this.worldPosition);
        GRINDSTONE_ANCHOR_MAP.put(grindstoneSL.getUniqueId(), new double[]{anchorX, anchorY, anchorZ});
        sendAnchorDataToClients();

        // ================================================================
        //  生成机枪组 ID（用于共享耐久池 + 精确归属注册）
        // ================================================================
        this.groupId = UUID.randomUUID();

        // 注册归属：砂轮 → 载具（使用新归属系统，精确角色 TURRET_YAW）
        if (this.vehicleSubLevelId != null) {
            AffiliationHelper.registerMachineGunPart(
                    grindstoneSL.getUniqueId(), this.vehicleSubLevelId,
                    this.groupId, AffiliationRole.MACHINE_GUN_YAW, AffiliationTag.FACTION_NEUTRAL);
        }
        // 主世界机枪（vehicleSubLevelId == null）：不注册归属。
        // 主世界机枪的开火被 WeaponFireC2SPacket.handle() 中的 isMounted 检查阻断，
        // 不需要排除集保护。不生成无意义的 null 标签污染注册表。

        // ================================================================
        //  建立旋转轴承（RotaryConstraint）：将砂轮锚定到载具上
        //  ⚠ 关键：pos1/pos2 使用 SubLevel 底层（underlying level）的**方块实际坐标**！
        //    - 砂轮方块在嵌入层 ZERO → 底层实际位置 = plot.getCenterBlock()
        //    - 地毯方块在载具嵌入层 worldPosition → 底层实际位置 = this.worldPosition
        //  约束系统将坐标通过各 SubLevel 的 pose 变换到世界空间
        //  由于砂轮已被 teleport 到定位点，两端的世界坐标相同 → 零力安全
        //  旋转轴 = 各 SubLevel 局部 Y 轴方向（两个姿态一致所以指向相同）
        //  同时禁用碰撞（砂轮穿透载具）
        // ================================================================
        if (containingSubLevel instanceof ServerSubLevel vehicleSL) {
            try {
                // 砂轮方块中心 = getCenterBlock() 的中心 + 硬编码偏移
                BlockPos gc = grindstoneSL.getPlot().getCenterBlock();
                Vector3d pos1 = new Vector3d(gc.getX() + 0.5, gc.getY() + 0.5, gc.getZ() + 0.5)
                        .add(ANCHOR_GS_SWIVEL_X, ANCHOR_GS_SWIVEL_Y, ANCHOR_GS_SWIVEL_Z);
                // 地毯方块中心 = this.worldPosition 的中心 + 硬编码偏移
                Vector3d pos2 = new Vector3d(
                        this.worldPosition.getX() + 0.5,
                        this.worldPosition.getY() + 0.5,
                        this.worldPosition.getZ() + 0.5
                ).add(ANCHOR_VEHICLE_X, ANCHOR_VEHICLE_Y, ANCHOR_VEHICLE_Z);
                RotaryConstraintConfiguration rotaryConfig = new RotaryConstraintConfiguration(
                        pos1, pos2,
                        new Vector3d(0, 1, 0), // normal1: 砂轮端旋转轴
                        new Vector3d(0, 1, 0) // normal2: 载具端旋转轴
                );
                this.swivelBearingHandle = pipeline.addConstraint(grindstoneSL, vehicleSL, rotaryConfig);
                this.swivelBearingHandle.setContactsEnabled(false);  // 禁用碰撞，砂轮穿过载具
                IACP.LOGGER.info("[MachineGunBase] 旋转轴承已建立 ✅ 位置=({}, {}, {})",
                        grindstoneSpawnVec.x, grindstoneSpawnVec.y, grindstoneSpawnVec.z);
            } catch (Exception e) {
                IACP.LOGGER.warn("[MachineGunBase] 创建旋转轴承失败，回退到纯碰撞禁用", e);
                try {
                    Vector3d blockCenter = new Vector3d(0.5, 0.5, 0.5);
                    FreeConstraintConfiguration freeConfig = new FreeConstraintConfiguration(
                            blockCenter, blockCenter, new Quaterniond());
                    this.swivelBearingHandle = pipeline.addConstraint(grindstoneSL, vehicleSL, freeConfig);
                    this.swivelBearingHandle.setContactsEnabled(false);
                    IACP.LOGGER.info("[MachineGunBase] 回退: 砂轮↔载具 碰撞已禁用");
                } catch (Exception e2) {
                    IACP.LOGGER.error("[MachineGunBase] 回退也失败", e2);
                }
            }
        }

        // ================================================================
        //  创建炮管（末地烛）SubLevel
        //  ⚠ 完全遵循与砂轮完全相同的模式：
        //    1. 同样的生成位置（grindstoneSpawnVec）+ 同样的姿态（grindstoneOrient）
        //    2. 同样的 initSingleBlockSubLevel + teleport + updateLastPose 流程
        //    3. 约束坐标用 plot.getCenterBlock() + 0.5（砂轮↔载具已验证可行）
        //    4. 物理引擎通过约束链自行处理：载具─Rotary─砂轮─Generic─末地烛
        //  方向机（Y 轴旋转）= 砂轮↔载具 RotaryConstraint
        //  高低机（X 轴俯仰）= 由 TurretAimController 通过速度控制驱动（约束全锁定）
        //  默认全锁定 = 参考 swivel_bearing 锁定状态，无目标时保持当前位置
        // ================================================================
        {
            try {
                // ⚠ 和砂轮完全相同的生成位置和姿态——两者重叠没问题，
                //   setContactsEnabled(false) 让双方碰撞体互相穿透
                Pose3d poseB = new Pose3d();
                poseB.position().set(grindstoneSpawnVec);
                poseB.orientation().set(grindstoneOrient);
                ServerSubLevel rodSL = (ServerSubLevel) container.allocateNewSubLevel(poseB);
                initSingleBlockSubLevel(rodSL, Blocks.END_ROD.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.EndRodBlock.FACING, Direction.SOUTH));
                pipeline.teleport(rodSL, grindstoneSpawnVec, grindstoneOrient);
                rodSL.updateLastPose();
                this.lightningRodSubLevelId = rodSL.getUniqueId();
                ROD_OWNER_MAP.put(rodSL.getUniqueId(), this.worldPosition);
                IACP.LOGGER.info("[MachineGunBase] 炮管(末地烛) SubLevel UUID={}", rodSL.getUniqueId());

                // 注册归属：末地烛 → 载具（使用新归属系统，精确角色 TURRET_PITCH）
                if (this.vehicleSubLevelId != null && this.groupId != null) {
                    AffiliationHelper.registerMachineGunPart(
                            rodSL.getUniqueId(), this.vehicleSubLevelId,
                            this.groupId, AffiliationRole.MACHINE_GUN_PITCH, AffiliationTag.FACTION_NEUTRAL);
                }
                // 主世界机枪（vehicleSubLevelId == null）：不注册归属。
                // 理由同上：主世界机枪的开火被 mount 检查阻断，不需要排除集。

                // ============================================================
                //  与砂轮完全相同的约束坐标模式：getCenterBlock() + 0.5
                //  GenericConstraint 全轴锁定 = 刚性连接
                //  默认锁定（参考 swivel_bearing），由 TurretAimController 速度控制驱动
                // ============================================================
                try {
                    BlockPos rc = rodSL.getPlot().getCenterBlock();
                    Vector3d pos1 = new Vector3d(rc.getX() + 0.5, rc.getY() + 0.5, rc.getZ() + 0.5)
                            .add(ANCHOR_ROD_X, ANCHOR_ROD_Y, ANCHOR_ROD_Z);
                    BlockPos gc = grindstoneSL.getPlot().getCenterBlock();
                    Vector3d pos2 = new Vector3d(gc.getX() + 0.5, gc.getY() + 0.5, gc.getZ() + 0.5)
                            .add(ANCHOR_GS_ROD_X, ANCHOR_GS_ROD_Y, ANCHOR_GS_ROD_Z);
                    IACP.LOGGER.info("[MachineGunBase] 炮管约束坐标: rod=({},{},{}) grindstone=({},{},{})",
                            pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z);

                    // ====================================================
                    //  GenericConstraint：保留 ANGULAR_X 自由（俯仰轴）
                    //  锁定：LINEAR_XYZ + ANGULAR_YZ
                    //  自由：ANGULAR_X（绕局部 X 轴俯仰）
                    //  默认"锁定"效果由 TurretAimController 的制动实现：
                    //  无目标时角速度归零 → 保持当前位置；有目标时驱动 ANGULAR_X
                    // ====================================================
                    GenericConstraintConfiguration bindConfig = new GenericConstraintConfiguration(
                            pos1, pos2,
                            new Quaterniond(), // orientation1
                            new Quaterniond(), // orientation2
                            EnumSet.of(
                                    ConstraintJointAxis.LINEAR_X,
                                    ConstraintJointAxis.LINEAR_Y,
                                    ConstraintJointAxis.LINEAR_Z,
                                    ConstraintJointAxis.ANGULAR_Y,
                                    ConstraintJointAxis.ANGULAR_Z
                            ) // ANGULAR_X 未锁定 = 可俯仰
                    );
                    this.barrelPitchHandle = pipeline.addConstraint(rodSL, grindstoneSL, bindConfig);
                    this.barrelPitchHandle.setContactsEnabled(false);
                    IACP.LOGGER.info("[MachineGunBase] 炮管↔砂轮 GenericConstraint ✅");

                    // ════════════════════════════════════════════════════════
                    //  末地烛↔载具 FreeConstraint：仅用于禁用碰撞
                    //  末地烛俯仰时会穿透载具车体，不加此约束则物理碰撞卡住炮管
                    //  无任何 DOF（FreeConstraint = 6 自由度自由），仅供碰撞禁用
                    // ════════════════════════════════════════════════════════
                    if (containingSubLevel instanceof ServerSubLevel vehicleSL2) {
                        try {
                            Vector3d rodCenter = new Vector3d(
                                    rodSL.getPlot().getCenterBlock().getX() + 0.5,
                                    rodSL.getPlot().getCenterBlock().getY() + 0.5,
                                    rodSL.getPlot().getCenterBlock().getZ() + 0.5);
                            Vector3d vehicleCenter = new Vector3d(
                                    this.worldPosition.getX() + 0.5,
                                    this.worldPosition.getY() + 0.5,
                                    this.worldPosition.getZ() + 0.5);
                            FreeConstraintConfiguration freeConfig = new FreeConstraintConfiguration(
                                    rodCenter, vehicleCenter, new Quaterniond());
                            this.rodVehicleFreeHandle = pipeline.addConstraint(rodSL, vehicleSL2, freeConfig);
                            this.rodVehicleFreeHandle.setContactsEnabled(false);
                            IACP.LOGGER.info("[MachineGunBase] 末地烛↔载具 碰撞已禁用 ✅");
                        } catch (Exception e2) {
                            IACP.LOGGER.warn("[MachineGunBase] 末地烛↔载具碰撞禁用失败", e2);
                        }
                    }
                } catch (Exception e) {
                    IACP.LOGGER.warn("[MachineGunBase] 炮管绑定失败", e);
                }
            } catch (Exception e) {
                IACP.LOGGER.error("[MachineGunBase] 炮管(末地烛) SubLevel 创建失败！仅保留砂轮", e);
                this.lightningRodSubLevelId = null;
            }
        }

        this.assembled = true;
        this.setChanged();
        this.sendData();
        IACP.LOGGER.info("[MachineGunBase] ====== 装配完成（砂轮 + 炮管(末地烛)）@ {} ======", this.worldPosition);
    }

    /**
     * 拆卸：移除所有 SubLevel（砂轮 + 末地烛）。
     */
    /**
     * 清理所有与当前机枪相关的静态映射条目。
     * <p>
     * 在 {@link #disassemble()} 和 {@link #setRemoved()} 中调用， 防止 static Map
     * 无限增长导致内存泄漏。
     */
    private void cleanupStaticMaps() {
        if (this.grindstoneSubLevelId != null) {
            GRINDSTONE_OWNER_MAP.remove(this.grindstoneSubLevelId);
            GRINDSTONE_ANCHOR_MAP.remove(this.grindstoneSubLevelId);
            GRINDSTONE_LINE_CACHE.remove(this.grindstoneSubLevelId);
        }
        if (this.lightningRodSubLevelId != null) {
            ROD_OWNER_MAP.remove(this.lightningRodSubLevelId);
        }
    }

    public void disassemble() {
        if (!this.assembled || this.level == null || this.level.isClientSide) {
            return;
        }
        IACP.LOGGER.info("[MachineGunBase] ====== 开始拆卸 @ {} ======", this.worldPosition);

        try {
            ServerLevel serverLevel = (ServerLevel) this.level;
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return;
            }

            // 先移除所有约束（必须在移除 SubLevel 之前）
            removeConstraint(this.barrelPitchHandle);
            this.barrelPitchHandle = null;
            removeConstraint(this.rodVehicleFreeHandle);
            this.rodVehicleFreeHandle = null;
            removeConstraint(this.swivelBearingHandle);
            this.swivelBearingHandle = null;

            // 整组注销（新归属系统）：自动清除组内所有成员的归属记录
            if (this.groupId != null) {
                AffiliationRegistry.unregisterGroup(this.groupId);
                this.groupId = null;
            }

            // 清理静态映射（合并到 cleanupStaticMaps）
            cleanupStaticMaps();

            // 移除砂轮
            removeSubLevelById(container, this.grindstoneSubLevelId);
            this.grindstoneSubLevelId = null;

            // 移除末地烛
            removeSubLevelById(container, this.lightningRodSubLevelId);
            this.lightningRodSubLevelId = null;
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 拆卸过程中发生异常", e);
            this.grindstoneSubLevelId = null;
            this.lightningRodSubLevelId = null;
            this.barrelPitchHandle = null;
        }

        this.vehicleSubLevelId = null;

        this.assembled = false;
        this.setChanged();
        this.sendData();
        IACP.LOGGER.info("[MachineGunBase] ====== 拆卸完成 @ {} ======", this.worldPosition);
    }

    // ==================================================================
    //  公共接口（供网络包调用）
    // ==================================================================
    /**
     * 返回砂轮 SubLevel 的 UUID
     */
    @Nullable
    public UUID getGrindstoneSubLevelId() {
        return this.grindstoneSubLevelId;
    }

    /**
     * 返回车体 SubLevel 的 UUID（用于瞄准局部坐标系稳定），null = 放在主世界
     */
    @Nullable
    public UUID getVehicleSubLevelId() {
        return this.vehicleSubLevelId;
    }

    /**
     * 返回方向机旋转轴承句柄（砂轮↔载具 RotaryConstraint），供 TurretAimController 驱动 Y 轴旋转
     */
    @Nullable
    public PhysicsConstraintHandle getSwivelBearingHandle() {
        return this.swivelBearingHandle;
    }

    /**
     * 返回高低机俯仰约束句柄（末地烛↔砂轮 GenericConstraint），供 TurretAimController 驱动 X 轴俯仰
     */
    @Nullable
    public PhysicsConstraintHandle getBarrelPitchHandle() {
        return this.barrelPitchHandle;
    }

    /**
     * 获取锚点坐标（副本）
     */
    public double[] getAnchor() {
        return new double[]{this.anchorX, this.anchorY, this.anchorZ};
    }

    /**
     * 设置锚点坐标并同步到客户端
     */
    public void setAnchor(double x, double y, double z) {
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        this.setChanged();
        this.sendData();
        sendAnchorDataToClients();
        IACP.LOGGER.info("[MachineGunBase] 锚点A已更新为 ({}, {}, {})", x, y, z);
    }

    /**
     * 将锚点数据和线条数据通过专用 S2C 包推送到客户端。
     */
    private void sendAnchorDataToClients() {
        if (this.level == null || this.level.isClientSide || this.grindstoneSubLevelId == null) {
            return;
        }
        try {
            ServerLevel serverLevel = (ServerLevel) this.level;
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return;
            }
            ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(this.grindstoneSubLevelId);
            if (sub == null || sub.isRemoved()) {
                // SubLevel 不可用，只发锚点数据（线条设为原点）
                sendAnchorOnly(serverLevel);
                return;
            }
            var pose = sub.logicalPose();
            if (pose == null) {
                sendAnchorOnly(serverLevel);
                return;
            }
            Vector3d o = pose.transformPosition(new Vector3d(anchorX, anchorY, anchorZ));
            Vector3d x = pose.transformPosition(new Vector3d(anchorX + 20, anchorY, anchorZ));
            Vector3d y = pose.transformPosition(new Vector3d(anchorX, anchorY + 20, anchorZ));
            Vector3d z = pose.transformPosition(new Vector3d(anchorX, anchorY, anchorZ + 20));

            AnchorDataS2CPacket packet = new AnchorDataS2CPacket(
                    this.grindstoneSubLevelId,
                    anchorX, anchorY, anchorZ,
                    new double[]{o.x, o.y, o.z, x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z}
            );
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                    new ChunkPos(this.worldPosition), packet);
            IACP.LOGGER.info("[MachineGunBase] 已推送锚点+线条数据到客户端");
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 推送锚点数据失败", e);
        }
    }

    /**
     * 当 SubLevel 不可用时，只发送锚点坐标，线条全零
     */
    private void sendAnchorOnly(ServerLevel serverLevel) {
        AnchorDataS2CPacket packet = new AnchorDataS2CPacket(
                this.grindstoneSubLevelId,
                anchorX, anchorY, anchorZ,
                new double[12]
        );
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                new ChunkPos(this.worldPosition), packet);
    }

    /**
     * 返回末地烛 SubLevel 的 UUID
     */
    @Nullable
    public UUID getLightningRodSubLevelId() {
        return this.lightningRodSubLevelId;
    }

    /**
     * 更改末地烛的朝向。
     */
    public void setLightningRodFacing(Direction facing) {
        if (this.lightningRodSubLevelId == null || this.level == null || this.level.isClientSide) {
            return;
        }
        try {
            ServerLevel serverLevel = (ServerLevel) this.level;
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return;
            }
            ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(this.lightningRodSubLevelId);
            if (subLevel == null || subLevel.isRemoved()) {
                return;
            }

            LevelPlot plot = subLevel.getPlot();
            if (plot == null) {
                return;
            }

            BlockState newState = Blocks.END_ROD.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.EndRodBlock.FACING, facing);
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, newState, 3);
            IACP.LOGGER.info("[MachineGunBase] 末地烛朝向已更改为 {}", facing);
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 更改末地烛朝向失败", e);
        }
    }

    /**
     * 更改砂轮的朝向。
     */
    public void setGrindstoneFacing(Direction facing) {
        if (this.grindstoneSubLevelId == null || this.level == null || this.level.isClientSide) {
            return;
        }
        try {
            ServerLevel serverLevel = (ServerLevel) this.level;
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return;
            }
            ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(this.grindstoneSubLevelId);
            if (subLevel == null || subLevel.isRemoved()) {
                return;
            }

            LevelPlot plot = subLevel.getPlot();
            if (plot == null) {
                return;
            }

            BlockState newState = Blocks.GRINDSTONE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.GrindstoneBlock.FACING, facing);
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, newState, 3);
            IACP.LOGGER.info("[MachineGunBase] 砂轮朝向已更改为 {}", facing);
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 更改砂轮朝向失败", e);
        }
    }

    // ==================================================================
    //  私有工具方法
    // ==================================================================
    /**
     * 安全移除约束
     */
    private static void removeConstraint(@Nullable PhysicsConstraintHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            if (handle.isValid()) {
                handle.remove();
            }
        } catch (Exception e) {
            IACP.LOGGER.warn("[MachineGunBase] 移除约束异常: {}", e.getMessage());
        }
    }

    /**
     * 根据 UUID 移除 SubLevel（安全，处理 null 和已移除的情况）。
     */
    private static void removeSubLevelById(ServerSubLevelContainer container, @Nullable UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(uuid);
            if (subLevel != null && !subLevel.isRemoved()) {
                container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            }
        } catch (Exception e) {
            IACP.LOGGER.warn("[MachineGunBase] 移除 SubLevel {} 异常: {}", uuid, e.getMessage());
        }
    }

    // ==================================================================
    //  工具方法
    // ==================================================================
    public boolean isAssembled() {
        return this.assembled;
    }

    /**
     * 初始化单方块 SubLevel：创建 chunk，放置方块，注册到物理引擎。
     */
    private static void initSingleBlockSubLevel(ServerSubLevel subLevel, BlockState blockState) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            IACP.LOGGER.error("[MachineGunBase] initSingleBlockSubLevel: plot 为空！blockState={}", blockState);
            return;
        }
        ChunkPos center = plot.getCenterChunk();
        IACP.LOGGER.info("[MachineGunBase] initSingleBlockSubLevel: plot.centerChunk={}", center);
        try {
            plot.newEmptyChunk(center);
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] newEmptyChunk 失败！center={}", center, e);
            return;
        }
        try {
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, blockState, 3);
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] setBlock 失败！", e);
            return;
        }
        try {
            subLevel.updateMergedMassData(0.001f);
        } catch (Exception e) {
            IACP.LOGGER.warn("[MachineGunBase] updateMergedMassData 异常（非致命）", e);
        }
        IACP.LOGGER.info("[MachineGunBase] initSingleBlockSubLevel ✅ blockState={}", blockState);
    }

    /**
     * 从起点向上搜索，找到 6 面均为空气的位置。
     */
    @Nullable
    private static BlockPos findEmptySpot(Level level, BlockPos origin) {
        for (int y = origin.getY() + 3; y <= level.getMaxBuildHeight() - 2; y++) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (isAllFacesAir(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isAllFacesAir(Level level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isAir()
                && level.getBlockState(pos.north()).isAir()
                && level.getBlockState(pos.south()).isAir()
                && level.getBlockState(pos.east()).isAir()
                && level.getBlockState(pos.west()).isAir();
    }

    // ==================================================================
    //  NBT 持久化
    // ==================================================================
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putBoolean("Assembled", this.assembled);
        if (this.grindstoneSubLevelId != null) {
            tag.putUUID("GrindstoneSubLevel", this.grindstoneSubLevelId);
        }
        if (this.lightningRodSubLevelId != null) {
            tag.putUUID("LightningRodSubLevel", this.lightningRodSubLevelId);
        }
        if (this.vehicleSubLevelId != null) {
            tag.putUUID("VehicleSubLevel", this.vehicleSubLevelId);
        }
        // 锚点坐标持久化
        // 机枪组 ID 持久化
        if (this.groupId != null) {
            tag.putUUID("TurretGroupId", this.groupId);
        }

        // 归属标签持久化到 NBT（分布式持久化，用于世界重载后重建注册表）
        // 注意：底座本身是 BlockEntity，不是 SubLevel，不注册到 AffiliationRegistry
        // 但持久化 groupId 和 vehicleId 供世界重载后 subLevel 重建归属时使用
        if (this.vehicleSubLevelId != null) {
            tag.putUUID(AffiliationHelper.TAG_VEHICLE_ID, this.vehicleSubLevelId);
            tag.putString(AffiliationHelper.TAG_ROLE, AffiliationRole.MACHINE_GUN_BASE.name());
            tag.putInt(AffiliationHelper.TAG_FACTION, AffiliationTag.FACTION_NEUTRAL);
        }

        tag.putDouble("AnchorX", this.anchorX);
        tag.putDouble("AnchorY", this.anchorY);
        tag.putDouble("AnchorZ", this.anchorZ);

        // （齿轮驱动已移除，TargetAngle 不再持久化）
        // 客户端同步：计算世界空间中的轴线端点，供渲染器直接使用
        if (clientPacket && this.grindstoneSubLevelId != null && this.level instanceof ServerLevel sl) {
            ServerSubLevelContainer c = (ServerSubLevelContainer) SubLevelContainer.getContainer(sl);
            if (c != null) {
                ServerSubLevel sub = (ServerSubLevel) c.getSubLevel(this.grindstoneSubLevelId);
                if (sub != null && !sub.isRemoved()) {
                    var pose = sub.logicalPose();
                    if (pose != null) {
                        var o = pose.transformPosition(new Vector3d(anchorX, anchorY, anchorZ));
                        var x = pose.transformPosition(new Vector3d(anchorX + 20, anchorY, anchorZ));
                        var y = pose.transformPosition(new Vector3d(anchorX, anchorY + 20, anchorZ));
                        var z = pose.transformPosition(new Vector3d(anchorX, anchorY, anchorZ + 20));
                        tag.putDouble("LOX", o.x);
                        tag.putDouble("LOY", o.y);
                        tag.putDouble("LOZ", o.z);
                        tag.putDouble("LXX", x.x);
                        tag.putDouble("LXY", x.y);
                        tag.putDouble("LXZ", x.z);
                        tag.putDouble("LYX", y.x);
                        tag.putDouble("LYY", y.y);
                        tag.putDouble("LYZ", y.z);
                        tag.putDouble("LZX", z.x);
                        tag.putDouble("LZY", z.y);
                        tag.putDouble("LZZ", z.z);
                    }
                }
            }
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.assembled = tag.getBoolean("Assembled");
        if (tag.hasUUID("GrindstoneSubLevel")) {
            this.grindstoneSubLevelId = tag.getUUID("GrindstoneSubLevel");
        } else {
            this.grindstoneSubLevelId = null;
        }
        if (tag.hasUUID("LightningRodSubLevel")) {
            this.lightningRodSubLevelId = tag.getUUID("LightningRodSubLevel");
        } else {
            this.lightningRodSubLevelId = null;
        }
        if (tag.hasUUID("VehicleSubLevel")) {
            this.vehicleSubLevelId = tag.getUUID("VehicleSubLevel");
        } else {
            this.vehicleSubLevelId = null;
        }
        // 机枪组 ID
        if (tag.hasUUID("TurretGroupId")) {
            this.groupId = tag.getUUID("TurretGroupId");
        } else {
            this.groupId = null;
        }

        // 锚点坐标
        this.anchorX = tag.getDouble("AnchorX");
        this.anchorY = tag.getDouble("AnchorY");
        this.anchorZ = tag.getDouble("AnchorZ");

        // （齿轮驱动已移除，TargetAngle 不再读取）
        // 只要有 UUID 就更新静态缓存（无论 clientPacket 与否）
        if (this.grindstoneSubLevelId != null) {
            GRINDSTONE_ANCHOR_MAP.put(this.grindstoneSubLevelId,
                    new double[]{anchorX, anchorY, anchorZ});
            if (tag.contains("LOX")) {
                GRINDSTONE_LINE_CACHE.put(this.grindstoneSubLevelId, new double[]{
                    tag.getDouble("LOX"), tag.getDouble("LOY"), tag.getDouble("LOZ"),
                    tag.getDouble("LXX"), tag.getDouble("LXY"), tag.getDouble("LXZ"),
                    tag.getDouble("LYX"), tag.getDouble("LYY"), tag.getDouble("LYZ"),
                    tag.getDouble("LZX"), tag.getDouble("LZY"), tag.getDouble("LZZ")
                });
            }
        }

        // ================================================================
        //  [断线重连修复] 服务端加载时重建约束 + 重新注册归属
        //  世界重载后，SubLevel 仍存在但约束句柄丢失；
        //  归属注册表（AffiliationRegistry）也在内存中丢失。
        //
        //  ⚠ 重要：不在此处直接调用 reestablishConstraints()，因为 read() 调用时
        //  SubLevel 容器可能尚未完全就绪（chunk 加载过程中）。
        //  改为设置 deferredRebuildTicks，由 onLoad() 或 tick() 中的延迟机制执行。
        // ================================================================
        if (!clientPacket && this.level != null && !this.level.isClientSide && this.assembled) {
            // 重新注册归属（使用新归属系统）：砂轮 + 末地烛 → 载具，含精确角色和组
            // 归属注册不依赖 SubLevel 容器，可以立即执行
            if (this.vehicleSubLevelId != null) {
                if (this.grindstoneSubLevelId != null && this.groupId != null) {
                    AffiliationHelper.registerMachineGunPart(
                            this.grindstoneSubLevelId, this.vehicleSubLevelId,
                            this.groupId, AffiliationRole.MACHINE_GUN_YAW, AffiliationTag.FACTION_NEUTRAL);
                }
                if (this.lightningRodSubLevelId != null && this.groupId != null) {
                    AffiliationHelper.registerMachineGunPart(
                            this.lightningRodSubLevelId, this.vehicleSubLevelId,
                            this.groupId, AffiliationRole.MACHINE_GUN_PITCH, AffiliationTag.FACTION_NEUTRAL);
                }
                // 从 NBT 读取的归属角色信息，用于后续重构
            }
            // 延迟重建约束：给 SubLevel 容器/SubLevel 加载留出时间
            // 如果 onLoad() 已经成功重建，此处不会覆盖
            if (this.deferredRebuildTicks < 0) {
                this.deferredRebuildTicks = 20; // 默认 1 秒后重试
                IACP.LOGGER.info("[MachineGunBase] read() 设置延迟重建约束 @ {} (deferredRebuildTicks=20)", this.worldPosition);
            }
        }

        // ================================================================
        //  更新地毯位置缓存（客户端）：用于 AxisLineRenderer 在主世界定位
        //  通过 Sable.HELPER.getContaining() 获取地毯所在的 SubLevel
        //  后续可以直接用 subLevelPose.transformPosition(localPos) 算出世界坐标
        // ================================================================
        if (clientPacket && this.level != null && this.level.isClientSide) {
            try {
                SubLevel containing = Sable.HELPER.getContaining(this);
                if (containing != null) {
                    CARPET_LOCAL_POS_MAP.computeIfAbsent(containing.getUniqueId(), k -> new java.util.ArrayList<>())
                            .add(this.worldPosition);
                    // 日志已移除（性能优化）
                }
            } catch (Exception e) {
                IACP.LOGGER.warn("[MachineGunBase] 更新地毯位置缓存失败（非致命）", e);
            }
        }
    }

    /**
     * 重建约束（世界重载/断线重连后使用）。
     * <p>
     * 从 NBT 恢复的 UUID 重新找到 SubLevel 并建立约束关系。 约束参数与 {@link #assemble()} 中完全一致。
     */
    private void reestablishConstraints() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (!this.assembled) {
            return;
        }
        if (this.grindstoneSubLevelId == null) {
            return;
        }

        IACP.LOGGER.info("[MachineGunBase] ====== 重建约束 @ {} ======", this.worldPosition);

        try {
            ServerLevel serverLevel = (ServerLevel) this.level;
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                IACP.LOGGER.warn("[MachineGunBase] 重建约束: SubLevelContainer 不可用");
                return;
            }

            ServerSubLevel grindstoneSL = (ServerSubLevel) container.getSubLevel(this.grindstoneSubLevelId);
            if (grindstoneSL == null || grindstoneSL.isRemoved()) {
                IACP.LOGGER.warn("[MachineGunBase] 重建约束: 砂轮 SubLevel 不存在或已移除");
                return;
            }

            PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
            if (pipeline == null) {
                return;
            }

            // 寻找载具 SubLevel
            ServerSubLevel vehicleSL = null;
            if (this.vehicleSubLevelId != null) {
                vehicleSL = (ServerSubLevel) container.getSubLevel(this.vehicleSubLevelId);
            }

            // ---- 1. 方向机：砂轮↔载具 RotaryConstraint ----
            if (vehicleSL != null && !vehicleSL.isRemoved() && this.swivelBearingHandle == null) {
                try {
                    BlockPos gc = grindstoneSL.getPlot().getCenterBlock();
                    Vector3d pos1 = new Vector3d(gc.getX() + 0.5, gc.getY() + 0.5, gc.getZ() + 0.5)
                            .add(ANCHOR_GS_SWIVEL_X, ANCHOR_GS_SWIVEL_Y, ANCHOR_GS_SWIVEL_Z);
                    Vector3d pos2 = new Vector3d(
                            this.worldPosition.getX() + 0.5,
                            this.worldPosition.getY() + 0.5,
                            this.worldPosition.getZ() + 0.5
                    ).add(ANCHOR_VEHICLE_X, ANCHOR_VEHICLE_Y, ANCHOR_VEHICLE_Z);
                    RotaryConstraintConfiguration rotaryConfig = new RotaryConstraintConfiguration(
                            pos1, pos2,
                            new Vector3d(0, 1, 0),
                            new Vector3d(0, 1, 0)
                    );
                    this.swivelBearingHandle = pipeline.addConstraint(grindstoneSL, vehicleSL, rotaryConfig);
                    this.swivelBearingHandle.setContactsEnabled(false);
                    IACP.LOGGER.info("[MachineGunBase] 重建 ✅ 方向机 RotaryConstraint");
                } catch (Exception e) {
                    IACP.LOGGER.warn("[MachineGunBase] 重建方向机失败", e);
                }
            }

            // ---- 2. 炮管（末地烛）SubLevel + 高低机 GenericConstraint ----
            if (this.lightningRodSubLevelId != null && this.barrelPitchHandle == null) {
                ServerSubLevel rodSL = (ServerSubLevel) container.getSubLevel(this.lightningRodSubLevelId);
                if (rodSL != null && !rodSL.isRemoved()) {
                    try {
                        BlockPos rc = rodSL.getPlot().getCenterBlock();
                        Vector3d pos1 = new Vector3d(rc.getX() + 0.5, rc.getY() + 0.5, rc.getZ() + 0.5)
                                .add(ANCHOR_ROD_X, ANCHOR_ROD_Y, ANCHOR_ROD_Z);
                        BlockPos gc2 = grindstoneSL.getPlot().getCenterBlock();
                        Vector3d pos2 = new Vector3d(gc2.getX() + 0.5, gc2.getY() + 0.5, gc2.getZ() + 0.5)
                                .add(ANCHOR_GS_ROD_X, ANCHOR_GS_ROD_Y, ANCHOR_GS_ROD_Z);
                        GenericConstraintConfiguration bindConfig = new GenericConstraintConfiguration(
                                pos1, pos2,
                                new Quaterniond(), new Quaterniond(),
                                EnumSet.of(
                                        ConstraintJointAxis.LINEAR_X,
                                        ConstraintJointAxis.LINEAR_Y,
                                        ConstraintJointAxis.LINEAR_Z,
                                        ConstraintJointAxis.ANGULAR_Y,
                                        ConstraintJointAxis.ANGULAR_Z
                                )
                        );
                        this.barrelPitchHandle = pipeline.addConstraint(rodSL, grindstoneSL, bindConfig);
                        this.barrelPitchHandle.setContactsEnabled(false);
                        IACP.LOGGER.info("[MachineGunBase] 重建 ✅ 高低机 GenericConstraint");
                    } catch (Exception e) {
                        IACP.LOGGER.warn("[MachineGunBase] 重建高低机失败", e);
                    }

                    // ---- 3. 末地烛↔载具 FreeConstraint（碰撞禁用） ----
                    if (vehicleSL != null && !vehicleSL.isRemoved() && this.rodVehicleFreeHandle == null) {
                        try {
                            Vector3d rodCenter = new Vector3d(
                                    rodSL.getPlot().getCenterBlock().getX() + 0.5,
                                    rodSL.getPlot().getCenterBlock().getY() + 0.5,
                                    rodSL.getPlot().getCenterBlock().getZ() + 0.5);
                            Vector3d vehicleCenter = new Vector3d(
                                    this.worldPosition.getX() + 0.5,
                                    this.worldPosition.getY() + 0.5,
                                    this.worldPosition.getZ() + 0.5);
                            FreeConstraintConfiguration freeConfig = new FreeConstraintConfiguration(
                                    rodCenter, vehicleCenter, new Quaterniond());
                            this.rodVehicleFreeHandle = pipeline.addConstraint(rodSL, vehicleSL, freeConfig);
                            this.rodVehicleFreeHandle.setContactsEnabled(false);
                            IACP.LOGGER.info("[MachineGunBase] 重建 ✅ 末地烛↔载具 碰撞禁用");
                        } catch (Exception e) {
                            IACP.LOGGER.warn("[MachineGunBase] 重建碰撞禁用失败", e);
                        }
                    }
                }
            }

            IACP.LOGGER.info("[MachineGunBase] ====== 约束重建完成 @ {} ======", this.worldPosition);
        } catch (Exception e) {
            IACP.LOGGER.error("[MachineGunBase] 重建约束异常", e);
        }
    }
}
