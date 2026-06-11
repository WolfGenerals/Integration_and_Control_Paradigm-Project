# Getting Started Guide

## Prerequisites

- Java 21 (JDK)
- Git
- A supported IDE (IntelliJ IDEA recommended)

## Setting Up the Development Environment

### 1. Clone the Repository

```bash
git clone https://github.com/HaiNaBaiChuan75/IAC-P.git
cd IAC-P
```

### 2. First Build

The project depends on Create Simulated and its nested JAR dependencies. On first build, these need to be extracted:

```bash
# On Linux/macOS
./gradlew runClient

# On Windows
gradlew runClient
```

This will:
1. Download all dependencies
2. Extract nested JARs (sable-companion, etc.) from the Simulated mod JAR
3. Launch the game client

If you only want to compile without launching:

```bash
gradlew compileJava
```

### 3. Importing into Your IDE

#### IntelliJ IDEA
```bash
gradlew idea
```
Then open the project folder.

#### VS Code / Eclipse
```bash
gradlew eclipse
```
Then import the project.

## Project Structure

```
IAC-P/
├── src/
│   ├── main/
│   │   ├── java/           # Source code
│   │   └── resources/      # Assets (textures, models, etc.)
│   └── generated/          # Data-generated resources
├── docs/                   # English documentation
├── 《中控载具工坊：范式》.../ # Chinese documentation (more comprehensive)
├── build.gradle            # Build configuration
├── gradle.properties       # Mod metadata & version
├── settings.gradle         # Gradle settings
├── gradlew / gradlew.bat   # Gradle wrapper
└── run/                    # Game instances (created at runtime)
```

## Common Gradle Tasks

| Task | Description |
|------|-------------|
| `gradlew runClient` | Launch the game client (with hot-reload for resource changes) |
| `gradlew runServer` | Launch a dedicated server |
| `gradlew compileJava` | Compile Java sources |
| `gradlew build` | Build the mod JAR (output in `build/libs/`) |
| `gradlew runData` | Run data generators |
| `gradlew test` | Run game tests |
| `gradlew clean` | Clean build artifacts |

## First-Time In-Game Test

After launching the client:

1. **Creative inventory** → find the IAC-P tab
2. Place a **Seat** block → your first "vehicle core"!
3. Right-click with a **Suspension Test Block** nearby
4. Press **F** to mount
5. Use **WASD** (if you configured keys) or try the default controls

See the [Feature Overview](02-Feature-Overview.md) for a complete list of features.

## Troubleshooting

### "Cannot access SubLevelAccess" compilation error

The sable-companion JAR was not extracted. Run `gradlew runClient` once to trigger extraction,
or manually extract it from the Simulated JAR in `libs/`.

### Missing Flywheel/Ponder classes

Nested JARs from Create were not extracted. Same solution as above.

### IDE cannot find symbols

Run `gradlew --refresh-dependencies` to refresh the local cache.

### Game crashes on launch

Check the crash report in `run/crash-reports/`. Common causes:
- Outdated NeoForge version
- Conflicting mods in the `mods/` folder

For more issues, see the [Troubleshooting Guide](05-Troubleshooting.md).
