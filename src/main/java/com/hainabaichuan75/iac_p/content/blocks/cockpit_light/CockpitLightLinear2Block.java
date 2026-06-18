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
 * CockpitLightLinear2Block —— 轻型线性座舱（驾驶座上方防滚架）。
 * <p>
 * 2×2 多方块结构的左上块，由 {@link CockpitLightLinear0Block} 放置时自动生成，
 * 位于种子方块正上方。不可独立放置，无对应物品。
 * <p>
 * 形状：四角立柱 + 顶部 slab + 前横梁，中心完全开放（笼式骨架）。
 */
public class CockpitLightLinear2Block extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 防滚架：四角立柱 + 顶 slab + 前横梁
    // 默认朝向 NORTH，z=0 为"后方"，z=1 为"前方"
    private static final VoxelShape SHAPE = Shapes.or(
            // 四根立柱（2/16 粗）
            Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 0.125),     // 左后
            Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 0.125),    // 右后
            Shapes.box(0.0, 0.0, 0.875, 0.125, 1.0, 1.0),    // 左前
            Shapes.box(0.875, 0.0, 0.875, 1.0, 1.0, 1.0),    // 右前
            // 顶部 slab
            Shapes.box(0.0, 0.75, 0.0, 1.0, 1.0, 1.0),
            // 前横梁（左右前柱之间的连接杆，位于驾驶座方向）
            Shapes.box(0.125, 0.5, 0.875, 0.875, 0.6875, 1.0)
    );

    public CockpitLightLinear2Block(BlockBehaviour.Properties properties) {
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
            // 种子方块在当前块正下方
            BlockPos p0 = pos.below();
            CockpitLightLinear0Block.destroyIfMatch(level, p0, ModBlocks.COCKPIT_LIGHT_LINEAR_0.get());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
