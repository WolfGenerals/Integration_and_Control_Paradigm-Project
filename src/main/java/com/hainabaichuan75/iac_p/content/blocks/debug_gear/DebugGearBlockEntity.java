package com.hainabaichuan75.iac_p.content.blocks.debug_gear;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.index.ModDebugGearBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 调试小齿轮的 BlockEntity。
 * <p>
 * 调试输出由 N 键切换（通过 DebugGearToggleC2SPacket 设置 debugEnabled）。
 * 开启后每 tick 打印齿轮的 RPM 状态，用于观察 Create 应力网络的
 * 启动加速、稳态运行、停止减速过程的精确数据。
 */
public class DebugGearBlockEntity extends KineticBlockEntity {

    /** 调试模式开关 */
    private boolean debugEnabled = false;

    public DebugGearBlockEntity(BlockPos pos, BlockState state) {
        super(ModDebugGearBlockEntityTypes.DEBUG_GEAR.get(), pos, state);
    }

    /**
     * 切换调试模式并打印初始状态。
     */
    public void toggleDebug() {
        this.debugEnabled = !this.debugEnabled;
        if (this.debugEnabled) {
            IACP.LOGGER.info("[DebugGear] ⚙ 调试输出已开启 @ {}", this.worldPosition);
            printStatus();
        } else {
            IACP.LOGGER.info("[DebugGear] ⚙ 调试输出已关闭 @ {}", this.worldPosition);
        }
    }

    public boolean isDebugEnabled() {
        return this.debugEnabled;
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.debugEnabled) return;
        if (this.level == null || this.level.isClientSide) return;

        // 每tick全速打印，用户可通过设置游戏刻来控制输出长度
        printStatus();
    }

    /**
     * 打印当前齿轮状态到日志。
     */
    private void printStatus() {
        if (this.level == null) return;

        float speed = this.getSpeed();

        // RPM = speed（Create 内部单位），convertToAngular 转为 °/tick
        float angularDegPerTick = KineticBlockEntity.convertToAngular(speed);
        // Create 惯用：1 RPM ≈ 1 speed 单位
        float rpm = speed;

        IACP.LOGGER.info("[DebugGear] tick={} speed={} angular={}°/t RPM={} pos={}",
                this.level.getGameTime(),
                String.format("%+.1f", speed),
                String.format("%+.2f", angularDegPerTick),
                String.format("%+.0f", rpm),
                this.worldPosition.toShortString()
        );
    }
}
