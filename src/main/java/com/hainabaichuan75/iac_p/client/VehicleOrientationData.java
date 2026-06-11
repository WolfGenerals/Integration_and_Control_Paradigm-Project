package com.hainabaichuan75.iac_p.client;

import net.minecraft.core.Direction;

/**
 * 载具悬挂朝向统计数据。
 * <p>
 * 记录 SubLevel 中所有悬挂方块的 HORIZONTAL_FACING 分布情况，
 * 用于推断载具的前进/宽度轴——WASD 智能映射的基础数据。
 * <p>
 * 逻辑：悬挂方块安装轮子的面（FACING）朝哪个水平方向，
 * 多数朝向的轴向 = 宽度轴（轮子伸出去的方向），
 * 垂直方向 = 前进轴。
 *
 * @param north FACING=NORTH 的悬挂数
 * @param south FACING=SOUTH 的悬挂数
 * @param east  FACING=EAST  的悬挂数
 * @param west  FACING=WEST  的悬挂数
 */
public record VehicleOrientationData(int north, int south, int east, int west) {

    public int eastWestTotal() { return east + west; }
    public int northSouthTotal() { return north + south; }
    public int total() { return north + south + east + west; }

    /**
     * @return 宽度轴（悬挂朝向占多数的轴向）。
     *         X = 东西方向为宽度轴，Z = 南北方向为宽度轴，
     *         null = 持平（东西=南北）无法判断
     */
    public Direction.Axis getWidthAxis() {
        if (eastWestTotal() > northSouthTotal()) return Direction.Axis.X;
        if (northSouthTotal() > eastWestTotal()) return Direction.Axis.Z;
        return null;
    }

    /**
     * @return 前进轴（垂直于宽度轴的方向）。
     *         X = 前进方向是东/西，Z = 前进方向是北/南，null = 无法判断
     */
    public Direction.Axis getForwardAxis() {
        Direction.Axis width = getWidthAxis();
        if (width == Direction.Axis.X) return Direction.Axis.Z;
        if (width == Direction.Axis.Z) return Direction.Axis.X;
        return null;
    }

    /** 人类可读的宽度轴描述 */
    public String getWidthAxisDisplay() {
        Direction.Axis axis = getWidthAxis();
        if (axis == Direction.Axis.X) return "东西 (X)";
        if (axis == Direction.Axis.Z) return "南北 (Z)";
        return "不确定";
    }

    /** 人类可读的前进轴描述 */
    public String getForwardAxisDisplay() {
        Direction.Axis axis = getForwardAxis();
        if (axis == Direction.Axis.X) return "东/西 (X)";
        if (axis == Direction.Axis.Z) return "南/北 (Z)";
        return "不确定";
    }
}
