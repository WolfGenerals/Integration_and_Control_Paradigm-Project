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

/**
 * 开火数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送射线起点和命中点，服务端对命中位置的 SubLevel 方块和实体造成伤害。 弹道渲染由客户端本地完成（从炮口到目标的白色线条）。
 * <p>
 * <b>简化设计</b>：客户端 {@code raycastGeneric} 已经是纯 Minecraft COLLIDER 检测， 无视所有
 * SubLevel 物理外壳（灰色/红色线框），直接返回方块/实体碰撞箱表面的命中点。 服务端不再做 SubLevel
 * 感知的射线重追踪，直接用命中点坐标查找并施加伤害。 不做归属排除——所有 SubLevel 方块、所有实体一视同仁。
 */
public record WeaponFireC2SPacket(
        double originX, double originY, double originZ,
        double hitX, double hitY, double hitZ
        ) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_fire");
    public static final Type<WeaponFireC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponFireC2SPacket> STREAM_CODEC
            = new StreamCodec<>() {
        @Override
        public WeaponFireC2SPacket decode(RegistryFriendlyByteBuf buf) {
            return new WeaponFireC2SPacket(
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WeaponFireC2SPacket packet) {
            buf.writeDouble(packet.originX);
            buf.writeDouble(packet.originY);
            buf.writeDouble(packet.originZ);
            buf.writeDouble(packet.hitX);
            buf.writeDouble(packet.hitY);
            buf.writeDouble(packet.hitZ);
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

            // ---- 方块伤害：直接使用命中点，不做 SubLevel 射线重追踪 ----
            PartDamageCache.damageBlock(level, hitPos, 1.0f);

            // ---- 实体伤害：用射线-实体交点检测（与客户端 raycastGeneric 一致） ----
            Vec3 dir = hitPos.subtract(origin);
            double dist = dir.length();
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
                    // 计算射线与实体碰撞箱的精确交点（与客户端一致）
                    var bb = entityHit.getEntity().getBoundingBox();
                    var optHit = bb.clip(origin, to);
                    Vec3 preciseHit = optHit.orElse(entityHit.getLocation());
                    // 只取在 origin→hitPos 方向的命中（不要打穿到背后）
                    if (preciseHit.distanceToSqr(origin) <= dist * dist + 1.0) {
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
}
