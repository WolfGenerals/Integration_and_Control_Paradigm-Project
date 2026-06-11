package com.hainabaichuan75.iac_p;

import com.hainabaichuan75.iac_p.client.ClientEvents;
import com.hainabaichuan75.iac_p.client.ClientMountGameHandler;
import com.hainabaichuan75.iac_p.client.ClientMountHandler;
import com.hainabaichuan75.iac_p.client.VehicleDebugOverlay;
import com.hainabaichuan75.iac_p.client.WeaponOverlay;
import com.hainabaichuan75.iac_p.client.renderer.AxisLineRenderer;
import com.hainabaichuan75.iac_p.client.renderer.BulletTrailRenderer;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestRenderer;
import com.hainabaichuan75.iac_p.index.ModBlockEntityTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = IACP.MODID, dist = Dist.CLIENT)
public class IACPClient {
    public IACPClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // 注册按键映射
        modEventBus.addListener(this::registerKeyMappings);

        // 注册 BlockEntity 渲染器
        modEventBus.addListener(this::registerRenderers);

        // 将客户端事件处理器注册到游戏总线
        NeoForge.EVENT_BUS.register(ClientMountGameHandler.class);
        NeoForge.EVENT_BUS.register(ClientMountHandler.class);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        NeoForge.EVENT_BUS.register(VehicleDebugOverlay.class);
        NeoForge.EVENT_BUS.register(WeaponOverlay.class);
        NeoForge.EVENT_BUS.register(AxisLineRenderer.class);
        NeoForge.EVENT_BUS.register(BulletTrailRenderer.class);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientEvents.getMountKey());
        event.register(ClientEvents.getVehicleConfigKey());
        event.register(ClientEvents.getRaycastFireKey());
        event.register(ClientEvents.getDebugGearKey());
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntityTypes.SUSPENSION_TEST.get(),
                SuspensionTestRenderer::new);
    }
}
