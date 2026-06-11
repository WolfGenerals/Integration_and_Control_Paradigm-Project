package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.events.ServerMountHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 上车/下车请求数据包（客户端 → 服务器）。
 * 客户端按下 F 键时发送此包，服务端响应执行上车/下车逻辑。
 */
public record SeatMountC2SPacket() implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "seat_mount");
    public static final Type<SeatMountC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, SeatMountC2SPacket> STREAM_CODEC =
            StreamCodec.unit(new SeatMountC2SPacket());

    @Override
    public Type<SeatMountC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：执行上车/下车逻辑。
     */
    public static void handle(final SeatMountC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                ServerMountHandler.handleMountDismount(serverPlayer);
            }
        });
    }
}
