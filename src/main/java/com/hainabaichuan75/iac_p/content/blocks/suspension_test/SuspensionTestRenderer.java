package com.hainabaichuan75.iac_p.content.blocks.suspension_test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.offroad.index.OffroadPartialModels;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

/**
 * 悬挂渲染器 —— 使用 SuspensionTestBlockEntity 中的编译时常量控制偏移。
 * 修改常量后重编即可调整弹簧/转向轴/轮轴的视觉位置。
 */
public class SuspensionTestRenderer implements BlockEntityRenderer<SuspensionTestBlockEntity> {

    public SuspensionTestRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SuspensionTestBlockEntity be, float pt, PoseStack ms,
                       MultiBufferSource buf, int light, int overlay) {
        VertexConsumer vb = buf.getBuffer(RenderType.cutoutMipped());
        BlockState state = be.getBlockState();
        Direction dir = state.getValue(SuspensionTestBlock.HORIZONTAL_FACING).getOpposite();

        SuperByteBuffer teleOuter = CachedBuffers.partial(OffroadPartialModels.TELE_OUTER, state);
        SuperByteBuffer teleInner = CachedBuffers.partial(OffroadPartialModels.TELE_INNER, state);
        SuperByteBuffer teleMount = CachedBuffers.partial(OffroadPartialModels.TELE_MOUNT, state);
        SuperByteBuffer springTop = CachedBuffers.partial(OffroadPartialModels.SPRING_UPPER, state);
        SuperByteBuffer springBot = CachedBuffers.partial(OffroadPartialModels.SPRING_LOWER, state);
        SuperByteBuffer springMid = CachedBuffers.partial(OffroadPartialModels.SPRING_MIDDLE, state);

        // 从 BlockEntity 读取编译时常量（通过静态方法）
        double wheelPivot = SuspensionTestBlockEntity.wheelPivotZ();
        double hWPos = SuspensionTestBlockEntity.wheelPosZ();
        double vWPos = -be.getLerpedExtension(pt) + SuspensionTestBlockEntity.wheelPosY();
        double wSide = SuspensionTestBlockEntity.wheelPosX();

        double tMountH = SuspensionTestBlockEntity.pivotBlockZ();
        double tMountV = SuspensionTestBlockEntity.pivotBlockY();
        double tMountS = SuspensionTestBlockEntity.pivotBlockX();

        double sMountH = SuspensionTestBlockEntity.springBlockZ();
        double sMountV = SuspensionTestBlockEntity.springBlockY();
        double sMountS = SuspensionTestBlockEntity.springBlockX();

        // 弹簧轮子侧端点
        double sWheelH = SuspensionTestBlockEntity.springWheelZ();
        double sWheelV = SuspensionTestBlockEntity.springWheelY();
        double sWheelS = SuspensionTestBlockEntity.springWheelX();

        double teleAngle = Math.atan2(vWPos - tMountV, hWPos - wheelPivot - tMountH);
        double teleDist = new Vector2d(vWPos - tMountV, hWPos - wheelPivot - tMountH).length();

        double springAngle = Math.atan2(vWPos - sWheelV - sMountV, hWPos - sWheelH - sMountH);
        double springDist = new Vector2d(vWPos - sWheelV - sMountV, hWPos - sWheelH - sMountH).length();

        ms.pushPose();
        TransformStack.of(ms).center()
                .rotateYDegrees(AngleHelper.horizontalAngle(dir))
                .rotateXDegrees(AngleHelper.verticalAngle(dir))
                .uncenter();

        // === 望远镜管（转向轴） ===
        ms.pushPose();
        ms.translate(tMountS, tMountV + 6.0/16.0, 0);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.XP.rotation((float) teleAngle));
        ms.translate(-0.5, -0.5, -0.5);
        teleOuter.light(light).renderInto(ms, vb);
        ms.translate(0, 0, -(teleDist - 1.0));
        teleInner.light(light).renderInto(ms, vb);
        ms.popPose();

        // === 轮子 ===
        ms.pushPose();
        ms.translate(wSide, vWPos, 26.0/16.0 - hWPos);
        ms.translate(0.5, 0.5, 0.5);
        ms.rotateAround(Axis.YP.rotation((float) be.getLerpedYaw(pt)),
                0, 0, (float)(-hWPos + 6.0/16.0));
        ms.translate(-0.5, -0.5, -0.5);
        teleMount.light(light).renderInto(ms, vb);

        ms.translate(0.5, 0.5, 0.5);
        ms.translate(0, 0, -26.0/16.0f);

        double sign = -be.getLerpedAngle(pt)
                * (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1)
                * (dir.getAxis() == Direction.Axis.X ? 1 : -1);
        ms.mulPose(Axis.ZP.rotation((float) sign));

        ItemStack stack = be.getHeldItem();
        var tire = stack.get(OffroadDataComponents.TIRE);
        if (tire != null) {
            Vec3 rot = tire.rotation();
            ms.mulPose(Axis.XP.rotation((float) Math.toRadians(rot.x)));
            ms.mulPose(Axis.YP.rotation((float) Math.toRadians(rot.y)));
            ms.mulPose(Axis.ZP.rotation((float) Math.toRadians(rot.z)));

            if (tire.model().isPresent()) {
                ResourceLocation m = tire.model().get();
                ms.translate(tire.offset().x, tire.offset().y, tire.offset().z);
                CachedBuffers.partial(PartialModel.of(m), state)
                        .light(light).translate(-0.5f, 0, -0.5f).renderInto(ms, vb);
            } else {
                ms.translate(tire.offset().x, tire.offset().y, tire.offset().z);
                Minecraft.getInstance().getItemRenderer()
                        .renderStatic(stack, ItemDisplayContext.NONE, light, overlay, ms, buf, be.getLevel(), 0);
            }
        }
        ms.popPose();

        // === 弹簧 ===
        ms.pushPose();
        ms.translate(0.5 + sMountS, 0.5 + sMountV, 0.5 - sMountH);
        ms.mulPose(Axis.XP.rotation((float) springAngle + Mth.PI / 2.0f));
        ms.translate(-0.5 - sMountS, -0.5 - sMountV, -0.5 + sMountH);

        float span = (float) springDist - 4.0f / 16.0f;
        springTop.light(light).renderInto(ms, vb);
        springMid.light(light)
                .translate(0, 13f/16f, 0)
                .scale(1, span / (14f/16f), 1)
                .translateBack(0, 13f/16f, 0)
                .renderInto(ms, vb);
        springBot.light(light)
                .translate(0, -(span + -14.0/16.0), 0)
                .renderInto(ms, vb);
        ms.popPose();

        ms.popPose();
    }

    @Override
    public int getViewDistance() { return 512; }
}
