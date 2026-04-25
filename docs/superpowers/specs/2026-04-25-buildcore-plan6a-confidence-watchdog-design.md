# BuildCore Plan 6a — Confidence + ActionStakes Gating + Watchdog Design

> **Status:** Spec — pending plan + implementation. Brainstormed and approved 2026-04-25.
> **Branch:** `main` on `xgoingleftyx/VitalPlugins` fork. Direct commit + push.
> **Author:** Chich only — no `Co-Authored-By`.
> **Builds on:** Plans 1, 2, 3, 4a, 4b, 5a (all merged; 293 tests).

---

## 1. Goal

Ship the runtime monitoring half of foundation §11:

1. **Confidence Layer** — 8-signal weighted scalar in [0.0, 1.0], cached 600ms, recomputed on `ServiceCallEnd`, published as `ConfidenceUpdated` events.
2. **ActionStakes gating** — `withServiceCall` (Plan 5a) gains a `stakes: ActionStakes` parameter; `ConfidenceGate.check(stakes)` throws `ConfidenceTooLow` when current score < `stakes.threshold`. New `ServiceOutcome.UNCONFIDENT` distinguishes from FAILURE/EXCEPTION/RESTRICTED.
3. **Watchdog** — coroutine on a dedicated single-thread dispatcher running 4 checks at 1Hz: STALL (progress fingerprint unchanged), UNCERTAIN (confidence < 0.4 sustained 30s), DEADLOCK (Runner heartbeat missed > 15s), LEAK (precision scope held > 30s). Emits `WatchdogTriggered(kind, detail)` events.
4. **Runner heartbeat** — small Plan 2 extension: `Runner.lastHeartbeatMs` + 1Hz throttled `RunnerHeartbeat` event.
5. **Three new Task SPI hooks** (default-safe) — `expectedEntities`, `expectedArea`, `expectedInventoryDelta`. Stubbed signals return 1.0 until tasks override.

Plan 6a is **runtime monitoring only**. The corrective half (Recovery Pipeline) is Plan 6b.

## 2. Out of scope (deferred)

- **Plan 6b — Recovery Pipeline** (7-step stop-at-first-success, 90s budget). Triggered by `WatchdogTriggered`. Needs `HotRulesClient` (Plan 5b) + `TeleportPlanner` (Plan 5b) for steps 4–5.
- **Per-call stakes override** (`BankService.deposit(itemId, amount, stakes = HIGH)`). Per-method declaration is sufficient for v1.
- **CRITICAL stakes usage.** No service method in 6a is classified CRITICAL — reserved for Plan 5c trade-accept and quest-final-click.
- **Recent chat scanning** (`RecentChatNormal` signal). Returns 1.0 in v1; chat-pattern detection lands in Plan 5c.
- **`InterfaceKnown` registry.** Returns 0.5 (permissive) when an unknown widget is open. The widget-allowlist registry is deferred until enough services exist to populate it.
- **`ConfidenceUnderconfidentAction` event** — listed in Section 5 and emitted on every `ConfidenceTooLow` throw, but no consumer ships in 6a; Plan 6b's Recovery subscribes.

## 3. Architecture

