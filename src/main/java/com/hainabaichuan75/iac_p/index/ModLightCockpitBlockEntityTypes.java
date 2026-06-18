package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 轻型座舱 BlockEntity 类型注册（分离注册避免 Block ↔ BE 间的循环依赖）。
 */
public class ModLightCockpitBlockEntityTypes {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IACP.MODID);

    public static final Supplier<BlockEntityType<CockpitLightBlockEntity>> COCKPIT_LIGHT =
            BLOCK_ENTITY_TYPES.register("cockpit_light",
                    () -> BlockEntityType.Builder.of(
                            CockpitLightBlockEntity::new,
                            ModBlocks.COCKPIT_LIGHT_LINEAR_0.get()
                    ).build(null));
}
