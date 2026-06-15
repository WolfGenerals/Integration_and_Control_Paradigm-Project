package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部件损坏管理器 — 简化版 5 击破坏系统。
 * <p>
 * <b>设计</b>：客户端射线检测（纯 Minecraft COLLIDER，无 SubLevel 感知）返回命中点世界坐标， 服务端通过
 * {@link SableBlockHelper#findSubLevelAt} 查找命中点所在的 SubLevel 及局部方块坐标， 累积伤害直到阈值（5
 * 击）后摧毁方块。不做归属排除，一视同仁。
 * <p>
 * <ul>
 * <li><b>简化阈值</b>：5 次命中摧毁一个方块</li>
 * <li><b>裂纹同步</b>：{@link ServerLevel#destroyBlockProgress} → 原版网络包</li>
 * </ul>
 */
public final class PartDamageCache {

    /**
     * SubLevel UUID → {@code BlockPos → 已累计伤害}
     */
    private static final Map<UUID, Map<BlockPos, Float>> CACHE = new ConcurrentHashMap<>();

    /**
     * 破坏阈值：5 次命中摧毁一个方块
     */
    private static final float THRESHOLD = 5.0f;

    /**
     * 伪实体 ID 偏移，用于 destroyBlockProgress 的 breakerId
     */
    private static final int BREAKER_ID_BASE = -0x1AC000;

    private PartDamageCache() {
    }

    // ==============================
    //  唯一 breakerId 生成
    // ==============================
    /**
     * 为每个 (SubLevel, BlockPos) 生成唯一的 breakerId。
     * <p>
     * Minecraft 的 {@link ServerLevel#destroyBlockProgress} 使用 breakerId 作为 Map
     * 键， 同一个 breakerId 只能跟踪一个方块的挖掘进度。 因此每个方块必须有自己的 breakerId，否则多个方块会互相覆盖裂纹。
     * <p>
     * 编码方案：SubLevel hash 8 bit + 坐标各 8 bit = 32 bit 在 SubLevel 的 ±128 格范围内保证唯一。
     */
    private static int makeBreakerId(UUID subUUID, BlockPos pos) {
        int h = subUUID.hashCode();
        return BREAKER_ID_BASE + (((h & 0xFF) << 24)
                | ((pos.getX() & 0xFF) << 16)
                | ((pos.getY() & 0xFF) << 8)
                | (pos.getZ() & 0xFF));
    }

    // ==============================
    //  核心 API
    // ==============================
    /**
     * 对命中位置的 SubLevel 方块造成部件损坏。
     * <p>
     * 使用 {@link SableBlockHelper#findSubLevelAt} 直接查找命中点所在的 SubLevel 方块， 不经过
     * SableBridge 完整射线追踪（客户端 <tt>raycastGeneric</tt>
     * 已经是纯 Minecraft COLLIDER 检测，返回的 hitPos 就是实际方块面命中点）。
     * <p>
     * <b>不做归属排除</b>：所有 SubLevel 方块一视同仁，自身载具部件也会受到伤害。
     *
     * @param level 服务端世界
     * @param hitPos 武器命中点世界坐标（来自客户端 raycastGeneric）
     * @param damage 本击伤害值
     * @param dir 射线方向（从起点到命中点的单位向量），用于命中点微调修正。可为 null。
     * @return true 如果方块被摧毁
     */
    public static boolean damageBlock(Level level, Vec3 hitPos, float damage, @javax.annotation.Nullable Vec3 dir) {
        if (level.isClientSide()) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel) level;

        BlockPos.MutableBlockPos hitBP = new BlockPos.MutableBlockPos();
        SubLevelAccess access = SableBlockHelper.findSubLevelAt(level, hitPos, hitBP, dir);
        if (access == null) {
            return false;
        }

        return processDamage(level, serverLevel, access.getUniqueId(), hitBP.immutable(), hitPos, damage);
    }

    /**
     * 向后兼容版本（不带方向参数）。
     */
    public static boolean damageBlock(Level level, Vec3 hitPos, float damage) {
        return damageBlock(level, hitPos, damage, null);
    }

    // ==============================
    //  SubLevel 局部坐标路径（旋转体无敌修复）
    // ==============================
    /**
     * 使用 SubLevel UUID + 局部坐标直接造成伤害。
     * <p>
     * 此方法绕过 {@link SableBlockHelper#findSubLevelAt} 的世界→局部坐标变换， 直接使用客户端在开火瞬间计算的
     * SubLevel 局部坐标（plot chunk 坐标）。 服务端仅用 SubLevel 当前 pose 将局部坐标转回世界坐标用于粒子效果，
     * 方块操作使用局部坐标直接进行。
     * <p>
     * <b>解决"旋转体无敌"</b>：当目标快速移动或旋转时，客户端在开火瞬间计算的命中点 世界坐标会在网络传输期间变得过时。局部坐标在
     * SubLevel 内部保持不变， 不受目标位移/旋转影响。
     *
     * @param level 服务端世界
     * @param subUUID 命中方块的 SubLevel UUID
     * @param localPos SubLevel 局部坐标（plot chunk 坐标，由客户端在开火时计算）
     * @param damage 本击伤害值
     * @param dir 射线方向（用于实体检测），可为 null
     * @return true 如果方块被摧毁
     */
    public static boolean damageBlockAtLocal(Level level, UUID subUUID, Vec3 localPos,
            float damage, @javax.annotation.Nullable Vec3 dir) {
        if (level.isClientSide()) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel) level;

        // 1. 验证局部坐标处有非空气方块
        BlockPos localBP = BlockPos.containing(localPos);
        if (level.getBlockState(localBP).isAir()) {
            return false;
        }

        // 2. 获取 SubLevel 当前 pose，将局部坐标转回世界坐标（用于粒子效果）
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        SubLevel sl = container.getSubLevel(subUUID);
        if (sl == null || sl.isRemoved()) {
            return false;
        }
        var pose = sl.logicalPose();
        if (pose == null) {
            return false;
        }
        Vec3 worldPos = SableBlockHelper.subLevelToWorldSpace(pose, localPos);

        // 3. 处理伤害（使用局部 BlockPos 操作方块，世界坐标用于粒子）
        return processDamage(level, serverLevel, subUUID, localBP, worldPos, damage);
    }

    /**
     * 不带方向参数的版本。
     */
    public static boolean damageBlockAtLocal(Level level, UUID subUUID, Vec3 localPos, float damage) {
        return damageBlockAtLocal(level, subUUID, localPos, damage, null);
    }

    // ==============================
    //  共享处理逻辑
    // ==============================
    private static boolean processDamage(Level level, ServerLevel serverLevel, UUID subUUID,
            BlockPos hitBP, Vec3 particlePos, float damage) {
        BlockState state = level.getBlockState(hitBP);

        if (state.isAir()) {
            return false;
        }
        if (state.getDestroySpeed(level, hitBP) < 0) {
            return false;
        }

        Map<BlockPos, Float> subCache = CACHE.computeIfAbsent(subUUID, k -> new ConcurrentHashMap<>());

        float accumulated = subCache.getOrDefault(hitBP, 0f) + damage;
        subCache.put(hitBP.immutable(), accumulated);

        // 写入持久化存储
        BlockDamageSavedData.get(serverLevel).setDamage(subUUID, hitBP, accumulated);

        int crackStage = Math.min(9, (int) (accumulated / THRESHOLD * 9));
        int breakerId = makeBreakerId(subUUID, hitBP);
        serverLevel.destroyBlockProgress(breakerId, hitBP, crackStage);

        spawnDamageParticle(serverLevel, particlePos, crackStage / 9f);

        if (accumulated >= THRESHOLD) {
            destroyBlock(subUUID, hitBP, serverLevel);
            return true;
        }

        return false;
    }

    // ==============================
    //  缓存管理
    // ==============================
    /**
     * 世界加载时从 NBT 恢复耐久缓存（由 WorldLoadHandler 触发）。
     * <p>
     * 同时重新发送所有裂纹进度到客户端，确保 chunk 重载后裂纹不丢失。
     */
    public static void onWorldLoad(Level level) {
        CACHE.clear();

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // 从持久化存储加载
        BlockDamageSavedData saved = BlockDamageSavedData.get(serverLevel);
        var allData = saved.getAllData();
        if (allData.isEmpty()) {
            return;
        }

        int totalBlocks = 0;
        for (var entry : allData.entrySet()) {
            UUID subUUID = entry.getKey();
            Map<BlockPos, Float> blockMap = entry.getValue();
            if (blockMap.isEmpty()) {
                continue;
            }

            Map<BlockPos, Float> subCache = new ConcurrentHashMap<>();
            for (var be : blockMap.entrySet()) {
                BlockPos pos = be.getKey();
                float damage = be.getValue();
                if (damage > 0) {
                    subCache.put(pos, damage);
                    // 重新发送裂纹到客户端
                    resendCrack(serverLevel, subUUID, pos, damage);
                }
            }
            if (!subCache.isEmpty()) {
                CACHE.put(subUUID, subCache);
                totalBlocks += subCache.size();
            }
        }

        IACP.LOGGER.info("[PartDamage] 世界加载，从 NBT 恢复 {} 个方块的破坏进度，已重发裂纹", totalBlocks);
    }

    /**
     * 清理 SubLevel 的耐久缓存（下车/销毁时调用）。 同时清理持久化存储。
     *
     * @param subUUID SubLevel UUID
     * @param level 服务端世界（用于获取 SavedData），为 null 时仅清理内存缓存
     */
    public static void clear(UUID subUUID, @javax.annotation.Nullable ServerLevel level) {
        Map<BlockPos, Float> removed = CACHE.remove(subUUID);
        if (removed != null) {
            // 清理持久化存储
            if (level != null) {
                BlockDamageSavedData.get(level).removeSubLevel(subUUID);
            }
            IACP.LOGGER.debug("[PartDamage] 已清理 [{}] ({} 条目)",
                    subUUID.toString().substring(0, 8), removed.size());
        }
    }

    /**
     * 向客户端重新发送一个方块当前的裂纹进度。
     */
    private static void resendCrack(ServerLevel level, UUID subUUID, BlockPos pos, float damage) {
        int crackStage = Math.min(9, (int) (damage / THRESHOLD * 9));
        int breakerId = makeBreakerId(subUUID, pos);
        level.destroyBlockProgress(breakerId, pos, crackStage);
    }

    // ==============================
    //  Chunk 监听：chunk 发送给客户端时重发裂纹
    // ==============================
    /**
     * 当服务端将 chunk 数据发送给客户端时，检查该 chunk 内是否有受损方块， 如果有则重新发送裂纹进度。
     * <p>
     * 解决：玩家远离载具后返回时，chunk 在客户端被重载，原有的 destroyBlockProgress 视觉丢失。
     */
    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (CACHE.isEmpty()) {
            return;
        }

        ChunkPos chunkPos = event.getPos();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkPos.getMaxBlockX();
        int chunkMaxZ = chunkPos.getMaxBlockZ();

        for (var subEntry : CACHE.entrySet()) {
            UUID subUUID = subEntry.getKey();
            for (var blockEntry : subEntry.getValue().entrySet()) {
                BlockPos pos = blockEntry.getKey();
                float damage = blockEntry.getValue();
                if (damage <= 0) {
                    continue;
                }

                // 检查该方块是否在正在发送的 chunk 范围内
                if (pos.getX() >= chunkMinX && pos.getX() <= chunkMaxX
                        && pos.getZ() >= chunkMinZ && pos.getZ() <= chunkMaxZ) {
                    ServerLevel level = event.getLevel();
                    resendCrack(level, subUUID, pos, damage);
                }
            }
        }
    }

    // ==============================
    //  内部方法
    // ==============================
    /**
     * 破坏方块：setBlock(AIR) → Sable 自动处理约束断裂。
     */
    private static void destroyBlock(UUID subUUID, BlockPos pos, ServerLevel level) {
        Map<BlockPos, Float> subCache = CACHE.get(subUUID);
        if (subCache != null) {
            subCache.remove(pos);
        }

        // 清理持久化存储
        BlockDamageSavedData.get(level).removeDamage(subUUID, pos);

        BlockState currentState = level.getBlockState(pos);
        if (currentState.isAir()) {
            return;
        }

        // 清除裂纹（使用与 processDamage 相同的 breakerId）
        level.destroyBlockProgress(makeBreakerId(subUUID, pos), pos, 10);
        level.levelEvent(2001, pos, Block.getId(currentState));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        IACP.LOGGER.debug("[PartDamage] 方块 {} 被摧毁", pos);
    }

    /**
     * 生成受击暴击粒子。
     */
    private static void spawnDamageParticle(ServerLevel level, Vec3 hitPos, float ratio) {
        int count = Math.max(1, Math.round(ratio * 6));
        level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.CRIT,
                hitPos.x, hitPos.y, hitPos.z,
                count, 0.15, 0.15, 0.15, 0.05
        );
    }
}
