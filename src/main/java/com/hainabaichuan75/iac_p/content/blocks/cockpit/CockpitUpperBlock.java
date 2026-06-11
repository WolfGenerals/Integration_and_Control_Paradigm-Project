package com.hainabaichuan75.iac_p.content.blocks.cockpit;

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
 * CockpitUpperBlock —— 通用驾驶舱（上半部分）。
 * <p>
 * 多方块结构的上半截，形状类似脚手架（底部开放的笼状结构），
 * 由 {@link CockpitBlock} 放置时自动生成，不可独立放置。
 * 破坏任意一半都会连带破坏另一半，仅掉落一个物品（由下格处理）。
 * <p>
 * 材质完全由 resources 文件夹内的 JSON 模型和 blockstate 配置着色，
 * 顶面使用 {@code scaffolding_top} 纹理（具备透明通道）。
 */
public class CockpitUpperBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 脚手架风格的笼状结构（类似脚手架，带交叉支撑）
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0),   // 底部薄 slab
            Shapes.box(0.0, 0.125, 0.0, 0.125, 1.0, 1.0),  // 后壁
            Shapes.box(0.875, 0.125, 0.0, 1.0, 1.0, 1.0),  // 前壁
            Shapes.box(0.125, 0.125, 0.0, 0.875, 1.0, 0.125), // 左壁
            Shapes.box(0.125, 0.125, 0.875, 0.875, 1.0, 1.0), // 右壁
            Shapes.box(0.125, 0.875, 0.125, 0.875, 1.0, 0.875) // 顶部
    );

    public CockpitUpperBlock(BlockBehaviour.Properties properties) {
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

    // ====== 破坏逻辑：连带破坏下格（不产生掉落物） ======

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!movedByPiston && !state.is(newState.getBlock())) {
            // 上格被破坏 → 同时破坏下格（使用 35 flag 抑制掉落物）
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.is(ModBlocks.COCKPIT.get())) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(2001, below, Block.getId(belowState));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}