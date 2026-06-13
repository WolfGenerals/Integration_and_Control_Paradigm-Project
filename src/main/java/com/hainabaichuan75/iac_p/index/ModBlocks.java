package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitUpperBlock;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlock;
import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlock;
import com.hainabaichuan75.iac_p.content.blocks.seat.SeatBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.turret.TurretBaseBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(IACP.MODID);

    // === 旧核心（保留作为模板） ===
    public static final DeferredBlock<SeatBlock> SEAT = BLOCKS.registerBlock("seat",
            SeatBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(0.5f)
                    .noOcclusion());

    // === 通用驾驶舱（多方块结构，下格 + 上格） ===
    /**
     * 驾驶舱下半部分（炼药锅形状）
     */
    public static final DeferredBlock<CockpitBlock> COCKPIT = BLOCKS.registerBlock("cockpit",
            CockpitBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.0f, 3.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion());

    /**
     * 驾驶舱上半部分（脚手架形状），无对应物品，由 COCKPIT 放置时自动生成
     */
    public static final DeferredBlock<CockpitUpperBlock> COCKPIT_UPPER = BLOCKS.registerBlock("cockpit_upper",
            CockpitUpperBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.0f, 3.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion());

    // === 炮塔底座方块 ===
    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE = BLOCKS.registerBlock("turret_base",
            TurretBaseBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(0.5f)
                    .sound(SoundType.WOOL)
                    .noOcclusion()
                    .isRedstoneConductor((s, l, p) -> false));

    // === 悬挂测试方块 ===
    public static final DeferredBlock<SuspensionTestBlock> SUSPENSION_TEST = BLOCKS.registerBlock("suspension_test",
            SuspensionTestBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.0f, 3.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .isRedstoneConductor((s, l, p) -> false));

    // === 调试小齿轮 ===
    public static final DeferredBlock<DebugGearBlock> DEBUG_GEAR = BLOCKS.registerBlock("debug_gear",
            DebugGearBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(1.5f, 6.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion());

    // === 调试 SwivelBearing 监视方块 ===
    public static final DeferredBlock<DebugSwivelBearingBlock> DEBUG_SWIVEL_BEARING = BLOCKS.registerBlock("debug_swivel_bearing",
            DebugSwivelBearingBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(1.5f, 6.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion());
}
