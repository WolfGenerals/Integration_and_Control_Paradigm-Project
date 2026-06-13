package com.hainabaichuan75.iac_p.events;

import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.WeakHashMap;

/** … [same javadoc] … */
public class SubLevelProjectileHandler {

    private static final float PROJECTILE_DAMAGE = 1.0f;
    private static final WeakHashMap<Projectile, Vec3> LAST_POS = new WeakHashMap<>();

    @SubscribeEvent
    public static void onProjectileTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        Level level = projectile.level();
        if (level.isClientSide()) return;

        Vec3 currentPos = projectile.position();

        // 用 SubLevelContainer 遍历所有 SubLevel，AABB 检测弹射物是否进入
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        SubLevel enteredSubLevel = null;
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            var bb = sl.boundingBox();
            if (bb == null) continue;
            // 膨胀 0.5 格以处理边界
            if (currentPos.x >= bb.minX() - 0.5 && currentPos.x <= bb.maxX() + 0.5 &&
                currentPos.y >= bb.minY() - 0.5 && currentPos.y <= bb.maxY() + 0.5 &&
                currentPos.z >= bb.minZ() - 0.5 && currentPos.z <= bb.maxZ() + 0.5) {
                enteredSubLevel = sl;
                break;
            }
        }
        if (enteredSubLevel == null) return;

        // 检查上 tick 是否已在同一 SubLevel（防止重复触发）
        Vec3 lastPos = LAST_POS.get(projectile);
        if (lastPos != null) {
            // 简单检查：上次也在同一 SubLevel 内？
            boolean wasInside = false;
            var bb = enteredSubLevel.boundingBox();
            if (bb != null &&
                lastPos.x >= bb.minX() - 0.5 && lastPos.x <= bb.maxX() + 0.5 &&
                lastPos.y >= bb.minY() - 0.5 && lastPos.y <= bb.maxY() + 0.5 &&
                lastPos.z >= bb.minZ() - 0.5 && lastPos.z <= bb.maxZ() + 0.5) {
                wasInside = true;
            }
            if (wasInside) {
                LAST_POS.put(projectile, currentPos);
                return;
            }
        }

        LAST_POS.put(projectile, currentPos);

        IACP.LOGGER.debug("[Projectile] 弹射物进入 SubLevel {} 于 {}", enteredSubLevel.getUniqueId(),
                BlockPos.containing(currentPos));

        boolean destroyed = PartDamageCache.damageBlock(level, currentPos, PROJECTILE_DAMAGE);
        if (destroyed || projectile.isAlive()) {
            projectile.discard();
        }
    }
}
