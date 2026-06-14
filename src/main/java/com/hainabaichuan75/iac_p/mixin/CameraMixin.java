package com.hainabaichuan75.iac_p.mixin;

import com.hainabaichuan75.iac_p.Config;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.ClientMountHandler;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plan B1 核心 Mixin：轨道摄像机 —— 镜头始终对准 SubLevel 焦点， 鼠标控制摄像机在球面上的环绕位置。
 * <p>
 * 原理：{@link Camera#setup(BlockGetter, Entity, boolean, boolean, float)} 在 setup
 * 完成后（@At("TAIL")），将摄像机位置设为 SubLevel 焦点周围的 球坐标位置，再计算从摄像机指向焦点的方向向量，强制设置摄像机旋转，
 * 实现"镜头始终对准焦点，鼠标控制环绕"的轨道摄像机效果。
 */
@Mixin(Camera.class)
public class CameraMixin {

    @Shadow
    private void setPosition(Vec3 position) {
    }

    @Shadow
    private void setRotation(float yRot, float xRot) {
    }

    /**
     * 在 Camera.setup() 完成后，若玩家处于骑乘状态，将摄像机改为轨道模式： 位置随鼠标在球面上环绕，旋转始终指向 SubLevel
     * 焦点。
     * <p>
     * 整个方法包裹在 try-catch 中，防止渲染异常导致游戏卡死。
     */
    @Inject(method = "setup", at = @At("TAIL"))
    private void iacp$afterCameraSetup(
            BlockGetter level, Entity entity,
            boolean thirdPerson, boolean inverseView,
            float partialTick, CallbackInfo ci
    ) {
        try {
            if (!ClientMountHandler.isMounted()) {
                return;
            }

            ClientSubLevel clientSubLevel = ClientMountHandler.getMountedClientSubLevel();
            if (clientSubLevel == null) {
                // 骑乘标记已设置但客户端 SubLevel 尚不可用（网络延迟/同步时序）
                // 这是正常情况，不应导致任何异常
                return;
            }

            // 使用与渲染完全相同的 partialTick 获取平滑插值位姿
            Pose3dc renderPose = clientSubLevel.renderPose(partialTick);
            if (renderPose == null) {
                return;
            }

            var renderPos = renderPose.position();

            // 获取 SubLevel 物理结构的世界空间包围盒（用于自适应计算）
            BoundingBox3dc bbox = clientSubLevel.boundingBox();

            // === 自适应摄像机高度 ===
            // 开启后：焦点高度 = maxY + 1 + 手动偏移，确保镜头始终比载具最高点高出 1 格
            // 关闭后：焦点高度 = renderPos.y + 手动偏移（原版行为）
            double focusY;
            if (Config.CAMERA_ADAPTIVE_HEIGHT.get()) {
                focusY = bbox.maxY() + 1.0 + Config.CAMERA_HEIGHT_OFFSET.get();
            } else {
                focusY = renderPos.y() + Config.CAMERA_HEIGHT_OFFSET.get();
            }
            double focusX = renderPos.x();
            double focusZ = renderPos.z();

            // === 自适应摄像机距离 ===
            // 开启后：距离 = 手动距离 + 边框最长边 / 2，确保镜头能完整包裹载具
            // 关闭后：距离 = 手动距离（原版行为）
            double distance = Config.CAMERA_DISTANCE.get();
            if (Config.CAMERA_ADAPTIVE_DISTANCE.get()) {
                double lenX = bbox.maxX() - bbox.minX();
                double lenY = bbox.maxY() - bbox.minY();
                double lenZ = bbox.maxZ() - bbox.minZ();
                double longestSide = Math.max(lenX, Math.max(lenY, lenZ));
                distance += longestSide / 2.0;
            }

            if (distance > 0.0 && entity != null) {
                // === 哨兵摄像机模式：位置冻结，始终看向焦点 ===
                if (ClientMountHandler.isCameraStationary()) {
                    Vec3 frozenPos = ClientMountHandler.getStationaryCameraPos();
                    if (frozenPos != null) {
                        this.setPosition(frozenPos);

                        // 从冻结位置指向焦点
                        double lookX = focusX - frozenPos.x;
                        double lookY = focusY - frozenPos.y;
                        double lookZ = focusZ - frozenPos.z;
                        double horizontalDist = Math.sqrt(lookX * lookX + lookZ * lookZ);

                        float lookPitch = (float) -Mth.atan2(lookY, Math.max(horizontalDist, 1e-4)) * Mth.RAD_TO_DEG;
                        float lookYaw;
                        if (horizontalDist < 1e-4) {
                            lookYaw = entity.getYRot();
                        } else {
                            lookYaw = (float) Mth.atan2(lookZ, lookX) * Mth.RAD_TO_DEG - 90.0F;
                        }
                        this.setRotation(lookYaw, lookPitch);

                        IACP.LOGGER.debug("[哨兵摄像机] 追踪焦点 @ {}", frozenPos);
                        return; // 跳过轨道模式
                    }
                }

                // === 轨道模式：摄像机在球面上环绕，始终看向焦点 ===
                float yaw = entity.getYRot();
                float pitch = entity.getXRot();

                // 球坐标 → 摄像机位置（在焦点周围的球面上）
                double dx = Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD) * distance;
                // dy 符号受反转 Y 轴配置控制：
                //   关闭反转（默认）= +sin(pitch)：鼠标上移→摄像机上升
                //   开启反转        = -sin(pitch)：鼠标上移→摄像机下降
                double dySign = Config.CAMERA_INVERT_Y.get() ? -1.0 : 1.0;
                double dy = dySign * Mth.sin(pitch * Mth.DEG_TO_RAD) * distance;
                double dz = -Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD) * distance;

                Vec3 cameraPos = new Vec3(focusX + dx, focusY + dy, focusZ + dz);
                this.setPosition(cameraPos);

                // 计算从摄像机指向焦点的方向 → 强制摄像机看向焦点
                double lookX = focusX - cameraPos.x;
                double lookY = focusY - cameraPos.y;
                double lookZ = focusZ - cameraPos.z;
                double horizontalDist = Math.sqrt(lookX * lookX + lookZ * lookZ);

                float lookPitch = (float) -Mth.atan2(lookY, Math.max(horizontalDist, 1e-4)) * Mth.RAD_TO_DEG;

                float lookYaw;
                if (horizontalDist < 1e-4) {
                    // 极点处理：当俯仰接近 ±90° 时，水平距离趋于零，
                    // atan2(0,0) 会返回 0 导致偏航角突变为 -90°。
                    // 此时沿用实体的 yRot，避免画面突然旋转。
                    lookYaw = entity.getYRot();
                } else {
                    lookYaw = (float) Mth.atan2(lookZ, lookX) * Mth.RAD_TO_DEG - 90.0F;
                }

                this.setRotation(lookYaw, lookPitch);
            } else {
                // 零距离：摄像机位于焦点（保持原版旋转）
                this.setPosition(new Vec3(focusX, focusY, focusZ));
            }
        } catch (Exception e) {
            IACP.LOGGER.error("[CameraMixin] 轨道摄像机异常: {}", e.getMessage());
        }
    }
}
