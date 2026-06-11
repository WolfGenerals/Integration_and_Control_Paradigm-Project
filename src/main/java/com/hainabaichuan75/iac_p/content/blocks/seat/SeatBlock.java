package com.hainabaichuan75.iac_p.content.blocks.seat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * SeatBlock —— 座位方块，作为载具的"核心"标记方块。
 * 玩家按下 F 键时，如果射线命中此方块（且该方块属于某个 SubLevel），
 * 则触发上车逻辑。
 *
 * 当前仅为标记方块，无纹理需求，形状为一个半砖高的底座。
 */
public class SeatBlock extends Block {

    private static final VoxelShape SHAPE = Shapes.create(
            0.0625, 0.0, 0.0625,
            0.9375, 0.5, 0.9375
    );

    public SeatBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
