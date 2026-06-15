package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
     * @return true 如果方块被摧毁
     */
    public static boolean damageBlock(Level level, Vec3 hitPos, float damage) {
        if (level.isClientSide()) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel) level;

        BlockPos.MutableBlockPos hitBP = new BlockPos.MutableBlockPos();
        SubLevelAccess access = SableBlockHelper.findSubLevelAt(level, hitPos, hitBP);
        if (access == null) {
            return false;
        }

        return processDamage(level, serverLevel, access.getUniqueId(), hitBP.immutable(), hitPos, damage);
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

        int crackStage = Math.min(9, (int) (accumulated / THRESHOLD * 9));
        int breakerId = BREAKER_ID_BASE + (subUUID.hashCode() & 0xFFFF);
        serverLevel.destroyBlockProgress(breakerId, hitBP, crackStage);

        spawnDamageParticle(serverLevel, particlePos, crackStage / 9f);

        if (accumulated >= THRESHOLD) {
            destroyBlock(subUUID, hitBP, serverLevel);
            serverLevel.destroyBlockProgress(breakerId, hitBP, 10);
            return true;
        }

        return false;
    }

    // ==============================
    //  缓存管理
    // ==============================
    /**
     * 清理 SubLevel 的耐久缓存（下车/销毁时调用）。
     */
    public static void clear(UUID subUUID) {
        Map<BlockPos, Float> removed = CACHE.remove(subUUID);
        if (removed != null) {
            IACP.LOGGER.debug("[PartDamage] 已清理 [{}] ({} 条目)",
                    subUUID.toString().substring(0, 8), removed.size());
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

        BlockState currentState = level.getBlockState(pos);
        if (currentState.isAir()) {
            return;
        }

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
