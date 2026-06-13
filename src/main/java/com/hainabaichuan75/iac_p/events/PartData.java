package com.hainabaichuan75.iac_p.events;

/**
 * 已废弃 — 部件耐久数据已内联到 {@link PartDamageCache} 中，
 * 用 {@code Map<BlockPos, Float>} 直接存储累计伤害。
 * <p>
 * 此类保留为空壳以避免 IDE 报错，将在后续清理中移除。
 */
@Deprecated
public record PartData(float maxHP, float currentHP) {
    @Deprecated public PartData takeDamage(float amount) { return this; }
    @Deprecated public boolean isDestroyed() { return false; }
    @Deprecated public float damageRatio() { return 0; }
    @Deprecated public static float getDefaultMaxHP(float destroySpeed) { return 0; }
}
