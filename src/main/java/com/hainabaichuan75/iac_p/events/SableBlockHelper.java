package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
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
 *   <li>{@code tacz_aero_compat.SableBridge.clipSubLevelsInner()} — 射线穿越多 SubLevel 的最优命中</li>
 *   <li>{@code AeronauticsHelper.isInSableSubLevel()} / {@code sableWorldToSubLevel()} — 坐标变换</li>
 * </ul>
 * <p>
 * <b>核心发现</b>：Sable 提供两套查询 API——
 * <ul>
 *   <li>{@code Sable.HELPER.getContaining(Level, Vector3dc)} ← 浮点坐标，查空间索引，命中点表面时返回 null ❌</li>
 *   <li>{@code Sable.HELPER.getContaining(Level, Vec3i)} ← 整数 BlockPos，查 plot grid，返回 {@link SubLevelAccess} ✅</li>
 * </ul>
 * <p>
 * 本工具统一使用后者。
 */
public final class SableBlockHelper {

    private SableBlockHelper() {}

    /**
     * 在世界坐标处查找所属的 SubLevel 及其局部方块坐标。
     * <p>
     * <b>关键发现</b>：SubLevel 的方块存储在 plot chunk 坐标系中（坐标值很大，如 2000 万+），
     * 而武器射线命中的是物理世界坐标（如 500 左右）。两套坐标通过 SubLevel 的
     * {@code logicalPose()} 进行变换。
     * <p>
     * 因此不能用 {@code Sable.HELPER.getContaining(Level, BlockPos)} 直接查物理世界坐标
     * （它查的是 plot grid，只认 plot chunk 坐标）。
     * <p>
     * 正确做法（参考 {@code SableBridge.clipSubLevelsInner()}）：
     * <ol>
     *   <li>{@code getAllIntersecting(Level, BoundingBox3dc)} 找物理 BB 包含 hitPos 的 SubLevel</li>
     *   <li>{@code pose.transformPositionInverse(hitPos)} 变换到 SubLevel 局部空间</li>
     *   <li>{@code BlockPos.containing(localPos)} 得到局部方块坐标</li>
     * </ol>
     *
     * @param level  世界
     * @param hitPos 命中位置（物理世界浮点坐标）
     * @param outPos 输出：SubLevel 局部空间中的 BlockPos（即 plot chunk 坐标）
     * @return 非 null 表示找到了 SubLevel，outPos 为 SubLevel 局部坐标
     */
    @Nullable
    public static SubLevelAccess findSubLevelAt(Level level, Vec3 hitPos, BlockPos.MutableBlockPos outPos) {
        BlockPos hitBP = BlockPos.containing(hitPos);
        IACP.LOGGER.info("[SableBlockHelper] ▶ findSubLevelAt: hitPos={}, physicalBlockPos={}", hitPos, hitBP);

        // ================================================================
        //  方案：遍历所有 SubLevel → 物理 BB 判含 → pose 变换 → 验证方块
        //  参考 SableBridge.clipSubLevelsInner() 思路
        //  用 SubLevelContainer 而不是 getAllIntersecting，因为 getAllIntersecting
        //  可能因物理 BB 不精确包含命中点而遗漏（日志已证实）
        // ================================================================

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            IACP.LOGGER.info("[SableBlockHelper] ❌ SubLevelContainer.getContainer 返回 null");
            return null;
        }

        SubLevelAccess bestAccess = null;
        BlockPos bestLocalPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;

            // 1. 物理 BB 判含：检查 hitPos 是否在此 SubLevel 的物理世界 AABB 内
            var physBB = sl.boundingBox();
            if (physBB == null) continue;
            if (hitPos.x < physBB.minX() || hitPos.x > physBB.maxX() ||
                hitPos.y < physBB.minY() || hitPos.y > physBB.maxY() ||
                hitPos.z < physBB.minZ() || hitPos.z > physBB.maxZ()) {
                continue;
            }

            // 2. 获取 SubLevel 的 logicalPose（物理↔局部坐标变换矩阵）
            var pose = sl.logicalPose();
            if (pose == null) continue;

            // 3. 将物理世界坐标变换到 SubLevel 局部空间（即 plot chunk 坐标）
            Vec3 localPos = pose.transformPositionInverse(hitPos);
            BlockPos localBP = BlockPos.containing(localPos);

            // 4. 验证该局部坐标处有实际方块（非空气）
            BlockState state = level.getBlockState(localBP);
            if (state.isAir()) {
                IACP.LOGGER.info("[SableBlockHelper]    SubLevel {} 物理 BB 含 hitPos 但局部 {} 为空气，跳过",
                        sl.getUniqueId().toString().substring(0, 8), localBP);
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
            IACP.LOGGER.info("[SableBlockHelper] ❌ hitPos {} 不在任何 SubLevel 的物理 BB 内", hitPos);
            return null;
        }

