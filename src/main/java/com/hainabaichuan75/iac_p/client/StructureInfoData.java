package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.affiliation.ComponentRegistry;
import com.hainabaichuan75.iac_p.affiliation.ComponentRole;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear0Block;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlockEntity;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlockEntity;
import com.hainabaichuan75.iac_p.events.SubLevelScanner;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结构信息数据——SubLevel 内方块统计、武器系统概览与底座连接信息。
 * <p>
 * 由 {@link StructureInfoData#scan(SubLevel, Level)} 在客户端全量扫描生成，
 * 为 {@link com.hainabaichuan75.iac_p.client.screen.StructureInfoScreen} 提供数据。
 */
public record StructureInfoData(
        // ====== 方块统计 ======
        /** 方块注册名 → 数量（按数量降序排列） */
        List<BlockCountEntry> blockCounts,
        /** 方块种类总数 */
        int distinctBlockTypes,
        /** 非空气方块总数 */
        int totalBlocks,

        // ====== 武器系统 ======
        /** 炮塔底座信息列表 */
        List<TurretInfo> turrets,

        // ====== 驾驶舱信息 ======
        /** 是否找到驾驶舱 */
        boolean hasCockpit
) {

    /**
     * 方块数量条目（用于排序显示）
     */
    public record BlockCountEntry(String blockName, int count) {}

    /**
     * 炮塔底座信息
     */
    public record TurretInfo(
            BlockPos position,
            boolean isAssembled,
            UUID grindstoneSubLevelId,
            UUID lightningRodSubLevelId,
            UUID vehicleSubLevelId,
            String statusSummary
    ) {}

    /**
     * 扫描指定 SubLevel，收集结构信息。
     * <p>
     * <ol>
     *   <li>全量遍历 SubLevel 内所有已加载方块，按 Block 注册名统计数量</li>
     *   <li>通过 {@link ComponentRegistry} 查询 TURRET_BASE 部件</li>
     *   <li>为每个炮塔底座提取连接信息</li>
     * </ol>
     *
     * @param subLevel 目标 SubLevel（驾驶舱所在物理结构）
     * @param level    主世界 Level（客户端实例）
     * @return 结构信息数据，可用于渲染 {@code StructureInfoScreen}
     */
    public static StructureInfoData scan(SubLevel subLevel, Level level) {
        UUID subUUID = subLevel.getUniqueId();

        // ====== 1. 方块统计 ======
        Map<String, Integer> typeCountMap = new HashMap<>();
        int[] nonAirTotal = {0};
        boolean[] foundCockpit = {false};

        SubLevelScanner.forEachBlockState(subLevel, level, (worldPos, state) -> {
            if (state.isAir()) {
                return;
            }
            nonAirTotal[0]++;

            // 使用方块的注册名作为键
            Block block = state.getBlock();
            String registryName = block.builtInRegistryHolder().key().location().toString();
            // 友好名：优先显示注册名
            typeCountMap.merge(registryName, 1, Integer::sum);

            // 检测驾驶舱
            if (!foundCockpit[0] && (block instanceof CockpitBlock || block instanceof CockpitLightLinear0Block)) {
                foundCockpit[0] = true;
            }
        });

        // 按数量降序排列
        List<BlockCountEntry> sortedCounts = typeCountMap.entrySet().stream()
                .map(e -> new BlockCountEntry(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(BlockCountEntry::count).reversed())
                .toList();

        // ====== 2. 武器系统扫描（通过 ComponentRegistry） ======
        List<TurretInfo> turretInfos = new ArrayList<>();

        // 炮塔
        var turretEntries = ComponentRegistry.getComponents(subUUID, ComponentRole.TURRET_BASE);
        for (var entry : turretEntries) {
            BlockEntity be = entry.blockEntity();
            if (be instanceof TurretBaseBlockEntity turret) {
                addTurretInfo(turretInfos, entry.blockPos(), turret.isAssembled(),
                        turret.getGrindstoneSubLevelId(), turret.getLightningRodSubLevelId(),
                        turret.getVehicleSubLevelId());
            }
        }

        // 霰弹枪
        var shotgunEntries = ComponentRegistry.getComponents(subUUID, ComponentRole.SHOTGUN_BASE);
        for (var entry : shotgunEntries) {
            BlockEntity be = entry.blockEntity();
            if (be instanceof ShotgunBaseBlockEntity shotgun) {
                addTurretInfo(turretInfos, entry.blockPos(), shotgun.isAssembled(),
                        shotgun.getGrindstoneSubLevelId(), shotgun.getLightningRodSubLevelId(),
                        shotgun.getVehicleSubLevelId());
            }
        }

        return new StructureInfoData(
                sortedCounts,
                typeCountMap.size(),
                nonAirTotal[0],
                turretInfos,
                foundCockpit[0]
        );
    }

    /**
     * 添加单条武器底座信息到列表（炮塔/霰弹枪共用）。
     */
    private static void addTurretInfo(List<TurretInfo> list, BlockPos pos, boolean assembled,
            UUID gsId, UUID rodId, UUID vehId) {
        StringBuilder status = new StringBuilder();
        if (!assembled) {
            status.append("§c未装配");
        } else {
            status.append("§a已装配");
            List<String> parts = new ArrayList<>();
            if (gsId != null) parts.add("砂轮§a✔");
            if (rodId != null) parts.add("炮管§a✔");
            if (vehId != null) parts.add("车体§a✔");
            if (!parts.isEmpty()) {
                status.append(" | ");
                status.append(String.join(" ", parts));
            }
        }
        list.add(new TurretInfo(pos, assembled, gsId, rodId, vehId, status.toString()));
    }
}
