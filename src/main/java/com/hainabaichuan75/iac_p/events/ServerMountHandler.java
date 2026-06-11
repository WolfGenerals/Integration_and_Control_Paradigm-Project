package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.IACPConfig;
import com.hainabaichuan75.iac_p.index.ModBlocks;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.MountedStateS2CPacket;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;

/**
 * 服务端上车/下车处理逻辑。
 * 遵循技术文档方案：不创建可骑乘实体，直接操纵玩家。
 * <p>
 * 变更记录（2026-05-31）：
 * <ul>
 *   <li>目标1：上车前检查 SubLevel 内驾驶舱结构唯一性</li>
 *   <li>目标2：上车前检查 SubLevel 是否已被其他玩家占用</li>
 *   <li>目标4：射线命中宽泛化 — 命中任意包含驾驶舱的 SubLevel 即可</li>
 * </ul>
 */
public class ServerMountHandler {

    private static final int COOLDOWN_TICKS = 5;
    private static final Map<UUID, Integer> COOLDOWN_MAP = new Object2IntOpenHashMap<>();

    public static void handleMountDismount(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // 冷却检查
        long currentTick = level.getGameTime();
        int lastTick = COOLDOWN_MAP.getOrDefault(player.getUUID(), 0);
        if (currentTick - lastTick < COOLDOWN_TICKS) return;
        COOLDOWN_MAP.put(player.getUUID(), (int) currentTick);

        // 已上车 → 下车
        if (PlayerMountTracker.isMounted(player)) {
            dismountPlayer(player);
            return;
        }

        // 尝试上车
        mountPlayer(player);
    }

