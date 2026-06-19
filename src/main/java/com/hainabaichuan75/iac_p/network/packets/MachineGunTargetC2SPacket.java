package com.hainabaichuan75.iac_p.network.packets;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.machine_gun.MachineGunAimController;
import com.hainabaichuan75.iac_p.content.blocks.machine_gun.MachineGunBaseBlockEntity;
import com.hainabaichuan75.iac_p.events.PlayerMountTracker;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.UUID;

/**
 * 机枪瞄准数据包（客户端 → 服务器）。
 * <p>
 * 客户端发送射线检测命中点的世界坐标，服务端将所有坐标变换到<b>载具局部空间</b>
 * 后为每座机枪独立计算瞄准角度。
 * <p>
 * <b>载具局部空间计算</b>：
 * <ol>
 * <li>命中点世界坐标 → {@code vPose⁻¹ · (hit - vPos)} 变换到载具局部空间</li>
 * <li>机枪（砂轮）世界坐标 → 同样变换到载具局部空间</li>
 * <li>在局部空间中计算角度，结果天然是载具相对角度，无需额外减去载具偏航</li>
 * </ol>
 * <b>角度分离（三维球坐标）</b>：
 * <ul>
 * <li>方向机 Yaw（俯视投影 / XZ 平面）：{@code -atan2(dx, dz)}</li>
 * <li>高低机 Pitch（侧面投影 / 垂直面）：{@code atan2(dy, sqrt(dx²+dz²))}</li>
 * </ul>
 * 载具翻转（上下颠倒、侧翻）时，由于全部在载具局部空间计算，角度天然正确。
 */
