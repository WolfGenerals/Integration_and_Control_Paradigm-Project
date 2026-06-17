package com.hainabaichuan75.iac_p.client.screen;

import com.hainabaichuan75.iac_p.client.ClientMountHandler;
import com.hainabaichuan75.iac_p.client.VehicleOrientationData;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.SmartMapC2SPacket;
import com.hainabaichuan75.iac_p.skill.DrivingSkill;
import com.hainabaichuan75.iac_p.skill.SkillRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 载具朝向与技能/智能映射界面（对着驾驶舱按 C 打开）。
 * <p>
 * 上半部分：显示悬挂 FACING 统计和朝向推断结果。
 * 下半部分：交互按钮——技能选择、反转方向、智能映射开关。
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
 * │  ┌──────┐ ┌──────┐ ┌──────┐            │
 * │  │技能A │ │技能B │ │技能C │  ← 技能选择 │
 * │  └──────┘ └──────┘ └──────┘            │
 * │                                         │
 * │  [ 反转方向 ]  [ 关闭智能映射 ]          │
 * │  [ 关闭 ]                                │
 * └─────────────────────────────────────────┘
 */
public class VehicleOrientationScreen extends Screen {

    private final VehicleOrientationData data;
    private final UUID subLevelUUID;
    private final int totalSuspensions;

    // 按钮引用
    private Button toggleBtn;
    private boolean smartMappingActive;
    private String activeSkillId;

    // 可用技能列表
    private final List<DrivingSkill> availableSkills;
    private final List<Button> skillButtons = new java.util.ArrayList<>();

    private static final int TITLE_Y = 12;
    private static final int STAT_START_Y = 42;
    private static final int LINE_HEIGHT = 13;
    private static final int BTN_W = 120;
    private static final int BTN_H = 20;
    private static final int SKILL_BTN_W = 80;
    private static final int SKILL_BTN_H = 18;

