package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModDebugGearBlockEntityTypes {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IACP.MODID);

    public static final Supplier<BlockEntityType<DebugGearBlockEntity>> DEBUG_GEAR =
            BLOCK_ENTITY_TYPES.register("debug_gear",
                    () -> BlockEntityType.Builder.of(
                            DebugGearBlockEntity::new,
                            ModBlocks.DEBUG_GEAR.get()
                    ).build(null));
}
