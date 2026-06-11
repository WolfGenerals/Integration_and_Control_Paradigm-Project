package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 开火数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送目标位置，服务端在该位置附近寻找实体并造成伤害。
 * 弹道渲染由客户端本地完成（从炮口到目标的白色线条）。
 */
public record WeaponFireC2SPacket(
        double hitX, double hitY, double hitZ
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_fire");
    public static final Type<WeaponFireC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponFireC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WeaponFireC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    return new WeaponFireC2SPacket(
                            buf.readDouble(), buf.readDouble(), buf.readDouble()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, WeaponFireC2SPacket packet) {
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
     * 服务端处理：在命中位置附近找实体造成伤害。
     */
    public static void handle(final WeaponFireC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!PlayerMountTracker.isMounted(player)) return;

            ServerLevel level = player.serverLevel();
            Vec3 hitPos = new Vec3(packet.hitX, packet.hitY, packet.hitZ);

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
