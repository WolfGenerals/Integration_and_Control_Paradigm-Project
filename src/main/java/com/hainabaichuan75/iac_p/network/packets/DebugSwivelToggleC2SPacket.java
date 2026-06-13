package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 调试 SwivelBearing 切换数据包（客户端 → 服务器）。
 * <p>
 * 按 N 键时发送，通知服务端切换 DebugSwivelBearingBlockEntity 的调试输出开关。
 */
public class DebugSwivelToggleC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "debug_swivel_toggle"
    );
    public static final Type<DebugSwivelToggleC2SPacket> TYPE = new Type<>(ID);

    private final BlockPos pos;

    public DebugSwivelToggleC2SPacket(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugSwivelToggleC2SPacket> STREAM_CODEC
            = new StreamCodec<>() {
        @Override
        public DebugSwivelToggleC2SPacket decode(RegistryFriendlyByteBuf buf) {
            return new DebugSwivelToggleC2SPacket(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, DebugSwivelToggleC2SPacket packet) {
            buf.writeBlockPos(packet.pos);
        }
    };

    public static void handle(DebugSwivelToggleC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) {
                return;
            }
            var level = player.level();
            if (!(level instanceof ServerLevel)) {
                return;
            }

            BlockPos pos = packet.pos();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DebugSwivelBearingBlockEntity dsbe) {
                dsbe.toggleDebug();
            } else {
                IACP.LOGGER.warn("[DebugSwivel] N 键命中位置 {} 不是调试 SwivelBearing", pos);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
