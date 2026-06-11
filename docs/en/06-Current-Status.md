# Current Status & Roadmap

> Last updated: 2026-06-12 (WASD Smart Mapping Complete)

## ✅ Completed Features

**87 features completed** across all subsystems:

| Category | Count | Status |
| ---------- | :----: | :-----: |
| Mounting System | 9 | ✅ |
| Suspension & Control | 11 | ✅ |
| Powertrain | 11 | ✅ |
| Turret & Weapon System | 20 | ✅ |
| Debugging & Monitoring | 4 | ✅ |
| Smart Mapping System | 9 | ✅ |
| Infrastructure | 2 | ✅ |
| Internationalization (i18n) | 7 | ✅ |
| Open Source Preparation | 5 | ✅ |
| **Total** | **78 (functional)** | **78 ✅** |

## 🐛 Known & Fixed Bugs

All identified bugs have been fixed — see the Chinese documentation for the complete list.

## ⚠️ Active Issues

| Issue | Priority | Category | Notes |
| ------- | :------: | ---------- | ------- |
| Weight synced only once on mount | Low | Limitation | No resync on reconnect |
| No MassData API on client | Low | Limitation | Mass must come from server |
| Multiplayer camera untested | Low | TBD | Plan B1 camera not tested with >1 player |
| Build dependency extraction | Medium | Process | First build needs manual JAR extraction |
| SPRING_STIFFNESS_PER_NM=400 may be soft for heavy vehicles | Medium | Tuning | Heavy vehicles may bottom out |
| Tire types not differentiated | Medium | Design Gap | 4 tire types share same physics properties |
| Yaw offset needs in-game tuning | 🟡 | Tuning | Currently requires Config file edit + restart |

## 📋 Roadmap

### High Priority

| Feature | Description |
| --------- | ------------- |
| ~~WASD Smart Mapping~~ | ~~Multi-wheel coordinated input abstraction~~ — ✅ Completed 06-12 |
| **Part Damage System** | Sable auto-split + block-level HP |
| **Resource Pack Import System** | Community weapon FX / tire models / sounds |

### Medium Priority

| Feature | Description |
| --------- | ------------- |
| Tire Squeal Particles | Smoke/skid marks when friction saturates |
| Smart Transmission | Auto-adjust gear ratios |
| Vehicle Status HUD | Polished HUD replacing debug overlay |

### Low Priority

| Feature | Description |
| --------- | ------------- |
| Cockpit Break Protection | Indestructible while occupied |
| In-Game Yaw Calibration | GUI slider instead of config file edit |
| Tire Type Registry | Different physics for off-road / racing / heavy tires |

## Paused

| Item | Reason | Resume Condition |
| ---- | ------ | ---------------- |
| SubLevel Physical Scaling | Sable Rust-side scaling incomplete | Upstream `Pose3d.scale` → Rapier native support |

## Design Direction

**Blocky car route** — Not waiting for Sable scaling. Build playable vehicles at Minecraft block scale.

**Asset strategy** — Don't make official FX/sound/texture packs. Build an import framework and let the community contribute.

See `5-技术参考/5.4-设计哲学与路线图/` (Chinese) for the full design philosophy.
