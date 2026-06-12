package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.client.screen.GrindstoneConfigScreen;
import com.hainabaichuan75.iac_p.client.screen.VehicleKeyConfigScreen;
import com.hainabaichuan75.iac_p.client.screen.VehicleOrientationScreen;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.DebugGearToggleC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.GearShiftC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.SeatMountC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.TurretTargetC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleControlC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.util.Lazy;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端事件 —— 处理 F 键上车/下车输入、C 键打开按键配置、以及骑乘时载具控制输入检测。
 *
 * <h3>C 键配置界面</h3>
 * 对着悬挂测试方块按 C 键打开按键配置界面，可自定义每组的 5 个操控按键。
 *
 * <h3>骑乘时控制输入</h3>
 * 当玩家骑乘在载具 SubLevel 中时，每 2 ticks 扫描该 SubLevel 内的所有
 * 悬挂测试方块，检查每个方块配置的按键是否被按下，打包发送到服务端执行。
 * 设计原则：客户端只检测按键状态（按下/抬起），服务端执行物理动作。
 */
@EventBusSubscriber(modid = IACP.MODID, value = Dist.CLIENT)
public class ClientEvents {

    private static final String KEY_CATEGORY = "key.category.iac_p";
    private static final String KEY_MOUNT = "key.iac_p.mount";
    private static final String KEY_VEHICLE_CONFIG = "key.iac_p.vehicle_config";
    private static final String KEY_RAYCAST_FIRE = "key.iac_p.raycast_fire";
    private static final String KEY_DEBUG_GEAR = "key.iac_p.debug_gear";

    private static final Lazy<KeyMapping> MOUNT_KEY = Lazy.of(() -> new KeyMapping(
            KEY_MOUNT,
            KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            KEY_CATEGORY
    ));

    private static final Lazy<KeyMapping> VEHICLE_CONFIG_KEY = Lazy.of(() -> new KeyMapping(
            KEY_VEHICLE_CONFIG,
            KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            KEY_CATEGORY
    ));

    private static final Lazy<KeyMapping> RAYCAST_FIRE_KEY = Lazy.of(() -> new KeyMapping(
            KEY_RAYCAST_FIRE,
            KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            KEY_CATEGORY
    ));

    private static final Lazy<KeyMapping> DEBUG_GEAR_KEY = Lazy.of(() -> new KeyMapping(
            KEY_DEBUG_GEAR,
            KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            KEY_CATEGORY
    ));

    /** 载具控制数据包发送间隔（每 2 ticks ≈ 10 次/秒） */
    private static int vehicleControlCooldown = 0;

    /** 上次发送的控制状态（用于检测状态变化，减少发包） */
    private static final Map<BlockPos, boolean[]> lastControlStates = new HashMap<>();

    /** 换挡按键上升沿检测 —— 上次 tick 时 Q 键是否被按下 */
    private static boolean gearUpKeyWasDown = false;
    /** 换挡按键上升沿检测 —— 上次 tick 时 E 键是否被按下 */
    private static boolean gearDownKeyWasDown = false;

    /** 上次开火的游戏刻（用于冷却判断） */
    private static int lastFireGameTime = 0;
    /** 开火最小间隔（tick） */
    private static final int FIRE_COOLDOWN_TICKS = 3;

    /** 持续射线检测冷却（已废弃，现为每 tick 检测） */

    /**
     * 返回挂载/卸载键位映射，用于注册。
     */
    public static KeyMapping getMountKey() {
        return MOUNT_KEY.get();
    }

    /**
     * 返回载具配置键位映射，用于注册。
     */
    public static KeyMapping getVehicleConfigKey() {
        return VEHICLE_CONFIG_KEY.get();
    }

    /**
     * 返回射线检测/开火键位映射，用于注册。
     */
    public static KeyMapping getRaycastFireKey() {
        return RAYCAST_FIRE_KEY.get();
    }

