package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 载具实时控制输入数据包（客户端 → 服务器）。
 * <p>
 * 当玩家骑乘载具时，客户端每 tick 扫描载具所在 SubLevel 内的所有悬挂测试方块，
 * 检查每个方块配置的自定义按键是否被按下，然后将此包发送到服务端。
 * <p>
 * 设计原则：客户端只检测按键按下/抬起状态，服务端执行物理动作。
 * 仅在有状态变化时发送，以减少网络开销。
 */
public class VehicleControlC2SPacket implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            IACP.MODID, "vehicle_control"
    );
    public static final Type<VehicleControlC2SPacket> TYPE = new Type<>(ID);

    private final List<Entry> entries;

    public record Entry(BlockPos blockPos, boolean forward, boolean backward, boolean left, boolean right, boolean brake) {}

    public VehicleControlC2SPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() { return entries; }

    public static final StreamCodec<RegistryFriendlyByteBuf, VehicleControlC2SPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VehicleControlC2SPacket decode(RegistryFriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    List<Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(new Entry(
                                buf.readBlockPos(),
                                buf.readBoolean(),
                                buf.readBoolean(),
                                buf.readBoolean(),
                                buf.readBoolean(),
                                buf.readBoolean()
                        ));
                    }
                    return new VehicleControlC2SPacket(entries);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, VehicleControlC2SPacket packet) {
                    buf.writeVarInt(packet.entries.size());
                    for (Entry e : packet.entries) {
                        buf.writeBlockPos(e.blockPos);
                        buf.writeBoolean(e.forward);
                        buf.writeBoolean(e.backward);
                        buf.writeBoolean(e.left);
                        buf.writeBoolean(e.right);
                        buf.writeBoolean(e.brake);
                    }
                }
            };

    @Override
    public Type<VehicleControlC2SPacket> type() {
        return TYPE;
    }

    /**
     * 服务端处理：将每个方块的控制输入写入对应的 BlockEntity。
     * 仅在玩家处于骑乘状态时处理。
     */
    public static void handle(final VehicleControlC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (!PlayerMountTracker.isMounted(serverPlayer)) return;

                ServerLevel level = serverPlayer.serverLevel();
                for (Entry entry : packet.entries) {
                    if (level.getBlockEntity(entry.blockPos) instanceof SuspensionTestBlockEntity be) {
                        be.applyControlInput(
                                entry.forward,
                                entry.backward,
                                entry.left,
                                entry.right,
                                entry.brake
                        );
                    }
                }
            }
        });
    }
}
