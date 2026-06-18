/*
 * 载具实时状态同步包（服务端 → 客户端）。
 *
 * 在 NBT 块实体同步之外建立一条轻量专用通道，
 * 每 2 tick 推送发动机转速、油门深度、档位、车速等动态数据，
 * 使客户端覆盖层和 HUD 能够平滑实时地反映车辆状态。
 *
 * 设计原则：
 *   - 只推送高频变化量（RPM / 油门 / 车速），
 *     静态配置（齿比 / 技能 ID / 智能映射开关）仍走 NBT 同步。
 *   - 包体极小：6 个原生类型字段，固定 34 字节。
 */
package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.ClientMountHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 载具实时状态同步包。
 *
 * @param engineRpm       发动机当前转速（RPM）
 * @param throttleLevel   油门踏板深度 [0.0, 1.0]
 * @param currentGear     当前档位（-1=R, 0=N, 1-5=前进）
 * @param stalled         发动机是否熄火
 * @param effectiveTorque 引擎输出扭矩（Nm），含扭矩曲线修正 × 油门
 * @param vehicleSpeedMs  载具当前速度（m/s）
 * @param vehicleAccelMs2 载具当前加速度（m/s²），速度差分计算
 * @param isShifting      是否正在换挡
 */
public record VehicleStateS2CPacket(
        double engineRpm,
        double throttleLevel,
        int currentGear,
        boolean stalled,
        double effectiveTorque,
        double vehicleSpeedMs,
        double vehicleAccelMs2,
        boolean isShifting
) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "vehicle_state");
    public static final Type<VehicleStateS2CPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, VehicleStateS2CPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VehicleStateS2CPacket decode(RegistryFriendlyByteBuf buf) {
                    return new VehicleStateS2CPacket(
                            buf.readDouble(),   // engineRpm
                            buf.readDouble(),   // throttleLevel
                            buf.readVarInt(),   // currentGear
                            buf.readBoolean(),  // stalled
                            buf.readDouble(),   // effectiveTorque
                            buf.readDouble(),   // vehicleSpeedMs
                            buf.readDouble(),   // vehicleAccelMs2
                            buf.readBoolean()   // isShifting
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, VehicleStateS2CPacket packet) {
                    buf.writeDouble(packet.engineRpm);
                    buf.writeDouble(packet.throttleLevel);
                    buf.writeVarInt(packet.currentGear);
                    buf.writeBoolean(packet.stalled);
                    buf.writeDouble(packet.effectiveTorque);
                    buf.writeDouble(packet.vehicleSpeedMs);
                    buf.writeDouble(packet.vehicleAccelMs2);
                    buf.writeBoolean(packet.isShifting);
                }
            };

    @Override
    public Type<VehicleStateS2CPacket> type() {
        return TYPE;
    }

    /**
     * 客户端处理：将接收到的状态更新写入 ClientMountHandler 缓存。
     * 覆盖层 / HUD 均从该缓存读取，与 NBT 块实体同步解耦。
     */
    public static void handle(final VehicleStateS2CPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientMountHandler.updateVehicleState(
                    packet.engineRpm,
                    packet.throttleLevel,
                    packet.currentGear,
                    packet.stalled,
                    packet.effectiveTorque,
                    packet.vehicleSpeedMs,
                    packet.vehicleAccelMs2,
                    packet.isShifting
            );
        });
    }
}
