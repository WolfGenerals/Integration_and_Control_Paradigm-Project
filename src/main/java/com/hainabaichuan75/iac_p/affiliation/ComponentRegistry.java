package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 部件注册表——SubLevel 内部功能部件的运行时索引。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 * <li><b>运行时缓存</b>：数据不持久化，世界加载时从 NBT 重建</li>
 * <li><b>NBT 是唯一事实来源</b>：不一致时以 NBT 为准，Registry 可随时清空重建</li>
 * <li><b>线程安全</b>：所有 Map 使用 ConcurrentHashMap</li>
 * <li><b>角色索引</b>：按 SubLevel + 角色双重索引，查询 O(1)</li>
 * </ul>
 * <p>
 * <b>索引结构</b>：
 * <pre>
 *   bySubLevel:  SubLevel UUID → Map&lt;BlockPos, ComponentEntry&gt;
 *   byRole:      (SubLevel UUID, Role) → Set&lt;BlockPos&gt;
 *   posToSub:    BlockPos → SubLevel UUID（反向索引，用于快速注销）
 * </pre>
 * <p>
 * <b>典型查询</b>：
 * <ul>
 * <li>"这个 SubLevel 有哪些悬挂方块" → {@link #getComponents(UUID, ComponentRole)}</li>
 * <li>"这个 SubLevel 有哪些武器" →
 * {@link #getComponentsByRoleGroup(UUID, boolean)}</li>
 * <li>"这个 SubLevel 所有功能部件总数" → {@link #getComponentCount(UUID)}</li>
 * </ul>
 */
public final class ComponentRegistry {

    // ==================================================================
    //  索引
    // ==================================================================
    /**
     * SubLevel UUID → { BlockPos → ComponentEntry }
     */
    private static final Map<UUID, Map<BlockPos, ComponentEntry>> BY_SUBLEVEL = new ConcurrentHashMap<>();

    /**
     * SubLevel UUID → { ComponentRole → Set&lt;BlockPos&gt; }
     */
    private static final Map<UUID, Map<ComponentRole, Set<BlockPos>>> BY_ROLE = new ConcurrentHashMap<>();

    /**
     * BlockPos.asLong() → SubLevel UUID（反向索引，快速注销时定位）
     */
    private static final Map<Long, UUID> POS_TO_SUB = new ConcurrentHashMap<>();

    private ComponentRegistry() {
    }

    // ==================================================================
    //  注册 / 注销
    // ==================================================================
    /**
     * 注册一个功能部件。
     * <p>
     * 如果该位置已有注册，旧条目会被覆盖。
     *
     * @param entry 部件条目（包含 SubLevel UUID、BlockPos、Role、BE 引用）
     */
    public static void register(ComponentEntry entry) {
        if (entry == null) {
            return;
        }

        UUID subUUID = entry.subLevelUUID();
        BlockPos pos = entry.blockPos();
        ComponentRole role = entry.role();

        // 先注销旧的（如有），确保索引一致
        unregister(pos);

        // 主索引
        BY_SUBLEVEL.computeIfAbsent(subUUID, k -> new ConcurrentHashMap<>())
                .put(pos, entry);

        // 角色索引
        BY_ROLE.computeIfAbsent(subUUID, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet())
                .add(pos);

        // 反向索引
        POS_TO_SUB.put(pos.asLong(), subUUID);

        IACP.LOGGER.debug("[ComponentRegistry] 注册: {} role={} @ SubLevel {}",
                pos, role, subUUID.toString().substring(0, 8));
    }

    /**
     * 注销指定位置的功能部件。
     *
     * @param pos 方块位置
     */
    public static void unregister(BlockPos pos) {
        if (pos == null) {
            return;
        }

        Long posLong = pos.asLong();
        UUID subUUID = POS_TO_SUB.remove(posLong);
        if (subUUID == null) {
            return;
        }

        // 从主索引中移除
        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap != null) {
            ComponentEntry removed = subMap.remove(pos);
            if (removed != null) {
                // 从角色索引中移除
                Map<ComponentRole, Set<BlockPos>> roleMap = BY_ROLE.get(subUUID);
                if (roleMap != null) {
                    Set<BlockPos> roleSet = roleMap.get(removed.role());
                    if (roleSet != null) {
                        roleSet.remove(pos);
                        if (roleSet.isEmpty()) {
                            roleMap.remove(removed.role());
                        }
                    }
                    if (roleMap.isEmpty()) {
                        BY_ROLE.remove(subUUID);
                    }
                }
            }
            if (subMap.isEmpty()) {
                BY_SUBLEVEL.remove(subUUID);
            }
        }

        IACP.LOGGER.debug("[ComponentRegistry] 注销: {}", pos);
    }

    /**
     * 更新指定 BlockPos 的 BE 引用（世界重载后重建）。
     *
     * @param subUUID SubLevel UUID
     * @param pos 方块位置
     * @param be 新的 BE 引用
     */
    public static void updateBlockEntity(UUID subUUID, BlockPos pos, BlockEntity be) {
        if (subUUID == null || pos == null) {
            return;
        }

        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap == null) {
            return;
        }

        ComponentEntry old = subMap.get(pos);
        if (old != null) {
            ComponentEntry updated = old.withBlockEntity(be);
            subMap.put(pos, updated);
        }
    }

    /**
     * 清除指定 SubLevel 的所有部件注册（SubLevel 销毁时调用）。
     *
     * @param subUUID SubLevel UUID
     */
    public static void clearSubLevel(UUID subUUID) {
        if (subUUID == null) {
            return;
        }

        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.remove(subUUID);
        if (subMap != null) {
            // 清理反向索引
            for (BlockPos pos : subMap.keySet()) {
                POS_TO_SUB.remove(pos.asLong());
            }
        }
        BY_ROLE.remove(subUUID);

        IACP.LOGGER.info("[ComponentRegistry] 清除 SubLevel {} 的所有部件 ({} 个)",
                subUUID.toString().substring(0, 8),
                subMap != null ? subMap.size() : 0);
    }

    // ==================================================================
    //  查询
    // ==================================================================
    /**
     * 获取 SubLevel 中指定角色的所有部件。
     * <p>
     * 这是最常用的查询——CockpitBE 用它获取悬挂列表，WeaponOverlay 用它获取武器列表。
     *
     * @param subUUID SubLevel UUID
     * @param role 角色过滤（null 返回全部）
     * @return 部件列表，按注册顺序排列
     */
    public static List<ComponentEntry> getComponents(UUID subUUID, @Nullable ComponentRole role) {
        if (subUUID == null) {
            return List.of();
        }

        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap == null || subMap.isEmpty()) {
            return List.of();
        }

        if (role == null) {
            return List.copyOf(subMap.values());
        }

        Map<ComponentRole, Set<BlockPos>> roleMap = BY_ROLE.get(subUUID);
        if (roleMap == null) {
            return List.of();
        }

        Set<BlockPos> positions = roleMap.get(role);
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        List<ComponentEntry> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            ComponentEntry entry = subMap.get(pos);
            if (entry != null) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取 SubLevel 中所有武器类部件（isWeapon() 为 true 的角色）。
     *
     * @param subUUID SubLevel UUID
     * @return 武器部件列表
     */
    public static List<ComponentEntry> getWeapons(UUID subUUID) {
        if (subUUID == null) {
            return List.of();
        }

        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap == null || subMap.isEmpty()) {
            return List.of();
        }

        return subMap.values().stream()
                .filter(e -> e.role().isWeapon())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取 SubLevel 中所有性能修饰部件（isModifier() 为 true 的角色）。
     *
     * @param subUUID SubLevel UUID
     * @return 修饰部件列表
     */
    public static List<ComponentEntry> getModifiers(UUID subUUID) {
        if (subUUID == null) {
            return List.of();
        }

        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap == null || subMap.isEmpty()) {
            return List.of();
        }

        return subMap.values().stream()
                .filter(e -> e.role().isModifier())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取 SubLevel 中所有已注册部件的总数。
     *
     * @param subUUID SubLevel UUID
     * @return 部件数量，-1 表示 SubLevel 未注册
     */
    public static int getComponentCount(UUID subUUID) {
        if (subUUID == null) {
            return -1;
        }
        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        return subMap != null ? subMap.size() : -1;
    }

    /**
     * 获取特定位置的部件。
     *
     * @param pos 方块位置
     * @return 部件条目，未注册返回 null
     */
    @Nullable
    public static ComponentEntry getAt(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        Long posLong = pos.asLong();
        UUID subUUID = POS_TO_SUB.get(posLong);
        if (subUUID == null) {
            return null;
        }
        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        return subMap != null ? subMap.get(pos) : null;
    }

    /**
     * 检查 SubLevel 是否包含指定角色的部件。
     *
     * @param subUUID SubLevel UUID
     * @param role 要检查的角色
     * @return true 如果至少有一个该角色的部件
     */
    public static boolean hasRole(UUID subUUID, ComponentRole role) {
        if (subUUID == null || role == null) {
            return false;
        }
        Map<ComponentRole, Set<BlockPos>> roleMap = BY_ROLE.get(subUUID);
        if (roleMap == null) {
            return false;
        }
        Set<BlockPos> positions = roleMap.get(role);
        return positions != null && !positions.isEmpty();
    }

    /**
     * 获取指定 SubLevel 中所有已注册的角色类型。
     *
     * @param subUUID SubLevel UUID
     * @return 角色类型集合
     */
    public static Set<ComponentRole> getRoles(UUID subUUID) {
        if (subUUID == null) {
            return Set.of();
        }
        Map<ComponentRole, Set<BlockPos>> roleMap = BY_ROLE.get(subUUID);
        return roleMap != null ? Collections.unmodifiableSet(roleMap.keySet()) : Set.of();
    }

    // ==================================================================
    //  生命周期
    // ==================================================================
    /**
     * 世界加载时调用：清空所有索引，等待从 NBT/BE.onLoad() 重建。
     */
    public static void onWorldLoad() {
        clearAll();
    }

    /**
     * 清除所有注册数据。
     */
    public static void clearAll() {
        BY_SUBLEVEL.clear();
        BY_ROLE.clear();
        POS_TO_SUB.clear();
        IACP.LOGGER.info("[ComponentRegistry] 已清除所有索引");
    }

    // ==================================================================
    //  调试
    // ==================================================================
    /**
     * 获取指定 SubLevel 的注册状态摘要。
     */
    public static String getDebugString(UUID subUUID) {
        if (subUUID == null) {
            return "null";
        }
        Map<BlockPos, ComponentEntry> subMap = BY_SUBLEVEL.get(subUUID);
        if (subMap == null) {
            return "未注册";
        }
        Map<ComponentRole, Set<BlockPos>> roleMap = BY_ROLE.get(subUUID);
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(subMap.size()).append(" 个部件\n");
        if (roleMap != null) {
            for (var entry : roleMap.entrySet()) {
                sb.append("  ").append(entry.getKey().name())
                        .append(": ").append(entry.getValue().size()).append(" 个\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取当前注册的 SubLevel 数量。
     */
    public static int getSubLevelCount() {
        return BY_SUBLEVEL.size();
    }

    /**
     * 获取当前注册的部件总数。
     */
    public static int getTotalComponentCount() {
        return POS_TO_SUB.size();
    }
}
