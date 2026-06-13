package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.events.PartDamageCache;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.hainabaichuan75.iac_p.events.SubLevelOwnership;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * 开火数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送射线起点和目标位置，服务端进行完整射线追踪以精确命中 SubLevel 方块。
 * 弹道渲染由客户端本地完成（从炮口到目标的白色线条）。
 * <p>
 * <b>部件损坏关键设计</b>：服务端使用 {@link SableBlockHelper#rayTraceSubLevels} 将射线变换到
 * SubLevel 局部空间后重新 clip，避免 AABB 表面交点映射为空气的问题。
 * 详见 {@code 5.1-关键技术要点/30-SableAPI查询方式：Vector3dc vs SubLevel遍历.md}。
 */
public record WeaponFireC2SPacket(
        double originX, double originY, double originZ,
        double hitX, double hitY, double hitZ
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_fire");
    public static final Type<WeaponFireC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponFireC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
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
     * 服务端处理：在命中位置附近找实体造成伤害，并对 SubLevel 方块执行精确射线追踪。
     */
    public static void handle(final WeaponFireC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                IACP.LOGGER.info("[WeaponFire] ⛔ context.player 不是 ServerPlayer");
                return;
            }
            if (!PlayerMountTracker.isMounted(player)) {
                IACP.LOGGER.info("[WeaponFire] ⛔ 玩家 {} 未骑乘，忽略开火", player.getName().getString());
                return;
            }

            ServerLevel level = player.serverLevel();
            Vec3 origin = new Vec3(packet.originX, packet.originY, packet.originZ);
            Vec3 hitPos = new Vec3(packet.hitX, packet.hitY, packet.hitZ);

            IACP.LOGGER.info("[WeaponFire] ====== 收到武器开火 =====");
            IACP.LOGGER.info("[WeaponFire] 玩家: {}, 射线起点: {}, 命中位置: {}",
                    player.getName().getString(), origin, hitPos);
            IACP.LOGGER.info("[WeaponFire] 命中 BlockPos: {}", BlockPos.containing(hitPos));

            // 在命中位置附近找实体，造成 2 点伤害
            AABB aabb = new AABB(hitPos, hitPos).inflate(1.5);
            for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, aabb,
                    e -> e.isPickable() && e != player)) {
                // 无视无敌帧（每次受伤前清除免疫时间）
                entity.invulnerableTime = 0;
                entity.hurt(entity.damageSources().generic(), 2.0f);
                // 暴击粒子
                level.sendParticles(
                        net.minecraft.core.particles.ParticleTypes.CRIT,
                        entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ(),
                        6, 0.3, 0.3, 0.3, 0.1);
            }

            // 构建排除集合：载具自身 + 所有衍生 SubLevel（砂轮、避雷针等）
            Set<UUID> exclusions = new java.util.HashSet<>();
            var mountData = PlayerMountTracker.getMountData(player);
            if (mountData != null) {
                UUID vehicleUUID = mountData.subLevelUUID();
                // 通过 SubLevelOwnership 查询载具的所有衍生结构
                exclusions.addAll(SubLevelOwnership.getAllOwnedByVehicle(vehicleUUID));
                IACP.LOGGER.info("[WeaponFire] 排除集合: {} 个 SubLevel (含载具 {} 及其衍生结构)",
                        exclusions.size(), vehicleUUID.toString().substring(0, 8));
            }

            // 对 SubLevel 方块造成 1 点部件伤害（5 击摧毁）
            // 使用完整射线追踪方案：将射线变换到 SubLevel 局部空间后重新 clip
            // 自动排除射线起点所在 SubLevel（枪管）和排除集合中的所有衍生结构
            PartDamageCache.damageBlock(level, origin, hitPos, 1.0f, exclusions.isEmpty() ? null : exclusions);

            // 命中效果：暴击粒子 + 丢鸡蛋音效
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.CRIT,
                    hitPos.x, hitPos.y, hitPos.z,
                    6, 0.2, 0.2, 0.2, 0.1);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EGG_THROW,
                    SoundSource.PLAYERS, 0.8f, 1.0f);

            // 发射音效：丢雪球
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS,
                    1.0f, 1.0f);
        });
    }
}
