/*
 * 技能注册表 —— 管理所有可用驾驶技能的加载、注册和查询。
 *
 * 当前实现：
 *   - 内置默认技能（car_mode）通过硬编码 JSON 初始化
 *   - 未来扩展：从数据包（datapack）加载第三方技能 JSON
 *
 * 用法：
 *   SkillRegistry registry = SkillRegistry.createDefault();
 *   DrivingSkill skill = registry.get("iac_p:car_mode");
 */
package com.hainabaichuan75.iac_p.skill;

import com.hainabaichuan75.iac_p.IACP;

import java.util.*;

/**
 * 驾驶技能注册表（单例）。
 * <p>
 * 线程安全：初始化后所有查询为只读操作。
 */
public final class SkillRegistry {

    /** 内置默认技能 ID */
    public static final String DEFAULT_SKILL_ID = "iac_p:car_mode";

    /** 全局单例 */
    private static SkillRegistry INSTANCE = null;

    /** ID → 技能映射 */
    private final Map<String, DrivingSkill> skills = new LinkedHashMap<>();

    /** 注册顺序列表 */
    private final List<DrivingSkill> skillList = new ArrayList<>();

    private SkillRegistry() {}

    // ==================================================================
    //  单例访问
    // ==================================================================

    /**
     * 获取全局技能注册表单例（含默认技能）。
     * 首次调用时自动初始化。
     */
    public static synchronized SkillRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = createDefault();
        }
        return INSTANCE;
    }

    /**
     * 重置注册表（重新加载时调用）。
     */
    public static synchronized void reset() {
        INSTANCE = createDefault();
        IACP.LOGGER.info("[SkillRegistry] 已重置");
    }

    /**
     * 创建包含默认技能的注册表。
     */
    public static SkillRegistry createDefault() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerBuiltinSkills();
        return registry;
    }

    // ==================================================================
    //  注册
    // ==================================================================

    /**
     * 注册一个技能。
     */
    public void register(DrivingSkill skill) {
        if (skills.containsKey(skill.id())) {
            IACP.LOGGER.warn("[SkillRegistry] 技能 '{}' 已存在，覆盖", skill.id());
        }
        skills.put(skill.id(), skill);
        skillList.add(skill);
    }

    /**
     * 从 JSON 字符串注册技能。
     */
    public DrivingSkill registerFromJson(String json) {
        DrivingSkill skill = DrivingSkill.fromJson(json);
        register(skill);
        return skill;
    }

    // ==================================================================
    //  查询
    // ==================================================================

    /**
     * 按 ID 获取技能。
     */
    public DrivingSkill get(String id) {
        DrivingSkill skill = skills.get(id);
        if (skill == null) {
            IACP.LOGGER.warn("[SkillRegistry] 未找到技能 '{}'，回退到默认", id);
            return skills.get(DEFAULT_SKILL_ID);
        }
        return skill;
    }

    /**
     * 获取默认技能。
     */
    public DrivingSkill getDefault() {
        return skills.get(DEFAULT_SKILL_ID);
    }

    /**
     * 获取所有已注册技能（按注册顺序）。
     */
    public List<DrivingSkill> getAll() {
        return Collections.unmodifiableList(skillList);
    }

    /**
     * 获取所有技能 ID 列表。
     */
    public List<String> getAllIds() {
        return skillList.stream().map(DrivingSkill::id).toList();
    }

    /**
     * 检查技能是否存在。
     */
    public boolean contains(String id) {
        return skills.containsKey(id);
    }

    /**
     * 返回注册的技能数量。
     */
    public int size() {
        return skills.size();
    }

    // ==================================================================
    //  内置技能
    // ==================================================================

    /**
     * 注册内置技能。
     */
    private void registerBuiltinSkills() {
        // ── car_mode：汽车模式 ──
        // EW 轮 = 驱动轮（W/S 前后，A/D 镜像转向）
        // NS 轮 = 横移轮（Q/E 横移）
        registerFromJson(CAR_MODE_JSON);

        // ── 未来更多内置技能在这里添加 ──
        // registerFromJson(TANK_SKID_JSON);
    }

    // ==================================================================
    //  内置技能 JSON
    // ==================================================================

    private static final String CAR_MODE_JSON = """
            {
              "id": "iac_p:car_mode",
              "name": "汽车模式",
              "description": "W/S 前进后退、A/D 转向、Q/E 横移\\n驱动轮（EW 朝向）对应前进/后退/转向\\n横移轮（NS 朝向）对应左右平移",
              "wheel_classification": {
                "method": "facing_axis",
                "primary": "EW",
                "secondary": "NS"
              },
              "input_bindings": {
                "forward": "key.keyboard.w",
                "backward": "key.keyboard.s",
                "turn_left": "key.keyboard.a",
                "turn_right": "key.keyboard.d",
                "strafe_left": "key.keyboard.q",
                "strafe_right": "key.keyboard.e",
                "brake": "key.keyboard.space"
              },
              "wheel_outputs": {
                "primary": {
                  "forward": "input.forward",
                  "backward": "input.backward",
                  "left": "wheel.pos_z >= vehicle.centroid_z ? input.turn_left : input.turn_right",
                  "right": "wheel.pos_z >= vehicle.centroid_z ? input.turn_right : input.turn_left",
                  "brake": "input.brake"
                },
                "secondary": {
                  "forward": "input.strafe_right",
                  "backward": "input.strafe_left",
                  "left": "false",
                  "right": "false",
                  "brake": "false"
                }
              }
            }
            """;
}
