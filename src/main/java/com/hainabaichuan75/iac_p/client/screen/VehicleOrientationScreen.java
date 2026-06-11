package com.hainabaichuan75.iac_p.client.screen;

import com.hainabaichuan75.iac_p.client.VehicleOrientationData;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.SmartMapC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 载具朝向与智能映射界面（对着驾驶舱按 C 打开）。
 * <p>
 * 上半部分：显示悬挂 FACING 统计和朝向推断结果。
 * 下半部分：交互按钮——汽车模式、反转方向、智能映射开关。
 * <p>
 * 布局：
 * ┌─────────────────────────────────────────┐
 * │          载具朝向信息                     │
 * │       悬挂总数: 4                        │
 * │                                         │
 * │  北: 2             南: 0                │
 * │  东: 0             西: 2                │
 * │  ──────────────────────────────────      │
 * │  东西总和: 2       南北总和: 2           │
 * │  宽度轴: 东西 (X)  前进轴: 南/北 (Z)    │
 * │                                         │
 * │  [ 汽车模式 ]  [ 反转方向 ]               │
 * │  [ 关闭智能映射 ← 绿色/红色 ]             │
 * │  [ 关闭 ]                                │
 * └─────────────────────────────────────────┘
 */
public class VehicleOrientationScreen extends Screen {

    private final VehicleOrientationData data;
    private final UUID subLevelUUID;
    private final int totalSuspensions;

    // 按钮引用（用于切换文字）
    private Button toggleBtn;
    private boolean smartMappingActive;

    private static final int TITLE_Y = 12;
    private static final int STAT_START_Y = 42;
    private static final int LINE_HEIGHT = 13;
    private static final int BTN_W = 120;
    private static final int BTN_H = 20;

