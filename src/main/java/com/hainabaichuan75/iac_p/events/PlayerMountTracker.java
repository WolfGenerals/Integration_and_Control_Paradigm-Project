package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.AffiliationHelper;
import com.hainabaichuan75.iac_p.affiliation.AffiliationTag;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.index.ModBlocks;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.MountedStateS2CPacket;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家骑乘状态跟踪器（服务端）。 不创建实体，直接在每 tick 将玩家位置同步到 SubLevel 中心。
 * <p>
 * 注意：此类通过 {@code MinecraftForge.EVENT_BUS.register(PlayerMountTracker.class)}
 * 注册到游戏总线，因此不在 {@code @EventBusSubscriber} 中指定 bus 参数。
 */
public class PlayerMountTracker {

    public record MountData(
            UUID subLevelUUID,
            double cockpitLocalX, double cockpitLocalY, double cockpitLocalZ,
            double lastPoseX, double lastPoseZ
    ) {
    }

    /**
     * 驾驶舱一次扫描结果。由 {@link #scanSubLevelForCockpit(SubLevel, Level)} 返回， 替代原本
     * {@code containsCockpit + hasUniqueCockpit + findCockpitBlockInSubLevel}
     * 的三次独立扫描。
     *
     * @param hasCockpit SubLevel 内是否包含至少一个驾驶舱组
     * @param isUnique 驾驶舱结构是否唯一（一个组 + 一个下半截）
     * @param cockpitWorldPos 驾驶舱核心方块的世界坐标（底部中心），无驾驶舱时为 null
     * @param cockpitGroupId 驾驶舱组索引（ALL_COCKPIT_GROUPS 中的索引），无驾驶舱时为 -1
     * @param lowerHalfCount 核心下半截方块数量（用于诊断）
     */
    public record CockpitScanResult(
            boolean hasCockpit,
            boolean isUnique,
            @Nullable BlockPos cockpitWorldPos,
            int cockpitGroupId,
            int lowerHalfCount
    ) {
    }

    private static final Map<UUID, MountData> MOUNTED = new ConcurrentHashMap<>();

    /**
     * 通过 SubLevel UUID 查找当前骑乘该 SubLevel 的玩家。
     * 用于 CockpitBlockEntity 向骑乘者发送实时状态包。
     *
     * @param subLevelUUID SubLevel 的 UUID
     * @param level        服务端世界实例
     * @return 骑乘该 SubLevel 的玩家，如果无人骑乘则返回 null
     */
    @Nullable
    public static ServerPlayer getPlayerForSubLevel(UUID subLevelUUID, ServerLevel level) {
        UUID playerUUID = SUBLEVEL_OCCUPANTS.get(subLevelUUID);
        if (playerUUID == null) return null;
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        return server.getPlayerList().getPlayer(playerUUID);
    }

    // ====== 目标 2：SubLevel 占用追踪（谁占用了哪个 SubLevel） ======
    /**
     * SubLevel UUID → 占用该 SubLevel 的玩家 UUID
     */
    private static final Map<UUID, UUID> SUBLEVEL_OCCUPANTS = new ConcurrentHashMap<>();

    // ====== 公开 API ======
    public static boolean isMounted(ServerPlayer player) {
        return MOUNTED.containsKey(player.getUUID());
    }

    /**
     * 获取玩家的挂载数据（含 SubLevel UUID）。用于下车时定位 SubLevel。
     */
    public static MountData getMountData(ServerPlayer player) {
        return MOUNTED.get(player.getUUID());
    }

    // ====== 世界加载时清理（由 WorldLoadHandler 触发） ======
    /**
     * 清空所有骑乘状态，防止跨存档残留。
     */
    public static void onWorldLoad() {
        if (!MOUNTED.isEmpty()) {
            IACP.LOGGER.info("[PlayerMountTracker] 世界加载，清理 {} 条骑乘状态", MOUNTED.size());
            MOUNTED.clear();
        }
        if (!SUBLEVEL_OCCUPANTS.isEmpty()) {
            SUBLEVEL_OCCUPANTS.clear();
        }
    }

    private static final String MOUNTED_NBT_KEY = IACP.MODID + ".mounted";

