package com.hainabaichuan75.iac_p.content.blocks.cockpit_light;

import com.hainabaichuan75.iac_p.index.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * CockpitLightLinear1Block —— 轻型线性座舱（水平延伸 / 副驾区）。
 * <p>
 * 2×2 多方块结构的水平延伸块，由 {@link CockpitLightLinear0Block} 放置时自动生成，
 * 位于种子方块前方一格（FACING 方向）。不可独立放置，无对应物品。
 * <p>
 * 形状：底部薄 slab + 后壁，前方开口。
 */
public class CockpitLightLinear1Block extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 副驾区域：底部 slab + 后壁，前方开口
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 0.25, 1.0),    // 地板
            Shapes.box(0.0, 0.25, 0.0, 1.0, 0.8125, 0.1875) // 后壁
    );

    public CockpitLightLinear1Block(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // ====== 破坏逻辑：连带破坏种子方块 ======

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!movedByPiston && !state.is(newState.getBlock())) {
            Direction facing = state.getValue(FACING);
            // 种子方块在当前块的反方向：pos.relative(facing.getOpposite())
            BlockPos p0 = pos.relative(facing.getOpposite());
            CockpitLightLinear0Block.destroyIfMatch(level, p0, ModBlocks.COCKPIT_LIGHT_LINEAR_0.get());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
