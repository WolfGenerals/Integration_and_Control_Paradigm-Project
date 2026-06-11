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
 * 胎压配置数据包（客户端 → 服务器）。
 * <p>
 * 玩家在配置界面调整胎压后点击"应用"发送此包，
 * 将胎压保存到指定悬挂测试方块的 NBT 中。
 * 其余轮胎属性由轮胎款式决定，不由玩家直接修改。
 */
public record TireConfigC2SPacket(
        BlockPos blockPos,
        double nominalPressure
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "tire_config"
    );
    public static final Type<TireConfigC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, TireConfigC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TireConfigC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new TireConfigC2SPacket(
                            buf.readBlockPos(),
                            buf.readDouble()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, TireConfigC2SPacket packet) {
                    buf.writeBlockPos(packet.blockPos);
                    buf.writeDouble(packet.nominalPressure);
                }
            };

    @Override
    public Type<TireConfigC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：将胎压写入指定悬挂测试方块的 NBT。
     */
    public static void handle(final TireConfigC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ServerLevel level = serverPlayer.serverLevel();
                if (level.getBlockEntity(packet.blockPos) instanceof SuspensionTestBlockEntity be) {
                    be.setNominalPressure(packet.nominalPressure);
                }
            }
        });
    }
}
