package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 驾驶舱 BlockEntity 类型注册（与普通方块 BE 分开，避免 CockpitBlock
 * 和 CockpitBlockEntity 之间的循环依赖问题）。
 */
public class ModCockpitBlockEntityTypes {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IACP.MODID);

    public static final Supplier<BlockEntityType<CockpitBlockEntity>> COCKPIT =
            BLOCK_ENTITY_TYPES.register("cockpit",
                    () -> BlockEntityType.Builder.of(
                            CockpitBlockEntity::new,
                            ModBlocks.COCKPIT.get()
                    ).build(null));
}
