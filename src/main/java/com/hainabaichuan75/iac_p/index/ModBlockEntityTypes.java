package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntityTypes {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IACP.MODID);

    public static final Supplier<BlockEntityType<SuspensionTestBlockEntity>> SUSPENSION_TEST =
            BLOCK_ENTITY_TYPES.register("suspension_test",
                    () -> BlockEntityType.Builder.of(
                            SuspensionTestBlockEntity::new,
                            ModBlocks.SUSPENSION_TEST.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<TurretBaseBlockEntity>> TURRET_BASE =
            BLOCK_ENTITY_TYPES.register("turret_base",
                    () -> BlockEntityType.Builder.of(
                            TurretBaseBlockEntity::new,
                            ModBlocks.TURRET_BASE.get()
                    ).build(null));

    public static final Supplier<BlockEntityType<ShotgunBaseBlockEntity>> SHOTGUN_BASE =
            BLOCK_ENTITY_TYPES.register("shotgun_base",
                    () -> BlockEntityType.Builder.of(
                            ShotgunBaseBlockEntity::new,
                            ModBlocks.SHOTGUN_BASE.get()
                    ).build(null));
}
