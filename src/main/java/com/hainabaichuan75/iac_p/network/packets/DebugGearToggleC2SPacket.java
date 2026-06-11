package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 调试齿轮切换数据包（客户端 → 服务器）。
 * <p>
 * 按 N 键时发送，通知服务端切换 DebugGearBlockEntity 的调试输出开关。
 */
public class DebugGearToggleC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "debug_gear_toggle"
    );
    public static final Type<DebugGearToggleC2SPacket> TYPE = new Type<>(ID);

    private final BlockPos pos;

    public DebugGearToggleC2SPacket(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() { return pos; }

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugGearToggleC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DebugGearToggleC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new DebugGearToggleC2SPacket(buf.readBlockPos());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DebugGearToggleC2SPacket packet) {
                    buf.writeBlockPos(packet.pos);
                }
            };

    public static void handle(DebugGearToggleC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            var level = player.level();
            if (!(level instanceof ServerLevel)) return;

            BlockPos pos = packet.pos();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DebugGearBlockEntity dgbe) {
                dgbe.toggleDebug();
            } else {
                IACP.LOGGER.warn("[DebugGear] N 键命中位置 {} 不是调试齿轮", pos);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
