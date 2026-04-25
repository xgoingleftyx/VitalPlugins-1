# BuildCore Plan 4b — Precision Mode + 4-Tier Breaks + Misclick Design

> **Status:** Spec — pending plan + implementation. Brainstormed and approved 2026-04-25.
> **Precedes:** Plan 4b implementation plan (next).
> **Builds on:** Plans 1, 2, 3, 4a (all merged to `main`).
> **Branch:** `main` on `xgoingleftyx/VitalPlugins` fork. Direct commit + push.
> **Author:** Chich only — no `Co-Authored-By`.

---

## 1. Goal

Lift the Plan 4a `require(mode == NORMAL)` invariant and ship the three remaining antiban subsystems that compose over Plan 4a's primitives:

1. **Precision Mode** — `withPrecision { }` / `withSurvival { }` scopes that swap input behavior to tight, no-fidget, no-fatigue timing for tick-perfect work. SURVIVAL additionally preempts active breaks.
2. **4-tier break system** — Micro / Normal / Bedtime / Banking. Cooperative scheduler that respects `Task.canStopNow()`. Bedtime escalates via a new `Task.requestEarlyStop(BEDTIME)` SPI method.
3. **Misclick injection** — primitive-layer pixel jitter (universal, free) + a service-layer opt-in hook (`SemanticMisclickHook`) that Plan 5+ services adopt for higher-fidelity wrong-item / wrong-tab misclicks.

Plus two cross-cutting pieces:
- **`@UsesPrecisionInput` annotation + Konsist arch test** — prevents accidental precision use at architecture-review time.
- **Two-tier privacy sinks** — formalize the existing local log file as full-fidelity (debug-friendly), and the new "export bundle" path as scrubbed. The `PrivacyScrubber` becomes an *export-time* transform, not a write-time one.

Plan 4b is **pure framework** with no activity modules and no service layer. Plan 5 ships the first services that consume `SemanticMisclickHook`.

## 2. Out of scope (deferred)

- **Real `ReplayRecorder` / `ReplayRng` / `ReplayServices`** — Plan 4c.
- **Plan 5 services** — adopt `SemanticMisclickHook` later.
- **Per-account precision-budget tuning** — explicitly **dropped** during brainstorming. No budget. Tasks decide; `@UsesPrecisionInput` + Konsist is the only gate.
- **GUI controls for break config** — Plan 7 (Config + Profile System) wires UI; 4b ships JSON config + sane defaults.
- **`SessionManager` integration with breaks** — Plan 6 (Watchdog/Recovery).

## 3. Architecture

### 3.1 Module map

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                              # MODIFY — install BreakScheduler in startUp
└── core/
    ├── antiban/
    │   ├── AntibanBootstrap.kt                     # MODIFY — wire PrecisionGate + BreakScheduler
    │   ├── input/
    │   │   ├── InputMode.kt                        # MODIFY — drop NORMAL-only invariant
    │   │   ├── Mouse.kt                            # MODIFY — call MisclickPolicy on click()
    │   │   └── PrecisionGate.kt                    # CREATE — single mode-dispatch point
    │   ├── precision/
    │   │   ├── PrecisionWindow.kt                  # CREATE — withPrecision / withSurvival builders
    │   │   └── UsesPrecisionInput.kt               # CREATE — @MustBeDocumented annotation
    │   ├── breaks/
    │   │   ├── BreakTier.kt                        # CREATE — sealed: Micro/Normal/Bedtime/Banking
    │   │   ├── BreakConfig.kt                      # CREATE — Jackson-serialized per-tier config
    │   │   ├── BreakConfigStore.kt                 # CREATE — load/persist breaks.json
    │   │   ├── BreakScheduler.kt                   # CREATE — coroutine, cooperative, RNG-driven
    │   │   ├── BreakState.kt                       # CREATE — internal state machine + defer counters
    │   │   └── BedtimeEscalator.kt                 # CREATE — calls Task.requestEarlyStop(BEDTIME)
    │   └── misclick/
    │       ├── MisclickPolicy.kt                   # CREATE — primitive-layer pixel jitter
    │       └── SemanticMisclickHook.kt             # CREATE — service-layer opt-in API
    ├── task/
    │   └── Task.kt                                 # MODIFY — add suspend fun requestEarlyStop()
    ├── events/
    │   ├── BusEvent.kt                             # MODIFY — add 8 new subtypes
    │   └── PrivacyScrubber.kt                      # MODIFY — 8 new scrubber cases (export-time)
    └── logging/
        ├── LogDirLayout.kt                         # MODIFY — formalize localLog vs exportDir
        └── LogLevel.kt                             # MODIFY — map new events to DEBUG/INFO