    public VehicleOrientationScreen(VehicleOrientationData data, UUID subLevelUUID, boolean smartMappingActive) {
        super(Component.translatable("screen.iac_p.vehicle_orientation.title"));
        this.data = data;
        this.subLevelUUID = subLevelUUID;
        this.totalSuspensions = data.total();
        this.smartMappingActive = smartMappingActive;
        this.activeSkillId = ClientMountHandler.getActiveSkillId();
        this.availableSkills = SkillRegistry.getInstance().getAll();
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;

        // ── 统计信息所需动态行数 ──
        boolean hasStrafe = data.northSouthTotal() > 0;
        int infoLines = 2; // 总行数从 summaryY 开始算
        int statusY = TITLE_Y + 180; // 按钮区域起始 Y

        // ── 技能选择行 ──
        if (!availableSkills.isEmpty()) {
            int skillY = statusY;
            int totalWidth = availableSkills.size() * SKILL_BTN_W + (availableSkills.size() - 1) * 4;
            int startX = centerX - totalWidth / 2;

            for (int i = 0; i < availableSkills.size(); i++) {
                DrivingSkill skill = availableSkills.get(i);
                boolean isActive = skill.id().equals(activeSkillId);
                int bx = startX + i * (SKILL_BTN_W + 4);

                Button skillBtn = this.addRenderableWidget(Button.builder(
                        Component.literal((isActive ? "§l§a▶ " : "§7 ") + skill.name()),
                        btn -> {
                            // 发送技能选择
                            selectSkill(skill.id());
                            // 更新 UI
                            activeSkillId = skill.id();
                            smartMappingActive = true;
                            updateSkillButtons();
                            if (toggleBtn != null) toggleBtn.setMessage(buildToggleLabel());
                        }
                )
                        .bounds(bx, skillY, SKILL_BTN_W, SKILL_BTN_H)
                        .build());
                skillButtons.add(skillBtn);
            }
            statusY += SKILL_BTN_H + 8;
        }

        // ── 第2行：反转方向 + 智能映射开关 ──
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.vehicle_orientation.reverse"),
                btn -> {
                    sendAction(SmartMapC2SPacket.Action.REVERSE);
                    ClientMountHandler.localSwapSmartKeys();
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("message.iac_p.smart_map_reversed"), true);
                    }
                }
        )
                .bounds(centerX - BTN_W - 8, statusY, BTN_W, BTN_H)
                .build());

        this.toggleBtn = this.addRenderableWidget(Button.builder(
                buildToggleLabel(),
                btn -> {
                    sendAction(SmartMapC2SPacket.Action.TOGGLE_SMART);
                    smartMappingActive = !smartMappingActive;
                    toggleBtn.setMessage(buildToggleLabel());
                    // 开关改变时刷新技能按钮高亮
                    updateSkillButtons();
                }
        )
                .bounds(centerX + 8, statusY, BTN_W, BTN_H)
                .build());

        // ── 第3行：关闭按钮 ──
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.vehicle_orientation.close"),
                btn -> this.onClose()
        )
                .bounds(centerX - 50, statusY + BTN_H + 6, 100, BTN_H)
                .build());
    }

    /** 更新所有技能按钮的文本 */
    private void updateSkillButtons() {
        for (int i = 0; i < skillButtons.size() && i < availableSkills.size(); i++) {
            DrivingSkill skill = availableSkills.get(i);
            boolean isActive = smartMappingActive && skill.id().equals(activeSkillId);
            skillButtons.get(i).setMessage(Component.literal(
                    (isActive ? "§l§a▶ " : "§7 ") + skill.name()));
        }
    }

    /** 选择技能 */
    private void selectSkill(String skillId) {
        ModNetworking.sendToServer(new SmartMapC2SPacket(
                SmartMapC2SPacket.Action.SELECT_SKILL, subLevelUUID, skillId));
        ClientMountHandler.setActiveSkillId(skillId);
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

        // ── 横移信息（仅当有 NS 朝向悬挂时） ──
        int strafeY = inferenceY + LINE_HEIGHT * 2;
        if (data.northSouthTotal() > 0) {
            graphics.drawCenteredString(this.font,
                    "§6" + Component.translatable("screen.iac_p.vehicle_orientation.strafe").getString()
                            + ": §f" + data.northSouthTotal()
                            + "  (§eQ§7↔§eE§7)",
                    centerX, strafeY, 0xFFFFFF);
        }

        // ── 当前智能映射状态条 ──
        int statusY = (data.northSouthTotal() > 0 ? strafeY : inferenceY + LINE_HEIGHT * 2) + 6;
        String statusColor = smartMappingActive ? "§a" : "§7";
        int statusLineY = statusY + 2;
        graphics.drawCenteredString(this.font,
                statusColor + Component.translatable(
                        smartMappingActive
                                ? "screen.iac_p.vehicle_orientation.status_active"
                                : "screen.iac_p.vehicle_orientation.status_inactive"
                ).getString(),
                centerX, statusLineY, smartMappingActive ? 0x55FF55 : 0xAAAAAA);

        // ── 当前技能显示 ──
        if (smartMappingActive && activeSkillId != null) {
            DrivingSkill currentSkill = SkillRegistry.getInstance().get(activeSkillId);
            if (currentSkill != null) {
                graphics.drawCenteredString(this.font,
                        "§7技能: §f" + currentSkill.name() + " §8(" + currentSkill.id() + ")",
                        centerX, statusLineY + 11, 0x888888);
                if (currentSkill.description() != null && !currentSkill.description().isEmpty()) {
                    graphics.drawCenteredString(this.font,
                            "§7" + currentSkill.description().replace("\\n", " §8| §7"),
                            centerX, statusLineY + 22, 0x666666);
                }
            }
        }

        // ── 提示：技能按钮说明 ──
        if (!availableSkills.isEmpty()) {
            int skillHintY = TITLE_Y + 180 - 10;
            graphics.drawCenteredString(this.font,
                    "§8[ 选择驾驶技能 ]",
                    centerX, skillHintY, 0x888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
