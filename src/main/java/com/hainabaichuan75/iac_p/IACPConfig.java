package com.hainabaichuan75.iac_p;

/**
 * IAC-P 编译时常量配置。
 * <p>
 * 与 {@link Config}（运行时 ModConfigSpec）不同，此类中的配置为编译时常量， 适用于需要编译期确定的开关（如是否启用
 * SubLevel 缩放，这取决于引用的 Sable 版本是否支持 scale 传递到物理引擎原生层）。
 * <p>
 * 当 Sable 维护者合并了 scale 支持后，可移除这些常量或改为运行时配置。
 */
public final class IACPConfig {

    // ====== SubLevel 缩放（比例耦合方案） ======
    /**
     * 是否启用 SubLevel 缩放。
     * <p>
     * 设为 {@code true} 需要改版 Sable（feature/sublevel-scale 分支）， 该版本在 JNI 层将
     * Pose3d.scale 传递到 Rapier 物理引擎原生层。 使用原版 Sable 时必须设为 {@code false}，否则会导致
     * Java 侧变换 （座位高度、悬挂物理等）与物理碰撞体不同步。
     */
    public static final boolean SUBLEVEL_SCALE_ENABLED = false;

    /**
     * SubLevel 缩放系数 X（Crossout 网格 / Minecraft 网格 = 1/3）
     */
    public static final double SUBLEVEL_SCALE_X = 1.0 / 3.0;

    /**
     * SubLevel 缩放系数 Y
     */
    public static final double SUBLEVEL_SCALE_Y = 1.0 / 3.0;

    /**
     * SubLevel 缩放系数 Z
     */
    public static final double SUBLEVEL_SCALE_Z = 1.0 / 3.0;

    // ====== 构造器私有化，禁止实例化 ======
    private IACPConfig() {
    }
}
