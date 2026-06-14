package com.hainabaichuan75.iac_p.affiliation;

import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * 归属状态变更事件——当 {@link AffiliationRegistry} 中的归属关系发生变化时抛出。
 * <p>
 * 其他模块可以通过监听此事件来响应归属变化，而无需直接耦合 {@link AffiliationRegistry}。
 * <p>
 * <b>注册方式</b>（在任意 {@code @EventBusSubscriber} 或
 * {@code NeoForge.EVENT_BUS.register()} 中）：
 * <pre>{@code
 * @SubscribeEvent
 * public static void onAffiliationChange(AffiliationChangeEvent event) {
 *     // 处理变更
 * }
 * }</pre>
 * <p>
 * <b>事件类型（ChangeType）</b>：
 * <ul>
 * <li>{@code REGISTER} — 新的 SubLevel 注册了归属标签</li>
 * <li>{@code UNREGISTER} — SubLevel 注销了归属标签</li>
 * <li>{@code UNREGISTER_GROUP} — 整个逻辑组被一次性注销</li>
 * <li>{@code PLAYER_BIND} — 玩家与载具绑定/解绑</li>
 * </ul>
 * <p>
 * 注意：此事件在 {@link AffiliationRegistry} 内部状态变更后抛出，为"事后通知"语义。
 * 监听器不应在此事件中再次修改同一个注册表索引，以免造成递归。
 */
public class AffiliationChangeEvent extends Event {

    /**
     * 变更类型枚举。
     */
    public enum ChangeType {
        /**
         * 新的 SubLevel 注册了归属标签
         */
        REGISTER,
        /**
         * SubLevel 注销了归属标签
         */
        UNREGISTER,
        /**
         * 整个逻辑组被一次性注销
         */
        UNREGISTER_GROUP,
        /**
         * 玩家与载具绑定/解绑
         */
        PLAYER_BIND
    }

    private final ChangeType changeType;
    private final UUID subLevelUUID;
    private final UUID groupId;
    private final UUID playerUUID;
    private final UUID vehicleUUID;
    private final AffiliationTag tag;

    // ==================================================================
    //  工厂方法
    // ==================================================================
    /**
     * 创建 SubLevel 注册/注销事件。
     */
    public static AffiliationChangeEvent ofSubLevel(ChangeType type, UUID subLevelUUID, @Nullable AffiliationTag tag) {
        return new AffiliationChangeEvent(type, subLevelUUID, null, null, null, tag);
    }

    /**
     * 创建整组注销事件。
     */
    public static AffiliationChangeEvent ofGroup(UUID groupId) {
        return new AffiliationChangeEvent(ChangeType.UNREGISTER_GROUP, null, groupId, null, null, null);
    }

    /**
     * 创建玩家-载具绑定/解绑事件。
     */
    public static AffiliationChangeEvent ofPlayerBind(UUID playerUUID, @Nullable UUID vehicleUUID) {
        return new AffiliationChangeEvent(ChangeType.PLAYER_BIND, null, null, playerUUID, vehicleUUID, null);
    }

    private AffiliationChangeEvent(ChangeType changeType, UUID subLevelUUID, UUID groupId,
            UUID playerUUID, UUID vehicleUUID, AffiliationTag tag) {
        this.changeType = changeType;
        this.subLevelUUID = subLevelUUID;
        this.groupId = groupId;
        this.playerUUID = playerUUID;
        this.vehicleUUID = vehicleUUID;
        this.tag = tag;
    }

    // ==================================================================
    //  Getter
    // ==================================================================
    /**
     * @return 变更类型
     */
    public ChangeType getChangeType() {
        return changeType;
    }

    /**
     * @return 受影响的 SubLevel UUID（整组事件时可能为 null）
     */
    @Nullable
    public UUID getSubLevelUUID() {
        return subLevelUUID;
    }

    /**
     * @return 受影响的组 UUID（仅整组注销事件非 null）
     */
    @Nullable
    public UUID getGroupId() {
        return groupId;
    }

    /**
     * @return 受影响的玩家 UUID（仅玩家绑定事件非 null）
     */
    @Nullable
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    /**
     * @return 玩家绑定的载具 UUID（仅玩家绑定事件非 null，解绑时可能为 null）
     */
    @Nullable
    public UUID getVehicleUUID() {
        return vehicleUUID;
    }

    /**
     * @return 注册/注销时的归属标签（仅 SubLevel 事件非 null）
     */
    @Nullable
    public AffiliationTag getTag() {
        return tag;
    }

    @Override
    public String toString() {
        return "AffiliationChangeEvent{"
                + "changeType=" + changeType
                + ", subLevelUUID=" + (subLevelUUID != null ? subLevelUUID.toString().substring(0, 8) : "null")
                + ", groupId=" + (groupId != null ? groupId.toString().substring(0, 8) : "null")
                + ", playerUUID=" + (playerUUID != null ? playerUUID.toString().substring(0, 8) : "null")
                + ", vehicleUUID=" + (vehicleUUID != null ? vehicleUUID.toString().substring(0, 8) : "null")
                + ", tag=" + tag
                + '}';
    }
}
