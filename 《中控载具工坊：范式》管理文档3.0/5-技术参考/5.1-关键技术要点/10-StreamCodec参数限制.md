# 十、StreamCodec 参数限制

`StreamCodec.composite()` 最多支持 **8 个字段**。超过时需手动编解码：

```java
public static final StreamCodec<FriendlyByteBuf, MyPacket> CODEC = new StreamCodec<>() {
    @Override public MyPacket decode(FriendlyByteBuf buf) { /* 手动读取 */ }
    @Override public void encode(FriendlyByteBuf buf, MyPacket p) { /* 手动写入 */ }
};
```
