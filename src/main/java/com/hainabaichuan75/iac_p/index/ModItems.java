package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IACP.MODID);

    // === 旧核心 ===
    public static final DeferredItem<BlockItem> SEAT = ITEMS.registerSimpleBlockItem(ModBlocks.SEAT);

    // === 通用驾驶舱（仅下格有物品，上格无物品） ===
    /**
     * 驾驶舱物品：放置后生成下格 + 上格的双方块结构
     */
    public static final DeferredItem<BlockItem> COCKPIT = ITEMS.registerSimpleBlockItem(ModBlocks.COCKPIT);

    // === 轻型线性座舱（仅种子方块有物品，结构子块无物品） ===
    public static final DeferredItem<BlockItem> COCKPIT_LIGHT_LINEAR_0 =
            ITEMS.registerSimpleBlockItem(ModBlocks.COCKPIT_LIGHT_LINEAR_0);

    // === 霰弹枪底座方块 ===
    public static final DeferredItem<BlockItem> SHOTGUN_BASE = ITEMS.registerSimpleBlockItem(ModBlocks.SHOTGUN_BASE);

    // === 机枪底座方块 ===
    public static final DeferredItem<BlockItem> MACHINE_GUN_BASE = ITEMS.registerSimpleBlockItem(ModBlocks.MACHINE_GUN_BASE);

    // === 悬挂测试方块 ===
    public static final DeferredItem<BlockItem> SUSPENSION_TEST = ITEMS.registerSimpleBlockItem(ModBlocks.SUSPENSION_TEST);

    // === 调试小齿轮 ===
    public static final DeferredItem<BlockItem> DEBUG_GEAR = ITEMS.registerSimpleBlockItem(ModBlocks.DEBUG_GEAR);

    // === 调试 SwivelBearing 监视方块 ===
    public static final DeferredItem<BlockItem> DEBUG_SWIVEL_BEARING = ITEMS.registerSimpleBlockItem(ModBlocks.DEBUG_SWIVEL_BEARING);
}
