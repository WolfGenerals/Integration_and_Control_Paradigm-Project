package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.client.screen.GrindstoneConfigScreen;
import com.hainabaichuan75.iac_p.client.screen.StructureInfoScreen;
import com.hainabaichuan75.iac_p.client.screen.VehicleKeyConfigScreen;
import com.hainabaichuan75.iac_p.client.screen.VehicleOrientationScreen;
import com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock;
import com.hainabaichuan75.iac_p.content.blocks.cockpit_light.CockpitLightLinear0Block;
import com.hainabaichuan75.iac_p.content.blocks.debug_gear.DebugGearBlock;
import com.hainabaichuan75.iac_p.content.blocks.debug_swivel.DebugSwivelBearingBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.network.ModNetworking;
import com.hainabaichuan75.iac_p.network.packets.DebugGearToggleC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.DebugSwivelToggleC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.GearShiftC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.SeatMountC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.MachineGunTargetC2SPacket;
import com.hainabaichuan75.iac_p.network.packets.VehicleControlC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3d;
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

import java.util.List;

/**
 * 客户端事件 —— 处理 F 键上车/下车输入、C 键打开按键配置、以及骑乘时载具控制输入检测。
 *
 * <h3>C 键配置界面</h3>
 * 对着悬挂测试方块按 C 键打开按键配置界面，可自定义每组的 5 个操控按键。
 *
 * <h3>骑乘时控制输入</h3>
 * 当玩家骑乘在载具 SubLevel 中时，每 2 ticks 扫描该 SubLevel 内的所有
 * 悬挂测试方块，检查每个方块配置的按键是否被按下，打包发送到服务端执行。 设计原则：客户端只检测按键状态（按下/抬起），服务端执行物理动作。
 */
@EventBusSubscriber(modid = IACP.MODID, value = Dist.CLIENT)
public class ClientEvents {

    private static final String KEY_CATEGORY = "key.category.iac_p";
    private static final String KEY_MOUNT = "key.iac_p.mount";
    private static final String KEY_VEHICLE_CONFIG = "key.iac_p.vehicle_config";
    private static final String KEY_RAYCAST_FIRE = "key.iac_p.raycast_fire";
    private static final String KEY_DEBUG_GEAR = "key.iac_p.debug_gear";
    private static final String KEY_STATIONARY_CAM = "key.iac_p.stationary_cam";

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

    private static final Lazy<KeyMapping> STATIONARY_CAM_KEY = Lazy.of(() -> new KeyMapping(
            KEY_STATIONARY_CAM,
            KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            KEY_CATEGORY
    ));

    /**
     * 载具控制数据包发送间隔（每 2 ticks ≈ 10 次/秒）
     */
    private static int vehicleControlCooldown = 0;

    /**
     * 换挡按键上升沿检测 —— 上次 tick 时 Q 键是否被按下
     */
    private static boolean gearUpKeyWasDown = false;
    /**
     * 换挡按键上升沿检测 —— 上次 tick 时 E 键是否被按下
     */
    private static boolean gearDownKeyWasDown = false;

    /**
     * 上次开火的游戏刻（用于冷却判断）
     */
    private static int lastFireGameTime = 0;
    /**
     * 开火最小间隔（tick）
     */
    private static final int FIRE_COOLDOWN_TICKS = 3;

    /**
     * 持续射线检测冷却（每 2 tick 执行一次以降低性能开销）
     */
    private static int raycastCooldown = 0;

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

