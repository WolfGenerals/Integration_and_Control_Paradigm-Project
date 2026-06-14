package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.ComponentEntry;
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
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
 * 玩家输入 (W/S 油门)
 *   ↓
 * [tick()] EngineModel 质量自适应扭矩 + 扭矩曲线 + 连续油门 + 负载模型
 *   ↓
 * [getWheelOutput()] TransmissionModel 变速箱 + 差速分配
 *   ↓
 * 各 SuspensionTestBlockEntity 的 P 控制器
 * </pre>
 */
public class CockpitBlockEntity extends SmartBlockEntity implements ComponentHost {

    // 所有编译时常量已提取到 PowertrainConstants.java
    // 通过静态导入 `import static ...PowertrainConstants.*` 访问
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
    }

    @Override
    public void onChunkUnloaded() {
        ComponentHost.unregisterComponent(this);
        super.onChunkUnloaded();
    }

    // ====================================================================
    //  运行时状态
    // ====================================================================
    /**
     * 当前档位：
     * <ul>
     * <li>-1 = 倒车档 (R)</li>
     * <li> 0 = 空档 (N)</li>
     * <li> 1～5 = 前进档</li>
     * </ul>
     */
    private int currentGear = 0;

    /**
     * 发动机当前转速（RPM）。
     */
    private double engineRpm = PowertrainConstants.ENGINE_IDLE_RPM;

    /**
     * 油门踏板位置 0.0（全松）~ 1.0（全踩）。 W 增加，S 减少，松开按键后保持在当前位置（无自动衰减）。
     */
    private double throttleLevel = 0.0;

    /**
     * 质量自适应有效扭矩（Nm）。 根据车辆实际质量计算，轻车小扭矩、重车大扭矩， 保证一致的功率/重量比。通过 NBT 同步到客户端供覆盖层显示。
     */
    private double effectiveTorque = PowertrainConstants.ENGINE_TORQUE;

    /**
     * WASD 智能映射是否已启用。 为 true 时使用 smartKey*，为 false 时回退到手动配置的 key*。 通过 NBT
     * 同步到客户端，供朝向信息界面显示开关状态。
     */
    private boolean smartMappingActive = false;

    /**
     * WASD 智能映射方向是否已反转。 为 true 时引擎层反转方向解读，使按键交换后的驾驶行为与交换前一致。
     */
    private boolean smartMappingReversed = false;

    // ====================================================================
    //  构造
    // ====================================================================
    public CockpitBlockEntity(BlockPos pos, BlockState state) {
        super(ModCockpitBlockEntityTypes.COCKPIT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // ====================================================================
    //  动力系统接口
    // ====================================================================
    /**
     * 直接设置发动机转速（用于外部强制复位）。
     */
    public void setEngineRpm(double rpm) {
        this.engineRpm = net.minecraft.util.Mth.clamp(rpm, ENGINE_IDLE_RPM, ENGINE_MAX_RPM);
    }

    /**
     * 获取动力系统输出 —— 委托 {@link TransmissionModel#computeWheelOutput}。
     */
    public @NotNull
    TransmissionModel.PowertrainOutput getWheelOutput(int totalWheels) {
        return TransmissionModel.computeWheelOutput(
                this.currentGear, this.throttleLevel,
                this.engineRpm, this.effectiveTorque,
                this.smartMappingActive, this.smartMappingReversed,
                totalWheels);
    }

    // ====================================================================
    //  换挡操作
    // ====================================================================
    /**
     * 升档：委托 {@link TransmissionModel#gearUp}。
     */
    public void gearUp() {
        int old = this.currentGear;
        var result = TransmissionModel.gearUp(this.currentGear, this.engineRpm);
        this.currentGear = result.gear();
        this.engineRpm = result.engineRpm();
        if (old != this.currentGear) {
            setChanged();
            sendData();
        }
    }

    /**
     * 降档：委托 {@link TransmissionModel#gearDown}。
     */
    public void gearDown() {
        int old = this.currentGear;
        var result = TransmissionModel.gearDown(this.currentGear, this.engineRpm);
        this.currentGear = result.gear();
        this.engineRpm = result.engineRpm();
        if (old != this.currentGear) {
            setChanged();
            sendData();
        }
    }

    /**
     * @return 当前档位代号：-1=R, 0=N, 1-5=前进档
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
     * @return 质量自适应有效扭矩（Nm），由 getWheelOutput() 使用
     */
    public double getEffectiveTorque() {
        return effectiveTorque;
    }

    public boolean isSmartMappingActive() {
        return smartMappingActive;
    }

    public void setSmartMappingActive(boolean active) {
        this.smartMappingActive = active;
        setChanged();
        sendData();
    }

    public boolean isSmartMappingReversed() {
        return smartMappingReversed;
    }

    public void setSmartMappingReversed(boolean reversed) {
        this.smartMappingReversed = reversed;
        setChanged();
        sendData();
    }

    /**
     * @return 当前档位的人类可读名称
     */
    public String getGearDisplayName() {
        return PowertrainConstants.gearName(this.currentGear);
    }

    /**
     * 将发动机重置到怠速。下车/断线时调用。
     */
    public void resetEngineToIdle() {
        this.engineRpm = PowertrainConstants.ENGINE_IDLE_RPM;
    }

    // ====================================================================
    //  每 tick 更新
    // ====================================================================
    @Override
    public void tick() {
        super.tick();
        if (level == null) {
            return;
        }

        SubLevel sl = Sable.HELPER.getContaining(this);

        if (sl != null) {
            // ── 质量自适应扭矩 + 扭矩曲线（委托 EngineModel）──
            if (sl instanceof ServerSubLevel ssl) {
                try {
                    double totalMass = ssl.getMassTracker().getMass();
                    this.effectiveTorque = EngineModel.computeMassAdaptiveTorque(totalMass);
                } catch (Exception e) {
                    this.effectiveTorque = PowertrainConstants.ENGINE_TORQUE;
                }
                this.effectiveTorque *= EngineModel.computeTorqueCurveMultiplier(this.engineRpm);
            }

            // ── 单次 SubLevel 扫描：收集所有悬挂数据 ──
            SubLevelScanResult scan = scanSubLevel(sl);
            int direction = scan.throttleDirection;

            // 智能映射反转
            if (smartMappingActive && smartMappingReversed) {
                direction = -direction;
            }

            // ── 三段式油门调整（委托 EngineModel）──
            this.throttleLevel = EngineModel.updateThrottle(this.throttleLevel, direction);

            // ── 扭矩平衡转速模型（委托 EngineModel）──
            var rpmResult = EngineModel.computeRpmUpdate(
                    this.engineRpm, this.throttleLevel, this.currentGear, scan.loadFactor);
            this.engineRpm = rpmResult.engineRpm();

            // ── 发动机-轮速耦合（委托 EngineModel）──
            this.engineRpm = EngineModel.applyEngineCoupling(
                    this.currentGear, scan.avgWheelRpm, this.engineRpm);
        }
    }

    /**
     * 单次扫描 SubLevel 内所有悬挂方块，收集全部所需数据。
     * <p>
     * 原先的 scanThrottleDirection / calculateLoadFactor / getAverageWheelRpm
     * 各自独立做了全量 SubLevel 扫描，每 tick 3 次 → 合并为 1 次。
     */
    private record SubLevelScanResult(int throttleDirection, double loadFactor, double avgWheelRpm) {
    }

    /**
     * 扫描 SubLevel 内所有悬挂方块，收集动力系统所需数据。
     * <p>
     * <b>首选</b>通过 {@link ComponentRegistry} 获取悬挂部件列表（O(1) 查询），
     * <b>回退</b>到 {@link SubLevelScanner} 全量遍历（当注册表不完整时）。
     */
    private SubLevelScanResult scanSubLevel(SubLevel sl) {
        UUID subUUID = sl.getUniqueId();

        // ---- 首选：通过 ComponentRegistry 查询 ----
        var entries = ComponentRegistry.getComponents(subUUID, ComponentRole.SUSPENSION);
        if (!entries.isEmpty()) {
            return scanFromRegistry(entries);
        }

        // ---- 回退：全量扫描（注册表尚未就绪 / 旧版兼容） ----
        IACP.LOGGER.debug("[Cockpit] ComponentRegistry 无悬挂数据，回退到全量扫描 (SubLevel {})",
                subUUID.toString().substring(0, 8));
        return scanFromScanner(sl);
    }

    /**
     * 从注册表数据计算扫描结果。
     */
    private SubLevelScanResult scanFromRegistry(List<com.hainabaichuan75.iac_p.affiliation.ComponentEntry> entries) {
        boolean anyForward = false;
        boolean anyBackward = false;
        double totalDemand = 0;
        double totalMaxForce = 0;
        double totalRpm = 0;
        int count = 0;

        double ratio = PowertrainConstants.getCurrentRatio(this.currentGear);
        double absRatio = Math.abs(ratio) * FINAL_DRIVE_RATIO;
        double localEffTorque = this.effectiveTorque;
        int localGear = this.currentGear;

        for (var entry : entries) {
            BlockEntity be = entry.blockEntity();
            if (!(be instanceof SuspensionTestBlockEntity sbe)) {
                continue;
            }

            // 油门方向
            if (sbe.isThrottleForward()) {
                anyForward = true;
            }
            if (sbe.isThrottleBackward()) {
                anyBackward = true;
            }

            // 负载因子
            if (localGear != 0) {
                totalDemand += sbe.getTotalEngineLoad();
                double wheelRadius = sbe.getWheelRadius();
                if (wheelRadius > 0.01) {
                    totalMaxForce += (localEffTorque * absRatio) / wheelRadius;
                }
            }

            // 轮速耦合
            totalRpm += sbe.getCurrentWheelRpm();
            count++;
        }

        int direction = (anyForward == anyBackward) ? 0 : (anyForward ? +1 : -1);
        double loadFactor = (count > 0 && totalMaxForce > 0) ? totalDemand / (totalMaxForce / count) : 0;
        double avgWheelRpm = count > 0 ? totalRpm / count : 0;
        return new SubLevelScanResult(direction, loadFactor, avgWheelRpm);
    }

    /**
     * 回退方案：全量扫描 SubLevel。
     */
    private SubLevelScanResult scanFromScanner(SubLevel sl) {
        boolean[] anyForward = {false};
        boolean[] anyBackward = {false};
        double[] totalDemand = {0};
        double[] totalMaxForce = {0};
        double[] totalRpm = {0};
        int[] count = {0};

        double ratio = PowertrainConstants.getCurrentRatio(this.currentGear);
        double absRatio = Math.abs(ratio) * FINAL_DRIVE_RATIO;
        double localEffTorque = this.effectiveTorque;
        int localGear = this.currentGear;

        SubLevelScanner.forEachBlock(sl, level, (worldPos, state, be) -> {
            if (!(state.getBlock() instanceof SuspensionTestBlock)) {
                return;
            }
            if (!(be instanceof SuspensionTestBlockEntity sbe)) {
                return;
            }

            if (sbe.isThrottleForward()) {
                anyForward[0] = true;
            }
            if (sbe.isThrottleBackward()) {
                anyBackward[0] = true;
            }

            if (localGear != 0) {
                totalDemand[0] += sbe.getTotalEngineLoad();
                double wheelRadius = sbe.getWheelRadius();
                if (wheelRadius > 0.01) {
                    totalMaxForce[0] += (localEffTorque * absRatio) / wheelRadius;
                }
            }

            totalRpm[0] += sbe.getCurrentWheelRpm();
            count[0]++;
        });

        int direction = (anyForward[0] == anyBackward[0]) ? 0 : (anyForward[0] ? +1 : -1);
        double loadFactor = 0;
        if (count[0] > 0 && totalMaxForce[0] > 0) {
            loadFactor = totalDemand[0] / (totalMaxForce[0] / count[0]);
        }
        double avgWheelRpm = count[0] > 0 ? totalRpm[0] / count[0] : 0;
        return new SubLevelScanResult(direction, loadFactor, avgWheelRpm);
    }

    // ====================================================================
    //  NBT 持久化 & 同步
    // ====================================================================
    private static final String TAG_GEAR = "CurrentGear";
    private static final String TAG_RPM = "EngineRpm";
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
        if (tag.contains(TAG_GEAR)) {
            this.currentGear = tag.getInt(TAG_GEAR);
        }
        if (tag.contains(TAG_RPM)) {
            this.engineRpm = tag.getDouble(TAG_RPM);
        }
        if (tag.contains(TAG_THROTTLE_LEVEL)) {
            this.throttleLevel = tag.getDouble(TAG_THROTTLE_LEVEL);
        }
        if (tag.contains(TAG_EFFECTIVE_TORQUE)) {
            this.effectiveTorque = tag.getDouble(TAG_EFFECTIVE_TORQUE);
        }
        if (tag.contains(TAG_SMART_MAPPING)) {
            this.smartMappingActive = tag.getBoolean(TAG_SMART_MAPPING);
        }
        if (tag.contains(TAG_SMART_MAPPING_REVERSED)) {
            this.smartMappingReversed = tag.getBoolean(TAG_SMART_MAPPING_REVERSED);
        }
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
    /**
     * 将档位代号转为人名可读的名称。
     */
    private static String gearName(int gear) {
        return switch (gear) {
            case -1 ->
                "R";
            case 0 ->
                "N";
            default ->
                String.valueOf(gear);
        };
    }

}
