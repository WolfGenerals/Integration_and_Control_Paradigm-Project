package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 物理结构的"身份证"——描述一个 SubLevel 在归属系统中的完整信息。
 * <p>
 * 每个 SubLevel 在 {@link AffiliationRegistry} 中注册时附带的标签， 也被持久化到 BlockEntity 的 NBT
 * 中。
 * <p>
 * <b>NBT 键名</b>：
 * <ul>
 * <li>{@code "AffiliationOwnerId"} — 归属主 ID（玩家或载具 UUID）</li>
 * <li>{@code "AffiliationVehicleId"} — 所属载具 UUID（衍生结构有此值）</li>
 * <li>{@code "AffiliationGroupId"} — 逻辑组 ID（共享耐久池/生命周期的组）</li>
 * <li>{@code "AffiliationRole"} — 角色名（{@link AffiliationRole#name()}）</li>
 * <li>{@code "AffiliationFaction"} — 阵营 ID（int）</li>
 * </ul>
 *
 * @param ownerId 归属主 ID：玩家 UUID（载具主体）或载具 UUID（衍生部件）
 * @param vehicleId 所属载具 SubLevel 的 UUID（载具主体此值为 null）
 * @param groupId 逻辑组 UUID（如炮塔组），共享耐久池的部件在同一组
 * @param role 角色枚举
 * @param faction 阵营 ID（未来扩展：0=中立, 1=红队, 2=蓝队...）
 */
public record AffiliationTag(
        @Nullable
        UUID ownerId,
        @Nullable
        UUID vehicleId,
        @Nullable
        UUID groupId,
        AffiliationRole role,
        int faction
        ) {

    /**
     * 默认阵营：中立
     */
    public static final int FACTION_NEUTRAL = 0;

    // ==================================================================
    //  工厂方法
    // ==================================================================
    /**
     * 创建一个载具主体标签（ownerId = 玩家 UUID，无 vehicleId）。
     */
    public static AffiliationTag vehicleBody(UUID playerId, int faction) {
        return new AffiliationTag(playerId, null, null, AffiliationRole.VEHICLE_BODY, faction);
    }

    /**
     * 创建一个炮塔部件标签。
     */
    public static AffiliationTag turretPart(UUID vehicleId, UUID groupId, AffiliationRole role, int faction) {
        return new AffiliationTag(vehicleId, vehicleId, groupId, role, faction);
    }

    // ==================================================================
    //  NBT 序列化
    // ==================================================================
    private static final String TAG_OWNER_ID = "AffiliationOwnerId";
    private static final String TAG_VEHICLE_ID = "AffiliationVehicleId";
    private static final String TAG_GROUP_ID = "AffiliationGroupId";
    private static final String TAG_ROLE = "AffiliationRole";
    private static final String TAG_FACTION = "AffiliationFaction";

    /**
     * 将本标签写入 NBT（供 BlockEntity.write() 调用）。
     */
    public void writeToNbt(CompoundTag tag) {
        if (ownerId != null) {
            tag.putUUID(TAG_OWNER_ID, ownerId);
        }
        if (vehicleId != null) {
            tag.putUUID(TAG_VEHICLE_ID, vehicleId);
        }
        if (groupId != null) {
            tag.putUUID(TAG_GROUP_ID, groupId);
        }
        tag.putString(TAG_ROLE, role.name());
        tag.putInt(TAG_FACTION, faction);
    }

    /**
     * 从 NBT 读取标签（供 BlockEntity.read() 调用）。
     *
     * @return 解析后的标签，若 NBT 中无任何归属数据则返回 null
     */
    @Nullable
    public static AffiliationTag readFromNbt(CompoundTag tag) {
        if (!tag.contains(TAG_ROLE)) {
            return null; // 无归属数据
        }
        UUID ownerId = tag.hasUUID(TAG_OWNER_ID) ? tag.getUUID(TAG_OWNER_ID) : null;
        UUID vehicleId = tag.hasUUID(TAG_VEHICLE_ID) ? tag.getUUID(TAG_VEHICLE_ID) : null;
        UUID groupId = tag.hasUUID(TAG_GROUP_ID) ? tag.getUUID(TAG_GROUP_ID) : null;
        AffiliationRole role = AffiliationRole.fromString(tag.getString(TAG_ROLE));
        int faction = tag.getInt(TAG_FACTION);
        return new AffiliationTag(ownerId, vehicleId, groupId, role, faction);
    }
}
