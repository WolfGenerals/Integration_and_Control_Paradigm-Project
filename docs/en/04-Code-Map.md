# Code Map

> Quick reference for finding the right file.

## Entry Points

| File | Purpose |
|------|---------|
| `src/main/java/.../IACP.java` | Main mod class — registration, event bus setup |
| `src/main/java/.../IACPClient.java` | Client-side setup |
| `src/main/java/.../Config.java` | Mod configuration |

## Networking

| File | Direction | Purpose |
|------|:---------:|---------|
| `.../ModNetworking.java` | — | Payload registration |
| `.../SeatMountC2SPacket.java` | C→S | Mount/dismount request |
| `.../MountedStateS2CPacket.java` | S→C | Mount state + SubLevel UUID + vehicle mass |
| `.../VehicleControlC2SPacket.java` | C→S | WASD/brake control input |
| `.../GearShiftC2SPacket.java` | C→S | Shift up/down request |
| `.../TurretTargetC2SPacket.java` | C→S | Turret aim target coordinates |
| `.../WeaponFireC2SPacket.java` | C→S | Weapon fire hit position + damage |
| `.../AnchorConfigC2SPacket.java` | C→S | Anchor point coordinate update |
| `.../AnchorDataS2CPacket.java` | S→C | Anchor + axis world position data |
| `.../GrindstoneConfigC2SPacket.java` | C→S | Grindstone/rod orientation config |
| `.../TireConfigC2SPacket.java` | C→S | Tire pressure config |

## Client (Rendering & Input)

| File | Purpose |
|------|---------|
| `.../client/ClientMountHandler.java` | Client-side mount state management |
| `.../client/ClientEvents.java` | Key input, raycast, fire control, player hiding |
| `.../client/WeaponOverlay.java` | Crosshair rendering, raycast system (aim + damage) |
| `.../client/VehicleDebugOverlay.java` | In-game F3-style debug HUD |
| `.../client/renderer/BulletTrailRenderer.java` | White line fire trail rendering |
| `.../client/renderer/AxisLineRenderer.java` | Turret axis debug rendering |
| `.../client/screen/VehicleKeyConfigScreen.java` | Keybinding config GUI |
| `.../client/screen/GrindstoneConfigScreen.java` | Grindstone orientation & anchor config GUI |
| `.../mixin/CameraMixin.java` | Orbital camera override |

## Blocks & Block Entities

| File | Purpose |
|------|---------|
| `.../content/blocks/seat/SeatBlock.java` | Original vehicle core (legacy) |
| `.../content/blocks/cockpit/CockpitBlock.java` | Cockpit dual-block structure |
| `.../content/blocks/cockpit/CockpitBlockEntity.java` | Cockpit tick logic |
| `.../content/blocks/suspension_test/SuspensionTestBlock.java` | Suspension + wheel test block |
| `.../content/blocks/suspension_test/SuspensionTestBlockEntity.java` | Suspension physics tick |
| `.../content/blocks/turret/TurretBaseBlock.java` | Turret base (KineticBlock + ICogWheel) |
| `.../content/blocks/turret/TurretBaseBlockEntity.java` | Turret yaw servo, pitch servo |
| `.../content/blocks/turret/TurretAimController.java` | Auto-aim logic (server-side) |
| `.../content/blocks/debug_gear/DebugGearBlock.java` | Debug gear for RPM diagnosis |
| `.../content/blocks/debug_gear/DebugGearBlockEntity.java` | Debug gear RPM printout |

## Server Events

| File | Purpose |
|------|---------|
| `.../events/PlayerMountTracker.java` | Mount state tracking, `tick()` for aim controller |
| `.../events/ServerMountHandler.java` | Server-side mount logic |
| `.../events/MountedProtectionHandler.java` | Damage immunity & aggro suppression |

## Index / Registration

| File | Purpose |
|------|---------|
| `.../index/ModBlocks.java` | Block registration |
| `.../index/ModItems.java` | Item registration |
| `.../index/ModEntities.java` | Entity registration |
| `.../index/ModBlockEntityTypes.java` | Block entity registration |
| `.../index/ModCreativeModeTabs.java` | Creative tab registration |
