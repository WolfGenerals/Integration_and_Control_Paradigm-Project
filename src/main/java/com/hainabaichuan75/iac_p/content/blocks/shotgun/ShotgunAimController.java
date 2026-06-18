package com.hainabaichuan75.iac_p.content.blocks.shotgun;

/**
 * 霰弹枪瞄准控制器 —— packet handler 直接驱动 + 立即执行 servo 更新。
 * <p>
 * 瞄准数据流（06-15 最终版）：
 * <ol>
 * <li>客户端每 tick 发送命中点坐标 {@code TurretTargetC2SPacket(hitX, hitY, hitZ)}</li>
 * <li>服务端 packet handler 在载具局部空间计算角度 →
 * {@link #driveAnglesImmediate(ShotgunBaseBlockEntity, float, float)}</li>
 * <li>立即执行 {@code ShotgunBaseBE.driveImmediate()} → 设目标 + 立即 setMotor</li>
 * <li>BE.tick() 中的 servo 调用作为冗余保持（无副作用的空操作）</li>
 * </ol>
 * <p>
 * 消除了一整个 server tick（0~50ms）的 {@code setTarget→tick→updateServo} 等待延迟。
 */
public class ShotgunAimController {

    /**
     * 立即驱动枪塔到指定角度。
     * <p>
     * 调用 {@link ShotgunBaseBlockEntity#driveImmediate(float, float)}，
     * 在设置目标角度后<b>立即执行 setMotor</b>，无需等待下一个 server tick 的
     * {@code tick()} → {@code updateServo()} 调用。
     *
     * @param sb 已解析的霰弹枪底座 BE
     * @param targetYaw 枪塔目标偏航角（度，相对于载具主体）
     * @param targetPitch 枪塔目标俯仰角（度，正=上仰）
     */
    public static void driveAnglesImmediate(ShotgunBaseBlockEntity sb,
            float targetYaw, float targetPitch) {
        sb.driveImmediate(targetYaw, targetPitch);
    }
}
