package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.ComponentHost;
import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.events.SubLevelScanner;
import com.hainabaichuan75.iac_p.index.ModCockpitBlockEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

import static com.hainabaichuan75.iac_p.content.blocks.cockpit.PowertrainConstants.*;

/**
 * 驾驶舱方块实体 —— 载具动力系统的状态管理和编排。
 * <p>
 * 编译时常量见 {@link PowertrainConstants}，发动机计算见 {@link EngineModel}，变速箱见
 * {@link TransmissionModel}。
 *
 * <h3>动力系统架构</h3>
 * <pre>
 * 玩家输入 (油门)
 *   ↓
 * EngineModel.computeThrottleControlledRun()  ← 发动机始终独立运行
 *   RPM = IDLE + throttle × (MAX - IDLE)         油门直控，不受变速箱影响
 *   扭矩 = ENGINE_TORQUE × torqueCurve(RPM)      纯 RPM 函数，油门 100% 已含内部损耗
 *   ↓
 * 空档：torquePerWheel = 0（变速箱断开）
 * 在档：TransmissionModel.computeOutput() 纯数学变换
 *   扭矩b = 扭矩a × 齿比  转速b = RPM / 齿比
 *   换挡真空期 6 tick → 扭矩b = 0
 *   ↓
 * 各 Suspension 从 getTorquePerWheel() 读取可用扭矩
 *   摩擦圆约束决定实际地面驱动力（轮胎是唯一限幅器）
 * </pre>
 */
public class CockpitBlockEntity extends SmartBlockEntity implements ComponentHost {

    // ==================================================================
    //  ComponentHost 实现
    // ==================================================================
    @Override
    public ComponentRole getComponentRole() {
        return ComponentRole.COCKPIT;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ComponentHost.registerComponent(this, getComponentRole());
        // 油门强制归零：NBT 写入 throttleLevel 是为了客户端同步（覆盖层显示），
        // 但重新加载世界时必须清零，否则油门残留会驱动 RPM 自涨。
        this.throttleLevel = 0.0;
        this.rawThrottleDirection = 0;
    }

    @Override
    public void onChunkUnloaded() {
        ComponentHost.unregisterComponent(this);
        super.onChunkUnloaded();
    }

    // ====================================================================
    //  运行时状态
    // ====================================================================

    /** 当前档位：-1=R, 0=N, 1～5=前进档 */
    private int currentGear = 0;

    /** 发动机当前转速（RPM） */
    private double engineRpm = ENGINE_IDLE_RPM;

    /** 油门踏板位置 [0.0, 1.0] */
    private double throttleLevel = 0.0;

    /** 当前 tick 的引擎输出扭矩（Nm），含扭矩曲线修正 × 油门。
     *  即 computeEngineTorque() 的计算结果缓存，不含离合器/摩擦扣减。 */
    private double effectiveTorque = PowertrainConstants.ENGINE_TORQUE;

    /** 智能映射启用 */
    private boolean smartMappingActive = false;

    /** 当前驾驶技能 ID */
    private String activeSkillId = com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;

    /** 原始油门方向（+1/-1/0），由 VehicleControlC2SPacket 设置 */
    private int rawThrottleDirection = 0;

    // ── 扭矩源模型新增字段 ──

    /** 每轮可用扭矩（Nm），供悬挂 P 控制器限幅 */
    private double torquePerWheel = 0.0;

    /** 发动机是否已熄火 */
    private boolean stalled = false;

    /** 上次更新时在档位中的轮子总数（用于扭矩均摊） */
    private int lastWheelCount = 0;


