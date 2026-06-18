package com.hainabaichuan75.iac_p.client;

import com.hainabaichuan75.iac_p.IACP;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlock;
import com.hainabaichuan75.iac_p.content.blocks.suspension_test.SuspensionTestBlockEntity;
import com.hainabaichuan75.iac_p.events.SubLevelScanner;
import dev.ryanhcode.sable.Sable;

import javax.annotation.Nullable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端骑乘状态处理器 —— Plan B 实现。
 * <p>
 * 核心改动：相机不再跟随玩家实体，而是直接绑定到 SubLevel 的 {@code renderPose()} （Create Simulated
 * 已经处理好的平滑插值变换），彻底消除玩家实体插值和 SubLevel 渲染插值之间的冲突。
 * <p>
 * 额外处理：
 * <ul>
 * <li>重新进入世界时重置骑乘状态</li>
 * <li>骑乘时禁止交互、隐藏手部渲染</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = IACP.MODID, value = Dist.CLIENT)
public class ClientMountHandler {

    private static boolean isMounted = false;
    /**
     * Plan B: 当前骑乘的 SubLevel UUID，用于在客户端获取 renderPose()
     */
    private static UUID mountedSubLevelUUID = null;

    /**
     * 上车时由服务端同步的载具实际物理质量（kg），用于调试覆盖层显示
     */
    private static double vehicleMass = 0;

    /**
     * 驾驶舱方块在 SubLevel Plot 中的本地位置（底部中心），用于客户端位置同步
     */
    private static double cockpitLocalX, cockpitLocalY, cockpitLocalZ;

    // ====== 悬挂方块位置缓存（性能优化） ======
    /**
     * 当前载具 SubLevel 中所有悬挂测试方块的世界坐标列表。 上车时一次性填充，供
     * {@code sendVehicleControlInput()} 使用， 避免每 2 tick 全量扫描 SubLevel chunks。
     */
    private static final List<BlockPos> SUSPENSION_POSITIONS = new ArrayList<>();

    /**
     * 获取缓存的悬挂方块位置列表。 由
     * {@link com.hainabaichuan75.iac_p.client.ClientEvents#sendVehicleControlInput}
     * 使用。
     */
    public static List<BlockPos> getSuspensionPositions() {
        return SUSPENSION_POSITIONS;
    }