### 3.1 Module map

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                        # MODIFY — install ConfidenceBootstrap + Watchdog
└── core/
    ├── events/
    │   ├── BusEvent.kt                                       # MODIFY — 4 new subtypes + WatchdogKind enum
    │   └── PrivacyScrubber.kt                                # MODIFY — 4 passthrough cases
    ├── task/
    │   ├── Runner.kt                                         # MODIFY — lastHeartbeatMs + heartbeat() call
    │   └── Task.kt                                           # MODIFY — 3 new SPI hooks (default null)
    ├── services/
    │   ├── WithServiceCall.kt                                # MODIFY — add `stakes` param + UNCONFIDENT outcome
    │   ├── bank/BankService.kt                               # MODIFY — pass stakes per method
    │   ├── inventory/InventoryService.kt                     # MODIFY
    │   └── (...)                                             # all 13 services modified for stakes
    └── confidence/
        ├── ActionStakes.kt
        ├── Confidence.kt
        ├── ConfidenceSignal.kt
        ├── ConfidenceTracker.kt
        ├── ConfidenceGate.kt
        ├── ConfidenceTooLow.kt
        ├── ConfidenceBootstrap.kt
        ├── hints/
        │   ├── EntityHint.kt
        │   ├── AreaHint.kt
        │   └── InventoryDeltaHint.kt
        └── watchdog/
            ├── WatchdogScope.kt
            ├── WatchdogKind.kt                               # typealias to events/WatchdogKind
            ├── WatchdogFinding.kt
            ├── WatchdogCheck.kt                              # interface
            ├── Watchdog.kt                                   # driver
            └── checks/
                ├── StallCheck.kt
                ├── UncertainCheck.kt
                ├── DeadlockCheck.kt
                └── LeakCheck.kt

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/confidence/
│   ├── ConfidenceTrackerTest.kt
│   ├── ConfidenceComputeTest.kt
│   ├── ConfidenceGateTest.kt
│   └── watchdog/
│       ├── WatchdogTest.kt
│       └── checks/{StallCheckTest, UncertainCheckTest, DeadlockCheckTest, LeakCheckTest}.kt
├── core/services/WithServiceCallStakesTest.kt
├── core/task/RunnerHeartbeatTest.kt
└── arch/ConfidenceArchitectureTest.kt
```

### 3.2 Layering

L6. Confidence + Watchdog cross-cut into L3 (Runner heartbeat), L5 (services pass `stakes`), L4 (Task SPI hooks). Recovery (Plan 6b) will sit at the same layer.

Confidence reads VitalAPI (`vital.api.ui.Widgets`, `vital.api.entities.Player`) for the 4 real signals — those reads are restricted to `ConfidenceTracker`'s compute method (analogous to how Plan 5a's `VitalApi*Backend` files isolate VitalAPI imports). Architecture test enforces.

### 3.3 Singleton pattern

`ConfidenceTracker` and `ConfidenceGate` are Kotlin `object`s with `@Volatile internal var` fields wired by `ConfidenceBootstrap`. Same shape as `ServiceBootstrap` in Plan 5a. `Watchdog` is a regular class (multi-instance for test isolation; single instance at runtime).

## 4. Confidence types

### 4.1 Signal taxonomy and weights

```kotlin
sealed class ConfidenceSignal(val weight: Double, val name: String)
{
	object InterfaceKnown          : ConfidenceSignal(0.10, "INTERFACE_KNOWN")
	object LastActionResulted      : ConfidenceSignal(0.20, "LAST_ACTION_RESULTED")
	object ExpectedEntitiesVisible : ConfidenceSignal(0.10, "EXPECTED_ENTITIES_VISIBLE")
	object PositionReasonable      : ConfidenceSignal(0.10, "POSITION_REASONABLE")
	object HpNormal                : ConfidenceSignal(0.15, "HP_NORMAL")
	object InventoryDeltaExpected  : ConfidenceSignal(0.10, "INVENTORY_DELTA_EXPECTED")
	object NoUnexpectedDialog      : ConfidenceSignal(0.15, "NO_UNEXPECTED_DIALOG")
	object RecentChatNormal        : ConfidenceSignal(0.10, "RECENT_CHAT_NORMAL")
}
// Weights sum to 1.00.
```

### 4.2 `Confidence` value type

```kotlin
data class Confidence(
	val score: Double,
	val perSignal: Map<String, Double>,
	val computedAtMs: Long
)
```

`score` = `Σ signal.weight × signalValue` clamped to `[0.0, 1.0]`.

### 4.3 `ActionStakes` enum

```kotlin
enum class ActionStakes(val threshold: Double)
{
	READ_ONLY(0.0),   // pass-through; Confidence not consulted
	LOW(0.4),
	MEDIUM(0.6),
	HIGH(0.8),
	CRITICAL(0.9)
}
```

### 4.4 Per-signal compute rules

Each rule is a pure function of `(Task?, TaskContext?, lastActionOutcome: ServiceOutcome?)` and current VitalAPI game state. Stub rules use the new Task SPI hooks (§4.6) and default to 1.0 when the hook returns null.

| Signal | Rule |
|---|---|
| **InterfaceKnown** (real) | `Widgets.getOpenWidgetId()` non-null AND in known set → 1.0; non-null but unknown → 0.5; null → 1.0 (no widget = nothing wrong). v1 known set is empty so 0.5 is the practical floor. |
| **LastActionResulted** (real) | SUCCESS → 1.0; FAILURE → 0.5; EXCEPTION → 0.2; RESTRICTED → 1.0 (restriction is a profile choice, not failure); UNCONFIDENT → use cached previous (avoids feedback loop); null → 1.0 (no calls yet). |
| **HpNormal** (real) | `currentHp / maxHp`: ≥ 0.7 → 1.0; 0.4–0.7 → 0.5; < 0.4 → 0.0; query failure → 1.0 (assume normal). |
| **NoUnexpectedDialog** (real) | Any dialog widget open AND task did not declare it expected → 0.0; else 1.0. v1 has no expected-dialog SPI; defaults to "all dialogs unexpected" — but we treat absent-by-default as 1.0 because dialogs only appear during interaction. **Decision:** any dialog widget open → 0.5 (suspicion, not failure); none open → 1.0. Conservative; tunes once recovery exists. |
| **ExpectedEntitiesVisible** (stubbed) | `task?.expectedEntities(ctx)` null/empty → 1.0; else (matched / expected.size) clamped to [0,1]. |
| **PositionReasonable** (stubbed) | `task?.expectedArea(ctx)` null → 1.0; else `Player.tile in area.bounds` → 1.0 / 0.0. |
| **InventoryDeltaExpected** (stubbed) | `task?.expectedInventoryDelta(ctx)` null → 1.0; else (matched delta vs actual) heuristic. v1 implementation: returns 1.0 unconditionally if hint is null; if hint is non-null, returns 0.5 (we recognise the hint exists but can't fully evaluate without more context — leaves room for tasks to opt in). |
| **RecentChatNormal** (stubbed) | Returns 1.0 unconditionally in v1. |

### 4.5 `ConfidenceTracker`

```kotlin
object ConfidenceTracker
{
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }
	@Volatile internal var taskProvider: () -> Pair<Task?, TaskContext?> = { null to null }
	@Volatile internal var clock: Clock = Clock.systemUTC()
	@Volatile internal var gameStateProvider: GameStateProvider = VitalApiGameStateProvider

	@Volatile private var cached: Confidence? = null
	@Volatile private var lastActionOutcome: ServiceOutcome? = null

	private const val CACHE_TTL_MS = 600L

	fun current(): Confidence
	{
		val now = clock.millis()
		cached?.let { if (now - it.computedAtMs < CACHE_TTL_MS) return it }
		val fresh = compute(now)
		cached = fresh
		bus?.tryEmit(ConfidenceUpdated(sessionId = sessionIdProvider(), score = fresh.score, perSignal = fresh.perSignal))
		return fresh
	}

	internal fun onServiceCallEnd(end: ServiceCallEnd)
	{
		if (end.outcome != ServiceOutcome.UNCONFIDENT) lastActionOutcome = end.outcome
		cached = null
	}

	private fun compute(now: Long): Confidence { /* per §4.4 rules */ }

	internal fun resetForTests() { cached = null; lastActionOutcome = null; bus = null; gameStateProvider = VitalApiGameStateProvider }
}
```

`GameStateProvider` is an interface backing the 4 real signals' VitalAPI calls (analogous to Plan 5a's `<X>Backend`). Default `VitalApiGameStateProvider` calls `vital.api.*`. Tests swap to `FakeGameStateProvider`.

### 4.6 New Task SPI hooks (default null)

```kotlin
interface Task
{
	// existing...

