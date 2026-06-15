package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;

import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 归属系统集中索引——运行时注册表。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 * <li><b>运行时缓存</b>：数据不持久化，世界加载时从 BlockEntity NBT 重建</li>
 * <li><b>NBT 是唯一事实来源</b>：不一致时以 NBT 为准，Registry 可随时清空重建</li>
 * <li><b>线程安全</b>：所有 Map 使用 ConcurrentHashMap</li>
 * </ul>
 * <p>
 * <b>索引结构</b>：
 * <pre>
 *   subLevelToTag:          SubLevel UUID → AffiliationTag（身份证）
 *   groupToMembers:         Group UUID → Set&lt;SubLevel UUID&gt;（组内成员）
 *   playerToVehicle:        玩家 UUID → 载具 SubLevel UUID
 *   vehicleToPlayer:        载具 SubLevel UUID → 玩家 UUID（反向，用于广播/判断）
 *   vehicleToGroups:        载具 UUID → Set&lt;Group UUID&gt;（载具拥有的组）
 *   vehicleToDirectMembers: 载具 UUID → Set&lt;SubLevel UUID&gt;（直接衍生结构，无组）
 * </pre>
 * <p>
 * <b>典型查询</b>：
 * <ul>
 * <li>"这个炮塔有哪些部件" → {@link #getAllInGroup(UUID)}</li>
 * <li>"这辆车及其所有衍生结构" → {@link #getOwnAffiliatedSet(UUID)}</li>
 * <li>"谁在开这辆车" → {@link #getPlayerOfVehicle(UUID)}</li>
 * </ul>
 */
public final class AffiliationRegistry {

    // ==================================================================
    //  索引
    // ==================================================================
    /**
     * SubLevel UUID → AffiliationTag
     */
    private static final Map<UUID, AffiliationTag> SUBLEVEL_TO_TAG = new ConcurrentHashMap<>();

    /**
     * Group UUID → Set<SubLevel UUID>（组内所有成员）
     */
    private static final Map<UUID, Set<UUID>> GROUP_TO_MEMBERS = new ConcurrentHashMap<>();

    /**
     * 玩家 UUID → 载具 SubLevel UUID
     */
    private static final Map<UUID, UUID> PLAYER_TO_VEHICLE = new ConcurrentHashMap<>();

    /**
     * 载具 SubLevel UUID → 玩家 UUID（反向索引）
     */
    private static final Map<UUID, UUID> VEHICLE_TO_PLAYER = new ConcurrentHashMap<>();

    /**
     * 载具 SubLevel UUID → Set<Group UUID>（载具拥有的所有组）
     */
    private static final Map<UUID, Set<UUID>> VEHICLE_TO_GROUPS = new ConcurrentHashMap<>();

    /**
     * 载具 SubLevel UUID → Set<SubLevel UUID>（不属于任何组的直接衍生结构）。
     * <p>
     * 用于快速查找属于某载具但不属于任何逻辑组的散落 SubLevel（如主世界炮塔的部件）， 避免
     * {@link #getOwnAffiliatedSet} 每次全表扫描 {@link #SUBLEVEL_TO_TAG}。
     * <p>
     * 组内成员通过 {@link #VEHICLE_TO_GROUPS} → {@link #GROUP_TO_MEMBERS} 索引， 不在此 Map
     * 中重复存储。
     */
    private static final Map<UUID, Set<UUID>> VEHICLE_TO_DIRECT_MEMBERS = new ConcurrentHashMap<>();

    // ==================================================================
    //  性能监控
    // ==================================================================
    /**
     * 慢查询阈值（纳秒）。超过此阈值的查询会被记录到 DEBUG 日志。
     * <p>
     * 默认 100 微秒 (100_000 ns)。可通过调试命令临时调整。
     */
    private static long SLOW_QUERY_THRESHOLD_NS = 100_000;

    /**
     * 写操作锁对象。用于确保 register/unregister/clearAll 的原子性， 防止多线程下 unregister 和 put
     * 之间的竞态条件。
     */
    private static final Object WRITE_LOCK = new Object();

    /**
     * 设置慢查询阈值（纳秒）。传入 0 或负值表示禁用慢查询日志。
     */
    public static void setSlowQueryThreshold(long nanos) {
        SLOW_QUERY_THRESHOLD_NS = nanos;
        IACP.LOGGER.info("[AffiliationRegistry] 慢查询阈值已设为 {} ns", nanos);
    }

    /**
     * 获取当前慢查询阈值。
     */
    public static long getSlowQueryThreshold() {
        return SLOW_QUERY_THRESHOLD_NS;
    }

    private AffiliationRegistry() {
    }

    // ==================================================================
    //  注册 / 注销
    // ==================================================================
    /**
     * 注册一个 SubLevel 的归属信息。
     * <p>
     * 如果该 SubLevel 已注册，旧标签会被覆盖。
     *
     * @param subLevelUUID SubLevel 的 UUID
     * @param tag 归属标签
     */
    public static void register(UUID subLevelUUID, AffiliationTag tag) {
        if (subLevelUUID == null || tag == null) {
            return;
        }

        synchronized (WRITE_LOCK) {
            // 内联清理旧的索引条目（不调用 unregister，避免重复加锁和事件滥用）
            AffiliationTag oldTag = SUBLEVEL_TO_TAG.remove(subLevelUUID);
            if (oldTag != null) {
                removeFromIndices(subLevelUUID, oldTag);
            }

            SUBLEVEL_TO_TAG.put(subLevelUUID, tag);

            // 维护组索引和直接成员索引
            if (tag.groupId() != null) {
                GROUP_TO_MEMBERS.computeIfAbsent(tag.groupId(), k -> ConcurrentHashMap.newKeySet())
                        .add(subLevelUUID);

                // 维护载具→组索引
                if (tag.vehicleId() != null) {
                    VEHICLE_TO_GROUPS.computeIfAbsent(tag.vehicleId(), k -> ConcurrentHashMap.newKeySet())
                            .add(tag.groupId());
                }
            } else if (tag.vehicleId() != null && !subLevelUUID.equals(tag.vehicleId())) {
                // 有 vehicleId 但无 groupId → 直接衍生结构，加入 VEHICLE_TO_DIRECT_MEMBERS
                // 排除 vehicleUUID == subLevelUUID 的情况（载具主体自身）
                VEHICLE_TO_DIRECT_MEMBERS.computeIfAbsent(tag.vehicleId(), k -> ConcurrentHashMap.newKeySet())
                        .add(subLevelUUID);
            }
        }

        IACP.LOGGER.debug("[AffiliationRegistry] 注册: {} → role={}, group={}",
                subLevelUUID.toString().substring(0, 8),
                tag.role(), tag.groupId() != null ? tag.groupId().toString().substring(0, 8) : "null");

        // 抛出事件（在锁外抛出，避免死锁）
        NeoForge.EVENT_BUS.post(AffiliationChangeEvent.ofSubLevel(
                AffiliationChangeEvent.ChangeType.REGISTER, subLevelUUID, tag));
    }

    /**
     * 注销一个 SubLevel 的归属信息。
     * <p>
     * 自动从所有索引中移除。
     *
     * @param subLevelUUID SubLevel 的 UUID
     */
    /**
     * 从辅助索引中移除一个 SubLevel 的记录（不操作 SUBLEVEL_TO_TAG）。
     * <p>
     * 调用方必须持有 {@link #WRITE_LOCK}。
     */
    private static void removeFromIndices(UUID subLevelUUID, AffiliationTag tag) {
        if (tag.groupId() != null) {
            Set<UUID> members = GROUP_TO_MEMBERS.get(tag.groupId());
            if (members != null) {
                members.remove(subLevelUUID);
                if (members.isEmpty()) {
                    GROUP_TO_MEMBERS.remove(tag.groupId());
                    // 清理载具→组索引
                    if (tag.vehicleId() != null) {
                        Set<UUID> groups = VEHICLE_TO_GROUPS.get(tag.vehicleId());
                        if (groups != null) {
                            groups.remove(tag.groupId());
                            if (groups.isEmpty()) {
                                VEHICLE_TO_GROUPS.remove(tag.vehicleId());
                            }
                        }
                    }
                }
            }
        } else if (tag.vehicleId() != null && !subLevelUUID.equals(tag.vehicleId())) {
            // 从直接衍生索引中移除
            Set<UUID> directMembers = VEHICLE_TO_DIRECT_MEMBERS.get(tag.vehicleId());
            if (directMembers != null) {
                directMembers.remove(subLevelUUID);
                if (directMembers.isEmpty()) {
                    VEHICLE_TO_DIRECT_MEMBERS.remove(tag.vehicleId());
                }
            }
        }
    }

    public static void unregister(UUID subLevelUUID) {
        if (subLevelUUID == null) {
            return;
        }

        AffiliationTag oldTag;
        synchronized (WRITE_LOCK) {
            oldTag = SUBLEVEL_TO_TAG.remove(subLevelUUID);
            if (oldTag == null) {
                return;
            }
            removeFromIndices(subLevelUUID, oldTag);
        }

        IACP.LOGGER.debug("[AffiliationRegistry] 注销: {}", subLevelUUID.toString().substring(0, 8));

        // 抛出事件（在锁外抛出，避免死锁）
        NeoForge.EVENT_BUS.post(AffiliationChangeEvent.ofSubLevel(
                AffiliationChangeEvent.ChangeType.UNREGISTER, subLevelUUID, oldTag));
    }

    /**
     * 整组注销：移除组内所有成员的归属记录。
     * <p>
     * 用于炮塔被摧毁时一次性清理所有部件。
     *
     * @param groupId 组 UUID
     */
    public static void unregisterGroup(UUID groupId) {
        if (groupId == null) {
            return;
        }

        Set<UUID> members;
        synchronized (WRITE_LOCK) {
            members = GROUP_TO_MEMBERS.remove(groupId);
            if (members != null) {
                for (UUID memberUUID : members) {
                    SUBLEVEL_TO_TAG.remove(memberUUID);
                    // 组内成员不可能在 VEHICLE_TO_DIRECT_MEMBERS 中（有 groupId），
                    // 但清理 VEHICLE_TO_GROUPS 索引
                }
            }

            // 清理载具→组索引
            for (Map.Entry<UUID, Set<UUID>> entry : VEHICLE_TO_GROUPS.entrySet()) {
                Set<UUID> groups = entry.getValue();
                if (groups != null) {
                    groups.remove(groupId);
                    if (groups.isEmpty()) {
                        VEHICLE_TO_GROUPS.remove(entry.getKey());
                    }
                }
            }
        }

        if (members != null) {
            IACP.LOGGER.info("[AffiliationRegistry] 整组注销: {}, 共 {} 个成员",
                    groupId.toString().substring(0, 8), members.size());
        }

        // 抛出事件（在锁外）
        NeoForge.EVENT_BUS.post(AffiliationChangeEvent.ofGroup(groupId));
    }

    // ==================================================================
    //  性能监控辅助
    // ==================================================================
    /**
     * 记录查询耗时，超过阈值时输出 DEBUG 日志。
     *
     * @param methodName 方法名
     * @param startNanos 起始纳秒时间
     * @param detail 附加信息（如 UUID 缩写），可为 null
     */
    private static void recordQueryTime(String methodName, long startNanos, @Nullable String detail) {
        if (SLOW_QUERY_THRESHOLD_NS <= 0) {
            return; // 禁用
        }
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed > SLOW_QUERY_THRESHOLD_NS) {
            String suffix = detail != null ? " [" + detail + "]" : "";
            IACP.LOGGER.debug("[AffiliationRegistry] 慢查询: {}{} 耗时 {} μs",
                    methodName, suffix, elapsed / 1000);
        }
    }

    // ==================================================================
    //  查询
    // ==================================================================
    /**
     * 获取 SubLevel 的归属标签。
     *
     * @return 标签，未注册时返回 null
     */
    @Nullable
    public static AffiliationTag getAffiliation(UUID subLevelUUID) {
        if (subLevelUUID == null) {
            return null;
        }
        long start = System.nanoTime();
        AffiliationTag result = SUBLEVEL_TO_TAG.get(subLevelUUID);
        recordQueryTime("getAffiliation", start, subLevelUUID.toString().substring(0, 8));
        return result;
    }

    /**
     * 获取 SubLevel 的角色。
     */
    public static AffiliationRole getRole(UUID subLevelUUID) {
        AffiliationTag tag = getAffiliation(subLevelUUID);
        return tag != null ? tag.role() : AffiliationRole.UNKNOWN;
    }

    /**
     * 获取 SubLevel 所属的载具 UUID。
     */
    @Nullable
    public static UUID getVehicle(UUID subLevelUUID) {
        AffiliationTag tag = getAffiliation(subLevelUUID);
        return tag != null ? tag.vehicleId() : null;
    }

    /**
     * 获取 SubLevel 所属的组 UUID。
     */
    @Nullable
    public static UUID getGroup(UUID subLevelUUID) {
        AffiliationTag tag = getAffiliation(subLevelUUID);
        return tag != null ? tag.groupId() : null;
    }

    /**
     * 获取组内所有成员的 SubLevel UUID。
     *
     * @return 不可变集合，组不存在或为空时返回空集合
     */
    public static Set<UUID> getAllInGroup(@Nullable UUID groupId) {
        if (groupId == null) {
            return Collections.emptySet();
        }
        long start = System.nanoTime();
        Set<UUID> members = GROUP_TO_MEMBERS.get(groupId);
        recordQueryTime("getAllInGroup", start, groupId.toString().substring(0, 8));
        return members != null ? Collections.unmodifiableSet(members) : Collections.emptySet();
    }

    /**
     * 获取载具自身及其所有相关 SubLevel UUID 的并集。
     * <p>
     * 用于射线追踪排除：返回载具自身 + 所有衍生结构（炮塔底座、砂轮、避雷针等）。 包括：
     * <ul>
     * <li>载具自身</li>
     * <li>所有组内成员（通过 VEHICLE_TO_GROUPS 索引）</li>
     * <li>所有 vehicleId 指向此载具但不在任何组中的散落衍生结构</li>
     * </ul>
     *
     * @param vehicleUUID 载具 SubLevel UUID
     * @return 包含载具自身和所有相关 SubLevel 的不可变集合
     */
    public static Set<UUID> getOwnAffiliatedSet(@Nullable UUID vehicleUUID) {
        if (vehicleUUID == null) {
            return Collections.emptySet();
        }

        Set<UUID> result = new HashSet<>();
        result.add(vehicleUUID);

        // 收集该载具的所有组内成员（通过 VEHICLE_TO_GROUPS → GROUP_TO_MEMBERS 索引，O(1)）
        Set<UUID> groups = VEHICLE_TO_GROUPS.get(vehicleUUID);
        if (groups != null) {
            for (UUID groupId : groups) {
                Set<UUID> members = GROUP_TO_MEMBERS.get(groupId);
                if (members != null) {
                    result.addAll(members);
                }
            }
        }

        // 收集不在任何组中的散落衍生结构（通过 VEHICLE_TO_DIRECT_MEMBERS 索引，O(1)）
        // 不再全表扫描 SUBLEVEL_TO_TAG
        Set<UUID> directMembers = VEHICLE_TO_DIRECT_MEMBERS.get(vehicleUUID);
        if (directMembers != null) {
            result.addAll(directMembers);
        }

        return Collections.unmodifiableSet(result);
    }

    // ==================================================================
    //  玩家-载具绑定
    // ==================================================================
    /**
     * 设置玩家当前驾驶的载具。
     *
     * @param playerUUID 玩家 UUID
     * @param vehicleUUID 载具 SubLevel UUID，为 null 表示下车/解绑
     */
    public static void setPlayerVehicle(UUID playerUUID, @Nullable UUID vehicleUUID) {
        if (playerUUID == null) {
            return;
        }

        synchronized (WRITE_LOCK) {
            // 清除旧的绑定
            UUID oldVehicle = PLAYER_TO_VEHICLE.remove(playerUUID);
            if (oldVehicle != null) {
                VEHICLE_TO_PLAYER.remove(oldVehicle);
            }

            // 设置新的绑定
            if (vehicleUUID != null) {
                PLAYER_TO_VEHICLE.put(playerUUID, vehicleUUID);
                VEHICLE_TO_PLAYER.put(vehicleUUID, playerUUID);
            }
        }

        // 抛出事件（在锁外）
        NeoForge.EVENT_BUS.post(AffiliationChangeEvent.ofPlayerBind(playerUUID, vehicleUUID));
    }

    /**
     * 获取玩家当前驾驶的载具 UUID。
     *
     * @return 载具 SubLevel UUID，未在驾驶时返回 null
     */
    @Nullable
    public static UUID getPlayerVehicle(UUID playerUUID) {
        if (playerUUID == null) {
            return null;
        }
        long start = System.nanoTime();
        UUID result = PLAYER_TO_VEHICLE.get(playerUUID);
        recordQueryTime("getPlayerVehicle", start, playerUUID.toString().substring(0, 8));
        return result;
    }

    /**
     * 获取驾驶该载具的玩家 UUID。
     *
     * @param vehicleUUID 载具 SubLevel UUID
     * @return 玩家 UUID，无人驾驶时返回 null
     */
    @Nullable
    public static UUID getPlayerOfVehicle(UUID vehicleUUID) {
        return vehicleUUID != null ? VEHICLE_TO_PLAYER.get(vehicleUUID) : null;
    }

    // ==================================================================
    //  交互策略解析
    // ==================================================================
    /**
     * 判断两个归属标签是否属于同一辆车。
     */
    public static boolean isSameVehicle(@Nullable AffiliationTag a, @Nullable AffiliationTag b) {
        if (a == null || b == null) {
            return false;
        }
        // 两者 vehicleId 非空且相同 → 同车
        if (a.vehicleId() != null && b.vehicleId() != null) {
            return a.vehicleId().equals(b.vehicleId());
        }
        // 一方 vehicleId 为 null（是载具主体自身）→ 比较 ownerId
        UUID aVehicle = a.vehicleId() != null ? a.vehicleId() : a.ownerId();
        UUID bVehicle = b.vehicleId() != null ? b.vehicleId() : b.ownerId();
        return aVehicle != null && aVehicle.equals(bVehicle);
    }

    /**
     * 判断两个归属标签是否属于同一阵营（未来扩展）。
     */
    public static boolean isSameFaction(@Nullable AffiliationTag a, @Nullable AffiliationTag b) {
        if (a == null || b == null) {
            return false;
        }
        return a.faction() == b.faction() && a.faction() != AffiliationTag.FACTION_NEUTRAL;
    }

    /**
     * 解析射线交互策略。
     * <p>
     * 根据射线类型、观察者标签、目标标签，返回应采用的交互策略。
     *
     * @param rayType 射线类型
     * @param viewer 观察者（发出射线的实体）的标签，可为 null
     * @param target 目标 SubLevel 的标签，不可为 null
     * @return 交互策略
     */
    public static RayPolicy resolvePolicy(RayType rayType, @Nullable AffiliationTag viewer, AffiliationTag target) {
        long start = System.nanoTime();
        RayPolicy result = resolvePolicyImpl(rayType, viewer, target);
        recordQueryTime("resolvePolicy", start, rayType.name() + "/" + (target.role() != null ? target.role().name() : "?"));
        return result;
    }

    /**
     * resolvePolicy 的内部实现，便于外层包裹性能监控。
     */
    private static RayPolicy resolvePolicyImpl(RayType rayType, @Nullable AffiliationTag viewer, AffiliationTag target) {
        if (target == null) {
            return RayPolicy.BLOCK;
        }

        // ================================================================
        //  CAMERA_AIM 路径：纯角色决定，不需要 sameVehicle/sameFaction 判断
        //  所有实体结构统一 PENETRATE_AABB，弹射物/传感器统一 IGNORE
        // ================================================================
        if (rayType == RayType.CAMERA_AIM) {
            return switch (target.role()) {
                case PROJECTILE, SENSOR ->
                    RayPolicy.IGNORE;
                default ->
                    RayPolicy.PENETRATE_AABB;
            };
        }

        boolean sameVehicle = isSameVehicle(viewer, target);
        boolean sameFaction = isSameFaction(viewer, target);

        // 如果观察者和目标都无归属信息，回退为默认阻挡
        if (viewer == null && target.role() == AffiliationRole.UNKNOWN) {
            return RayPolicy.BLOCK;
        }

        return switch (rayType) {
            // ============================================================
            //  武器伤害射线
            // ============================================================
            case WEAPON_DAMAGE -> {
                if (sameVehicle) {
                    yield RayPolicy.IGNORE; // 完全不打自己
                }
                if (sameFaction) {
                    yield RayPolicy.IGNORE; // 不开友伤（未来可配置）
                }
                yield RayPolicy.DAMAGE; // 敌对 → 阻挡+伤害
            }

            // ============================================================
            //  传感器扫描（预留）
            // ============================================================
            case SENSOR_SCAN -> {
                if (sameVehicle) {
                    yield RayPolicy.IGNORE; // 自己的结构不干扰传感器
                }
                yield RayPolicy.BLOCK; // 其余正常阻挡
            }

            // ============================================================
            //  CAMERA_AIM 已被早返回拦截，不应到达此处
            // ============================================================
            case CAMERA_AIM -> {
                IACP.LOGGER.warn("[AffiliationRegistry] CAMERA_AIM 未在 resolvePolicyImpl 中被早返回拦截");
                yield RayPolicy.PENETRATE_AABB;
            }
        };
    }

    // ==================================================================
    //  生命周期
    // ==================================================================
    /**
     * 世界加载时调用：清空所有索引，等待从 NBT 重建。
     */
    public static void onWorldLoad() {
        clearAll();
    }

    /**
     * 清除所有注册数据（调试/重载用）。
     */
    public static void clearAll() {
        synchronized (WRITE_LOCK) {
            SUBLEVEL_TO_TAG.clear();
            GROUP_TO_MEMBERS.clear();
            PLAYER_TO_VEHICLE.clear();
            VEHICLE_TO_PLAYER.clear();
            VEHICLE_TO_GROUPS.clear();
            VEHICLE_TO_DIRECT_MEMBERS.clear();
        }
        IACP.LOGGER.info("[AffiliationRegistry] 已清除所有索引");
    }

    /**
     * 获取当前注册的 SubLevel 数量（调试用）。
     */
    public static int size() {
        return SUBLEVEL_TO_TAG.size();
    }
}