    private static void mountPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // ====== 步骤 1：3 格射线检测 ======
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(3.0));

        BlockHitResult hitResult = level.clip(
                new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        );
        if (hitResult.getType() == HitResult.Type.MISS) return;

        BlockPos hitPos = hitResult.getBlockPos();
        Vector3d hitJomlPos = new Vector3d(hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5);

        // ====== 步骤 2：射线命中的方块必须在 SubLevel 内 ======
        SubLevel subLevel = Sable.HELPER.getContaining(level, hitJomlPos);
        if (subLevel == null) return;

        // ====== SubLevel 缩放（比例耦合方案） ======
        // 将 SubLevel 缩放到 0.33，使 Minecraft 的 1 格 ≈ Crossout 的 1 米。
        // 需要改版 Sable（feature/sublevel-scale 分支）支持 JNI 传 scale。
        // 如使用原版 Sable，设为 false 跳过以避免 Java 侧变换与物理碰撞不同步。
        if (IACPConfig.SUBLEVEL_SCALE_ENABLED) {
            Pose3d pose = subLevel.logicalPose();
            pose.scale().set(
                IACPConfig.SUBLEVEL_SCALE_X,
                IACPConfig.SUBLEVEL_SCALE_Y,
                IACPConfig.SUBLEVEL_SCALE_Z
            );
            IACP.LOGGER.info("[Scale] Set SubLevel {} scale to ({},{},{})",
                subLevel.getUniqueId(),
                IACPConfig.SUBLEVEL_SCALE_X,
                IACPConfig.SUBLEVEL_SCALE_Y,
                IACPConfig.SUBLEVEL_SCALE_Z);
        }
        // ====== Scale End ======

        // ====== 步骤 3（目标4）：检查 SubLevel 是否包含驾驶舱核心 ======
        // 不再要求射线必须命中驾驶舱方块，只要命中的 SubLevel 内有驾驶舱即可
        BlockState hitState = level.getBlockState(hitPos);
        boolean hitIsCockpit = hitState.is(ModBlocks.COCKPIT.get())
                || hitState.is(ModBlocks.SEAT.get());

        if (!hitIsCockpit) {
            // 射线没有直接命中驾驶舱 → 检查 SubLevel 内是否有驾驶舱
            if (!PlayerMountTracker.containsCockpit(subLevel, level)) return;
        }

        // ====== 步骤 3.1：驾驶舱结构完整性检查 ======
        // 如果射线直接命中了驾驶舱下格，还是检查上格是否存在
        if (hitState.is(ModBlocks.COCKPIT.get())) {
            BlockPos above = hitPos.above();
            BlockState aboveState = level.getBlockState(above);
            if (!aboveState.is(ModBlocks.COCKPIT_UPPER.get())) {
                player.sendSystemMessage(Component.translatable("message.iac_p.cockpit_incomplete"));
                return;
            }
        } else if (hitState.is(ModBlocks.SEAT.get())) {
            // SeatBlock 是单方块结构，无需完整性检查
        } else {
            // 射线未直接命中驾驶舱，但 SubLevel 内有驾驶舱
            // 需要找到驾驶舱位置进行完整性检查
            BlockPos cockpitPos = findCockpitBlockInSubLevel(subLevel, level);
            if (cockpitPos == null) {
                // 这不应该发生，因为 containsCockpit 已通过
                return;
            }
            BlockState cockpitState = level.getBlockState(cockpitPos);
            if (cockpitState.is(ModBlocks.COCKPIT.get())) {
                BlockPos above = cockpitPos.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.is(ModBlocks.COCKPIT_UPPER.get())) {
                    player.sendSystemMessage(Component.translatable("message.iac_p.cockpit_incomplete"));
                    return;
                }
            }
            // 如果是 SeatBlock，不需要完整性检查
        }

        UUID subLevelUUID = subLevel.getUniqueId();

        // ====== 步骤 3.2（目标1）：检查驾驶舱唯一性 ======
        if (!PlayerMountTracker.hasUniqueCockpit(subLevel, level)) {
            player.sendSystemMessage(Component.translatable("message.iac_p.multiple_cockpits"));
            return;
        }

        // ====== 步骤 3.3（目标2）：检查 SubLevel 是否已被其他玩家占用 ======
        if (PlayerMountTracker.isSubLevelOccupiedByOther(subLevelUUID, player)) {
            player.sendSystemMessage(Component.translatable("message.iac_p.sublevel_occupied"));
            return;
        }

        // 查找驾驶舱方块的本地位置（用于后续位置同步）
        BlockPos cockpitWorldPos = findCockpitBlockInSubLevel(subLevel, level);
        if (cockpitWorldPos == null) {
            IACP.LOGGER.warn("[ServerMount] 找不到驾驶舱方块位置，无法上车");
            return;
        }
        // 驾驶舱本地位置 = 其在 Plot 中的原始世界位置。取底部中心（y 不变 = 底部）
        double cockpitLocalX = cockpitWorldPos.getX() + 0.5;
        double cockpitLocalY = cockpitWorldPos.getY();
        double cockpitLocalZ = cockpitWorldPos.getZ() + 0.5;

        // ====== 步骤 4：执行上车 ======
        PlayerMountTracker.mount(player, subLevelUUID, cockpitLocalX, cockpitLocalY, cockpitLocalZ);

        // 禁用玩家移动/碰撞
        player.noPhysics = true;
        player.setNoGravity(true);
        player.getAbilities().flying = true;
        player.getAbilities().setFlyingSpeed(0.0f);
        player.onUpdateAbilities();

        // 速度归零
        player.setDeltaMovement(Vec3.ZERO);

        // 获取载具实际物理质量（用于客户端调试覆盖层精确显示）
        // 使用 getMass() 获取总质量，而非 getInverseNormalMass()（后者是某点的有效质量，
        // 只有点在质心时才等于总质量）。
        double mass = 0;
        if (subLevel instanceof ServerSubLevel ssl) {
            try {
                mass = ssl.getMassTracker().getMass();
            } catch (Exception e) {
                IACP.LOGGER.warn("[ServerMount] 获取载具质量失败: {}", e.getMessage());
            }
        }

        // 上车前刷新 SuspensionBE 缓存（确保轮子计数和驾驶舱引用与新状态一致）
        SuspensionTestBlockEntity.invalidateCachesInSubLevel(level, subLevelUUID);

        // 通知客户端切换到第三人称，并传递 SubLevel UUID + 实际质量 + 驾驶舱本地位置
        ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(
                true, subLevelUUID, mass,
                cockpitLocalX, cockpitLocalY, cockpitLocalZ));
        IACP.LOGGER.info("[ServerMount] 上车完成: player={}, subLevel={}, mass={}",
                player.getName().getString(), subLevelUUID, mass);
    }

    /**
     * 在 SubLevel 中查找驾驶舱核心方块的位置（使用 chunk 迭代，无需 pose 变换）。
     * 用于目标 4：射线未直接命中驾驶舱时的结构完整性检查。
     */
    private static BlockPos findCockpitBlockInSubLevel(SubLevel subLevel, ServerLevel level) {
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
                        BlockPos worldPos = new BlockPos(
                                x + chunkMinX, y, z + chunkMinZ
                        );
                        BlockState state = level.getBlockState(worldPos);

                        if (state.is(ModBlocks.COCKPIT.get()) || state.is(ModBlocks.SEAT.get())) {
                            return worldPos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 重置 SubLevel 内所有悬挂测试方块的输入状态。
     * 如果方块当前正在刹车，保留刹车（手刹逻辑）。
     * 在玩家下车前调用，防止按键输入残留导致载具自行运动。
     */
    public static void resetSuspensionInputs(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vector3d playerPos = new Vector3d(player.getX(), player.getY(), player.getZ());
        SubLevel subLevel = Sable.HELPER.getContaining(level, playerPos);
        if (subLevel == null) return;
        resetSuspensionInputsInSubLevel(subLevel, level);
    }

    /**
     * 直接重置指定 SubLevel 内所有悬挂方块的输入（保留刹车状态）。
     * 用于断线/死亡等无法通过玩家对象找到 SubLevel 的场景。
     */
    public static void resetSuspensionInputsInSubLevel(SubLevel subLevel, ServerLevel level) {
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
                        BlockEntity be = level.getBlockEntity(worldPos);
                        if (be instanceof SuspensionTestBlockEntity suspension) {
                            // 如果刹车踩着的，保留刹车（手刹效果）
                            boolean keepBrake = suspension.isBraking();
                            suspension.resetControlInput(keepBrake);
                        } else if (be instanceof CockpitBlockEntity cockpit) {
                            // 驾驶舱发动机回到怠速
                            cockpit.resetEngineToIdle();
                        }
                    }
                }
            }
        }
    }

    /**
     * 通过 SubLevel UUID 查找并重置悬挂输入。
     * 用于玩家断线时（player == null），无法通过玩家位置找到 SubLevel。
     */
    public static void resetSuspensionInputsByUUID(MinecraftServer server, UUID subLevelUUID) {
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            SubLevel subLevel = container.getSubLevel(subLevelUUID);
            if (subLevel != null) {
                resetSuspensionInputsInSubLevel(subLevel, level);
                return;
            }
        }
    }

    private static void dismountPlayer(ServerPlayer player) {
        // === 前置步骤：重置所有悬挂方块输入（防止按键残留） ===
        // 使用 UUID 查找而非 player 位置——下车时玩家可能不在 SubLevel 空间内
        var mountData = PlayerMountTracker.getMountData(player);
        if (mountData != null) {
            resetSuspensionInputsByUUID(player.getServer(), mountData.subLevelUUID());
        } else {
            IACP.LOGGER.warn("[ServerMount] 下车时 mountData 为 null，走 fallback");
            resetSuspensionInputs(player); // fallback
        }

        // 清除挂载状态（自动清除 SubLevel 占用）
        PlayerMountTracker.unmount(player);

        // 恢复玩家
        PlayerMountTracker.restorePlayer(player);

        // 下车前刷新 SuspensionBE 缓存（为下次上车做好准备）
        if (mountData != null) {
            SuspensionTestBlockEntity.invalidateCachesInSubLevel(player.serverLevel(), mountData.subLevelUUID());
        }

        // 传送到安全位置
        Vec3 pos = findSafeDismountPosition(player);
        player.teleportTo(pos.x, pos.y, pos.z);

        // 通知客户端恢复第一人称（UUID/驾驶舱位置传空值，客户端忽略）
        ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));

        IACP.LOGGER.info("[ServerMount] 下车完成: player={}", player.getName().getString());
    }

    private static Vec3 findSafeDismountPosition(ServerPlayer player) {
        // 玩家实体在上车后未移动，原地就是安全的。
        // 不再使用 getLookAngle() 计算偏移——上车后鼠标转动会改变视线方向，
        // 导致"身后2格"指向空中，玩家下车后坠落。
        Vec3 playerPos = player.position();
        BlockPos center = BlockPos.containing(playerPos);
        ServerLevel level = player.serverLevel();

        // 直接检查玩家当前位置是否安全（双脚 + 头部在空气中）
        if (level.getBlockState(center).isAir() &&
                level.getBlockState(center.above()).isAir()) {
            return playerPos;
        }

        // 水平方向找安全位置
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos tryPos = center.offset(dx, 0, dz);
                if (level.getBlockState(tryPos).isAir() &&
                        level.getBlockState(tryPos.above()).isAir()) {
                    return Vec3.atCenterOf(tryPos);
                }
            }
        }

        // fallback：回到玩家位置
        return playerPos;
    }
}
