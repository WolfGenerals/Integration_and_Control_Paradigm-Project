package com.hainabaichuan75.iac_p.index;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitUpperBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear0Block;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear1Block;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear2Block;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear3Block;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlock;
import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlock;
import com.hainabaichuan75.iac_p.content.blocks.seat.SeatBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.shotgun.ShotgunBaseBlock;
import com.hainabaichuan75.iac_p.content.blocks.machine_gun.MachineGunBaseBlock;
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

    // === 轻型线性座舱（2×2 多方块结构） ===

    /**
     * 轻型座舱种子方块（驾驶座区域），放置触发整个多方块结构
     */
    public static final DeferredBlock<CockpitLightLinear0Block> COCKPIT_LIGHT_LINEAR_0 =
            BLOCKS.registerBlock("cockpit_light_linear_0",
                    CockpitLightLinear0Block::new,
                    BlockBehaviour.Properties.of()
                            .strength(3.0f, 3.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion());

    /**
     * 轻型座舱水平延伸（副驾区），无对应物品，由 _0 放置时自动生成
     */
    public static final DeferredBlock<CockpitLightLinear1Block> COCKPIT_LIGHT_LINEAR_1 =
            BLOCKS.registerBlock("cockpit_light_linear_1",
                    CockpitLightLinear1Block::new,
                    BlockBehaviour.Properties.of()
                            .strength(3.0f, 3.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion());

    /**
     * 轻型座舱驾驶座上方防滚架，无对应物品，由 _0 放置时自动生成
     */
    public static final DeferredBlock<CockpitLightLinear2Block> COCKPIT_LIGHT_LINEAR_2 =
            BLOCKS.registerBlock("cockpit_light_linear_2",
                    CockpitLightLinear2Block::new,
                    BlockBehaviour.Properties.of()
                            .strength(3.0f, 3.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion());

    /**
     * 轻型座舱副驾上方防滚架，无对应物品，由 _0 放置时自动生成
     */
    public static final DeferredBlock<CockpitLightLinear3Block> COCKPIT_LIGHT_LINEAR_3 =
            BLOCKS.registerBlock("cockpit_light_linear_3",
                    CockpitLightLinear3Block::new,
                    BlockBehaviour.Properties.of()
                            .strength(3.0f, 3.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion());

    // === 霰弹枪底座方块 ===
    public static final DeferredBlock<ShotgunBaseBlock> SHOTGUN_BASE = BLOCKS.registerBlock("shotgun_base",
            ShotgunBaseBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(0.5f)
                    .sound(SoundType.WOOL)
                    .noOcclusion()
                    .isRedstoneConductor((s, l, p) -> false));

    // === 机枪底座方块 ===
    public static final DeferredBlock<MachineGunBaseBlock> MACHINE_GUN_BASE = BLOCKS.registerBlock("machine_gun_base",
            MachineGunBaseBlock::new,
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