BuildCore/src/test/kotlin/.../arch/
└── PrecisionInputArchTest.kt                       # CREATE — Konsist enforcement
```

### 3.2 Layering

- **Precision** lives at L7 (Input). Composes `Mouse`/`Keyboard`/`Camera`/`ReactionDelay`/`FatigueCurve` from 4a.
- **Breaks** also L7. Drives the Runner (L3) via existing `Task.canStopNow()` (L4 SPI from Plan 2).
- **Misclick** L7 (primitive layer) + L5-bridging API (`SemanticMisclickHook`) for Plan 5 services.
- **Privacy two-tier** L7-adjacent (cross-cutting); modifies `LogDirLayout` (L7 logging from Plan 3).

No new layers introduced.

## 4. Precision Mode

### 4.1 Behavioral contract

| Mode | Reaction delay | Fidget | Overshoot | Fatigue mult | Misclick | Preempts breaks |
|------|----------------|--------|-----------|--------------|----------|-----------------|
| `NORMAL` | full variance | yes | yes | applied | active | no |
| `PRECISION` | tight floor only | no | no | bypassed | suppressed | no |
| `SURVIVAL` | tight floor only | no | no | bypassed | suppressed | **yes** |

"Tight floor" = a per-`PersonalityVector` minimum reaction delay (e.g., 80–140ms) applied without random padding. Floor itself is sampled once at session start and reused; no per-call variance.

### 4.2 PrecisionGate

Single static dispatch point. Every primitive (`Mouse.moveTo`, `Mouse.click`, `Keyboard.tap`, `Keyboard.type`, `Camera.rotate`, `Camera.pitch`) calls:

```kotlin
PrecisionGate.enter(currentMode())
```

at the top of its body. `enter()`:

1. Reads the thread-local "precision marker" set by `withPrecision` / `withSurvival`.
2. If marker absent and mode != NORMAL → throw `IllegalStateException("Precision call outside withPrecision/withSurvival scope")`. Defense-in-depth; Konsist test catches at build time, runtime check catches if Konsist was bypassed.
3. If mode is SURVIVAL → call `BreakScheduler.preempt()` (idempotent; no-op if no break active and no-op if already preempted in this scope).
4. Returns the `TimingProfile` (reaction-delay floor, fidget enabled flag, overshoot enabled flag) the primitive should use.

`PrecisionGate` is the **only** code that reads the precision marker; primitives never read it directly.

### 4.3 PrecisionWindow

```kotlin
@UsesPrecisionInput
inline fun <T> withPrecision(block: () -> T): T

@UsesPrecisionInput
inline fun <T> withSurvival(block: () -> T): T
```

Implementation: set thread-local marker, run `block`, clear marker in `finally`. Emits:

- `PrecisionModeEntered(scopeId, mode)` on entry
- `PrecisionModeExited(scopeId, mode, durationMs)` on exit

`scopeId` is a session-monotonic `Long` for replay correlation.

### 4.4 `@UsesPrecisionInput` annotation

```kotlin
@MustBeDocumented
@Retention(AnnotationRetention.SOURCE)  // Konsist reads source; not needed at runtime
@Target(AnnotationTarget.FUNCTION)
annotation class UsesPrecisionInput
```

**Konsist arch test** (`PrecisionInputArchTest.kt`):

- Any function in `src/main/` whose body calls `withPrecision`, `withSurvival`, or `PrecisionGate.enter(PRECISION|SURVIVAL)` **must** be annotated `@UsesPrecisionInput` **or** be inside a function that is.
- Functions in `src/test/` are exempt.
- The `withPrecision`/`withSurvival` builders themselves are exempt (they're the source of the annotation).
- Test asserts the rule against the live call graph using Konsist's function-body queries.

## 5. 4-Tier Break System

### 5.1 Tiers

```kotlin
sealed class BreakTier {
    object Micro    : BreakTier()  // ~5-30s, in-task
    object Normal   : BreakTier()  // ~5-20min, between tasks
    object Bedtime  : BreakTier()  // ~6-10hr, daily anchor
    object Banking  : BreakTier()  // ~30-90s, "I just walked to the bank"
}
```

All durations and intervals are config-driven (§5.2). Defaults shipped in code.

### 5.2 BreakConfig

```kotlin
data class BreakConfig(
    val tiers: Map<BreakTier, TierConfig> = TIER_DEFAULTS
)

