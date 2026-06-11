package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, IACP.MODID);

    // （暂无自定义实体）
}
