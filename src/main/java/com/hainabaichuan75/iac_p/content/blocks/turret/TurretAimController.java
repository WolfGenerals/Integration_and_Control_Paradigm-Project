package com.hainabaichuan75.iac_p.content.blocks.turret;

import com.hainabaichuan75.iac_p.Config;
import com.hainabaichuan75.iac_p.IACP;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 炮塔瞄准控制器 —— 比例增量驱动 + 位置模式保持。
 * <p>
 * 核心思想：SwivelBearing 模式 —— BE 每 tick 用 position-mode setMotor 保持目标角度；
 * AimController 按比例增量调节目标角度，误差大调得多、误差小调得少、到位不调。
 * <p>
 * 控制流程（每 server tick）：
 *   └─ 计算到目标的水平偏角（yawErr）和俯仰角（pitchErr）
 *   ├─ |err| < DEAD → 清除目标（BE 自然保持当前位置）
 *   └─ 否则 → 比例增量: deltaDeg = sign(err) × clamp(|err|×GAIN, MAX_STEP)
 *               → BE.advanceTargetYaw(deltaDeg)
 *                  → BE.updateYawServo() 位置模式 setMotor 自动跟踪
 */
public class TurretAimController {

    private static final Map<UUID, AimTarget> TARGETS = new ConcurrentHashMap<>();
    private record AimTarget(double x, double y, double z) {}

    // ==================================================================
    //  方向机参数
    // ==================================================================
    /** 每 tick 最大角度增量（度），对应 ~60°/s */
    private static final double YAW_MAX_STEP_DEG = 3.0;
    /** 比例增益（度/弧度）：1 rad 误差 → 每 tick 调 ~57° → 限幅到 MAX_STEP */
    private static final double YAW_GAIN = 80.0;
    /** 死区：误差 < 0.5° 认为到位 */
    private static final double YAW_DEAD = Math.toRadians(0.5);

    // ==================================================================
    //  高低机参数
    // ==================================================================
    /** 每 tick 最大俯仰增量（度），对应 ~40°/s */
    private static final double PITCH_MAX_STEP_DEG = 2.0;
    private static final double PITCH_GAIN = 60.0;
    private static final double PITCH_DEAD = Math.toRadians(0.5);
    private static final double PITCH_CLAMP = Math.toRadians(60.0);

    // ==================================================================

    public static void setTarget(UUID gs, double x, double y, double z) {
        if (gs != null) TARGETS.put(gs, new AimTarget(x, y, z));
        IACP.LOGGER.info("[TurretAim] setTarget gs={} target=({},{},{})",
                gs != null ? gs.toString().substring(0, 8) : "null",
                String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
    }

    public static void clearTarget(UUID gs) {
        if (gs != null) {
            TARGETS.remove(gs);
            IACP.LOGGER.info("[TurretAim] clearTarget gs={}", gs.toString().substring(0, 8));
        }
    }

    // ==================================================================
    //  每 tick 驱动
    // ==================================================================

    public static void tick(ServerLevel level) {
        ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
        if (container == null) return;
        if (TARGETS.isEmpty()) return;

        Iterator<Map.Entry<UUID, AimTarget>> it = TARGETS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            UUID uid = e.getKey();
            AimTarget t = e.getValue();
            SubLevel sl = container.getSubLevel(uid);
            if (!(sl instanceof ServerSubLevel gs) || gs.isRemoved()) { it.remove(); continue; }
            try { drive(gs, t); }
            catch (Exception ex) { IACP.LOGGER.warn("[TurretAim] exception", ex); }
        }
    }

    // ==================================================================
    //  核心驱动：比例增量
    // ==================================================================

    private static void drive(ServerSubLevel gs, AimTarget t) {
        Pose3dc pose = gs.logicalPose();
        if (pose == null) return;
        var p = pose.position();

        // ── 查找底座 ──
        var ownerPos = TurretBaseBlockEntity.findOwnerByGrindstoneUUID(gs.getUniqueId());
        if (ownerPos == null) return;
        var be = gs.getLevel().getBlockEntity(ownerPos);
        if (!(be instanceof TurretBaseBlockEntity tb)) return;

        // ================================================================
        //  方向机（Yaw）：车体局部坐标系稳定瞄准
        //
        //  核心改进（2026-06-10）：
        //  将目标方向变换到车体 SubLevel 的局部坐标系中计算偏航角，
        //  使瞄准不受车体俯仰/侧倾/翻转的影响。
        //
        //  ［数学原理］
        //  RotaryConstraint.setMotor 设置的是"两个刚体在约束法线上的相对角度"。
        //  当 normal1=normal2=(0,1,0) 时，这个相对角度 = 炮塔相对于车体局部 Y 轴的偏航。
        //  因此只要在车体局部 XZ 平面算出目标方向角，就能直接驱动。
        //
        //  步骤：
        //     dir_world = normalize(target - gsPos)   // 世界空间目标方向
        //     dir_local = q_veh⁻¹ · dir_world         // 变换到车体局部空间
        //     localYaw  = atan2(dir_local.x, dir_local.z)
        //     setMotor(-localYaw + offset)            // 取反：CCW+ → CW+
        // ================================================================

        double dx = t.x - p.x(), dz = t.z - p.z();
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 0.01) return;
        double tnx = dx / h, tnz = dz / h;

