package com.hainabaichuan75.iac_p.client;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 客户端骑乘交互限制 —— 游戏总线（Forge Event Bus）事件处理。
 * <p>
 * 由于 {@link ClientMountHandler} 在模组总线上，无法接收 {@code PlayerInteractEvent}
 * 等游戏总线事件，因此将此类事件分离至此。
 * <p>
 * 注意：此类通过 {@code NeoForge.EVENT_BUS.register(ClientMountGameHandler.class)}
 * 注册到游戏总线。
 */
public class ClientMountGameHandler {

    // ====== 骑乘时隐藏手持物品 ======

    /**
     * 骑乘时隐藏第一人称手持物品（不影响物品栏）。
     */
    @SubscribeEvent
    public static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (ClientMountHandler.isMounted()) {
            event.setCanceled(true);
        }
    }

    // ====== 骑乘时禁止交互（客户端即时反馈） ======

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (ClientMountHandler.isMounted()
                && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (ClientMountHandler.isMounted()
                && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (ClientMountHandler.isMounted()
                && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (ClientMountHandler.isMounted()
                && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    // ====== 隐藏手部渲染 ======

}
