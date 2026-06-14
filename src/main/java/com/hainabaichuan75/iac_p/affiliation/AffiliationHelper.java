package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 归属系统工具方法。
 * <p>
 * 提供便捷方法将 SubLevel 及其 BlockEntity 的归属数据持久化到 NBT， 并支持世界重载后从 NBT 重建
 * {@link AffiliationRegistry} 索引。
 */
public final class AffiliationHelper {

    private AffiliationHelper() {
    }

    // ==================================================================
    //  NBT 键名（与 AffiliationTag 中一致）
    // ==================================================================
    public static final String TAG_OWNER_ID = "AffiliationOwnerId";
    public static final String TAG_VEHICLE_ID = "AffiliationVehicleId";
    public static final String TAG_GROUP_ID = "AffiliationGroupId";
    public static final String TAG_ROLE = "AffiliationRole";
    public static final String TAG_FACTION = "AffiliationFaction";

    // ==================================================================
    //  便捷注册
    // ==================================================================
    /**
     * 注册一个载具主体 SubLevel（如驾驶舱/悬挂方块所在的 SubLevel）。
     * <p>
     * 当玩家上车时调用，将玩家 UUID 与载具 SubLevel 绑定。
     */
    public static void registerVehicleBody(SubLevel vehicleSubLevel, UUID playerUUID, int faction) {
        if (vehicleSubLevel == null || playerUUID == null) {
            return;
        }
        UUID vehicleUUID = vehicleSubLevel.getUniqueId();
        AffiliationTag tag = AffiliationTag.vehicleBody(playerUUID, faction);
        AffiliationRegistry.register(vehicleUUID, tag);
        AffiliationRegistry.setPlayerVehicle(playerUUID, vehicleUUID);
        IACP.LOGGER.debug("[AffiliationHelper] 载具主体注册: vehicle={}, player={}",
                vehicleUUID.toString().substring(0, 8),
                playerUUID.toString().substring(0, 8));
    }

    /**
     * 注销载具主体（下车时调用）。
     */
    public static void unregisterVehicleBody(UUID vehicleUUID, UUID playerUUID) {
        if (vehicleUUID != null) {
            AffiliationRegistry.unregister(vehicleUUID);
        }
        if (playerUUID != null) {
            AffiliationRegistry.setPlayerVehicle(playerUUID, null);
        }
    }

    /**
     * 注册一个炮塔部件（底座/砂轮/避雷针），自动分配 role 和 groupId。
     * <p>
     * 由 {@code TurretBaseBlockEntity.assemble()} 在创建 SubLevel 后调用。
     *
     * @param subLevelUUID 部件 SubLevel UUID
     * @param vehicleUUID 所属载具 SubLevel UUID
     * @param groupId 炮塔组 UUID（共享耐久池）
     * @param role 该部件的角色
     * @param faction 阵营
     */
    public static void registerTurretPart(UUID subLevelUUID, UUID vehicleUUID,
            UUID groupId, AffiliationRole role, int faction) {
        if (subLevelUUID == null || vehicleUUID == null || groupId == null) {
            return;
        }
        AffiliationTag tag = AffiliationTag.turretPart(vehicleUUID, groupId, role, faction);
        AffiliationRegistry.register(subLevelUUID, tag);
        IACP.LOGGER.debug("[AffiliationHelper] 炮塔部件注册: {} role={}, group={}, vehicle={}",
                subLevelUUID.toString().substring(0, 8),
                role, groupId.toString().substring(0, 8),
                vehicleUUID.toString().substring(0, 8));
    }
}
