# BuildCore — CLAUDE.md

Subproject guidance for BuildCore within the VitalPlugins Gradle multi-project.

## What BuildCore is

All-inclusive OSRS account builder foundation for the VitalClient platform. See the design spec at [../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md) for the full architecture.

## Status

**Foundation phase — Plans 1 + 2 + 3 + 4a + 4b + 5a + 6a complete.**

Future plans in `../docs/superpowers/plans/`:
- ~~Plan 2 — Core Runtime + Task SPI + Restrictions~~ (done)
- ~~Plan 3 — Logging + Event Bus~~ (done)
- ~~Plan 4a — RNG + Personality + Input Primitives~~ (done)
- ~~Plan 4b — Precision Mode + 4-tier break system + Misclick~~ (done)
- ~~Plan 5a — Service Infrastructure + 13 thin VitalAPI wrappers~~ (done)
- ~~Plan 6a — Confidence + ActionStakes Gating + Watchdog~~ (done)
- Plan 4c — ReplayRecorder + ReplayRng + ReplayServices
- Plan 5b — Services with logic (FoodPolicy, GearLoadout, StaminaPolicy, TeleportPlanner, HotRulesClient)
- Plan 5c — Cross-account / safety services (MuleService, WildernessThreatAnalyzer, TradeSafetyFilter, ChatSafetyListener)
- Plan 6b — Recovery Pipeline (7 steps, 90s budget)
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

Current invariants (Plans 2 + 3 + 4a + 4b):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions.
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet.
- Every `BusEvent` subtype has a `PrivacyScrubber` case (exhaustive-when + drift test, 45 subtypes).
- No free-form `payload`/`json`/`Any` fields on `BusEvent` subtypes.
- Correlation IDs (`eventId`, `sessionId`, `taskInstanceId`, `moduleId`) on every subtype.
- `core.logging` cannot import `Runner` internals.
- `MutableSharedFlow` not imported in `core.logging`.
- `PrivacyScrubber` has no public mutable fields.
- Log dir paths constructed only in `LogDirLayout` and `LogConfig`.
- `UncaughtExceptionHandler` uses `tryEmit` only.
- `vital.api.input.*` imports only in `VitalApi{Mouse,Keyboard,Camera}Backend.kt`.
- `java.util.Random` only in `JavaUtilRng.kt`; `SecureRandom` only in `SessionRng.kt`.
- `PersonalityVector` properties are all `val`, count is exactly 16 (schemaVersion + 15 dimensions).
- `PersonalityGenerator` draw order matches `PersonalityVector` field order.
- `Mouse`/`Keyboard`/`Camera` objects expose no public `var` state.
- `Mouse`/`Keyboard`/`Camera` primitive functions are all `suspend fun`.
- Functions calling `withPrecision`/`withSurvival` must be `@UsesPrecisionInput` (Plan 4b).
- `PrecisionGate.enter` only called from `Mouse`/`Keyboard`/`Camera`/`AntibanBootstrap` (Plan 4b).
- `PrivacyScrubber.scrub` runs only in `ExportBundle`; `LocalSummaryWriter` uses `hashAccountIdOnly` (Plan 4b).
- Every `*Service.kt` is a Kotlin `object` declaring `@Volatile internal var backend` (Plan 5a).
- Every `*Service.kt` action method body invokes `withServiceCall(...)` (Plan 5a).
- `*Service.kt` files do NOT import `vital.api.*`; only `VitalApi*Backend.kt` files do (Plan 5a).
- Every `*Service.kt` action method passes `stakes = ActionStakes.X` to `withServiceCall` (Plan 6a).
- `vital.api.*` imports only in `VitalApi*Backend.kt` files OR `VitalApiGameStateProvider.kt` (Plan 6a).
- `PrecisionGate.scopeEnteredAtMs` and `Runner.lastHeartbeatMs` are the only public mutable state added by Plan 6a (read by Watchdog checks).

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
