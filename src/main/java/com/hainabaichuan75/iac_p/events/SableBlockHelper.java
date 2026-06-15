package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRegistry;
import com.hainabaichuan75.iac_p.affiliation.AffiliationTag;
import com.hainabaichuan75.iac_p.affiliation.RayPolicy;
import com.hainabaichuan75.iac_p.affiliation.RayType;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Sable SubLevel 方块查询工具。
 * <p>
 * 参考实现：
 * <ul>
 * <li>{@code tacz_aero_compat.SableBridge.clipSubLevelsInner()} — 射线穿越多
 * SubLevel 的最优命中</li>
 * <li>{@code AeronauticsHelper.isInSableSubLevel()} / {@code sableWorldToSubLevel()}
 * — 坐标变换</li>
 * </ul>
 * <p>
 * <b>核心发现</b>：Sable 提供两套查询 API——
 * <ul>
 * <li>{@code Sable.HELPER.getContaining(Level, Vector3dc)} ←
 * 浮点坐标，查空间索引，命中点表面时返回 null ❌</li>
 * <li>{@code Sable.HELPER.getContaining(Level, Vec3i)} ← 整数 BlockPos，查 plot
 * grid，返回 {@link SubLevelAccess} ✅</li>
 * </ul>
 * <p>
 * 本工具统一使用后者。
 */
public final class SableBlockHelper {

    private SableBlockHelper() {
    }

    /**
     * 在世界坐标处查找所属的 SubLevel 及其局部方块坐标。
     * <p>
     * <b>关键发现</b>：SubLevel 的方块存储在 plot chunk 坐标系中（坐标值很大，如 2000 万+），
     * 而武器射线命中的是物理世界坐标（如 500 左右）。两套坐标通过 SubLevel 的 {@code logicalPose()} 进行变换。
     * <p>
     * 因此不能用 {@code Sable.HELPER.getContaining(Level, BlockPos)} 直接查物理世界坐标 （它查的是
     * plot grid，只认 plot chunk 坐标）。
     * <p>
     * 正确做法（参考 {@code SableBridge.clipSubLevelsInner()}）：
     * <ol>
     * <li>{@code getAllIntersecting(Level, BoundingBox3dc)} 找物理 BB 包含 hitPos 的
     * SubLevel</li>
     * <li>{@code pose.transformPositionInverse(hitPos)} 变换到 SubLevel 局部空间</li>
     * <li>{@code BlockPos.containing(localPos)} 得到局部方块坐标</li>
     * </ol>
     *
     * @param level 世界
     * @param hitPos 命中位置（物理世界浮点坐标）
     * @param outPos 输出：SubLevel 局部空间中的 BlockPos（即 plot chunk 坐标）
     * @param dir 射线方向（单位向量），用于命中点为空气时的微调修正。可为 null 表示不做微调。
     * @return 非 null 表示找到了 SubLevel，outPos 为 SubLevel 局部坐标
     */
    @Nullable
    public static SubLevelAccess findSubLevelAt(Level level, Vec3 hitPos,
            BlockPos.MutableBlockPos outPos, @Nullable Vec3 dir) {
        BlockPos hitBP = BlockPos.containing(hitPos);

        // ================================================================
        //  方案：遍历所有 SubLevel → 物理 BB 判含 → pose 变换 → 验证方块
        //  参考 SableBridge.clipSubLevelsInner() 思路
        //  用 SubLevelContainer 而不是 getAllIntersecting，因为 getAllIntersecting
        //  可能因物理 BB 不精确包含命中点而遗漏（日志已证实）
        // ================================================================
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        SubLevelAccess bestAccess = null;
        BlockPos bestLocalPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) {
                continue;
            }

            // 1. 物理 BB 判含：检查 hitPos 是否在此 SubLevel 的物理世界 AABB 内
            var physBB = sl.boundingBox();
            if (physBB == null) {
                continue;
            }
            if (hitPos.x < physBB.minX() || hitPos.x > physBB.maxX()
                    || hitPos.y < physBB.minY() || hitPos.y > physBB.maxY()
                    || hitPos.z < physBB.minZ() || hitPos.z > physBB.maxZ()) {
                continue;
            }