    public static KeyMapping getStationaryCamKey() {
        return STATIONARY_CAM_KEY.get();
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (MOUNT_KEY.get().consumeClick()) {
            // 按下 F 键 → 发送上车/下车请求到服务端
            ModNetworking.sendToServer(new SeatMountC2SPacket());
        }

        if (STATIONARY_CAM_KEY.get().consumeClick()) {
            // 按下 V 键 → 切换哨兵摄像机模式（仅骑乘时有效）
            if (ClientMountHandler.isMounted()) {
                ClientMountHandler.toggleStationaryCamera(mc);
            }
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
            // 按下 N 键 → 先试结构信息界面（驾驶舱），再试调试方块
            if (!tryOpenStructureInfoScreen(mc)) {
                // 原调试方块逻辑
                Vec3 eyePos2 = mc.player.getEyePosition();
                Vec3 lookVec2 = mc.player.getLookAngle().scale(8.0);
                BlockHitResult hitResult = mc.level.clip(
                        new ClipContext(eyePos2, eyePos2.add(lookVec2), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
                );
                if (hitResult.getType() == HitResult.Type.BLOCK) {
                    BlockPos hitPos = hitResult.getBlockPos();
                    BlockState hitState = mc.level.getBlockState(hitPos);
                    if (hitState.getBlock() instanceof DebugGearBlock) {
                        ModNetworking.sendToServer(new DebugGearToggleC2SPacket(hitPos));
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable("message.iac_p.debug_gear_toggled"),
                                false);
                    } else if (hitState.getBlock() instanceof DebugSwivelBearingBlock) {
                        ModNetworking.sendToServer(new DebugSwivelToggleC2SPacket(hitPos));
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable("message.iac_p.debug_swivel_toggled"),
                                false);
                    } else {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable("message.iac_p.debug_gear_not_target"),
                                false);
                    }
                }
            }
        }

    }

    /**
     * 尝试打开载具按键配置界面。 从玩家准星出发做射线检测，如果命中悬挂测试方块则打开配置界面。
     *
     * @return 是否成功打开界面
     */
    /**
     * 尝试打开载具朝向信息界面。
     * <p>
     * 有两种触发路径：
     * <ol>
     * <li>已上车 → 直接使用缓存中的朝向数据打开界面</li>
     * <li>未上车 → 6 格射线检测，如果命中驾驶舱方块（CockpitBlock）， 查找其所属 SubLevel
     * 并扫描悬挂朝向，然后打开界面</li>
     * </ol>
     *
     * @return 是否成功打开界面
     */
    private static boolean tryOpenVehicleOrientationScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return false;
        }

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
            if (hitResult.getType() == HitResult.Type.MISS) {
                return false;
            }

            BlockPos hitPos = hitResult.getBlockPos();
            BlockState hitState = mc.level.getBlockState(hitPos);
            if (!(hitState.getBlock() instanceof CockpitBlock || hitState.getBlock() instanceof CockpitLightLinear0Block)) {
                return false;
            }

            // 查找驾驶舱所属的 SubLevel
            targetSubLevel = dev.ryanhcode.sable.Sable.HELPER.getContaining(mc.level, hitPos);
            if (targetSubLevel == null) {
                return false;
            }

            // 扫描并缓存朝向数据（非骑乘时首次扫描）
            ClientMountHandler.scanOrientation(targetSubLevel, mc.level);
        } else {
            // 已上车：刷新朝向缓存（骑乘过程中结构可能变化）
            ClientMountHandler.scanOrientation(targetSubLevel, mc.level);
        }

        // ═══ 统一刷新智能映射状态 ═══
        // 无论上车/下车路径，都从客户端 BE 的 NBT 重新读取最新状态，
        // 消除缓存滞后（如上车后服务端切换了智能映射，但缓存还是上车时的值）。
        ClientMountHandler.syncSmartMappingState(targetSubLevel, mc.level);

        if (targetSubLevel == null) {
            return false;
        }

        // 打开朝向信息界面（交互式，含汽车模式/反转/开关按钮）
        VehicleOrientationData data = ClientMountHandler.getOrientationData(targetSubLevel.getUniqueId());
        boolean smartOn = ClientMountHandler.isSmartMappingActive();
        boolean autoOn = ClientMountHandler.isAutoShiftEnabled();
        mc.setScreen(new VehicleOrientationScreen(data, targetSubLevel.getUniqueId(), smartOn, autoOn));
        return true;
    }

    /**
     * 尝试打开结构信息界面（对着驾驶舱按 N 键触发）。
     * <p>
     * 与 {@link #tryOpenVehicleOrientationScreen} 类似，但扫描的是 SubLevel 内的
     * 全量方块统计 + 武器系统信息 + 底座连接信息。
     * <p>
     * 有两种触发路径：
     * <ol>
     *   <li>已上车 → 直接使用当前挂载 SubLevel 扫描数据</li>
     *   <li>未上车 → 6 格射线检测，如果命中驾驶舱方块则查找 SubLevel 并扫描</li>
     * </ol>
     *
     * @return 是否成功打开界面
     */
    private static boolean tryOpenStructureInfoScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return false;
        }

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
            if (hitResult.getType() == HitResult.Type.MISS) {
                return false;
            }

            BlockPos hitPos = hitResult.getBlockPos();
            BlockState hitState = mc.level.getBlockState(hitPos);
            if (!(hitState.getBlock() instanceof CockpitBlock || hitState.getBlock() instanceof CockpitLightLinear0Block)) {
                return false;
            }

            // 查找驾驶舱所属的 SubLevel
            targetSubLevel = Sable.HELPER.getContaining(mc.level, hitPos);
            if (targetSubLevel == null) {
                return false;
            }
        }

        if (targetSubLevel == null) {
            return false;
        }

        // 扫描结构信息（客户端全量扫描）
        StructureInfoData data = StructureInfoData.scan(targetSubLevel, mc.level);
        mc.setScreen(new StructureInfoScreen(data));
        return true;
    }

    private static boolean tryOpenVehicleConfigScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return false;
        }

        // 5 格射线检测
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(5.0));

        BlockHitResult hitResult = mc.level.clip(
                new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );
        if (hitResult.getType() == HitResult.Type.MISS) {
            return false;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof SuspensionTestBlock)) {
            return false;
        }

        mc.setScreen(new VehicleKeyConfigScreen(hitPos));
        return true;
    }

    /**
     * 尝试打开炮塔部件（砂轮/末地烛）朝向配置界面。
     */
    private static void tryOpenGrindstoneConfigScreen(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(5.0));

        BlockHitResult hitResult = mc.level.clip(
                new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );
        if (hitResult.getType() == HitResult.Type.MISS) {
            return;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);

        // 检测砂轮或避雷针（炮塔）或霰弹枪部件
        boolean isGrindstone = hitState.is(Blocks.GRINDSTONE);
        boolean isRod = hitState.is(Blocks.END_ROD) || hitState.is(Blocks.LIGHTNING_ROD);
        if (!isGrindstone && !isRod) {
            return;
        }

        // 检查是否在 SubLevel 中
        SubLevel subLevel = Sable.HELPER.getContaining(mc.level, hitPos);
        if (subLevel == null) {
            return;
        }

        String name = isGrindstone ? "砂轮" : (hitState.is(Blocks.END_ROD) ? "末地烛" : "避雷针");
        IACP.LOGGER.info("[ClientEvents] 检测到 SubLevel 中的{} @ worldPos={} subLevelUUID={}",
                name, hitPos, subLevel.getUniqueId());
        mc.setScreen(new GrindstoneConfigScreen(subLevel.getUniqueId()));
    }

    /**
     * 每 client tick 开始前：骑乘时消耗 Q/E 按键点击，阻止物品栏和丢弃。
     * <p>
     * 在 {@link ClientTickEvent.Pre} 阶段，原版的 {@code handleKeybinds()} 尚未处理
     * KeyMapping 的 {@code consumeClick()}，但 GLFW 回调已在 Pre 之前将
     * {@code clickCount} 递增。此处调消耗这些点击次数，使原版无法触发。
     * 不影响 {@link InputConstants#isKeyDown} 的原始 GLFW 状态。
     */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (ClientMountHandler.isMounted()) {
            // 消耗 Q/E 的点击计数，在 handleKeybinds 处理前清空
            while (mc.options.keyDrop.consumeClick()) {}
            while (mc.options.keyInventory.consumeClick()) {}
        }
    }

    /**
     * 骑乘时阻止打开物品栏（E 键的第二道防线）。
     * <p>
     * 如果 {@link #onClientTickPre} 中的 consumeClick 漏掉了 E 的按键事件，
     * 此事件会在物品栏屏幕即将打开时拦截并取消。
     */
    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (ClientMountHandler.isMounted() && event.getScreen() instanceof InventoryScreen) {
            event.setCanceled(true);
        }
    }

    /**
     * 骑乘时每 tick 读取 WASD/空格/潜行输入，发送到服务端。 使用冷却避免刷屏（每 20 tick 发送一次）。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // ===== 载具控制输入检测（骑乘时） =====
        if (ClientMountHandler.isMounted() && mc.screen == null) {
            if (--vehicleControlCooldown <= 0) {
                vehicleControlCooldown = 2; // 每 2 ticks 发送一次
                sendVehicleControlInput(mc);
            }

            // ===== 持续射线检测 + 瞄准 + 开火（每 3 tick 自动 + 鼠标左键） =====
            {
                // 哨兵摄像机模式：不发送瞄准坐标，炮塔保持最后瞄准位置
                if (ClientMountHandler.isCameraStationary()) {
                    // 仍然允许开火
                    if (RAYCAST_FIRE_KEY.get().isDown()
                            && mc.level.getGameTime() - lastFireGameTime >= FIRE_COOLDOWN_TICKS) {
                        lastFireGameTime = (int) mc.level.getGameTime();
                        WeaponOverlay.fireAllTurrets(mc);
                    }
                } else {
                    // 每 2 tick 射线检测 → 发送命中点世界坐标
                    // 降低频率以减少 raycastGeneric 中全 SubLevel 遍历的开销
                    if (--raycastCooldown <= 0) {
                        raycastCooldown = 2;
                        Vec3 hitPos = WeaponOverlay.performRaycast();
                        if (hitPos != null) {
                            ModNetworking.sendToServer(
                                    new MachineGunTargetC2SPacket(
                                            (float) hitPos.x,
                                            (float) hitPos.y,
                                            (float) hitPos.z));
                        }
                    }

                    // 按住连发（最小间隔 3 tick）
                    if (RAYCAST_FIRE_KEY.get().isDown()
                            && mc.level.getGameTime() - lastFireGameTime >= FIRE_COOLDOWN_TICKS) {
                        lastFireGameTime = (int) mc.level.getGameTime();
                        WeaponOverlay.fireAllTurrets(mc);
                    }
                }
            }

            // ===== 衰减弹道渲染计数 =====
            WeaponOverlay.tickFireTrail();

            // ===== 换挡操作（PageUp 升档 / PageDown 降档） =====
            {
                long window = mc.getWindow().getWindow();
                boolean pgUp = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_PAGE_UP);
                boolean pgDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_PAGE_DOWN);

                if (pgUp && !gearUpKeyWasDown) {
                    ModNetworking.sendToServer(new GearShiftC2SPacket(GearShiftC2SPacket.Direction.UP));
                }
                if (pgDown && !gearDownKeyWasDown) {
                    ModNetworking.sendToServer(new GearShiftC2SPacket(GearShiftC2SPacket.Direction.DOWN));
                }
                gearUpKeyWasDown = pgUp;
                gearDownKeyWasDown = pgDown;
            }

        } else {
            vehicleControlCooldown = 0;
            if (!ClientMountHandler.isMounted()) {
                // 重置换挡按键状态
                gearUpKeyWasDown = false;
                gearDownKeyWasDown = false;
            }
        }
    }

    /**
     * 发送油门方向到服务端（↑/↓ 键直接控制发动机油门）。
     * <p>
     * 同时扫描所有悬挂方块，根据每个方块生效的按键（手动 key* 或智能映射 smartKey*）
     * 检测当前键盘状态，填入 entries。这确保 WASD 闸门能正确收到
     * throttleForward/Backward/braking 等标志。
     */
    /** 最近一次发送的油门方向（供覆盖层读取） */
    static int debugLastThrottleDir = 0;
    /** 最近检测到的 ↑ 键状态（供覆盖层读取） */
    static boolean debugThrottleUp = false;
    /** 最近检测到的 ↓ 键状态（供覆盖层读取） */
    static boolean debugThrottleDown = false;

    private static void sendVehicleControlInput(Minecraft mc) {
        long window = mc.getWindow().getWindow();

        // ── ↑/↓ 键直接控制油门方向 ──
        boolean upDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP);
        boolean downDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN);
        int throttleDirection = (upDown && !downDown) ? 1 : (downDown && !upDown) ? -1 : 0;

        // 更新调试数据
        debugThrottleUp = upDown;
        debugThrottleDown = downDown;
        debugLastThrottleDir = throttleDirection;

        // ── 构建逐悬挂输入条目 ──
        // 每个悬挂根据其生效按键（手动/智能映射）检测键盘状态，
        // 填入 Entry 供服务端 applyControlInput() 使用。
        // 同时本地也调 applyControlInput()，使客户端 BE 的 targetSteeringYaw
        // 实时更新，tick() 中的 chasingYaw 随之变化，渲染器读到正确转向角。
        List<VehicleControlC2SPacket.Entry> entries = new java.util.ArrayList<>();
        for (BlockPos pos : ClientMountHandler.getSuspensionPositions()) {
            BlockEntity be = mc.level.getBlockEntity(pos);
            if (be instanceof SuspensionTestBlockEntity sbe) {
                boolean fwd = isNamedKeyDown(sbe.getActiveKeyForward(), window);
                boolean bwd = isNamedKeyDown(sbe.getActiveKeyBackward(), window);
                boolean left = isNamedKeyDown(sbe.getActiveKeyLeft(), window);
                boolean right = isNamedKeyDown(sbe.getActiveKeyRight(), window);
                boolean brake = isNamedKeyDown(sbe.getActiveKeyBrake(), window);

                entries.add(new VehicleControlC2SPacket.Entry(pos, fwd, bwd, left, right, brake));

                // 本地应用：客户端 BE 的 targetSteeringYaw 立即更新 → tick() 驱动 chasingYaw
                // 转向零网络延迟，服务端同步仅用于物理一致性。
                sbe.applyControlInput(fwd, bwd, left, right, brake);
            }
        }

        // 每次发送最新状态（2 tick 冷却由调用者控制）
        ModNetworking.sendToServer(new VehicleControlC2SPacket(entries, throttleDirection));
    }

    /** 检查命名按键（如 "key.keyboard.w"）是否被按下。 */
    private static boolean isNamedKeyDown(String keyName, long window) {
        if (keyName == null || keyName.isEmpty()) return false;
        com.mojang.blaze3d.platform.InputConstants.Key mapped =
                com.mojang.blaze3d.platform.InputConstants.getKey(keyName);
        return mapped != null
                && com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, mapped.getValue());
    }

    /**
     * 骑乘时完全隐藏玩家模型及衍生粒子发射器。
     * <p>
     * 取消整个渲染事件，使玩家模型、装备、名称标签均不渲染。 再加上 {@code player.setInvisible(true)}
     * 抑制大部分粒子生成。
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        var player = event.getEntity();
        var mc = Minecraft.getInstance();

        // 1. 玩家自身上车 → 隐藏（本地玩家立即生效）
        if (ClientMountHandler.isMounted() && player == mc.player) {
            event.setCanceled(true);
            return;
        }

        // 2. 服务端已设为不可见 → 隐藏（其他玩家视角）
        if (player.isInvisible()) {
            event.setCanceled(true);
            return;
        }

        // 3. 降级检测：玩家位置在 SubLevel 内 → 上车状态
        //    覆盖 isInvisible() 尚未同步的时间窗口
        if (mc.level != null && player.isAlive()) {
            try {
                SubLevel sl = Sable.HELPER.getContaining(mc.level,
                        new Vector3d(player.getX(), player.getY(), player.getZ()));
                if (sl != null) {
                    event.setCanceled(true);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ==================================================================
    //  工具方法
    // ==================================================================
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
