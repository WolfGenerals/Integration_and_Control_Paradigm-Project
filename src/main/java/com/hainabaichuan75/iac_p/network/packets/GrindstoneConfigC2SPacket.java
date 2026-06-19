package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.machine_gun.MachineGunBaseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * GrindstoneConfigC2SPacket —— 客户端→服务端：更改砂轮朝向。
 * <p>
 * 携带砂轮 SubLevel 的 UUID 和目标朝向方向。
 * 服务端找到拥有该 SubLevel 的 MachineGunBaseBlockEntity，更新方块状态。
 */
public record GrindstoneConfigC2SPacket(UUID grindstoneSubLevelUUID, Direction facing) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "grindstone_config");
    public static final Type<GrindstoneConfigC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, GrindstoneConfigC2SPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString), GrindstoneConfigC2SPacket::grindstoneSubLevelUUID,
                    ByteBufCodecs.VAR_INT.map(Direction::from3DDataValue, Direction::get3DDataValue), GrindstoneConfigC2SPacket::facing,
                    GrindstoneConfigC2SPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GrindstoneConfigC2SPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                Level level = sp.serverLevel();
                UUID uuid = packet.grindstoneSubLevelUUID;

                // 先查砂轮注册表
                BlockPos ownerPos = MachineGunBaseBlockEntity.findOwnerByGrindstoneUUID(uuid);
                if (ownerPos != null) {
                    BlockEntity be = level.getBlockEntity(ownerPos);
                    if (be instanceof MachineGunBaseBlockEntity turret) {
                        turret.setGrindstoneFacing(packet.facing);
                        IACP.LOGGER.info("[PartConfig] 砂轮朝向 -> {} (底座 @ {})", packet.facing, ownerPos);
                        return;
                    }
                }

                // 再查避雷针注册表（炮塔）
                ownerPos = MachineGunBaseBlockEntity.findOwnerByRodUUID(uuid);
                if (ownerPos != null) {
                    BlockEntity be = level.getBlockEntity(ownerPos);
                    if (be instanceof MachineGunBaseBlockEntity turret) {
                        turret.setLightningRodFacing(packet.facing);
                        IACP.LOGGER.info("[PartConfig] 末地烛朝向 -> {} (底座 @ {})", packet.facing, ownerPos);
                        return;
                    }
                }

                // 查霰弹枪砂轮注册表
                ownerPos = ShotgunBaseBlockEntity.findOwnerByGrindstoneUUID(uuid);
                if (ownerPos != null) {
                    BlockEntity be = level.getBlockEntity(ownerPos);
                    if (be instanceof ShotgunBaseBlockEntity shotgun) {
                        shotgun.setGrindstoneFacing(packet.facing);
                        IACP.LOGGER.info("[PartConfig] 霰弹枪砂轮朝向 -> {} (底座 @ {})", packet.facing, ownerPos);
                        return;
                    }
                }

                // 查霰弹枪避雷针注册表
                ownerPos = ShotgunBaseBlockEntity.findOwnerByRodUUID(uuid);
                if (ownerPos != null) {
                    BlockEntity be = level.getBlockEntity(ownerPos);
                    if (be instanceof ShotgunBaseBlockEntity shotgun) {
                        shotgun.setLightningRodFacing(packet.facing);
                        IACP.LOGGER.info("[PartConfig] 霰弹枪避雷针朝向 -> {} (底座 @ {})", packet.facing, ownerPos);
                        return;
                    }
                }

                IACP.LOGGER.warn("[PartConfig] 未找到拥有 SubLevel {} 的武器底座", uuid);
            }
        });
    }
}
