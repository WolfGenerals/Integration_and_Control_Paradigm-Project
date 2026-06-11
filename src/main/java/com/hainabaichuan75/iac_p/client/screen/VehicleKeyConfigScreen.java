package com.hainabaichuan75.iac_p.client.screen;

import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.TireConfigC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleKeyConfigC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 载具综合配置界面（C 键打开）。
 * <p>
 * 上部分：5 个按键绑定（前进/后退/左转/右转/刹车）
 * 下部分：胎压调节（唯一可调的运行时轮胎参数，其余由轮胎款式决定）
 * 所有参数持久化到悬挂测试方块的 NBT 中。
 */
public class VehicleKeyConfigScreen extends Screen {

    private static final int TITLE_Y = 12;
    private static final int ROW_START_Y = 36;
    private static final int ROW_HEIGHT = 22;
    private static final int LABEL_WIDTH = 80;
    private static final int FIELD_WIDTH = 100;
    private static final int FIELD_HEIGHT = 16;

    private final BlockPos targetPos;

    // ---- 按键绑定 ----
    private static final String[] KEY_LABEL_KEYS = {
            "screen.iac_p.vehicle_config.forward",
            "screen.iac_p.vehicle_config.backward",
            "screen.iac_p.vehicle_config.left",
            "screen.iac_p.vehicle_config.right",
            "screen.iac_p.vehicle_config.brake"
    };
    private final String[] keys = new String[5];
    private int listeningIndex = -1;

    // ---- 智能映射按键展示（只读） ----
    private final String[] smartKeys = new String[5];
    private boolean hasSmartKeys = false;

    // ---- 胎压调节 ----
    private String pressureValue = "220000";
    private EditBox pressureField;

    public VehicleKeyConfigScreen(BlockPos targetPos) {
        super(Component.translatable("screen.iac_p.vehicle_config.title"));
        this.targetPos = targetPos;
        loadFromBlockEntity();
    }

    /** 从客户端方块实体读取当前配置和智能映射按键 */
    private void loadFromBlockEntity() {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(targetPos) instanceof SuspensionTestBlockEntity be) {
            keys[0] = be.getKeyForward();
            keys[1] = be.getKeyBackward();
            keys[2] = be.getKeyLeft();
            keys[3] = be.getKeyRight();
            keys[4] = be.getKeyBrake();
            pressureValue = String.format("%.0f", be.getNominalPressure());

            // 读取智能映射按键（只读展示）
            smartKeys[0] = be.getSmartKeyForward();
            smartKeys[1] = be.getSmartKeyBackward();
            smartKeys[2] = be.getSmartKeyLeft();
            smartKeys[3] = be.getSmartKeyRight();
            smartKeys[4] = be.getSmartKeyBrake();
            hasSmartKeys = smartKeys[0] != null && !smartKeys[0].isEmpty();
        } else {
            keys[0] = "key.keyboard.w";
            keys[1] = "key.keyboard.s";
            keys[2] = "key.keyboard.a";
            keys[3] = "key.keyboard.d";
            keys[4] = "key.keyboard.space";
            pressureValue = "220000";
            hasSmartKeys = false;
        }
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int leftCol = centerX - LABEL_WIDTH - FIELD_WIDTH / 2;

        // ===== 上部分：按键绑定 =====
        for (int i = 0; i < 5; i++) {
            final int index = i;
            int y = ROW_START_Y + i * ROW_HEIGHT;
            this.addRenderableWidget(Button.builder(
                            Component.literal(formatKeyName(keys[index])),
                            btn -> onKeyButtonClick(index)
                    )
                    .bounds(leftCol + LABEL_WIDTH, y, FIELD_WIDTH, FIELD_HEIGHT)
                    .build());
        }