            // 2. 获取 SubLevel 的 logicalPose（物理↔局部坐标变换矩阵）
            var pose = sl.logicalPose();
            if (pose == null) {
                continue;
            }

            // 3. 将物理世界坐标变换到 SubLevel 局部空间（即 plot chunk 坐标）
            Vec3 localPos = pose.transformPositionInverse(hitPos);
            BlockPos localBP = BlockPos.containing(localPos);

            // 4. 验证该局部坐标处有实际方块（非空气）
            //    如果精确位置为 air，沿射线方向微调 +0.01 后重试一次。
            //    这是因为射线命中在方块表面时因浮点精度可能取到空气面。
            //    不尝试相邻方块，避免"伤害扩散"到已摧毁的方块旁边。
            if (level.getBlockState(localBP).isAir() && dir != null) {
                Vec3 nudgedHitPos = hitPos.add(dir.scale(0.01));
                localPos = pose.transformPositionInverse(nudgedHitPos);
                localBP = BlockPos.containing(localPos);
            }
            if (level.getBlockState(localBP).isAir()) {
                continue;
            }

            double dist = localPos.distanceToSqr(Vec3.atCenterOf(localBP));
            if (dist < bestDistSq) {
                bestDistSq = dist;
                bestAccess = sl;
                bestLocalPos = localBP;
            }
        }

        if (bestAccess == null) {
            return null;
        }

        outPos.set(bestLocalPos);
        return bestAccess;
    }

    /**
     * 将世界坐标转换为 SubLevel 局部坐标。
     * <p>
     * 等效于 {@code AeronauticsHelper.sableWorldToSubLevel()}。
     *
     * @param pose SubLevel 的 logicalPose
     * @param worldPos 世界坐标
     * @return SubLevel 局部空间坐标
     */
    public static Vec3 worldToSubLevelSpace(Pose3dc pose, Vec3 worldPos) {
        return pose.transformPositionInverse(worldPos);
    }

    /**
     * 将 SubLevel 局部坐标转换为世界坐标。
     * <p>
     * 等效于 {@code AeronauticsHelper.sableSubLevelToWorld()}。
     *
     * @param pose SubLevel 的 logicalPose
     * @param localPos SubLevel 局部坐标
     * @return 世界坐标
     */
    public static Vec3 subLevelToWorldSpace(Pose3dc pose, Vec3 localPos) {
        return pose.transformPosition(localPos);
    }

    /**
     * 向后兼容版本（不带方向参数）。 当没有射线方向信息时，精确位置为 air 直接返回 null。
     */
    @Nullable
    public static SubLevelAccess findSubLevelAt(Level level, Vec3 hitPos, BlockPos.MutableBlockPos outPos) {
        return findSubLevelAt(level, hitPos, outPos, null);
    }

    /**
     * 纯射线追踪：沿射线查找最近的 SubLevel 方块命中。
     * <p>
     * 参考 {@code SableBridge.clipSubLevelsInner()} 实现。 仅跳过射线起点所在的
     * SubLevel（防炮管自伤），不做任何归属/排除判断。 调用方自行决定是否施加伤害。
     *
     * @param level 世界
     * @param from 射线起点（物理世界坐标）
     * @param to 射线终点（物理世界坐标）
     * @return 最近的方块命中结果，无命中返回 null
     */
    @Nullable
    public static BlockHitResult rayTraceSubLevels(Level level, Vec3 from, Vec3 to) {
        if (from.equals(to)) {
            return null;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        BlockHitResult bestHit = null;
        double bestDistSq = Double.MAX_VALUE;

        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) {
                continue;
            }

            var physBB = sl.boundingBox();
            if (physBB == null) {
                continue;
            }

            // 跳过射线起点所在的 SubLevel（防止枪管自伤）
            // ORIGIN_MARGIN=1.0 覆盖炮口偏移 0.5 格的情况
            final double M = 1.0;
            if (from.x >= physBB.minX() - M && from.x <= physBB.maxX() + M
                    && from.y >= physBB.minY() - M && from.y <= physBB.maxY() + M
                    && from.z >= physBB.minZ() - M && from.z <= physBB.maxZ() + M) {
                continue;
            }

            Vec3 dir = to.subtract(from).normalize();
            double maxDist = from.distanceTo(to);
            Vec3 bbHit = rayAABBIntersection(from, dir,
                    physBB.minX(), physBB.minY(), physBB.minZ(),
                    physBB.maxX(), physBB.maxY(), physBB.maxZ());
            if (bbHit == null) {
                continue;
            }
            if (from.distanceToSqr(bbHit) > maxDist * maxDist) {
                continue;
            }

            Pose3dc pose = sl.logicalPose();
            Vec3 localFrom = pose.transformPositionInverse(from);
            Vec3 localTo = pose.transformPositionInverse(to);
            if (localFrom.equals(localTo)) {
                continue;
            }

            var clipCtx = new net.minecraft.world.level.ClipContext(
                    localFrom, localTo,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    CollisionContext.empty());
            BlockHitResult localHit = level.clip(clipCtx);

            if (localHit != null && localHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                Vec3 worldHitLoc = pose.transformPosition(localHit.getLocation());
                double distSq = from.distanceToSqr(worldHitLoc);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestHit = new BlockHitResult(
                            worldHitLoc, localHit.getDirection(),
                            localHit.getBlockPos(), localHit.isInside());
                }
            }
        }
        return bestHit;
    }

    /**
     * 射线与 AABB 的相交检测（Slab 算法）。 与 {@code WeaponOverlay.rayAABBIntersection()}
     * 相同实现，用于快速剔除不相交的 SubLevel。
     */
    @Nullable
    private static Vec3 rayAABBIntersection(Vec3 origin, Vec3 dir,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double invDx = 1.0 / dir.x;
        double invDy = 1.0 / dir.y;
        double invDz = 1.0 / dir.z;

        double t1 = (minX - origin.x) * invDx;
        double t2 = (maxX - origin.x) * invDx;
        double t3 = (minY - origin.y) * invDy;
        double t4 = (maxY - origin.y) * invDy;
        double t5 = (minZ - origin.z) * invDz;
        double t6 = (maxZ - origin.z) * invDz;

        double tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        double tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        if (tmax < 0 || tmin > tmax) {
            return null;
        }

        double t = tmin < 0 ? tmax : tmin;
        return new Vec3(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t);
    }

    /**
     * 快速检查 BlockPos 是否在任何 SubLevel 内。 等效于
     * {@code AeronauticsHelper.isInSableSubLevel()}。
     */
    public static boolean isInAnySubLevel(Level level, BlockPos pos) {
        return Sable.HELPER.getContaining(level, pos) != null;
    }

    // ==================================================================
    //  RayType 感知的重载
    // ==================================================================
    /**
     * 沿射线查找所有可能命中的 SubLevel，使用 {@link RayType} 和 {@link AffiliationRegistry}
     * 自动决定排除/穿透策略。
     * <p>
     * 对观察者自身（viewerTag）的 SubLevel 适用 {@link RayPolicy#PENETRATE_AABB} 或
     * {@link RayPolicy#IGNORE}；对敌对的 SubLevel 适用 {@link RayPolicy#BLOCK} 或
     * {@link RayPolicy#DAMAGE}。
     * <p>
     * 调用方不需要手动构建排除集合。
     *
     * @param level 世界
     * @param from 射线起点（物理世界坐标）
     * @param to 射线终点（物理世界坐标）
     * @param rayType 射线类型
     * @param viewerId 观察者（发出射线的实体）所属的载具 SubLevel UUID，可为 null
     * @return 最近的方块命中结果（BlockPos 为 plot chunk 局部坐标，Location 为世界坐标）， 无命中或只有穿透的
     * SubLevel 时返回 null
     */
    @Nullable
    public static BlockHitResult rayTraceSubLevels(Level level, Vec3 from, Vec3 to,
            RayType rayType, @Nullable UUID viewerId) {
        if (from.equals(to)) {
            return null;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }

        AffiliationTag viewerTag = viewerId != null ? AffiliationRegistry.getAffiliation(viewerId) : null;

        SubLevelAccess bestAccess = null;
        BlockHitResult bestHit = null;
        double bestDistSq = Double.MAX_VALUE;

        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) {
                continue;
            }

            UUID slUUID = sl.getUniqueId();
            AffiliationTag targetTag = AffiliationRegistry.getAffiliation(slUUID);

            // 预先解析策略（缓存结果避免后续重复调用）
            RayPolicy policy = null;
            if (targetTag != null) {
                policy = AffiliationRegistry.resolvePolicy(rayType, viewerTag, targetTag);
                if (policy == RayPolicy.IGNORE) {
                    continue; // 完全跳过
                }
                // PENETRATE_AABB 需要特殊处理：不在这里跳过，而是在命中后判断
            } // targetTag == null → policy 保持 null，后续按 BLOCK 处理

            var physBB = sl.boundingBox();
            if (physBB == null) {
                continue;
            }

            // 快速剔除：AABB 判交
            Vec3 dir = to.subtract(from).normalize();
            double maxDist = from.distanceTo(to);
            Vec3 bbHit = rayAABBIntersection(from, dir,
                    physBB.minX(), physBB.minY(), physBB.minZ(),
                    physBB.maxX(), physBB.maxY(), physBB.maxZ());
            if (bbHit == null) {
                continue;
            }
            if (from.distanceToSqr(bbHit) > maxDist * maxDist) {
                continue;
            }

            Pose3dc pose = sl.logicalPose();

            // 将完整射线变换到 SubLevel 局部空间做 clip
            Vec3 localFrom = pose.transformPositionInverse(from);
            Vec3 localTo = pose.transformPositionInverse(to);
            if (localFrom.equals(localTo)) {
                continue;
            }

            var clipCtx = new net.minecraft.world.level.ClipContext(
                    localFrom, localTo,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    CollisionContext.empty());
            BlockHitResult localHit = level.clip(clipCtx);

            if (localHit != null && localHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                // 将命中位置变换回世界空间
                Vec3 worldHitLoc = pose.transformPosition(localHit.getLocation());
                double distSq = from.distanceToSqr(worldHitLoc);

                // 使用缓存的 policy 判断：PENETRATE_AABB 则穿透
                if (policy == RayPolicy.PENETRATE_AABB) {
                    continue; // 穿透整个 SubLevel（外层 AABB + 内部方块均穿透）
                }

                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestAccess = sl;
                    bestHit = new BlockHitResult(
                            worldHitLoc,
                            localHit.getDirection(),
                            localHit.getBlockPos(),
                            localHit.isInside());
                }
            }
        }

        return bestHit;
    }

    /**
     * {@link #rayTraceSubLevels(Level, Vec3, Vec3, Set)} 的便捷重载， 接受
     * {@link RayType} 和观察者所属载具 UUID，自动构建排除集合。
     * <p>
     * 向后兼容：此重载将 RayType 映射为旧式的排除集合。 新代码请使用
     * {@link #rayTraceSubLevels(Level, Vec3, Vec3, RayType, UUID)}。
     */
    @Nullable
    public static BlockHitResult rayTraceSubLevels(Level level, Vec3 from, Vec3 to,
            RayType rayType, @Nullable UUID viewerId,
            @Nullable Set<UUID> extraExclusions) {
        BlockHitResult result = rayTraceSubLevels(level, from, to, rayType, viewerId);
        // 如果 RayType 感知的结果不符合预期，用 extraExclusions 过滤
        // 目前 RayType 处理已足够，此参数保留供未来扩展
        return result;
    }
}
