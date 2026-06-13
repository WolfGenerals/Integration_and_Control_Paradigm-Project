package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SubLevel 归属管理系统。
 * <p>
 * 跟踪每个"衍生 SubLevel"（如炮塔的砂轮、避雷针）从属于哪个"载具 SubLevel"。
 * 用于在武器射线追踪时排除所有归属于同一载具的 SubLevel，防止自伤。
 * <p>
 * <b>注册时机</b>：在 {@code TurretBaseBlockEntity.assemble()} 中创建砂轮/避雷针 SubLevel 后立即注册。
 * <b>注销时机</b>：在 {@code TurretBaseBlockEntity.disassemble()} 中移除 SubLevel 前注销。
 * <b>使用场景</b>：{@link SableBlockHelper#rayTraceSubLevels} 中查询并排除。
 */
public final class SubLevelOwnership {

    /** ownedSubLevel → ownerVehicleSubLevel */
    private static final Map<UUID, UUID> OWNED_BY = new ConcurrentHashMap<>();

    /** ownerVehicleSubLevel → Set<ownedSubLevel>（反向索引，便于批量查询） */
    private static final Map<UUID, Set<UUID>> OWNER_TO_OWNED = new ConcurrentHashMap<>();

    private SubLevelOwnership() {}

    // ==============================
    //  注册 / 注销
    // ==============================

    /**
     * 注册一个衍生 SubLevel 归属于某个载具 SubLevel。
     *
     * @param subLevelUUID     衍生 SubLevel 的 UUID（如砂轮、避雷针）
     * @param ownerVehicleUUID 所属载具 SubLevel 的 UUID
     */
    public static void register(UUID subLevelUUID, UUID ownerVehicleUUID) {
        if (subLevelUUID == null || ownerVehicleUUID == null) return;
        OWNED_BY.put(subLevelUUID, ownerVehicleUUID);
        OWNER_TO_OWNED.computeIfAbsent(ownerVehicleUUID, k -> ConcurrentHashMap.newKeySet()).add(subLevelUUID);
        IACP.LOGGER.debug("[SubLevelOwnership] 注册: {} → 载具 {}",
                subLevelUUID.toString().substring(0, 8),
                ownerVehicleUUID.toString().substring(0, 8));
    }

    /**
     * 注销一个衍生 SubLevel 的归属。
     *
     * @param subLevelUUID 衍生 SubLevel 的 UUID
     */
    public static void unregister(UUID subLevelUUID) {
        if (subLevelUUID == null) return;
        UUID owner = OWNED_BY.remove(subLevelUUID);
        if (owner != null) {
            Set<UUID> owned = OWNER_TO_OWNED.get(owner);
            if (owned != null) {
                owned.remove(subLevelUUID);
                if (owned.isEmpty()) {
                    OWNER_TO_OWNED.remove(owner);
                }
            }
            IACP.LOGGER.debug("[SubLevelOwnership] 注销: {} (原属于载具 {})",
                    subLevelUUID.toString().substring(0, 8),
                    owner.toString().substring(0, 8));
        }
    }

    // ==============================
    //  查询
    // ==============================

    /**
     * 获取衍生 SubLevel 所属的载具 UUID。
     *
     * @param subLevelUUID 衍生 SubLevel 的 UUID
     * @return 所属载具的 UUID，未注册时返回 null
     */
    public static UUID getOwner(UUID subLevelUUID) {
        return OWNED_BY.get(subLevelUUID);
    }

    /**
     * 获取指定载具的所有衍生 SubLevel UUID（含自身）。
     * <p>
     * 返回的集合包含载具自身 UUID + 所有注册的衍生 SubLevel UUID，
     * 可直接用于射线追踪排除。
     *
     * @param vehicleUUID 载具 SubLevel 的 UUID
     * @return 包含载具自身和所有衍生 SubLevel 的不可变集合
     */
    public static Set<UUID> getAllOwnedByVehicle(UUID vehicleUUID) {
        if (vehicleUUID == null) return Collections.emptySet();
        Set<UUID> owned = OWNER_TO_OWNED.get(vehicleUUID);
        if (owned == null || owned.isEmpty()) {
            return Collections.singleton(vehicleUUID);
        }
        // 合并载具自身 + 所有衍生
        Set<UUID> result = new HashSet<>(owned.size() + 1);
        result.add(vehicleUUID);
        result.addAll(owned);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 清除所有归属记录（用于调试/重载）。
     */
    public static void clearAll() {
        OWNED_BY.clear();
        OWNER_TO_OWNED.clear();
        IACP.LOGGER.info("[SubLevelOwnership] 已清除所有归属记录");
    }
}
