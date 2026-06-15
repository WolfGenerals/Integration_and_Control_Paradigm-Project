package com.hainabaichuan75.iac_p.client.renderer;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.WeaponOverlay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * BulletTrailRenderer —— 渲染开火弹道（白色线条）。
 * <p>
 * 使用与 {@link AxisLineRenderer} 相同的渲染模式（已验证工作）：
 * <ul>
 * <li>阶段：{@link RenderLevelStageEvent.Stage#AFTER_LEVEL}</li>
 * <li>线条：{@link RenderType#LINES}</li>
 * <li>坐标：摄像机相对坐标（world - camera）</li>
 * <li>刷新：{@link MultiBufferSource.BufferSource#endLastBatch()}</li>
 * </ul>
 * <p>
 * 起点使用 {@link WeaponOverlay.TurretFireInstance#origin}（开火时固定的世界坐标），
 * 不跟随炮口移动，消除动态炮口位置带来的弹道抖动。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = IACP.MODID)
public class BulletTrailRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        List<WeaponOverlay.TurretFireInstance> fires = WeaponOverlay.getActiveFires();
        if (fires.isEmpty()) {
            return;
        }

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

        for (WeaponOverlay.TurretFireInstance fi : fires) {
            // 直接使用开火时固定的起点，不动态计算
            Vec3 origin = fi.origin;
            if (origin == null) {
                drawPoint(consumer, matrix, fi.hitPos, cx, cy, cz);
                continue;
            }
            drawLine(consumer, matrix,
                    origin.x - cx, origin.y - cy, origin.z - cz,
                    fi.hitPos.x - cx, fi.hitPos.y - cy, fi.hitPos.z - cz,
                    1f, 1f, 1f, 0.9f);
        }

        bufferSource.endLastBatch();
    }

    /**
     * 在命中点画一个点（降级：找不到起点时使用）。
     */
    private static void drawPoint(VertexConsumer consumer, Matrix4f matrix,
            Vec3 hitPos, double cx, double cy, double cz) {
        float x = (float) (hitPos.x - cx);
        float y = (float) (hitPos.y - cy);
        float z = (float) (hitPos.z - cz);
        float s = 0.1f;
        consumer.addVertex(matrix, x - s, y - s, z - s).setColor(1f, 1f, 1f, 0.5f).setNormal(0f, 1f, 0f);
        consumer.addVertex(matrix, x + s, y + s, z + s).setColor(1f, 1f, 1f, 0.5f).setNormal(0f, 1f, 0f);
    }

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
