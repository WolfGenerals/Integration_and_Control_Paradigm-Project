# 二十四、S2C 数据推送 vs NBT 同步

### 问题
`SmartBlockEntity.sendData()` 的 NBT 同步机制在复杂场景下不可靠：
- `clientPacket` 标志位在客户端不一定为 `true`
- 时序不可控——同步包可能在界面打开后才到达
- 不适合大数据量（如 12 个 double 的线条数据）

### 推荐方案：专用 S2C 数据包

```java
// 服务端发送
AnchorDataS2CPacket packet = new AnchorDataS2CPacket(uuid, ax, ay, az, lineData);
PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), packet);

// 客户端接收 → 直接写入静态缓存
TurretBaseBlockEntity.getAnchorMap().put(uuid, new double[]{ax, ay, az});
TurretBaseBlockEntity.getLineCache().put(uuid, lineData);
```

### 手动编解码（超过 8 字段时）
```java
public static final StreamCodec<ByteBuf, MyPacket> CODEC = new StreamCodec<>() {
    @Override public MyPacket decode(ByteBuf buf) {
        UUID uuid = new UUID(buf.readLong(), buf.readLong());
        double[] data = new double[12];
        for (int i = 0; i < 12; i++) data[i] = buf.readDouble();
        return new MyPacket(uuid, data);
    }
    @Override public void encode(ByteBuf buf, MyPacket p) {
        buf.writeLong(p.uuid.getMostSignificantBits());
        buf.writeLong(p.uuid.getLeastSignificantBits());
        for (int i = 0; i < 12; i++) buf.writeDouble(p.lineData[i]);
    }
};
```
