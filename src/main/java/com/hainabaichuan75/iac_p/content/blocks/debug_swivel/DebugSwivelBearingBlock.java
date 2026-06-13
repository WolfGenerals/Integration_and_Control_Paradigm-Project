package com.hainabaichuan75.iac_p.content.blocks.debug_swivel;

import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlockEntity;
import com.hainabaichuan75.iac_p.index.ModDebugSwivelBearingBlockEntityTypes;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 调试用 SwivelBearing 监视方块。
 * <p>
 * 完全继承 {@link SwivelBearingBlock} 的所有行为、外形、参数， 仅替换 BlockEntity 类型为
 * {@link DebugSwivelBearingBlockEntity}。
 * <p>
 * 将其放置在炮塔底座（TurretBaseBlock）附近，按 N 键切换调试输出。 开启后每 tick 打印该炮塔方向机/高低机的全部运行数据， 用于诊断
 * PD 伺服行为、惯性偏移、过冲回头等物理现象。
 */
public class DebugSwivelBearingBlock extends SwivelBearingBlock {

    public DebugSwivelBearingBlock(Properties properties) {
        super(properties);
    }

    /**
     * 注意：Java 泛型不可协变，不能将返回类型改为 {@code Class<DebugSwivelBearingBlockEntity>}。 但
     * {@code getBlockEntityType()} 返回的 BE type 工厂创建的是
     * {@code DebugSwivelBearingBlockEntity}， 运行时正确分发。
     */
    @Override
    public BlockEntityType<? extends SwivelBearingBlockEntity> getBlockEntityType() {
        return ModDebugSwivelBearingBlockEntityTypes.DEBUG_SWIVEL_BEARING.get();
    }
}
