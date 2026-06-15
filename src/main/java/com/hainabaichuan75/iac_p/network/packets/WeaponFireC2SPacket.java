package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 开火数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送射线起点和命中点，服务端对命中位置的 SubLevel 方块和实体造成伤害。 弹道渲染由客户端本地完成（从炮口到目标的白色线条）。
 * <p>
 * <b>简化设计</b>：客户端 {@code raycastGeneric} 已经是纯 Minecraft COLLIDER 检测， 无视所有
 * SubLevel 物理外壳（灰色/红色线框），直接返回方块/实体碰撞箱表面的命中点。 服务端不再做 SubLevel
 * 感知的射线重追踪，直接用命中点坐标查找并施加伤害。 不做归属排除——所有 SubLevel 方块、所有实体一视同仁。
 * <p>
 * <b>旋转体无敌修复</b>：当命中 SubLevel 方块时，客户端额外发送 SubLevel UUID 和局部坐标。 服务端用 SubLevel 当前
 * pose 将局部坐标转回世界坐标，消除目标移动/旋转导致的命中失效。 非 SubLevel 命中保持原有世界坐标行为。
 */
public class WeaponFireC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_fire");
    public static final Type<WeaponFireC2SPacket> TYPE = new Type<>(ID);

    // ---- 公共字段（记录语法糖） ----
    public final double originX, originY, originZ;
    public final double hitX, hitY, hitZ;
    /**
     * 是否为 SubLevel 命中（此时 local / uuid 字段有效）
     */
    public final boolean hasSubLevel;
    public final long subUUIDMost;
    public final long subUUIDLeast;
    public final double localX, localY, localZ;

    // ---- 构造器 ----
    /**
     * 非 SubLevel 命中（传统世界坐标模式）
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                false, 0L, 0L, 0.0, 0.0, 0.0);
    }

    /**
     * SubLevel 命中（携带 UUID + 局部坐标）
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            UUID subUUID, Vec3 localPos) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                true, subUUID.getMostSignificantBits(), subUUID.getLeastSignificantBits(),
                localPos.x, localPos.y, localPos.z);
    }

    /**
     * 全字段构造器
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            boolean hasSubLevel, long subUUIDMost, long subUUIDLeast,
            double localX, double localY, double localZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.hasSubLevel = hasSubLevel;
        this.subUUIDMost = subUUIDMost;
        this.subUUIDLeast = subUUIDLeast;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponFireC2SPacket> STREAM_CODEC
            = new StreamCodec<>() {
        @Override
        public WeaponFireC2SPacket decode(RegistryFriendlyByteBuf buf) {
            double ox = buf.readDouble();
            double oy = buf.readDouble();
            double oz = buf.readDouble();
            double hx = buf.readDouble();
            double hy = buf.readDouble();
            double hz = buf.readDouble();
            boolean hasSL = buf.readBoolean();
            if (hasSL) {
                long uuidMost = buf.readLong();
                long uuidLeast = buf.readLong();
                double lx = buf.readDouble();
                double ly = buf.readDouble();
                double lz = buf.readDouble();
                return new WeaponFireC2SPacket(ox, oy, oz, hx, hy, hz,
                        true, uuidMost, uuidLeast, lx, ly, lz);
            } else {
                return new WeaponFireC2SPacket(ox, oy, oz, hx, hy, hz);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WeaponFireC2SPacket packet) {
            buf.writeDouble(packet.originX);
            buf.writeDouble(packet.originY);
            buf.writeDouble(packet.originZ);
            buf.writeDouble(packet.hitX);
            buf.writeDouble(packet.hitY);
            buf.writeDouble(packet.hitZ);
            buf.writeBoolean(packet.hasSubLevel);
            if (packet.hasSubLevel) {
                buf.writeLong(packet.subUUIDMost);
                buf.writeLong(packet.subUUIDLeast);
                buf.writeDouble(packet.localX);
                buf.writeDouble(packet.localY);
                buf.writeDouble(packet.localZ);
            }
        }
    };

    @Override
    public Type<WeaponFireC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：对命中位置的 SubLevel 方块和实体造成伤害。
     * <p>
     * 实体伤害使用与客户端 {@code WeaponOverlay.raycastGeneric} 相同的射线-实体交点检测
     * （{@link ProjectileUtil#getEntityHitResult}），确保伤害点与弹道渲染命中点一致， 而非使用 AABB
     * 包围盒的实体中心（脚部）。
     * <p>
     * 不做归属/排除检查，一视同仁。
     * <p>
     * <b>局部坐标修复</b>：当 {@link #hasSubLevel} 为 true 时，使用客户端在开火瞬间计算的 SubLevel
     * 局部坐标，通过 {@link PartDamageCache#damageBlockAtLocal} 直接处理， 避免目标移动/旋转后
     * 世界坐标命中点失效（"旋转体无敌"）。
     */
    public static void handle(final WeaponFireC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!PlayerMountTracker.isMounted(player)) {
                return;
            }

            ServerLevel level = player.serverLevel();
            Vec3 origin = new Vec3(packet.originX, packet.originY, packet.originZ);
            Vec3 hitPos = new Vec3(packet.hitX, packet.hitY, packet.hitZ);

            // 计算射线方向（用于方块检测微调和实体检测）
            Vec3 dir = hitPos.subtract(origin);
            double dist = dir.length();

            // ---- 方块伤害 ----
            if (packet.hasSubLevel) {
                // ---- 【局部坐标路径】客户端已确定命中 SubLevel，使用局部坐标避免移动/旋转失效 ----
                UUID subUUID = new UUID(packet.subUUIDMost, packet.subUUIDLeast);
                Vec3 localPos = new Vec3(packet.localX, packet.localY, packet.localZ);
                if (dist > 0.01) {
                    PartDamageCache.damageBlockAtLocal(level, subUUID, localPos, 1.0f, dir.normalize());
                } else {
                    PartDamageCache.damageBlockAtLocal(level, subUUID, localPos, 1.0f);
                }
            } else {
                // ---- 【传统世界坐标路径】非 SubLevel 命中 ----
                if (dist > 0.01) {
                    PartDamageCache.damageBlock(level, hitPos, 1.0f, dir.normalize());
                } else {
                    PartDamageCache.damageBlock(level, hitPos, 1.0f);
                }
            }

            // ---- 实体伤害：用射线-实体交点检测（与客户端 raycastGeneric 一致） ----
            if (dist > 0.01) {
                dir = dir.normalize();
                Vec3 to = origin.add(dir.scale(dist + 2.0)); // 略过 hitPos 确保命中
                AABB searchBox = new AABB(origin, to).inflate(2.0);
                EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                        level,
                        player,
                        origin,
                        to,
                        searchBox,
                        e -> e.isPickable() && !e.isSpectator() && e != player
                );
                if (entityHit != null) {
                    var bb = entityHit.getEntity().getBoundingBox();

                    // 三层降级计算精确命中点（与客户端 computeEntityHitPoint 一致）
                    Vec3 preciseHit = null;

                    // 1. 优先：非膨胀碰撞箱 clip（精确命中面）
                    var optClip = bb.clip(origin, to);
                    if (optClip.isPresent()) {
                        preciseHit = optClip.get();
                    }

                    // 2. 降级：AABB 表面最近点（clip 失败时，如射线从内部出发或擦边而过）
                    if (preciseHit == null) {
                        Vec3 surface = nearestSurfacePointOnAABB(bb, origin, dir);
                        if (surface != null) {
                            Vec3 delta = surface.subtract(origin);
                            if (delta.dot(dir) >= 0) {
                                preciseHit = surface;
                            }
                        }
                    }

                    // 3. 最终降级：使用 entityHit.getLocation()（可能为脚部，但保证有值）
                    if (preciseHit == null) {
                        Vec3 delta = entityHit.getLocation().subtract(origin);
                        if (delta.dot(dir) >= 0) {
                            preciseHit = entityHit.getLocation();
                        }
                    }

                    if (preciseHit != null
                            && preciseHit.distanceToSqr(origin) <= dist * dist + 1.0) {
                        entityHit.getEntity().invulnerableTime = 0;
                        entityHit.getEntity().hurt(entityHit.getEntity().damageSources().generic(), 2.0f);
                        // 粒子在精确交点处，与弹道终点一致
                        level.sendParticles(
                                net.minecraft.core.particles.ParticleTypes.CRIT,
                                preciseHit.x, preciseHit.y, preciseHit.z,
                                6, 0.3, 0.3, 0.3, 0.1);
                    }
                }
            }

            // ---- 命中效果（始终在 hitPos） ----
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.CRIT,
                    hitPos.x, hitPos.y, hitPos.z,
                    6, 0.2, 0.2, 0.2, 0.1);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EGG_THROW,
                    SoundSource.PLAYERS, 0.8f, 1.0f);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS,
                    1.0f, 1.0f);
        });
    }

    // ==================================================================
    //  实体命中点计算（与 WeaponOverlay.computeEntityHitPoint 逻辑一致）
    // ==================================================================
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
}
