package com.hainabaichuan75.iac_p.client.screen;

import com.hainabaichuan75.iac_p.client.StructureInfoData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 结构信息界面（对着驾驶舱按 N 打开）。
 * <p>
 * 上半部分：显示 SubLevel 内的方块统计（种类、数量）。
 * 中半部分：武器系统概览（炮塔底座、连接状态）。
 * 下半部分：武器底座连接的物理结构信息。
 * <p>
 * 只读界面（仅有关闭按钮），数据由 {@link StructureInfoData#scan} 在打开前采集。
 * <p>
 * 布局：
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │         §l✦ 结构概览 ✦                   │
 * │                                          │
 * │  方块种类: 12      方块总数: 156          │
 * │  ─────────────────────────               │
 * │  minecraft:stone         45              │
 * │  create:andesite_casing  23              │
 * │  iac_p:cockpit           1               │
 * │  iac_p:machine_gun_base       2               │
 * │  ...（最多显示 12 行）                    │
 * │                                          │
 * │  ── 武器系统 ──                          │
 * │  炮塔底座 × 2                            │
 * │                                          │
 * │  ▶ 炮塔 @ (123,64,456)                  │
 * │    状态: 已装配 | 砂轮✔ 炮管✔ 车体✔     │
 * │  ▶ 炮塔 @ (127,64,460)                  │
 * │    状态: 未装配                          │
 * │                                          │
 * │  ── 连接结构 ──                          │
 * │  炮塔 @ (123,64,456) → 车体 SubLevel     │
 * │                                          │
 * │  [ 关闭 ]                                │
 * └─────────────────────────────────────────┘
 * </pre>
 */
public class StructureInfoScreen extends Screen {

    private final StructureInfoData data;

    private static final int TITLE_Y = 8;
    private static final int SECTION_SPACING = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int LEFT_MARGIN = 15;
    private static final int LABEL_X = LEFT_MARGIN;

    public StructureInfoScreen(StructureInfoData data) {
        super(Component.translatable("screen.iac_p.structure_info.title"));
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;

        // ── 关闭按钮 ──
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.iac_p.structure_info.close"),
                btn -> this.onClose()
        )
                .bounds(centerX - 50, this.height - 40, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int currentY = TITLE_Y;

        // ══════════════════════════════════════════════
        //  标题
        // ══════════════════════════════════════════════
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.iac_p.structure_info.title").getString(),
                centerX, currentY, 0xFFFFFF);
        currentY += 13;

        // ── 驾驶舱状态提示 ──
        if (!data.hasCockpit()) {
            graphics.drawCenteredString(this.font,
                    "§e" + Component.translatable("screen.iac_p.structure_info.no_cockpit").getString(),
                    centerX, currentY, 0xFFFF55);
            currentY += LINE_HEIGHT + 2;
        }

        // ══════════════════════════════════════════════
        //  方块统计区
        // ══════════════════════════════════════════════
        graphics.drawString(this.font,
                "§b§l" + Component.translatable("screen.iac_p.structure_info.block_header").getString(),
                LABEL_X, currentY, 0x55FFFF);
        currentY += LINE_HEIGHT;

        // 统计摘要行
        graphics.drawString(this.font,
                "§7" + Component.translatable(
                        "screen.iac_p.structure_info.block_summary",
                        data.distinctBlockTypes(), data.totalBlocks()).getString(),
                LABEL_X + 8, currentY, 0xAAAAAA);
        currentY += LINE_HEIGHT + 2;

        // 方块列表（最多显示 14 行，超出部分截断）
        List<StructureInfoData.BlockCountEntry> counts = data.blockCounts();
        int maxRows = Math.min(counts.size(), 14);
        for (int i = 0; i < maxRows; i++) {
            StructureInfoData.BlockCountEntry entry = counts.get(i);
            // 使用 §7 灰色显示完整注册名，§f 白色显示数量
            String line = "§7" + entry.blockName();
            graphics.drawString(this.font, line, LABEL_X + 8, currentY, 0xAAAAAA);
            graphics.drawString(this.font, "§f" + entry.count(),
                    this.width - LEFT_MARGIN - 40, currentY, 0xFFFFFF);
            currentY += LINE_HEIGHT;
        }
        if (counts.size() > 14) {
            graphics.drawString(this.font,
                    "§8..." + Component.translatable(
                            "screen.iac_p.structure_info.more", counts.size() - 14).getString(),
                    LABEL_X + 8, currentY, 0x555555);
            currentY += LINE_HEIGHT;
        }

        currentY += SECTION_SPACING;

        // ══════════════════════════════════════════════
        //  武器系统区
        // ══════════════════════════════════════════════
        graphics.drawString(this.font,
                "§c§l" + Component.translatable("screen.iac_p.structure_info.weapon_header").getString(),
                LABEL_X, currentY, 0xFF5555);
        currentY += LINE_HEIGHT;

        List<StructureInfoData.MachineGunInfo> machineGuns = data.machineGuns();
        if (machineGuns.isEmpty()) {
            graphics.drawString(this.font,
                    "§7" + Component.translatable("screen.iac_p.structure_info.no_weapons").getString(),
                    LABEL_X + 8, currentY, 0xAAAAAA);
            currentY += LINE_HEIGHT;
        } else {
            // 总量统计
            int assembledCount = (int) machineGuns.stream().filter(StructureInfoData.MachineGunInfo::isAssembled).count();
            graphics.drawString(this.font,
                    "§7" + Component.translatable(
                            "screen.iac_p.structure_info.machine_gun_count",
                            machineGuns.size(), assembledCount).getString(),
                    LABEL_X + 8, currentY, 0xAAAAAA);
            currentY += LINE_HEIGHT + 2;

            // 每个炮塔明细
            int machineGunIndex = 1;
            for (StructureInfoData.MachineGunInfo t : machineGuns) {
                // 标题行
                graphics.drawString(this.font,
                        "§e► " + Component.translatable(
                                "screen.iac_p.structure_info.machine_gun_entry",
                                machineGunIndex, t.position().toShortString()).getString(),
                        LABEL_X + 8, currentY, 0xFFFF55);
                currentY += LINE_HEIGHT;

                // 状态行
                graphics.drawString(this.font,
                        "  " + Component.translatable("screen.iac_p.structure_info.status_label").getString()
                                + " " + t.statusSummary(),
                        LABEL_X + 8, currentY, 0xAAAAAA);
                currentY += LINE_HEIGHT;

                machineGunIndex++;
            }
        }

        currentY += SECTION_SPACING;

        // ══════════════════════════════════════════════
        //  连接结构区
        // ══════════════════════════════════════════════
        graphics.drawString(this.font,
                "§d§l" + Component.translatable("screen.iac_p.structure_info.connection_header").getString(),
                LABEL_X, currentY, 0xFF55FF);
        currentY += LINE_HEIGHT;

        boolean hasConnections = false;
        for (var machineGun : data.machineGuns()) {
            if (machineGun.isAssembled()) {
                hasConnections = true;

                // 机炮底座位置信息
                graphics.drawString(this.font,
                        "§7" + Component.translatable(
                                "screen.iac_p.structure_info.connection_machine_gun",
                                machineGun.position().toShortString()).getString(),
                        LABEL_X + 8, currentY, 0xAAAAAA);
                currentY += LINE_HEIGHT;

                // 连接的物理结构
                if (machineGun.vehicleSubLevelId() != null) {
                    graphics.drawString(this.font,
                            "  §a├ " + Component.translatable(
                                    "screen.iac_p.structure_info.conn_vehicle",
                                    machineGun.vehicleSubLevelId().toString().substring(0, 8) + "…")
                                    .getString(),
                            LABEL_X + 8, currentY, 0x55FF55);
                    currentY += LINE_HEIGHT;
                } else {
                    graphics.drawString(this.font,
                            "  §e├ " + Component.translatable(
                                    "screen.iac_p.structure_info.conn_mainworld").getString(),
                            LABEL_X + 8, currentY, 0xFFFF55);
                    currentY += LINE_HEIGHT;
                }

                // 砂轮 SubLevel
                if (machineGun.grindstoneSubLevelId() != null) {
                    graphics.drawString(this.font,
                            "  §a├ " + Component.translatable(
                                    "screen.iac_p.structure_info.conn_grindstone",
                                    machineGun.grindstoneSubLevelId().toString().substring(0, 8) + "…")
                                    .getString(),
                            LABEL_X + 8, currentY, 0x55FF55);
                    currentY += LINE_HEIGHT;
                }

                // 炮管 SubLevel
                if (machineGun.lightningRodSubLevelId() != null) {
                    graphics.drawString(this.font,
                            "  §a└ " + Component.translatable(
                                    "screen.iac_p.structure_info.conn_rod",
                                    machineGun.lightningRodSubLevelId().toString().substring(0, 8) + "…")
                                    .getString(),
                            LABEL_X + 8, currentY, 0x55FF55);
                    currentY += LINE_HEIGHT;
                }
            }
        }

        if (!hasConnections) {
            graphics.drawString(this.font,
                    "§7" + Component.translatable("screen.iac_p.structure_info.no_connections").getString(),
                    LABEL_X + 8, currentY, 0xAAAAAA);
            currentY += LINE_HEIGHT;
        }

        // ── 底部操作提示 ──
        currentY += SECTION_SPACING + 2;
        graphics.drawCenteredString(this.font,
                "§7" + Component.translatable("screen.iac_p.structure_info.hint").getString(),
                centerX, this.height - 55, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
