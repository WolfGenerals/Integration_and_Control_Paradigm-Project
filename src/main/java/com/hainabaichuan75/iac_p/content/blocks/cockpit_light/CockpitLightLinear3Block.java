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
 * CockpitLightLinear3Block —— 轻型线性座舱（副驾上方防滚架）。
 * <p>
 * 2×2 多方块结构的右上块，由 {@link CockpitLightLinear0Block} 放置时自动生成，
 * 位于副驾区（Block1）正上方。不可独立放置，无对应物品。
 * <p>
 * 形状：四角立柱 + 顶部 slab + 外侧全封闭面板，中心部分开放。
 * 外侧封闭面为朝向结构外部的方向（正 x 或负 x 取决于 FACING）。
 */
public class CockpitLightLinear3Block extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 副驾上方：四角立柱 + 顶 slab + 外侧面全封闭
    // 默认朝向 NORTH，外侧面在 z=0（北侧/车尾侧）
    private static final VoxelShape SHAPE = Shapes.or(
            // 四根立柱
            Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 0.125),
            Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 0.125),
            Shapes.box(0.0, 0.0, 0.875, 0.125, 1.0, 1.0),
            Shapes.box(0.875, 0.0, 0.875, 1.0, 1.0, 1.0),
            // 顶部 slab
            Shapes.box(0.0, 0.75, 0.0, 1.0, 1.0, 1.0),
            // 外侧全封闭面板（z 负方向，朝向车尾/外侧）
            Shapes.box(0.0, 0.0, 0.0, 1.0, 0.75, 0.1875),
            // 面板顶部连接（顶 slab 与外侧面板之间的过渡）
            Shapes.box(0.0, 0.6875, 0.125, 1.0, 0.75, 0.1875)
    );

    public CockpitLightLinear3Block(BlockBehaviour.Properties properties) {
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
            // 种子方块在当前块的下方 + 反方向
            BlockPos p0 = pos.below().relative(facing.getOpposite());
            CockpitLightLinear0Block.destroyIfMatch(level, p0, ModBlocks.COCKPIT_LIGHT_LINEAR_0.get());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
