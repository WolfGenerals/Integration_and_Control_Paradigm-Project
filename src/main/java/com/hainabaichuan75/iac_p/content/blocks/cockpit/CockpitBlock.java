package com.hainabaichuan75.iac_p.content.blocks.cockpit;

import com.hainabaichuan75.iac_p.index.ModBlocks;
import com.hainabaichuan75.iac_p.index.ModCockpitBlockEntityTypes;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * CockpitBlock —— 通用驾驶舱（下半部分）。
 * <p>
 * 多方块结构的下半截，形状类似炼药锅（底部实心 + 四壁），
 * 放置时自动在上方生成 {@link CockpitUpperBlock}。
 * 破坏任意一半都会连带破坏另一半，仅掉落一个物品。
 * <p>
 * 参考木门的放置逻辑，但下方不需要支撑方块（可以浮空）。
 * 对着方块顶部或侧面放置均可，总是竖直向上延伸两格。
 */
public class CockpitBlock extends Block implements IBE<CockpitBlockEntity> {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 炼药锅形状：底部 slab + 四壁
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 0.375, 1.0),   // 底部实心 slab
            Shapes.box(0.0, 0.375, 0.0, 0.125, 1.0, 1.0),  // 后壁
            Shapes.box(0.875, 0.375, 0.0, 1.0, 1.0, 1.0),  // 前壁
            Shapes.box(0.125, 0.375, 0.0, 0.875, 1.0, 0.125), // 左壁
            Shapes.box(0.125, 0.375, 0.875, 0.875, 1.0, 1.0)  // 右壁
    );

    public CockpitBlock(BlockBehaviour.Properties properties) {
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

    // ====== BlockEntity ======

    @Override
    public Class<CockpitBlockEntity> getBlockEntityClass() {
        return CockpitBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CockpitBlockEntity> getBlockEntityType() {
        return ModCockpitBlockEntityTypes.COCKPIT.get();
    }

    // ====== 放置逻辑 ======

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();

        // 检查上方是否有空间容纳上格
        if (pos.getY() >= level.getMaxBuildHeight() - 1) return null;
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) return null;

        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            // 放置后自动在上方生成上格
            BlockPos above = pos.above();
            level.setBlock(above, ModBlocks.COCKPIT_UPPER.get().defaultBlockState()
                    .setValue(CockpitUpperBlock.FACING, state.getValue(FACING)), 3);
        }
    }

    // ====== 破坏逻辑：连带破坏上格 ======

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!movedByPiston && !state.is(newState.getBlock())) {
            // 下格被破坏 → 同时破坏上格（使用 35 flag 抑制掉落物）
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            if (aboveState.is(ModBlocks.COCKPIT_UPPER.get())) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(2001, above, Block.getId(aboveState));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
