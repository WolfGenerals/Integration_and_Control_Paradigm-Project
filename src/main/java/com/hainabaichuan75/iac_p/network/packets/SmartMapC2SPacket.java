package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能映射数据包（客户端 → 服务器）。
 * <p>
 * 玩家在朝向信息界面中点击「汽车模式」「反转方向」「开关」等按钮时发送，
 * 服务端执行对应的智能按键分配逻辑。
 */
public class SmartMapC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "smart_map"
    );
    public static final Type<SmartMapC2SPacket> TYPE = new Type<>(ID);

    public enum Action {
        CAR_MODE,   // 应用汽车模式智能映射
        REVERSE,    // 反转方向盘（W↔S, A↔D）
        TOGGLE_SMART // 开关智能映射
    }

    private final Action action;
    private final UUID subLevelUUID;

    public SmartMapC2SPacket(Action action, UUID subLevelUUID) {
        this.action = action;
        this.subLevelUUID = subLevelUUID;
    }

    public Action action() { return action; }
    public UUID subLevelUUID() { return subLevelUUID; }

    public static final StreamCodec<RegistryFriendlyByteBuf, SmartMapC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SmartMapC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new SmartMapC2SPacket(
                            buf.readEnum(Action.class),
                            buf.readUUID()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, SmartMapC2SPacket packet) {
                    buf.writeEnum(packet.action);
                    buf.writeUUID(packet.subLevelUUID);
                }
            };

    @Override
    public Type<SmartMapC2SPacket> type() {
        return TYPE;
    }

    // ====================================================================
    //  服务端处理
    // ====================================================================

    public static void handle(final SmartMapC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            ServerLevel level = serverPlayer.serverLevel();
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return;

            SubLevel subLevel = container.getSubLevel(packet.subLevelUUID);
            if (subLevel == null) return;

            // 查找该 SubLevel 中的驾驶舱
            CockpitBlockEntity cockpit = findCockpitInSubLevel(subLevel, level);
            if (cockpit == null) return;

            switch (packet.action) {
                case CAR_MODE -> applyCarMode(subLevel, level, cockpit);
                case REVERSE -> applyReverse(subLevel, level, cockpit);
                case TOGGLE_SMART -> toggleSmartMapping(subLevel, level, cockpit);
            }
        });
    }

    // ====================================================================
    //  CAR_MODE：汽车模式智能映射
    // ====================================================================

    /**
     * 汽车模式：根据悬挂朝向和轮位分配智能按键。
     * <ol>
     *   <li>统计东西/南北 FACING 数量，确定宽度轴</li>
     *   <li>计算所有悬挂方块的重心（平均坐标）</li>
     *   <li>东西朝向的轮子：前进=W，后退=S</li>
     *   <li>重心以南的轮子：左转=A，右转=D</li>
     *   <li>重心以北的轮子：左转=D，右转=A</li>
     * </ol>
     */
    private static void applyCarMode(SubLevel subLevel, ServerLevel level, CockpitBlockEntity cockpit) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;

        // ── 第1轮：收集所有悬挂方块的位置和朝向 ──
        record WheelData(BlockPos pos, boolean facingEastWest, double posZ) {}
        List<WheelData> wheels = new ArrayList<>();
        double sumZ = 0;

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
                        if (!(state.getBlock() instanceof SuspensionTestBlock)) continue;

                        Direction facing = state.getValue(SuspensionTestBlock.HORIZONTAL_FACING);
                        boolean isEW = facing.getAxis() == Direction.Axis.X;
                        wheels.add(new WheelData(worldPos, isEW, worldPos.getZ()));
                        sumZ += worldPos.getZ();
                    }
                }
            }
        }

        if (wheels.isEmpty()) return;

        // ── 重心 Z 坐标（用于区分南北） ──
        double centroidZ = sumZ / wheels.size();

        // ── 第2轮：为每个方块设置智能按键 ──
        for (WheelData wd : wheels) {
            BlockEntity be = level.getBlockEntity(wd.pos);
            if (!(be instanceof SuspensionTestBlockEntity sbe)) continue;

            String fwd = "key.keyboard.w";
            String bwd = "key.keyboard.s";
            String left, right;

            // 南北半区决定左右转向键
            if (wd.posZ < centroidZ) {
                // 北侧：左转=D，右转=A（镜像）
                left = "key.keyboard.d";
                right = "key.keyboard.a";
            } else {
                // 南侧：左转=A，右转=D（正常）
                left = "key.keyboard.a";
                right = "key.keyboard.d";
            }

            sbe.setSmartKeyBindings(fwd, bwd, left, right, sbe.getActiveKeyBrake());
        }

        // 标记智能映射已启用，重置反转状态
        cockpit.setSmartMappingActive(true);
        cockpit.setSmartMappingReversed(false);
        IACP.LOGGER.info("[SmartMap] CAR_MODE applied to SubLevel {} (centroidZ={}, wheels={})",
                subLevel.getUniqueId(), centroidZ, wheels.size());
    }

    // ====================================================================
    //  REVERSE：反转方向盘
    // ====================================================================

    /**
     * 反转已设置的智能按键：W↔S, A↔D。
     * 对 SubLevel 内所有悬挂方块的 smartKey 做交换。
     */
    private static void applyReverse(SubLevel subLevel, ServerLevel level, CockpitBlockEntity cockpit) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;

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
                        if (!(state.getBlock() instanceof SuspensionTestBlock)) continue;

                        BlockEntity be = level.getBlockEntity(worldPos);
                        if (!(be instanceof SuspensionTestBlockEntity sbe)) continue;

                        String oldFwd = sbe.getSmartKeyForward();
                        String oldBwd = sbe.getSmartKeyBackward();
                        String oldLeft = sbe.getSmartKeyLeft();
                        String oldRight = sbe.getSmartKeyRight();

                        // 仅当有智能映射键时才反转
                        if (oldFwd.isEmpty() && oldBwd.isEmpty()
                                && oldLeft.isEmpty() && oldRight.isEmpty()) continue;

                        sbe.setSmartKeyBindings(
                                swapWASD(oldBwd, oldFwd), // W↔S
                                swapWASD(oldFwd, oldBwd),
                                swapWASD(oldRight, oldLeft), // A↔D
                                swapWASD(oldLeft, oldRight),
                                sbe.getActiveKeyBrake()
                        );
                    }
                }
            }
        }
        // 切换引擎层方向反转（再点一次恢复）
        cockpit.setSmartMappingReversed(!cockpit.isSmartMappingReversed());
        IACP.LOGGER.info("[SmartMap] REVERSE applied");
    }

    /** 辅助：取两个字符串的非空值（若两个都非空则取 preferred） */
    private static String swapWASD(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    // ====================================================================
    //  TOGGLE_SMART：开关智能映射
    // ====================================================================

    /**
     * 切换智能映射启用/禁用。
     * <ul>
     *   <li>启用：重新应用 CAR_MODE（若尚未应用则直接调用）</li>
     *   <li>禁用：清除所有方块的 smartKey，回退到手动配置</li>
     * </ul>
     */
    private static void toggleSmartMapping(SubLevel subLevel, ServerLevel level, CockpitBlockEntity cockpit) {
        boolean wasActive = cockpit.isSmartMappingActive();
        if (wasActive) {
            // 关闭：清除所有智能键
            LevelPlot plot = subLevel.getPlot();
            if (plot != null) {
                for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
                    BoundingBox3ic localBounds = chunk.getBoundingBox();
                    if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

                    int chunkMinX = chunk.getPos().getMinBlockX();
                    int chunkMinZ = chunk.getPos().getMinBlockZ();

                    for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                        for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                            for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                                BlockPos wp = new BlockPos(x + chunkMinX, y, z + chunkMinZ);
                                BlockState st = level.getBlockState(wp);
                                if (!(st.getBlock() instanceof SuspensionTestBlock)) continue;
                                if (level.getBlockEntity(wp) instanceof SuspensionTestBlockEntity sbe) {
                                    sbe.resetSmartKeys();
                                }
                            }
                        }
                    }
                }
            }
            cockpit.setSmartMappingActive(false);
            cockpit.setSmartMappingReversed(false);
            IACP.LOGGER.info("[SmartMap] TOGGLE OFF: smart keys cleared");
        } else {
            // 开启：应用汽车模式（applyCarMode 会自动重置 reversed=false）
            applyCarMode(subLevel, level, cockpit);
            IACP.LOGGER.info("[SmartMap] TOGGLE ON: car mode applied");
        }
    }

    // ====================================================================
    //  工具：在 SubLevel 内找驾驶舱
    // ====================================================================

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
