# BuildCore

All-inclusive OSRS account builder foundation for VitalClient.

**Status:** Foundation phase — framework only. No activity modules (quests, skills, etc.) yet; those ship as separate subprojects in later phases.

## Quick start

Prerequisites (from `../VitalShell` and `../VitalAPI`):

```bash
cd ../VitalShell && ./gradlew publishRuneliteApiPublicationToMavenLocal
cd ../VitalShell && ./gradlew :client:publishRunelite-clientPublicationToMavenLocal
cd ../VitalAPI   && ./gradlew publishToMavenLocal
```

Build:

```bash
cd ../                         # back to VitalPlugins root
./gradlew :BuildCore:build
```

The resulting JAR lands at:
- `BuildCore/build/libs/BuildCore-0.1.0.jar`
- `~/.vitalclient/sideloaded-plugins/BuildCore-0.1.0.jar` (auto-deployed)

## What's inside

See [CLAUDE.md](./CLAUDE.md) for the package layout, build system, and architecture overview. The full design is in [docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md).

## Contributing

Private project — not accepting external contributions at this time. See the project owner for access.

## License

BSD 2-Clause License (matches VitalPlugins root).
