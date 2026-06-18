package com.hainabaichuan75.iac_p;

import com.hainabaichuan75.iac_p.affiliation.AffiliationCommand;
import com.hainabaichuan75.iac_p.affiliation.WorldLoadHandler;
import com.hainabaichuan75.iac_p.events.MountedProtectionHandler;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import com.hainabaichuan75.iac_p.events.SablePostPhysicsTickEvent;
import com.hainabaichuan75.iac_p.events.SubLevelProjectileHandler;
import com.hainabaichuan75.iac_p.index.ModBlockEntityTypes;
import com.hainabaichuan75.iac_p.index.ModBlocks;
import com.hainabaichuan75.iac_p.index.ModCockpitBlockEntityTypes;
import com.hainabaichuan75.iac_p.index.ModCreativeModeTabs;
import com.hainabaichuan75.iac_p.index.ModDebugGearBlockEntityTypes;
import com.hainabaichuan75.iac_p.index.ModDebugSwivelBearingBlockEntityTypes;
import com.hainabaichuan75.iac_p.index.ModEntities;
import com.hainabaichuan75.iac_p.index.ModItems;
import com.hainabaichuan75.iac_p.index.ModLightCockpitBlockEntityTypes;
import com.hainabaichuan75.iac_p.index.ModSounds;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

@Mod(IACP.MODID)
public class IACP {

    public static final String MODID = "iac_p";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IACP(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 注册所有 DeferredRegister
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCockpitBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModDebugGearBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModDebugSwivelBearingBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModLightCockpitBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // 将服务端事件处理器注册到游戏总线（替代弃用的 @EventBusSubscriber(bus = Bus.GAME)）
        NeoForge.EVENT_BUS.register(PlayerMountTracker.class);
        // 骑乘保护处理器：伤害免疫 + 仇恨屏蔽
        NeoForge.EVENT_BUS.register(MountedProtectionHandler.class);
        // 部件损坏系统：外部弹射物命中 SubLevel 方块
        NeoForge.EVENT_BUS.register(SubLevelProjectileHandler.class);
        // 部件损坏缓存 + 裂纹同步 + chunk 加载重发
        NeoForge.EVENT_BUS.register(PartDamageCache.class);

        // Sable 物理 tick 后处理：将延迟敏感的玩家位置同步提升到物理 tick 频率
        NeoForge.EVENT_BUS.register(SablePostPhysicsTickEvent.class);

        // 归属系统调试命令
        NeoForge.EVENT_BUS.register(AffiliationCommand.class);

        // 世界加载时清空归属/部件注册表（维度切换/重载时防止数据残留）
        NeoForge.EVENT_BUS.register(WorldLoadHandler.class);
    }
}
