package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 世界加载事件处理器——在每次世界加载时清空 AffiliationRegistry 和 ComponentRegistry， 等待 SubLevel
 * 加载后通过 BlockEntity NBT 重新构建注册表。
 * <p>
 * 注册方式：{@code NeoForge.EVENT_BUS.register(WorldLoadHandler.class)}
 */
public final class WorldLoadHandler {

    private WorldLoadHandler() {
    }

    /**
     * 监听世界加载事件，清空归属注册表和部件注册表。
     * <p>
     * 维度切换/世界重载时旧 SubLevel UUID 数据会残留，必须清空等待 NBT 重建。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        AffiliationRegistry.onWorldLoad();
        ComponentRegistry.onWorldLoad();
        IACP.LOGGER.info("[WorldLoadHandler] 世界加载，已清空 AffiliationRegistry 和 ComponentRegistry");
    }
}
