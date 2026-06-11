# SubLevel Physics Architecture

## Core Concept

IAC-P uses **Create Simulated's SubLevel system** — each vehicle lives in its own isolated physics world (SubLevel) that runs on the Sable physics engine (Rust via JNI), while being rendered seamlessly in the main Minecraft world.

```
Minecraft World (main level)
├── Player Entity (mounted)
└── Vehicle SubLevel (client + server)
    ├── CockpitBlock       — Mounting point
    ├── SuspensionBlocks   — Individual wheel/suspension physics
    ├── TurretBaseBlock    — Turret anchor
    │   ├── Grindstone SubLevel (rotates = yaw)
    │   │   └── LightningRod SubLevel (pitches)
    │   └── RotaryConstraint — Vehicle ↔ grindstone
    └── Blocks as rigid bodies — Connected by constraints
```

## Key Principle: Everything is a SubLevel

- The **vehicle itself** is a SubLevel
- The **turret's rotating base** (grindstone) is a SubLevel connected by RotaryConstraint
- The **turret's barrel** (lightning rod) is a SubLevel connected by GenericConstraint

This nested SubLevel approach enables:
- Independent physics for each part
- Realistic constraint chains (vehicle → turret swivel → barrel pitch)
- Future component damage (destroy a constraint → part flies off)

## Physics Flow

```
Server Physics Tick (20 Hz)
  └── PlayerMountTracker.tick()
       ├── VehicleControlSystem — Apply WASD input forces
       ├── EngineModel — Compute RPM & torque
       ├── Transmission — Gear ratios & torque multiplication
       ├── Suspension — Spring forces, load transfer
       ├── TireModel — Friction circle, lateral slip
       └── TurretAimController — Yaw & pitch servo updates

Client Render Tick (variable)
  └── OrbitalCamera — Follow SubLevel.renderPose() with smooth interpolation
  └── BulletTrailRenderer — Draw cached fire trails
  └── AxisLineRenderer — Debug axes (F3+B toggle)
```

## Coordinate Systems

This is the #1 source of bugs. Key rules:

1. **World space** — Absolute Minecraft coordinates. Used for: camera, raycasts, entity positions
2. **SubLevel local space** — Relative to SubLevel origin. The SubLevel's `logicalPose()` gives the transform.
3. **Constraint local space** — Each constraint has its own frame. Critical for multi-SubLevel chains.

### Bloody Lessons (from `5.1-关键技术要点/25. Sable约束坐标系.md`)

- RotaryConstraint positions must use **absolute block coordinates** (`getCenterBlock() + 0.5`), NOT world coordinates or relative coordinates
- When transforming between SubLevels, always use the full pose (position + orientation)
- Normal vectors for constraints are in **local space** — `(0,1,0)` means "this SubLevel's Y axis"
- The atan2 convention: math uses CCW+, but RotaryConstraint.setMotor uses CW+ → negate the angle

## Performance Considerations

- Each SubLevel adds ~1 physics solver overhead
- Multi-layer constraint chains are stable but increase solver iterations
- Turret auto-aim raycast runs every client tick — negligible cost
- Bullet trail rendering uses cached data (zero SubLevel scan per frame)

## Further Reading

- [Sable: SubLevel Documentation](https://github.com/ryanhcode/sable)
- Chinese (detailed): `《中控载具工坊：范式》项目推进文档3.0/3-架构与文件结构/3.1-SubLevel物理架构.md`
