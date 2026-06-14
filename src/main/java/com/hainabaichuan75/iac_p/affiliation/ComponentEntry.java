package com.hainabaichuan75.iac_p.affiliation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 部件注册表条目——记录 SubLevel 中一个功能方块的完整信息。
 * <p>
 * 每个功能方块（悬挂、炮塔底座、驾驶舱等）在 {@link ComponentRegistry} 中 对应一个 ComponentEntry，由方块实体在
 * {@code onLoad()} 时注册。
 * <p>
 * NBT 持久化支持世界重载后重建注册表。
 *
 * @param subLevelUUID 所在 SubLevel 的 UUID
 * @param blockPos 方块在 SubLevel 局部空间中的 BlockPos（即 plot chunk 坐标）
 * @param role 功能角色
 * @param blockEntity 方块实体的运行时引用（世界重载后可能暂时为 null，直到 BE 加载完成）
 */
public record ComponentEntry(
        UUID subLevelUUID,
        BlockPos blockPos,
        ComponentRole role,
        @Nullable
        BlockEntity blockEntity
        ) {

    // ==================================================================
    //  NBT 键名
    // ==================================================================
    private static final String TAG_SUBLEVEL_UUID = "CESubLevelUUID";
    private static final String TAG_BLOCK_POS = "CEBlockPos";
    private static final String TAG_ROLE = "CERole";
    // blockEntity 不持久化到 NBT——它是运行时引用，由 BE.onLoad() 重建

    /**
     * 将 ComponentEntry 的核心数据（不含 blockEntity 引用）写入 NBT。
     * <p>
     * 供 {@link ComponentRegistry} 在保存索引快照时使用。
     */
    public net.minecraft.nbt.CompoundTag writeToNbt() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putUUID(TAG_SUBLEVEL_UUID, subLevelUUID);
        tag.putLong(TAG_BLOCK_POS, blockPos.asLong());
        tag.putString(TAG_ROLE, role.name());
        return tag;
    }

    /**
     * 从 NBT 读取 ComponentEntry（不含 blockEntity 引用，需后续通过 BE 重建）。
     *
     * @return 解析后的条目，若数据不完整返回 null
     */
    @Nullable
    public static ComponentEntry readFromNbt(net.minecraft.nbt.CompoundTag tag) {
        if (!tag.hasUUID(TAG_SUBLEVEL_UUID) || !tag.contains(TAG_BLOCK_POS) || !tag.contains(TAG_ROLE)) {
            return null;
        }
        UUID subLevelUUID = tag.getUUID(TAG_SUBLEVEL_UUID);
        BlockPos blockPos = BlockPos.of(tag.getLong(TAG_BLOCK_POS));
        ComponentRole role = ComponentRole.fromString(tag.getString(TAG_ROLE));
        return new ComponentEntry(subLevelUUID, blockPos, role, null);
    }

    /**
     * 返回一个更新了 blockEntity 引用的新条目。
     */
    public ComponentEntry withBlockEntity(@Nullable BlockEntity be) {
        return new ComponentEntry(subLevelUUID, blockPos, role, be);
    }
}