    public static void mount(ServerPlayer player, UUID subLevelUUID,
            double cockpitLocalX, double cockpitLocalY, double cockpitLocalZ) {
        // 日志移除（性能优化）
        Vector3dc posePos = null;
        // 尝试获取初始位姿位置用于 lastPose
        ServerLevel level = player.serverLevel();
        if (level != null) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                SubLevel sl = container.getSubLevel(subLevelUUID);
                if (sl != null) {
                    posePos = sl.logicalPose().position();
                }
            }
        }
        double lpx = posePos != null ? posePos.x() : player.getX();
        double lpz = posePos != null ? posePos.z() : player.getZ();

        MOUNTED.put(player.getUUID(), new MountData(subLevelUUID,
                cockpitLocalX, cockpitLocalY, cockpitLocalZ, lpx, lpz));
        // 目标 2：标记 SubLevel 被此玩家占用
        SUBLEVEL_OCCUPANTS.put(subLevelUUID, player.getUUID());
        // 持久化标记：标记玩家当前处于骑乘状态，用于断线重连时检测
        player.getPersistentData().putBoolean(MOUNTED_NBT_KEY, true);

        // 目标 3（新增）：上车时设置无敌状态，仅指令级伤害可穿透
        // setInvulnerable(true) 配合 Entity.isInvulnerableTo() 的 BYPASSES_INVULNERABILITY 检查，
        // 使 /kill、/damage 等指令仍能正常作用，而一切非指令伤害被阻挡。
        player.setInvulnerable(true);

        // 注册载具主体归属到 AffiliationRegistry
        try {
            SubLevelContainer slc = SubLevelContainer.getContainer(level);
            if (slc != null) {
                SubLevel sl = slc.getSubLevel(subLevelUUID);
                if (sl != null) {
                    AffiliationHelper.registerVehicleBody(sl, player.getUUID(), AffiliationTag.FACTION_NEUTRAL);
                }
            }
        } catch (Exception e) {
            IACP.LOGGER.warn("[Mount] 注册载具归属失败（非致命）: {}", e.getMessage());
        }

        IACP.LOGGER.info("Player {} mounted SubLevel {}", player.getName().getString(), subLevelUUID);
    }

    public static MountData unmount(ServerPlayer player) {
        IACP.LOGGER.info("[ServerMount] unmount() 清理状态表: player={}", player.getName().getString());
        MountData data = MOUNTED.remove(player.getUUID());
        if (data != null) {
            // 清除载具归属
            AffiliationHelper.unregisterVehicleBody(data.subLevelUUID(), player.getUUID());
            // 目标 2：清除 SubLevel 占用
            SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
            // 部件损坏：清理该 SubLevel 的耐久缓存
            PartDamageCache.clear(data.subLevelUUID(), player.serverLevel());
            IACP.LOGGER.info("[ServerMount] 已清除 SubLevel {} 的占用与部件缓存", data.subLevelUUID());
        } else {
            IACP.LOGGER.warn("[ServerMount] unmount() 时 MOUNTED 表中无此玩家数据");
        }
        // 清除持久化标记
        player.getPersistentData().remove(MOUNTED_NBT_KEY);
        return data;
    }

    /**
     * 目标 2：检查 SubLevel 是否已被其他玩家占用。
     *
     * @param subLevelUUID 要检查的 SubLevel UUID
     * @param player 请求上车的玩家
     * @return true 如果 SubLevel 已被其他玩家占用
     */
    public static boolean isSubLevelOccupiedByOther(UUID subLevelUUID, ServerPlayer player) {
        UUID occupant = SUBLEVEL_OCCUPANTS.get(subLevelUUID);
        return occupant != null && !occupant.equals(player.getUUID());
    }

    /**
     * 检查持久化 NBT 中是否有残留的骑乘标记。 用于检测"上次骑乘退出但未正常下车"的陈旧状态。
     */
    public static boolean hasStaleMountTag(ServerPlayer player) {
        return player.getPersistentData().getBoolean(MOUNTED_NBT_KEY);
    }

    // ====== 目标 1 & 4：SubLevel 内驾驶舱检测 ======
    /**
     * 驾驶舱组定义 —— 使用直接方块实例比较，替代 Tag 方式。
     * <p>
     * 每组由一个或多个方块组成，同一组方块视为同一驾驶舱结构：
     * <ul>
     * <li>{@code GROUP_GENERAL} — 通用驾驶舱（CockpitBlock + CockpitUpperBlock）</li>
     * <li>{@code GROUP_CORE_0} — 初代核心 SeatBlock（单方块）</li>
     * </ul>
     * 后续添加新驾驶舱类型时，在此处注册新组。
     */
    private static final Set<Block> GROUP_GENERAL = Set.of(
            ModBlocks.COCKPIT.get(),
            ModBlocks.COCKPIT_UPPER.get()
    );
    private static final Set<Block> GROUP_CORE_0 = Set.of(
            ModBlocks.SEAT.get()
    );

    /**
     * 轻型线性座舱（4 方块 2×2 结构：种子块 + 延伸 + 上层 + 对角上层）
     */
    private static final Set<Block> GROUP_LIGHT_LINEAR = Set.of(
            ModBlocks.COCKPIT_LIGHT_LINEAR_0.get(),
            ModBlocks.COCKPIT_LIGHT_LINEAR_1.get(),
            ModBlocks.COCKPIT_LIGHT_LINEAR_2.get(),
            ModBlocks.COCKPIT_LIGHT_LINEAR_3.get()
    );

    /**
     * 所有驾驶舱组的集合（用于迭代）
     */
    private static final List<Set<Block>> ALL_COCKPIT_GROUPS = List.of(GROUP_GENERAL, GROUP_CORE_0, GROUP_LIGHT_LINEAR);

    /**
     * 被视为"核心下半截"的方块（一个组内只能有一个下半截）
     */
    private static final Set<Block> CORE_LOWER_HALVES = Set.of(
            ModBlocks.COCKPIT.get(),
            ModBlocks.SEAT.get(),
            ModBlocks.COCKPIT_LIGHT_LINEAR_0.get()
    );

    /**
     * 单次扫描 SubLevel，一次性获取驾驶舱检测所需的所有信息。
     * <p>
     * 替代原本
     * {@code findCockpitGroups + containsCockpit + hasUniqueCockpit + findCockpitBlockInSubLevel}
     * 的四次独立 chunk 扫描。一次遍历完成全部判断。
     * <p>
     * 使用
     * {@link SubLevelScanner#forEachBlock(SubLevel, Level, SubLevelScanner.BlockVisitor)}
     * 统一遍历逻辑，消除 boilerplate。
     */
    public static CockpitScanResult scanSubLevelForCockpit(SubLevel subLevel, Level level) {
        Set<Integer> groupIds = new HashSet<>();
        int[] lowerHalfCount = {0};
        BlockPos[] firstCockpitPos = {null};
        int[] firstGroupId = {-1};

        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            Block block = state.getBlock();

            // 检查驾驶舱组
            for (int g = 0; g < ALL_COCKPIT_GROUPS.size(); g++) {
                if (ALL_COCKPIT_GROUPS.get(g).contains(block)) {
                    groupIds.add(g);
                }
            }

            // 记录第一个驾驶舱核心方块的位置
            if (firstCockpitPos[0] == null && CORE_LOWER_HALVES.contains(block)) {
                firstCockpitPos[0] = worldPos;
                // 找到对应的组 ID
                for (int g = 0; g < ALL_COCKPIT_GROUPS.size(); g++) {
                    if (ALL_COCKPIT_GROUPS.get(g).contains(block)) {
                        firstGroupId[0] = g;
                        break;
                    }
                }
            }

            // 统计核心下半截
            if (CORE_LOWER_HALVES.contains(block)) {
                lowerHalfCount[0]++;
            }
        });

        boolean hasCockpit = !groupIds.isEmpty();
        boolean isUnique = hasCockpit
                && groupIds.size() == 1 // 只有一个驾驶舱组
                && lowerHalfCount[0] == 1;   // 只有一个下半截

        return new CockpitScanResult(hasCockpit, isUnique, firstCockpitPos[0], firstGroupId[0], lowerHalfCount[0]);
    }

    /**
     * 检查 SubLevel 内是否包含任何驾驶舱核心方块。 使用
     * {@link #scanSubLevelForCockpit(SubLevel, Level)} 单次扫描。
     */
    public static boolean containsCockpit(SubLevel subLevel, Level level) {
        return scanSubLevelForCockpit(subLevel, level).hasCockpit();
    }

    /**
     * 检查 SubLevel 内的驾驶舱结构是否唯一（无多余驾驶舱）。
     * <p>
     * 规则（使用直接方块实例比较 + 单次扫描）：
     * <ul>
     * <li>只能存在一个驾驶舱组</li>
     * <li>该组内只能有一个"核心下半截"方块</li>
     * </ul>
     */
    public static boolean hasUniqueCockpit(SubLevel subLevel, Level level) {
        return scanSubLevelForCockpit(subLevel, level).isUnique();
    }

    // ====== 每 tick 处理 ======
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 炮塔瞄准已移至 TurretTargetC2SPacket.handle() 直接驱动，不再依赖 tick

        if (MOUNTED.isEmpty()) {
            return;
        }

        // 轻度日志：每 100 tick ≈ 5 秒
        long gameTime = 0;

        // 遍历所有已挂载玩家
        for (var it = MOUNTED.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            UUID playerUUID = entry.getKey();
            MountData data = entry.getValue();

            MinecraftServer server = event.getServer();
            ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
            if (player == null) {
                IACP.LOGGER.warn("[ServerMount] 玩家 {} 断线，清理骑乘状态", playerUUID);
                // 玩家断线 → 先重置悬挂输入（保留刹车），再清理
                AffiliationHelper.unregisterVehicleBody(data.subLevelUUID(), playerUUID);
                ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
                SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
                // 注意：player == null 时无法清理玩家的 NBT 标记，
                // 但 onEntityJoinLevel() 中的 hasStaleMountTag 检查会处理残留标记
                ServerLevel anyLevel = server.getLevel(ServerLevel.OVERWORLD);
                PartDamageCache.clear(data.subLevelUUID(), anyLevel);
                it.remove();
                continue;
            }

            // 玩家死亡 → 自动下车 + 重置输入
            if (!player.isAlive()) {
                IACP.LOGGER.info("[ServerMount] 玩家 {} 死亡，自动下车", player.getName().getString());
                AffiliationHelper.unregisterVehicleBody(data.subLevelUUID(), player.getUUID());
                ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
                SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
                PartDamageCache.clear(data.subLevelUUID(), player.serverLevel());
                it.remove();
                forceDismountClient(server, player);
                continue;
            }

            ServerLevel level = player.serverLevel();
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                IACP.LOGGER.warn("[ServerMount] 玩家 {}: SubLevelContainer 不可达", player.getName().getString());
                ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
                SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
                PartDamageCache.clear(data.subLevelUUID(), player.serverLevel());
                it.remove();
                forceDismountClient(server, player);
                continue;
            }

            SubLevel subLevel = container.getSubLevel(data.subLevelUUID);
            if (subLevel == null) {
                IACP.LOGGER.warn("[ServerMount] 玩家 {}: SubLevel {} 已销毁，自动下车",
                        player.getName().getString(), data.subLevelUUID);
                AffiliationHelper.unregisterVehicleBody(data.subLevelUUID(), player.getUUID());
                SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
                PartDamageCache.clear(data.subLevelUUID(), player.serverLevel());
                it.remove();
                forceDismountClient(server, player);
                continue;
            }

            // === 玩家位置同步到驾驶舱方块底部 ===
            // 将玩家固定在驾驶舱方块底部中心（世界空间），随 SubLevel 物理运动。
            // 零碰撞箱确保无敌（爆炸、近战、投射物、生物仇恨均无效）。
            var logicalPose = subLevel.logicalPose();
            Vector3d worldCockpitPos = new Vector3d();
            logicalPose.transformPosition(
                    new Vector3d(data.cockpitLocalX(), data.cockpitLocalY(), data.cockpitLocalZ()),
                    worldCockpitPos);

            // 计算朝向：使用 pose 的旋转矩阵（不受车辆是否移动影响）
            Vector3d localOrigin = new Vector3d();
            Vector3d localForward = new Vector3d();
            logicalPose.transformPosition(new Vector3d(0, 0, 0), localOrigin);
            logicalPose.transformPosition(new Vector3d(0, 0, 1), localForward);
            double fdx = localForward.x - localOrigin.x;
            double fdz = localForward.z - localOrigin.z;
            if (fdx * fdx + fdz * fdz > 1e-8) {
                float yaw = (float) Math.toDegrees(Math.atan2(-fdx, fdz));
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                player.yBodyRot = yaw;
                player.yBodyRotO = yaw;
            }

            // 更新最后位姿位置
            Vector3dc posePos = logicalPose.position();
            MountData updatedData = new MountData(
                    data.subLevelUUID(),
                    data.cockpitLocalX(), data.cockpitLocalY(), data.cockpitLocalZ(),
                    posePos.x(), posePos.z()
            );
            MOUNTED.put(playerUUID, updatedData);

            // 设置位置（底部中心）
            player.setPos(worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z);

            // 速度归零 + 零碰撞箱 + 禁用物理/移动
            // 碰撞箱设在玩家当前位置（非世界原点），避免影响渲染拣选
            player.setDeltaMovement(Vec3.ZERO);
            player.setBoundingBox(new net.minecraft.world.phys.AABB(
                    worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z,
                    worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z));
            player.noPhysics = true;
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.getAbilities().setFlyingSpeed(0.0f);
            player.onUpdateAbilities();

            // 无敌保护：防止一切非指令伤害，Entity.isInvulnerableTo() 层拦截
            // setInvulnerable(true) 配合 BYPASSES_INVULNERABILITY 标签，
            // 使 /kill、/damage 指令仍能正常作用，非指令伤害全部阻挡
            player.setInvulnerable(true);

            // 额外碰撞保护：setPos() + 零碰撞箱使实体位置缓存随之刷新，
            // 确保物理引擎（含 Sable 的 SubLevelEntityCollision）完全忽略该实体
            // 轻度日志：每 100 tick 打印一次
            if (gameTime == 0) {
                gameTime = level.getGameTime();
            }
            // 每 tick 位置日志已移除（性能优化）
        }
    }

    /**
     * 强制将玩家踢出骑乘状态（服务端状态清理 + 通知客户端）。
     */
    private static void forceDismountClient(MinecraftServer server, ServerPlayer player) {
        IACP.LOGGER.warn("[ServerMount] forceDismountClient: player={}", player.getName().getString());
        // 前置：用 UUID 查找 SubLevel 重置悬挂输入（保留刹车）
        MountData data = MOUNTED.get(player.getUUID());
        if (data != null) {
            ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
        }
        // 清理挂载状态（清除 NBT 标记）
        unmount(player);
        // 通知客户端
        ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));
        // 恢复玩家状态
        restorePlayer(player);
    }

    /**
     * 恢复玩家到正常状态。
     */
    public static void restorePlayer(ServerPlayer player) {
        IACP.LOGGER.info("[ServerMount] restorePlayer: {}", player.getName().getString());
        player.noPhysics = false;
        player.setNoGravity(false);
        player.getAbilities().flying = false;
        player.getAbilities().setFlyingSpeed(0.05f);
        player.onUpdateAbilities();
        // 移除无敌状态（上车时设为 true），恢复可被伤害
        player.setInvulnerable(false);
    }

    // ====== 服务端生命周期清理 ======
    /**
     * 服务端启动/重启时清理所有残留骑乘状态。
     * <p>
     * 集成服务器重启时，{@link #MOUNTED} 和 {@link #SUBLEVEL_OCCUPANTS} 中的
     * ConcurrentHashMap 在类加载后仍是空的，无需清理。 但在某些热加载/reload 场景下可能有残留，保险起见在 server
     * starting 时清空。
     * <p>
     * 此方法通过 {@code MinecraftForge.EVENT_BUS} 注册在 {@link IACP#IACP()} 中， 响应
     * {@link net.neoforged.neoforge.event.server.ServerStartingEvent}。
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!MOUNTED.isEmpty()) {
            IACP.LOGGER.info("[ServerMount] 服务端启动，清理 {} 条残留骑乘状态", MOUNTED.size());
            MOUNTED.clear();
        }
        if (!SUBLEVEL_OCCUPANTS.isEmpty()) {
            SUBLEVEL_OCCUPANTS.clear();
        }
    }

    // ====== 重新进入世界时清理 ======
    /**
     * 当玩家实体加入世界时，检查是否处于过期的骑乘状态。
     * <p>
     * 处理两种场景：
     * <ol>
     * <li><b>多人重连</b>：MOUNTED 表中可能残留旧数据，验证 SubLevel 是否仍存在。</li>
     * <li><b>重新进入（单人/多人）</b>：MOUNTED 表已清空但玩家 NBT 中仍有残留标记， 此时玩家的
     * abilities（flying=true, noGravity=true, flyingSpeed=0） 被从 NBT
     * 恢复，需要清除。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }

        MountData data = MOUNTED.get(player.getUUID());

        if (data != null) {
            // 场景 1：MOUNTED 表中有记录 → 验证 SubLevel 是否仍存在
            ServerLevel level = player.serverLevel();
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || container.getSubLevel(data.subLevelUUID) == null) {
                IACP.LOGGER.info("Player {} re-joined but SubLevel {} is gone, auto-dismount",
                        player.getName().getString(), data.subLevelUUID);
                unmount(player); // 清理 MOUNTED + NBT 标记
                restorePlayer(player);
                ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));
                return;
            }
            // SubLevel 仍存在 → 保持骑乘
        }

        // 场景 2：NBT 中有残留骑乘标记但 MOUNTED 表中无记录
        // （例如单人游戏退出重进，集成服务器已重启）
        if (hasStaleMountTag(player)) {
            IACP.LOGGER.info("Player {} has stale mount NBT tag, restoring abilities",
                    player.getName().getString());
            player.getPersistentData().remove(MOUNTED_NBT_KEY);
            restorePlayer(player);
            ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));
        }
    }

    // ====== 玩家登出：强制下车 + 清理 NBT ======
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MountData data = MOUNTED.get(player.getUUID());
        if (data == null) {
            // 可能残留 NBT 标记，一并清理
            if (hasStaleMountTag(player)) {
                player.getPersistentData().remove(MOUNTED_NBT_KEY);
            }
            return;
        }
        IACP.LOGGER.info("[ServerMount] 玩家 {} 登出，强制下车", player.getName().getString());
        // 先重置悬挂输入（保留手刹），再清理状态
        MinecraftServer server = player.getServer();
        if (server != null) {
            ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
        }
        forceDismountServer(player);
    }

    /**
     * 强制玩家下车（服务端状态清理 + NBT + 通知客户端 + 恢复状态）。
     * <p>
     * 与 {@link #forceDismountClient} 的区别：此方法也恢复玩家移动能力。
     * 用于登出/维度切换等无法通过正常下车流程处理的场景。
     */
    private static void forceDismountServer(ServerPlayer player) {
        MountData data = MOUNTED.get(player.getUUID());
        if (data != null) {
            AffiliationHelper.unregisterVehicleBody(data.subLevelUUID(), player.getUUID());
            SUBLEVEL_OCCUPANTS.remove(data.subLevelUUID());
        }
        unmount(player);
        restorePlayer(player);
        ModNetworking.sendToPlayer(player, new MountedStateS2CPacket(false, new UUID(0, 0), 0.0, 0, 0, 0));
    }

    // ====== 维度切换：强制下车 ======
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isMounted(player)) {
            return;
        }
        IACP.LOGGER.info("[ServerMount] 玩家 {} 切换维度，强制下车", player.getName().getString());
        MountData data = MOUNTED.get(player.getUUID());
        if (data != null) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                ServerMountHandler.resetSuspensionInputsByUUID(server, data.subLevelUUID());
            }
        }
        forceDismountServer(player);
    }

    // ====== 骑乘时禁止交互（服务端强制） ======
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer sp && isMounted(sp)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer sp && isMounted(sp)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer sp && isMounted(sp)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer sp && isMounted(sp)) {
            event.setCanceled(true);
        }
    }
}
