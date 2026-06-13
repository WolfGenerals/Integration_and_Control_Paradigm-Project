package com.hainabaichuan75.iac_p.content.blocks.debug_swivel;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.index.ModDebugSwivelBearingBlockEntityTypes;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * 调试 SwivelBearing 监视方块 BE。
 * <p>
 * 完全继承 {@link SwivelBearingBlockEntity} 的所有行为（伺服控制、应力、组装等）， 调试开启时每 tick
 * 打印该方块<strong>自身</strong>的 SwivelBearing 运行数据。
 * <p>
 * 打印数据包括（每 tick 一行）：
 * <ul>
 * <li>tick 号</li>
 * <li>目标角度（{@link #getTargetAngleDegrees()}）</li>
 * <li>当前实际角度（从 SubLevel pose Z 轴正向推算）</li>
 * <li>角度误差（目标 − 实际）</li>
 * <li>是否已组装</li>
 * <li>SubLevel UUID</li>
 * </ul>
 */
public class DebugSwivelBearingBlockEntity extends SwivelBearingBlockEntity {

    private boolean debugEnabled = false;

    public DebugSwivelBearingBlockEntity(BlockPos pos, BlockState state) {
        super(ModDebugSwivelBearingBlockEntityTypes.DEBUG_SWIVEL_BEARING.get(), pos, state);
    }

    public void toggleDebug() {
        this.debugEnabled = !this.debugEnabled;
        if (this.debugEnabled) {
            IACP.LOGGER.info("[DebugSwivel] 🔧 调试输出已开启 @ {}", this.worldPosition);
            printHeader();
        } else {
            IACP.LOGGER.info("[DebugSwivel] 🔧 调试输出已关闭 @ {}", this.worldPosition);
        }
    }

    public boolean isDebugEnabled() {
        return this.debugEnabled;
    }

    @Override
    public void tick() {
        // 先执行 SwivelBearingBlockEntity 的完整 tick（组装、伺服控制、应力）
        super.tick();

        if (!this.debugEnabled) {
            return;
        }
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        printSelfState();
    }

    private void printHeader() {
        IACP.LOGGER.info("[DebugSwivel] ========== DebugSwivelBearing 自检数据 ==========");
        IACP.LOGGER.info("[DebugSwivel] tick | targetAngle | actualAngle | angleErr | assembled | subLevelUUID");
        IACP.LOGGER.info("[DebugSwivel] =================================================");
    }

    private void printSelfState() {
        long tick = level.getGameTime();

        double targetDeg = getTargetAngleDegrees();
        boolean assembled = isAssembled();

        // 读取自身 SubLevel 的实际 pose
        double actualDeg = Double.NaN;
        var slId = getSubLevelID();
        if (slId != null && level instanceof ServerLevel serverLevel) {
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(serverLevel);
            if (container != null) {
                var sl = container.getSubLevel(slId);
                if (sl instanceof ServerSubLevel ss && !ss.isRemoved()) {
                    Pose3dc pose = ss.logicalPose();
                    if (pose != null) {
                        Vector3d fwd = new Vector3d(0, 0, 1);
                        fwd.rotate(pose.orientation());
                        actualDeg = Math.toDegrees(Math.atan2(fwd.x, fwd.z));
                    }
                }
            }
        }

        double err = targetDeg - actualDeg;
        String uuidShort = slId != null ? slId.toString().substring(0, 8) : "null";

        IACP.LOGGER.info("[DebugSwivel] {} | t={} | a={} | e={} | asm={} | uuid={}",
                tick,
                fmt(targetDeg, 1),
                fmt(actualDeg, 1),
                fmt(err, 1),
                assembled ? "Y" : "N",
                uuidShort);
    }

    private static String fmt(double val, int decimals) {
        if (Double.isNaN(val)) {
            return "NaN";
        }
        return String.format("%+." + decimals + "f", val);
    }
}
