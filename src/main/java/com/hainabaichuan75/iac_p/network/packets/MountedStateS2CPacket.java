package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.ClientMountHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 骑乘状态同步包（服务器 → 客户端）。
 * 告诉客户端玩家已上车或已下车，以便切换第三人称、绑定摄像机到 SubLevel 视觉渲染位姿等。
 * <p>
 * Plan B: 携带 SubLevel UUID，客户端据此获取 SubLevel 的 renderPose() 以消除抖动。
 * <p>
 * v4 新增: {@code vehicleMass} 字段，服务端在 mount 时通过 MassData 将实际物理质量同步到客户端，
 * 供 {@link com.hainabaichuan75.iac_p.client.VehicleDebugOverlay} 精确显示。
 */
public record MountedStateS2CPacket(
        boolean mounted, UUID subLevelUUID, double vehicleMass,
        double cockpitLocalX, double cockpitLocalY, double cockpitLocalZ
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "mounted_state");
    public static final Type<MountedStateS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MountedStateS2CPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MountedStateS2CPacket decode(RegistryFriendlyByteBuf buf) {
                    return new MountedStateS2CPacket(
                            buf.readBoolean(), buf.readUUID(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MountedStateS2CPacket packet) {
                    buf.writeBoolean(packet.mounted);
                    buf.writeUUID(packet.subLevelUUID);
                    buf.writeDouble(packet.vehicleMass);
                    buf.writeDouble(packet.cockpitLocalX);
                    buf.writeDouble(packet.cockpitLocalY);
                    buf.writeDouble(packet.cockpitLocalZ);
                }
            };

    @Override
    public Type<MountedStateS2CPacket> type() {
        return TYPE;
    }

    public static void handle(final MountedStateS2CPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientMountHandler.handleMountState(
                    packet.mounted, packet.subLevelUUID,
                    packet.cockpitLocalX, packet.cockpitLocalY, packet.cockpitLocalZ
            );
            if (packet.mounted) {
                ClientMountHandler.setVehicleMass(packet.vehicleMass);
            }
        });
    }
}