	/** Plan 6a: stub signal source. Default null = "no expectation" → signal returns 1.0. */
	fun expectedEntities(ctx: TaskContext): List<EntityHint>? = null
	fun expectedArea(ctx: TaskContext): AreaHint? = null
	fun expectedInventoryDelta(ctx: TaskContext): InventoryDeltaHint? = null
}

data class EntityHint(val kind: String, val nameOrId: String)        // e.g., ("npc", "Goblin"), ("object", "Bank booth")
data class AreaHint(val centerX: Int, val centerY: Int, val radius: Int)
data class InventoryDeltaHint(val itemId: Int, val expectedQty: Int) // simple v1; multi-item lists for later
```

Hints live in `core/confidence/hints/`. NoOpTask continues to default everything; existing tests unaffected.

## 5. Confidence gate

### 5.1 Exception type

```kotlin
class ConfidenceTooLow(
	val required: Double,
	val current: Double,
	val worstSignal: String
) : RuntimeException("confidence $current < required $required (worst signal: $worstSignal)")
```

### 5.2 `ConfidenceGate` object

```kotlin
object ConfidenceGate
{
	fun check(stakes: ActionStakes)
	{
		if (stakes.threshold <= 0.0) return
		val c = ConfidenceTracker.current()
		if (c.score < stakes.threshold)
		{
			val worst = c.perSignal.minByOrNull { it.value }?.key ?: "unknown"
			throw ConfidenceTooLow(stakes.threshold, c.score, worst)
		}
	}
}
```

### 5.3 `withServiceCall` extension

The Plan 5a helper gains a `stakes: ActionStakes = ActionStakes.MEDIUM` parameter (between `restriction` and `block`). New flow inside the helper:

1. Emit `ServiceCallStart`.
2. Restriction check (existing).
3. **NEW:** `try { ConfidenceGate.check(stakes) } catch (e: ConfidenceTooLow) { outcome = UNCONFIDENT; bus?.tryEmit(ConfidenceUnderconfidentAction(...)); throw e }`.
4. Run block, classify outcome (existing).
5. Emit `ServiceCallEnd`.

`ServiceOutcome` enum gains `UNCONFIDENT`. Caught by `withServiceCall`'s outer try/catch alongside `RestrictionViolation`; both rethrow.

### 5.4 Per-method stakes assignment

Across the 13 services from Plan 5a, ~38 method bodies get a one-line `stakes = ActionStakes.X` addition. Defaults:

| Service | Stakes |
|---|---|
| BankService | MEDIUM |
| InventoryService | MEDIUM |
| EquipmentService | MEDIUM |
| WalkerService | LOW |
| LoginService | HIGH |
| WorldService | HIGH |
| CombatService | MEDIUM |
| MagicService | MEDIUM |
| PrayerService | MEDIUM |
| InteractService | LOW |
| DialogueService | MEDIUM |
| ChatService | MEDIUM |
| GrandExchangeService | HIGH |

No service uses CRITICAL in 6a.

## 6. Watchdog

### 6.1 `WatchdogScope`

```kotlin
class WatchdogScope : AutoCloseable
{
	private val dispatcher = Executors.newSingleThreadExecutor { r ->
		Thread(r, "Watchdog").apply { isDaemon = true }
	}.asCoroutineDispatcher()
	private val job = SupervisorJob()
	val coroutineScope = CoroutineScope(dispatcher + job + CoroutineName("WatchdogScope"))
	override fun close() { job.cancel(); dispatcher.close() }
}
```

Single-thread dispatcher → deadlock immunity from main runner dispatcher. Sibling of `LoggerScope` from Plan 3.

### 6.2 `WatchdogCheck` interface + `Watchdog` driver

```kotlin
interface WatchdogCheck
{
	fun tick(nowMs: Long): WatchdogFinding?
}

