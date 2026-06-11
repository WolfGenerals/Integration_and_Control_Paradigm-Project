package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;


public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IACP.MODID);

    public static final Supplier<CreativeModeTab> IACP_TAB =
            CREATIVE_MODE_TABS.register("iac_p_tab",() ->CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.COCKPIT.asItem()))
                    .title(Component.translatable("itemGroup.iac_p"))
                    .displayItems((parameters, output) -> {

                        output.accept(ModBlocks.COCKPIT.asItem());
                        output.accept(ModBlocks.TURRET_BASE.asItem());
                        output.accept(ModBlocks.SUSPENSION_TEST.asItem());
                        output.accept(ModBlocks.DEBUG_GEAR.asItem());

                    }).build());
    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
