# 七、质量 API

```java
// ❌ 错误：方向有效质量，不是总质量
double mass = rigidBody.getInverseNormalMass(center, up);

// ✅ 正确：真实总质量
double mass = massData.getMass();
```

`getInverseNormalMass(center, up)` 返回刚体在**特定接触点**和**特定法线方向**上的等效质量。不同方向的值不同。