data class WatchdogFinding(val kind: WatchdogKind, val detail: String)

class Watchdog(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID,
	private val checks: List<WatchdogCheck>,
	private val tickIntervalMs: Long = 1_000L,
	private val nowMs: () -> Long = System::currentTimeMillis
)
{
	suspend fun run() = coroutineScope {
		while (isActive)
		{
			for (check in checks)
			{
				check.tick(nowMs())?.let { trigger ->
					bus.tryEmit(WatchdogTriggered(
						sessionId = sessionIdProvider(),
						kind = trigger.kind,
						detail = trigger.detail
					))
				}
			}
			delay(tickIntervalMs)
		}
	}
}
```

### 6.3 The 4 checks

**`StallCheck`** — holds `last: ProgressFingerprint?` and `lastChangedAtMs: Long`. Each tick: `task.progressSignal(ctx)` (via injected `taskProvider`); if equal to `last`, compare `nowMs - lastChangedAtMs` to `task.stallThreshold.inWholeMilliseconds`. Fire once per stall episode (reset `lastChangedAtMs` to nowMs after firing — but keep `last` so a re-stall fires after another threshold).

**`UncertainCheck`** — holds `firstBelowThresholdMs: Long?`. Each tick: `ConfidenceTracker.current().score`. If < 0.4 and timestamp null, set timestamp; if sustained 30s, fire and clear; if recovers, clear.

**`DeadlockCheck`** — reads `runnerProvider().lastHeartbeatMs`. If `nowMs - lastHeartbeatMs > 15_000`, fire and reset (record fired-at to suppress re-fire until heartbeat returns).

**`LeakCheck`** — reads `PrecisionGate.scopeEnteredAtMs` (new field). If non-null and `nowMs - scopeEnteredAtMs > 30_000`, fire and reset (record fired-at to suppress re-fire until exit).

### 6.4 Runner heartbeat (Plan 2 extension)

```kotlin
class Runner(...)
{
	@Volatile var lastHeartbeatMs: Long = System.currentTimeMillis()
		private set

