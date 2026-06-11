package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 换挡数据包（客户端 → 服务器）。
 * <p>
 * 玩家上车后按 Q（升档）或 E（降档）时发送此包。
 * 服务端找到当前骑乘的 SubLevel 中的驾驶舱，执行换挡操作。
 */
public class GearShiftC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "gear_shift"
    );
    public static final Type<GearShiftC2SPacket> TYPE = new Type<>(ID);

    /**
     * 换挡方向。
     */
    public enum Direction { UP, DOWN }

    private final Direction direction;

    public GearShiftC2SPacket(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() { return direction; }

    public static final StreamCodec<RegistryFriendlyByteBuf, GearShiftC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public GearShiftC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new GearShiftC2SPacket(buf.readEnum(Direction.class));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, GearShiftC2SPacket packet) {
                    buf.writeEnum(packet.direction);
                }
            };

    @Override
    public Type<GearShiftC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：找到当前骑乘 SubLevel 中的驾驶舱，执行换挡。
     */
    public static void handle(final GearShiftC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (!PlayerMountTracker.isMounted(serverPlayer)) return;

                var mountData = PlayerMountTracker.getMountData(serverPlayer);
                if (mountData == null) return;

                ServerLevel level = serverPlayer.serverLevel();
                var container = SubLevelContainer.getContainer(level);
                if (container == null) return;

                SubLevel subLevel = container.getSubLevel(mountData.subLevelUUID());
                if (subLevel == null) return;

                // 在 SubLevel 中查找驾驶舱 BlockEntity
                CockpitBlockEntity cockpit = findCockpitInSubLevel(subLevel, level);
                if (cockpit == null) return;

                switch (packet.direction) {
                    case UP   -> cockpit.gearUp();
                    case DOWN -> cockpit.gearDown();
                }
            }
        });
    }

    /**
     * 在 SubLevel 的已加载 chunk 中扫描 CockpitBlock，返回其 BlockEntity。
     */
    private static CockpitBlockEntity findCockpitInSubLevel(SubLevel subLevel, ServerLevel level) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return null;

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic localBounds = chunk.getBoundingBox();
            if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

            int chunkMinX = chunk.getPos().getMinBlockX();
            int chunkMinZ = chunk.getPos().getMinBlockZ();

            for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                    for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                        BlockPos worldPos = new BlockPos(x + chunkMinX, y, z + chunkMinZ);
                        BlockState state = level.getBlockState(worldPos);

                        if (state.getBlock() instanceof CockpitBlock) {
                            BlockEntity be = level.getBlockEntity(worldPos);
                            if (be instanceof CockpitBlockEntity cockpit) {
                                return cockpit;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
