package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/**
 * AnchorConfigC2SPacket —— 客户端→服务端：更新砂轮锚点坐标。
 * <p>
 * 玩家在 GrindstoneConfigScreen 中修改 X/Y/Z 后保存时发送。
 * 服务端找到拥有该 SubLevel 的 TurretBaseBlockEntity，更新锚点。
 */
public record AnchorConfigC2SPacket(UUID subLevelUUID, double x, double y, double z) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "anchor_config");
    public static final Type<AnchorConfigC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorConfigC2SPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                    AnchorConfigC2SPacket::subLevelUUID,
                    ByteBufCodecs.DOUBLE, AnchorConfigC2SPacket::x,
                    ByteBufCodecs.DOUBLE, AnchorConfigC2SPacket::y,
                    ByteBufCodecs.DOUBLE, AnchorConfigC2SPacket::z,
                    AnchorConfigC2SPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AnchorConfigC2SPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                Level level = sp.serverLevel();
                UUID uuid = packet.subLevelUUID;

                // 查砂轮注册表找到底座
                BlockPos ownerPos = TurretBaseBlockEntity.findOwnerByGrindstoneUUID(uuid);
                if (ownerPos == null) {
                    // 也查避雷针注册表（用户可能对避雷针打开配置）
                    ownerPos = TurretBaseBlockEntity.findOwnerByRodUUID(uuid);
                }
                if (ownerPos != null) {
                    BlockEntity be = level.getBlockEntity(ownerPos);
                    if (be instanceof TurretBaseBlockEntity turret) {
                        turret.setAnchor(packet.x, packet.y, packet.z);
                        IACP.LOGGER.info("[AnchorConfig] 锚点A -> ({}, {}, {}) (底座 @ {})",
                                packet.x, packet.y, packet.z, ownerPos);
                        return;
                    }
                }

                IACP.LOGGER.warn("[AnchorConfig] 未找到拥有 SubLevel {} 的 TurretBase", uuid);
            }
        });
    }
}