        // 计算世界空间 yaw 误差（仅用于死区判断和回退方案）
        Vector3d fwd = new Vector3d(0, 0, 1);
        fwd.rotate(pose.orientation());
        double fl = Math.sqrt(fwd.x * fwd.x + fwd.z * fwd.z);
        if (fl < 0.001) return;
        double fnx = fwd.x / fl, fnz = fwd.z / fl;
        double yawErr = Math.atan2(tnx * fnz - tnz * fnx, clamp(fnx * tnx + fnz * tnz, -1, 1));

        if (Math.abs(yawErr) < YAW_DEAD) {
            return; // ✅ 到位，BE 自然保持当前位置
        }

        double offsetRad = Math.toRadians(Config.TURRET_YAW_OFFSET.get());
        double targetYawRad;

        // ── 方案 A：车体局部坐标系稳定瞄准（优先） ──
        UUID vehicleId = tb.getVehicleSubLevelId();
        if (vehicleId != null) {
            ServerSubLevelContainer container = (ServerSubLevelContainer) SubLevelContainer.getContainer(gs.getLevel());
            if (container != null) {
                SubLevel sl = container.getSubLevel(vehicleId);
                if (sl instanceof ServerSubLevel vsl && !vsl.isRemoved()) {
                    Pose3dc vPose = vsl.logicalPose();
                    if (vPose != null) {
                        // 方向向量（世界空间）
                        Vector3d dirWorld = new Vector3d(dx / h, 0, dz / h);
                        // 车体姿态逆四元数
                        Quaterniond qVehInv = new Quaterniond(vPose.orientation()).invert();
                        // 变换到车体局部空间
                        Vector3d dirLocal = qVehInv.transform(dirWorld);
                        double ll = Math.sqrt(dirLocal.x * dirLocal.x + dirLocal.z * dirLocal.z);
                        if (ll > 0.001) {
                            double localYaw = Math.atan2(dirLocal.x / ll, dirLocal.z / ll);
                            targetYawRad = -localYaw + offsetRad; // 取反：CCW+ → CW+
                            tb.setTargetYawAbsolute(Math.toDegrees(targetYawRad));
                            // 继续到高低机
                            drivePitch(tb, p, t, pose);
                            return;
                        }
                    }
                }
            }
        }

        // ── 方案 B（回退）：主世界或无法获取车体 pose ──
        // 沿用旧的世界空间 yaw 计算
        double currentYawRad = Math.atan2(fnx, fnz);
        targetYawRad = -(currentYawRad + yawErr) + offsetRad;
        tb.setTargetYawAbsolute(Math.toDegrees(targetYawRad));

        // 继续到高低机
        drivePitch(tb, p, t, pose);
    }

    // ==================================================================
    //  高低机（Pitch）：与方向机无关，保持现有逻辑
    // ==================================================================

    private static void drivePitch(TurretBaseBlockEntity tb,
                                    Vector3dc gsPos, AimTarget t, Pose3dc gsPose) {
        double dx = t.x - gsPos.x(), dz = t.z - gsPos.z();
        double dy = t.y - gsPos.y();

        // 目标方向在砂轮局部空间
        Vector3d local = new Vector3d(dx, dy, dz);
        local.rotate(new Quaterniond(gsPose.orientation()).invert());
        double lh2 = Math.sqrt(local.x * local.x + local.z * local.z);
        if (lh2 < 0.01) return;

        // 绝对俯仰角度（弧度），正值 = 上仰，负值 = 下俯
        double targetPitchRad = clamp(Math.atan2(local.y, lh2), -PITCH_CLAMP, PITCH_CLAMP);

        // 死区判断：与当前目标角度比较
        double currentTargetDeg = tb.getTargetPitchAngle();
        double targetDeg = Math.toDegrees(targetPitchRad);
        if (Math.abs(targetDeg - currentTargetDeg) < Math.toDegrees(PITCH_DEAD)) {
            return;
        }

        // 位置模式 PD 伺服：直接设绝对角度
        tb.setTargetPitchAbsolute(targetDeg);
    }

    // ==================================================================

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
