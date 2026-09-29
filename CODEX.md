# SQL Migration Visualizer

See [AGENTS.md](AGENTS.md) for full project context, package map, data flow, bridge communication, threading rules, security rules, and commit checklist.

## Commands

```bash
./gradlew test          # run tests
./gradlew runIde        # launch sandbox IDE
./gradlew buildPlugin   # build distributable ZIP
```

## Key conventions

- Kotlin 2.0.21 + Java 17; kotlinx-serialization for all models
- Web UI is vanilla JS in `src/main/resources/web/` — no framework
- All Kotlin↔JS communication goes through `JcefBridge` as JSON
- No comments unless the WHY is non-obvious
- Prefer editing existing files over creating new ones
