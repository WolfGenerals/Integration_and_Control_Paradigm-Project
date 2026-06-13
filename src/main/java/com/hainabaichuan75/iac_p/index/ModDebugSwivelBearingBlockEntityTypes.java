package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModDebugSwivelBearingBlockEntityTypes {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES
            = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IACP.MODID);

    public static final Supplier<BlockEntityType<DebugSwivelBearingBlockEntity>> DEBUG_SWIVEL_BEARING
            = BLOCK_ENTITY_TYPES.register("debug_swivel_bearing",
                    () -> BlockEntityType.Builder.of(
                            DebugSwivelBearingBlockEntity::new,
                            ModBlocks.DEBUG_SWIVEL_BEARING.get()
                    ).build(null));
}
