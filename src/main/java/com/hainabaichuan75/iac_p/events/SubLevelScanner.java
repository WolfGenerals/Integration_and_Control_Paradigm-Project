package com.hainabaichuan75.iac_p.events;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SubLevel chunk 遍历工具类。
 * <p>
 * 消除各模块中反复出现的三重嵌套循环（chunk → 本地坐标 → 世界坐标），
 * 提供统一的访问接口。在 {@code forEachBlock} 的回调中直接获取世界坐标、
 * 方块状态和方块实体，避免每次使用时重复编写 boilerplate 代码。
 * <p>
 * 使用示例：
 * <pre>{@code
 * SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
 *     if (state.getBlock() instanceof SuspensionTestBlock) {
 *         // ...
 *     }
 * });
 * }</pre>
 */
public final class SubLevelScanner {

    @FunctionalInterface
    public interface BlockVisitor {
        /**
         * 访问 SubLevel 中的一个方块。
         *
         * @param worldPos 方块在世界空间中的 BlockPos
         * @param state    方块的世界 BlockState
         * @param be       方块的 BlockEntity（可能为 null）
         */
        void accept(BlockPos worldPos, BlockState state, @Nullable BlockEntity be);
    }

    /**
     * 遍历 SubLevel 内所有已加载 chunk 中的所有方块。
     * <p>
     * 仅遍历 {@code plot.getLoadedChunks()} 中已加载的 chunk，
     * 未加载区域不会被访问。
     *
     * @param subLevel 要遍历的 SubLevel
     * @param level    主世界 Level 实例
     * @param visitor  每方块回调，接收 (worldPos, state, be)
     */
    public static void forEachBlock(SubLevel subLevel, Level level, BlockVisitor visitor) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic localBounds = chunk.getBoundingBox();
            if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

            int chunkMinX = chunk.getPos().getMinBlockX();
            int chunkMinZ = chunk.getPos().getMinBlockZ();

            for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                    for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                        BlockPos worldPos = new BlockPos(
                                x + chunkMinX, y, z + chunkMinZ
                        );
                        BlockState state = level.getBlockState(worldPos);
                        BlockEntity be = level.getBlockEntity(worldPos);
                        visitor.accept(worldPos, state, be);
                    }
                }
            }
        }
    }

    /**
     * 简化版本：仅遍历方块状态，不获取 BlockEntity（略高效）。
     *
     * @param subLevel 要遍历的 SubLevel
     * @param level    主世界 Level 实例
     * @param visitor  每方块回调，接收 (worldPos, state)
     */
    public static void forEachBlockState(SubLevel subLevel, Level level,
                                          BiConsumer<BlockPos, BlockState> visitor) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;

        for (PlotChunkHolder chunk : plot.getLoadedChunks()) {
            BoundingBox3ic localBounds = chunk.getBoundingBox();
            if (localBounds == null || localBounds == BoundingBox3i.EMPTY) continue;

            int chunkMinX = chunk.getPos().getMinBlockX();
            int chunkMinZ = chunk.getPos().getMinBlockZ();

            for (int x = localBounds.minX(); x <= localBounds.maxX(); x++) {
                for (int y = localBounds.minY(); y <= localBounds.maxY(); y++) {
                    for (int z = localBounds.minZ(); z <= localBounds.maxZ(); z++) {
                        BlockPos worldPos = new BlockPos(
                                x + chunkMinX, y, z + chunkMinZ
                        );
                        visitor.accept(worldPos, level.getBlockState(worldPos));
                    }
                }
            }
        }
    }

    private SubLevelScanner() {}
}
