# BuildCore — CLAUDE.md

Subproject guidance for BuildCore within the VitalPlugins Gradle multi-project.

## What BuildCore is

All-inclusive OSRS account builder foundation for the VitalClient platform. See the design spec at [../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md) for the full architecture.

## Status

**Foundation phase — Plans 1 + 2 complete.**

Future plans in `../docs/superpowers/plans/`:
- Plan 2 — Core Runtime + Task SPI + Restrictions
- Plan 3 — Logging + Event Bus
- Plan 4 — Antiban + Input Layer
- Plan 5 — Action Library (L5 Services)
- Plan 6 — Confidence / Watchdog / Recovery
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell

## Build

```bash
# From VitalPlugins root
./gradlew :BuildCore:build                   # full build + tests
./gradlew :BuildCore:test                    # tests only
./gradlew :BuildCore:compileKotlin           # compile check
./gradlew :BuildCore:jar                     # build + deploy to ~/.vitalclient/sideloaded-plugins/
```

## Architecture

Follow the layered model in spec §4:
- L1 GUI Shell (`gui/`)
- L2 Profile/Plan (`core/config/`)
- L3 Task Runner (`core/task/`)
- L4 Task SPI (`core/task/`)
- L5 Services (`core/services/`)
- L6 Confidence/Watchdog (`core/watchdog/`)
- L7 Input (`core/antiban/`)
- L8 VitalAPI bridge (external)

Cross-cutting: `core/restrictions/`, `core/antiban/`, `core/events/`, `core/logging/`, `core/licensing/`.

## Key invariants (enforced by architecture tests)

Architecture tests live in `src/test/kotlin/net/vital/plugins/buildcore/arch/`. Each test cites the spec section it enforces. Adding a new rule = adding a new `@Test` in that directory.

Current invariants (Plan 2):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions (enforced at ModuleRegistry.register and by Method.validateStructure).
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet, additional cannot override archetype base.

Plan 3 onward adds many more. Never weaken an architecture test — extend it or add a new one.

## Code style

Matches the rest of VitalPlugins:
- **Tabs** for indentation (not spaces)
- Allman braces (opening brace on its own line)
- Always use braces on control flow
- UTF-8 files

Kotlin-specific:
- Prefer `val` over `var`
- Prefer immutable collections (`listOf`, `mapOf`)
- Prefer sealed classes/interfaces for closed taxonomies
- Data classes for value types; `object` for stateless singletons

## Testing

- Unit tests: JUnit 5 (Jupiter) + MockK
- Architecture tests: Konsist
- Coroutine tests: `kotlinx-coroutines-test` with `runTest { ... }`
- Headless CI tolerance: GUI tests use `assumeFalse(GraphicsEnvironment.isHeadless())`

## Commit conventions

- Author: Chich only — NO `Co-Authored-By` trailers
- Message prefix: `BuildCore: <what changed>`
- Commit after every logical step (per root-repo convention)
