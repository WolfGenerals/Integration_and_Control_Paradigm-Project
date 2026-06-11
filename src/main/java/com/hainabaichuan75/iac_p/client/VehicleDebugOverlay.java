package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;

import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 载具调试信息覆盖层。
 * <p>
 * 上车时在右下角显示动力系统与物理参数，每 3 ticks 刷新数据。
 * 文字左对齐，自动换行（单行数据），半透明黑底，F3 同款风格。
 * 通过 {@link RenderGuiEvent.Post} 渲染，注册到游戏事件总线。
 */
@EventBusSubscriber(modid = "iac_p", value = Dist.CLIENT)
public class VehicleDebugOverlay {

    /** 数据刷新间隔（tick） */
    private static final int UPDATE_INTERVAL = 3;
    private static int updateCooldown = 0;

    // ===== 缓存数据 =====
    private static double engineRpm = 0;
    /** 质量自适应有效扭矩，由 CockpitBE 同步到客户端 */
    private static double engineTorque = CockpitBlockEntity.ENGINE_TORQUE;
    private static int gear = 0;
    private static double gearboxRpm = 0;
    private static double gearboxTorque = 0;
    private static int totalWheels = 0;
    private static int wheelsWithTire = 0;
    private static double avgWheelRpm = 0;
    private static double avgWheelTorque = 0;
    private static double mass = 0;
    private static double idealSpeedMs = 0;
    private static double currentSpeedMs = 0;
    private static double frictionPct = 0;
    /** 力需求/摩擦预算比率（可 > 100%，表示打滑程度） */
    private static double frictionDemandRatio = 0;

    /** 渲染行缓存 */
    private static final List<Component> displayLines = new ArrayList<>();

