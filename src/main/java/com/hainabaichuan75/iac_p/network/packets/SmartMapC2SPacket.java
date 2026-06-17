package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.skill.DrivingSkill;
import com.hainabaichuan75.iac_p.skill.SkillRegistry;
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
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能映射数据包（客户端 → 服务器）。
 * <p>
 * 玩家在朝向信息界面中点击「汽车模式」「选择技能」「反转方向」「开关」等按钮时发送，
 * 服务端执行对应的智能按键分配逻辑。
 * <p>
 * 数据包格式：
 * <ul>
 *   <li>Action 枚举（1 byte）</li>
 *   <li>SubLevel UUID（16 bytes）</li>
 *   <li>字符串 payload（仅 SELECT_SKILL 使用，其余动作传空字符串）</li>
 * </ul>
 */
public class SmartMapC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "smart_map"
    );
    public static final Type<SmartMapC2SPacket> TYPE = new Type<>(ID);

    public enum Action {
        CAR_MODE,       // 应用汽车模式（旧版兼容）
        REVERSE,        // 反转方向盘（W↔S, A↔D）
        TOGGLE_SMART,   // 开关智能映射
        SELECT_SKILL    // 选择驾驶技能（payload=技能ID）
    }

    private final Action action;
    private final UUID subLevelUUID;
    private final String payload; // SELECT_SKILL 时 = 技能 ID，其余空

    public SmartMapC2SPacket(Action action, UUID subLevelUUID) {
        this(action, subLevelUUID, "");
    }

    public SmartMapC2SPacket(Action action, UUID subLevelUUID, String payload) {
        this.action = action;
        this.subLevelUUID = subLevelUUID;
        this.payload = payload != null ? payload : "";
    }

    public Action action() { return action; }
    public UUID subLevelUUID() { return subLevelUUID; }
    public String payload() { return payload; }

    public static final StreamCodec<RegistryFriendlyByteBuf, SmartMapC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SmartMapC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new SmartMapC2SPacket(
                            buf.readEnum(Action.class),
                            buf.readUUID(),
                            buf.readUtf(256)
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, SmartMapC2SPacket packet) {
                    buf.writeEnum(packet.action);
                    buf.writeUUID(packet.subLevelUUID);
                    buf.writeUtf(packet.payload, 256);
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
                case CAR_MODE -> {
                    // 旧版兼容：选择默认技能
                    applySkill(subLevel, level, cockpit, SkillRegistry.DEFAULT_SKILL_ID);
                }
                case REVERSE -> applyReverse(subLevel, level, cockpit);
                case TOGGLE_SMART -> toggleSmartMapping(subLevel, level, cockpit);
                case SELECT_SKILL -> {
                    String skillId = packet.payload;
                    if (skillId.isEmpty()) {
                        IACP.LOGGER.warn("[SmartMap] SELECT_SKILL 收到空技能 ID");
                        return;
                    }
                    applySkill(subLevel, level, cockpit, skillId);
                }
            }
        });
    }

    // ====================================================================
    //  技能应用（替代旧的 applyCarMode）
    // ====================================================================

    /**
     * 应用指定技能：根据技能的 wheel_classification 和 wheel_outputs
     * 为每个悬挂方块设置 smartKey 绑定。
     */
    private static void applySkill(SubLevel subLevel, ServerLevel level, CockpitBlockEntity cockpit, String skillId) {
        DrivingSkill skill = SkillRegistry.getInstance().get(skillId);
        if (skill == null) {
            IACP.LOGGER.error("[SmartMap] 技能 '{}' 不存在", skillId);
            return;
        }

        // ── 第1步：收集所有悬挂方块的位置和朝向 ──
        List<DrivingSkill.WheelEntry> allWheels = collectWheels(subLevel, level);
        if (allWheels.isEmpty()) {
            IACP.LOGGER.warn("[SmartMap] 未找到悬挂方块");
            return;
        }

        // ── 第2步：分类 ──
        List<DrivingSkill.WheelGroup> groups = skill.classify(allWheels);

        // ── 第3步：对每个组，计算重心 ──
        for (var group : groups) {
            double centroid = computeCentroid(group, skill.classification().isFacingAxis());

            // ── 第4步：对组内每个悬挂，评估表达式并设置 smartKey ──
            for (var entry : group.wheels()) {
                BlockPos pos = BlockPos.containing(entry.posX(), entry.posY(), entry.posZ());
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof SuspensionTestBlockEntity sbe)) continue;

                var outputs = skill.evaluateOutputs(group.groupName(), entry, centroid);

                // 应用输出：解析直接键映射
                String smartFwd = resolveToKey(skill, outputs.get("forward"));
                String smartBwd = resolveToKey(skill, outputs.get("backward"));
                String smartLeft = resolveToKey(skill, outputs.get("left"));
                String smartRight = resolveToKey(skill, outputs.get("right"));
                String smartBrake = resolveToKey(skill, outputs.get("brake"));

                sbe.setSmartKeyBindings(smartFwd, smartBwd, smartLeft, smartRight, smartBrake);

                // 根据分组名称设置 strafe wheel
                boolean isStrafe = "secondary".equals(group.groupName())
                        || "ns".equalsIgnoreCase(group.groupName());
                sbe.setStrafeWheel(isStrafe);

                IACP.LOGGER.debug("[SmartMap]   {} @ ({},{}): fwd={} bwd={} left={} right={} brake={} strafe={}",
                        entry.facing(), (int)entry.posX(), (int)entry.posZ(),
                        smartFwd, smartBwd, smartLeft, smartRight, smartBrake, isStrafe);
            }
        }

        // ── 第5步：保存技能 ID 到驾驶舱 ──
        cockpit.setActiveSkillId(skillId);
        cockpit.setSmartMappingActive(true);

        IACP.LOGGER.info("[SmartMap] 技能 '{}' 已应用到 SubLevel {} ({} 个悬挂, {} 组)",
                skillId, subLevel.getUniqueId(), allWheels.size(), groups.size());
    }

    /**
     * 收集 SubLevel 中所有悬挂方块的信息。
     */
    private static List<DrivingSkill.WheelEntry> collectWheels(SubLevel subLevel, ServerLevel level) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return List.of();

        List<DrivingSkill.WheelEntry> wheels = new ArrayList<>();

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
                        wheels.add(new DrivingSkill.WheelEntry(
                                worldPos.getX(), worldPos.getY(), worldPos.getZ(),
                                facing.getName().toUpperCase(),
                                isEW,
                                facing.getStepX(), facing.getStepZ()
                        ));
                    }
                }
            }
        }
        return wheels;
    }

    /**
     * 计算分组重心。
     */
    private static double computeCentroid(DrivingSkill.WheelGroup group, boolean facingAxis) {
        if (group.wheels().isEmpty()) return 0;
        double sum = 0;
        // facing_axis 用 facing 方向的重心，side 用空间平均
        for (var w : group.wheels()) {
            sum += w.isEW() ? w.posZ() : w.posX();
        }
        return sum / group.wheels().size();
    }

    /**
     * 将输出解析为物理键名或表达式原文。
     * 如果是直接键映射（input.XXX → 已解析为键名），直接返回。
     * 如果是复杂表达式，返回表达式原文（暂不支持运行时求值）。
     */
    private static String resolveToKey(DrivingSkill skill, String resolved) {
        if (resolved == null || resolved.isEmpty()) return "";
        // 如果已经是键名格式（key.keyboard.xxx），直接使用
        if (resolved.startsWith("key.")) return resolved;
        // 否则返回空（复杂表达式需要运行时求值，暂不支持）
        return "";
    }

    // ====================================================================
    //  REVERSE：反转方向盘（保持不变）
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

                        if (oldFwd.isEmpty() && oldBwd.isEmpty()
                                && oldLeft.isEmpty() && oldRight.isEmpty()) continue;

                        sbe.setSmartKeyBindings(
                                swapWASD(oldBwd, oldFwd),
                                swapWASD(oldFwd, oldBwd),
                                swapWASD(oldRight, oldLeft),
                                swapWASD(oldLeft, oldRight),
                                sbe.getActiveKeyBrake()
                        );
                    }
                }
            }
        }
        IACP.LOGGER.info("[SmartMap] REVERSE applied");
    }

    private static String swapWASD(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    // ====================================================================
    //  TOGGLE_SMART：开关智能映射
    // ====================================================================

    /**
     * 切换智能映射启用/禁用。
     * 启用时重新应用当前技能；禁用时清除所有 smartKey。
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
                                    sbe.setStrafeWheel(false);
                                }
                            }
                        }
                    }
                }
            }
            cockpit.setSmartMappingActive(false);
            IACP.LOGGER.info("[SmartMap] TOGGLE OFF: smart keys cleared");
        } else {
            // 开启：重新应用当前技能
            String skillId = cockpit.getActiveSkillId();
            if (skillId == null || skillId.isEmpty()) {
                skillId = SkillRegistry.DEFAULT_SKILL_ID;
            }
            applySkill(subLevel, level, cockpit, skillId);
            IACP.LOGGER.info("[SmartMap] TOGGLE ON: skill '{}' applied", skillId);
        }
    }

    // ====================================================================
    //  工具：在 SubLevel 内查找方块
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
