package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * AnchorDataS2CPacket —— 服务端→客户端：推送锚点数据及线条渲染数据。
 * <p>
 * 在 assemble() 和 setAnchor() 时发送，由客户端直接存入静态缓存。
 * 手动编解码，避免 StreamCodec.composite() 的参数数量限制。
 */
public record AnchorDataS2CPacket(
        UUID subLevelUUID,
        double anchorX, double anchorY, double anchorZ,
        double[] lineData  // 12 doubles: [ox,oy,oz, xx,xy,xz, yx,yy,yz, zx,zy,zz]
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "anchor_data");
    public static final Type<AnchorDataS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<ByteBuf, AnchorDataS2CPacket> CODEC = new StreamCodec<>() {
        @Override
        public AnchorDataS2CPacket decode(ByteBuf buf) {
            UUID uuid = new UUID(buf.readLong(), buf.readLong());
            double ax = buf.readDouble();
            double ay = buf.readDouble();
            double az = buf.readDouble();
            double[] ld = new double[12];
            for (int i = 0; i < 12; i++) ld[i] = buf.readDouble();
            return new AnchorDataS2CPacket(uuid, ax, ay, az, ld);
        }

        @Override
        public void encode(ByteBuf buf, AnchorDataS2CPacket p) {
            buf.writeLong(p.subLevelUUID.getMostSignificantBits());
            buf.writeLong(p.subLevelUUID.getLeastSignificantBits());
            buf.writeDouble(p.anchorX);
            buf.writeDouble(p.anchorY);
            buf.writeDouble(p.anchorZ);
            for (int i = 0; i < 12; i++) buf.writeDouble(p.lineData[i]);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AnchorDataS2CPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            UUID uuid = packet.subLevelUUID;

            // 存入锚点缓存
            TurretBaseBlockEntity.getAnchorMap().put(uuid,
                    new double[]{packet.anchorX, packet.anchorY, packet.anchorZ});

            // 存入线条缓存
            TurretBaseBlockEntity.getLineCache().put(uuid, packet.lineData);

            IACP.LOGGER.info("[AnchorDataS2CPacket] 收到锚点数据: UUID={} anchor=({}, {}, {})",
                    uuid, packet.anchorX, packet.anchorY, packet.anchorZ);
        });
    }
}
