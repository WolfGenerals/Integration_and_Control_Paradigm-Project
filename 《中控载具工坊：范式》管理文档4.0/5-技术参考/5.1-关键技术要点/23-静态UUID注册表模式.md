# 二十三、静态 UUID 注册表模式

### 用途
网络包接收端（服务端）需要从 SubLevel UUID 反查拥有它的方块实体。由于 SubLevel 和方块实体之间没有直接关联，通过静态 Map 建立间接索引。

### 模式

```java
public class TurretBaseBlockEntity extends SmartBlockEntity {
    // 注册表
    private static final Map<UUID, BlockPos> GRINDSTONE_OWNER_MAP = new HashMap<>();
    private static final Map<UUID, BlockPos> ROD_OWNER_MAP = new HashMap<>();

    // 注册（在 assemble() 中）
    GRINDSTONE_OWNER_MAP.put(subLevel.getUniqueId(), this.worldPosition);

    // 查询（在网络包 handler 中）
    BlockPos pos = TurretBaseBlockEntity.findOwnerByGrindstoneUUID(uuid);
    if (pos != null && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity turret) {
        turret.setGrindstoneFacing(dir);
    }

    // 清理（在 disassemble() 中）
    GRINDSTONE_OWNER_MAP.remove(this.grindstoneSubLevelId);
}
```

### 适用场景
- 网络包需要从 SubLevel UUID 找到"拥有者"方块实体
- 服务端与客户端均可访问（但只有服务端写，客户端通过 NBT/S2C 获取）
