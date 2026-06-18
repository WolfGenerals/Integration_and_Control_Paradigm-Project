package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 自定义音效注册表。
 * <p>
 * 所有武器开火音效在此注册，通过 DeferredRegister 自动代理到事件总线。
 */
public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, IACP.MODID);

    /**
     * 霰弹枪开火音效
     */
    public static final Supplier<SoundEvent> SHOTGUN_FIRE = SOUND_EVENTS.register("shotgun_fire",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(IACP.MODID, "shotgun_fire")));

    /**
     * 炮塔开火音效
     */
    public static final Supplier<SoundEvent> TURRET_FIRE = SOUND_EVENTS.register("turret_fire",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(IACP.MODID, "turret_fire")));
}