    // ── 换挡状态 ──
    public CockpitBlockEntity(BlockPos pos, BlockState state) {
        super(ModCockpitBlockEntityTypes.COCKPIT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // ====================================================================
    //  动力系统接口（供 SuspensionTestBlockEntity 查询）
    // ====================================================================

    /**
     * @return 每轮可用扭矩（Nm）。空档或熄火时返回 0。
     */
    public double getTorquePerWheel() {
        if (stalled || currentGear == 0) return 0;
        return torquePerWheel;
    }

    /**
     * @return 轮端目标 RPM（由发动机当前转速通过齿比推算）。
     *         正值前进，负值倒车。空档或熄火时返回 0。
     */
    public double getTargetWheelRpm() {
        if (stalled) return 0;
        return TransmissionModel.computeTargetWheelRpm(currentGear, engineRpm);
    }

    /**
     * @return 方向符号：+1 前进, -1 倒车, 0 空档/熄火
     */
    public double getDirectionSign() {
        if (stalled) return 0;
        return TransmissionModel.getDirectionSign(currentGear);
    }

    /**
     * @return 发动机是否已熄火
     */
    public boolean isStalled() {
        return stalled;
    }

    /**
     * 尝试重启发动机（仅熄火时有效）。
     */
    public void tryRestart() {
        if (!stalled) return;
        stalled = false;
        engineRpm = ENGINE_IDLE_RPM;
        IACP.LOGGER.info("[Cockpit] 发动机重启");
        setChanged();
        sendData();
    }

    // ====================================================================
    //  换挡操作
    // ====================================================================

    /**
     * 升档：R → N → 1 → 2 → 3 → 4 → 5。
     * <p>
     * 非瞬时完成：启动换挡序列 → SHIFT_TIME_TICKS tick 动力中断 → 档位切换。
     * 换挡期间 torquePerWheel = 0，发动机空载运行。
     */
    public void gearUp() {
        if (isShifting) return;
        var result = TransmissionModel.gearUp(this.currentGear, this.engineRpm);
        if (result.gear() == this.currentGear) return;
        startShiftSequence(result.gear());
    }

    /**
     * 降档：5 → 4 → 3 → 2 → 1 → N → R。
     * <p>
     * 非瞬时完成：启动换挡序列 → SHIFT_TIME_TICKS tick 动力中断 → 档位切换。
     */
    public void gearDown() {
        if (isShifting) return;
        var result = TransmissionModel.gearDown(this.currentGear, this.engineRpm);
        if (result.gear() == this.currentGear) return;
        startShiftSequence(result.gear());
    }

    /**
     * @return 当前档位：-1=R, 0=N, 1-5=前进档
     */
    public int getCurrentGear() {
        return currentGear;
    }

    /**
     * @return 发动机当前转速（RPM）
     */
    public double getEngineRpm() {
        return engineRpm;
    }

    /**
     * @return 当前 tick 的引擎输出扭矩（Nm），含扭矩曲线修正 × 油门
     */
    public double getEffectiveTorque() {
        return effectiveTorque;
    }

    /**
     * @return 当前油门位置 [0.0, 1.0]
     */
    public double getThrottleLevel() {
        return throttleLevel;
    }

    // ====================================================================
    //  智能映射与技能
    // ====================================================================

    public boolean isSmartMappingActive() {
        return smartMappingActive;
    }

    public void setSmartMappingActive(boolean active) {
        this.smartMappingActive = active;
        setChanged();
        sendData();
    }

    public String getActiveSkillId() {
        return activeSkillId;
    }

    public void setActiveSkillId(String skillId) {
        this.activeSkillId = skillId != null ? skillId : com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;
        setChanged();
        sendData();
    }

    // ====================================================================
    //  控制输入
    // ====================================================================

    /**
     * 设置原始油门方向。由 VehicleControlC2SPacket 每 tick 调用。
     */
    public void setRawThrottleDirection(int dir) {
        this.rawThrottleDirection = dir;
    }

    /**
     * @return 当前档位的人类可读名称
     */
    public String getGearDisplayName() {
        return PowertrainConstants.gearName(this.currentGear);
    }

    /**
     * @return 是否正在换挡（动力中断期间）
     */
    public boolean isShifting() {
        return isShifting;
    }

    /**
     * 启动换挡序列。
     * <p>
     * 立即切断轮端扭矩（torquePerWheel = 0），启动换挡计时器。
     * 计时器归零时由 tick() 完成档位切换。
     * 换挡后 RPM 由实际轮速（车速）决定，而非旧 RPM × 齿比推算。
     *
     * @param targetGear 目标档位
     */
    private void startShiftSequence(int targetGear) {
        this.isShifting = true;
        this.shiftingTimer = PowertrainConstants.SHIFT_TIME_TICKS;
        this.targetShiftGear = targetGear;
        this.torquePerWheel = 0;

        // ── 降档自动补油目标（Rev-Match）──
        // 前进档降档：target < current（如 4→3, 2→1），需更高 RPM 匹配低档位
        // 换挡期间油门直控模式下临时提油使 RPM 升到目标值
        if (this.currentGear >= 2 && targetGear >= 1 && targetGear < this.currentGear) {
            double oldRatio = PowertrainConstants.getRatioForGear(this.currentGear);
            double newRatio = PowertrainConstants.getRatioForGear(targetGear);
            this.revMatchTargetRpm = this.engineRpm * newRatio / oldRatio;
            this.revMatchTargetRpm = net.minecraft.util.Mth.clamp(
                    this.revMatchTargetRpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);
        } else {
            this.revMatchTargetRpm = 0;
        }

        IACP.LOGGER.debug("[Cockpit] 换挡开始 → {} (revMatch={} RPM)",
                PowertrainConstants.gearName(targetGear), (int)this.revMatchTargetRpm);
        setChanged();
        sendData();
    }

    /**
     * 将发动机重置到怠速。下车/断线/重启时调用。
     * 同时清零油门深度，防止 NBT 持久化的 throttleLevel 在重登后继续生效。
     */
    public void resetEngineToIdle() {
        this.engineRpm = ENGINE_IDLE_RPM;
        this.throttleLevel = 0.0;
        this.rawThrottleDirection = 0;
        this.stalled = false;
    }

    /**
     * 直接设置发动机转速（用于外部强制复位）。
     */
    public void setEngineRpm(double rpm) {
        this.engineRpm = net.minecraft.util.Mth.clamp(rpm, 0, ENGINE_MAX_RPM);
    }

    // ====================================================================
    //  每 tick 更新
    // ====================================================================
    //  空档：油门直控转速，变速箱断开，发动机在测试架上独立运转。
    //  在档：变速箱纯比率变换，发动机转速由轮速运动学约束。

    /** 缓存总质量（kg），仅用于覆盖层显示 */
    private double totalMass = 1000.0;

    /** 上次同步到客户端的油门值，用于阈值检测避免每 tick 刷包 */
    private double lastSyncedThrottle = -1.0;

    /** 状态同步包冷却计数器（每 2 tick 向客户端推送一次实时状态） */
    private int stateSyncCooldown = 0;

    // ── 换挡状态 ──
    /** 是否正在换挡（动力中断期间）。期间 torquePerWheel = 0，发动机空载运行。 */
    private boolean isShifting = false;
    /** 换挡倒计时（tick），归零时完成换挡 */
    private int shiftingTimer = 0;
    /** 换挡目标档位 */
    private int targetShiftGear = 0;
    /** 降档自动补油（Rev-Match）目标 RPM。降档时发动机需升转匹配低档位，
     *  此值为目标转速，在换挡期间自动补油使 RPM 平滑接近此值。 */
    private double revMatchTargetRpm = 0;

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;

        SubLevel sl = Sable.HELPER.getContaining(this);
        if (sl == null) return;

        // ── 读取物理质量（仅服务端，仅用于覆盖层显示）──
        if (sl instanceof ServerSubLevel ssl) {
            try {
                this.totalMass = ssl.getMassTracker().getMass();
            } catch (Exception ignored) { }
        }

        // ── 油门（服务端才有 rawThrottleDirection，客户端跳过）──
        if (sl instanceof ServerSubLevel) {
            this.throttleLevel = EngineModel.updateThrottle(this.throttleLevel, this.rawThrottleDirection);
        }

        // ── 全部引擎计算仅服务端执行 ──
        if (sl instanceof ServerSubLevel serverSl) {
            // ── 状态同步（引擎计算之前执行，确保提前返回也能发送）──
            if (Math.abs(this.throttleLevel - this.lastSyncedThrottle) > 0.02) {
                this.lastSyncedThrottle = this.throttleLevel;
                setChanged();
                sendData();
            }
            // 状态包推送：每 2 tick
            if (--this.stateSyncCooldown <= 0) {
                this.stateSyncCooldown = 2;
                double speedMs = 0;
                try {
                    org.joml.Vector3d vel = dev.ryanhcode.sable.Sable.HELPER.getVelocity(level,
                            new org.joml.Vector3d(
                                    this.worldPosition.getX() + 0.5,
                                    this.worldPosition.getY() + 0.5,
                                    this.worldPosition.getZ() + 0.5));
                    if (vel != null) speedMs = vel.length();
                } catch (Exception ignored) { }
                var subLevel = dev.ryanhcode.sable.Sable.HELPER.getContaining(this);
                if (subLevel != null) {
                    var player = com.hainabaichuan75.iac_p.events.PlayerMountTracker.getPlayerForSubLevel(
                            subLevel.getUniqueId(), (net.minecraft.server.level.ServerLevel) level);
                    if (player != null) {
                        com.hainabaichuan75.iac_p.network.ModNetworking.sendToPlayer(player,
                                new com.hainabaichuan75.iac_p.network.packets.VehicleStateS2CPacket(
                                        this.engineRpm,
                                        this.throttleLevel,
                                        this.currentGear,
                                        this.stalled,
                                        this.effectiveTorque,
                                        speedMs,
                                        this.isShifting
                                ));
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  引擎计算 — 发动机完全独立运行，变速箱仅做数学变换
            // ═══════════════════════════════════════════════════════════════
            //
            //  发动机始终由油门直控：throttle=0% → 800 RPM, 100% → 6000 RPM。
            //  无论空档还是在档，变速箱都不反向约束发动机转速。
            //  挂档只是把扭矩a × 齿比输出到轮端，不影响发动机自身状态。

            if (stalled) {
                this.torquePerWheel = 0;
                this.effectiveTorque = 0;
                return;
            }

            // ═══ 换挡真空期 ═══
            if (this.isShifting) {
                this.torquePerWheel = 0;
                this.effectiveTorque = 0;

                // Rev-match 自动补油：降档时临时提高油门
                double effectiveThrottle = this.throttleLevel;
                if (this.revMatchTargetRpm > 0) {
                    double rpmNow = EngineModel.computeThrottleControlledRun(this.throttleLevel).rpm();
                    if (this.revMatchTargetRpm > rpmNow) {
                        double blip = (this.revMatchTargetRpm - ENGINE_IDLE_RPM)
                                / (ENGINE_MAX_RPM - ENGINE_IDLE_RPM);
                        effectiveThrottle = Math.max(effectiveThrottle, Math.min(blip, 0.8));
                    }
                }

                var sr = EngineModel.computeThrottleControlledRun(effectiveThrottle);
                this.engineRpm = sr.rpm();

                if (--this.shiftingTimer <= 0) {
                    this.currentGear = this.targetShiftGear;
                    this.isShifting = false;
                    IACP.LOGGER.debug("[Cockpit] 换挡完成 → {}", PowertrainConstants.gearName(this.currentGear));
                    setChanged();
                    sendData();
                }
                return;
            }

            // ═══ 正常行驶：发动机永远独立运行 ═══
            var result = EngineModel.computeThrottleControlledRun(this.throttleLevel);
            this.effectiveTorque = result.engineTorque();
            this.engineRpm = result.rpm();

            if (this.currentGear == 0) {
                // 空档：不向轮端输出扭矩
                this.torquePerWheel = 0;
            } else {
                // 在档：变速箱做纯数学变换，扭矩a × 齿比 → 扭矩b
                WheelScanResult wheels = scanWheelRpm(sl);
                this.lastWheelCount = wheels.wheelCount;
                int wheelCount = Math.max(wheels.wheelCount, 1);
                var gbOut = TransmissionModel.computeOutput(result.engineTorque(), result.rpm(), this.currentGear);
                this.torquePerWheel = gbOut.torqueB() / wheelCount;
            }
        }
    }

    /**
     * 处理熄火：标记状态，清空扭矩输出。
     */
    private void handleStall() {
        this.stalled = true;
        this.engineRpm = 0;
        this.torquePerWheel = 0;
        this.effectiveTorque = 0;
        IACP.LOGGER.info("[Cockpit] 发动机熄火！");
        setChanged();
        sendData();
    }

    // ====================================================================
    //  车轮扫描（简化版：仅获取轮速和数量，无需消耗扭矩）
    // ====================================================================

    private record WheelScanResult(double avgWheelRpm, int wheelCount) {}

    private WheelScanResult scanWheelRpm(SubLevel sl) {
        UUID subUUID = sl.getUniqueId();

        var entries = ComponentRegistry.getComponents(subUUID, ComponentRole.SUSPENSION);
        if (!entries.isEmpty()) {
            return scanRpmFromRegistry(entries);
        }

        return scanRpmFromScanner(sl);
    }

    private WheelScanResult scanRpmFromRegistry(
            List<com.hainabaichuan75.iac_p.affiliation.ComponentEntry> entries) {
        double totalRpm = 0;
        int count = 0;

        for (var entry : entries) {
            BlockEntity be = entry.blockEntity();
            if (!(be instanceof SuspensionTestBlockEntity sbe)) continue;
            totalRpm += sbe.getCurrentWheelRpm();
            count++;
        }

        double avgRpm = count > 0 ? totalRpm / count : 0;
        return new WheelScanResult(avgRpm, count);
    }

    private WheelScanResult scanRpmFromScanner(SubLevel sl) {
        double[] totalRpm = {0};
        int[] count = {0};

        SubLevelScanner.forEachBlock(sl, level, (worldPos, state, be) -> {
            if (!(state.getBlock() instanceof SuspensionTestBlock)) return;
            if (!(be instanceof SuspensionTestBlockEntity sbe)) return;
            totalRpm[0] += sbe.getCurrentWheelRpm();
            count[0]++;
        });

        double avgRpm = count[0] > 0 ? totalRpm[0] / count[0] : 0;
        return new WheelScanResult(avgRpm, count[0]);
    }

    // ====================================================================
    //  NBT 持久化 & 同步
    // ====================================================================

    private static final String TAG_GEAR = "CurrentGear";
    private static final String TAG_RPM = "EngineRpm";
    /** 油门深度写入 NBT 供客户端同步（覆盖层显示需要）。
     *  onLoad() 强制归零，保证重登后不会油门残留。 */
    private static final String TAG_THROTTLE_LEVEL = "ThrottleLevel";
    private static final String TAG_EFFECTIVE_TORQUE = "EffectiveTorque";
    private static final String TAG_SMART_MAPPING = "SmartMappingActive";
    private static final String TAG_SKILL_ID = "ActiveSkillId";
    private static final String TAG_STALLED = "Stalled";

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(TAG_GEAR, this.currentGear);
        tag.putDouble(TAG_RPM, this.engineRpm);
        tag.putDouble(TAG_THROTTLE_LEVEL, this.throttleLevel);
        tag.putDouble(TAG_EFFECTIVE_TORQUE, this.effectiveTorque);
        tag.putBoolean(TAG_SMART_MAPPING, this.smartMappingActive);
        tag.putString(TAG_SKILL_ID, this.activeSkillId);
        tag.putBoolean(TAG_STALLED, this.stalled);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(TAG_GEAR)) this.currentGear = tag.getInt(TAG_GEAR);
        if (tag.contains(TAG_RPM)) this.engineRpm = tag.getDouble(TAG_RPM);
        // 读 throttleLevel 用于客户端同步（覆盖层显示），
        // 但 onLoad() 会在 world load 时强制归零。
        if (tag.contains(TAG_THROTTLE_LEVEL)) this.throttleLevel = tag.getDouble(TAG_THROTTLE_LEVEL);
        if (tag.contains(TAG_EFFECTIVE_TORQUE)) this.effectiveTorque = tag.getDouble(TAG_EFFECTIVE_TORQUE);
        if (tag.contains(TAG_SMART_MAPPING)) this.smartMappingActive = tag.getBoolean(TAG_SMART_MAPPING);
        if (tag.contains(TAG_SKILL_ID)) this.activeSkillId = tag.getString(TAG_SKILL_ID);
        if (tag.contains(TAG_STALLED)) this.stalled = tag.getBoolean(TAG_STALLED);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
