# Feature Overview

> Complete list of all features organized by subsystem.

## 1. Mounting System (3C Control: Cockpit, Camera, Control)

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 1 | Seat Block | ✅ | Initial vehicle core prototype (half-slab) |
| 2 | Cockpit Block | ✅ | Dual-block structure (cauldron bottom + scaffold top) |
| 3 | Mount/Dismount | ✅ | Press F, broad raycast detection |
| 4 | Occupancy System | ✅ | One player per SubLevel, one cockpit per structure |
| 5 | Broad Raycast | ✅ | Hit any block in SubLevel to mount |
| 6 | Orbital Camera | ✅ | Sphere-orbit around SubLevel focus, gimbal-lock fixed |
| 7 | Adaptive Camera | ✅ | Auto-adjust distance & height based on vehicle bounds |
| 8 | Safe Dismount | ✅ | F-key/disconnect/death/teleport — all handled |
| 9 | Player Hiding | ✅ | Complete player model/particle hiding while mounted |

### Comparison: SuspensionTestBlock vs Offroad WheelMountBlock

| Feature | WheelMountBlock (Offroad) | SuspensionTestBlock (IAC-P) |
|---------|--------------------------|------------------------------|
| Power input | Requires Create RPM network | ❌ None — powered by cockpit powertrain |
| Redstone steering | Differential redstone signal | ❌ None |
| UI tuning | Scroll-wheel suspension strength | ❌ None (compile-time constants) |
| Wheel attach | Any face | ✅ Facing direction only |
| Suspension tuning | Runtime scroll wheel | ✅ Compile-time constants |
| Visual offset | Fixed hardcoded | ✅ 19 compile-time values in 4 groups |
| Model | Textured | ✅ Fully transparent (`block/air`) |
| Base class | `KineticBlockEntity` (Create) | ✅ `SmartBlockEntity` (lightweight) |

---

## 2. Suspension & Vehicle Control

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 10 | Suspension Test Block | ✅ | Independent suspension + wheel physics test block. Built on top of Offroad's tire data components (`TireLike`). See comparison with Offroad WheelMountBlock below. |
| 11 | Friction Circle Model | ✅ | Shared friction budget for longitudinal & lateral forces |
| 12 | Handbrake (v3) | ✅ | Wheel lock + pure sliding friction, ~0.35g deceleration |
| 13 | Vehicle Control System | ✅ | C key GUI, 5 configurable keybindings per block |
| 14 | Dynamic Load Transfer | ✅ | Weight shift during acceleration/braking/turning |
| 15 | Brush Tire Lateral Slip | ✅ | Peak slip angle ~4.5°, grip collapse simulates drift |
| 16 | Tire Physics System | ✅ | Pressure/width/stiffness/rolling resistance; runtime params simplified to compile-time constants in 06-09. Tire physics model (pressure, burst detection, rolling resistance) is IAC-P's own implementation; wheel items and data components (`TireLike`) come from Offroad. |
| 17 | Quadratic Drag | ✅ | DRAG_COEFFICIENT=0.0045, top speed ~120 km/h |
| 18 | MIN_IMPULSE Rationalization | ✅ | 500→30, friction budget from 45g to 2.75g |
| 19 | Rolling Resistance Cleanup | ✅ | Removed `/0.4` backward-compat relic |
| 20 | WASD Smart Mapping | 🔄 | Multi-wheel coordinated input abstraction (in progress) |

