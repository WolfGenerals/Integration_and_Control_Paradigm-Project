# Quick Start — Get Driving in 3 Minutes

> **Audience**: Players who just want to drive something.
> **Prerequisites**: Minecraft 1.21.1 + NeoForge with IAC-P installed.

---

## English

### Build

1. Use **Honey Glue**\* (right-click) on two block corners to define a build region.
2. Place a **Physical Assembler**\* inside the region. Hold right-click to pull the lever — your structure becomes physical.
3. Place a **Cockpit** on the physical structure (or include it in the region before assembling).
4. Place **Suspension Test Blocks** on the structure. Hold an **Offroad Wheel**\* item and right-click a Suspension Test Block to attach a wheel.

    > \* Wheel items are provided by the Offroad module (included in Simulated-Project). The IAC-P mod implements suspension physics, friction circle, and tire pressure/burst simulation on top of Offroad's tire data components.
    > \* Honey Glue and Physical Assembler are provided by **Simulated-Project**. IAC-P interacts with them for vehicle assembly and cockpit placement within physical structures. The underlying physics-ification of blocks (assemble/disassemble) is also handled by Simulated-Project.

### Drive

1. Aim your crosshair at the physical structure and press **F** to mount.
2. **W** = accelerate, **S** = decelerate, **Q** = shift up, **E** = shift down (if inventory opens, press E again).
3. **A** = turn left, **D** = turn right.
5. Controls feel wrong? Press **F** to dismount. Press **C** while mounted to open the **Vehicle Orientation Screen** — it shows your suspension FACING statistics and offers one-click **Car Mode** (auto-assigns WASD based on wheel layout), **Reverse** (swap W↔S, A↔D), and **Toggle** (enable/disable smart mapping).
6. Or aim at a Suspension Test Block and press **C** to open its individual keybinding config. Configure keys to your liking. Setting a key to an unused button effectively clears that binding.

### Shoot

1. Place a **Turret Base** on your structure — it looks like a carpet.
2. Right-click it to summon the **Grindstone Turret**. Right-click again to dismiss.
3. Press **F** to mount. The turret auto-aims at your crosshair. **Left-click** to fire!
4. If the turret falls apart on reconnect, double right-click the Turret Base to quickly repair it.

### Tips

1. While mounted, a real-time debug overlay (weight, RPM, speed, etc.) shows in the bottom-right corner.
2. Camera behavior (height, distance, invert Y) can be tuned in the mod config screen.

---

## 中文

建造！

1.在游戏中使用物品“蜂蜜胶”\*右键选中两个顶点，设定范围
2.在范围内的方块上放置“物理组装器”\*，长按右键拉下拉杆，创建物理化结构。
3.在物理化结构上放置“通用驾驶舱”（当然，也可以在设定范围的时候一并囊括，随后一同物理化）
4.在物理化结构上放置“悬挂测试方块”，并手持“车轮”\*物品右键悬挂测试方块，安装车轮。

    > \* 车轮物品由 Offroad 模组提供（包含在 Simulated-Project 中）。IAC-P 在 Offroad 轮胎数据组件之上实现悬挂物理、摩擦圆和胎压/爆胎模拟。
    > \* "蜂蜜胶"(Honey Glue)和"物理组装器"(Physical Assembler)由 **Simulated-Project** 提供。IAC-P 与之交互以完成载具的装配和驾驶舱放置。方块物理化（装配/拆卸）的底层机制同样由 Simulated-Project 处理。

驾驶！

5.准星对准物理化结构按下 F ,即可上车
6.W键增加油门，S减少油门，Q升档，E降档（如果打开了物品栏则再点一次E即可）
7.A键左转，D键右转
8.如果出现控制不符合驾驶直觉的情况，可以在车上按 **C** 打开**载具朝向信息界面**——显示各悬挂方块朝向分布，提供一键**汽车模式**（根据轮子布局自动分配 WASD）、**反转方向盘**（W↔S, A↔D）、**开关智能映射**。
9.也可以下车后对准悬挂测试方块按 **C**，打开单个悬挂按键配置界面，按自己喜好配置。将按键设置成不常用按键可以看作是清空。

射击！

10.您可以在物理结构上放置“武器底座”，它看起来像是个地毯
11.放置后对其右键即可召唤炮塔“砂轮枪”，再点一次收回。
12.按下F上车，炮塔会自动瞄准到您准星所在的位置，鼠标左键开火！
13.重新进入游戏时如果炮塔解体并散落一地，可以对着武器底座双击鼠标右键快速修复。

tips:
1.上车状态时，右下角会实时更新显示载具的运动参数。
2.在模组配置界面，您可以修改摄像机行为参数。