        int keyBtnY = ROW_START_Y + 5 * ROW_HEIGHT + 4;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.iac_p.vehicle_config.apply_keys"),
                        btn -> applyKeys()
                )
                .bounds(centerX - 110, keyBtnY, 90, 18)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.iac_p.vehicle_config.clear_keys"),
                        btn -> clearKeys()
                )
                .bounds(centerX - 15, keyBtnY, 80, 18)
                .build());

        // ===== 下部分：胎压调节 =====
        int tireSectionY = keyBtnY + 26;
        int pressureY = tireSectionY + 14;

        this.pressureField = new EditBox(this.font, leftCol + LABEL_WIDTH, pressureY,
                FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("screen.iac_p.vehicle_config.tire_pressure"));
        this.pressureField.setValue(pressureValue);
        this.pressureField.setMaxLength(12);
        this.pressureField.setFilter(s -> s.matches("[\\d.]*"));
        this.pressureField.setResponder(s -> pressureValue = s);
        this.addRenderableWidget(this.pressureField);

        int tireBtnY = pressureY + ROW_HEIGHT + 4;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.iac_p.vehicle_config.apply_pressure"),
                        btn -> applyPressure()
                )
                .bounds(centerX - 110, tireBtnY, 90, 18)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.iac_p.vehicle_config.reset_pressure"),
                        btn -> resetPressure()
                )
                .bounds(centerX - 15, tireBtnY, 80, 18)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 基类先绘制：背景模糊层 + 所有已注册控件（Button/EditBox）
        super.render(graphics, mouseX, mouseY, partialTick);

        // 自定义文字在 super.render() 之后绘制，确保在所有控件之上，
        // 不会被 EditBox 的背景框或模糊层遮挡。
        int centerX = this.width / 2;
        int leftCol = centerX - LABEL_WIDTH - FIELD_WIDTH / 2;

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.title").getString(),
                centerX, TITLE_Y, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.hint").getString(),
                centerX, TITLE_Y + 11, 0xAAAAAA);

        for (int i = 0; i < 5; i++) {
            int y = ROW_START_Y + i * ROW_HEIGHT;
            String label = Component.translatable(KEY_LABEL_KEYS[i]).getString();
            graphics.drawString(this.font, "§e" + label + ":", leftCol, y + 4, 0xFFFFFF);
            if (i == listeningIndex) {
                graphics.fill(leftCol + LABEL_WIDTH - 2, y - 2,
                        leftCol + LABEL_WIDTH + FIELD_WIDTH + 2, y + FIELD_HEIGHT + 2, 0x55FFFF00);
            }
        }

        if (listeningIndex >= 0) {
            String listeningLabel = Component.translatable(KEY_LABEL_KEYS[listeningIndex]).getString();
            String msg = Component.translatable("screen.iac_p.vehicle_config.listening", listeningLabel).getString();
            graphics.drawCenteredString(this.font, msg,
                    centerX, ROW_START_Y + 5 * ROW_HEIGHT + 24, 0x00FF00);
        }

        // ── 智能映射按键展示（右侧列） ──
        if (hasSmartKeys) {
            int smartColX = leftCol + LABEL_WIDTH + FIELD_WIDTH + 28;
            graphics.drawString(this.font,
                    "§8" + Component.translatable("screen.iac_p.vehicle_config.smart_header").getString(),
                    smartColX, ROW_START_Y - 2, 0x888888);
            for (int i = 0; i < 5; i++) {
                int y = ROW_START_Y + i * ROW_HEIGHT;
                String displayName = formatKeyName(smartKeys[i]);
                graphics.drawString(this.font,
                        "§a" + displayName,
                        smartColX, y + 4, 0x55FF55);
            }
        }

        int tireSectionY = ROW_START_Y + 6 * ROW_HEIGHT + 8;
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.tire_pressure_section").getString(),
                centerX, tireSectionY, 0x88CCFF);

        int pressureY = tireSectionY + 14;
        String pLabel = Component.translatable("screen.iac_p.vehicle_config.tire_pressure").getString();
        graphics.drawString(this.font, "§b" + pLabel + ":", leftCol, pressureY + 3, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.tire_pressure_unit").getString(),
                leftCol + LABEL_WIDTH + FIELD_WIDTH + 4, pressureY + 3, 0xAAAAAA);

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.tire_hint_1").getString(),
                centerX, pressureY + ROW_HEIGHT + 24, 0x888888);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.vehicle_config.tire_hint_2").getString(),
                centerX, pressureY + ROW_HEIGHT + 36, 0x888888);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listeningIndex >= 0) {
            String keyName = InputConstants.getKey(keyCode, scanCode).getName();
            if (keyName != null && !keyName.isEmpty()) {
                keys[listeningIndex] = keyName;
                listeningIndex = -1;
                rebuildWidgets();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listeningIndex >= 0) {
            listeningIndex = -1;
            rebuildWidgets();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ====================================================================
    //  按键绑定操作
    // ====================================================================

    private void onKeyButtonClick(int index) {
        listeningIndex = (listeningIndex == index) ? -1 : index;
        rebuildWidgets();
    }

    private void applyKeys() {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(targetPos) instanceof SuspensionTestBlockEntity be) {
            be.setKeyBindings(keys[0], keys[1], keys[2], keys[3], keys[4]);
        }
        ModNetworking.sendToServer(new VehicleKeyConfigC2SPacket(
                targetPos, keys[0], keys[1], keys[2], keys[3], keys[4]
        ));
        Minecraft.getInstance().player.displayClientMessage(
                Component.translatable("message.iac_p.keys_saved"), true);
    }

    private void clearKeys() {
        keys[0] = "key.keyboard.w";
        keys[1] = "key.keyboard.s";
        keys[2] = "key.keyboard.a";
        keys[3] = "key.keyboard.d";
        keys[4] = "key.keyboard.space";
        listeningIndex = -1;
        rebuildWidgets();
    }

    // ====================================================================
    //  轮胎参数操作
    // ====================================================================

    private double parseDouble(String s, double def, String name) {
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("message.iac_p.pressure_format_error", name), true);
            return def;
        }
    }

    private void applyPressure() {
        // 按键
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(targetPos) instanceof SuspensionTestBlockEntity be) {
            be.setKeyBindings(keys[0], keys[1], keys[2], keys[3], keys[4]);
        }
        ModNetworking.sendToServer(new VehicleKeyConfigC2SPacket(
                targetPos, keys[0], keys[1], keys[2], keys[3], keys[4]
        ));

        // 胎压
        double np = clamp(parseDouble(pressureValue, 220000, "胎压"), 50000, 600000);

        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(targetPos) instanceof SuspensionTestBlockEntity be) {
            be.setNominalPressure(np);
        }
        ModNetworking.sendToServer(new TireConfigC2SPacket(targetPos, np));

        Minecraft.getInstance().player.displayClientMessage(
                Component.translatable("message.iac_p.pressure_saved"), true);
        this.onClose();
    }

    private void resetPressure() {
        pressureValue = "220000";
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(targetPos) instanceof SuspensionTestBlockEntity be) {
            be.resetPressureToDefault();
        }
        ModNetworking.sendToServer(new TireConfigC2SPacket(targetPos, 220000.0));
        rebuildWidgets();
    }

    // ====================================================================
    //  工具
    // ====================================================================

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String formatKeyName(String key) {
        if (key == null) return "?";
        String name = key.replace("key.keyboard.", "");
        return switch (name) {
            case "space" -> "空格";
            case "left.shift" -> "左Shift";
            case "left.control" -> "左Ctrl";
            case "left.alt" -> "左Alt";
            case "right.shift" -> "右Shift";
            case "right.control" -> "右Ctrl";
            case "right.alt" -> "右Alt";
            case "tab" -> "Tab";
            case "escape" -> "ESC";
            case "enter" -> "Enter";
            case "up" -> "↑";
            case "down" -> "↓";
            case "left" -> "←";
            case "right" -> "→";
            default -> name.length() == 1 ? name.toUpperCase() : name;
        };
    }
}
