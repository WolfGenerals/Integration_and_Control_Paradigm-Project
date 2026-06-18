package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.ComponentEntry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.hainabaichuan75.iac_p.index.ModSounds;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.WeaponFireC2SPacket;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
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
     * 霰弹枪最大检测距离（格）
     */
    private static final double SHOTGUN_MAX_DISTANCE = 100.0;

    /**
     * 霰弹枪每发弹丸数
     */
    private static final int SHOTGUN_PELLETS = 8;

    /**
     * 霰弹散布正态分布标准差（度），3σ ≈ 15°
     */
    private static final double SHOTGUN_SPREAD_SIGMA_DEG = 5.0;

    /**
     * 霰弹散布最大角度（度）
     */
    private static final double SHOTGUN_SPREAD_MAX_DEG = 10.0;

    /**
     * 霰弹枪枪口偏移（格）：将开火起始点沿枪管方向前移，避免自伤
     */
    private static final double SHOTGUN_MUZZLE_OFFSET = 0.6;

    /**
     * 炮塔枪口偏移（格）。
     * 炮塔射线起点在避雷针方块中心，需沿炮管方向前移才能越过炮管方块表面。
     * 与 {@link #SHOTGUN_MUZZLE_OFFSET} 分开配置以分别调优。
     */
    private static final double TURRET_MUZZLE_OFFSET = 0.6;

    /**
     * 开火速度偏移系数（秒）。
     * <p>
     * 将枪口发射点沿载具瞬时速度方向偏移 {@code velocity × 系数}，补偿从开火到伤害判定
     * 之间的位姿时间差。纯客户端偏移，服务端无需额外处理。
     * <p>
     * 调优建议：0.05 ≈ 1 游戏 tick，值越大高速偏移越明显。
     * 仅作用于水平速度分量，不影响垂直弹道。
     */
    private static final double FIRING_SPEED_OFFSET = 0.05;

    /**
     * 霰弹枪开火最小间隔（tick）
     */
    private static final int SHOTGUN_FIRE_COOLDOWN_TICKS = 5;

    /**
     * 霰弹枪上次开火的游戏刻
     */
    private static int lastShotgunFireTick = 0;

    /**
     * 霰弹枪随机数发生器
     */
    private static final Random SHOTGUN_RANDOM = new Random();

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
     * 世界加载时清空所有武器覆盖层缓存（由 WorldLoadHandler 触发）。
     * <p>
     * 清理 lastHitPos/lastHitType 防止跨世界坐标残留导致准星偏向异常， 清理 activeFires
     * 防止旧世界的弹道线条在新世界闪现。
     */
    public static void onWorldLoad() {
        clearAll();
    }

    /**
     * 下车时清空所有弹道特效，防止特效悬空。
     */
    public static void onDismount() {
        clearAll();
    }

    private static void clearAll() {
        lastHitPos = null;
        lastHitType = "";
        activeFires.clear();
    }

    // ==================================================================
    /**
     * 单次开火弹道数据。
     * <p>
     * origin 在开火时固定（避雷针方块中心世界坐标），渲染时不再跟随炮口移动。
     */
    public static class TurretFireInstance {

        /**
         * 起点世界坐标（开火时固定，不跟随炮口移动）
         */
        public final Vec3 origin;
        /**
         * 命中点世界坐标（固定不变）
         */
        public final Vec3 hitPos;
        public int ticks;

        TurretFireInstance(Vec3 origin, Vec3 hitPos) {
            this.origin = origin;
            this.hitPos = hitPos;
            this.ticks = 2;
        }
    }

    /**
     * SubLevel 命中信息：记录命中点在哪个 SubLevel 及其局部坐标。
     */
    private record SubLevelHit(UUID uuid, Vec3 localPos) {
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

        // ---- 采样载具瞬时速度（用于开火射线外推） ----
        Vec3 vehicleVel = sampleVehicleVelocity(mc, mountedSL);

        activeFires.clear();

        // ---- 首选：通过 ComponentRegistry 查询武器底座 ----
        var turretEntries = ComponentRegistry.getComponents(mountedUUID, ComponentRole.TURRET_BASE);
        var shotgunEntries = ComponentRegistry.getComponents(mountedUUID, ComponentRole.SHOTGUN_BASE);
        if (!turretEntries.isEmpty() || !shotgunEntries.isEmpty()) {
            fireFromRegistry(mc, container, mountedUUID, partialTick, vehicleVel, turretEntries, shotgunEntries);
            return;
        }

        // ---- 回退：chunk 全量遍历（注册表尚未就绪） ----
        fireFromScan(mc, mountedSL, container, mountedUUID, partialTick, vehicleVel);
    }

    /**
     * 采样载具 SubLevel 当前位置的瞬时速度向量。
     * <p>
     * 用于在开火数据包中携带速度信息，服务端据此做射线起点外推，
     * 补偿从客户端开火到服务端处理之间的位姿时间差。
     *
     * @return 速度向量（m/s），采样失败返回 {@link Vec3#ZERO}
     */
    private static Vec3 sampleVehicleVelocity(Minecraft mc, ClientSubLevel mountedSL) {
        if (mc.level == null) return Vec3.ZERO;
        try {
            // 使用第一个悬挂位置或 SubLevel 中心查询速度
            Vec3 queryPos;
            var suspList = ClientMountHandler.getSuspensionPositions();
            if (!suspList.isEmpty()) {
                BlockPos p = suspList.get(0);
                queryPos = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            } else {
                // 降级：用 SubLevel renderPose 将中心方块变换到世界坐标
                var renderPose = mountedSL.renderPose(0);
                if (renderPose == null) return Vec3.ZERO;
                var plot = mountedSL.getPlot();
                if (plot == null) return Vec3.ZERO;
                var centerBP = plot.getCenterBlock();
                if (centerBP == null) return Vec3.ZERO;
                var localCenter = new org.joml.Vector3d(
                        centerBP.getX() + 0.5, centerBP.getY() + 0.5, centerBP.getZ() + 0.5);
                var worldCenter = new org.joml.Vector3d();
                renderPose.transformPosition(localCenter, worldCenter);
                queryPos = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);
            }
            org.joml.Vector3d vel = dev.ryanhcode.sable.Sable.HELPER.getVelocity(
                    mc.level, new org.joml.Vector3d(queryPos.x, queryPos.y, queryPos.z));
            if (vel != null) {
                return new Vec3(vel.x(), vel.y(), vel.z());
            }
        } catch (Exception e) {
            // 采样失败不影响开火，只是无外推
        }
        return Vec3.ZERO;
    }

    /**
     * 从注册表数据开火（炮塔 + 霰弹枪）。
     */
    private static void fireFromRegistry(Minecraft mc, SubLevelContainer container,
            UUID mountedUUID, float partialTick, Vec3 vehicleVel,
            List<ComponentEntry> turretEntries,
            List<ComponentEntry> shotgunEntries) {
        for (var entry : turretEntries) {
            BlockEntity be = entry.blockEntity();
            if (be instanceof TurretBaseBlockEntity tb && tb.isAssembled()) {
                fireSingleTurret(mc, container, mountedUUID, partialTick, vehicleVel, tb);
            }
        }

        // 霰弹枪组冷却：统一在 tick 级别检查，确保多枪同时开火
        if (mc.level != null) {
            int currentTick = (int) mc.level.getGameTime();
            if (currentTick - lastShotgunFireTick < SHOTGUN_FIRE_COOLDOWN_TICKS) {
                return; // 霰弹枪组冷却中，跳过所有霰弹枪
            }
            lastShotgunFireTick = currentTick;
        }

        // 音效已迁移至服务端 WeaponFireC2SPacket.handle() 广播，
        // 确保所有玩家都能听到 + 使用 muzzle 位置获得空间感

        for (var entry : shotgunEntries) {
            BlockEntity be = entry.blockEntity();
            if (be instanceof ShotgunBaseBlockEntity sb && sb.isAssembled()) {
                fireSingleShotgun(mc, container, mountedUUID, partialTick, vehicleVel, sb);
            }
        }
    }

    /**
     * 从 chunk 扫描开火（回退方案）。
     */
    private static void fireFromScan(Minecraft mc, ClientSubLevel mountedSL,
            SubLevelContainer container,
            UUID mountedUUID, float partialTick, Vec3 vehicleVel) {
        LevelPlot plot = mountedSL.getPlot();
        if (plot == null) {
            return;
        }

        // 霰弹枪组冷却（回退路径也需检查）
        boolean shotgunReady = true;
        boolean sgSoundPlayed = false;
        if (mc.level != null) {
            int currentTick = (int) mc.level.getGameTime();
            if (currentTick - lastShotgunFireTick < SHOTGUN_FIRE_COOLDOWN_TICKS) {
                shotgunReady = false;
            }
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
                        if (be instanceof TurretBaseBlockEntity tb) {
                            if (tb.isAssembled()) {
                                fireSingleTurret(mc, container, mountedUUID, partialTick, vehicleVel, tb);
                            }
                        } else if (be instanceof ShotgunBaseBlockEntity sb) {
                            if (sb.isAssembled() && shotgunReady) {
                                if (!sgSoundPlayed) {
                                    lastShotgunFireTick = (int) mc.level.getGameTime();
                                    mc.level.playLocalSound(
                                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                            ModSounds.SHOTGUN_FIRE.get(),
                                            SoundSource.PLAYERS,
                                            1.5f, 1.0f, false);
                                    sgSoundPlayed = true;
                                }
                                fireSingleShotgun(mc, container, mountedUUID, partialTick, vehicleVel, sb);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析命中点所在的 SubLevel 及局部坐标。
     * <p>
     * 遍历所有 SubLevel，将命中点世界坐标变换到每个 SubLevel 的局部空间，
     * 检查该位置是否有非空气方块。找到即返回（优先返回第一个匹配的 SubLevel）。
     * <p>
     * 使用 {@code logicalPose()}（非插值）以匹配 {@link #raycastGeneric}
     * 中 SubLevel clip 所使用的位姿，确保坐标转换一致性。
     *
     * @param hitPos 世界坐标命中点
     * @param level 客户端世界
     * @return SubLevel 命中信息，非 SubLevel 命中返回 null
     */
    @Nullable
    private static SubLevelHit resolveSubLevelHit(Vec3 hitPos, net.minecraft.world.level.Level level) {
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            for (SubLevel sl : container.getAllSubLevels()) {
                if (sl.isRemoved() || !(sl instanceof ClientSubLevel)) {
                    continue;
                }
                // 使用 logicalPose（非插值），与 raycastGeneric 中的 SubLevel clip 保持一致
                var pose = sl.logicalPose();
                if (pose == null) {
                    continue;
                }
                Vec3 localPos = pose.transformPositionInverse(hitPos);
                BlockPos localBP = BlockPos.containing(localPos);
                if (!level.getBlockState(localBP).isAir()) {
                    return new SubLevelHit(sl.getUniqueId(), localPos);
                }
            }
        } catch (Exception e) {
            IACP.LOGGER.debug("[WeaponOverlay] resolveSubLevelHit 异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 单霰弹枪开火逻辑：发射 {@value #SHOTGUN_PELLETS} 根射线， 正态分布散布 ±{@value #SHOTGUN_SPREAD_MAX_DEG}°，
     * 最大射程 {@value #SHOTGUN_MAX_DISTANCE} 格。
     * <p>
     * 8 根射线共享同一个枪口原点，各自独立做射线检测、独立发数据包、独立渲染弹道。
     */
    private static void fireSingleShotgun(Minecraft mc, SubLevelContainer container,
            UUID mountedUUID, float partialTick, Vec3 vehicleVel,
            ShotgunBaseBlockEntity sb) {
        UUID rodId = sb.getLightningRodSubLevelId();
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

        var rodPlot = csl.getPlot();
        if (rodPlot == null) {
            return;
        }
        BlockPos localBP = rodPlot.getCenterBlock();
        var localCenter = new Vector3d(localBP.getX() + 0.5, localBP.getY() + 0.5, localBP.getZ() + 0.5);
        var worldCenter = pose.transformPosition(localCenter);
        Vec3 origin = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);

        // 炮管朝向（Z 轴正向旋转到世界空间）
        Vector3d fwd = new Vector3d(0, 0, 1);
        fwd.rotate(pose.orientation());
        Vec3 barrelDir = new Vec3(fwd.x, fwd.y, fwd.z);

        // 准星偏向：将炮管方向拉向玩家瞄准点
        barrelDir = applyAimBias(barrelDir, origin, lastHitPos);
        Vec3 dirNorm = barrelDir.normalize();

        // 枪口偏移：沿枪管方向前移，避免弹道起点在炮管内导致自伤
        Vec3 muzzleOrigin = origin.add(dirNorm.scale(SHOTGUN_MUZZLE_OFFSET));

        // 速度偏移：将发射点沿载具速度方向偏移，补偿开火到判定间的时间差
        muzzleOrigin = muzzleOrigin.add(vehicleVel.scale(FIRING_SPEED_OFFSET));

        // ---- 构建局部坐标系：以枪管方向为 Z 轴，计算垂直向量 ----
        Vec3 refUp;
        if (Math.abs(dirNorm.y) < 0.9) {
            refUp = new Vec3(0, 1, 0).cross(dirNorm).normalize();
        } else {
            refUp = new Vec3(1, 0, 0).cross(dirNorm).normalize();
        }
        Vec3 right = dirNorm.cross(refUp).normalize();
        Vec3 up = right.cross(dirNorm).normalize();

        // ---- 发射 8 颗弹丸 ----
        for (int i = 0; i < SHOTGUN_PELLETS; i++) {
            // Box-Muller 变换生成正态分布随机数
            double gauss1 = Math.sqrt(-2 * Math.log(SHOTGUN_RANDOM.nextDouble()))
                    * Math.cos(2 * Math.PI * SHOTGUN_RANDOM.nextDouble());
            double gauss2 = Math.sqrt(-2 * Math.log(SHOTGUN_RANDOM.nextDouble()))
                    * Math.cos(2 * Math.PI * SHOTGUN_RANDOM.nextDouble());

            // 钳位到 ±15°
            double spreadDegH = clamp(gauss1 * SHOTGUN_SPREAD_SIGMA_DEG,
                    -SHOTGUN_SPREAD_MAX_DEG, SHOTGUN_SPREAD_MAX_DEG);
            double spreadDegV = clamp(gauss2 * SHOTGUN_SPREAD_SIGMA_DEG,
                    -SHOTGUN_SPREAD_MAX_DEG, SHOTGUN_SPREAD_MAX_DEG);

            double spreadRadH = Math.toRadians(spreadDegH);
            double spreadRadV = Math.toRadians(spreadDegV);

            // 弹丸方向 = 枪管方向 + 水平偏移 + 垂直偏移 → 归一化
            Vec3 pelletDir = dirNorm
                    .add(right.scale(spreadRadH))
                    .add(up.scale(spreadRadV))
                    .normalize();

            // 从枪口偏移点沿弹丸方向射线检测（最大 100 格），跳过自己的枪管
            Vec3 hitPos = raycastGeneric(mc, muzzleOrigin, pelletDir, SHOTGUN_MAX_DISTANCE, rodId);

            // 弹道渲染（起点为枪口偏移点）
            activeFires.add(new TurretFireInstance(muzzleOrigin, hitPos));

            // 发数据包到服务端（起点统一为枪口偏移点）
            SubLevelHit subHit = resolveSubLevelHit(hitPos, mc.level);
            if (subHit != null) {
                ModNetworking.sendToServer(new WeaponFireC2SPacket(
                        muzzleOrigin.x, muzzleOrigin.y, muzzleOrigin.z,
                        hitPos.x, hitPos.y, hitPos.z,
                        subHit.uuid(), subHit.localPos(),
                        vehicleVel.x, vehicleVel.y, vehicleVel.z,
                        WeaponFireC2SPacket.WEAPON_SHOTGUN
                ));
            } else {
                ModNetworking.sendToServer(new WeaponFireC2SPacket(
                        muzzleOrigin.x, muzzleOrigin.y, muzzleOrigin.z,
                        hitPos.x, hitPos.y, hitPos.z,
                        vehicleVel.x, vehicleVel.y, vehicleVel.z,
                        WeaponFireC2SPacket.WEAPON_SHOTGUN
                ));
            }
        }
    }

    /**
     * 单炮台开火逻辑。
     * <p>
     * 起点直接使用避雷针方块中心世界坐标（无半格偏移、无速度偏移）， 开火时固定存入
     * {@link TurretFireInstance#origin}，渲染不再跟随炮口移动。
     * <p>
     * <b>局部坐标修复</b>：如果命中点在 SubLevel 上，将命中点转换到 SubLevel 局部坐标后发送给服务端， 服务端用当前 pose
     * 转回世界坐标，解决目标移动/旋转时的命中失效问题（"旋转体无敌"）。
     */
    private static void fireSingleTurret(Minecraft mc, SubLevelContainer container,
            UUID mountedUUID, float partialTick, Vec3 vehicleVel,
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

        // 炮口位置：从 SubLevel 内部 plot chunk 坐标转换到主世界坐标
        var rodPlot = csl.getPlot();
        if (rodPlot == null) {
            return;
        }
        BlockPos localBP = rodPlot.getCenterBlock();
        // 避雷针方块中心 → 主世界空间
        var localCenter = new Vector3d(localBP.getX() + 0.5, localBP.getY() + 0.5, localBP.getZ() + 0.5);
        var worldCenter = pose.transformPosition(localCenter);
        Vec3 blockCenter = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);

        // 炮管朝向（Z 轴正向旋转到世界空间）
        Vector3d fwd = new Vector3d(0, 0, 1);
        fwd.rotate(pose.orientation());
        Vec3 dir = new Vec3(fwd.x, fwd.y, fwd.z);

        // 准星偏向：在 ±5° 锥角内将弹道拉向玩家瞄准点
        dir = applyAimBias(dir, blockCenter, lastHitPos);
        Vec3 dirNorm = dir.normalize();

        // 枪口偏移：沿炮管方向前移，避免射线起点在方块内部挡弹
        Vec3 muzzleOrigin = blockCenter.add(dirNorm.scale(TURRET_MUZZLE_OFFSET));

        // 速度偏移：将发射点沿载具速度方向偏移，补偿开火到判定间的时间差
        muzzleOrigin = muzzleOrigin.add(vehicleVel.scale(FIRING_SPEED_OFFSET));

        // 从枪口起点沿修正方向做射线检测
        Vec3 hitPos = raycastGeneric(mc, muzzleOrigin, dir, MAX_RAY_DISTANCE);

        // 固定起点 + 命中点（弹道渲染使用枪口偏移点）
        activeFires.add(new TurretFireInstance(muzzleOrigin, hitPos));

        // ---- 判断是否命中 SubLevel 方块，转换为局部坐标发送 ----
        SubLevelHit subHit = resolveSubLevelHit(hitPos, mc.level);
        if (subHit != null) {
            // 命中 SubLevel：发送局部坐标 + UUID + 速度外推
            ModNetworking.sendToServer(new WeaponFireC2SPacket(
                    muzzleOrigin.x, muzzleOrigin.y, muzzleOrigin.z,
                    hitPos.x, hitPos.y, hitPos.z,
                    subHit.uuid(), subHit.localPos(),
                    vehicleVel.x, vehicleVel.y, vehicleVel.z,
                    WeaponFireC2SPacket.WEAPON_TURRET
            ));
        } else {
            // 非 SubLevel 命中（地形/实体等）：发送世界坐标 + 速度外推
            ModNetworking.sendToServer(new WeaponFireC2SPacket(
                    muzzleOrigin.x, muzzleOrigin.y, muzzleOrigin.z,
                    hitPos.x, hitPos.y, hitPos.z,
                    vehicleVel.x, vehicleVel.y, vehicleVel.z,
                    WeaponFireC2SPacket.WEAPON_TURRET
            ));
        }
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
        return raycastGeneric(mc, origin, dir, maxDist, null);
    }

    /**
     * 通用射线检测 —— 可跳过指定 SubLevel（用于避免子弹打中自己的枪管）。
     */
    private static Vec3 raycastGeneric(Minecraft mc, Vec3 origin, Vec3 dir, double maxDist,
            @Nullable UUID skipSubLevel) {
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
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closestHit = blockHit.getLocation();
                hitType = "Block";
            }
        }

        // ---- 2. 实体检测（计算射线与实体碰撞箱的精确交点） ----
        // 使用 ProjectileUtil 快速查找最近的实体（带拾取半径 0.3 格宽容度），
        // 但命中点始终用实际碰撞箱计算，避免 entityHit.getLocation() 返回脚部位置。
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.level,
                mc.player,
                origin,
                to,
                new AABB(origin, to).inflate(2.0),
                entity -> !entity.isSpectator() && entity.isPickable()
        );
        if (entityHit != null) {
            Vec3 preciseHit = computeEntityHitPoint(entityHit.getEntity().getBoundingBox(),
                    origin, to, dir, entityHit.getLocation());
            if (preciseHit != null) {
                double distSq = preciseHit.distanceToSqr(origin);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closestHit = preciseHit;
                    hitType = "Entity (" + entityHit.getEntity().getType().getDescription().getString() + ")";
                }
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

                    // 跳过发射自己的枪管 SubLevel，防止子弹穿模击中自身
                    if (skipSubLevel != null && sl.getUniqueId().equals(skipSubLevel)) {
                        continue;
                    }

                    // AABB 快速剔除：检查射线是否经过此 SubLevel 的物理世界 AABB
                    var physBB = sl.boundingBox();
                    if (physBB == null) {
                        continue;
                    }
                    Vec3 bbHit = rayAABBIntersection(origin, dir,
                            physBB.minX(), physBB.minY(), physBB.minZ(),
                            physBB.maxX(), physBB.maxY(), physBB.maxZ());
                    if (bbHit == null) {
                        continue; // 射线不经过此 SubLevel
                    }
                    if (origin.distanceToSqr(bbHit) > maxDist * maxDist) {
                        continue; // 交点在最大检测距离之外
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
                        if (distSq < closestDistSq) {
                            closestDistSq = distSq;
                            closestHit = worldHitLoc;
                            hitType = "SubLevelBlock";
                        }
                    }
                }
            }
        } catch (Exception e) {
            IACP.LOGGER.debug("[WeaponOverlay] SubLevel 局部空间 clip 异常: {}", e.getMessage());
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
     * 统一计算实体命中点：优先使用碰撞箱 clip，失败时用表面最近点， 彻底避免
     * {@link EntityHitResult#getLocation()} 返回脚部位置的问题。
     *
     * @param bb 实体碰撞箱
     * @param origin 射线起点
     * @param to 射线终点
     * @param dir 射线方向（单位向量）
     * @param fallback 最后的回退位置（仅当全部方法失败时）
     * @return 命中点世界坐标，全部失败返回 null
     */
    @Nullable
    private static Vec3 computeEntityHitPoint(AABB bb, Vec3 origin, Vec3 to,
            Vec3 dir, Vec3 fallback) {
        // 1. 优先：非膨胀碰撞箱 clip（精确命中面）
        var optClip = bb.clip(origin, to);
        if (optClip.isPresent()) {
            return optClip.get();
        }
        // 2. 降级：AABB 表面最近点（clip 失败时，如射线从内部出发或擦边而过）
        Vec3 surface = nearestSurfacePointOnAABB(bb, origin, dir);
        if (surface != null) {
            // 确保表面点在射线前方（沿 direction 正方向）
            Vec3 delta = surface.subtract(origin);
            if (delta.dot(dir) >= 0) {
                return surface;
            }
        }
        // 3. 最后：使用 fallback（entityHit.getLocation()，可能为脚部）
        //    但只在它确实在射线前方时使用
        if (fallback != null) {
            Vec3 delta = fallback.subtract(origin);
            if (delta.dot(dir) >= 0) {
                return fallback;
            }
        }
        return null;
    }

    /**
     * 计算 AABB 表面上距离射线最近的点（当 {@link AABB#clip} 失败时用）。
     * <p>
     * 先将射线投影到 AABB 中心方向，取射线上的最近点，再钳位到 AABB 边界上。 结果必定在 AABB 表面（至少一个坐标等于边界值）。
     *
     * @param bb 实体碰撞箱
     * @param origin 射线起点
     * @param dir 射线方向（单位向量）
     * @return AABB 表面最近点
     */
    private static Vec3 nearestSurfacePointOnAABB(AABB bb, Vec3 origin, Vec3 dir) {
        Vec3 center = bb.getCenter();
        // 射线上的最近点（t 限制为非负）
        double t = dir.dot(center.subtract(origin));
        if (t < 0) {
            t = 0;
        }
        Vec3 rayPoint = origin.add(dir.scale(t));
        // 钳位到 AABB 边界 → 自动落在表面
        double x = Math.max(bb.minX, Math.min(bb.maxX, rayPoint.x));
        double y = Math.max(bb.minY, Math.min(bb.maxY, rayPoint.y));
        double z = Math.max(bb.minZ, Math.min(bb.maxZ, rayPoint.z));
        return new Vec3(x, y, z);
    }

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

    // ==================================================================
    //  工具方法
    // ==================================================================
    /**
     * 将值钳位到 [lo, hi] 范围。
     */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
