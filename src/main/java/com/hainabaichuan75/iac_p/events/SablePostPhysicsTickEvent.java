package com.hainabaichuan75.iac_p.events;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 监听 Sable 物理 tick 结束事件 ({@link ForgeSablePostPhysicsTickEvent})，
 * 在物理 tick 完成后立即处理延迟敏感的游戏逻辑。
 *
 * <p><b>设计动机</b>：原架构中大部分游戏事件在 {@code ServerTickEvent.Post} (20Hz)
 * 中处理，与 Sable 物理 tick (~100Hz) 之间存在异步处理延迟。
 * 该监听器将部分逻辑提升到物理 tick 频率执行，降低响应延迟。</p>
 *
 * <p><b>多线程远景</b>：当前 Sable 物理引擎运行在主线程上；
 * 但以物理 tick 为边界组织代码，为未来多线程物理做好了架构预备。
 * 当上游支持多线程物理时，按物理 tick 组织的逻辑将自然分摊到多核（如 AMD CPU）。</p>
 *
 * <p><b>当前处理的逻辑</b>：</p>
 * <ul>
 *   <li><b>骑乘玩家位置同步</b> — 使用物理 tick 后最新的 SubLevel logicalPose
 *       将玩家固定在驾驶舱位置。原在
 *       {@link PlayerMountTracker#onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post)}
 *       中每游戏 tick (20Hz) 执行，现提升到物理 tick (~100Hz)。</li>
 * </ul>
 *
 * @see ForgeSablePostPhysicsTickEvent
 * @see PlayerMountTracker
 */
public class SablePostPhysicsTickEvent {

    /**
     * 在 Sable 物理 tick 完成后立即调用。
     * <p>
     * 每个物理 tick 触发一次（频率 ~100Hz，取决于 Sable 配置），
     * 携带当前世界的 {@link SubLevelPhysicsSystem}。
     */
    @SubscribeEvent
    public static void onPostPhysicsTick(ForgeSablePostPhysicsTickEvent event) {
        SubLevelPhysicsSystem physicsSystem = event.getPhysicsSystem();
        ServerLevel level = physicsSystem.getLevel();
        if (level == null) return;

        syncMountedPlayerPositions(level);
    }

    /**
     * 将指定世界中所有已挂载玩家的位置同步到其 SubLevel 的最新 logicalPose。
     * <p>
     * 在物理 tick 完成后立即执行，确保使用的 SubLevel 位姿是最新的物理求解结果。
     * 同步内容包括：位置变换、朝向计算、零碰撞箱、无敌/无物理状态维护。
     * <p>
     * 该方法通过 {@link PlayerMountTracker#getMountedEntries()} 访问挂载表，
     * 通过 {@link PlayerMountTracker#updateMountLastPose(UUID, double, double)}
     * 更新位姿追踪。
     */
    private static void syncMountedPlayerPositions(ServerLevel level) {
        if (PlayerMountTracker.getMountedCount() == 0) return;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        var server = level.getServer();
        if (server == null) return;

        for (var entry : PlayerMountTracker.getMountedEntries()) {
            UUID playerUUID = entry.getKey();
            PlayerMountTracker.MountData data = entry.getValue();

            // 过滤：仅处理属于当前 physicsSystem 所在世界的 SubLevel
            SubLevel subLevel = container.getSubLevel(data.subLevelUUID());
            if (subLevel == null || subLevel.isRemoved()) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
            if (player == null || !player.isAlive()) continue;

            // === 使用最新 logicalPose 计算世界坐标 ===
            var logicalPose = subLevel.logicalPose();

            // 变换到驾驶舱底部中心的世界坐标
            Vector3d worldCockpitPos = new Vector3d();
            logicalPose.transformPosition(
                    new Vector3d(data.cockpitLocalX(), data.cockpitLocalY(), data.cockpitLocalZ()),
                    worldCockpitPos);

            // 计算朝向：pose 旋转矩阵将局部前向 (0,0,1) 变换到世界空间
            Vector3d localOrigin = new Vector3d();
            Vector3d localForward = new Vector3d();
            logicalPose.transformPosition(new Vector3d(0, 0, 0), localOrigin);
            logicalPose.transformPosition(new Vector3d(0, 0, 1), localForward);
            double fdx = localForward.x - localOrigin.x;
            double fdz = localForward.z - localOrigin.z;
            if (fdx * fdx + fdz * fdz > 1e-8) {
                float yaw = (float) Math.toDegrees(Math.atan2(-fdx, fdz));
                player.setYRot(yaw);
                player.setYHeadRot(yaw);
                player.yBodyRot = yaw;
                player.yBodyRotO = yaw;
            }

            // 更新 MountData 位姿追踪
            var posePos = logicalPose.position();
            PlayerMountTracker.updateMountLastPose(playerUUID, posePos.x(), posePos.z());

            // 设置玩家位置
            player.setPos(worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z);

            // 维持载具骑乘所需的物理状态
            player.setDeltaMovement(Vec3.ZERO);
            player.setBoundingBox(new AABB(
                    worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z,
                    worldCockpitPos.x, worldCockpitPos.y, worldCockpitPos.z));
            player.noPhysics = true;
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.getAbilities().setFlyingSpeed(0.0f);
            player.onUpdateAbilities();
            player.setInvulnerable(true);
        }
    }
}
