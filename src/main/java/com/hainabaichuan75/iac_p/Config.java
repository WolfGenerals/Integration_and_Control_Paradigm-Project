package com.hainabaichuan75.iac_p;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ====== 客户端摄像机配置 ======
    public static final ModConfigSpec.DoubleValue CAMERA_HEIGHT_OFFSET;
    public static final ModConfigSpec.DoubleValue CAMERA_DISTANCE;
    public static final ModConfigSpec.BooleanValue CAMERA_INVERT_Y;
    public static final ModConfigSpec.BooleanValue CAMERA_ADAPTIVE_HEIGHT;
    public static final ModConfigSpec.BooleanValue CAMERA_ADAPTIVE_DISTANCE;

    static {
        BUILDER.push("client").push("camera");
        CAMERA_ADAPTIVE_HEIGHT = BUILDER
                .comment("Adaptive camera height / 启用自适应摄像机高度",
                        "Camera height = offset + vehicle bounding box height + 1",
                        "Even with offset=0, camera stays 1 block above the vehicle")
                .define("cameraAdaptiveHeight", true);
        CAMERA_ADAPTIVE_DISTANCE = BUILDER
                .comment("Adaptive camera distance / 启用自适应摄像机距离",
                        "Camera distance = manual distance + longest side of vehicle / 2")
                .define("cameraAdaptiveDistance", true);
        CAMERA_HEIGHT_OFFSET = BUILDER
                .comment("Camera height offset (blocks) / 摄像机高度偏移",
                        "Positive = up, negative = down. Client only.",
                        "When adaptive is enabled, this adds on top of the adaptive offset")
                .defineInRange("cameraHeightOffset", 0.0, -5.0, 5.0);
        CAMERA_DISTANCE = BUILDER
                .comment("Camera distance (blocks) / 摄像机距离",
                        "Larger = farther (3rd person back), smaller = closer.",
                        "When adaptive is enabled, this adds on top of the adaptive distance")
                .defineInRange("cameraDistance", 4.0, 0.0, 60.0);
        CAMERA_INVERT_Y = BUILDER
                .comment("Invert mouse Y axis / 反转鼠标 Y 轴",
                        "Off: mouse up → camera up, mouse down → camera down.",
                        "On: mouse up → camera down, mouse down → camera up.")
                .define("cameraInvertY", false);
        BUILDER.pop().pop();

        // ====== Turret constraint anchor offsets ======
        // Four groups: (block center getCenterBlock()+0.5) + offset
        // Each group has three doubles: offsetX, offsetY, offsetZ (blocks)
        BUILDER.push("turret").push("constraint_anchors");

        // ── Rod (barrel) anchor offset ──
        // Used as pos1 for rod↔grindstone GenericConstraint
        BUILDER.push("rod_anchor");
        ROD_ANCHOR_OFFSET_X = BUILDER
                .comment("Rod (barrel) anchor X offset / 避雷针锚点 X 偏移")
                .defineInRange("offsetX", 0.0, -10.0, 10.0);
        ROD_ANCHOR_OFFSET_Y = BUILDER
                .comment("Rod (barrel) anchor Y offset / 避雷针锚点 Y 偏移")
                .defineInRange("offsetY", 0.0, -10.0, 10.0);
        ROD_ANCHOR_OFFSET_Z = BUILDER
                .comment("Rod (barrel) anchor Z offset / 避雷针锚点 Z 偏移")
                .defineInRange("offsetZ", 0.0, -10.0, 10.0);
        BUILDER.pop();

        // ── Grindstone (rod side) anchor offset ──
        // Used as pos2 for rod↔grindstone GenericConstraint
        BUILDER.push("grindstone_rod_anchor");
        GRINDSTONE_ROD_OFFSET_X = BUILDER
                .comment("Grindstone (rod side) X offset / 砂轮端（避雷针侧）X 偏移")
                .defineInRange("offsetX", 0.0, -10.0, 10.0);
        GRINDSTONE_ROD_OFFSET_Y = BUILDER
                .comment("Grindstone (rod side) Y offset / 砂轮端（避雷针侧）Y 偏移")
                .defineInRange("offsetY", 0.0, -10.0, 10.0);
        GRINDSTONE_ROD_OFFSET_Z = BUILDER
                .comment("Grindstone (rod side) Z offset / 砂轮端（避雷针侧）Z 偏移")
                .defineInRange("offsetZ", 0.0, -10.0, 10.0);
        BUILDER.pop();

        // ── Grindstone (swivel / yaw) anchor offset ──
        // Used as pos1 for grindstone↔vehicle RotaryConstraint
        BUILDER.push("grindstone_swivel");
        GRINDSTONE_SWIVEL_OFFSET_X = BUILDER
                .comment("Grindstone swivel X offset / 砂轮（方向机）X 偏移")
                .defineInRange("offsetX", 0.0, -10.0, 10.0);
        GRINDSTONE_SWIVEL_OFFSET_Y = BUILDER
                .comment("Grindstone swivel Y offset / 砂轮（方向机）Y 偏移")
                .defineInRange("offsetY", 0.0, -10.0, 10.0);
        GRINDSTONE_SWIVEL_OFFSET_Z = BUILDER
                .comment("Grindstone swivel Z offset / 砂轮（方向机）Z 偏移")
                .defineInRange("offsetZ", 0.0, -10.0, 10.0);
        BUILDER.pop();

        // ── Vehicle (carpet) anchor offset ──
        // Used as pos2 for grindstone↔vehicle RotaryConstraint
        BUILDER.push("vehicle_carpet_anchor");
        VEHICLE_CARPET_OFFSET_X = BUILDER
                .comment("Vehicle (carpet) X offset / 载具（地毯端）X 偏移")
                .defineInRange("offsetX", 0.0, -10.0, 10.0);
        VEHICLE_CARPET_OFFSET_Y = BUILDER
                .comment("Vehicle (carpet) Y offset / 载具（地毯端）Y 偏移")
                .defineInRange("offsetY", 0.0, -10.0, 10.0);
        VEHICLE_CARPET_OFFSET_Z = BUILDER
                .comment("Vehicle (carpet) Z offset / 载具（地毯端）Z 偏移")
                .defineInRange("offsetZ", 0.0, -10.0, 10.0);
        BUILDER.pop();

        BUILDER.pop().pop();
    }

    // ====== 炮塔约束锚点偏移配置项 ======

    // 避雷针(炮管)端约束锚点偏移
    public static final ModConfigSpec.DoubleValue ROD_ANCHOR_OFFSET_X;
    public static final ModConfigSpec.DoubleValue ROD_ANCHOR_OFFSET_Y;
    public static final ModConfigSpec.DoubleValue ROD_ANCHOR_OFFSET_Z;

    // 砂轮端(避雷针侧)约束锚点偏移
    public static final ModConfigSpec.DoubleValue GRINDSTONE_ROD_OFFSET_X;
    public static final ModConfigSpec.DoubleValue GRINDSTONE_ROD_OFFSET_Y;
    public static final ModConfigSpec.DoubleValue GRINDSTONE_ROD_OFFSET_Z;

    // 砂轮端(方向机)约束锚点偏移
    public static final ModConfigSpec.DoubleValue GRINDSTONE_SWIVEL_OFFSET_X;
    public static final ModConfigSpec.DoubleValue GRINDSTONE_SWIVEL_OFFSET_Y;
    public static final ModConfigSpec.DoubleValue GRINDSTONE_SWIVEL_OFFSET_Z;

    // 载具(地毯)端约束锚点偏移
    public static final ModConfigSpec.DoubleValue VEHICLE_CARPET_OFFSET_X;
    public static final ModConfigSpec.DoubleValue VEHICLE_CARPET_OFFSET_Y;
    public static final ModConfigSpec.DoubleValue VEHICLE_CARPET_OFFSET_Z;

    // ====== 炮塔瞄准校准 ======
    public static final ModConfigSpec.DoubleValue TURRET_YAW_OFFSET;

    static {
        BUILDER.push("turret").push("aim");
        TURRET_YAW_OFFSET = BUILDER
                .comment("炮塔方向机偏航角度校准偏移（单位：度）。",
                        "如果炮塔瞄准位置与实际位置有固定角度偏差，",
                        "通过此值校准。正值使炮塔顺时针偏转，负值逆时针。",
                        "建议在游戏中微调：先瞄准一个目标，测量偏差角度，填入此值。")
                .defineInRange("yawOffset", 0.0, -180.0, 180.0);
        BUILDER.pop().pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