    // ====================================================================
    //  渲染入口
    // ====================================================================

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!ClientMountHandler.isMounted()) {
            if (!displayLines.isEmpty()) displayLines.clear();
            return;
        }

        // 定时收集数据
        if (--updateCooldown <= 0) {
            updateCooldown = UPDATE_INTERVAL;
            collectData(mc);
        }

        if (displayLines.isEmpty()) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int lineH = font.lineHeight + 2;
        int padX = 5, padY = 4;

        // 最大行宽
        int maxW = 0;
        for (Component line : displayLines) {
            int w = font.width(line);
            if (w > maxW) maxW = w;
        }

        var window = mc.getWindow();
        int sw = window.getGuiScaledWidth();
        int sh = window.getGuiScaledHeight();
        int bl = sw - maxW - padX * 2 - 4;
        int br = sw - 2;
        int bt = sh - displayLines.size() * lineH - padY * 2 - 4;
        int bb = sh - 2;

        // 半透明黑底
        g.fill(bl, bt, br, bb, 0x88000000);

        // 文字左对齐
        int tx = bl + padX;
        int ty = bt + padY;
        for (Component line : displayLines) {
            g.drawString(font, line, tx, ty, 0xFFFFFFFF, true);
            ty += lineH;
        }
    }

    // ====================================================================
    //  数据收集
    // ====================================================================

    private static void collectData(Minecraft mc) {
        ClientSubLevel sl = ClientMountHandler.getMountedClientSubLevel();
        if (sl == null) return;
        LevelPlot plot = sl.getPlot();
        if (plot == null) return;

        CockpitBlockEntity cockpit = null;
        List<SuspensionTestBlockEntity> susp = new ArrayList<>();
        double totalRadius = 0;
        boolean anyLifted = false;      // 是否有轮子离地
        int onGroundCount = 0;          // 接地轮数
        // 记录一个 SubLevel 内的世界坐标点，用于后续查询速度
        BlockPos samplePos = null;

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic bb = chunk.getBoundingBox();
            if (bb == null || bb == BoundingBox3i.EMPTY) continue;
            int cmx = chunk.getPos().getMinBlockX();
            int cmz = chunk.getPos().getMinBlockZ();
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int y = bb.minY(); y <= bb.maxY(); y++) {
                    for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                        BlockPos wp = new BlockPos(x + cmx, y, z + cmz);
                        if (samplePos == null) samplePos = wp;
                        BlockState st = mc.level.getBlockState(wp);
                        BlockEntity be = mc.level.getBlockEntity(wp);
                        if (be instanceof CockpitBlockEntity cbe) {
                            cockpit = cbe;
                        } else if (be instanceof SuspensionTestBlockEntity sbe
                                && st.getBlock() instanceof SuspensionTestBlock) {
                            susp.add(sbe);
                            if (sbe.isLifted()) {
                                anyLifted = true;
                            } else {
                                onGroundCount++;
                            }
                            var tire = sbe.getHeldItem().get(OffroadDataComponents.TIRE);
                            if (tire != null) totalRadius += tire.radius();
                        }
                    }
                }
            }
        }

        totalWheels = susp.size();
        wheelsWithTire = 0;
        for (var s : susp) {
            if (s.getHeldItem().has(OffroadDataComponents.TIRE)) wheelsWithTire++;
        }
        double avgR = wheelsWithTire > 0 ? totalRadius / wheelsWithTire : 0.25;

        // 动力系统
        if (cockpit != null) {
            engineRpm      = cockpit.getEngineRpm();
            engineTorque   = cockpit.getEffectiveTorque();
            gear           = cockpit.getCurrentGear();
            int w          = Math.max(totalWheels, 1);
            var out        = cockpit.getWheelOutput(w);
            avgWheelRpm    = out.wheelRpm();
            avgWheelTorque = out.wheelTorque();
            gearboxRpm     = out.wheelRpm();
            gearboxTorque  = out.wheelTorque() * w;
        } else {
            engineRpm = 0; gear = 0;
            gearboxRpm = 0; gearboxTorque = 0;
            avgWheelRpm = 0; avgWheelTorque = 0;
        }

        // 速度（使用 SubLevel 内部已知方块位置查询，避免 pose.position() 不在 Plot 内）
        idealSpeedMs = avgWheelRpm * Math.PI * 2.0 / 60.0 * avgR;
        if (samplePos != null) {
            Vector3d vel = Sable.HELPER.getVelocity(mc.level,
                    new org.joml.Vector3d(samplePos.getX() + 0.5, samplePos.getY() + 0.5, samplePos.getZ() + 0.5));
            currentSpeedMs = vel != null ? vel.length() : 0;
        } else {
            currentSpeedMs = 0;
        }

        // 质量：优先使用服务端同步的实际物理质量，降级使用固定估算值
        // 降级路径移除了全量扫描（服务端始终在 mount 时同步 mass，降级极少触发）
        double serverMass = ClientMountHandler.getVehicleMass();
        if (serverMass > 0) {
            mass = serverMass;
        } else {
            mass = 2000.0; // 降级：按单方块估算，忽略全量扫描
        }

        // 力需求/摩擦预算比（基于物理的摩擦圆约束值）：
        //   < 100% = 抓地有余
        //   = 100% = 摩擦圆刚好饱和
        //   > 100% = 需求超抓地 → 轮子空转/打滑（显示动力盈余）
        // 取所有轮子的最大值（最恶劣的那个轮子）
        frictionDemandRatio = 0;
        for (var s : susp) {
            double r = s.getFrictionDemandRatio();
            if (r > frictionDemandRatio) frictionDemandRatio = r;
        }
        frictionPct = frictionDemandRatio * 100.0;

        buildLines();
    }

    // ====================================================================
    //  文字构建
    // ====================================================================

    private static void buildLines() {
        displayLines.clear();
        displayLines.add(line("debug.iac_p.overlay.mass",        String.format("%,.0f kg", mass)));
        displayLines.add(line("debug.iac_p.overlay.engine",      String.format("%,.0f RPM  |  %.0f Nm", engineRpm, engineTorque)));
        displayLines.add(line("debug.iac_p.overlay.gear_wheels", (gear == -1 ? "R" : gear == 0 ? "N" : String.valueOf(gear))
                + " 档  |  " + wheelsWithTire + "/" + totalWheels + " 轮着地"));
        displayLines.add(line("debug.iac_p.overlay.gearbox_out", String.format("%,.0f RPM  |  %.1f Nm", gearboxRpm, gearboxTorque)));
        displayLines.add(line("debug.iac_p.overlay.tire_avg",    String.format("%,.0f RPM  |  %.1f Nm/轮", avgWheelRpm, avgWheelTorque)));
        displayLines.add(line("debug.iac_p.overlay.ideal_speed", String.format("%.2f m/s", idealSpeedMs)));
        displayLines.add(line("debug.iac_p.overlay.current_speed", String.format("%.2f m/s  (%.1f km/h)", currentSpeedMs, currentSpeedMs * 3.6)));
        displayLines.add(line("debug.iac_p.overlay.friction_demand", String.format("%.0f%%", frictionPct)));
    }

    private static Component line(String labelKey, String value) {
        return Component.translatable(labelKey).append(": ").append(Component.literal(value));
    }
}