data class TierConfig(
    val enabled: Boolean,
    val durationRange: LongRange,        // millis
    val intervalRange: LongRange,        // millis between fires
    val maxDeferMs: Long,                // cooperative wait ceiling
    val onDeferTimeout: DeferAction      // DROP | RESCHEDULE | ESCALATE
)

enum class DeferAction { DROP, RESCHEDULE, ESCALATE }
```

Defaults (subject to tuning during implementation):
- Micro: 5-30s every 3-12min, defer 60s, RESCHEDULE
- Normal: 3-20min every 30-90min, defer 5min, RESCHEDULE
- Bedtime: 6-10h every 14-22h, defer 30min, **ESCALATE**
- Banking: 20-60s after every bank action (interval not used; trigger-driven), defer 30s, DROP

Stored as `breaks.json` in `LogDirLayout.breakConfigDir()` (sibling to `personalities/`). Jackson round-trip; unknown tiers ignored; missing tiers fall back to defaults.

### 5.3 BreakScheduler

Single coroutine launched from `AntibanBootstrap.install`. Lifecycle:

```
loop:
  pick next break: tier T, fire-at time t = now + sample(T.intervalRange, SessionRng)
  delay until t
  poll-fire(T):
    while now - t < T.maxDeferMs:
      if currentTask?.canStopNow() == true:
        executeBreak(T); break
      else:
        emit BreakDeferred(T, deferredMs); delay(POLL_INTERVAL)
    if not executed:
      apply T.onDeferTimeout
```

`executeBreak`:
1. Emit `BreakStarted(tier, plannedDurationMs)`.
2. Suspend (sample `durationRange`).
3. Emit `BreakEnded(tier, actualDurationMs)`.

`onDeferTimeout`:
- `DROP` — emit `BreakDropped(tier, deferredMs)`. Schedule next from `intervalRange`.
- `RESCHEDULE` — emit `BreakRescheduled(tier, newFireAt)`. Schedule a smaller follow-up window (e.g., next attempt in `intervalRange.first / 4`).
- `ESCALATE` — emit `EarlyStopRequested(tier, reason=BEDTIME)` and call `BedtimeEscalator.escalate(currentTask)` (§5.4).

### 5.4 BedtimeEscalator

When Bedtime defer exceeds its ceiling, the scheduler hands off to `BedtimeEscalator`:

```kotlin
suspend fun escalate(task: Task) {
    task.requestEarlyStop(EarlyStopReason.BEDTIME)
    // Now poll canStopNow() with a longer ceiling (configurable, default 30 more minutes)
    // because the task is *trying* to wind down (banking, logging out).
    // If it still won't stop, log a warning and fire the Bedtime break anyway —
    // sleep is non-negotiable, even if the bot is "stuck".
}
```

The "fire anyway after the second ceiling" branch is deliberately last-resort. It does **not** pause the runner forcibly — it just starts the break sleep. The next `canStopNow()` poll loop will catch the stuck task.

### 5.5 SURVIVAL preemption

`BreakScheduler.preempt()`:
- If a break is currently sleeping → emit `BreakPreempted(tier, remainingMs)`, cancel the sleep, push the next-fire of that tier forward by `survivalCooldownMs` (default 60s).
- If no break is active → no-op.
- Idempotent within the same `withSurvival` scope.

### 5.6 New Task SPI method

```kotlin
interface Task {
    // existing Plan 2 members...
    suspend fun requestEarlyStop(reason: EarlyStopReason) {}  // default no-op
}