public record MachineGunTargetC2SPacket(
        float hitX,
        float hitY,
        float hitZ
        ) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(IACP.MODID, "machine_gun_target");
    public static final Type<MachineGunTargetC2SPacket> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineGunTargetC2SPacket> STREAM_CODEC
            = new StreamCodec<>() {
        @Override
        public MachineGunTargetC2SPacket decode(RegistryFriendlyByteBuf buf) {
            return new MachineGunTargetC2SPacket(
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MachineGunTargetC2SPacket packet) {
            buf.writeFloat(packet.hitX);
            buf.writeFloat(packet.hitY);
            buf.writeFloat(packet.hitZ);
        }
    };

    @Override
    public Type<MachineGunTargetC2SPacket> type() {
        return TYPE;
    }

    // ==================================================================
    //  工具：世界 → 载具局部空间变换
    // ==================================================================
    /**
     * 将世界坐标变换到载具局部空间。
     *
     * @param vPose 载具 SubLevel 位姿
     * @param vOrientInv 预计算的载具旋转逆四元数
     * @param wx 世界 X
     * @param wy 世界 Y
     * @param wz 世界 Z
     * @return 载具局部空间中的 Vector3d
     */
    private static Vector3d worldToLocal(Pose3dc vPose,
            Quaterniond vOrientInv, double wx, double wy, double wz) {
        return new Vector3d(
                wx - vPose.position().x(),
                wy - vPose.position().y(),
                wz - vPose.position().z()
        ).rotate(vOrientInv);
    }

    /**
     * 驱动单座机枪瞄准目标点 —— 在载具局部空间中计算。
     * <p>
     * 全部坐标变换到载具局部空间后计算，结果天然是载具相对角度：
     * <ul>
     * <li>方向机 Yaw = {@code -atan2(dx_local, dz_local)} ← 局部 XZ 平面俯视投影</li>
     * <li>高低机 Pitch = {@code atan2(dy_local, sqrt(dx²+dz²))} ← 局部侧面投影</li>
     * </ul>
     * 载具翻转时，局部空间的「水平面」随载具旋转，角度计算始终正确。
     */
    private static void driveTurretAtTarget(MachineGunBaseBlockEntity tb,
            Vector3d hitLocal, Vector3d turretLocal) {
        double dx = hitLocal.x - turretLocal.x;
        double dy = hitLocal.y - turretLocal.y;
        double dz = hitLocal.z - turretLocal.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // ---- 方向机：载具局部 XZ 平面俯视投影 ----
        float turretYaw = horiz < 0.001 ? 0f
                : (float) -Math.toDegrees(Math.atan2(dx, dz));

        // ---- 高低机：载具局部侧面投影 ----
        float turretPitch = (float) Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.001)));

        MachineGunAimController.driveAnglesImmediate(tb, turretYaw, turretPitch);
    }

    /**
     * 驱动单座霰弹枪瞄准目标点 —— 与机枪相同的局部空间计算。
     */
    private static void driveShotgunAtTarget(ShotgunBaseBlockEntity sb,
            Vector3d hitLocal, Vector3d weaponLocal) {
        double dx = hitLocal.x - weaponLocal.x;
        double dy = hitLocal.y - weaponLocal.y;
        double dz = hitLocal.z - weaponLocal.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = horiz < 0.001 ? 0f
                : (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.001)));

        com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunAimController.driveAnglesImmediate(sb, yaw, pitch);
    }

    /**
     * 服务端处理：将命中点 + 每座机枪坐标变换到载具局部空间后计算角度。
     * <p>
     * 载具局部空间计算的优势：
     * <ul>
     * <li>角度天然是载具相对角度，无需手动减载具偏航</li>
     * <li>载具翻转（上下颠倒、侧翻）时，局部「水平面」跟随载具，角度计算正确</li>
     * <li>方向机只看局部 XZ 平面，高低机只看局部侧面投影，互不干扰</li>
     * </ul>
     */
    public static void handle(final MachineGunTargetC2SPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!PlayerMountTracker.isMounted(player)) {
                return;
            }

            ServerLevel level = player.serverLevel();
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }

            var mountData = PlayerMountTracker.getMountData(player);
            if (mountData == null) {
                return;
            }

            UUID vehicleUUID = mountData.subLevelUUID();
            float hitX = packet.hitX;
            float hitY = packet.hitY;
            float hitZ = packet.hitZ;

            SubLevel vehicleSL = container.getSubLevel(vehicleUUID);
            if (vehicleSL == null || vehicleSL.isRemoved()) {
                return;
            }

            // 预计算载具位姿和逆四元数
            var vPose = vehicleSL.logicalPose();
            if (vPose == null) {
                return;
            }
            var vOrientInv = new Quaterniond(vPose.orientation()).conjugate();

            // 变换命中点到载具局部空间（只需做一次）
            var hitLocal = worldToLocal(vPose, vOrientInv, hitX, hitY, hitZ);

            // ---- 首选：ComponentRegistry O(1) 查找 ----
            // 机枪（MachineGun）
            var machineGunEntries = ComponentRegistry.getComponents(vehicleUUID, ComponentRole.MACHINE_GUN_BASE);
            for (var entry : machineGunEntries) {
                if (!(entry.blockEntity() instanceof MachineGunBaseBlockEntity tb)) {
                    continue;
                }
                if (!tb.isAssembled()) {
                    continue;
                }
                SubLevel gsSL = container.getSubLevel(tb.getGrindstoneSubLevelId());
                if (gsSL == null || gsSL.isRemoved()) {
                    continue;
                }
                var gsPose = gsSL.logicalPose();
                if (gsPose == null) {
                    continue;
                }
                var turretLocal = worldToLocal(vPose, vOrientInv,
                        gsPose.position().x(), gsPose.position().y(), gsPose.position().z());
                driveTurretAtTarget(tb, hitLocal, turretLocal);
            }

            // 霰弹枪（Shotgun）
            var shotgunEntries = ComponentRegistry.getComponents(vehicleUUID, ComponentRole.SHOTGUN_BASE);
            for (var entry : shotgunEntries) {
                if (!(entry.blockEntity() instanceof ShotgunBaseBlockEntity sb)) {
                    continue;
                }
                if (!sb.isAssembled()) {
                    continue;
                }
                SubLevel gsSL = container.getSubLevel(sb.getGrindstoneSubLevelId());
                if (gsSL == null || gsSL.isRemoved()) {
                    continue;
                }
                var gsPose = gsSL.logicalPose();
                if (gsPose == null) {
                    continue;
                }
                var weaponLocal = worldToLocal(vPose, vOrientInv,
                        gsPose.position().x(), gsPose.position().y(), gsPose.position().z());
                driveShotgunAtTarget(sb, hitLocal, weaponLocal);
            }

            // 如果注册表中找到武器条目，跳过回退扫描
            if (!machineGunEntries.isEmpty() || !shotgunEntries.isEmpty()) {
                return;
            }

            // ---- 回退：chunk 扫描（用地毯位置近似机枪位置） ----
            LevelPlot plot = vehicleSL.getPlot();
            if (plot == null) {
                return;
            }

            for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
                var localBounds = chunk.getBoundingBox();
                if (localBounds == null || localBounds == BoundingBox3i.EMPTY) {
                    continue;
                }
                int cMinX = chunk.getPos().getMinBlockX();
                int cMinZ = chunk.getPos().getMinBlockZ();
                for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                    for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                        for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                            BlockPos wp = new BlockPos(x + cMinX, y, z + cMinZ);
                            BlockEntity be = level.getBlockEntity(wp);
                            if (be instanceof MachineGunBaseBlockEntity tb) {
                                if (tb.isAssembled()) {
                                    var weaponLocal = worldToLocal(vPose, vOrientInv,
                                            wp.getX() + 0.5, wp.getY() + 0.5, wp.getZ() + 0.5);
                                    driveTurretAtTarget(tb, hitLocal, weaponLocal);
                                }
                            } else if (be instanceof ShotgunBaseBlockEntity sb) {
                                if (sb.isAssembled()) {
                                    var weaponLocal = worldToLocal(vPose, vOrientInv,
                                            wp.getX() + 0.5, wp.getY() + 0.5, wp.getZ() + 0.5);
                                    driveShotgunAtTarget(sb, hitLocal, weaponLocal);
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}
