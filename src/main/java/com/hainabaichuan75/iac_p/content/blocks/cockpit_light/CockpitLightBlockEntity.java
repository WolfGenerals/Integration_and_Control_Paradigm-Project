package com.hainabaichuan75.iac_p.content.blocks.cockpit_light;

import com.hainabaichuan75.iac_p.affiliation.ComponentHost;
import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.index.ModLightCockpitBlockEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 轻型线性座舱 BlockEntity —— 极简驾驶舱状态管理。
 * <p>
 * 功能边界：
 * <ul>
 *   <li>作为 {@link ComponentHost}（COCKPIT）供悬挂系统通过 ComponentRegistry 查询</li>
 *   <li>持有基本状态：档位 / 转速 / 油门 / 熄火标志</li>
 *   <li>NBT 持久化上述状态</li>
 * </ul>
 * <p>
 * 明确不包含：
 * <ul>
 *   <li>换挡逻辑（手动/自动）</li>
 *   <li>智能映射（SmartMapping）</li>
 *   <li>技能系统</li>
 *   <li>每 tick 引擎计算</li>
 *   <li>S2C 状态同步包</li>
 * </ul>
 * 以上功能由专用座舱类型（如 {@link com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity}）提供。
 */
public class CockpitLightBlockEntity extends SmartBlockEntity implements ComponentHost {

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
        this.throttleLevel = 0.0;
    }

    @Override
    public void onChunkUnloaded() {
        ComponentHost.unregisterComponent(this);
        super.onChunkUnloaded();
    }

    // ==================================================================
    //  运行时状态
    // ==================================================================

    /** 当前档位：-1=R, 0=N, 1～5=前进档 */
    private int currentGear = 0;

    /** 发动机当前转速（RPM） */
    private double engineRpm = 800.0;

    /** 油门踏板位置 [0.0, 1.0] */
    private double throttleLevel = 0.0;

    /** 发动机是否已熄火 */
    private boolean stalled = false;

    /** 每轮可用扭矩（Nm），当前为占位 0（动力系统暂未接入） */
    private double torquePerWheel = 0.0;

    // ==================================================================
    //  构造 & 行为
    // ==================================================================

    public CockpitLightBlockEntity(BlockPos pos, BlockState state) {
        super(ModLightCockpitBlockEntityTypes.COCKPIT_LIGHT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 无额外行为
    }

    // ==================================================================
    //  动力系统接口（供 SuspensionTestBlockEntity 查询）
    // ==================================================================

    /**
     * @return 每轮可用扭矩（Nm）。当前返回 0（占位），动力系统接入后实现。
     */
    public double getTorquePerWheel() {
        if (stalled) return 0;
        return torquePerWheel;
    }

    /**
     * @return 轮端目标 RPM。当前返回 0（占位）。
     */
    public double getTargetWheelRpm() {
        if (stalled) return 0;
        return 0;
    }

    /**
     * @return 方向符号：+1 前进, -1 倒车, 0 空档/熄火。当前返回 0。
     */
    public double getDirectionSign() {
        if (stalled) return 0;
        if (currentGear > 0) return 1;
        if (currentGear < 0) return -1;
        return 0;
    }

    /**
     * @return 发动机是否已熄火
     */
    public boolean isStalled() {
        return stalled;
    }

    // ==================================================================
    //  状态访问器
    // ==================================================================

    public int getCurrentGear() { return currentGear; }
    public double getEngineRpm() { return engineRpm; }
    public double getThrottleLevel() { return throttleLevel; }

    // ==================================================================
    //  NBT 持久化 & 同步
    // ==================================================================

    private static final String TAG_GEAR = "CurrentGear";
    private static final String TAG_RPM = "EngineRpm";
    private static final String TAG_THROTTLE = "ThrottleLevel";
    private static final String TAG_STALLED = "Stalled";

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(TAG_GEAR, this.currentGear);
        tag.putDouble(TAG_RPM, this.engineRpm);
        tag.putDouble(TAG_THROTTLE, this.throttleLevel);
        tag.putBoolean(TAG_STALLED, this.stalled);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(TAG_GEAR)) this.currentGear = tag.getInt(TAG_GEAR);
        if (tag.contains(TAG_RPM)) this.engineRpm = tag.getDouble(TAG_RPM);
        // throttleLevel 在 onLoad() 中强制归零
        if (tag.contains(TAG_THROTTLE)) this.throttleLevel = tag.getDouble(TAG_THROTTLE);
        if (tag.contains(TAG_STALLED)) this.stalled = tag.getBoolean(TAG_STALLED);
    }
}