	@Volatile private var lastHeartbeatEventMs: Long = 0L

	private fun heartbeat()
	{
		lastHeartbeatMs = System.currentTimeMillis()
		if (lastHeartbeatMs - lastHeartbeatEventMs >= 1_000L)
		{
			bus.tryEmit(RunnerHeartbeat(sessionId = sessionIdProvider(), taskInstanceId = currentTaskInstanceId))
			lastHeartbeatEventMs = lastHeartbeatMs
		}
	}

	// Add `heartbeat()` call at the top of every state-machine tick.
}
```

The Runner already has a tick loop from Plan 2; this adds two `@Volatile Long`s and a 5-line `heartbeat()` method. Throttled bus emit at 1Hz keeps event volume low.

### 6.5 `PrecisionGate` extension

```kotlin
object PrecisionGate
{
	// existing fields...
	@Volatile internal var scopeEnteredAtMs: Long? = null

	internal fun markEnterScope(mode: InputMode)
	{
		val depth = scopeDepth.get()
		if (depth == 0) scopeEnteredAtMs = System.currentTimeMillis()
		scopeDepth.set(depth + 1)
		scopeMode.set(mode)
	}

	internal fun markExitScope()
	{
		val d = scopeDepth.get() - 1
		if (d <= 0)
		{
			scopeDepth.set(0); scopeMode.set(null); scopeEnteredAtMs = null
		}
		else
		{
			scopeDepth.set(d)
		}
	}
}
```

Outermost-scope tracking: `scopeEnteredAtMs` is set only when depth transitions 0 → 1 and cleared on 1 → 0. Nested `withPrecision { withPrecision { } }` reuses the outermost timestamp.

## 7. New BusEvent subtypes

```kotlin
enum class WatchdogKind { STALL, UNCERTAIN, DEADLOCK, LEAK }

data class ConfidenceUpdated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val score: Double,
	val perSignal: Map<String, Double>
) : BusEvent

data class WatchdogTriggered(
	...common fields...,
	val kind: WatchdogKind,
	val detail: String
) : BusEvent

data class RunnerHeartbeat(
	...common fields...,
	override val taskInstanceId: UUID    // non-nullable here; runner always has a task
) : BusEvent

