package com.hainabaichuan75.iac_p.client.renderer;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.client.WeaponOverlay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;

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
 * 起点从 {@link WeaponOverlay.TurretFireInstance#subLevelId} 对应的避雷针 SubLevel 每帧通过
 * {@code renderPose(partialTick)} 重新计算，确保线条始终连接炮口当前位置，消除因炮塔旋转/相机移动导致的抖动。
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

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) {
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
            // 每帧从避雷针 SubLevel 的 renderPose 重新计算炮口世界坐标，再转为摄像机相对坐标
            Vec3 dynamicOrigin = computeMuzzlePosition(container, fi.subLevelId, partialTick);
            if (dynamicOrigin == null) {
                drawPoint(consumer, matrix, fi.hitPos, cx, cy, cz);
                continue;
            }
            drawLine(consumer, matrix,
                    dynamicOrigin.x - cx, dynamicOrigin.y - cy, dynamicOrigin.z - cz,
                    fi.hitPos.x - cx, fi.hitPos.y - cy, fi.hitPos.z - cz,
                    1f, 1f, 1f, 0.9f);
        }

        bufferSource.endLastBatch();
    }

    /**
     * 计算避雷针 SubLevel 在当前帧的炮口世界坐标。 逻辑与 {@code WeaponOverlay.fireSingleTurret()}
     * 中的炮口计算一致。
     */
    @javax.annotation.Nullable
    private static Vec3 computeMuzzlePosition(SubLevelContainer container, UUID subLevelId, float partialTick) {
        if (subLevelId == null) {
            return null;
        }
        SubLevel sl = container.getSubLevel(subLevelId);
        if (!(sl instanceof ClientSubLevel csl) || csl.isRemoved()) {
            return null;
        }

        var rodPlot = csl.getPlot();
        if (rodPlot == null) {
            return null;
        }

        var pose = csl.renderPose(partialTick);
        if (pose == null) {
            return null;
        }

        // 方块中心在 plot 局部空间 → 变换到主世界
        var localBP = rodPlot.getCenterBlock();
        var localCenter = new Vector3d(localBP.getX() + 0.5, localBP.getY() + 0.5, localBP.getZ() + 0.5);
        var worldCenter = pose.transformPosition(localCenter);

        // 炮管朝向（Z 轴正向）
        Vector3d fwd = new Vector3d(0, 0, 1);
        fwd.rotate(pose.orientation());

        return new Vec3(
                worldCenter.x + fwd.x * 0.5,
                worldCenter.y + fwd.y * 0.5,
                worldCenter.z + fwd.z * 0.5);
    }

    /**
     * 在命中点画一个点（降级：找不到炮口位置时使用）。
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
