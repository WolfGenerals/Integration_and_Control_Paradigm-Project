package com.hainabaichuan75.iac_p.client.renderer;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.machine_gun.MachineGunBaseBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;

/**
 * AxisLineRenderer —— 在世界中渲染三色轴线。
 * <p>
 * 参考 Sable debug_render/LevelRendererMixin 的实现模式：
 * - 创建新 PoseStack，乘以 modelViewMatrix
 * - 手动减去摄像机坐标（x-cx, y-cy, z-cz）
 * - 使用 MultiBufferSource + RenderType.LINES + VertexConsumer
 * - 最后调用 endLastBatch() 提交
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = IACP.MODID)
public class AxisLineRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 仅当 F3+B（碰撞箱）开启时渲染，避免干扰正常游戏画面
        if (!mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) return;

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);

        // ============ 准备渲染上下文（参考 Sable debug render 模式） ============
        Camera camera = event.getCamera();
        Matrix4f modelView = event.getModelViewMatrix();
        double cx = camera.getPosition().x;
        double cy = camera.getPosition().y;
        double cz = camera.getPosition().z;

        PoseStack ps = new PoseStack();
        ps.mulPose(modelView);
        Matrix4f matrix = ps.last().pose();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.LINES);

        // ============ 测试线：玩家脚前 1 格向前延伸 5 格（白色） ============
        double px = mc.player.getX();
        double py = mc.player.getY() + 1.0;
        double pz = mc.player.getZ();
        double lx = mc.player.getLookAngle().x * 5;
        double ly = mc.player.getLookAngle().y * 5;
        double lz = mc.player.getLookAngle().z * 5;
        drawLine(consumer, matrix,
                px - cx, py - cy, pz - cz,
                px + lx - cx, py + ly - cy, pz + lz - cz,
                1f, 1f, 1f, 0.9f);

        // ============ 遍历所有 SubLevel，在每个 SubLevel 的原点画三色轴线 ============
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
            if (container != null) {
                int total = 0;
                for (SubLevel sl : container.getAllSubLevels()) {
                    total++;

                    // 获取渲染位姿（含位置+旋转+缩放）
                    Pose3dc renderPose;
                    if (sl instanceof ClientSubLevel csl) {
                        renderPose = csl.renderPose(partialTick);
                    } else {
                        renderPose = sl.logicalPose();
                    }
                    if (renderPose == null) continue;

                    // 从 renderPose 提取位置和旋转
                    var pos = renderPose.position();
                    double wx = pos.x(), wy = pos.y(), wz = pos.z();
                    var orient = renderPose.orientation(); // Quaterniondc

                    // 用旋转四元数计算三个局部轴方向 × 100 格长度
                    Vector3d localX = orient.transform(new Vector3d(100, 0, 0));
                    Vector3d localY = orient.transform(new Vector3d(0, 100, 0));
                    Vector3d localZ = orient.transform(new Vector3d(0, 0, 100));

                    // 绘制三色轴线：原点在 SubLevel 的世界位置
                    // 红 X
                    drawLine(consumer, matrix,
                            wx - cx, wy - cy, wz - cz,
                            wx + localX.x - cx, wy + localX.y - cy, wz + localX.z - cz,
                            1f, 0f, 0f, 0.9f);
                    // 绿 Y
                    drawLine(consumer, matrix,
                            wx - cx, wy - cy, wz - cz,
                            wx + localY.x - cx, wy + localY.y - cy, wz + localY.z - cz,
                            0f, 1f, 0f, 0.9f);
                    // 蓝 Z
                    drawLine(consumer, matrix,
                            wx - cx, wy - cy, wz - cz,
                            wx + localZ.x - cx, wy + localZ.y - cy, wz + localZ.z - cz,
                            0.2f, 0.4f, 1f, 0.9f);

                    // ============================================================
                    //  三色焦点标记：在该 SubLevel 上找地毯（炮塔底座）的位置
                    //  绘制一个 3×3×3 的三色三维十字线（红X 绿Y 蓝Z），两端各延伸 3 格
                    //  效果：在主世界精准定位物理结构上特定方块的位置
                    // ============================================================
                    java.util.List<BlockPos> carpetPositions = MachineGunBaseBlockEntity.getCarpetLocalPosMap().get(sl.getUniqueId());
                    if (carpetPositions != null && !carpetPositions.isEmpty()) {
                        for (BlockPos carpetLocalPos : carpetPositions) {
                            // 地毯中心在 SubLevel 局部坐标
                            Vector3d carpetLocal = new Vector3d(
                                    carpetLocalPos.getX() + 0.5,
                                    carpetLocalPos.getY() + 0.5,
                                    carpetLocalPos.getZ() + 0.5
                            );
                            // 用渲染位姿将局部坐标 → 世界坐标
                            renderPose.transformPosition(carpetLocal);
                            double cwx = carpetLocal.x;
                            double cwy = carpetLocal.y;
                            double cwz = carpetLocal.z;

                            // 焦点标记长度 3 格（两端各 3 格，总长 6 格）
                            double focusLen = 3.0;
                            Vector3d fx = orient.transform(new Vector3d(focusLen, 0, 0));
                            Vector3d fy = orient.transform(new Vector3d(0, focusLen, 0));
                            Vector3d fz = orient.transform(new Vector3d(0, 0, focusLen));

                            // 红 X 轴（双向延伸）
                            drawLine(consumer, matrix,
                                    cwx - fx.x - cx, cwy - fx.y - cy, cwz - fx.z - cz,
                                    cwx + fx.x - cx, cwy + fx.y - cy, cwz + fx.z - cz,
                                    1f, 0f, 0f, 0.9f);
                            // 绿 Y 轴（双向延伸）
                            drawLine(consumer, matrix,
                                    cwx - fy.x - cx, cwy - fy.y - cy, cwz - fy.z - cz,
                                    cwx + fy.x - cx, cwy + fy.y - cy, cwz + fy.z - cz,
                                    0f, 1f, 0f, 0.9f);
                            // 蓝 Z 轴（双向延伸）
                            drawLine(consumer, matrix,
                                    cwx - fz.x - cx, cwy - fz.y - cy, cwz - fz.z - cz,
                                    cwx + fz.x - cx, cwy + fz.y - cy, cwz + fz.z - cz,
                                    0.2f, 0.4f, 1f, 0.9f);

                        }
                    }
                }
            }
        } catch (Exception e) {
            IACP.LOGGER.error("[AxisRenderer] 异常", e);
        }

        // ============ 提交所有线段 ============
        bufferSource.endLastBatch();
    }

    /** 绘制一组三色轴线（3条线段: 红X 黄Y 蓝Z） */
    private static void drawAxis(VertexConsumer consumer, Matrix4f matrix,
                                  double ox, double oy, double oz,
                                  double xx, double xy, double xz,
                                  double yx, double yy, double yz,
                                  double zx, double zy, double zz) {
        drawLine(consumer, matrix, ox, oy, oz, xx, xy, xz, 1f, 0.2f, 0.2f, 0.9f); // 红 X
        drawLine(consumer, matrix, ox, oy, oz, yx, yy, yz, 1f, 1f, 0f, 0.9f);     // 黄 Y
        drawLine(consumer, matrix, ox, oy, oz, zx, zy, zz, 0.2f, 0.4f, 1f, 0.9f); // 蓝 Z
    }

    /** 绘制一条线段：addVertex(matrix, ...).setColor(...).setNormal(...) */
    private static void drawLine(VertexConsumer consumer, Matrix4f matrix,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  float r, float g, float b, float a) {
        consumer.addVertex(matrix, (float) x1, (float) y1, (float) z1)
                .setColor(r, g, b, a)
                .setNormal(0f, 1f, 0f);
        consumer.addVertex(matrix, (float) x2, (float) y2, (float) z2)
                .setColor(r, g, b, a)
                .setNormal(0f, 1f, 0f);
    }
}
