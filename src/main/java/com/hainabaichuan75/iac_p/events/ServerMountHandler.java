package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.Config;
import com.hainabaichuan75.iac_p.IACPConfig;
import com.hainabaichuan75.iac_p.index.ModBlocks;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.MountedStateS2CPacket;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Set;

import java.util.Map;
import java.util.UUID;

/**
 * 服务端上车/下车处理逻辑。 遵循技术文档方案：不创建可骑乘实体，直接操纵玩家。
 * <p>
 * 变更记录（2026-05-31）：
 * <ul>
 * <li>目标1：上车前检查 SubLevel 内驾驶舱结构唯一性</li>
 * <li>目标2：上车前检查 SubLevel 是否已被其他玩家占用</li>
 * <li>目标4：射线命中宽泛化 — 命中任意包含驾驶舱的 SubLevel 即可</li>
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
        if (currentTick - lastTick < COOLDOWN_TICKS) {
            return;
        }
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
        if (hitResult.getType() == HitResult.Type.MISS) {
            return;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        Vector3d hitJomlPos = new Vector3d(hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5);

        // ====== 步骤 2：射线命中的方块必须在 SubLevel 内 ======
        SubLevel subLevel = Sable.HELPER.getContaining(level, hitJomlPos);
        if (subLevel == null) {
            return;
        }

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

        UUID subLevelUUID = subLevel.getUniqueId();

        // ====== 步骤 3（合并）：单次扫描获取驾驶舱全部信息 ======
        // 替代原来的 containsCockpit + hasUniqueCockpit + findCockpitBlockInSubLevel 三次独立扫描
        var cockpitScan = PlayerMountTracker.scanSubLevelForCockpit(subLevel, level);

        // 检查 SubLevel 是否包含驾驶舱
        if (!cockpitScan.hasCockpit()) {
            return;
        }

        BlockPos cockpitWorldPos = cockpitScan.cockpitWorldPos();
        if (cockpitWorldPos == null) {
            IACP.LOGGER.warn("[ServerMount] scanSubLevelForCockpit 报告有驾驶舱但位置为 null");
            return;
        }

        // ====== 步骤 3.1：驾驶舱结构完整性检查 ======
        BlockState cockpitState = level.getBlockState(cockpitWorldPos);
        if (cockpitState.is(ModBlocks.COCKPIT.get())) {
            BlockPos above = cockpitWorldPos.above();
            BlockState aboveState = level.getBlockState(above);
            if (!aboveState.is(ModBlocks.COCKPIT_UPPER.get())) {
                player.sendSystemMessage(Component.translatable("message.iac_p.cockpit_incomplete"));
                return;
            }
        }
        // SeatBlock 是单方块结构，无需完整性检查

        // ====== 步骤 3.2（目标1）：检查驾驶舱唯一性 ======
        // 直接使用 scanSubLevelForCockpit 的结果，无需再次扫描
        if (!cockpitScan.isUnique()) {
            player.sendSystemMessage(Component.translatable("message.iac_p.multiple_cockpits"));
            return;
        }

        // ====== 步骤 3.3（目标2）：检查 SubLevel 是否已被其他玩家占用 ======
        if (PlayerMountTracker.isSubLevelOccupiedByOther(subLevelUUID, player)) {
            player.sendSystemMessage(Component.translatable("message.iac_p.sublevel_occupied"));
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
     * 在 SubLevel 中查找驾驶舱核心方块的位置。 使用 {@link SubLevelScanner} 统一遍历。
     * <p>
     * 注：mountPlayer() 已改用 {@link PlayerMountTracker#scanSubLevelForCockpit}
     * 单次扫描， 此方法仅保留供其他外部调用方使用。
     */
    private static BlockPos findCockpitBlockInSubLevel(SubLevel subLevel, ServerLevel level) {
        final BlockPos[] result = {null};
        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            if (result[0] == null && (state.is(ModBlocks.COCKPIT.get()) || state.is(ModBlocks.SEAT.get()))) {
                result[0] = worldPos;
            }
        });
        return result[0];
    }

    /**
     * 重置 SubLevel 内所有悬挂测试方块的输入状态。 如果方块当前正在刹车，保留刹车（手刹逻辑）。
     * 在玩家下车前调用，防止按键输入残留导致载具自行运动。
     */
    public static void resetSuspensionInputs(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vector3d playerPos = new Vector3d(player.getX(), player.getY(), player.getZ());
        SubLevel subLevel = Sable.HELPER.getContaining(level, playerPos);
        if (subLevel == null) {
            return;
        }
        resetSuspensionInputsInSubLevel(subLevel, level);
    }

    /**
     * 直接重置指定 SubLevel 内所有悬挂方块的输入（保留刹车状态）。 用于断线/死亡等无法通过玩家对象找到 SubLevel 的场景。 使用
     * {@link SubLevelScanner} 统一遍历。
     */
    public static void resetSuspensionInputsInSubLevel(SubLevel subLevel, ServerLevel level) {
        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            if (be instanceof SuspensionTestBlockEntity suspension) {
                // 如果刹车踩着的，保留刹车（手刹效果）
                boolean keepBrake = suspension.isBraking();
                suspension.resetControlInput(keepBrake);
            } else if (be instanceof CockpitBlockEntity cockpit) {
                // 驾驶舱发动机回到怠速
                cockpit.resetEngineToIdle();
                // 重置原始油门方向输入（防止下车后残留油门信号）
                cockpit.setRawThrottleDirection(0);
            }
        });
    }

    /**
     * 通过 SubLevel UUID 查找并重置悬挂输入。 用于玩家断线时（player == null），无法通过玩家位置找到 SubLevel。
     */
    public static void resetSuspensionInputsByUUID(MinecraftServer server, UUID subLevelUUID) {
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            SubLevel subLevel = container.getSubLevel(subLevelUUID);
            if (subLevel != null) {
                resetSuspensionInputsInSubLevel(subLevel, level);
                return;
            }
        }
    }

    /**
     * 危险方块集合——玩家不应站在这些方块上或旁边。
     */
    private static final Set<Block> DANGEROUS_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.LAVA_CAULDRON,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY,
            Blocks.POWDER_SNOW
    );

    private static void dismountPlayer(ServerPlayer player) {
        var mountData = PlayerMountTracker.getMountData(player);
        UUID subLevelUUID = mountData != null ? mountData.subLevelUUID() : null;

        // === 步骤 1：检测手刹状态（在重置输入之前） ===
        boolean isBraking = false;
        SubLevel subLevel = null;
        if (subLevelUUID != null) {
            subLevel = findSubLevelByUUID(player.getServer(), subLevelUUID);
            if (subLevel != null) {
                isBraking = isSubLevelBraking(subLevel, player.serverLevel());
            }
        }

        // === 步骤 2：重置悬挂输入 ===
        if (mountData != null) {
            resetSuspensionInputsByUUID(player.getServer(), subLevelUUID);
        } else {
            IACP.LOGGER.warn("[ServerMount] 下车时 mountData 为 null，走 fallback");
            resetSuspensionInputs(player);
        }

        // === 步骤 3：清理挂载状态 ===
        PlayerMountTracker.unmount(player);
        PlayerMountTracker.restorePlayer(player);

        if (mountData != null) {
            SuspensionTestBlockEntity.invalidateCachesInSubLevel(player.serverLevel(), mountData.subLevelUUID());
        }

        // === 步骤 4：选择下车位置 ===
        Vec3 pos;
        if (isBraking) {
            // 手刹下车 → 到附近地面
            pos = findGroundDismountPosition(player, subLevel, subLevelUUID);
            IACP.LOGGER.info("[ServerMount] 手刹下车→地面: player={}, pos={}", player.getName().getString(), pos);
        } else if (Config.DISMOUNT_EXPERIMENTAL_DIRECT.get()) {
            // [实验性] 直传车顶 → 直接传送到载具最高方块的世界坐标
            pos = findDirectTopPosition(player, subLevel, subLevelUUID);
            IACP.LOGGER.info("[ServerMount] 实验性直传车顶: player={}, pos={}", player.getName().getString(), pos);
        } else {
            // 普通 F 下车 → 到载具顶部（带安全搜索）
            pos = findVehicleTopDismountPosition(player, subLevel, subLevelUUID);
            IACP.LOGGER.info("[ServerMount] 普通下车→车顶: player={}, pos={}", player.getName().getString(), pos);
        }

        player.teleportTo(pos.x, pos.y, pos.z);

        // === 步骤 5：通知客户端 ===
        ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));
        IACP.LOGGER.info("[ServerMount] 下车完成: player={}", player.getName().getString());
    }

    /**
     * 通过 UUID 查找 SubLevel。
     */
    private static SubLevel findSubLevelByUUID(MinecraftServer server, UUID subLevelUUID) {
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            SubLevel sl = container.getSubLevel(subLevelUUID);
            if (sl != null) {
                return sl;
            }
        }
        return null;
    }

    /**
     * 检测 SubLevel 内是否有悬挂方块正在刹车。 在重置输入前调用，用于判断手刹下车还是普通下车。
     */
    private static boolean isSubLevelBraking(SubLevel subLevel, ServerLevel level) {
        boolean[] braking = {false};
        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            if (be instanceof SuspensionTestBlockEntity sbe && sbe.isBraking()) {
                braking[0] = true;
            }
        });
        return braking[0];
    }

    // ====================================================================
    //  下车位置算法
    // ====================================================================
    /**
     * 手刹下车：从玩家当前位置出发，找到附近的安全地面位置。
     * <p>
     * 使用 {@link ServerPlayer#position()} 作为搜索锚点（每 tick 与驾驶舱世界位置同步）， 而非 SubLevel
     * 底层坐标系的固定世界坐标，避免下车位置偏离物理实体。
     * <p>
     * 搜索规则：
     * <ol>
     * <li>以玩家世界位置为中心，逐层扩大半径（1~5 格）</li>
     * <li>以玩家 Y 为起点，向下搜索固体方块地面</li>
     * <li>检查：脚下方块非危险/传送门，脚部和头部空间为空气</li>
     * <li>检查：两格空间内不包含 SubLevel 物理结构的世界空间方块</li>
     * </ol>
     */
    private static Vec3 findGroundDismountPosition(ServerPlayer player, SubLevel subLevel, UUID subLevelUUID) {
        ServerLevel level = player.serverLevel();
        Vec3 playerPos = player.position();

        int baseX = (int) Math.floor(playerPos.x);
        int baseZ = (int) Math.floor(playerPos.z);
        int baseY = (int) Math.floor(playerPos.y);

        // 收集 SubLevel 内所有方块的世界空间位置（用于碰撞检测）
        Set<BlockPos> subLevelBlocks = (subLevel != null)
                ? collectSubLevelBlockPositionsWorldSpace(subLevel, level)
                : java.util.Collections.emptySet();

        // 8 方向搜索，逐层扩大半径（1~5 格）
        int[][] directions = {
            {0, -1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0},
            {0, 1}, {1, 1}, {-1, 1}
        };

        for (int radius = 1; radius <= 5; radius++) {
            for (int[] dir : directions) {
                int dx = dir[0] * radius;
                int dz = dir[1] * radius;

                // 从玩家高度 +3 向下搜索地面（-10 格范围，适应下坡/悬崖）
                for (int dy = 3; dy >= -10; dy--) {
                    BlockPos footPos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                    BlockPos headPos = footPos.above();
                    BlockPos groundPos = footPos.below();

                    BlockState groundState = level.getBlockState(groundPos);
                    BlockState footState = level.getBlockState(footPos);
                    BlockState headState = level.getBlockState(headPos);

                    // 脚下必须是固体方块
                    if (groundState.isAir() || !groundState.isSolid()) {
                        continue;
                    }
                    // 脚下不能是危险方块或传送门
                    if (DANGEROUS_BLOCKS.contains(groundState.getBlock())) {
                        continue;
                    }
                    // 脚部和头部必须是空气
                    if (!footState.isAir()) {
                        continue;
                    }
                    if (!headState.isAir()) {
                        continue;
                    }
                    // 检查两格空间内不包含 SubLevel 物理结构
                    if (subLevelBlocks.contains(footPos) || subLevelBlocks.contains(headPos)) {
                        continue;
                    }

                    // 向上 5 格检测是否被车辆结构遮挡（防止复杂车体下车到车底）
                    boolean blockedAbove = false;
                    for (int up = 1; up <= 5; up++) {
                        if (subLevelBlocks.contains(footPos.offset(0, up, 0))) {
                            blockedAbove = true;
                            break;
                        }
                    }
                    if (blockedAbove) {
                        continue;
                    }

                    return Vec3.atBottomCenterOf(footPos);
                }
            }
        }

        // 都找不到：水平搜索回退
        return fallbackDismountPosition(player, level, subLevel, subLevelUUID);
    }

    /**
     * 普通 F 下车：找到载具物理结构顶部位置（世界空间坐标）。
     * <p>
     * 使用
     * {@link #collectSubLevelBlockPositionsWorldSpace(SubLevel, ServerLevel)} 将
     * SubLevel 方块位置从底层坐标系变换到世界空间，确保最高 Y 反映物理实体的实际位置， 而非 plot chunk 的固定世界坐标。
     * <p>
     * 规则：
     * <ol>
     * <li>收集 SubLevel 内所有方块的世界空间位置，找到最高 Y 和对应的 XZ</li>
     * <li>玩家站在最高方块上方 +1（脚部），头部在 +2</li>
     * <li>检查两格都在空气中，无危险方块</li>
     * <li>检查两格空间内不包含 SubLevel 物理结构</li>
     * <li>顶部被挡住时向四周偏移搜索</li>
     * </ol>
     */
    private static Vec3 findVehicleTopDismountPosition(ServerPlayer player, SubLevel subLevel, UUID subLevelUUID) {
        ServerLevel level = player.serverLevel();

        if (subLevel == null) {
            return fallbackDismountPosition(player, level, null, subLevelUUID);
        }

        // 收集 SubLevel 方块的世界空间位置（通过 logicalPose 变换）
        Set<BlockPos> subLevelBlocks = collectSubLevelBlockPositionsWorldSpace(subLevel, level);

        if (subLevelBlocks.isEmpty()) {
            return fallbackDismountPosition(player, level, subLevel, subLevelUUID);
        }

        // 在世界空间中寻找最高方块及其 XZ 坐标
        int maxY = Integer.MIN_VALUE;
        int topX = 0;
        int topZ = 0;
        for (BlockPos bp : subLevelBlocks) {
            if (bp.getY() > maxY) {
                maxY = bp.getY();
                topX = bp.getX();
                topZ = bp.getZ();
            }
        }

        int footY = maxY + 1;

        // 尝试站在最高方块正上方
        BlockPos footPos = new BlockPos(topX, footY, topZ);
        BlockPos headPos = footPos.above();

        if (isSafeTopPosition(level, footPos, headPos, subLevelBlocks)) {
            // 下沉 0.4 格：让玩家与车顶物理结构轻微重叠，
            // 类似泰坦陨落 2 的物理系统——车辆移动时带动玩家
            return new Vec3(topX + 0.5, footY - 0.2, topZ + 0.5);
        }

        // 顶部被挡住时，尝试向四周偏移（优先搜索开阔侧）
        int[][] offsets = {
            {0, -1}, {1, 0}, {-1, 0}, {0, 1},
            {1, -1}, {-1, -1}, {1, 1}, {-1, 1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        for (int[] off : offsets) {
            BlockPos tryFoot = new BlockPos(topX + off[0], footY, topZ + off[1]);
            BlockPos tryHead = tryFoot.above();
            if (isSafeTopPosition(level, tryFoot, tryHead, subLevelBlocks)) {
                return new Vec3(topX + off[0] + 0.5, footY - 0.4, topZ + off[1] + 0.5);
            }
        }

        // fallback：回到玩家当前位置搜索
        return fallbackDismountPosition(player, level, subLevel, subLevelUUID);
    }

    /**
     * [实验性] 直传车顶下车：跳过所有安全搜索，将玩家精确传送到载具最高方块的世界坐标正上方。
     * <p>
     * 直接从
     * {@link #collectSubLevelBlockPositionsWorldSpace(SubLevel, ServerLevel)}
     * 获取 SubLevel 方块的世界空间位置，找到最高方块，返回其正上方的站立位置。 不做空气/危险方块/偏移搜索。由
     * {@link Config#DISMOUNT_EXPERIMENTAL_DIRECT} 控制。
     */
    private static Vec3 findDirectTopPosition(ServerPlayer player, SubLevel subLevel, UUID subLevelUUID) {
        ServerLevel level = player.serverLevel();

        if (subLevel == null) {
            return fallbackDismountPosition(player, level, null, subLevelUUID);
        }

        Set<BlockPos> subLevelBlocks = collectSubLevelBlockPositionsWorldSpace(subLevel, level);

        if (subLevelBlocks.isEmpty()) {
            return fallbackDismountPosition(player, level, subLevel, subLevelUUID);
        }

        // 在世界空间中寻找最高方块
        int maxY = Integer.MIN_VALUE;
        int topX = 0;
        int topZ = 0;
        for (BlockPos bp : subLevelBlocks) {
            if (bp.getY() > maxY) {
                maxY = bp.getY();
                topX = bp.getX();
                topZ = bp.getZ();
            }
        }

        // 站在最高方块正上方偏下 0.01 格（+0.99 替代 +1.0），
        // 让玩家与车顶方块有微小碰撞重叠，防止掉入虚空
        return new Vec3(topX + 0.5, maxY - 1, topZ + 0.5);
    }

    /**
     * 检查车顶下车位置是否安全。 脚部和头部必须是空气，不能是危险方块，且不包含在 SubLevel 物理结构中。
     */
    private static boolean isSafeTopPosition(ServerLevel level, BlockPos footPos, BlockPos headPos,
            Set<BlockPos> subLevelBlocks) {
        BlockState footState = level.getBlockState(footPos);
        BlockState headState = level.getBlockState(headPos);

        if (!footState.isAir() || !headState.isAir()) {
            return false;
        }
        if (DANGEROUS_BLOCKS.contains(footState.getBlock())) {
            return false;
        }
        if (DANGEROUS_BLOCKS.contains(headState.getBlock())) {
            return false;
        }
        if (subLevelBlocks.contains(footPos) || subLevelBlocks.contains(headPos)) {
            return false;
        }

        return true;
    }

    /**
     *
     * 收集 SubLevel 内所有方块的世界空间坐标（通过 logicalPose 从底层坐标变换到世界空间）。
     * <p>
     * 相比 {@link #collectSubLevelBlockPositions(SubLevel, ServerLevel)} 直接读取
     * plot chunk 的固定世界坐标（底层坐标系），此方法正确处理了 SubLevel 物理移动后的世界空间位置，
     * 用于下车时准确判断候选位置是否与车辆物理结构重叠。
     */
    private static Set<BlockPos> collectSubLevelBlockPositionsWorldSpace(SubLevel subLevel, ServerLevel level) {
        Set<BlockPos> positions = new java.util.HashSet<>();
        if (subLevel == null) {
            return positions;
        }

        var pose = subLevel.logicalPose();
        if (pose == null) {
            return positions;
        }

        SubLevelScanner.forEachBlockState(subLevel, level, (worldPos, state) -> {
            if (!state.isAir()) {
                // 将底层坐标（plot chunk 固定世界坐标）变换到物理世界空间
                Vector3d localCenter = new Vector3d(
                        worldPos.getX() + 0.5,
                        worldPos.getY() + 0.5,
                        worldPos.getZ() + 0.5
                );
                Vector3d worldCenter = new Vector3d();
                pose.transformPosition(localCenter, worldCenter);

                BlockPos wp = BlockPos.containing(worldCenter.x(), worldCenter.y(), worldCenter.z());
                positions.add(wp);
                positions.add(wp.above()); // 两格空间都标记
            }
        });

        return positions;
    }

    /**
     * 收集 SubLevel 内所有方块的世界坐标（用于碰撞判断）。
     * <p>
     * 注意：此方法返回的是 plot chunk 底层坐标系的固定世界坐标， 不反映 SubLevel 物理移动后的实际位置。 推荐使用
     * {@link #collectSubLevelBlockPositionsWorldSpace(SubLevel, ServerLevel)}。
     */
    private static Set<BlockPos> collectSubLevelBlockPositions(SubLevel subLevel, ServerLevel level) {
        Set<BlockPos> positions = new java.util.HashSet<>();
        if (subLevel == null) {
            return positions;
        }

        SubLevelScanner.forEachBlockState(subLevel, level, (worldPos, state) -> {
            if (!state.isAir()) {
                positions.add(worldPos);
                positions.add(worldPos.above()); // 两格空间都标记
            }
        });

        return positions;
    }

    /**
     * 回退下车位置：水平搜索 + 竖直搜索安全位置。
     */
    private static Vec3 fallbackDismountPosition(ServerPlayer player, ServerLevel level,
            SubLevel subLevel, UUID subLevelUUID) {
        Vec3 playerPos = player.position();
        BlockPos center = BlockPos.containing(playerPos);

        // 直接检查当前位置
        if (isSafeBlock(level, center) && isSafeBlock(level, center.above())) {
            return playerPos;
        }

        // 收集 SubLevel 方块
        Set<BlockPos> subLevelBlocks = (subLevel != null)
                ? collectSubLevelBlockPositions(subLevel, level)
                : java.util.Collections.emptySet();

        // 水平 8 方向搜索（半径 1~3）
        int[][] offsets = {
            {0, -1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0},
            {0, 1}, {1, 1}, {-1, 1}
        };
        for (int radius = 1; radius <= 3; radius++) {
            for (int[] off : offsets) {
                BlockPos tryPos = center.offset(off[0] * radius, 0, off[1] * radius);
                BlockPos headPos = tryPos.above();
                if (isSafeBlock(level, tryPos) && isSafeBlock(level, headPos)
                        && !subLevelBlocks.contains(tryPos) && !subLevelBlocks.contains(headPos)) {
                    return Vec3.atCenterOf(tryPos);
                }
            }
        }

        // 向上搜索
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos tryPos = center.above(dy);
            BlockPos headPos = tryPos.above();
            if (isSafeBlock(level, tryPos) && isSafeBlock(level, headPos)) {
                return Vec3.atCenterOf(tryPos);
            }
        }

        return playerPos;
    }

    /**
     * 检查一个方块位置是否"安全"——即该方块是空气或非实心方块。
     */
    private static boolean isSafeBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        // 不能是危险方块或传送门
        if (DANGEROUS_BLOCKS.contains(block)) {
            return false;
        }
        return state.isAir() || !state.isSolid();
    }
}