enum class EarlyStopReason { BEDTIME, USER, ORCHESTRATOR }
```

Default implementation is no-op so existing tasks (NoOpTask) compile unchanged. Tasks that own state (banking, combat) override to drive themselves to a safe terminus.

The Runner does **not** force a state transition on `requestEarlyStop` — the task is still authoritative on `canStopNow()`. Runner stays in `RUNNING` until the task's normal completion path fires.

## 6. Misclick

### 6.1 MisclickPolicy (primitive layer)

Hooked into `Mouse.click(x, y, button)`. On every call:

```kotlin
val p = MISCLICK_BASE_PROB *
        FatigueCurve.misclickMultiplier() *
        PersonalityVector.misclickBias

if (currentMode() == NORMAL && SessionRng.nextDouble() < p) {
    val kind = if (SessionRng.nextDouble() < NEAR_MISS_FRACTION) NEAR_MISS else PIXEL_JITTER
    val (dx, dy) = sampleOffset(kind, SessionRng)
    busEmit(Misclick(kind, intended = Point(x, y), actual = Point(x + dx, y + dy), corrected = (kind == NEAR_MISS)))
    delegateClick(x + dx, y + dy, button)
    if (kind == NEAR_MISS) {
        delay(ReactionDelay.sample())
        delegateClick(x, y, button)  // corrective click
    }
} else {
    delegateClick(x, y, button)
}
```

- `MISCLICK_BASE_PROB` — small constant (e.g., 0.005). Tunable via personality if needed; otherwise hard-coded for v1.
- `NEAR_MISS_FRACTION` — ~0.10 of misclick events become NEAR_MISS.
- `PIXEL_JITTER` offset: `±1..3` px (Gaussian, clamped).
- `NEAR_MISS` offset: `±5..12` px (uniform, clamped).
- Suppressed when mode != NORMAL (PrecisionGate signals; MisclickPolicy queries the same marker).

### 6.2 SemanticMisclickHook (service layer, opt-in)

```kotlin
object SemanticMisclickHook {
    fun rollMisclick(context: String): Boolean {
        if (currentMode() != InputMode.NORMAL) return false
        val p = SEMANTIC_MISCLICK_BASE_PROB *
                FatigueCurve.misclickMultiplier() *
                PersonalityVector.misclickBias
        return SessionRng.nextDouble() < p
    }

    fun emitSemanticMisclick(context: String, intended: String, actual: String) {
        busEmit(SemanticMisclick(context, intended, actual))
    }
}
```

Plan 4b ships only the API + event types. Plan 5 services adopt: `InventoryService.useItemOn(a, b)` may call `rollMisclick("useItemOn")` and, if true, swap `b` for an adjacent slot, then call `emitSemanticMisclick`.

`SEMANTIC_MISCLICK_BASE_PROB` — even smaller (e.g., 0.002); semantic misclicks are rarer than pixel jitter because they have higher behavioral cost.

## 7. Two-Tier Privacy Sinks

### 7.1 Sinks

| Sink | Path | Fidelity | Audience |
|------|------|----------|----------|
| **Local log** | `LogDirLayout.sessionDir(sid).resolve("session.log.jsonl")` | Full — exact coords, exact GP, exact item IDs | Local debugging only; never auto-shipped |
| **Export bundle** | `LogDirLayout.exportDir().resolve("$sid-export.jsonl")` (created on demand) | Scrubbed via `PrivacyScrubber` | Sharing with devs, attaching to bug reports |

Account name (display name) is hashed in **both** sinks — defense-in-depth against accidental copy-paste.

### 7.2 LogDirLayout additions

```kotlin
class LogDirLayout(val root: Path) {
    // existing members...

    /** Sibling to logs root. Plan 4b. */
    fun breakConfigDir(): Path = root.resolveSibling("breaks").also { Files.createDirectories(it) }