    /**
     * 扫描 SubLevel 并刷新悬挂方块位置缓存。 在 mount 和状态变更时调用。
     */
    public static void refreshSuspensionPositions(SubLevel subLevel, Level level) {
        SUSPENSION_POSITIONS.clear();
        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            if (state.getBlock() instanceof SuspensionTestBlock) {
                SUSPENSION_POSITIONS.add(worldPos);
            }
        });
    }

    // ====== 载具朝向缓存（WASD 智能映射用） ======
    /**
     * SubLevel UUID → 悬挂朝向统计缓存。上车/扫描时填充，下车时清空。
     */
    private static final Map<UUID, VehicleOrientationData> ORIENTATION_CACHE = new HashMap<>();

    /**
     * 扫描指定 SubLevel 内所有悬挂方块的 HORIZONTAL_FACING，统计四个方向的数目。
     *
     * @param subLevel 要扫描的 SubLevel（客户端或服务端均可）
     * @param level 主世界 Level 实例
     * @return 悬挂朝向统计数据
     */
    public static VehicleOrientationData scanOrientation(SubLevel subLevel, Level level) {
        int[] north = {0}, south = {0}, east = {0}, west = {0};

        SubLevelScanner.forEachBlockState(subLevel, level, (worldPos, state) -> {
            if (!(state.getBlock() instanceof SuspensionTestBlock)) {
                return;
            }
            Direction facing = state.getValue(SuspensionTestBlock.HORIZONTAL_FACING);
            switch (facing) {
                case NORTH ->
                    north[0]++;
                case SOUTH ->
                    south[0]++;
                case EAST ->
                    east[0]++;
                case WEST ->
                    west[0]++;
            }
        });

        VehicleOrientationData data = new VehicleOrientationData(north[0], south[0], east[0], west[0]);
        // 缓存到 SubLevel UUID
        ORIENTATION_CACHE.put(subLevel.getUniqueId(), data);

        // 同时刷新悬挂位置缓存
        refreshSuspensionPositions(subLevel, level);

        return data;
    }

    /**
     * 从缓存获取指定 SubLevel 的悬挂朝向数据。
     *
     * @param subLevelUUID SubLevel 的 UUID
     * @return 缓存的朝向数据，如果未扫描则返回空数据
     */
    public static VehicleOrientationData getOrientationData(UUID subLevelUUID) {
        return ORIENTATION_CACHE.getOrDefault(subLevelUUID, new VehicleOrientationData(0, 0, 0, 0));
    }

    /**
     * 清除指定 SubLevel 的朝向缓存。
     */
    public static void clearOrientationCache(UUID subLevelUUID) {
        ORIENTATION_CACHE.remove(subLevelUUID);
    }

    /**
     * 清除所有朝向缓存。下车/断线时调用。
     */
    private static void clearAllOrientationCache() {
        ORIENTATION_CACHE.clear();
    }

    // ====== 智能映射状态缓存 ======
    /**
     * 当前载具的智能映射是否已启用（从 CockpitBE 同步）
     */
    private static boolean smartMappingActive = false;

    /** 当前载具的智能变速是否已启用（从 CockpitBE 同步） */
    private static boolean autoShiftEnabled = false;

    /**
     * 当前选中的驾驶技能 ID（从 CockpitBE 同步）
     */
    private static String activeSkillId = com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;

    public static boolean isSmartMappingActive() {
        return smartMappingActive;
    }

    public static void setSmartMappingActive(boolean active) {
        smartMappingActive = active;
    }

    public static boolean isAutoShiftEnabled() {
        return autoShiftEnabled;
    }

    public static void setAutoShiftEnabled(boolean enabled) {
        autoShiftEnabled = enabled;
    }

    /**
     * @return 当前选中的驾驶技能 ID
     */
    public static String getActiveSkillId() {
        return activeSkillId;
    }

    /**
     * 设置当前选中的驾驶技能 ID。
     */
    public static void setActiveSkillId(String skillId) {
        activeSkillId = skillId != null ? skillId : com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;
    }

    /**
     * 在客户端本地立即交换所有悬挂方块的智能映射键（W↔S, A↔D）。 在发送 REVERSE 网络包后立即调用，消除等待服务端同步的延迟窗口期。
     */
    public static void localSwapSmartKeys() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ClientSubLevel clientSubLevel = getMountedClientSubLevel();
        if (clientSubLevel == null) {
            return;
        }

        SubLevelScanner.forEachBlock(clientSubLevel, mc.level, (worldPos, state, be) -> {
            if (!(state.getBlock() instanceof SuspensionTestBlock)) {
                return;
            }
            if (!(be instanceof SuspensionTestBlockEntity sbe)) {
                return;
            }

            String oldFwd = sbe.getSmartKeyForward();
            String oldBwd = sbe.getSmartKeyBackward();
            String oldLeft = sbe.getSmartKeyLeft();
            String oldRight = sbe.getSmartKeyRight();

            // 仅当有智能映射键时才反转
            if (oldFwd.isEmpty() && oldBwd.isEmpty()
                    && oldLeft.isEmpty() && oldRight.isEmpty()) {
                return;
            }

            sbe.setSmartKeyBindings(
                    oldBwd.isEmpty() ? oldFwd : oldBwd, // W↔S
                    oldFwd.isEmpty() ? oldBwd : oldFwd,
                    oldRight.isEmpty() ? oldLeft : oldRight, // A↔D
                    oldLeft.isEmpty() ? oldRight : oldLeft,
                    sbe.getActiveKeyBrake()
            );
        });
    }

    /**
     * 在 SubLevel 中查找驾驶舱 BE，同步其 smartMappingActive、autoShiftEnabled 和 activeSkillId 状态到缓存。
     */
    public static void syncSmartMappingState(SubLevel subLevel, Level level) {
        smartMappingActive = false;
        autoShiftEnabled = false;
        activeSkillId = com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;

        SubLevelScanner.forEachBlock(subLevel, level, (worldPos, state, be) -> {
            if (state.getBlock() instanceof com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlock
                    && be instanceof com.hainabaichuan75.iac_p.content.blocks.cockpit.CockpitBlockEntity cockpit) {
                smartMappingActive = cockpit.isSmartMappingActive();
                autoShiftEnabled = cockpit.isAutoShiftEnabled();
                String skillId = cockpit.getActiveSkillId();
                if (skillId != null && !skillId.isEmpty()) {
                    activeSkillId = skillId;
                }
            }
        });
    }

    // ====== 哨兵摄像机模式 ======
    /**
     * 哨兵摄像机模式：摄像机冻结在当前世界位置，持续锁定载具中心。 上车时启用，再次按键恢复轨道模式。
     */
    private static boolean cameraStationary = false;

    /**
     * 进入哨兵模式时摄像机所在的世界坐标。
     */
    private static Vec3 stationaryCameraPos = null;

    /**
     * 进入哨兵模式时的玩家视角（用于离开时恢复）。
     */
    private static float stationaryYaw, stationaryPitch;

    public static boolean isCameraStationary() {
        return cameraStationary;
    }

    @Nullable
    public static Vec3 getStationaryCameraPos() {
        return stationaryCameraPos;
    }

    /**
     * 切换哨兵摄像机模式。
     * <p>
     * 进入时记录当前摄像机世界位置； 退出时根据载具当前速度方向设置摄像机朝向（面向载具前进方向），
     * 使摄像机自然跟随载具移动，而非跳转到进入时的旧角度。
     *
     * @param mc Minecraft 实例，用于获取当前摄像机位置
     */
    public static void toggleStationaryCamera(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        if (!cameraStationary) {
            // 进入哨兵模式：冻结摄像机位置
            var camera = mc.gameRenderer.getMainCamera();
            stationaryCameraPos = camera.getPosition();
            stationaryYaw = mc.player.getYRot();
            stationaryPitch = mc.player.getXRot();
            cameraStationary = true;
            IACP.LOGGER.info("[哨兵摄像机] 已启用 @ {}", stationaryCameraPos);
        } else {
            // 退出哨兵模式：根据载具速度方向计算视角
            cameraStationary = false;
            stationaryCameraPos = null;

            if (mc.player != null) {
                // 获取载具当前速度方向 → 设置玩家视角面向前进方向
                float velocityYaw = computeVehicleVelocityYaw(mc);
                if (velocityYaw >= 0) {
                    // 速度有效：面向速度方向（摄像机自动位于载具后方）
                    mc.player.setYRot(velocityYaw);
                    mc.player.setXRot(15.0f); // 轻微俯视，看清前方路况
                } else {
                    // 速度过低或无法获取：回退恢复进入时的视角
                    mc.player.setYRot(stationaryYaw);
                    mc.player.setXRot(stationaryPitch);
                }
            }
            IACP.LOGGER.info("[哨兵摄像机] 已禁用");
        }
    }

    /**
     * 计算载具当前速度方向对应的玩家偏航角。
     * <p>
     * 通过 {@link Sable#HELPER}.getVelocity() 查询载具 SubLevel 内
     * 已知方块位置的速度向量，取其水平方向计算 yaw。
     *
     * @return 面向速度方向的偏航角（度），速度过低或查询失败返回 -1
     */
    private static float computeVehicleVelocityYaw(Minecraft mc) {
        if (mc.level == null) {
            return -1;
        }

        ClientSubLevel subLevel = getMountedClientSubLevel();
        if (subLevel == null) {
            return -1;
        }

        // 使用悬挂方块缓存中的第一个位置查询速度
        Vec3 queryPos = null;
        if (!SUSPENSION_POSITIONS.isEmpty()) {
            BlockPos p = SUSPENSION_POSITIONS.get(0);
            queryPos = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        } else {
            // 降级：使用驾驶舱本地位置的变换坐标
            var renderPose = subLevel.renderPose(0);
            if (renderPose != null) {
                org.joml.Vector3d worldPos = new org.joml.Vector3d();
                renderPose.transformPosition(
                        new org.joml.Vector3d(cockpitLocalX, cockpitLocalY, cockpitLocalZ), worldPos);
                queryPos = new Vec3(worldPos.x, worldPos.y, worldPos.z);
            }
        }

        if (queryPos == null) {
            return -1;
        }

        org.joml.Vector3d vel = Sable.HELPER.getVelocity(mc.level,
                new org.joml.Vector3d(queryPos.x, queryPos.y, queryPos.z));
        if (vel == null) {
            return -1;
        }

        double speed = vel.length();
        if (speed < 0.1) {
            return -1; // 速度太低，方向不可靠
        }
        // 速度向量的水平 yaw（Minecraft 坐标系：-Z 为南）
        float yaw = (float) Math.toDegrees(Math.atan2(-vel.x(), -vel.z()));
        return yaw;
    }

    /**
     * 下车时强制关闭哨兵模式。
     */
    private static void disableStationaryCamera() {
        cameraStationary = false;
        stationaryCameraPos = null;
    }

    // ==================================================================
    //  车辆实时状态缓存（由 VehicleStateS2CPacket 每 2 tick 填充）
    // ==================================================================
    //  覆盖层从此处读取高频动态数据，与 NBT 块实体同步解耦，
    //  消除油门稳定时 RPM/车速不更新的问题。

    /** 发动机当前转速（RPM） */
    private static double cachedEngineRpm = 0;
    /** 油门踏板深度 [0.0, 1.0] */
    private static double cachedThrottleLevel = 0;
    /** 当前档位 */
    private static int cachedCurrentGear = 0;
    /** 发动机是否熄火 */
    private static boolean cachedStalled = false;
    /** 引擎输出扭矩（Nm），含扭矩曲线修正 × 油门（来自服务端同步） */
    private static double cachedEffectiveTorque = 0;
    /** 载具当前速度（m/s） */
    private static double cachedVehicleSpeedMs = 0;
    /** 载具当前加速度（m/s²），来自服务端速度差分 */
    private static double cachedVehicleAccelMs2 = 0;
    /** 是否正在换挡（动力中断期间） */
    private static boolean cachedIsShifting = false;

    /**
     * 由 VehicleStateS2CPacket.handle() 调用，更新缓存。
     * 所有字段一次性写入，避免部分更新导致覆盖层读到不一致状态。
     */
    public static void updateVehicleState(
            double engineRpm, double throttleLevel, int currentGear,
            boolean stalled, double effectiveTorque, double vehicleSpeedMs,
            double vehicleAccelMs2, boolean isShifting) {
        cachedEngineRpm = engineRpm;
        cachedThrottleLevel = throttleLevel;
        cachedCurrentGear = currentGear;
        cachedStalled = stalled;
        cachedEffectiveTorque = effectiveTorque;
        cachedVehicleSpeedMs = vehicleSpeedMs;
        cachedVehicleAccelMs2 = vehicleAccelMs2;
        cachedIsShifting = isShifting;
    }

    /** @return 缓存的发动机转速（RPM） */
    public static double getCachedEngineRpm() { return cachedEngineRpm; }
    /** @return 缓存的油门深度 [0.0, 1.0] */
    public static double getCachedThrottleLevel() { return cachedThrottleLevel; }
    /** @return 缓存的当前档位 */
    public static int getCachedCurrentGear() { return cachedCurrentGear; }
    /** @return 缓存的熄火状态 */
    public static boolean isCachedStalled() { return cachedStalled; }
    /** @return 缓存的有效扭矩（Nm） */
    public static double getCachedEffectiveTorque() { return cachedEffectiveTorque; }
    /** @return 缓存的载具速度（m/s） */
    public static double getCachedVehicleSpeedMs() { return cachedVehicleSpeedMs; }
    /** @return 缓存的载具加速度（m/s²） */
    public static double getCachedVehicleAccelMs2() { return cachedVehicleAccelMs2; }
    /** @return 是否正在换挡 */
    public static boolean isCachedShifting() { return cachedIsShifting; }

    /** 下车时清空状态缓存 */
    private static void clearVehicleStateCache() {
        cachedEngineRpm = 0;
        cachedThrottleLevel = 0;
        cachedCurrentGear = 0;
        cachedStalled = false;
        cachedEffectiveTorque = 0;
        cachedVehicleSpeedMs = 0;
        cachedVehicleAccelMs2 = 0;
        cachedIsShifting = false;
    }

    // ====== 公开 API ======
    /**
     * 处理上车/下车状态（由 {@code MountedStateS2CPacket} 调用）。
     *
     * @param mounted 是否上车
     * @param subLevelUUID 上车时对应的 SubLevel UUID（下车时传空值）
     * @param cx 驾驶舱本地 X（底部中心）
     * @param cy 驾驶舱本地 Y（底部）
     * @param cz 驾驶舱本地 Z（底部中心）
     */
    public static void handleMountState(boolean mounted, UUID subLevelUUID,
            double cx, double cy, double cz) {
        isMounted = mounted;
        mountedSubLevelUUID = mounted ? subLevelUUID : null;
        cockpitLocalX = cx;
        cockpitLocalY = cy;
        cockpitLocalZ = cz;

        var mc = Minecraft.getInstance();
        if (mounted) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);

            // 上车时自动扫描载具悬挂朝向，缓存供 WASD 智能映射使用
            ClientSubLevel clientSubLevel = getMountedClientSubLevel();
            if (clientSubLevel != null && mc.level != null) {
                scanOrientation(clientSubLevel, mc.level);
                // scanOrientation() 内部已调用 refreshSuspensionPositions()
                // 同步智能映射开关状态
                syncSmartMappingState(clientSubLevel, mc.level);
            }
        } else {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            // 恢复玩家可见性（骑乘时被设为不可见以抑制粒子）
            mc.player.setInvisible(false);
            // 下车时清除缓存
            clearAllOrientationCache();
            SUSPENSION_POSITIONS.clear();
            smartMappingActive = false;
            autoShiftEnabled = false;
            activeSkillId = com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;
            // 清空弹道特效（防止悬空）
            WeaponOverlay.onDismount();
            // 强制关闭哨兵模式
            disableStationaryCamera();
            // 清空车辆状态缓存
            clearVehicleStateCache();
        }
    }

    public static boolean isMounted() {
        return isMounted;
    }

    /**
     * @return 服务端同步的载具实际质量（kg），驾驶舱接入时有效
     */
    public static double getVehicleMass() {
        return vehicleMass;
    }

    /**
     * 由 {@link com.hainabaichuan75.iac_p.network.packets.MountedStateS2CPacket}
     * 调用，设置实际质量
     */
    public static void setVehicleMass(double mass) {
        vehicleMass = mass;
    }

    /**
     * 获取当前骑乘的 SubLevel（客户端），用于 Mixin 在渲染阶段获取 renderPose。
     */
    public static ClientSubLevel getMountedClientSubLevel() {
        if (!isMounted || mountedSubLevelUUID == null) {
            return null;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) {
            return null;
        }
        SubLevel subLevel = container.getSubLevel(mountedSubLevelUUID);
        if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
            return null;
        }
        return clientSubLevel;
    }

    // ====== 重新进入世界时重置状态 ======
    /**
     * 当客户端玩家加入/重新进入世界时，重置骑乘状态。
     * <p>
     * 解决：玩家在"上车"状态退出单人游戏后再进入， 客户端 isMounted 仍为 true 导致控制权被错误剥夺的问题。
     */
    @SubscribeEvent
    public static void onClientLogin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        if (isMounted) {
            isMounted = false;
            mountedSubLevelUUID = null;
            // 确保恢复第一人称
            var mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        // 不论是否挂载，都清理所有缓存（确保重连后状态干净）
        clearAllOrientationCache();
        SUSPENSION_POSITIONS.clear();
        smartMappingActive = false;
        activeSkillId = com.hainabaichuan75.iac_p.skill.SkillRegistry.DEFAULT_SKILL_ID;
    }

    // ====== 每 Client Tick：Plan B 摄像机跟随 ======
    /**
     * 每 client tick 将玩家位置同步到驾驶舱方块底部中心。
     * <p>
     * 使用 {@code renderPose(partialTick)} 确保玩家模型与 SubLevel 渲染位置平滑对齐。
     * 零碰撞箱避免客户端碰撞检测干扰。 偏航角由 SubLevel 位姿变化决定，使玩家视觉模型始终面向车辆前进方向。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!isMounted) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 强制第三人称背面，让 F5 失效
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);

        // 无物理（防止服务端 SubLevel 刚体碰撞网格交互）
        mc.player.noPhysics = true;

        // 零体积碰撞箱：使原版碰撞系统和 Sable SubLevelEntityCollision 均忽略该实体
        var p = mc.player;
        mc.player.setBoundingBox(new net.minecraft.world.phys.AABB(
                p.getX(), p.getY(), p.getZ(),
                p.getX(), p.getY(), p.getZ()));

        // 速度归零
        mc.player.setDeltaMovement(Vec3.ZERO);

        // 禁用行走/奔跑动画（腿部和手臂保持静止）
        // walkAnimation.speed/position 设为 0 冻结肢体摆动。
        // walkDist = oWalkDist 使游戏认为行走距离增量为零，
        // 防止 aiStep() 中通过位置变化量重新计算动画。
        mc.player.walkAnimation.speed(0.0f);
        mc.player.walkAnimation.position(0.0f);
        // oWalkDist 是 LivingEntity 私有字段，不可直接访问。
        // walkAnimation 冻结由 RenderPlayerEvent.Pre 在渲染阶段执行。

        // === 玩家位置同步到驾驶舱方块底部（使用 renderPose 平滑插值） ===
        ClientSubLevel clientSubLevel = getMountedClientSubLevel();
        if (clientSubLevel == null) {
            return;
        }

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        var renderPose = clientSubLevel.renderPose(partialTick);
        if (renderPose == null) {
            return;
        }

        // 驾驶舱本地位置 → 世界空间
        org.joml.Vector3d worldPos = new org.joml.Vector3d();
        renderPose.transformPosition(
                new org.joml.Vector3d(cockpitLocalX, cockpitLocalY, cockpitLocalZ),
                worldPos);

        var player = mc.player;

        double px = worldPos.x;
        double py = worldPos.y;
        double pz = worldPos.z;

        // 玩家不可见（抑制大部分粒子生成，如受伤粒子、进食粒子等）
        player.setInvisible(true);

        // 同步位置：使用 setPosRaw 避免触发位置相关事件，
        // 不设置 xo/yo/zo——让 Minecraft 的实体渲染插值系统自然处理
        // 前后两 tick 之间的平滑过渡，与 SubLevel 的 renderPose
        // 插值时序保持一致，消除视觉滞后。
        player.setPosRaw(px, py, pz);

        // 位置日志已移除（性能优化）
    }

}
