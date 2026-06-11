package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 骑乘状态玩家保护处理器。
 * <p>
 * 当玩家处于载具骑乘状态时：
 * <ul>
 *   <li><b>碰撞</b>：配合 {@code player.noPhysics = true} 和零体积碰撞箱，完全免疫物理碰撞</li>
 *   <li><b>仇恨</b>：阻止任何生物将骑乘玩家设为目标（索敌、仇恨均不触发）</li>
 *   <li><b>伤害</b>：除绕过无敌的伤害类型（{@code /kill}、{@code /damage} 指令）外，免疫一切伤害</li>
 * </ul>
 * <p>
 * 注册方式：通过 {@code NeoForge.EVENT_BUS.register(MountedProtectionHandler.class)}
 * 注册到游戏总线。
 */
public class MountedProtectionHandler {

    // ====== 仇恨/索敌保护 ======

    /**
     * 阻止生物将骑乘中的玩家设为目标。
     * <p>
     * 覆盖所有索敌场景：
     * <ul>
     *   <li>主动攻击型生物（僵尸、骷髅、蜘蛛等）的 AI 目标选择</li>
     *   <li>中立生物（末影人、狼、僵尸猪灵等）的被激怒目标</li>
     *   <li>Boss 生物的目标切换</li>
     *   <li>铁傀儡的敌对目标</li>
     * </ul>
     * <p>
     * 无论原因为何（玩家攻击、距离最近、仇恨值最高），
     * 只要目标是骑乘玩家，一律取消目标设定。
     */
    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        // 检查新目标是否为骑乘中的玩家
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player
                && PlayerMountTracker.isMounted(player)) {
            // 取消事件：保持原有目标不变
            event.setCanceled(true);
        }
    }

    // ====== 伤害免疫 ======

    /**
     * 骑乘时免疫除指令级外的一切伤害。
     * <p>
     * 此事件在 {@code LivingEntity.hurt()} 中 invulnerability 检查之后、伤害减免之前触发，
     * 可实现 {@code ICancellableEvent}。
     * <p>
     * 规则：
     * <ul>
     *   <li>如果伤害源 {@code bypasses_invulnerability}（{@code /kill}、{@code /damage} 指令），允许通过</li>
     *   <li>其他所有伤害（生物攻击、坠落、爆炸、弹射物、火焰、魔法等）全部取消</li>
     * </ul>
     * <p>
     * 此处的检查作为第二道防线。第一道防线是 {@code player.setInvulnerable(true)}。
     */
    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PlayerMountTracker.isMounted(player)) return;

        // 允许绕过无敌的伤害通过（/kill、/damage 指令）
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;

        // 取消一切非指令伤害
        event.setCanceled(true);
    }
}
