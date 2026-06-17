package com.hainabaichuan75.iac_p.network;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.network.packets.DebugGearToggleC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.DebugSwivelToggleC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.GearShiftC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.MountedStateS2CPacket;
import com.hainabaichuan75.iac_p.network.packets.PlayerInputC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.SeatMountC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.TireConfigC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.TurretTargetC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.WeaponFireC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.AnchorConfigC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.AnchorDataS2CPacket;
import com.hainabaichuan75.iac_p.network.packets.GrindstoneConfigC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.SmartMapC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleControlC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleStateS2CPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleKeyConfigC2SPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络管理器 —— 注册所有自定义数据包并处理收发。
 */
@EventBusSubscriber(modid = IACP.MODID)
public class ModNetworking {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1.0");
        registrar.playToServer(
                SeatMountC2SPacket.TYPE,
                SeatMountC2SPacket.STREAM_CODEC,
                SeatMountC2SPacket::handle
        );
        registrar.playToServer(
                PlayerInputC2SPacket.TYPE,
                PlayerInputC2SPacket.STREAM_CODEC,
                PlayerInputC2SPacket::handle
        );
        registrar.playToClient(
                MountedStateS2CPacket.TYPE,
                MountedStateS2CPacket.STREAM_CODEC,
                MountedStateS2CPacket::handle
        );
        registrar.playToServer(
                VehicleKeyConfigC2SPacket.TYPE,
                VehicleKeyConfigC2SPacket.STREAM_CODEC,
                VehicleKeyConfigC2SPacket::handle
        );
        registrar.playToServer(
                VehicleControlC2SPacket.TYPE,
                VehicleControlC2SPacket.STREAM_CODEC,
                VehicleControlC2SPacket::handle
        );
        registrar.playToServer(
                GearShiftC2SPacket.TYPE,
                GearShiftC2SPacket.STREAM_CODEC,
                GearShiftC2SPacket::handle
        );
        registrar.playToServer(
                TireConfigC2SPacket.TYPE,
                TireConfigC2SPacket.STREAM_CODEC,
                TireConfigC2SPacket::handle
        );
        registrar.playToServer(
                GrindstoneConfigC2SPacket.TYPE,
                GrindstoneConfigC2SPacket.CODEC,
                GrindstoneConfigC2SPacket::handle
        );
        registrar.playToServer(
                AnchorConfigC2SPacket.TYPE,
                AnchorConfigC2SPacket.CODEC,
                AnchorConfigC2SPacket::handle
        );
        registrar.playToClient(
                AnchorDataS2CPacket.TYPE,
                AnchorDataS2CPacket.CODEC,
                AnchorDataS2CPacket::handle
        );
        registrar.playToServer(
                TurretTargetC2SPacket.TYPE,
                TurretTargetC2SPacket.STREAM_CODEC,
                TurretTargetC2SPacket::handle
        );
        registrar.playToServer(
                DebugGearToggleC2SPacket.TYPE,
                DebugGearToggleC2SPacket.STREAM_CODEC,
                DebugGearToggleC2SPacket::handle
        );
        registrar.playToServer(
                DebugSwivelToggleC2SPacket.TYPE,
                DebugSwivelToggleC2SPacket.STREAM_CODEC,
                DebugSwivelToggleC2SPacket::handle
        );
        registrar.playToServer(
                WeaponFireC2SPacket.TYPE,
                WeaponFireC2SPacket.STREAM_CODEC,
                WeaponFireC2SPacket::handle
        );
        registrar.playToServer(
                SmartMapC2SPacket.TYPE,
                SmartMapC2SPacket.STREAM_CODEC,
                SmartMapC2SPacket::handle
        );
        registrar.playToClient(
                VehicleStateS2CPacket.TYPE,
                VehicleStateS2CPacket.STREAM_CODEC,
                VehicleStateS2CPacket::handle
        );

    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
