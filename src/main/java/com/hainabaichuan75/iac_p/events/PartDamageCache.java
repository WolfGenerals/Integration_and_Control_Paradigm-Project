package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部件损坏管理器 — 简化版 5 击破坏系统。
 * <p>
 * <h3>核心方案：SableBridge 完整射线追踪</h3>
 * 武器射线返回的 hitPos 是 AABB 表面交点，无法通过 pose 变换精确映射到 SubLevel 内部方块。
 * 正确做法（参考 {@code SableBridge.clipSubLevelsInner()}）：
 * <ol>
 *   <li>将完整射线（from→to）变换到 SubLevel 局部空间</li>
 *   <li>在局部空间做 {@code level.clip()} 获得精确的方块面命中</li>
 *   <li>将命中位置变换回世界空间</li>
 * </ol>
 * 详见 {@code 5.1-关键技术要点/30-SableAPI查询方式：Vector3dc vs SubLevel遍历.md}。
 * <p>
 * <h3>设计</h3>
 * <ul>
 *   <li><b>射线追踪层</b>：{@link SableBlockHelper#rayTraceSubLevels(Level, Vec3, Vec3)}
 *       使用 SubLevelContainer 遍历 + 局部空间 clip</li>
 *   <li><b>回退方案</b>：{@link SableBlockHelper#findSubLevelAt(Level, Vec3, BlockPos.MutableBlockPos)}
 *       用于无射线起点的场景（如弹射物进入检测）</li>
 *   <li><b>简化阈值</b>：5 次命中摧毁一个方块（后续可替换为硬度×倍率等详细系统）</li>
 *   <li><b>裂纹同步</b>：{@link ServerLevel#destroyBlockProgress} → Minecraft 原版网络包</li>
 * </ul>
 */
public final class PartDamageCache {

    /** SubLevel UUID → {@code BlockPos → 已累计伤害} */
    private static final Map<UUID, Map<BlockPos, Float>> CACHE = new ConcurrentHashMap<>();

    /** 破坏阈值：5 次命中摧毁一个方块 */
    private static final float THRESHOLD = 5.0f;

    /** 伪实体 ID 偏移，用于 destroyBlockProgress 的 breakerId */
    private static final int BREAKER_ID_BASE = -0x1AC000;

    private PartDamageCache() {}

    // ==============================
    //  核心 API
    // ==============================

    /**
     * 带射线起点的部件损坏（推荐入口）。
     * <p>
     * 使用 SableBridge 完整射线追踪方案：将射线 from→hitPos+ε 变换到 SubLevel 局部空间后重新 clip，
     * 获得精确的 SubLevel 方块命中。
     * <p>
     * 自动排除射线起点所在的 SubLevel（防枪管自伤）以及排除集合中的所有衍生结构。
     *
     * @param level      服务端世界
     * @param from       射线起点（炮口/枪口世界坐标）
     * @param hitPos     武器命中位置（AABB 表面交点，用于扩展射线终点）
     * @param damage     本击伤害值
     * @param exclusions 要排除的 SubLevel UUID 集合（含载具自身 + 砂轮 + 避雷针等），可为 null
     * @return true 如果方块被摧毁
     */
    public static boolean damageBlock(Level level, Vec3 from, Vec3 hitPos, float damage,
                                       @Nullable Set<UUID> exclusions) {
        if (level.isClientSide()) {
            IACP.LOGGER.info("[PartDamage] ⛔ 客户端调用了 damageBlock，跳过");
            return false;
        }
        ServerLevel serverLevel = (ServerLevel) level;

        IACP.LOGGER.info("[PartDamage] ====== 部件损坏逻辑链 (射线追踪方案) =====");
        IACP.LOGGER.info("[PartDamage] ▶ Step 0: 收到伤害请求: from={}, hitPos={}, damage={}, exclusions={}",
                from, hitPos, damage, exclusions);

        // ================================================================
        //  步骤 1：用完整射线追踪找到精确的 SubLevel 方块命中
        //  参考 SableBridge.clipSubLevelsInner() 实现
        //  将射线从 from 延伸到 hitPos 再往前 5 格，确保穿透第一个方块表面
        // ================================================================
        IACP.LOGGER.info("[PartDamage] ▶ Step 1: 调用 SableBlockHelper.rayTraceSubLevels()");

        Vec3 dir = hitPos.subtract(from);
        double rayLen = dir.length();
        if (rayLen < 0.001) {
            IACP.LOGGER.info("[PartDamage] ❌ Step 1 失败: 射线长度过短 ({})", rayLen);
            return false;
        }
        dir = dir.normalize();
        // 将射线终点延伸到命中点之后 5 格，确保局部空间 clip 能穿过第一个方块表面
        Vec3 to = hitPos.add(dir.scale(5.0));

        BlockHitResult hitResult = SableBlockHelper.rayTraceSubLevels(level, from, to, exclusions);

        if (hitResult == null || hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            IACP.LOGGER.info("[PartDamage] ❌ Step 1 失败: rayTraceSubLevels 未命中任何 SubLevel 方块");
            // 回退：尝试用旧方案 findSubLevelAt
            return fallbackDamageBlock(level, hitPos, damage, serverLevel);
        }

        // hitResult.getBlockPos() 是 SubLevel 局部坐标（plot chunk 坐标），可直接用于 level.getBlockState()
        BlockPos hitBP = hitResult.getBlockPos();
        SubLevelAccess access = null;

        // 从 hitResult 反向查找 SubLevel（通过 plot chunk 坐标）
        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (var sl : container.getAllSubLevels()) {
                if (sl.isRemoved()) continue;
                // 直接用 plot chunk 坐标查 SubLevel
                var checkAccess = Sable.HELPER.getContaining(level, hitBP);
                if (checkAccess != null) {
                    access = checkAccess;
                    break;
                }
            }
        }

        if (access == null) {
            IACP.LOGGER.info("[PartDamage] ❌ Step 1 回退: 无法从 rayTrace 结果反向找到 SubLevel");
            return fallbackDamageBlock(level, hitPos, damage, serverLevel);
        }

        UUID subUUID = access.getUniqueId();
        IACP.LOGGER.info("[PartDamage] ✅ Step 1 成功: BlockPos={} (plot chunk), SubLevel UUID={}, 世界命中位置={}",
                hitBP, subUUID.toString().substring(0, 8), hitResult.getLocation());

        // ================================================================
        //  步骤 2~5：与旧方案相同的逻辑（检查方块、累计伤害、裂纹、摧毁）
        // ================================================================
        return processDamage(level, serverLevel, subUUID, hitBP, hitResult.getLocation(), damage);
    }

    /**
     * 无射线起点的部件损坏（弹射物入口 / 旧调用方兼容）。
     * <p>
     * 使用旧的 {@link SableBlockHelper#findSubLevelAt} 方案，通过 hitPos 查找 SubLevel。
     * 此方案受限于 AABB 表面交点无法精确映射的问题，推荐尽可能使用 {@link #damageBlock(Level, Vec3, Vec3, float)}。
     *
     * @param level  服务端世界
     * @param hitPos 命中位置（世界坐标）
     * @param damage 本击伤害值
     * @return true 如果方块被摧毁
     */
    public static boolean damageBlock(Level level, Vec3 hitPos, float damage) {
        if (level.isClientSide()) return false;
        return fallbackDamageBlock(level, hitPos, damage, (ServerLevel) level);
    }

    // ==============================
    //  回退方案（旧 findSubLevelAt）
    // ==============================

    /**
     * 回退方案：使用 findSubLevelAt 通过单点查找 SubLevel。
     * 适用于无射线起点的场景（如弹射物进入 SubLevelProjectileHandler）。
     */
    private static boolean fallbackDamageBlock(Level level, Vec3 hitPos, float damage, ServerLevel serverLevel) {
        IACP.LOGGER.info("[PartDamage] ▶ 回退方案: 使用 SableBlockHelper.findSubLevelAt()");
        BlockPos.MutableBlockPos hitBP = new BlockPos.MutableBlockPos();
        SubLevelAccess access = SableBlockHelper.findSubLevelAt(level, hitPos, hitBP);

        if (access == null) {
            IACP.LOGGER.info("[PartDamage] ❌ 回退方案失败: 未命中任何 SubLevel");
            return false;
        }

        IACP.LOGGER.info("[PartDamage] ✅ 回退方案成功: hitBP={}, SubLevel UUID={}",
                hitBP, access.getUniqueId().toString().substring(0, 8));

        return processDamage(level, serverLevel, access.getUniqueId(), hitBP.immutable(), hitPos, damage);
    }

    // ==============================
    //  共享处理逻辑（步骤 2~5）
    // ==============================

    /**
     * 共享的损坏处理逻辑：检查方块状态 → 累计伤害 → 裂纹同步 → 阈值摧毁。
     */
    private static boolean processDamage(Level level, ServerLevel serverLevel, UUID subUUID,
                                          BlockPos hitBP, Vec3 particlePos, float damage) {
        IACP.LOGGER.info("[PartDamage] ▶ Step 2: 检查方块状态 @ {}", hitBP);
        BlockState state = level.getBlockState(hitBP);
        IACP.LOGGER.info("[PartDamage]    BlockState = {} (isAir={}, destroySpeed={})",
                state.getBlock(), state.isAir(), state.getDestroySpeed(level, hitBP));

        if (state.isAir()) {
            IACP.LOGGER.info("[PartDamage] ❌ Step 2 失败: 方块是空气");
            return false;
        }
        if (state.getDestroySpeed(level, hitBP) < 0) {
            IACP.LOGGER.info("[PartDamage] ❌ Step 2 失败: 方块不可破坏 (destroySpeed < 0)");
            return false;
        }
        IACP.LOGGER.info("[PartDamage] ✅ Step 2 通过: 方块可破坏");

        // ================================================================
        //  步骤 3：累计伤害（惰性初始化）
        // ================================================================
        IACP.LOGGER.info("[PartDamage] ▶ Step 3: 累计伤害");
        Map<BlockPos, Float> subCache = CACHE.computeIfAbsent(subUUID, k -> {
            IACP.LOGGER.info("[PartDamage]    🆕 首次命中此 SubLevel，创建缓存");
            return new ConcurrentHashMap<>();
        });

        float accumulated = subCache.getOrDefault(hitBP, 0f) + damage;
        subCache.put(hitBP.immutable(), accumulated);

        IACP.LOGGER.info("[PartDamage] ✅ Step 3: 累计 {}/{} ({}%)",
                String.format("%.1f", accumulated), String.format("%.1f", THRESHOLD),
                String.format("%.0f", accumulated / THRESHOLD * 100));

        // ================================================================
        //  步骤 4：显示挖掘裂纹（0~9 级）
        // ================================================================
        int crackStage = Math.min(9, (int) (accumulated / THRESHOLD * 9));
        int breakerId = BREAKER_ID_BASE + (subUUID.hashCode() & 0xFFFF);
        serverLevel.destroyBlockProgress(breakerId, hitBP, crackStage);
        IACP.LOGGER.info("[PartDamage] ✅ Step 4: 裂纹等级 {} (breakerId={})", crackStage, breakerId);

        spawnDamageParticle(serverLevel, particlePos, crackStage / 9f);

        // ================================================================
        //  步骤 5：达到阈值 → 破坏
        // ================================================================
        if (accumulated >= THRESHOLD) {
            IACP.LOGGER.info("[PartDamage] ▶ Step 5: 阈值到达，执行破坏！");
            destroyBlock(subUUID, hitBP, serverLevel);
            serverLevel.destroyBlockProgress(breakerId, hitBP, 10);
            IACP.LOGGER.info("[PartDamage] ✅ 方块 {} 已被摧毁", hitBP);
            return true;
        }

        IACP.LOGGER.info("[PartDamage] ⏳ Step 5: 尚未达到阈值，还需 {} 伤害",
                String.format("%.1f", THRESHOLD - accumulated));
        return false;
    }

    // ==============================
    //  缓存管理
    // ==============================

    /** 清理 SubLevel 的耐久缓存（下车/销毁时调用）。 */
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

    /** 破坏方块：setBlock(AIR) → Sable 自动处理约束断裂。 */
    private static void destroyBlock(UUID subUUID, BlockPos pos, ServerLevel level) {
        Map<BlockPos, Float> subCache = CACHE.get(subUUID);
        if (subCache != null) subCache.remove(pos);

        BlockState currentState = level.getBlockState(pos);
        if (currentState.isAir()) return;

        level.levelEvent(2001, pos, Block.getId(currentState));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        IACP.LOGGER.debug("[PartDamage] 方块 {} 被摧毁", pos);
    }

    /** 生成受击暴击粒子。 */
    private static void spawnDamageParticle(ServerLevel level, Vec3 hitPos, float ratio) {
        int count = Math.max(1, Math.round(ratio * 6));
        level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.CRIT,
                hitPos.x, hitPos.y, hitPos.z,
                count, 0.15, 0.15, 0.15, 0.05
        );
    }
}
