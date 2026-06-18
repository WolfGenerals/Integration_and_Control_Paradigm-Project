package com.hainabaichuan75.iac_p.content.blocks.cockpit_light;

import com.hainabaichuan75.iac_p.index.ModBlocks;
import com.hainabaichuan75.iac_p.index.ModLightCockpitBlockEntityTypes;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
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
 * CockpitLightLinear0Block —— 轻型线性座舱（种子方块 / 驾驶座区域）。
 * <p>
 * 2×2 多方块结构的左下种子块。放置时自动在水平方向（FACING）延伸出
 * Block1（副驾区），在垂直方向延伸出 Block2（驾驶座上方防滚架）
 * 和对角 Block3（副驾上方防滚架）。
 * <p>
 * 形状：底部实心地板 + 靠背（L 形截面），前方脚坑开放。
 * 材质暂用磁石纹理占位。
 */
public class CockpitLightLinear0Block extends Block implements IBE<CockpitLightBlockEntity> {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 驾驶座区域（L 形）：底部地板 + 靠背，前方开口为脚坑
    // 默认朝向 NORTH，靠背在北侧（z 负方向），脚坑在南侧（z 正方向）
    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 0.3125, 1.0),   // 地板 slab
            Shapes.box(0.0, 0.3125, 0.0, 1.0, 0.875, 0.3125), // 靠背下部
            Shapes.box(0.0, 0.3125, 0.0, 1.0, 0.9375, 0.125)  // 靠背/头枕上部
    );

    // 固定 shape 暂不旋转（与 CockpitBlock 行为一致）
    private static final VoxelShape SHAPE = SHAPE_NORTH;

    public CockpitLightLinear0Block(BlockBehaviour.Properties properties) {
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
    public Class<CockpitLightBlockEntity> getBlockEntityClass() {
        return CockpitLightBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CockpitLightBlockEntity> getBlockEntityType() {
        return ModLightCockpitBlockEntityTypes.COCKPIT_LIGHT.get();
    }

    // ====== 放置逻辑（种子方块） ======

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        Direction facing = context.getHorizontalDirection().getOpposite();

        // 检查全部 4 个位置可用：
        // p1 = pos.relative(facing) — 水平延伸（如同床的床头）
        // p2 = pos.above()           — 垂直延伸（如同门的上半）
        // p3 = pos.relative(facing).above() — 对角

        if (pos.getY() >= level.getMaxBuildHeight() - 1) return null;

        BlockPos p1 = pos.relative(facing);
        BlockPos p2 = pos.above();
        BlockPos p3 = p1.above();

        if (!level.getBlockState(p1).canBeReplaced(context)) return null;
        if (!level.getBlockState(p2).canBeReplaced(context)) return null;
        if (!level.getBlockState(p3).canBeReplaced(context)) return null;

        return this.defaultBlockState()
                .setValue(FACING, facing);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            Direction facing = state.getValue(FACING);
            BlockPos p1 = pos.relative(facing);
            BlockPos p2 = pos.above();
            BlockPos p3 = p1.above();

            // 放置 Block1（水平延伸——副驾区）
            level.setBlock(p1, ModBlocks.COCKPIT_LIGHT_LINEAR_1.get().defaultBlockState()
                    .setValue(CockpitLightLinear1Block.FACING, facing), 3);
            // 放置 Block2（上方——驾驶座上防滚架）
            level.setBlock(p2, ModBlocks.COCKPIT_LIGHT_LINEAR_2.get().defaultBlockState()
                    .setValue(CockpitLightLinear2Block.FACING, facing), 3);
            // 放置 Block3（对角——副驾上方防滚架）
            level.setBlock(p3, ModBlocks.COCKPIT_LIGHT_LINEAR_3.get().defaultBlockState()
                    .setValue(CockpitLightLinear3Block.FACING, facing), 3);
        }
    }

    // ====== 破坏逻辑 ======

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!movedByPiston && !state.is(newState.getBlock())) {
            Direction facing = state.getValue(FACING);
            BlockPos p1 = pos.relative(facing);
            BlockPos p2 = pos.above();
            BlockPos p3 = p1.above();

            destroyIfMatch(level, p1, ModBlocks.COCKPIT_LIGHT_LINEAR_1.get());
            destroyIfMatch(level, p2, ModBlocks.COCKPIT_LIGHT_LINEAR_2.get());
            destroyIfMatch(level, p3, ModBlocks.COCKPIT_LIGHT_LINEAR_3.get());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 在指定位置查找目标方块类型，匹配则替换为空气（flag 35 抑制掉落物）。
     */
    static void destroyIfMatch(Level level, BlockPos targetPos, Block targetBlock) {
        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.is(targetBlock)) {
            level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 35);
            level.levelEvent(2001, targetPos, Block.getId(targetState));
        }
    }
}
