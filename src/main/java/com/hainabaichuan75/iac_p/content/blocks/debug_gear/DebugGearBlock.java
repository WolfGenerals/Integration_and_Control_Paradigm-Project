package com.hainabaichuan75.iac_p.content.blocks.debug_gear;

import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 调试用小齿轮 —— 与 AugerCogBlock 功能一致，但自带调试打印能力。
 * 接入创造马达 → 驱动原生轴承，用 N 键切换调试输出。
 */
public class DebugGearBlock extends RotatedPillarKineticBlock implements ICogWheel, IBE<DebugGearBlockEntity> {

    public DebugGearBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isSmallCog() {
        return true;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public Class<DebugGearBlockEntity> getBlockEntityClass() {
        return DebugGearBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DebugGearBlockEntity> getBlockEntityType() {
        return com.hainabaichuan75.iac_p.index.ModDebugGearBlockEntityTypes.DEBUG_GEAR.get();
    }
}
