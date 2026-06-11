package com.hainabaichuan75.iac_p.client.screen;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.AnchorConfigC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.GrindstoneConfigC2SPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * GrindstoneConfigScreen —— 砂轮/避雷针配置界面（C 键打开）。
 * <p>
 * 左列：6 个方向按钮（2列×3行）+ 关闭按钮。
 * 右列：锚点 A 坐标编辑（X / Y / Z 输入框 + 保存按钮）。
 */
public class GrindstoneConfigScreen extends Screen {

    private static final int TITLE_Y = 10;
    private static final int BTN_W = 68;
    private static final int BTN_H = 22;
    private static final int GAP = 6;

    private final UUID subLevelUUID;

    // 坐标编辑框
    private EditBox editX;
    private EditBox editY;
    private EditBox editZ;
    private Button saveAnchorBtn;

    public GrindstoneConfigScreen(UUID subLevelUUID) {
        super(Component.translatable("screen.iac_p.grindstone_config.title"));
        this.subLevelUUID = subLevelUUID;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2;

        // ================================================================
        //  左侧面板：方向按钮（2列×3行）+ 关闭
        // ================================================================
        int leftPanelX = cx - 150;
        int panelTop  = cy - 50;

        Direction[] dirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        String[] labelKeys = {
                "screen.iac_p.grindstone_config.dir_down",
                "screen.iac_p.grindstone_config.dir_up",
                "screen.iac_p.grindstone_config.dir_north",
                "screen.iac_p.grindstone_config.dir_south",
                "screen.iac_p.grindstone_config.dir_west",
                "screen.iac_p.grindstone_config.dir_east"
        };

        for (int i = 0; i < dirs.length; i++) {
            final Direction dir = dirs[i];
            final String dirKey = labelKeys[i];
            int col = i % 2;
            int row = i / 2;
            this.addRenderableWidget(Button.builder(
                    Component.translatable(dirKey),
                    btn -> onDirectionClicked(dir))
                    .bounds(leftPanelX + col * (BTN_W + GAP), panelTop + row * (BTN_H + GAP), BTN_W, BTN_H)
                    .build());
        }

        // 关闭按钮（方向按钮下方）
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.grindstone_config.close"),
                btn -> this.onClose())
                .bounds(leftPanelX, panelTop + 3 * (BTN_H + GAP) + 6, BTN_W * 2 + GAP, 20)
                .build());

        // ================================================================
        //  右侧面板：锚点坐标编辑
        // ================================================================
        int rightPanelX = cx + 20;
        int rightTop     = panelTop;

        double[] anchor = getCurrentAnchor();

        // X 输入框
        int labelLeft = rightPanelX;
        int fieldLeft = rightPanelX + 12;
        int fieldW    = 100;
        int fieldH    = 16;

        // X
        this.editX = new EditBox(this.font, fieldLeft, rightTop, fieldW, fieldH, Component.literal("X"));
        this.editX.setValue(formatCoord(anchor[0]));
        this.editX.setMaxLength(12);
        this.addRenderableWidget(this.editX);

        // Y
        this.editY = new EditBox(this.font, fieldLeft, rightTop + 24, fieldW, fieldH, Component.literal("Y"));
        this.editY.setValue(formatCoord(anchor[1]));
        this.editY.setMaxLength(12);
        this.addRenderableWidget(this.editY);

        // Z
        this.editZ = new EditBox(this.font, fieldLeft, rightTop + 48, fieldW, fieldH, Component.literal("Z"));
        this.editZ.setValue(formatCoord(anchor[2]));
        this.editZ.setMaxLength(12);
        this.addRenderableWidget(this.editZ);

        // 保存按钮
        this.saveAnchorBtn = Button.builder(
                Component.translatable("screen.iac_p.grindstone_config.save_anchor"),
                btn -> onSaveAnchor())
                .bounds(rightPanelX, rightTop + 72, 120, 20)
                .build();
        this.addRenderableWidget(this.saveAnchorBtn);
    }

    /** 从客户端锚点缓存读取当前值，无则返回 (0,0,0) */
    private double[] getCurrentAnchor() {
        double[] fromMap = TurretBaseBlockEntity.getAnchorMap().get(this.subLevelUUID);
        return (fromMap != null && fromMap.length >= 3) ? fromMap : new double[]{0.0, 0.0, 0.0};
    }

    private static String formatCoord(double val) {
        return String.format("%.2f", val);
    }

    private static double parseCoord(EditBox box) {
        String t = box.getValue().trim();
        if (t.isEmpty()) return 0.0;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return 0.0; }
    }

    private void onDirectionClicked(Direction dir) {
        ModNetworking.sendToServer(new GrindstoneConfigC2SPacket(subLevelUUID, dir));
        this.onClose();
    }

    private void onSaveAnchor() {
        double x = parseCoord(this.editX);
        double y = parseCoord(this.editY);
        double z = parseCoord(this.editZ);
        IACP.LOGGER.info("[GrindstoneConfig] 发送锚点更新: ({}, {}, {})", x, y, z);
        ModNetworking.sendToServer(new AnchorConfigC2SPacket(subLevelUUID, x, y, z));
        this.saveAnchorBtn.setMessage(Component.translatable("screen.iac_p.grindstone_config.saved"));
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int cx = this.width / 2;

        // 顶部标题
        g.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.grindstone_config.title").getString(),
                cx, TITLE_Y, 0xFFFFFF);

        // 左列标题
        g.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.grindstone_config.orient_label").getString(),
                cx - 150 + 71, TITLE_Y + 16, 0xAAAAAA);

        // 右列标题
        int rp = cx + 20;
        g.drawString(this.font,
                Component.translatable("screen.iac_p.grindstone_config.anchor_title").getString(),
                rp, TITLE_Y + 10, 0x88CCFF);
        g.drawString(this.font,
                Component.translatable("screen.iac_p.grindstone_config.anchor_hint").getString(),
                rp, TITLE_Y + 22, 0x888888);

        // 为三个编辑框绘制标签
        int fl = rp + 12;
        g.drawString(this.font, "§7X", fl - 10, this.editX.getY() + 4, 0xCC4444);
        g.drawString(this.font, "§7Y", fl - 10, this.editY.getY() + 4, 0xCCCC44);
        g.drawString(this.font, "§7Z", fl - 10, this.editZ.getY() + 4, 0x4444CC);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // EditBox 的 mouseClicked 由 super 处理，无需手动拦截
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (key == 258) { // Tab
            if (this.editX.isFocused()) this.editY.setFocused(true);
            else if (this.editY.isFocused()) this.editZ.setFocused(true);
            else this.editX.setFocused(true);
            return true;
        }
        if (key == 257 || key == 335) { onSaveAnchor(); return true; } // Enter
        return super.keyPressed(key, sc, mod);
    }
}
