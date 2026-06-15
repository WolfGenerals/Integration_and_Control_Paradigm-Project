package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.ComponentEntry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.WeaponFireC2SPacket;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 武器系统覆盖层 —— 上车时在屏幕中央渲染准星，并提供射线检测能力。
 *
 * <h3>功能</h3>
 * <ul>
 * <li>上车状态下，屏幕中央显示一个 2×2 白色像素方块作为准星</li>
 * <li>提供从当前摄像机位置出发的射线检测（最大 1000 格）： 命中物包括实体、SubLevel 物理结构、传统 Minecraft
 * 方块地形，均使用精确交点坐标</li>
 * <li>按下 B 键时，{@link ClientEvents} 调用 {@link #performRaycast()} 并显示结果</li>
 * </ul>
 */
@EventBusSubscriber(modid = IACP.MODID, value = Dist.CLIENT)
public class WeaponOverlay {

    /**
     * 射线最大检测距离（格）
     */
    public static final double MAX_RAY_DISTANCE = 1000.0;
    /**
     * 最小检测距离（格），低于此距离的命中视为载具自身，忽略
     */
    private static final double MIN_RAY_DISTANCE = 2.0;

    /**
     * 准星偏向最大锥角（度）。弹道在此锥角内被拉向玩家准星瞄准点， 用于掩饰炮塔旋转延迟带来的命中偏差。
     */
    private static final double AIM_BIAS_MAX_DEG = 5.0;

    /**
     * 最后一次射线检测的命中点世界坐标，{@code null} 表示未命中
     */
    private static Vec3 lastHitPos = null;

    /**
     * 最后一次命中目标的类型描述
     */
    private static String lastHitType = "";

    /**
     * 开火弹道数据（每个活跃炮台一条）
     */
    private static final List<TurretFireInstance> activeFires = new ArrayList<>();

    // ==================================================================
    /**
     * 单次开火弹道数据。
     */
    public static class TurretFireInstance {

        public final Vec3 origin;
        public final Vec3 hitPos;
        public int ticks;

        TurretFireInstance(Vec3 origin, Vec3 hitPos) {
            this.origin = origin;
            this.hitPos = hitPos;
            this.ticks = 2;
        }
    }

    /**
     * 获取当前所有活跃的弹道（用于渲染）。
     */
    public static List<TurretFireInstance> getActiveFires() {
        return activeFires;
    }

    /**
     * 对所有炮台执行一次开火：从炮口沿炮管方向发射射线， 存储弹道数据并发送伤害数据包。
     * <p>
     * <b>首选</b>通过 {@link ComponentRegistry} 获取炮塔列表（O(1) 查询），
     * <b>回退</b>到 chunk 全量遍历（客户端注册表未就绪时）。
     */
    public static void fireAllTurrets(Minecraft mc) {
        ClientSubLevel mountedSL = ClientMountHandler.getMountedClientSubLevel();
        if (mountedSL == null) {
            return;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) {
            return;
        }

        UUID mountedUUID = mountedSL.getUniqueId();
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);

        activeFires.clear();

        // ---- 首选：通过 ComponentRegistry 查询炮塔底座 ----
        var turretEntries = ComponentRegistry.getComponents(mountedUUID, ComponentRole.TURRET_BASE);
        if (!turretEntries.isEmpty()) {
            fireFromRegistry(mc, container, mountedUUID, partialTick, turretEntries);
            return;
        }

        // ---- 回退：chunk 全量遍历（注册表尚未就绪） ----
        IACP.LOGGER.debug("[WeaponOverlay] ComponentRegistry 无炮塔数据，回退到 chunk 扫描");
        fireFromScan(mc, mountedSL, container, mountedUUID, partialTick);
    }

    /**
     * 从注册表数据开火。
     */
    private static void fireFromRegistry(Minecraft mc, SubLevelContainer container,
            UUID mountedUUID, float partialTick,
            List<ComponentEntry> turretEntries) {
        for (var entry : turretEntries) {
            BlockEntity be = entry.blockEntity();
            if (!(be instanceof TurretBaseBlockEntity tb)) {
                continue;
            }
            if (!tb.isAssembled()) {
                continue;
            }
            fireSingleTurret(mc, container, mountedUUID, partialTick, tb);
        }
    }

    /**
     * 从 chunk 扫描开火（回退方案）。
     */
    private static void fireFromScan(Minecraft mc, ClientSubLevel mountedSL,
            SubLevelContainer container,
            UUID mountedUUID, float partialTick) {
        LevelPlot plot = mountedSL.getPlot();
        if (plot == null) {
            return;
        }

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic localBounds = chunk.getBoundingBox();
            if (localBounds == null || localBounds == BoundingBox3i.EMPTY) {
                continue;
            }

            int cx = chunk.getPos().getMinBlockX();
            int cz = chunk.getPos().getMinBlockZ();

            for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                    for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                        BlockPos wp = new BlockPos(x + cx, y, z + cz);
                        var be = mc.level.getBlockEntity(wp);
                        if (!(be instanceof TurretBaseBlockEntity tb)) {
                            continue;
                        }
                        if (!tb.isAssembled()) {
                            continue;
                        }
                        fireSingleTurret(mc, container, mountedUUID, partialTick, tb);
                    }
                }
            }
        }
    }

    /**
     * 单炮台开火逻辑。
     */
    private static void fireSingleTurret(Minecraft mc, SubLevelContainer container,
            UUID mountedUUID, float partialTick,
            TurretBaseBlockEntity tb) {
        UUID rodId = tb.getLightningRodSubLevelId();
        if (rodId == null) {
            return;
        }
        SubLevel sl = container.getSubLevel(rodId);
        if (!(sl instanceof ClientSubLevel csl) || csl.isRemoved()) {
            return;
        }
        var pose = csl.renderPose(partialTick);
        if (pose == null) {
            return;
        }

        // 炮口位置 + 炮管朝向（Z 轴正向旋转到世界空间）
        Vector3d fwd = new Vector3d(0, 0, 1);
        fwd.rotate(pose.orientation());
        Vec3 origin = new Vec3(
                pose.position().x() + fwd.x * 0.5,
                pose.position().y() + fwd.y * 0.5,
                pose.position().z() + fwd.z * 0.5);
        Vec3 dir = new Vec3(fwd.x, fwd.y, fwd.z);

        //如果想增大角度，可以调整 AIM_BIAS_MAX_DEG 的值
        // 准星偏向：在 ±5° 锥角内将弹道拉向玩家瞄准点
        // 掩饰炮塔旋转延迟带来的命中偏差，提升响应感
        dir = applyAimBias(dir, origin, lastHitPos);

        // 从炮口沿（可能经过偏修正的）方向做射线检测
        // 射线穿透所有 SubLevel 物理外壳，只停在方块/实体碰撞箱上。
        // 实际武器伤害服务端会使用 SableBlockHelper + AffiliationRegistry 重新验证，
        // 客户端结果仅用于弹道渲染视觉和初始瞄准坐标。
        Vec3 hitPos = raycastGeneric(mc, origin, dir, MAX_RAY_DISTANCE);

        // 存储弹道数据 + 发送伤害数据包
        activeFires.add(new TurretFireInstance(origin, hitPos));
        ModNetworking.sendToServer(new WeaponFireC2SPacket(origin.x, origin.y, origin.z, hitPos.x, hitPos.y, hitPos.z));
    }

    /**
     * 每 tick 衰减所有弹道的渲染计数。
     */
    public static void tickFireTrail() {
        activeFires.removeIf(fi -> {
            fi.ticks--;
            return fi.ticks <= 0;
        });
    }

    // ==================================================================
    //  准星渲染
    // ==================================================================
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientMountHandler.isMounted()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        GuiGraphics g = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // 屏幕中心
        int cx = sw / 2;
        int cy = sh / 2;

        // 绘制 2×2 白色像素方块作为准星
        // 使用白色在任意背景下均有良好的辨识度
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
    }

    // ==================================================================
    //  射线检测
    // ==================================================================
    /**
     * 从当前摄像机位置沿视线方向发射射线，检测命中物。 所有命中均使用射线与物体的精确交点坐标。
     *
     * @return 命中点的世界坐标，未命中返回射线端点
     */
    public static Vec3 performRaycast() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            lastHitPos = null;
            lastHitType = "";
            return null;
        }

        // 从摄像机位置出发（上车时为第三人称轨道摄像机）
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        var lookVec = camera.getLookVector();
        Vec3 look = new Vec3(lookVec.x, lookVec.y, lookVec.z);

        // 射线穿透所有 SubLevel 物理外壳，只停在方块/实体碰撞箱上
        return raycastGeneric(mc, from, look, MAX_RAY_DISTANCE);
    }

    /**
     * 通用射线检测：从任意起点+方向出发，返回最近命中点的世界坐标。
     * <p>
     * 所有命中均使用精确交点坐标：方块使用 {@link ClipContext.Block#COLLIDER}
     * （自动忽略无碰撞箱的植物/农作物/藤曼等）+ 树叶穿透循环； 实体使用 {@link AABB#clip(Vec3, Vec3)}
     * 计算射线与碰撞箱的交点。
     * <p>
     * <b>不检测 SubLevel 物理外壳 AABB</b>：射线直接穿透所有 SubLevel 的灰色/红色碰撞箱表面， 但遇到 SubLevel
     * 内部的实际方块（如栅栏、墙壁、驾驶舱）时会正常停止。 这与
     * {@link com.hainabaichuan75.iac_p.affiliation.RayPolicy#PENETRATE_AABB}
     * 的语义一致。
     *
     * @param mc Minecraft 实例
     * @param origin 射线起点
     * @param dir 射线方向（单位向量）
     * @param maxDist 最大检测距离
     * @return 命中点世界坐标，无命中时返回射线端点
     */
    private static Vec3 raycastGeneric(Minecraft mc, Vec3 origin, Vec3 dir, double maxDist) {
        Vec3 to = origin.add(dir.scale(maxDist));
        double closestDistSq = maxDist * maxDist;
        Vec3 closestHit = null;
        String hitType = "";

        // ---- 1. 方块地形检测（COLLIDER + 树叶穿透） ----
        // 使用 COLLIDER 而非 OUTLINE：碰撞箱为空的方块（花/草/农作物/藤曼等）
        // 会自然被射线忽略，只有有碰撞体积的方块才会被检测到。
        // 树叶虽体积碰撞但应被穿透，单独处理。
        BlockHitResult blockHit = mc.level.clip(
                new ClipContext(origin, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)
        );
        // 树叶穿透循环：遇到树叶时跳过，继续向后检测
        for (int pass = 0; pass < 32 && blockHit.getType() != HitResult.Type.MISS; pass++) {
            BlockPos hitPos = blockHit.getBlockPos();
            BlockState hitState = mc.level.getBlockState(hitPos);
            if (!isPenetrable(hitState)) {
                break; // 非穿透方块 → 有效命中
            }            // 是穿透方块 → 从命中点稍前位置继续向后检测
            Vec3 advance = blockHit.getLocation().add(dir.scale(0.1));
            blockHit = mc.level.clip(
                    new ClipContext(advance, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)
            );
        }
        if (blockHit.getType() != HitResult.Type.MISS) {
            double distSq = blockHit.getLocation().distanceToSqr(origin);
            if (distSq < closestDistSq && distSq > MIN_RAY_DISTANCE * MIN_RAY_DISTANCE) {
                closestDistSq = distSq;
                closestHit = blockHit.getLocation();
                hitType = "Block";
            }
        }

        // ---- 2. 实体检测（计算射线与实体碰撞箱的精确交点） ----
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.level,
                mc.player,
                origin,
                to,
                new AABB(origin, to).inflate(2.0),
                entity -> !entity.isSpectator() && entity.isPickable()
        );
        if (entityHit != null) {
            // 手动计算射线与实体碰撞箱的精确交点，避免 entityHit.getLocation()
            // 在某些实现路径下返回 entity.position()（脚部位置）而非实际交点。
            var bb = entityHit.getEntity().getBoundingBox();
            var optHit = bb.clip(origin, to);
            Vec3 preciseHit = optHit.orElse(entityHit.getLocation());
            double distSq = preciseHit.distanceToSqr(origin);
            if (distSq < closestDistSq && distSq > MIN_RAY_DISTANCE * MIN_RAY_DISTANCE) {
                closestDistSq = distSq;
                closestHit = preciseHit;
                hitType = "Entity (" + entityHit.getEntity().getType().getDescription().getString() + ")";
            }
        }

        // ---- 3. SubLevel 内部方块检测 ----
        // 将射线变换到每个 SubLevel 的局部空间做 clip，再将命中点变换回主世界坐标。
        // SubLevel 内方块的位置存储在其局部 plot chunk 坐标系中，mc.level.clip()
        // 返回的命中位置是局部坐标，必须通过 logicalPose 变换到主世界。
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
            if (container != null) {
                for (SubLevel sl : container.getAllSubLevels()) {
                    if (sl.isRemoved() || !(sl instanceof ClientSubLevel csl)) {
                        continue;
                    }
                    var pose = csl.logicalPose();
                    if (pose == null) {
                        continue;
                    }

                    // 变换射线起点/终点到 SubLevel 局部空间
                    Vec3 localFrom = pose.transformPositionInverse(origin);
                    Vec3 localTo = pose.transformPositionInverse(to);
                    if (localFrom.equals(localTo)) {
                        continue;
                    }

                    var clipCtx = new ClipContext(
                            localFrom, localTo,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            net.minecraft.world.phys.shapes.CollisionContext.empty());
                    BlockHitResult localHit = mc.level.clip(clipCtx);

                    if (localHit.getType() != HitResult.Type.MISS) {
                        // 将命中位置变换回主世界空间
                        Vec3 worldHitLoc = pose.transformPosition(localHit.getLocation());
                        double distSq = worldHitLoc.distanceToSqr(origin);
                        if (distSq < closestDistSq && distSq > MIN_RAY_DISTANCE * MIN_RAY_DISTANCE) {
                            closestDistSq = distSq;
                            closestHit = worldHitLoc;
                            hitType = "SubLevelBlock";
                        }
                    }
                }
            }
        } catch (Exception e) {
            IACP.LOGGER.warn("[WeaponOverlay] SubLevel 局部空间 clip 异常: {}", e.getMessage());
        }
        if (closestHit == null) {
            closestHit = to;
            hitType = "Air";
        }

        lastHitPos = closestHit;
        lastHitType = hitType;
        return closestHit;
    }

    /**
     * 获取最后一次射线检测的命中点坐标。
     *
     * @return 世界坐标，未命中返回 {@code null}
     */
    public static Vec3 getLastHitPos() {
        return lastHitPos;
    }

    /**
     * 获取最后一次命中目标的类型描述。
     */
    public static String getLastHitType() {
        return lastHitType;
    }

    // ==================================================================
    //  准星偏向锥角
    // ==================================================================
    /**
     * 对射线方向施加准星偏向偏移（最大 {@value #AIM_BIAS_MAX_DEG}°）。
     * <p>
     * 如果炮塔实际朝向与准星方向的偏差 ≤ 锥角，弹道完全指向准星瞄准点； 如果偏差 > 锥角，弹道限制到锥角边界。
     * <p>
     * 效果：炮塔尚未完全到位时，子弹已偏向准星，提升命中响应感。
     *
     * @param barrelDir 炮管实际朝向（单位向量）
     * @param origin 射线起点（炮口位置）
     * @param aimPos 玩家准星瞄准点（摄像机射线命中位置），为 {@code null} 时不修正
     * @return 修正后的射线方向（单位向量）
     */
    private static Vec3 applyAimBias(Vec3 barrelDir, Vec3 origin, Vec3 aimPos) {
        if (aimPos == null) {
            return barrelDir;
        }
        // 从炮口到准星点的理想方向
        Vec3 wishDir = aimPos.subtract(origin).normalize();

        // 计算炮管与理想方向的夹角
        double dot = Math.max(-1.0, Math.min(1.0, barrelDir.dot(wishDir)));
        double angleRad = Math.acos(dot);

        double maxRad = Math.toRadians(AIM_BIAS_MAX_DEG);
        if (angleRad <= maxRad) {
            // 偏差在锥角内 → 完全对准准星
            return wishDir;
        }

        // 偏差超出锥角 → 限制到锥角边界
        // 在 barrelDir → wishDir 之间线性插值后归一化
        double t = maxRad / angleRad;
        return barrelDir.add(wishDir.subtract(barrelDir).scale(t)).normalize();
    }

    // ==================================================================
    //  穿透名单
    // ==================================================================
    /**
     * 判断方块是否应被射线穿透（跳过）。
     * <p>
     * 配合 {@link ClipContext.Block#COLLIDER} 使用——碰撞箱为空的方块 （花/草/农作物/藤曼等）已被
     * COLLIDER 自然忽略，此方法仅处理 有碰撞箱但应穿透的特殊方块（如树叶）。
     */
    private static boolean isPenetrable(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT);
    }

    // ==================================================================
    //  工具方法
    // ==================================================================
    /**
     * 射线与 AABB 的相交检测（Slab 算法）。
     *
     * @param origin 射线起点
     * @param dir 射线方向（单位向量）
     * @param minX AABB 最小 X
     * @param minY AABB 最小 Y
     * @param minZ AABB 最小 Z
     * @param maxX AABB 最大 X
     * @param maxY AABB 最大 Y
     * @param maxZ AABB 最大 Z
     * @return 交点世界坐标，不相交返回 {@code null}
     */
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

        // 不相交
        if (tmax < 0 || tmin > tmax) {
            return null;
        }

        double t = tmin < 0 ? tmax : tmin;
        if (t > MAX_RAY_DISTANCE) {
            return null;
        }

        return new Vec3(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t);
    }
}
