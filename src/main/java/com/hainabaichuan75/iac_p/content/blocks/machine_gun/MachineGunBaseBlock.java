package com.hainabaichuan75.iac_p.content.blocks.machine_gun;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.index.ModBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * MachineGunBaseBlock —— 机枪底座方块（地毯状，Create 动力学方块）。
 * <p>
 * 放置时自动召唤砂轮 SubLevel（水平旋转/方向机）和避雷针 SubLevel（俯仰/高低机）。
 * 右键（空手）可切换拆卸/重新装配。形状类似地毯（1/16 格高），玩家可以站在上面。
 * <p>
 * 同时也是 Create 动力学方块 + 齿轮（ICogWheel）， 四个侧面可以接入齿轮驱动，RPM 通过约束电机驱动机枪旋转。
 */
public class MachineGunBaseBlock extends KineticBlock implements IBE<MachineGunBaseBlockEntity>, ICogWheel {

    /**
     * 地毯形状：1/16 格高
     */
    private static final VoxelShape SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0 / 16.0, 1.0);

    public MachineGunBaseBlock(Properties properties) {
        super(properties);
    }

    /**
     * 放置时自动召唤机枪（砂轮 + 避雷针 SubLevel）。 玩家放置底座后立即装配，无需额外右键操作。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        this.withBlockEntityDo(level, pos, be -> {
            if (!be.isAssembled()) {
                IACP.LOGGER.info("[MachineGunBaseBlock] setPlacedBy: 自动装配 @ {}", pos);
                be.assemble();
            }
        });
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        // 机枪绕 Y 轴旋转（方向机）
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        // 无轴连接（仅齿轮啮合）
        return false;
    }

    // ICogWheel: 本方块就是齿轮，四个侧面可接入齿轮
    @Override
    public boolean isLargeCog() {
        return false; // 小齿轮
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // 用物品右键 → 交给默认行为（比如放置方块）
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!player.mayBuild()) {
            return InteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            return InteractionResult.FAIL;
        }
        // 空手右键触发装配/拆卸（1.21.1 空手交互走此方法）
        IACP.LOGGER.info("[MachineGunBaseBlock] useWithoutItem @ {} client={} player={}",
                pos, level.isClientSide, player.getName().getString());
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        this.withBlockEntityDo(level, pos, be -> {
            IACP.LOGGER.info("[MachineGunBaseBlock] 回调 BE：assembled={}", be.isAssembled());
            if (be.isAssembled()) {
                be.disassemble();
            } else {
                be.assemble();
            }
        });
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && state.hasBlockEntity() && state.getBlock() != newState.getBlock()) {
            IACP.LOGGER.info("[MachineGunBaseBlock] onRemove @ {} newState={}", pos, newState);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MachineGunBaseBlockEntity turretBE) {
                IACP.LOGGER.info("[MachineGunBaseBlock] onRemove: 调用 disassemble, assembled={}", turretBE.isAssembled());
                turretBE.disassemble();
            } else {
                IACP.LOGGER.warn("[MachineGunBaseBlock] onRemove: BE 为空或类型不匹配: {}", be);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<MachineGunBaseBlockEntity> getBlockEntityClass() {
        return MachineGunBaseBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MachineGunBaseBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.MACHINE_GUN_BASE.get();
    }
}
