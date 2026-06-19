package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.index.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * WeaponSoundS2CPacket —— 服务端→客户端：播放武器开火音效。
 * <p>
 * 由服务端在 {@link WeaponFireC2SPacket#handle} 中发送给<b>除开火者以外</b>的所有玩家，
 * 配合客户端本地音效（{@link com.hainabaichuan75.iac_p.client.WeaponOverlay}）使用，避免双重播放。
 */
public record WeaponSoundS2CPacket(
        double x, double y, double z,
        byte weaponType,
        float volume, float pitch
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "weapon_sound");
    public static final Type<WeaponSoundS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponSoundS2CPacket> STREAM_CODEC
            = new StreamCodec<>() {
        @Override
        public WeaponSoundS2CPacket decode(RegistryFriendlyByteBuf buf) {
            return new WeaponSoundS2CPacket(
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readByte(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WeaponSoundS2CPacket packet) {
            buf.writeDouble(packet.x);
            buf.writeDouble(packet.y);
            buf.writeDouble(packet.z);
            buf.writeByte(packet.weaponType);
            buf.writeFloat(packet.volume);
            buf.writeFloat(packet.pitch);
        }
    };

    @Override
    public Type<WeaponSoundS2CPacket> type() {
        return TYPE;
    }

    /**
     * 客户端处理：在本地位置播放武器开火音效。
     */
    public static void handle(final WeaponSoundS2CPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            SoundEvent soundEvent = (packet.weaponType == WeaponFireC2SPacket.WEAPON_SHOTGUN)
                    ? ModSounds.SHOTGUN_FIRE.get()
                    : ModSounds.MACHINE_GUN_FIRE.get();
            mc.level.playLocalSound(
                    packet.x, packet.y, packet.z,
                    soundEvent,
                    SoundSource.PLAYERS,
                    packet.volume, packet.pitch,
                    false);
        });
    }
}
