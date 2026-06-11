# Contributing to Integration and Control : Paradigm

First off, thanks for taking the time to contribute! 🎉

## Code of Conduct

This project follows a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.

## How Can I Contribute?

### 🐛 Reporting Bugs

Before filing a bug report:
1. **Check the [troubleshooting guide](docs/en/05-Troubleshooting.md)** — it might be a known issue
2. **Search existing issues** — someone might already have reported it
3. **Use a clear title** — "Turret doesn't track targets when vehicle is pitched" is better than "weapon bug"

When filing, include:
- Minecraft version, mod version, NeoForge version
- Steps to reproduce (be specific!)
- What you expected to happen vs. what actually happened
- Crash log (if applicable) — paste to a [gist](https://gist.github.com/) and link it
- Screenshots or videos (very helpful for visual/rendering bugs)

### 💡 Suggesting Features

Feature suggestions are welcome, but please understand that IAC-P has a specific design philosophy. Before suggesting:
- Read the [design philosophy](docs/en/02-Feature-Overview.md#design-philosophy)
- Check if the feature fits the project's scope

Good feature suggestions include:
- **What problem** you're trying to solve
- **How you envision** the feature working
- Why it fits IAC-P's approach

### 🧪 Submitting Code Changes

#### Getting Started

1. Fork the repository
2. Create a branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Test thoroughly
5. Commit with a clear message
6. Push and open a Pull Request

#### Code Style

- Follow the existing code style (indentation, naming conventions, etc.)
- Use meaningful variable names — `entityHit` is good, `e` is not
- Write Javadoc for public methods
- Keep methods focused and reasonably sized

#### Architecture Notes

Please read these before making significant changes:
- [SubLevel Physics Architecture](docs/en/03-SubLevel-Physics.md) — understand how the physics works
- [Code Map](docs/en/04-Code-Map.md) — find the right file to modify
- [Key Technical Points](docs/en/05-Troubleshooting.md) — avoid known pitfalls

#### What Makes a Good PR

- **Small and focused** — one feature/fix per PR. A 500-line PR is harder to review than five 100-line PRs.
- **Tested** — describe how you tested the change
- **Documented** — update relevant docs if your change affects usage
- **No formatting-only changes** — don't mix re-formatting with actual changes

#### What to Avoid

- Large refactors without prior discussion
- Adding dependencies without justification
- Breaking existing functionality without clear migration path
- Changes that contradict the project's design philosophy

## Review Process

1. Maintainer reviews the PR
2. Feedback may be given — please engage in discussion
3. Once approved, the PR will be merged

> **Note**: This is a small project. Reviews may take time — please be patient!

## Questions?

Open a [Discussion](https://github.com/HaiNaBaiChuan75/IAC-P/discussions) or an Issue.
