package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRegistry;
import com.hainabaichuan75.iac_p.affiliation.AffiliationRole;
import com.hainabaichuan75.iac_p.affiliation.AffiliationTag;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * SubLevel 归属管理系统。
 * <p>
 * <b>已迁移到 {@link AffiliationRegistry}。</b>
 * 此类现在作为委派层，保持对旧调用者的向后兼容。
 * <p>
 * 跟踪每个"衍生 SubLevel"（如炮塔的砂轮、避雷针）从属于哪个"载具 SubLevel"。 用于在武器射线追踪时排除所有归属于同一载具的
 * SubLevel，防止自伤。
 * <p>
 * <b>注册时机</b>：在 {@code TurretBaseBlockEntity.assemble()} 中创建砂轮/避雷针 SubLevel
 * 后立即注册。
 * <b>注销时机</b>：在 {@code TurretBaseBlockEntity.disassemble()} 中移除 SubLevel 前注销。
 * <b>使用场景</b>：{@link SableBlockHelper#rayTraceSubLevels} 中查询并排除。
 * <p>
 * 新代码请直接使用 {@link AffiliationRegistry}。
 */
public final class SubLevelOwnership {

    private SubLevelOwnership() {
    }

    // ==============================
    //  注册 / 注销（委派到 AffiliationRegistry）
    // ==============================
    /**
     * 注册一个衍生 SubLevel 归属于某个载具 SubLevel。
     * <p>
     * 旧 API：自动分配 Role.TURRET_YAW 或 TURRET_PITCH（通过判断调用者上下文）。 新代码请使用
     * {@link AffiliationRegistry#register(UUID, AffiliationTag)} 指定完整标签。
     *
     * @param subLevelUUID 衍生 SubLevel 的 UUID（如砂轮、避雷针）
     * @param ownerVehicleUUID 所属载具 SubLevel 的 UUID
     */
    public static void register(UUID subLevelUUID, UUID ownerVehicleUUID) {
        if (subLevelUUID == null || ownerVehicleUUID == null) {
            return;
        }
        // 旧 API 无法确定确切角色，使用 UNKNOWN 由后续精确注册覆盖
        AffiliationTag tag = new AffiliationTag(
                ownerVehicleUUID, ownerVehicleUUID, null,
                AffiliationRole.UNKNOWN, AffiliationTag.FACTION_NEUTRAL);
        AffiliationRegistry.register(subLevelUUID, tag);
        IACP.LOGGER.debug("[SubLevelOwnership→Registry] 注册: {} → 载具 {}",
                subLevelUUID.toString().substring(0, 8),
                ownerVehicleUUID.toString().substring(0, 8));
    }

    /**
     * 注销一个衍生 SubLevel 的归属。
     */
    public static void unregister(UUID subLevelUUID) {
        AffiliationRegistry.unregister(subLevelUUID);
    }

    // ==============================
    //  查询（委派到 AffiliationRegistry）
    // ==============================
    /**
     * 获取衍生 SubLevel 所属的载具 UUID。
     */
    public static UUID getOwner(UUID subLevelUUID) {
        return AffiliationRegistry.getVehicle(subLevelUUID);
    }

    /**
     * 获取指定载具的所有衍生 SubLevel UUID（含自身）。
     */
    public static Set<UUID> getAllOwnedByVehicle(UUID vehicleUUID) {
        return AffiliationRegistry.getOwnAffiliatedSet(vehicleUUID);
    }

    /**
     * 清除所有归属记录（委派）。
     */
    public static void clearAll() {
        AffiliationRegistry.clearAll();
        IACP.LOGGER.info("[SubLevelOwnership→Registry] 已清除所有归属记录");
    }
}