data class ConfidenceUnderconfidentAction(
	...common fields...,
	val serviceName: String,
	val methodName: String,
	val required: Double,
	val current: Double
) : BusEvent
```

`ServiceOutcome` enum gains `UNCONFIDENT` case.

`PrivacyScrubber`: all 4 new subtypes are passthrough — `score`, `kind`, `detail`, `serviceName`/`methodName` are not personal. `perSignal` keys are static names (e.g., "HP_NORMAL"), values are floats — not personal.

`LoggingArchitectureTest.scrubberSampleCount`: **41 → 45**.

## 8. Wiring

### 8.1 `ConfidenceBootstrap`

```kotlin
object ConfidenceBootstrap
{
	private val installed = AtomicBoolean(false)

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		taskProvider: () -> Pair<Task?, TaskContext?>,
		clock: Clock = Clock.systemUTC()
	)
	{
		if (!installed.compareAndSet(false, true)) return
		ConfidenceTracker.bus = bus
		ConfidenceTracker.sessionIdProvider = sessionIdProvider
		ConfidenceTracker.taskProvider = taskProvider
		ConfidenceTracker.clock = clock

		// Subscribe to ServiceCallEnd events
		LoggerScope.coroutineScope.launch {
			bus.events.collect { ev ->
				if (ev is ServiceCallEnd) ConfidenceTracker.onServiceCallEnd(ev)
			}
		}
	}

	internal fun resetForTests()
	{
		installed.set(false)
		ConfidenceTracker.resetForTests()
	}
}
```

Call from `BuildCorePlugin.startUp` after `ServiceBootstrap.install`. The `LoggerScope` reference here is a placeholder — actual subscription mechanism uses Plan 3's `SubscriberRegistry` if that's the established pattern; the implementer follows whatever Plan 3's pattern is.

### 8.2 Watchdog launch

```kotlin
// in BuildCorePlugin
private lateinit var watchdogScope: WatchdogScope
private var watchdogJob: Job? = null

override fun startUp()
{
	// ...existing
	ConfidenceBootstrap.install(bus = eventBus, sessionIdProvider = { sessionManager.sessionId }, taskProvider = { /* TODO: Runner-provided */ })

	watchdogScope = WatchdogScope()
	val checks = listOf(
		StallCheck(taskProvider = { ... }),
		UncertainCheck(),
		DeadlockCheck(runnerProvider = { /* current runner */ }),
		LeakCheck()
	)
	val watchdog = Watchdog(eventBus, { sessionManager.sessionId }, checks)
	watchdogJob = watchdogScope.coroutineScope.launch { watchdog.run() }
}

