# Troubleshooting Guide

> Known issues, root causes, and fixes — indexed by symptom.

## Compilation & Setup

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| `SubLevelAccess` class not found | sable-companion JAR not extracted | Run `gradlew runClient` once, or manually extract from Simulated JAR |
| Flywheel/Ponder classes missing | Nested JARs from Create not extracted | Same as above |
| IDE cannot find Create classes | Dependencies not downloaded | `gradlew --refresh-dependencies` |

## Physics & Controls

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| Vehicle won't move | Speed unit mismatch (20x off) | Remove `/20.0` in physics tick speed conversion (already fixed) |
| Brake pins you to ground (18g) | `brakeMag` used 51x inflated `frictionBasis` | Use `springImpulse` as real normal force (already fixed v3) |
| RPM won't drop after releasing W | `putIfAbsent(false)` doesn't overwrite | Direct SubLevel scan instead of shared Map (already fixed) |
| Vehicle keeps accelerating without input | Throttle doesn't decay | Add THROTTLE_DECAY (already fixed 06-08) |
| Heavy vehicles feel sluggish | Rolling resistance/阻尼 used `sm` (cap 200) | Use `nm` for mass-scaled values (already fixed) |
| Vehicle shakes when turning | `tan(α)` diverges at large slip angles | Clamp slip angle ±45°, totalSpeed > 1.0 threshold (already fixed) |
| Vehicle spins in place while parked | `forwardSpeedAbs > 0.5` threshold too low | `totalSpeed > 1.0`, pure damping at low speed (already fixed) |
| Friction budget always ~17% | Display uses 500x inflated `frictionBudget` | Use natural budget: `max(springImpulse, nm×dt×20)` (already fixed) |

## Mounting & Camera

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| Screen shakes when mounted | `teleportTo()` triggers position sync | Use `setPos()` every tick (already fixed) |
| Camera yaw flips at extreme pitch | Gimbal lock: `horizontalDist=0` → `atan2(0,0)=0` | Use `entity.getYRot()` when `dist < 1e-4` (already fixed) |
| Player model visible when mounted | Model rotation conflicts with camera yaw | Cancel RenderPlayerEvent entirely (already fixed) |
| State not restored after reconnect | NBT leftovers on entity join | Clean up in EntityJoinLevelEvent (already fixed) |

## Turret & Weapons

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| Turret points wrong direction (180° off) | atan2 CCW+ ≠ RotaryConstraint CW+ | Negate angle: `-(currentYawRad + yawErr)` (already fixed) |
| Turret oscillates near target | Abstract gear mode: RPM step + PD = double integrator | Use position-mode PD servo (already fixed) |
| `setMotor` stops responding after 1st call | SmartBlockEntity lacks RPM awareness | Use KineticBlockEntity (already fixed) |
| Velocity mode doesn't rotate | RotaryConstraint doesn't support velocity mode | Always use position mode (already fixed) |
| Raycast always hits own vehicle | Camera is inside vehicle AABB | Skip mounted SubLevel UUID (already fixed) |
| Bullet trail ends at entity's feet | `entityHit.getLocation()` returns feet position | Use `AABB.clip()` for precise hit point (already fixed 06-11) |
| Plants/leaves block aiming ray | `ClipContext.OUTLINE` detects all blocks | Use `COLLIDER` + leaf penetration loop (already fixed 06-11) |

## Common Configuration

| Parameter | Location | Description |
|-----------|----------|-------------|
| `TURRET_YAW_OFFSET` | Config.java `[turret.aim]` | Yaw calibration, range -180~180°, default 0° |
| `TORQUE_WEIGHT_RATIO` | EngineModel.java | 0.02, mass-adaptive torque multiplier |
| `FINAL_DRIVE_RATIO` | EngineModel.java | 14.0, overall gear reduction |
| `DRAG_COEFFICIENT` | EngineModel.java | 0.0045, quadratic drag, limits top speed |
