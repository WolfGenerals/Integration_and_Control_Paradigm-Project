package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 载具按键配置数据包（客户端 → 服务器）。
 * <p>
 * 玩家在按键配置界面点击"应用"后发送此包，
 * 将 5 个按键名称保存到指定悬挂测试方块的 NBT 中。
 */
public record VehicleKeyConfigC2SPacket(
        BlockPos blockPos,
        String keyForward,
        String keyBackward,
        String keyLeft,
        String keyRight,
        String keyBrake
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "vehicle_key_config"
    );
    public static final Type<VehicleKeyConfigC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, VehicleKeyConfigC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VehicleKeyConfigC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new VehicleKeyConfigC2SPacket(
                            buf.readBlockPos(),
                            buf.readUtf(64),
                            buf.readUtf(64),
                            buf.readUtf(64),
                            buf.readUtf(64),
                            buf.readUtf(64)
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, VehicleKeyConfigC2SPacket packet) {
                    buf.writeBlockPos(packet.blockPos);
                    buf.writeUtf(packet.keyForward, 64);
                    buf.writeUtf(packet.keyBackward, 64);
                    buf.writeUtf(packet.keyLeft, 64);
                    buf.writeUtf(packet.keyRight, 64);
                    buf.writeUtf(packet.keyBrake, 64);
                }
            };

    @Override
    public Type<VehicleKeyConfigC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：将按键配置写入指定悬挂测试方块的 NBT。
     */
    public static void handle(final VehicleKeyConfigC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ServerLevel level = serverPlayer.serverLevel();
                if (level.getBlockEntity(packet.blockPos) instanceof SuspensionTestBlockEntity be) {
                    be.setKeyBindings(
                            packet.keyForward,
                            packet.keyBackward,
                            packet.keyLeft,
                            packet.keyRight,
                            packet.keyBrake
                    );
                }
            }
        });
    }
}
