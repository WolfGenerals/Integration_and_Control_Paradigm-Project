# CLAUDE.md
(update 2026/6/20)
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IAC-P is a **Minecraft 1.21.1 NeoForge mod** — a "glue layer" that adds vehicle controls, weapons, and HUD on top of the [Sable](https://github.com/ryanhcode/sable) physics engine (Rapier rigid-body simulation) and [Create](https://github.com/Creators-of-Create/Create) mechanical power network. It does not provide its own physics; it **coordinates** existing systems into a playable vehicle experience.

## Build Commands

```bash
./gradlew runClient       # Launch game client
./gradlew runClient_alt   # Launch game client
./gradlew runServer       # Launch dedicated server
./gradlew compileJava     # Compile only
./gradlew build           # Build mod JAR
```

- Java 21 required
- First launch extracts nested JAR dependencies automatically
- IDE runs: "Client", "Client 2" (alt username), "Server", "Data Generation"

## Key Dependencies

| Dependency | Gradle Property | Version |
|---|---|---|
| NeoForge | `neo_version` | 21.1.230 |
| Create | `create_version` | 6.0.10-280 |
| Sable | `sable_version` | 2.0.3 |
| Offroad | `offroad_version` | 1.3.0 |
| Simulated | `simulated_version` | 1.3.0 |
| Aeronautics | `aeronautics_version` | 1.3.0 |

All dependency versions are set in `gradle.properties`.

## Architecture

### Entry Points

- **`IACP.java`** — Server/common mod class (`@Mod`). Registers all DeferredRegisters (blocks, items, entities, BE types, sounds, creative tabs) and game-bus event handlers: `PlayerMountTracker`, `MountedProtectionHandler`, `SubLevelProjectileHandler`, `PartDamageCache`, `SablePostPhysicsTickEvent`, `AffiliationCommand`, `WorldLoadHandler`.
- **`IACPClient.java`** — Client-only mod class (`@Mod(dist = Dist.CLIENT)`). Registers key mappings, BlockEntity renderers, and game-bus handlers: `ClientMountGameHandler`, `ClientMountHandler`, `ClientEvents`, `VehicleDebugOverlay`, `WeaponOverlay`, `AxisLineRenderer`, `BulletTrailRenderer`.

### Core Systems

**Mount/Dismount (`PlayerMountTracker` + `ServerMountHandler`):**
- No entity riding — the player is teleported and pinned to a SubLevel world position each tick
- Mount flow: ray-trace 3 blocks → check hit is in a SubLevel → scan for cockpit structure (unique, complete) → check occupancy → pin player, disable physics
- Dismount: resets suspension inputs, chooses safe dismount position (ground near vehicle/handbrake, vehicle top/normal, or direct top/experimental)
- Position sync raised to physics tick rate (~100Hz) via `SablePostPhysicsTickEvent`

**Powertrain (`CockpitBlockEntity` + `EngineModel` + `TransmissionModel`):**
- Throttle-direct RPM model: RPM = IDLE + throttle × (MAX - IDLE), engine always runs independently
- Torque = ENGINE_TORQUE × torqueCurve(RPM) — pure RPM function, not throttle-attenuated
- 5-speed + reverse + neutral transmission: torque × gear ratio → torque per wheel
- Shift sequence: 6-tick power interruption, rev-match on downshift
- Auto-shift: detects speed/accel relative to gear-ideal speed, stalls prevention

**Tire/Suspension Physics (`SuspensionTestBlockEntity` + `TirePhysicsCalculator` + `BrushTireModel`):**
- Built on Sable's `BlockEntitySubLevelActor` — applies forces directly to Rapier rigid bodies
- Brush tire lateral slip model (peak slip angle ~4.5°)
- Dynamic load transfer during acceleration/braking/turning
- Rolling resistance, tire deflection, burst detection, pressure sensitivity
- Uses Offroad's `TireLike` data component for tire properties

**SubLevel Scanning (`SubLevelScanner`):**
- Central utility for iterating all blocks within a SubLevel's loaded chunks
- Eliminates repeated triple-nested loop boilerplate across the codebase
- Provides both full (BlockEntity-aware) and state-only variants

**Affiliation System (`AffiliationRegistry` + `ComponentRegistry`):**
- Runtime indexes (not persisted, rebuilt from NBT on world load) using ConcurrentHashMap
- `AffiliationRegistry`: SubLevel ownership — tracks which SubLevels belong to which vehicle, player-vehicle bindings, faction system. Used for ray-trace exclusion (don't shoot your own turret).
- `ComponentRegistry`: Functional component index — by SubLevel + ComponentRole (SUSPENSION, COCKPIT, TURRET_BASE, etc.). Enables O(1) queries instead of full SubLevel scans.
- `RayPolicy`/`RayType`: Policy-based ray interaction resolution — decides whether rays penetrate, damage, or ignore SubLevel targets based on affiliation

**Weapons System:**
- Turret: `TurretBaseBlock` spawns grindstone + lightning rod as two SubLevels linked by RotaryConstraint (yaw) and GenericConstraint (pitch). Auto-aim with vehicle-local coordinate stabilization.
- Machine Gun + Shotgun: Barrel-origin damage rays, multi-turret support, hold-to-fire, bypasses immunity frames. Bullet trail rendering on client.
- Aim controller uses immediate servo drive (packet handler → `driveImmediate()`) to eliminate one server tick of latency.

**Client Systems:**
- `CameraMixin`: Orbital camera — when mounted, positions camera on a sphere around the SubLevel focus point. Adaptive height/distance based on bounding box. Supports sentinel (frozen position) mode.
- `ClientEvents`: Key input handling — F (mount), C (config screens), N (debug), V (sentinel cam), WASD/arrow control forwarding to server, Q/E gear shift interception, hold-to-fire with raycast.
- `VehicleDebugOverlay`: F3-style HUD showing weight, RPM, torque, gear, speed, friction budget.

**Networking (`ModNetworking`):**
- NeoForge `PayloadRegistrar`-based custom packets. 14 packet types covering: mount, player input, gear shift, tire config, turret targeting, weapon fire, vehicle state sync, anchor config, smart map toggle.

### Config

- `Config.java` — Runtime `ModConfigSpec` (NeoForge 3-level GUI): camera settings, turret anchor offsets, machine gun calibration
- `IACPConfig.java` — Compile-time constants: SubLevel scale (0.33×, requires custom Sable fork — currently disabled)

### Mixins

- `CameraMixin` — only mixin. Injects at `Camera.setup()` TAIL to implement orbital/sentinel camera when player is mounted.

### Internationalization

Full i18n with Chinese (`zh_cn.json`) and English (`en_us.json`) at `assets/iac_p/lang/`.

## Development Notes

- There are no unit tests — testing is done by launching the client (`runClient`) and verifying in-game
- The mod requires all dependencies present at runtime (Create, Sable, Offroad, Simulated, Aeronautics)
- Chinese comments are pervasive; key architectural comments are bilingual
- `BlockEntity` types are split across multiple index classes (`ModBlockEntityTypes`, `ModCockpitBlockEntityTypes`, `ModDebugGearBlockEntityTypes`, etc.) rather than one monolithic registry
- When adding a new BlockEntity, add its type to the appropriate index class and register in `IACP.java`
- New packets need: a packet class, registration in `ModNetworking.register()`, and a handler method