## 3. Powertrain

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 21 | Engine Model | ✅ | Idle 800 / Redline 6,000 RPM, mass-adaptive torque |
| 22 | Transmission | ✅ | 5 forward + R reverse + N neutral, ratios 4.0/2.5/1.6/1.2/1.0/-3.5 |
| 23 | Gear Shift | ✅ | Q up / E down, rising-edge detection, RPM sync on shift |
| 24 | Differential | ✅ | Equal torque distribution to all wheels |
| 25 | Fallback Mode | ✅ | No cockpit → fixed RPM/Torque (400/80) |
| 26 | Mass-Adaptive Torque | ✅ | `effectiveTorque = mass × g × 0.02` |
| 27 | Continuous Throttle | ✅ | 0.0~1.0 throttle, triple decay profile |
| 28 | Load-Balanced Engine | ✅ | `loadFactor = force demand / max force` |
| 29 | Torque Curve | ✅ | RPM-dependent correction, peak ~3,400 RPM |
| 30 | Engine-Wheel Coupling | ✅ | engineRPM = max(wheelRPM × gear × finalDrive, idle) |
| 31 | Final Drive Ratio | ✅ | FINAL_DRIVE_RATIO=14.0 |

## 4. Turret & Weapon System

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 32 | Turret Base Block | ✅ | Carpet-shaped, generates grindstone + lightning rod SubLevels |
| 33 | Anchor Point System | ✅ | Configurable X/Y/Z local coordinates |
| 34 | Grindstone Config Screen | ✅ | C key GUI for orientation & anchor editing |
| 35 | Grindstone Config Packet | ✅ | C→S orientation update |
| 36 | Anchor Config Packet | ✅ | C→S anchor coordinate update |
| 37 | Anchor Data Packet | ✅ | S→C anchor + axis line world positions |
| 38 | Axis Line Rendering | ✅ | RGB axes per SubLevel + per-carpet focus crosshairs |
| 39 | Rotary Joint | ✅ | RotaryConstraint linking grindstone ↔ vehicle |
| 40 | Weapon System | ✅ | 1000-block raycast, barrel-origin damage, multi-turret |
| 41 | Auto-Tracking | ✅ | Continuous client-tick raycast → TurretTargetC2SPacket |
| 42 | Turret Kinetic Block | ✅ | KineticBlock + ICogWheel for Create stress network |
| 43 | Position-Mode PD Servo | ✅ | SwivelBearing-style position-mode, stable tracking |
| 44 | Absolute Angle Aiming | ✅ | AimController computes absolute target angle, no drift |
| 45 | Yaw Calibration Offset | ✅ | Configurable -180~180° offset |
| 46 | Vehicle-Local Stabilization | ✅ | Target direction transformed to vehicle local space |
| 47 | Pitch PD Servo | ✅ | Position-mode, kP=6000/kD=400 for gravity compensation |
| 48 | Barrel Damage Ray | ✅ | Ray from lightning rod pose, independent of camera aim |
| 49 | Bullet Trail Rendering | ✅ | White line barrel→hit point, zero SubLevel scan |
| 50 | Hold-to-Fire | ✅ | Left mouse down, 3-tick cooldown |
| 51 | Bypass Immunity Frames | ✅ | `entity.invulnerableTime = 0` before hurt |

## 5. Debugging & Monitoring

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 52 | Debug Overlay | ✅ | F3-style HUD (weight, RPM, torque, gear, speed, friction) |
| 53 | Mass Sync | ✅ | vehicleMass via MountedStateS2CPacket |
| 54 | Network System | ✅ | CustomPacketPayload C→S / S→C |
| 55 | Debug Gear Block | ✅ | Small gear, N key toggles RPM printout |

## 6. Infrastructure

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 56 | SubLevel Chunk Scan | ✅ | Using `plot.getLoadedChunks()` for internal block access |
| 57 | Build Dependency Extraction | ✅ | Auto-extract nested JARs from Create/Simulated |

## Design Philosophy

**Physics isn't just visual — it's playable.**

Most mods:
- Animate suspension compression → Our springs *actually compress* (code has spring constants)
- Show "50 km/h" on HUD → That's `linearVelocity.length() × 3.6` we computed
- Play "wheel flew off" particles → In future, the wheel *physically* flies off

**Components matter more than the whole.**

Each block is an independent component. Vehicle performance depends on component arrangement, not just data values.