override fun shutDown()
{
	runBlocking {
		watchdogJob?.cancelAndJoin()
		breakSchedulerJob?.cancelAndJoin()
		// ...existing cleanup
	}
	watchdogScope.close()
	loggerScope.close()
}
```

The `taskProvider` and `runnerProvider` come from a registry the Runner updates on task transitions. For v1, there is no live Runner (Plan 2 ships the framework but no plugin uses it yet), so providers default to `{ null to null }` and `{ null }` — checks short-circuit when null. The Runner-task registration wires up when a real Runner instance launches.

## 9. Testing

| Suite | Tests |
|---|---|
| `ConfidenceTrackerTest` | cache freshness; recompute when stale; `ServiceCallEnd` invalidates; ConfidenceUpdated emitted on compute; `lastActionOutcome` persists through UNCONFIDENT; null bus = silent |
| `ConfidenceComputeTest` | each of 8 signals produces expected value with mocked `GameStateProvider`; weighted sum correct; default-1.0 stubs for null SPI hooks; HP < 0.4 → 0.0 |
| `ConfidenceGateTest` | READ_ONLY pass-through; LOW/MEDIUM/HIGH throw `ConfidenceTooLow` when score below; passes when above; `worstSignal` reflects min of perSignal |
| `WithServiceCallStakesTest` | UNCONFIDENT outcome; backend NOT invoked when gate throws; restriction check still happens before confidence (failing restriction → RESTRICTED, not UNCONFIDENT, even if confidence also low); `ConfidenceUnderconfidentAction` emitted on throw |
| `StallCheckTest` | virtual time; fingerprint unchanged → fires after `task.stallThreshold`; fingerprint changed → resets timer; fires once per episode |
| `UncertainCheckTest` | low score sustained 30s → fires; recovery before 30s → no fire; re-fires after another 30s sustained-low |
| `DeadlockCheckTest` | no heartbeat 15s → fires; heartbeat refresh → no fire and re-arm |
| `LeakCheckTest` | precision scope held > 30s → fires; nested scope tracks outermost; exit clears |
| `WatchdogTest` (integration) | scope launches all 4 checks on tick; emits WatchdogTriggered with correct kind+detail; check exception doesn't kill the loop |
| `RunnerHeartbeatTest` | tick updates `lastHeartbeatMs`; bus event emitted no more than 1Hz |
| `PrecisionGateLeakTest` (extension to Plan 4b) | `scopeEnteredAtMs` set at outermost entry; nested entry doesn't reset; exit at depth>0 doesn't clear; outermost exit clears |
| `ConfidenceArchitectureTest` (Konsist) | Every `*Service.kt` action method `withServiceCall` call includes a `stakes =` arg; `vital.api.*` imports outside `VitalApiGameStateProvider.kt` and the existing Plan 5a `VitalApi*Backend.kt` files prohibited |

**Estimated test count:** ~50 new tests, total → **~343**.

## 10. Risks

- **`ServiceCallEnd` subscription mechanism.** Plan 3 uses `SubscriberRegistry`; the spec's example in §8.1 collects from `bus.events` directly. Implementer follows the existing Plan 3 pattern. If Plan 3's bus exposes a `Flow`, use `LoggerScope.launch { bus.events.collect { ... } }`; if it requires registering via `SubscriberRegistry`, write a `ConfidenceSubscriber` class.
- **Cache invalidation race.** `ConfidenceTracker.cached` is `@Volatile`; setter on `onServiceCallEnd` (subscriber thread) and `current()` (caller thread) can race. The compute is idempotent — worst case is two callers compute simultaneously. Acceptable.
- **`taskProvider` returning null.** Until a Runner instance is alive (no plugin uses it in 6a), all stubbed signals return 1.0 (no expectation), the 4 real signals run normally, score is dominated by HP/dialog/widget. Watchdog checks short-circuit on null providers. This is correct: 6a ships a working framework with no live consumer.
- **`InterfaceKnown` 0.5 default** could cause unconfident gates to fire spuriously when a benign widget opens. Mitigated by HIGH/CRITICAL stakes being conservative (most gated services are LOGIN/WORLD_HOP/GE — those expect specific widgets). Tune in 6b once recovery exists.
- **LeakCheck false positives** for legitimate long precision scopes (e.g., Vorkath kill = 30+ seconds). Plan accepts this as a logging-only finding in 6a; Plan 6b's recovery decides whether to act. Tasks doing intentional long precision can override `LeakCheck` later.

## 11. Brainstorming resolutions

| # | Question | Resolution |
|---|----------|------------|
| 1 | Signal scope | C — 8 signals, 4 real + 4 stubbed-true with new SPI hooks |
| 2 | ActionStakes wiring | A — per-method declaration via `withServiceCall(stakes = ...)`, throws ConfidenceTooLow, new UNCONFIDENT outcome |
| 3 | Watchdog execution model | B — coroutine on dedicated single-thread dispatcher (`WatchdogScope`) |
| 4 | Watchdog check coverage | A — all 4 checks (STALL/UNCERTAIN/DEADLOCK/LEAK), Runner heartbeat + LEAK 30s ceiling included |
| 5 | Confidence cache + recompute | B — 600ms TTL invalidated on ServiceCallEnd; recompute on next read if stale |

## 12. References

- Foundation spec §11: `2026-04-21-buildcore-foundation-design.md`
- Plan 2 (Runner, Task SPI): `2026-04-21-plan-2-core-runtime.md`
- Plan 3 (BusEvent, EventBus, PrivacyScrubber, LoggerScope): `2026-04-23-plan-3-logging-eventbus.md`
- Plan 4b (PrecisionGate scope tracking): `2026-04-25-plan-4b-precision-breaks-misclick.md`
- Plan 5a (withServiceCall, ServiceCallEnd events): `2026-04-25-plan-5a-service-infrastructure.md`