    /**
     * 返回调试齿轮键位映射，用于注册。
     */
    public static KeyMapping getDebugGearKey() {
        return DEBUG_GEAR_KEY.get();
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (MOUNT_KEY.get().consumeClick()) {
            // 按下 F 键 → 发送上车/下车请求到服务端
            ModNetworking.sendToServer(new SeatMountC2SPacket());
        }

        if (VEHICLE_CONFIG_KEY.get().consumeClick()) {
            // 按下 C 键 → 先试载具朝向信息，再试悬挂配置，最后试砂轮配置
            if (!tryOpenVehicleOrientationScreen(mc)) {
                if (!tryOpenVehicleConfigScreen(mc)) {
                    tryOpenGrindstoneConfigScreen(mc);
                }
            }
        }

        if (DEBUG_GEAR_KEY.get().consumeClick()) {
            // 按下 N 键 → 射线检测找调试齿轮 → 切换调试输出
            Vec3 eyePos2 = mc.player.getEyePosition();
            Vec3 lookVec2 = mc.player.getLookAngle().scale(8.0);
            BlockHitResult hitResult = mc.level.clip(
                    new ClipContext(eyePos2, eyePos2.add(lookVec2), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
            );
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hitResult.getBlockPos();
                if (mc.level.getBlockState(hitPos).getBlock() instanceof DebugGearBlock) {
                    ModNetworking.sendToServer(new DebugGearToggleC2SPacket(hitPos));
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("message.iac_p.debug_gear_toggled"),
                            false);
                } else {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("message.iac_p.debug_gear_not_target"),
                            false);
                }
            }
        }
    }



    /**
     * 尝试打开载具按键配置界面。
     * 从玩家准星出发做射线检测，如果命中悬挂测试方块则打开配置界面。
     *
     * @return 是否成功打开界面
     */
    /**
     * 尝试打开载具朝向信息界面。
     * <p>
     * 有两种触发路径：
     * <ol>
     *   <li>已上车 → 直接使用缓存中的朝向数据打开界面</li>
     *   <li>未上车 → 6 格射线检测，如果命中驾驶舱方块（CockpitBlock），
     *       查找其所属 SubLevel 并扫描悬挂朝向，然后打开界面</li>
     * </ol>
     *
     * @return 是否成功打开界面
     */
    private static boolean tryOpenVehicleOrientationScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;

        SubLevel targetSubLevel = null;

        // 路径 1：已上车 → 使用当前挂载 SubLevel
        if (ClientMountHandler.isMounted()) {
            targetSubLevel = ClientMountHandler.getMountedClientSubLevel();
        }

        // 路径 2：未上车 → 射线检测找驾驶舱
        if (targetSubLevel == null) {
            Vec3 eyePos = mc.player.getEyePosition();
            Vec3 lookVec = mc.player.getLookAngle();
            Vec3 endPos = eyePos.add(lookVec.scale(6.0));

            BlockHitResult hitResult = mc.level.clip(
                    new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
            );
            if (hitResult.getType() == HitResult.Type.MISS) return false;

            BlockPos hitPos = hitResult.getBlockPos();
            BlockState hitState = mc.level.getBlockState(hitPos);
            if (!(hitState.getBlock() instanceof CockpitBlock)) return false;

            // 查找驾驶舱所属的 SubLevel
            targetSubLevel = dev.ryanhcode.sable.Sable.HELPER.getContaining(mc.level, hitPos);
            if (targetSubLevel == null) return false;

            // 扫描并缓存朝向数据
            ClientMountHandler.scanOrientation(targetSubLevel, mc.level);
            ClientMountHandler.syncSmartMappingState(targetSubLevel, mc.level);
        }

        if (targetSubLevel == null) return false;

        // 打开朝向信息界面（交互式，含汽车模式/反转/开关按钮）
        VehicleOrientationData data = ClientMountHandler.getOrientationData(targetSubLevel.getUniqueId());
        boolean smartOn = ClientMountHandler.isSmartMappingActive();
        mc.setScreen(new VehicleOrientationScreen(data, targetSubLevel.getUniqueId(), smartOn));
        return true;
    }

    private static boolean tryOpenVehicleConfigScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;

        // 5 格射线检测
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(5.0));

        BlockHitResult hitResult = mc.level.clip(
                new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );
        if (hitResult.getType() == HitResult.Type.MISS) return false;

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof SuspensionTestBlock)) return false;

        mc.setScreen(new VehicleKeyConfigScreen(hitPos));
        return true;
    }

    /**
     * 尝试打开炮塔部件（砂轮/避雷针）朝向配置界面。
     */
    /**
     * B 键单发开火：摄像机瞄准 + 所有炮台从炮口发射。
     */
    private static void fireWeapon(Minecraft mc) {
        if (!ClientMountHandler.isMounted()) return;
        // 摄像机瞄准（用于炮塔指向）
        Vec3 hitPos = WeaponOverlay.performRaycast();
        if (hitPos != null) {
            ModNetworking.sendToServer(new TurretTargetC2SPacket(hitPos.x, hitPos.y, hitPos.z));
            String type = WeaponOverlay.getLastHitType();
            double dist = Math.sqrt(mc.player.distanceToSqr(hitPos));
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.iac_p.fire_hit", type, dist),
                    false);
        }
        // 所有炮台从炮口发射射线（伤害 + 弹道渲染）
        WeaponOverlay.fireAllTurrets(mc);
    }

    private static void tryOpenGrindstoneConfigScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(5.0));

        BlockHitResult hitResult = mc.level.clip(
                new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );
        if (hitResult.getType() == HitResult.Type.MISS) return;

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);

        // 检测砂轮或避雷针
        boolean isGrindstone = hitState.is(Blocks.GRINDSTONE);
        boolean isRod = hitState.is(Blocks.LIGHTNING_ROD);
        if (!isGrindstone && !isRod) return;

        // 检查是否在 SubLevel 中
        SubLevel subLevel = Sable.HELPER.getContaining(mc.level, hitPos);
        if (subLevel == null) return;

        String name = isGrindstone ? "砂轮" : "避雷针";
        IACP.LOGGER.info("[ClientEvents] 检测到 SubLevel 中的{} @ worldPos={} subLevelUUID={}",
                name, hitPos, subLevel.getUniqueId());
        mc.setScreen(new GrindstoneConfigScreen(subLevel.getUniqueId()));
    }

    /**
     * 骑乘时每 tick 读取 WASD/空格/潜行输入，发送到服务端。
     * 使用冷却避免刷屏（每 20 tick 发送一次）。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // ===== 载具控制输入检测（骑乘时） =====
        if (ClientMountHandler.isMounted() && mc.screen == null) {
            if (--vehicleControlCooldown <= 0) {
                vehicleControlCooldown = 2; // 每 2 ticks 发送一次
                sendVehicleControlInput(mc);
            }

            // ===== 持续射线检测 + 瞄准 + 开火（每 3 tick 自动 + 鼠标左键） =====
            {
                Vec3 hitPos = WeaponOverlay.performRaycast();

                // 按住连发（最小间隔 3 tick）
                if (RAYCAST_FIRE_KEY.get().isDown()
                        && mc.level.getGameTime() - lastFireGameTime >= FIRE_COOLDOWN_TICKS) {
                    lastFireGameTime = (int) mc.level.getGameTime();
                    WeaponOverlay.fireAllTurrets(mc);
                }
                String hitType = WeaponOverlay.getLastHitType();
                if (hitPos != null) {
                    double dist = Math.sqrt(mc.player.distanceToSqr(hitPos));
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "message.iac_p.raycast_hit", hitType, dist),
                            true); // true = action bar
                    ModNetworking.sendToServer(new TurretTargetC2SPacket(hitPos.x, hitPos.y, hitPos.z));
                } else {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("message.iac_p.raycast_miss", hitType),
                            true);
                }
            }

            // ===== 衰减弹道渲染计数 =====
            WeaponOverlay.tickFireTrail();

            // ===== 换挡操作（Q 升档 / E 降档） =====
            long window = mc.getWindow().getWindow();
            boolean qDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Q);
            boolean eDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_E);

            // 上升沿检测：仅当按键从未按下→按下的瞬间发送
            if (qDown && !gearUpKeyWasDown) {
                ModNetworking.sendToServer(new GearShiftC2SPacket(GearShiftC2SPacket.Direction.UP));
            }
            if (eDown && !gearDownKeyWasDown) {
                ModNetworking.sendToServer(new GearShiftC2SPacket(GearShiftC2SPacket.Direction.DOWN));
            }
            gearUpKeyWasDown = qDown;
            gearDownKeyWasDown = eDown;

        } else {
            vehicleControlCooldown = 0;
            if (!ClientMountHandler.isMounted()) {
                lastControlStates.clear();
                // 重置换挡按键状态
                gearUpKeyWasDown = false;
                gearDownKeyWasDown = false;
            }
        }
    }

    /**
     * 检测每个悬挂方块配置的按键是否被按下，打包发送到服务端。
     * <p>
     * 性能优化：使用 {@link ClientMountHandler#getSuspensionPositions()} 缓存的位置列表，
     * 不再每 2 tick 全量扫描 SubLevel chunks。
     * 仅当有状态变化时才发送，减少网络开销。
     */
    private static void sendVehicleControlInput(Minecraft mc) {
        List<BlockPos> positions = ClientMountHandler.getSuspensionPositions();
        if (positions.isEmpty()) {
            lastControlStates.clear();
            return;
        }

        long window = mc.getWindow().getWindow();
        List<VehicleControlC2SPacket.Entry> entries = new ArrayList<>();
        boolean hasChanges = false;

        for (BlockPos worldPos : positions) {
            BlockState state = mc.level.getBlockState(worldPos);
            if (!(state.getBlock() instanceof SuspensionTestBlock)) continue;

            BlockEntity be = mc.level.getBlockEntity(worldPos);
            if (!(be instanceof SuspensionTestBlockEntity suspension)) continue;

            // 检查该方块配置的按键是否被按下
            // 使用智能映射键（如果设置了），否则回退到手动配置键
            boolean fwd  = InputConstants.isKeyDown(window, InputConstants.getKey(suspension.getActiveKeyForward()).getValue());
            boolean bwd  = InputConstants.isKeyDown(window, InputConstants.getKey(suspension.getActiveKeyBackward()).getValue());
            boolean left = InputConstants.isKeyDown(window, InputConstants.getKey(suspension.getActiveKeyLeft()).getValue());
            boolean right= InputConstants.isKeyDown(window, InputConstants.getKey(suspension.getActiveKeyRight()).getValue());
            boolean brake= InputConstants.isKeyDown(window, InputConstants.getKey(suspension.getActiveKeyBrake()).getValue());

            // === 同步控制输入到客户端 BE，以驱动视觉动画 ===
            // 这样轮子的 activeRpm、targetSteeringYaw、braking 在客户端也被正确设置，
            // 从而 tick() 中能产生轮子旋转动画和转向动画。
            suspension.applyControlInput(fwd, bwd, left, right, brake);

            boolean[] currentState = {fwd, bwd, left, right, brake};

            // 检测状态变化
            boolean[] lastState = lastControlStates.get(worldPos);
            boolean changed = lastState == null
                    || lastState[0] != fwd || lastState[1] != bwd
                    || lastState[2] != left || lastState[3] != right
                    || lastState[4] != brake;

            if (changed) {
                hasChanges = true;
                lastControlStates.put(worldPos, currentState);
            }

            entries.add(new VehicleControlC2SPacket.Entry(worldPos, fwd, bwd, left, right, brake));
        }

        // 仅在有关键状态变化时发送，减少网络开销
        if (hasChanges && !entries.isEmpty()) {
            ModNetworking.sendToServer(new VehicleControlC2SPacket(entries));
        }
    }

    /**
     * 骑乘时完全隐藏玩家模型及衍生粒子发射器。
     * <p>
     * 取消整个渲染事件，使玩家模型、装备、名称标签均不渲染。
     * 再加上 {@code player.setInvisible(true)} 抑制大部分粒子生成。
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (!ClientMountHandler.isMounted()) return;
        if (event.getEntity() != net.minecraft.client.Minecraft.getInstance().player) return;

        event.setCanceled(true);
    }
}
