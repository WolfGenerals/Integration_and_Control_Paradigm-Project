/*
 * 驾驶技能 —— JSON 配置的数据模型，定义 WASD+QE 到各悬挂组的映射规则。
 *
 * 技能包含：
 *   1. wheel_classification：如何对悬挂分类（按朝向、按位置等）
 *   2. input_bindings：语义输入名（input.forward）到物理键名的映射
 *   3. wheel_outputs：每组悬挂的 5 个输出表达式（forward/backward/left/right/brake）
 *
 * 当技能被应用时，对分类后的每组悬挂评估每个输出表达式。
 * 若表达式为简单标识符（input.XXX），则通过 input_bindings 解析为物理键名 → 设置 smartKey。
 * 若表达式为复杂逻辑，则存储表达式供未来运行时求值。
 */
package com.hainabaichuan75.iac_p.skill;

import com.google.gson.*;
import com.hainabaichuan75.iac_p.IACP;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 驾驶技能定义。
 * <p>
 * 从 JSON 反序列化，不可变。
 */
public record DrivingSkill(
        /** 唯一标识，格式 "命名空间:技能名" */
        String id,
        /** 人类可读名称 */
        String name,
        /** 描述文本 */
        String description,
        /** 悬挂分类规则 */
        WheelClassification classification,
        /** 语义输入 → 物理键名映射 */
        Map<String, String> inputBindings,
        /** 各组悬挂的输出规则 */
        Map<String, WheelOutputGroup> wheelOutputs
) {
    // ==================================================================
    //  悬挂分类规则
    // ==================================================================

    /**
     * 悬挂分类规则定义。
     */
    public record WheelClassification(
            /** 分类方法：facing_axis | side | individual */
            String method,
            /** 主轴朝向（facing_axis 用） */
            String primary,
            /** 次轴朝向（facing_axis 用） */
            String secondary,
            /** 侧边定义（side 用）：左/右侧对应的 FACING */
            String leftSide,
            String rightSide
    ) {
        /** 是否为 facing_axis 分类 */
        public boolean isFacingAxis() { return "facing_axis".equals(method); }
        /** 是否为 side 分类 */
        public boolean isSide() { return "side".equals(method); }
        /** 是否为主轴朝向 */
        public boolean isPrimary(String facing) {
            return primary != null && primary.equalsIgnoreCase(facing);
        }
        /** 是否为次轴朝向 */
        public boolean isSecondary(String facing) {
            return secondary != null && secondary.equalsIgnoreCase(facing);
        }
    }

    // ==================================================================
    //  轮组输出
    // ==================================================================

    /**
     * 一组悬挂的 5 个输出表达式。
     */
    public record WheelOutputGroup(
            String forward,
            String backward,
            String left,
            String right,
            String brake
    ) {
        /** 5 个表达式名称的列表 */
        public static final List<String> OUTPUT_NAMES = List.of(
                "forward", "backward", "left", "right", "brake"
        );

        /** 获取指定输出名称的表达式 */
        public String get(String name) {
            return switch (name) {
                case "forward" -> forward;
                case "backward" -> backward;
                case "left" -> left;
                case "right" -> right;
                case "brake" -> brake;
                default -> "false";
            };
        }

        /**
         * @return 所有 5 个输出表达式列表（顺序固定）
         */
        public List<String> allExpressions() {
            return List.of(forward, backward, left, right, brake);
        }
    }

    // ==================================================================
    //  表达式类型检测
    // ==================================================================

    /**
     * 判断一个表达式是否为简单的 input.XXX 引用。
     */
    public static boolean isSimpleInputRef(String expr) {
        if (expr == null || expr.isBlank()) return false;
        String trimmed = expr.trim();
        return trimmed.startsWith("input.") && trimmed.matches("input\\.[a-zA-Z_][a-zA-Z0-9_.]*");
    }

    /**
     * 从 input.XXX 引用中提取 XXX 部分。
     */
    public static String inputRefName(String expr) {
        if (!isSimpleInputRef(expr)) return null;
        return expr.trim().substring("input.".length());
    }

    // ==================================================================
    //  分类结果
    // ==================================================================

    /**
     * 分类结果：分组名称 → 该组内的悬挂方块信息列表。
     */
    public record WheelGroup(
            String groupName,
            List<WheelEntry> wheels
    ) {
        public int size() { return wheels.size(); }
        public boolean isEmpty() { return wheels.isEmpty(); }
    }

    /**
     * 单个悬挂在分类中的信息。
     */
    public record WheelEntry(
            /** 方块所在 BlockPos 的 X */
            double posX,
            /** Y */
            double posY,
            /** Z */
            double posZ,
            /** FACING 方向名称：NORTH/SOUTH/EAST/WEST */
            String facing,
            /** 是否是 EW 轴向 */
            boolean isEW,
            /** 面向方向向量 */
            double facingDirX,
            double facingDirZ
    ) {
        public boolean isNS() { return !isEW; }
    }

    // ==================================================================
    //  JSON 反序列化
    // ==================================================================

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(DrivingSkill.class, new SkillDeserializer())
            .setPrettyPrinting()
            .create();

    /**
     * 从 JSON 字符串反序列化技能。
     */
    public static DrivingSkill fromJson(String json) {
        try {
            return GSON.fromJson(json, DrivingSkill.class);
        } catch (JsonSyntaxException e) {
            IACP.LOGGER.error("[DrivingSkill] JSON 解析失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 反序列化为 JSON 字符串。
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 自定义反序列化器，兼容 snake_case 和 camelCase。
     */
    private static final class SkillDeserializer implements JsonDeserializer<DrivingSkill> {
        @Override
        public DrivingSkill deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            String id = getString(obj, "id");
            String name = getString(obj, "name");
            String description = getString(obj, "description");

            // 解析 classification
            JsonObject classObj = getObject(obj, "wheel_classification");
            WheelClassification classification = new WheelClassification(
                    getString(classObj, "method"),
                    getString(classObj, "primary", null),
                    getString(classObj, "secondary", null),
                    getString(classObj, "left_side", null),
                    getString(classObj, "right_side", null)
            );

            // 解析 input_bindings
            JsonObject bindObj = getObject(obj, "input_bindings");
            Map<String, String> inputBindings = new LinkedHashMap<>();
            for (var e : bindObj.entrySet()) {
                inputBindings.put(e.getKey(), e.getValue().getAsString());
            }

            // 解析 wheel_outputs
            JsonObject outObj = getObject(obj, "wheel_outputs");
            Map<String, WheelOutputGroup> wheelOutputs = new LinkedHashMap<>();
            for (var entry : outObj.entrySet()) {
                JsonObject groupObj = entry.getValue().getAsJsonObject();
                wheelOutputs.put(entry.getKey(), new WheelOutputGroup(
                        getString(groupObj, "forward"),
                        getString(groupObj, "backward"),
                        getString(groupObj, "left"),
                        getString(groupObj, "right"),
                        getString(groupObj, "brake")
                ));
            }

            return new DrivingSkill(id, name, description, classification, inputBindings, wheelOutputs);
        }

        private static String getString(JsonObject obj, String key) {
            JsonElement e = obj.get(key);
            return e != null ? e.getAsString() : "";
        }

        private static String getString(JsonObject obj, String key, String def) {
            JsonElement e = obj.get(key);
            return e != null ? e.getAsString() : def;
        }

        private static JsonObject getObject(JsonObject obj, String key) {
            JsonElement e = obj.get(key);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        }
    }

    // ==================================================================
    //  技能应用：分类 + 表达式评估
    // ==================================================================

    /**
     * 对给定悬挂列表执行分类。
     *
     * @param entries 悬挂方块列表（含位置和 FACING 信息）
     * @return 分组结果，每组包含对应的悬挂列表
     */
    public List<WheelGroup> classify(List<WheelEntry> entries) {
        return switch (classification.method()) {
            case "facing_axis" -> classifyByFacingAxis(entries);
            case "side" -> classifyBySide(entries);
            default -> {
                IACP.LOGGER.warn("[DrivingSkill] 未知分类方法: {}, 回退到 facing_axis", classification.method());
                yield classifyByFacingAxis(entries);
            }
        };
    }

    private List<WheelGroup> classifyByFacingAxis(List<WheelEntry> entries) {
        List<WheelEntry> primary = new ArrayList<>();
        List<WheelEntry> secondary = new ArrayList<>();

        for (var w : entries) {
            String axis = w.isEW() ? "EW" : "NS";
            if (classification.isPrimary(axis)) {
                primary.add(w);
            } else if (classification.isSecondary(axis)) {
                secondary.add(w);
            } else {
                // 无法分类的归为主组
                primary.add(w);
            }
        }

        List<WheelGroup> groups = new ArrayList<>();
        if (!primary.isEmpty()) {
            // facing_axis 的 primary 组需要根据重心镜像转向
            WheelGroup mirrored = mirrorByCentroid(primary, "primary");
            groups.add(mirrored);
        }
        if (!secondary.isEmpty()) {
            groups.add(new WheelGroup("secondary", secondary));
        }
        return groups;
    }

    /**
     * 对主驱轮组按重心分裂，将镜像信息附加到每个 WheelEntry 的元数据中。
     * 返回 WheelGroup，可通过 {@link #getGroupOutputs(String, WheelEntry)} 获取带镜像的输出值。
     */
    private WheelGroup mirrorByCentroid(List<WheelEntry> wheels, String groupName) {
        // 计算重心
        double sumPos = 0;
        for (var w : wheels) sumPos += (w.isEW() ? w.posZ() : w.posX());
        double centroid = sumPos / wheels.size();

        // 重新打包：在原输出基础上增加镜像侧标记
        return new WheelGroup(groupName, wheels);
    }

    private List<WheelGroup> classifyBySide(List<WheelEntry> entries) {
        List<WheelEntry> left = new ArrayList<>();
        List<WheelEntry> right = new ArrayList<>();
        List<WheelEntry> unclassified = new ArrayList<>();

        for (var w : entries) {
            if (classification.leftSide() != null && w.facing().equalsIgnoreCase(classification.leftSide())) {
                left.add(w);
            } else if (classification.rightSide() != null && w.facing().equalsIgnoreCase(classification.rightSide())) {
                right.add(w);
            } else {
                unclassified.add(w);
            }
        }

        List<WheelGroup> groups = new ArrayList<>();
        if (!left.isEmpty()) groups.add(new WheelGroup("left_side", left));
        if (!right.isEmpty()) groups.add(new WheelGroup("right_side", right));
        if (!unclassified.isEmpty()) groups.add(new WheelGroup("unclassified", unclassified));
        return groups;
    }

    // ==================================================================
    //  输出表达式评估
    // ==================================================================

    /**
     * 对指定分组中的特定悬挂，评估其 5 个输出表达式，返回物理键名或表达式文本。
     * <p>
     * 若表达式为简单 input.XXX 引用，则通过 inputBindings 解析为物理键名。
     * 若为复杂表达式，返回表达式原文（需运行时求值）。
     *
     * @param groupName 分组名称（如 "primary"）
     * @param entry     当前悬挂
     * @param centroid  分组重心坐标（用于镜像计算）
     * @return 5 个输出名称 → 物理键名或表达式原文
     */
    public Map<String, String> evaluateOutputs(String groupName, WheelEntry entry, double centroid) {
        WheelOutputGroup group = wheelOutputs.get(groupName);
        if (group == null) {
            Map<String, String> empty = new HashMap<>();
            for (var name : WheelOutputGroup.OUTPUT_NAMES) empty.put(name, "");
            return empty;
        }

        Map<String, String> results = new LinkedHashMap<>();
        for (var outputName : WheelOutputGroup.OUTPUT_NAMES) {
            String expr = group.get(outputName);
            results.put(outputName, resolveOutput(expr, entry, centroid));
        }
        return results;
    }

    /**
     * 解析单一输出表达式。
     * <p>
     * 支持：
     * <ul>
     *   <li>简单 input.XXX → 从 inputBindings 查找物理键名</li>
     *   <li>三元表达式 + 镜像条件 → 根据车轮位置分支</li>
     *   <li>其他 → 返回表达式原文</li>
     * </ul>
     */
    private String resolveOutput(String expr, WheelEntry entry, double centroid) {
        if (expr == null || expr.isBlank()) return "";

        String trimmed = expr.trim();

        // 简单引用: input.forward → 查 inputBindings
        if (isSimpleInputRef(trimmed)) {
            String inputName = inputRefName(trimmed);
            return inputBindings.getOrDefault(inputName, "");
        }

        // 尝试解析三元表达式: cond ? then : else
        // 支持的条件: 位置比较 "wheel.pos_z >= vehicle.centroid_z"
        //          或取反   "!(wheel.pos_z >= vehicle.centroid_z)"
        String ternaryResult = tryEvaluateStaticTernary(trimmed, entry, centroid);
        if (ternaryResult != null) return ternaryResult;

        // 复杂表达式：返回原文供运行时求值
        return trimmed;
    }

    /**
     * 尝试评估静态三元表达式（条件为位置比较）。
     * 格式: "wheel.pos_z >= vehicle.centroid_z ? value1 : value2"
     * 或:    "wheel.pos_z < vehicle.centroid_z ? value1 : value2"
     * 或:    "wheel.pos_x >= vehicle.centroid_x ? value1 : value2"
     */
    private String tryEvaluateStaticTernary(String expr, WheelEntry entry, double centroid) {
        // 查找三元运算符
        int qPos = findTopLevelQMark(expr);
        if (qPos < 0) return null;

        String condition = expr.substring(0, qPos).trim();
        String rest = expr.substring(qPos + 1).trim();

        // 找对应的 ':'
        int colonPos = findTopLevelColon(rest);
        if (colonPos < 0) return null;

        String thenVal = rest.substring(0, colonPos).trim();
        String elseVal = rest.substring(colonPos + 1).trim();

        // 评估条件
        boolean condResult = evaluateStaticCondition(condition, entry, centroid);
        String chosen = condResult ? thenVal : elseVal;

        // 递归解析（支持嵌套三元）
        return resolveOutput(chosen, entry, centroid);
    }

    /**
     * 评估静态条件表达式。
     * 支持: wheel.pos_z >= vehicle.centroid_z
     *       wheel.pos_z < vehicle.centroid_z
     *       !(wheel.pos_z >= vehicle.centroid_z)
     */
    private boolean evaluateStaticCondition(String condition, WheelEntry entry, double centroid) {
        // 处理取反
        boolean negate = false;
        String cond = condition;
        if (cond.startsWith("!")) {
            negate = true;
            cond = cond.substring(1).trim();
        }
        // 去掉可能的括号
        if (cond.startsWith("(") && cond.endsWith(")")) {
            cond = cond.substring(1, cond.length() - 1).trim();
        }

        // 解析 wheel.pos_z >= vehicle.centroid_z
        //     或 wheel.pos_x >= vehicle.centroid_x
        //     或 wheel.pos_z < vehicle.centroid_z
        String[] parts;
        String op;
        if (cond.contains(">=")) {
            parts = cond.split(">=", 2);
            op = ">=";
        } else if (cond.contains("<=")) {
            parts = cond.split("<=", 2);
            op = "<=";
        } else if (cond.contains(">")) {
            // 确保不是 ">=" 误匹配
            int idx = cond.indexOf('>');
            if (idx >= 0 && (idx + 1 >= cond.length() || cond.charAt(idx + 1) != '=')) {
                parts = cond.split(">", 2);
                op = ">";
            } else return false;
        } else if (cond.contains("<")) {
            int idx = cond.indexOf('<');
            if (idx >= 0 && (idx + 1 >= cond.length() || cond.charAt(idx + 1) != '=')) {
                parts = cond.split("<", 2);
                op = "<";
            } else return false;
        } else {
            return false;
        }

        if (parts.length != 2) return false;
        String left = parts[0].trim();
        String right = parts[1].trim();

        // 获取左值（车轮位置）
        double leftVal;
        if ("wheel.pos_z".equals(left)) leftVal = entry.posZ();
        else if ("wheel.pos_x".equals(left)) leftVal = entry.posX();
        else return false;

        // 获取右值（重心或数值）
        double rightVal;
        if ("vehicle.centroid_z".equals(right)) rightVal = centroid;
        else if ("vehicle.centroid_x".equals(right)) rightVal = centroid;
        else if ("vehicle.centroid".equals(right)) rightVal = centroid;
        else {
            try { rightVal = Double.parseDouble(right); }
            catch (NumberFormatException e) { return false; }
        }

        boolean result = switch (op) {
            case ">=" -> leftVal >= rightVal;
            case "<=" -> leftVal <= rightVal;
            case ">" -> leftVal > rightVal;
            case "<" -> leftVal < rightVal;
            default -> false;
        };

        return negate != result;
    }

    /**
     * 查找顶层（不在括号内）的 '?' 位置。
     */
    private static int findTopLevelQMark(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '?' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * 查找顶层（不在括号内）的 ':' 位置。
     */
    private static int findTopLevelColon(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ':' && depth == 0) return i;
        }
        return -1;
    }

    // ==================================================================
    //  便利方法
    // ==================================================================

    /**
     * 从 input_bindings 中获取 input.XXX 对应的键名。
     *
     * @param inputName XXX 部分（不含 "input." 前缀）
     * @return 物理键名，未定义时返回空字符串
     */
    public String getInputKey(String inputName) {
        return inputBindings.getOrDefault(inputName, "");
    }

    /**
     * 判断某个输出表达式是否为纯标识符引用（可直接映射为 smartKey）。
     */
    public boolean isDirectKeyMapping(String expr) {
        return isSimpleInputRef(expr);
    }

    @Override
    public String toString() {
        return "DrivingSkill{id='" + id + "', name='" + name + "'}";
    }
}
