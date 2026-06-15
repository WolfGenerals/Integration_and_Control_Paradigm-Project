package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.WeaponOverlay;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 世界加载事件处理器——在每次世界加载时清空所有运行时缓存， 等待 SubLevel 加载后通过 BlockEntity NBT 重新构建注册表。
 * <p>
 * 注册方式：{@code NeoForge.EVENT_BUS.register(WorldLoadHandler.class)}
 */
public final class WorldLoadHandler {

    private WorldLoadHandler() {
    }

    /**
     * 监听世界加载事件，清空所有运行时缓存，并从 NBT 恢复持久化数据。
     * <p>
     * 维度切换/世界重载时旧 SubLevel UUID 数据会残留，必须清空等待 NBT 重建。
     * 包括：归属注册表、部件注册表、骑乘状态、部件耐久缓存、武器覆盖层状态。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        AffiliationRegistry.onWorldLoad();
        ComponentRegistry.onWorldLoad();
        PlayerMountTracker.onWorldLoad();
        if (event.getLevel() instanceof Level level) {
            PartDamageCache.onWorldLoad(level);
        }
        WeaponOverlay.onWorldLoad();
        IACP.LOGGER.info("[WorldLoadHandler] 世界加载，已清空所有运行时缓存");
    }
}
