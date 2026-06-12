# 关键技术要点 — 索引

> 开发中高频查阅的技术知识点汇总。按主题分类，每篇独立文件。

| # | 标题 | 关键词 |
|:--:|------|--------|
| 1 | [BlockEntity 接口选择](1-BlockEntity接口选择.md) | IBE, ticker, SmartBlockEntity |
| 2 | [跨 BE 通信：不要用静态共享 Map](2-跨BE通信：不要用静态共享Map.md) | putIfAbsent, enqueueWork, 直接扫描 |
| 3 | [SubLevel 坐标：两种变换要分清](3-SubLevel坐标：两种变换要分清.md) | getLoadedChunks, logicalPose, renderPose |
| 4 | [摩擦圆物理](4-摩擦圆物理.md) | frictionBudget, springImpulse, nm vs sm |
| 5 | [手刹物理（v3 最终方案）](5-手刹物理（v3最终方案）.md) | BRAKE_STRENGTH, springImpulse, 滑动摩擦 |
| 6 | [变速箱齿比设计](6-变速箱齿比设计.md) | 直接档, 超比档, ratio=1.0 |
| 7 | [质量 API](7-质量API.md) | MassData.getMass, getInverseNormalMass |
| 8 | [调试覆盖层原则](8-调试覆盖层原则.md) | 编译时常量, 不硬编码 |
| 9 | [setPos vs teleportTo](9-setPos%20vs%20teleportTo.md) | lerpSteps, 位置同步包 |
| 9.5 | [Sable 的 Pose3d.scale](9.5%20Sable的Pose3d.scale：API设计在先，物理实现烂尾.md) | 缩放烂尾, Rust JNI, 上游未实现 |
| 10 | [StreamCodec 参数限制](10-StreamCodec参数限制.md) | composite 8字段, 手动编解码 |
| 11 | [摄像机极点奇点（Gimbal Lock）](11-摄像机极点奇点（Gimbal%20Lock）.md) | atan2(0,0), horizontalDist, 欧拉角 |
| 12 | [自适应摄像机](12-自适应摄像机.md) | boundingBox, 自适应高度, 自适应距离 |
| 13 | [玩家隐藏方案选择](13-玩家隐藏方案选择.md) | RenderPlayerEvent, setInvisible, 粒子抑制 |
| 14 | [质量自适应扭矩](14-质量自适应扭矩.md) | TORQUE_WEIGHT_RATIO, effectiveTorque, 下限兜底 |
| 15 | [连续油门系统](15-连续油门系统.md) | throttleLevel, 三段式衰减, THROTTLE_RATE |
| 16 | [扭矩平衡负载模型](16-扭矩平衡负载模型.md) | loadFactor, torqueRatio, 06-08分母修正 |
| 17 | [动态载荷转移](17-动态载荷转移.md) | LOAD_TRANSFER_SENSITIVITY, accelX, 侧向符号 |
| 18 | [Brush 轮胎侧偏模型](18-Brush轮胎侧偏模型.md) | CORNERING_STIFFNESS, slipAngle, 混合过渡 |
| 19 | [扭矩曲线（Torque Curve）](19-扭矩曲线（Torque%20Curve）.md) | TORQUE_IDLE_FRACTION, sin拟合, 平坦化 |
| 20 | [显示值 vs 物理值分离](20-显示值vs物理值分离.md) | frictionDemandRatio, 自然预算, 20× |
| 21 | [客户端事件注册](21-客户端事件注册：@EventBusSubscriber%20vs%20NeoForge.EVENT_BUS.register.md) | NeoForge.EVENT_BUS, @EventBusSubscriber, 总线选择 |
| 22 | [自定义世界渲染](22-自定义世界渲染（NeoForge%201.21.1）.md) | RenderLevelStageEvent, Tesselator, Sable debug render |
| 23 | [静态 UUID 注册表模式](23-静态UUID注册表模式.md) | GRINDSTONE_OWNER_MAP, UUID→方块实体反查 |
| 24 | [S2C 数据推送 vs NBT 同步](24-S2C数据推送vsNBT同步.md) | sendData, AnchorDataS2CPacket, 手动编解码 |
| 25 | [Sable 约束坐标系](25-Sable约束坐标系（血的教训）.md) | 底层绝对方块坐标, RotaryConstraint, 约束类型 |
| 26 | [轮胎参数设计](26-轮胎参数设计：运行时字段vs编译时常量.md) | 运行时NBT字段, 编译时常量, 决策检查清单 |
| 27 | [多 SubLevel 约束链](27-多SubLevel约束链：三层嵌套避雷针炮塔（06-09血泪教训）.md) | 约束链, GenericConstraint, pose一致性 |
| 28 | [Create 应力网络 RPM 阶跃特性](28-Create应力网络RPM阶跃特性.md) | RPM, 阶跃, DebugGear, Create应力网络 |
| 29 | [炮塔位置模式 PD 伺服（SwivelBearing 模式）](29-炮塔位置模式PD伺服（SwivelBearing模式）.md) | 位置模式, setMotor, PD伺服, RotaryConstraint |
