package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 玩家键盘输入状态数据包（客户端 → 服务器）。
 * <p>
 * 已弃用：原用于调试时在聊天窗口打印输入状态，
 * 现已被 VehicleControlC2SPacket 替代（直接写入悬挂方块实体）。
 * 保留此包结构及注册入口以兼容后续可能的用途扩展。
 */
public record PlayerInputC2SPacket(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "player_input"
    );
    public static final Type<PlayerInputC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerInputC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayerInputC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PlayerInputC2SPacket(
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readBoolean()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PlayerInputC2SPacket packet) {
                    buf.writeBoolean(packet.forward);
                    buf.writeBoolean(packet.backward);
                    buf.writeBoolean(packet.left);
                    buf.writeBoolean(packet.right);
                    buf.writeBoolean(packet.jump);
                    buf.writeBoolean(packet.sneak);
                }
            };

    @Override
    public Type<PlayerInputC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：已弃用，保留空实现以维持注册结构。
     * 输入处理已迁移至 VehicleControlC2SPacket。
     */
    public static void handle(final PlayerInputC2SPacket packet, final IPayloadContext context) {
        // 已弃用 —— 不再需要调试消息
    }
}