        outPos.set(bestLocalPos);
        IACP.LOGGER.info("[SableBlockHelper] ✅ 命中 SubLevel={} @ localBP={} (物理世界 hitPos={})",
                bestAccess.getUniqueId().toString().substring(0, 8),
                bestLocalPos, hitPos);
        return bestAccess;
    }

    /**
     * 将世界坐标转换为 SubLevel 局部坐标。
     * <p>
     * 等效于 {@code AeronauticsHelper.sableWorldToSubLevel()}。
     *
     * @param pose   SubLevel 的 logicalPose
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
     * @param pose     SubLevel 的 logicalPose
     * @param localPos SubLevel 局部坐标
     * @return 世界坐标
     */
    public static Vec3 subLevelToWorldSpace(Pose3dc pose, Vec3 localPos) {
        return pose.transformPosition(localPos);
    }

    /**
     * 沿射线查找所有可能命中的 SubLevel，返回最近的方块命中结果。
     * <p>
     * 参考 {@code SableBridge.clipSubLevelsInner()} 实现：
     * <ol>
     *   <li>用 {@link SubLevelContainer#getAllSubLevels()} 遍历所有 SubLevel</li>
     *   <li>跳过射线起点所在的 SubLevel（防止自伤——枪管/载具自身的 SubLevel）</li>
     *   <li>跳过排除集合中的所有 SubLevel（载具及其所有衍生结构，如砂轮、避雷针）</li>
     *   <li>对其余 SubLevel 将射线变换到局部空间做 {@code Level.clip()}</li>
     *   <li>将命中位置变换回世界空间，取最近者</li>
     * </ol>
     * <p>
     * <b>核心原理</b>：武器射线返回的 AABB 表面交点无法通过 pose 变换精确映射到 SubLevel
     * 内部方块（变换后为空气）。正确做法是将完整射线变换到 SubLevel 局部空间后重新 clip，
     * 这样可以得到精确的 SubLevel 内部方块命中。
     * <p>
     * 相比旧方案（{@link #findSubLevelAt} 用单点判定），此方法不受 AABB 表面交点限制。
     *
     * @param level      世界
     * @param from       射线起点（物理世界坐标，如炮口位置）
     * @param to         射线终点（物理世界坐标）
     * @param exclusions 要排除的 SubLevel UUID 集合（载具及其所有衍生结构），可为 null
     * @return 最近的方块命中结果（BlockPos 为 plot chunk 局部坐标，Location 为世界坐标），
     *         若无命中返回 null
     */
    @Nullable
    public static BlockHitResult rayTraceSubLevels(Level level, Vec3 from, Vec3 to,
                                                    @Nullable Set<UUID> exclusions) {
        if (from.equals(to)) return null;

        // 1. 用 SubLevelContainer 遍历所有 SubLevel（比 getAllIntersecting 更可靠）
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;

        SubLevelAccess bestAccess = null;
        BlockHitResult bestHit = null;
        double bestDistSq = Double.MAX_VALUE;

        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;

            UUID slUUID = sl.getUniqueId();

            // ---- 排除检查 ----
            // A) 跳过排除集合中的所有 SubLevel（载具自身 + 砂轮 + 避雷针等衍生结构）
            if (exclusions != null && exclusions.contains(slUUID)) {
                continue;
            }

            var physBB = sl.boundingBox();
            if (physBB == null) continue;

            // B) 跳过射线起点所在 SubLevel（防止枪管/炮管自伤）
            //    如果 origin 在物理 BB 内部，说明射线是从这个 SubLevel 内部发出的，
            //    这个 SubLevel 就是"自己"（枪管/避雷针），不应受伤害
            if (from.x >= physBB.minX() && from.x <= physBB.maxX() &&
                from.y >= physBB.minY() && from.y <= physBB.maxY() &&
                from.z >= physBB.minZ() && from.z <= physBB.maxZ()) {
                continue;
            }

            // 快速剔除：检查射线是否经过此 SubLevel 的物理 AABB
            Vec3 dir = to.subtract(from).normalize();
            double maxDist = from.distanceTo(to);
            Vec3 bbHit = rayAABBIntersection(from, dir,
                    physBB.minX(), physBB.minY(), physBB.minZ(),
                    physBB.maxX(), physBB.maxY(), physBB.maxZ());
            if (bbHit == null) continue;
            if (from.distanceToSqr(bbHit) > maxDist * maxDist) continue;

            Pose3dc pose = sl.logicalPose();

            // 2. 将完整射线变换到 SubLevel 局部空间（plot chunk 坐标系）
            Vec3 localFrom = pose.transformPositionInverse(from);
            Vec3 localTo = pose.transformPositionInverse(to);
            if (localFrom.equals(localTo)) continue;

            // 3. 在 SubLevel 局部空间做射线检测
            // 使用 COLLIDER 仅检测方块碰撞箱，忽略流体
            var clipCtx = new net.minecraft.world.level.ClipContext(
                    localFrom, localTo,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    CollisionContext.empty()
            );
            BlockHitResult localHit = level.clip(clipCtx);

            if (localHit != null && localHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                // 4. 将命中位置变换回世界空间（blockPos 保持 plot chunk 局部坐标）
                Vec3 worldHitLoc = pose.transformPosition(localHit.getLocation());
                double distSq = from.distanceToSqr(worldHitLoc);

                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestAccess = sl;
                    bestHit = new BlockHitResult(
                            worldHitLoc,
                            localHit.getDirection(),
                            localHit.getBlockPos(),
                            localHit.isInside()
                    );
                }
            }
        }

        return bestHit;
    }

    /**
     * 射线与 AABB 的相交检测（Slab 算法）。
     * 与 {@code WeaponOverlay.rayAABBIntersection()} 相同实现，用于快速剔除不相交的 SubLevel。
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

        if (tmax < 0 || tmin > tmax) return null;

        double t = tmin < 0 ? tmax : tmin;
        return new Vec3(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t);
    }

    /**
     * 快速检查 BlockPos 是否在任何 SubLevel 内。
     * 等效于 {@code AeronauticsHelper.isInSableSubLevel()}。
     */
    public static boolean isInAnySubLevel(Level level, BlockPos pos) {
        return Sable.HELPER.getContaining(level, pos) != null;
    }
}
