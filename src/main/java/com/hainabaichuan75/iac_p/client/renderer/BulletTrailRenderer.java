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

import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * BulletTrailRenderer —— 渲染开火弹道（白色线条）。
 * <p>
 * 直接从 {@link WeaponOverlay} 读取缓存的炮口+命中位置，
 * 不做任何 SubLevel 扫描（炮口在开火时已计算好）。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = IACP.MODID)
public class BulletTrailRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 读取所有炮台的弹道数据（无扫描，开火时已计算好）
        List<WeaponOverlay.TurretFireInstance> fires = WeaponOverlay.getActiveFires();
        if (fires.isEmpty()) return;

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
            drawLine(consumer, matrix,
                    fi.origin.x - cx, fi.origin.y - cy, fi.origin.z - cz,
                    fi.hitPos.x - cx, fi.hitPos.y - cy, fi.hitPos.z - cz,
                    1f, 1f, 1f, 0.9f);
        }

        bufferSource.endLastBatch();
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
