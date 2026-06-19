package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRegistry;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import com.hainabaichuan75.iac_p.index.ModSounds;
import com.hainabaichuan75.iac_p.network.packets.WeaponSoundS2CPacket;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 开火数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送射线起点和命中点，服务端对命中位置的 SubLevel 方块和实体造成伤害。 弹道渲染由客户端本地完成（从炮口到目标的白色线条）。
 * <p>
 * <b>简化设计</b>：客户端 {@code raycastGeneric} 已经是纯 Minecraft COLLIDER 检测， 无视所有
 * SubLevel 物理外壳（灰色/红色线框），直接返回方块/实体碰撞箱表面的命中点。 服务端不再做 SubLevel
 * 感知的射线重追踪，直接用命中点坐标查找并施加伤害。
 * <p>
 * <b>归属豁免</b>：命中 SubLevel 方块时，若该 SubLevel 属于射手自身的载具/炮塔/部件
 * （通过 {@link AffiliationRegistry#getOwnAffiliatedSet} 判定），则不造成伤害。
 * <p>
 * <b>旋转体无敌修复</b>：当命中 SubLevel 方块时，客户端额外发送 SubLevel UUID 和局部坐标。 服务端用 SubLevel 当前
 * pose 将局部坐标转回世界坐标，消除目标移动/旋转导致的命中失效。 非 SubLevel 命中保持原有世界坐标行为。
 */
public class WeaponFireC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_fire");
    public static final Type<WeaponFireC2SPacket> TYPE = new Type<>(ID);

    // 武器类型常量
    public static final byte WEAPON_MACHINE_GUN = 1;
    public static final byte WEAPON_SHOTGUN = 2;

    /**
     * 服务端音效冷却：每个玩家每 tick 只播放一次开火音效
     */
    private static final java.util.Map<java.util.UUID, Integer> LAST_SOUND_TICK = new java.util.HashMap<>();

    // ---- 公共字段（原始射线数据） ----
    public final double originX, originY, originZ;
    public final double hitX, hitY, hitZ;
    /**
     * 是否为 SubLevel 命中（此时 local / uuid 字段有效）
     */
    public final boolean hasSubLevel;
    public final long subUUIDMost;
    public final long subUUIDLeast;
    public final double localX, localY, localZ;

    // ---- 速度外推字段 ----
    /** 开火瞬间载具 SubLevel 的瞬时速度 X 分量（m/s），0 表示未采样或静止 */
    public final double velX;
    /** 开火瞬间载具 SubLevel 的瞬时速度 Y 分量（m/s） */
    public final double velY;
    /** 开火瞬间载具 SubLevel 的瞬时速度 Z 分量（m/s） */
    public final double velZ;

    // ---- 武器类型字段 ----
    /** 0=generic, 1=turret, 2=shotgun. 用于服务端选择开火音效 */
    public final byte weaponType;

    // ---- 构造器 ----

    /**
     * 非 SubLevel 命中（传统世界坐标模式），无速度信息。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                false, 0L, 0L, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, (byte) 1);
    }

    /**
     * 非 SubLevel 命中 + 速度外推。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            double velX, double velY, double velZ) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                false, 0L, 0L, 0.0, 0.0, 0.0,
                velX, velY, velZ, WEAPON_MACHINE_GUN);
    }

    /**
     * 非 SubLevel 命中 + 速度外推 + 武器类型。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            double velX, double velY, double velZ,
            byte weaponType) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                false, 0L, 0L, 0.0, 0.0, 0.0,
                velX, velY, velZ, weaponType);
    }

    /**
     * SubLevel 命中（携带 UUID + 局部坐标），无速度信息。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            UUID subUUID, Vec3 localPos) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                true, subUUID.getMostSignificantBits(), subUUID.getLeastSignificantBits(),
                localPos.x, localPos.y, localPos.z,
                0.0, 0.0, 0.0, (byte) 1);
    }

    /**
     * SubLevel 命中 + 速度外推 + 武器类型。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            UUID subUUID, Vec3 localPos,
            double velX, double velY, double velZ) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                true, subUUID.getMostSignificantBits(), subUUID.getLeastSignificantBits(),
                localPos.x, localPos.y, localPos.z,
                velX, velY, velZ, (byte) 1);
    }

    /**
     * SubLevel 命中 + 速度外推 + 武器类型指定。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            UUID subUUID, Vec3 localPos,
            double velX, double velY, double velZ,
            byte weaponType) {
        this(originX, originY, originZ, hitX, hitY, hitZ,
                true, subUUID.getMostSignificantBits(), subUUID.getLeastSignificantBits(),
                localPos.x, localPos.y, localPos.z,
                velX, velY, velZ, weaponType);
    }

    /**
     * 全字段构造器（含速度外推 + 武器类型）。
     */
    public WeaponFireC2SPacket(
            double originX, double originY, double originZ,
            double hitX, double hitY, double hitZ,
            boolean hasSubLevel, long subUUIDMost, long subUUIDLeast,
            double localX, double localY, double localZ,
            double velX, double velY, double velZ,
            byte weaponType) {
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
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.weaponType = weaponType;
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
                double vx = buf.readDouble();
                double vy = buf.readDouble();
                double vz = buf.readDouble();
                byte wt = buf.readByte();
                return new WeaponFireC2SPacket(ox, oy, oz, hx, hy, hz,
                        true, uuidMost, uuidLeast, lx, ly, lz,
                        vx, vy, vz, wt);
            } else {
                double vx = buf.readDouble();
                double vy = buf.readDouble();
                double vz = buf.readDouble();
                byte wt = buf.readByte();
                return new WeaponFireC2SPacket(ox, oy, oz, hx, hy, hz,
                        false, 0L, 0L, 0.0, 0.0, 0.0,
                        vx, vy, vz, wt);
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
            buf.writeDouble(packet.velX);
            buf.writeDouble(packet.velY);
            buf.writeDouble(packet.velZ);
            buf.writeByte(packet.weaponType);
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
     * <p>
     * <b>速度偏移</b>：客户端在开火前已将枪口起点沿载具速度方向做了偏移补偿，
     * 服务端直接使用数据包中的坐标，不再额外做时间外推。
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
            Vec3 dir = hitPos.subtract(origin);
            double dist = dir.length();

            // ── 服务端音效：只发给「其他玩家」，开火者已由客户端本地音效覆盖 ──
            //  开火者客户端已在 WeaponOverlay.fireFromRegistry() 中立即播放 playLocalSound，
            //  无需等待服务端广播。此处通过 WeaponSoundS2CPacket 仅发给附近的其他玩家。
            //  使用距离判断（64 格，覆盖 sounds.json 的 attenuation_distance=48）避免发给过远玩家。
            int currentTick = (int) level.getGameTime();
            Integer lastTick = LAST_SOUND_TICK.get(player.getUUID());
            if (lastTick == null || currentTick != lastTick) {
                LAST_SOUND_TICK.put(player.getUUID(), currentTick);
                WeaponSoundS2CPacket soundPacket = new WeaponSoundS2CPacket(
                        packet.originX, packet.originY, packet.originZ,
                        packet.weaponType, 1.0f, 1.0f);
                for (ServerPlayer otherPlayer : level.getServer().getPlayerList().getPlayers()) {
                    if (otherPlayer == player || otherPlayer.serverLevel() != level) continue;
                    double dx = otherPlayer.getX() - packet.originX;
                    double dz = otherPlayer.getZ() - packet.originZ;
                    if (dx * dx + dz * dz < 64.0 * 64.0) {
                        PacketDistributor.sendToPlayer(otherPlayer, soundPacket);
                    }
                }
            }

            // ---- 方块伤害 ----
            if (packet.hasSubLevel) {
                UUID subUUID = new UUID(packet.subUUIDMost, packet.subUUIDLeast);

                // ---- 归属豁免：不伤害自己的载具/炮塔/部件 ----
                PlayerMountTracker.MountData mountData = PlayerMountTracker.getMountData(player);
                boolean isOwn = false;
                if (mountData != null) {
                    java.util.Set<UUID> ownSet = AffiliationRegistry.getOwnAffiliatedSet(mountData.subLevelUUID());
                    isOwn = ownSet.contains(subUUID);
                    // 【诊断】输出命中 SubLevel UUID 和归属判定结果
                    IACP.LOGGER.info("[WF] hasSubLevel=true subUUID={} mountVeh={} inOwnSet={}",
                            subUUID.toString().substring(0, 8),
                            mountData.subLevelUUID().toString().substring(0, 8),
                            isOwn);
                    if (isOwn) {
                        return; // 命中自己的部件 → 不造成伤害
                    }
                }

                // ---- 【局部坐标路径】客户端已确定命中 SubLevel，使用局部坐标 ----
                Vec3 localPos = new Vec3(packet.localX, packet.localY, packet.localZ);
                if (dist > 0.01) {
                    PartDamageCache.damageBlockAtLocal(level, subUUID, localPos, 1.0f, dir.normalize());
                } else {
                    PartDamageCache.damageBlockAtLocal(level, subUUID, localPos, 1.0f);
                }
            } else {
                // ---- 【传统世界坐标路径】非 SubLevel 命中 ----
                // 仍做归属检查：若 hitPos 在射手载具的 AABB 内，也跳过
                boolean skipWorldDamage = false;
                PlayerMountTracker.MountData mountData2 = PlayerMountTracker.getMountData(player);
                if (mountData2 != null) {
                    java.util.Set<UUID> ownSet = AffiliationRegistry.getOwnAffiliatedSet(mountData2.subLevelUUID());
                    for (UUID ownSL : ownSet) {
                        SubLevel sl = SubLevelContainer.getContainer(level).getSubLevel(ownSL);
                        if (sl != null && !sl.isRemoved()) {
                            var bb = sl.boundingBox();
                            if (bb != null
                                    && hitPos.x >= bb.minX() && hitPos.x <= bb.maxX()
                                    && hitPos.y >= bb.minY() && hitPos.y <= bb.maxY()
                                    && hitPos.z >= bb.minZ() && hitPos.z <= bb.maxZ()) {
                                skipWorldDamage = true;
                                break;
                            }
                        }
                    }
                }
                IACP.LOGGER.info("[WF] hasSubLevel=false hitPos=({}, {}, {}) skipWorld={}",
                        String.format("%.2f", packet.hitX),
                        String.format("%.2f", packet.hitY),
                        String.format("%.2f", packet.hitZ),
                        skipWorldDamage);
                if (!skipWorldDamage && dist > 0.01) {
                    PartDamageCache.damageBlock(level, hitPos, 1.0f, dir.normalize());
                } else if (!skipWorldDamage) {
                    PartDamageCache.damageBlock(level, hitPos, 1.0f);
                }
            }

            // ---- 实体伤害：用外推后的射线做检测 ----
            if (dist > 0.01) {
                dir = dir.normalize();
                Vec3 to = origin.add(dir.scale(dist + 2.0));
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

                    Vec3 preciseHit = null;

                    // 1. 优先：非膨胀碰撞箱 clip
                    var optClip = bb.clip(origin, to);
                    if (optClip.isPresent()) {
                        preciseHit = optClip.get();
                    }

                    // 2. 降级：AABB 表面最近点
                    if (preciseHit == null) {
                        Vec3 surface = nearestSurfacePointOnAABB(bb, origin, dir);
                        if (surface != null) {
                            Vec3 delta = surface.subtract(origin);
                            if (delta.dot(dir) >= 0) {
                                preciseHit = surface;
                            }
                        }
                    }

                    // 3. 最终降级：entityHit.getLocation()
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
                        level.sendParticles(
                                net.minecraft.core.particles.ParticleTypes.CRIT,
                                preciseHit.x, preciseHit.y, preciseHit.z,
                                6, 0.3, 0.3, 0.3, 0.1);
                    }
                }
            }

            // ---- 弹道粒子特效（沿射线路径散布，所有玩家可见） ----
            Vec3 particleDir = hitPos.subtract(origin);
            double particleDist = particleDir.length();
            if (particleDist > 0.5) {
                particleDir = particleDir.normalize();
                // 每格一个粒子，上限 16 个防止过量
                int steps = Math.min((int) particleDist, 16);
                for (int i = 0; i < steps; i++) {
                    double t = (i + 0.5) / steps;
                    Vec3 p = origin.add(particleDir.scale(particleDist * t));
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.CRIT,
                            p.x, p.y, p.z,
                            1, 0.05, 0.05, 0.05, 0.02);
                }
            }

            // ---- 命中爆发粒子 ----
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.CRIT,
                    hitPos.x, hitPos.y, hitPos.z,
                    6, 0.2, 0.2, 0.2, 0.1);
        });
    }

    // ==================================================================
    //  实体命中点计算
    // ==================================================================
    private static Vec3 nearestSurfacePointOnAABB(AABB bb, Vec3 origin, Vec3 dir) {
        Vec3 center = bb.getCenter();
        double t = dir.dot(center.subtract(origin));
        if (t < 0) {
            t = 0;
        }
        Vec3 rayPoint = origin.add(dir.scale(t));
        double x = Math.max(bb.minX, Math.min(bb.maxX, rayPoint.x));
        double y = Math.max(bb.minY, Math.min(bb.maxY, rayPoint.y));
        double z = Math.max(bb.minZ, Math.min(bb.maxZ, rayPoint.z));
        return new Vec3(x, y, z);
    }
}
