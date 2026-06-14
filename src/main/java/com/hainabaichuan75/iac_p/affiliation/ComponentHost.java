package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import java.util.UUID;

/**
 * 功能部件宿主接口——标记一个 BlockEntity 是 SubLevel 内的功能部件。
 * <p>
 * 实现此接口的 BE 应在 {@code onLoad()} 中调用
 * {@link #registerComponent(BlockEntity, ComponentRole)}， 在
 * {@code setRemoved()} 中调用 {@link #unregisterComponent(BlockEntity)}。
 * <p>
 * 使用示例：
 * <pre>{@code
 * public class MyBlockEntity extends SmartBlockEntity implements ComponentHost {
 *     @Override
 *     public ComponentRole getComponentRole() {
 *         return ComponentRole.MODIFIER_ENGINE;
 *     }
 *
 *     @Override
 *     public void onLoad() {
 *         super.onLoad();
 *         ComponentHost.registerComponent(this, getComponentRole());
 *     }
 *
 *     @Override
 *     public void setRemoved() {
 *         ComponentHost.unregisterComponent(this);
 *         super.setRemoved();
 *     }
 * }
 * }</pre>
 */
public interface ComponentHost {

    /**
     * 返回此方块的功能角色。
     */
    ComponentRole getComponentRole();

    // ==================================================================
    //  静态工具方法
    // ==================================================================
    /**
     * 注册一个方块到 {@link ComponentRegistry}。
     * <p>
     * 仅在服务端执行。如果方块不在任何 SubLevel 中（主世界自由放置）， 则跳过注册。
     *
     * @param be 方块实体
     * @param role 功能角色
     */
    static void registerComponent(BlockEntity be, ComponentRole role) {
        if (be == null || role == null) {
            return;
        }
        Level level = be.getLevel();
        if (level == null) {
            return;
        }

        BlockPos pos = be.getBlockPos();
        SubLevel subLevel = Sable.HELPER.getContaining(be);
        if (subLevel == null) {
            // 主世界放置的方块不注册到 SubLevel 部件系统
            return;
        }

        UUID subUUID = subLevel.getUniqueId();
        ComponentEntry entry = new ComponentEntry(subUUID, pos, role, be);
        ComponentRegistry.register(entry);
        IACP.LOGGER.debug("[ComponentHost] 注册: {} @ {} role={}",
                pos, subUUID.toString().substring(0, 8), role);
    }

    /**
     * 从 {@link ComponentRegistry} 注销一个方块。
     * <p>
     * 安全：即使方块从未注册过也不会报错。
     *
     * @param be 方块实体
     */
    static void unregisterComponent(BlockEntity be) {
        if (be == null) {
            return;
        }
        ComponentRegistry.unregister(be.getBlockPos());
        IACP.LOGGER.debug("[ComponentHost] 注销: {}", be.getBlockPos());
    }

    /**
     * 从 NBT 重建时重新注册方块（带 BE 引用更新）。
     * <p>
     * 在 {@code read()} 中调用，更新已有注册条目的 BE 引用。
     */
    static void reregisterComponent(BlockEntity be, ComponentRole role) {
        if (be == null || role == null) {
            return;
        }
        Level level = be.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }

        SubLevel subLevel = Sable.HELPER.getContaining(be);
        if (subLevel == null) {
            return;
        }

        ComponentRegistry.updateBlockEntity(subLevel.getUniqueId(), be.getBlockPos(), be);
        IACP.LOGGER.debug("[ComponentHost] 重新注册: {} role={}", be.getBlockPos(), role);
    }
}