    public VehicleOrientationScreen(VehicleOrientationData data, UUID subLevelUUID, boolean smartMappingActive) {
        super(Component.translatable("screen.iac_p.vehicle_orientation.title"));
        this.data = data;
        this.subLevelUUID = subLevelUUID;
        this.totalSuspensions = data.total();
        this.smartMappingActive = smartMappingActive;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int btnY = TITLE_Y + 180; // 信息区下方

        // ── 第1行：汽车模式 + 反转方向 ──
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.vehicle_orientation.car_mode"),
                btn -> {
                    sendAction(SmartMapC2SPacket.Action.CAR_MODE);
                    // 汽车模式自带动智能映射开启，同步本地 toggle 状态
                    smartMappingActive = true;
                    if (toggleBtn != null) toggleBtn.setMessage(buildToggleLabel());
                }
        )
                .bounds(centerX - BTN_W - 8, btnY, BTN_W, BTN_H)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.vehicle_orientation.reverse"),
                btn -> {
                    sendAction(SmartMapC2SPacket.Action.REVERSE);
                    // 反转后显示提示
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("message.iac_p.smart_map_reversed"), true);
                    }
                }
        )
                .bounds(centerX + 8, btnY, BTN_W, BTN_H)
                .build());

        // ── 第2行：智能映射开关 ──
        this.toggleBtn = this.addRenderableWidget(Button.builder(
                buildToggleLabel(),
                btn -> {
                    sendAction(SmartMapC2SPacket.Action.TOGGLE_SMART);
                    smartMappingActive = !smartMappingActive;
                    toggleBtn.setMessage(buildToggleLabel());
                }
        )
                .bounds(centerX - 90, btnY + BTN_H + 6, 180, BTN_H)
                .build());

        // ── 第3行：关闭按钮 ──
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.vehicle_orientation.close"),
                btn -> this.onClose()
        )
                .bounds(centerX - 50, btnY + (BTN_H + 6) * 2, 100, BTN_H)
                .build());
    }

    /** 构建开关按钮的文字 */
    private Component buildToggleLabel() {
        if (smartMappingActive) {
            return Component.translatable("screen.iac_p.vehicle_orientation.smart_on");
        } else {
            return Component.translatable("screen.iac_p.vehicle_orientation.smart_off");
        }
    }

    /** 发送智能映射指令到服务端 */
    private void sendAction(SmartMapC2SPacket.Action action) {
        ModNetworking.sendToServer(new SmartMapC2SPacket(action, subLevelUUID));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── 标题 ──
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_orientation.title").getString(),
                centerX, TITLE_Y, 0xFFFFFF);

        if (totalSuspensions == 0) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.iac_p.vehicle_orientation.no_suspension").getString(),
                    centerX, STAT_START_Y, 0xFF5555);
            return;
        }

        // ── 悬挂总数 ──
        graphics.drawCenteredString(this.font,
                "§e" + Component.translatable("screen.iac_p.vehicle_orientation.total", totalSuspensions).getString(),
                centerX, TITLE_Y + 11, 0xAAAAAA);

        // ── 四方向统计（两列） ──
        int col1X = centerX - 120;
        int col2X = centerX + 20;

        graphics.drawString(this.font,
                "§b" + Component.translatable("screen.iac_p.vehicle_orientation.north").getString() + ":",
                col1X, STAT_START_Y, 0xFFFFFF);
        graphics.drawString(this.font, "§e" + data.north(),
                col1X + 120, STAT_START_Y, 0xFFFF55);

        graphics.drawString(this.font,
                "§b" + Component.translatable("screen.iac_p.vehicle_orientation.south").getString() + ":",
                col2X, STAT_START_Y, 0xFFFFFF);
        graphics.drawString(this.font, "§e" + data.south(),
                col2X + 120, STAT_START_Y, 0xFFFF55);

        graphics.drawString(this.font,
                "§b" + Component.translatable("screen.iac_p.vehicle_orientation.east").getString() + ":",
                col1X, STAT_START_Y + LINE_HEIGHT, 0xFFFFFF);
        graphics.drawString(this.font, "§e" + data.east(),
                col1X + 120, STAT_START_Y + LINE_HEIGHT, 0xFFFF55);

        graphics.drawString(this.font,
                "§b" + Component.translatable("screen.iac_p.vehicle_orientation.west").getString() + ":",
                col2X, STAT_START_Y + LINE_HEIGHT, 0xFFFFFF);
        graphics.drawString(this.font, "§e" + data.west(),
                col2X + 120, STAT_START_Y + LINE_HEIGHT, 0xFFFF55);

        // ── 分隔线 ──
        int sepY = STAT_START_Y + LINE_HEIGHT * 2 + 6;
        graphics.hLine(centerX - 140, centerX + 140, sepY, 0x55555555);

        // ── 汇总推断 ──
        int summaryY = sepY + 10;

        int ewTotal = data.eastWestTotal();
        int nsTotal = data.northSouthTotal();
        boolean ewDominant = ewTotal > nsTotal;
        boolean nsDominant = nsTotal > ewTotal;

        graphics.drawString(this.font,
                "§e" + Component.translatable("screen.iac_p.vehicle_orientation.ew_total").getString() + ":",
                col1X, summaryY, 0xFFFFFF);
        graphics.drawString(this.font, "§" + (ewDominant ? "a" : "7") + ewTotal,
                col1X + 120, summaryY, ewDominant ? 0x55FF55 : 0xAAAAAA);

        graphics.drawString(this.font,
                "§e" + Component.translatable("screen.iac_p.vehicle_orientation.ns_total").getString() + ":",
                col2X, summaryY, 0xFFFFFF);
        graphics.drawString(this.font, "§" + (nsDominant ? "a" : "7") + nsTotal,
                col2X + 120, summaryY, nsDominant ? 0x55FF55 : 0xAAAAAA);

        // 推断结果
        int inferenceY = summaryY + LINE_HEIGHT + 8;
        graphics.drawCenteredString(this.font,
                "§6" + Component.translatable("screen.iac_p.vehicle_orientation.width_axis").getString()
                        + ": §f" + data.getWidthAxisDisplay(),
                centerX, inferenceY, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§6" + Component.translatable("screen.iac_p.vehicle_orientation.forward_axis").getString()
                        + ": §f" + data.getForwardAxisDisplay(),
                centerX, inferenceY + LINE_HEIGHT, 0xFFFFFF);

        // ── 当前智能映射状态条 ──
        int statusY = inferenceY + LINE_HEIGHT * 2 + 6;
        String statusColor = smartMappingActive ? "§a" : "§7";
        graphics.drawCenteredString(this.font,
                statusColor + Component.translatable(
                        smartMappingActive
                                ? "screen.iac_p.vehicle_orientation.status_active"
                                : "screen.iac_p.vehicle_orientation.status_inactive"
                ).getString(),
                centerX, statusY, smartMappingActive ? 0x55FF55 : 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
