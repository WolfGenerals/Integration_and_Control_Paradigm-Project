# Integration and Control : Paradigm (IAC-P)

> A **glue layer** that turns [Create Simulated](https://github.com/ryanhcode/sable) physical structures into drivable, fightable vehicles.
>
> Minecraft 1.21.1 + NeoForge.

[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.230-orange)

---

> 🚀 **New? Start here → [Quick Start Guide](docs/en/00-Quick-Start.md)** — get driving in 3 minutes.

---

## What is IAC-P?

IAC-P is a **glue mod** — it adds vehicle controls, weapons, and HUD on top of the physics and mechanical blocks already provided by [Create Simulated](https://github.com/ryanhcode/sable).

The heavy lifting comes from our dependencies. What we do is **connect them into a playable vehicle experience**.

### Tech stack

| Layer | Handled by |
| ------- | ----------- |
| Physics engine (Rapier rigid-body simulation) | [Sable](https://github.com/ryanhcode/sable) |
| Physical blocks (wheels, suspensions, drills) | Create Offroad / Create Aeronautics |
| Mechanical power (RPM network, gearboxes) | [Create](https://github.com/Creators-of-Create/Create) |
| SubLevel hosting (in-world physics pockets) | [Sable](https://github.com/ryanhcode/sable) |
| **Vehicle controls, weapons, HUD — the glue** | **IAC-P (this mod)** |

### On top of existing physics blocks, we added…

- **Mount/dismount system**\* (F key, player hiding while seated, occupancy management)
- **Universal Cockpit** (two-block structure integrity check)
- **Orbital camera** with adaptive height/distance (tracks SubLevel pose via Sable API)
- **Keybinding config GUI** (C key, per-vehicle key remapping + tire pressure input)

    > \* Player mounting within SubLevels is handled by Simulated-Project. IAC-P provides the mount/dismount triggers, player hiding, occupancy management, and state synchronization.
    > \* Honey Glue and Physical Assembler are provided by **Simulated-Project**. IAC-P interacts with them for vehicle assembly and cockpit placement within physical structures.

### On top of Create RPM network and Offroad tire data components, we built our own…

> ⚠ The wheel items and tire data component (`TireLike` interface) are provided by Offroad. The physics simulation below — suspension springs, friction circle, brush tire model, load transfer — is our own implementation built on top of Offroad's tire data.

- **Full powertrain controller**: engine model with mass-adaptive torque, continuous throttle, torque curve (peak ~3,400 RPM), 5-speed + reverse transmission, load-balanced engine coupling, differential torque distribution
- **Handbrake v3**: wheel lock + pure sliding friction (~0.35g)
- **Dynamic load transfer**: weight shift during acceleration/braking/turning
- **Brush tire lateral slip model**: peak slip angle ~4.5°, grip collapse simulates drift
- **Tire physics**: pressure sensitivity, carcass stiffness, rolling resistance, burst detection

### On top of Sable constraints and SubLevels, we added…

- **Turret system**: TurretBase block spawns a physical grindstone + lightning rod as two SubLevels, linked by a RotaryConstraint for yaw and a GenericConstraint for pitch. Anchor point config via GUI. RGB axis line rendering.
- **Weapon system**: auto-aim with vehicle-local coordinate stabilization. Barrel-origin damage ray (independent of camera aim). Multi-turret support. Hold-to-fire, bypasses immunity frames. Bullet trail rendering.

### On top of NeoForge, we added…

- **Debug overlay** (F3-style HUD: weight, RPM, torque, gear, speed, friction budget)
- **Debug Gear block** (small gear that prints RPM per tick for Create network diagnosis)
- **Full i18n** (Chinese + English, 3-level NeoForge config GUI, all screens and messages translatable)

### Design Philosophy

**IAC-P is bridgeware.** The physics engine already exists. The physical blocks already exist. What was missing was the **coordination** — a layer that says "when the player presses W, here's how the engine torque gets distributed to each wheel, accounting for load transfer, gear ratio, and the tire's current slip angle."

**Physics isn't just visual — it's playable.** Our springs *actually compress* — no animations, just real spring forces. When we say "50 km/h", that's `linearVelocity.length() × 3.6` computed for you.

**Components matter more than the whole.** Knock off a wheel and your vehicle handles differently, not because of a data value, but because the physical connection is gone.

---

## Quick Start

```bash
gradlew runClient       # Launch game client
gradlew runServer       # Launch dedicated server
gradlew compileJava     # Compile only
gradlew build           # Build mod JAR
```

### In-Game Controls

| Key | Action |
| :---: | -------- |
| F | Mount / Dismount |
| C | Vehicle keybinding config / Turret anchor config |
| Q / E | Shift up / Shift down |
| Left Mouse | Fire weapons (hold for auto-fire) |
| N | Toggle Debug Gear output |

---

## Dependencies

| Dependency | Version | Notes |
| ----------- | --------- | ------- |
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.230 | |
| Create | 6.x | |
| Simulated (Sable) | [1.0, 2.0) | Core physics framework |
| Aeronautics | (in Simulated) | Propellers, balloons |
| Offroad | (in Simulated) | Wheels, drills |

### First-Time Build

```bash
gradlew runClient
```

The first launch auto-extracts nested JAR dependencies. See [Getting Started Guide](docs/en/01-Getting-Started.md) for details.

---

## Documentation

| Document | Audience |
| ---------- | ---------- |
| [Feature Overview](docs/en/02-Feature-Overview.md) | Everyone |
| [Getting Started Guide](docs/en/01-Getting-Started.md) | New contributors |
| [SubLevel Physics Architecture](docs/en/03-SubLevel-Physics.md) | Developers |
| [Code Map](docs/en/04-Code-Map.md) | Contributors |
| [Troubleshooting](docs/en/05-Troubleshooting.md) | Everyone |
| [Current Status & Roadmap](docs/en/06-Current-Status.md) | Everyone |

> **Chinese documentation (comprehensive)**: `《中控载具工坊：范式》项目推进文档3.0/` — includes 29 technical deep-dives, complete development history, design philosophy, and performance analysis. Machine-translation friendly.

---

## License

**LGPL-3.0-only** — See [LICENSE](LICENSE) for details.

- ✅ Use in modpacks
- ✅ Modify and distribute (source must stay open)
- ✅ Use as a library
- ❌ No additional restrictions

---

## Contributing

Contributions welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

Good ways to help: bug reports, feature suggestions, code PRs, documentation & translations.

---

## Acknowledgements

- **Create** — mechanical power network (RPM, gearboxes, stress system)
- **Sable (Simulated-Project)** — Rapier physics engine, SubLevel system, constraint API
- **Offroad (Simulated-Project)** — wheel items, tire data components (`TireLike`)
- **Aeronautics (Simulated-Project)** — propeller, balloon, and levitite systems
- **Copilot(DeepSeek-V4)** — Code Implementation Guide
- NeoForged modding framework
- Inspired by *Crossout* and *Besiege*

*Building vehicles, one constraint at a time.*
