package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretAimController;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 炮塔目标位置数据包（客户端 → 服务器）。
 * 当玩家在载具上按下 B 键执行射线检测后，将命中坐标发送到服务端，
 * 由 {@link TurretAimController} 驱动炮塔自动瞄准。
 */
public record TurretTargetC2SPacket(
        double targetX,
        double targetY,
        double targetZ
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "turret_target");
    public static final Type<TurretTargetC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, TurretTargetC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TurretTargetC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new TurretTargetC2SPacket(
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, TurretTargetC2SPacket packet) {
                    buf.writeDouble(packet.targetX);
                    buf.writeDouble(packet.targetY);
                    buf.writeDouble(packet.targetZ);
                }
            };

    @Override
    public Type<TurretTargetC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：查找当前玩家的载具 SubLevel 中的所有炮塔底座，
     * 设置瞄准目标位置。
     */
    public static void handle(final TurretTargetC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!PlayerMountTracker.isMounted(player)) return;

            ServerLevel level = player.serverLevel();
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;

            // 获取当前挂载的 SubLevel
            var mountData = PlayerMountTracker.getMountData(player);
            if (mountData == null) return;

            SubLevel vehicleSubLevel = container.getSubLevel(mountData.subLevelUUID());
            if (vehicleSubLevel == null) return;

            // 遍历 SubLevel 中的方块，查找炮塔底座
            LevelPlot plot = vehicleSubLevel.getPlot();
            if (plot == null) return;

            int turretCount = 0;
            for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
                BoundingBox3ic localBounds = chunk.getBoundingBox();
                if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

                int chunkMinX = chunk.getPos().getMinBlockX();
                int chunkMinZ = chunk.getPos().getMinBlockZ();

                for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                    for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                        for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                            BlockPos worldPos = new BlockPos(x + chunkMinX, y, z + chunkMinZ);
                            BlockEntity be = level.getBlockEntity(worldPos);
                            if (be instanceof TurretBaseBlockEntity turret) {
                                if (!turret.isAssembled()) continue;
                                turretCount++;
                                UUID gsUUID = turret.getGrindstoneSubLevelId();
                                // 设置瞄准目标
                                TurretAimController.setTarget(
                                        gsUUID,
                                        packet.targetX, packet.targetY, packet.targetZ
                                );
                                // 给玩家发送反馈
                                if (turretCount == 1) {
                                    player.displayClientMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                    String.format("§e[炮塔] 目标: (%.0f, %.0f, %.0f)",
                                                            packet.targetX, packet.targetY, packet.targetZ)),
                                            true); // action bar
                                }
                            }
                        }
                    }
                }
            }

            if (turretCount == 0) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.iac_p.turret_not_found"),
                        true);
            }
        });
    }
}