    /** Sibling to logs root. Created lazily on export. */
    fun exportDir(): Path = root.resolveSibling("exports").also { Files.createDirectories(it) }
}
```

Architecture test #7 (no string literals for log paths outside `LogDirLayout`) is extended to cover `breaks` and `exports`.

### 7.3 PrivacyScrubber becomes export-time

Today (4a): `PrivacyScrubber` is invoked by the log writer before `session.log.jsonl` is written.
After 4b: log writer writes raw events. A new `ExportBundle.create(sessionId)` method reads the raw log, runs each event through `PrivacyScrubber`, and writes the scrubbed file to `exportDir()`.

`PrivacyScrubber.transform(event)` signature unchanged. Only the *invocation site* moves. The exhaustive-when arch test from Plan 3 still applies — every BusEvent subtype must have a scrubber case.

### 7.4 Scrubber rules for new 4b events

| Event | Fields scrubbed on export |
|-------|---------------------------|
| `PrecisionModeEntered/Exited` | none — `scopeId` is session-local |
| `BreakScheduled/Started/Ended/Deferred/Dropped/Rescheduled/Preempted` | none — tier/duration is not personal |
| `EarlyStopRequested` | none — reason enum only |
| `Misclick` | `intended`/`actual` rounded to grid-cell (16px); `corrected` kept |
| `SemanticMisclick` | `context`/`intended`/`actual` strings hashed (preserve cardinality for debugging without leaking item names that could correlate to account inventory) |

## 8. New BusEvent subtypes

8 new subtypes added to `BusEvent.kt`:

```kotlin
data class PrecisionModeEntered(...common-fields..., val scopeId: Long, val mode: InputMode) : BusEvent
data class PrecisionModeExited(...common-fields..., val scopeId: Long, val mode: InputMode, val durationMs: Long) : BusEvent
data class BreakScheduled(...common-fields..., val tier: BreakTier, val fireAtEpochMs: Long) : BusEvent
data class BreakStarted(...common-fields..., val tier: BreakTier, val plannedDurationMs: Long) : BusEvent
data class BreakEnded(...common-fields..., val tier: BreakTier, val actualDurationMs: Long) : BusEvent
data class BreakDeferred(...common-fields..., val tier: BreakTier, val deferredMs: Long) : BusEvent
data class BreakDropped(...common-fields..., val tier: BreakTier, val deferredMs: Long) : BusEvent
data class BreakRescheduled(...common-fields..., val tier: BreakTier, val newFireAtEpochMs: Long) : BusEvent
data class BreakPreempted(...common-fields..., val tier: BreakTier, val remainingMs: Long) : BusEvent
data class EarlyStopRequested(...common-fields..., val reason: EarlyStopReason) : BusEvent
data class Misclick(...common-fields..., val kind: MisclickKind, val intended: Point, val actual: Point, val corrected: Boolean) : BusEvent
data class SemanticMisclick(...common-fields..., val context: String, val intended: String, val actual: String) : BusEvent
```

Common fields = `eventId, sessionId, taskInstanceId, moduleId, timestampMs` (Plan 3 contract).

Total: **12 new subtypes** (recount: 2 precision + 7 break + 1 early-stop + 2 misclick = 12). Plan 3's `PrivacyScrubber` arch test count: 27 → **39**.

## 9. Testing

New test files under `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/`:

| File | What it covers |
|------|----------------|
| `arch/PrecisionInputArchTest.kt` | Konsist: `withPrecision`/`withSurvival`/`PrecisionGate.enter(non-NORMAL)` callers must be `@UsesPrecisionInput` (transitive) |
| `core/antiban/precision/PrecisionGateTest.kt` | NORMAL passes through; PRECISION suppresses fatigue/fidget/overshoot; SURVIVAL preempts; missing scope throws |
| `core/antiban/precision/PrecisionWindowTest.kt` | Marker set/cleared correctly across normal exit, exception, and nested scopes |
| `core/antiban/breaks/BreakSchedulerTest.kt` | Virtual time; fake `Task` with scripted `canStopNow()`; cooperative deferral, RESCHEDULE, DROP, ESCALATE all exercised |
| `core/antiban/breaks/BedtimeEscalatorTest.kt` | `requestEarlyStop(BEDTIME)` called exactly once after `maxDeferMs`; second-ceiling fallback fires the break anyway |
| `core/antiban/breaks/BreakConfigTest.kt` | JSON round-trip; defaults; user-override merge; unknown tier ignored |
| `core/antiban/breaks/BreakSchedulerPreemptionTest.kt` | `withSurvival` cancels active break; emits `BreakPreempted`; idempotent |
| `core/antiban/misclick/MisclickPolicyTest.kt` | Fixed seed → expected misclick rate; PIXEL_JITTER vs NEAR_MISS distribution; suppressed in PRECISION/SURVIVAL |
| `core/antiban/misclick/SemanticMisclickHookTest.kt` | `rollMisclick` rate matches; suppressed in non-NORMAL; emits scrubbed event on export |
| `core/task/TaskEarlyStopTest.kt` | Default no-op; runner stays RUNNING when task ignores; runner advances normally when task drives to completion |
| `core/logging/LogDirLayoutTwoTierTest.kt` | `breakConfigDir`, `exportDir` created lazily; arch test #7 still passes |
| `core/logging/ExportBundleTest.kt` | Reads raw log, scrubs, writes to export dir; round-trip across all 12 new event types |
| `core/events/PrivacyScrubber4bTest.kt` | Each new event has a scrubber case; scrubbed payloads match §7.4 table |

Estimated **~30 new tests** on top of Plan 4a's 94 → ~**124 total**.

Existing arch tests extended:
- `BusEventArchTest` — exhaustive-when count 27 → 39
- `LogDirLayoutArchTest` — string literal exemptions: `"logs"`, `"personalities"`, `"breaks"`, `"exports"`

## 10. Open questions resolved during brainstorming

| # | Question | Resolution |
|---|----------|------------|
| 1 | Break tier defaults vs config-driven | Config-driven; defaults shipped in code |
| 2 | Precision Mode behavior set | A+B+C+D; SURVIVAL = PRECISION + preempts breaks |
| 3 | SURVIVAL semantics (sloppier?) | Backwards — SURVIVAL is precision+preempt, used for life-saving actions |
| 4 | Safe-Stop ↔ Break interaction | Cooperative only; Bedtime escalates via `Task.requestEarlyStop(BEDTIME)` |
| 5 | `@UsesPrecisionInput` enforcement | Konsist arch test only |
| 6 | Misclick injection point | Both — primitive jitter (universal) + service hook (opt-in) |
| 7 | Precision budget | **Dropped** — Konsist + opt-in scope is enough; YAGNI |
| 8 | Privacy scrubbing | Two-tier: full local, scrubbed on export |

## 11. Risks

- **Konsist transitive call-graph queries** — if Konsist's API doesn't support transitive annotation propagation cleanly, the arch test may need a custom implementation walking call sites. Mitigation: prototype the test first; fall back to documenting the rule + a simpler "any function calling withPrecision must be annotated" check (one-hop only) if transitive proves brittle.
- **BreakScheduler ↔ Runner state machine** — runner from Plan 2 is authoritative; scheduler should never mutate runner state. Risk that escalation feels like a state mutation. Mitigation: scheduler only emits events and calls SPI methods; runner state changes only via the task's own completion path.
- **Misclick correctness in batch-click sequences** (e.g., XP drops during PvM) — if every click in a 50-click burst rolls the dice independently, the cumulative misclick rate looks unnatural. Mitigation: burst-aware suppression — a misclick fires at most once per `burstCooldownMs` (default 2s). Add to `MisclickPolicy` config.
- **Bedtime "fire anyway" branch** masking stuck tasks — the second-ceiling fallback means a runaway task never blocks bedtime. Watchdog (Plan 6) will independently flag tasks that ignored `requestEarlyStop(BEDTIME)`.

## 12. References

- Plan 4a spec: `2026-04-24-buildcore-plan4a-rng-personality-input-design.md`
- Plan 3 spec: `2026-04-23-buildcore-plan3-logging-eventbus-design.md`
- BuildCore foundation spec: `2026-04-21-buildcore-foundation-design.md`
- VitalAPI input surface: `C:\Code\VitalAPI\src\vital\api\input\` (verified in 4a §VitalAPI surface)
