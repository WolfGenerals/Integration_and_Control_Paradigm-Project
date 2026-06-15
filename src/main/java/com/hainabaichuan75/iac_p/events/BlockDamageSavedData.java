package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块破坏进度持久化存储 —— 将部件损坏数据写入世界 NBT，确保 chunk 重载/服务器重启后裂纹和进度不丢失。
 * <p>
 * 数据结构：
 * <pre>
 * {
 *   "SubLevelUUID1": [
 *     {"x": 10, "y": 5, "z": 3, "d": 2.0},
 *     {"x": 10, "y": 5, "z": 4, "d": 4.0}
 *   ],
 *   "SubLevelUUID2": [...]
 * }
 * </pre>
 * <p>
 * 仅在 {@link ServerLevel} 中使用，客户端不做持久化。 数据通过 {@link #markDirty()} 标记脏，由
 * Minecraft 的自动保存机制（约每 2 秒）写入磁盘。
 */
public class BlockDamageSavedData extends SavedData {

    private static final String DATA_NAME = IACP.MODID + "_block_damage";
    private static final String TAG_SUBLEVEL_LIST = "sublevels";

    // SubLevel UUID → { BlockPos → accumulatedDamage }
    private final Map<UUID, Map<BlockPos, Float>> damageMap = new ConcurrentHashMap<>();

    // ==================================================================
    //  单例访问
    // ==================================================================
    /**
     * 获取指定世界的破坏进度数据。
     *
     * @param level 服务端世界
     * @return BlockDamageSavedData 实例，不存在时创建新的
     */
    public static BlockDamageSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(BlockDamageSavedData::new, BlockDamageSavedData::load, null),
                DATA_NAME
        );
    }

    // ==================================================================
    //  序列化
    // ==================================================================
    /**
     * 从 NBT 加载。
     */
    public static BlockDamageSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BlockDamageSavedData data = new BlockDamageSavedData();
        if (tag.contains(TAG_SUBLEVEL_LIST, Tag.TAG_LIST)) {
            ListTag subLevelList = tag.getList(TAG_SUBLEVEL_LIST, Tag.TAG_COMPOUND);
            for (int i = 0; i < subLevelList.size(); i++) {
                CompoundTag entry = subLevelList.getCompound(i);
                UUID subUUID = entry.getUUID("uuid");
                ListTag blockList = entry.getList("blocks", Tag.TAG_COMPOUND);
                Map<BlockPos, Float> blockMap = new HashMap<>();
                for (int j = 0; j < blockList.size(); j++) {
                    CompoundTag blockEntry = blockList.getCompound(j);
                    BlockPos pos = new BlockPos(
                            blockEntry.getInt("x"),
                            blockEntry.getInt("y"),
                            blockEntry.getInt("z")
                    );
                    float damage = blockEntry.getFloat("d");
                    if (damage > 0) {
                        blockMap.put(pos, damage);
                    }
                }
                if (!blockMap.isEmpty()) {
                    data.damageMap.put(subUUID, blockMap);
                }
            }
        }
        IACP.LOGGER.info("[BlockDamage] 从 NBT 加载 {} 个 SubLevel 的破坏进度", data.damageMap.size());
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag subLevelList = new ListTag();
        for (var entry : damageMap.entrySet()) {
            UUID subUUID = entry.getKey();
            Map<BlockPos, Float> blockMap = entry.getValue();
            if (blockMap.isEmpty()) {
                continue;
            }

            CompoundTag subEntry = new CompoundTag();
            subEntry.putUUID("uuid", subUUID);
            ListTag blockList = new ListTag();
            for (var blockEntry : blockMap.entrySet()) {
                if (blockEntry.getValue() <= 0) {
                    continue;
                }
                CompoundTag be = new CompoundTag();
                be.putInt("x", blockEntry.getKey().getX());
                be.putInt("y", blockEntry.getKey().getY());
                be.putInt("z", blockEntry.getKey().getZ());
                be.putFloat("d", blockEntry.getValue());
                blockList.add(be);
            }
            if (!blockList.isEmpty()) {
                subEntry.put("blocks", blockList);
                subLevelList.add(subEntry);
            }
        }
        tag.put(TAG_SUBLEVEL_LIST, subLevelList);
        return tag;
    }

    // ==================================================================
    //  数据访问
    // ==================================================================
    /**
     * 获取指定 SubLevel 中某个方块的累计伤害。
     */
    public float getDamage(UUID subUUID, BlockPos pos) {
        Map<BlockPos, Float> blockMap = damageMap.get(subUUID);
        return blockMap != null ? blockMap.getOrDefault(pos, 0f) : 0f;
    }

    /**
     * 设置指定 SubLevel 中某个方块的累计伤害。
     * <p>
     * 自动标记 {@link #setDirty()}。
     *
     * @return 写入前的旧值
     */
    public float setDamage(UUID subUUID, BlockPos pos, float damage) {
        Map<BlockPos, Float> blockMap = damageMap.computeIfAbsent(subUUID, k -> new ConcurrentHashMap<>());
        Float old = blockMap.put(pos.immutable(), damage);
        setDirty();
        return old != null ? old : 0f;
    }

    /**
     * 移除指定 SubLevel 中某个方块的记录。
     */
    public void removeDamage(UUID subUUID, BlockPos pos) {
        Map<BlockPos, Float> blockMap = damageMap.get(subUUID);
        if (blockMap != null) {
            blockMap.remove(pos);
            if (blockMap.isEmpty()) {
                damageMap.remove(subUUID);
            }
            setDirty();
        }
    }

    /**
     * 移除指定 SubLevel 的所有记录。
     */
    public void removeSubLevel(UUID subUUID) {
        if (damageMap.remove(subUUID) != null) {
            setDirty();
        }
    }

    /**
     * 获取指定 SubLevel 的完整伤害 Map 的快照。
     */
    @Nullable
    public Map<BlockPos, Float> getSubLevelDamage(UUID subUUID) {
        Map<BlockPos, Float> blockMap = damageMap.get(subUUID);
        return blockMap != null ? new HashMap<>(blockMap) : null;
    }

    /**
     * 获取所有 SubLevel 的完整数据（用于重新同步裂纹）。
     */
    public Map<UUID, Map<BlockPos, Float>> getAllData() {
        return damageMap;
    }

    /**
     * 清空所有数据。
     */
    public void clearAll() {
        damageMap.clear();
        setDirty();
    }
}
