# BuildCore Plan 3 — Logging + Event Bus Design

**Status:** Spec (approved for implementation planning)
**Date:** 2026-04-23
**Author:** Chich
**Parent spec:** [`2026-04-21-buildcore-foundation-design.md`](2026-04-21-buildcore-foundation-design.md) §13
**Predecessors:** Plan 1 (Bootstrap), Plan 2 (Core Runtime + Task SPI + Restrictions)

---

## 1. Overview

Plan 3 turns the Plan 1 `EventBus` primitive into a full structured-logging subsystem. It introduces a session lifecycle, a dedicated logger thread, three concrete subscribers (one JSONL writer, one human-readable writer, one performance aggregator), a privacy scrubber, and compile-time architecture tests that keep later plans honest.

Plan 3 does **not** ship `TelemetryClient` (Plan 8), `ReplayRecorder` (Plan 4), `WatchdogSubscriber` (Plan 6), `FatigueModel` (Plan 4), or `GuiLiveStatus` (Plan 10). Those subscribers are named as interfaces and wired as no-op implementations so the `SubscriberRegistry` plumbing is exercised end-to-end; real impls drop in without touching Plan 3 code.

### 1.1 Goals

- Every `BusEvent` flowing through the bus gets written to disk with full fidelity and correlation IDs.
- A separate human-readable log exists for operators, with privacy scrubbing applied and log-level filtering.
- Session lifecycle (start/end/summary) becomes a first-class concept with an enforced single owner.
- Later plans cannot add a new `BusEvent` subtype without also wiring a privacy scrubber entry — enforced at compile time.
- Slow or crashed subscribers cannot block emitters or the fast-path writer thread.
- Bootstrap (plugin `startUp`) does not become a suspending function.

### 1.2 Non-goals

- HTTP shipping to BuildCore-Server (Plan 8).
- RNG state capture for replay (Plan 4).
- Watchdog-driven recovery (Plan 6).
- GUI wiring — Log level is env-var / system-property only in Plan 3; GUI shell owns it in Plan 10.
- Compression of rotated log files.
- User-action event subtypes (`UserStartedPlan`, `UserEditedPlan`) — those land in Plan 7 alongside the Config subsystem that emits them.

---

## 2. Scope summary (decision log)

| Decision | Outcome |
|---|---|
| Subscriber scope | Minimal-core: `LocalJsonlWriter`, `LocalSummaryWriter`, `PerformanceAggregator`, plus `NoOpTelemetrySubscriber` / `NoOpReplaySubscriber` placeholders |
| File layout | Per-session dir `~/.vitalclient/buildcore/logs/<sessionId>/`; `events.jsonl` + `summary.log` + `session.meta.json` |
| Rotation | 10 MB size cap on `events.jsonl` with `.1`/`.2`/`.3` rollover; `summary.log` head-truncated at 10 MB |
| Retention | 30 most recent session directories; older pruned at startup |
| New `BusEvent` subtypes | Only the nine Plan 3 actually emits; later plans add their own |
| Taxonomy enforcement | Architecture test compels every new `BusEvent` subtype to register a `PrivacyScrubber` case (via exhaustive `when` + reflection belt-and-braces) |
| Dispatcher model | Single-thread `newSingleThreadContext("buildcore-logger")`; all fast-path subscribers share the thread; writes serialize for free |
| Correlation IDs | Pull `taskInstanceId: UUID?` and `moduleId: String?` up to the `BusEvent` sealed interface |
| Session ownership | Thin `SessionManager` owned by plugin bootstrap; mints sessionId, emits `SessionStart`/`SessionEnd`/`SessionSummary`, injects sessionId into Runner |
| Fatal-path emit | Uncaught handlers use `bus.tryEmit`; last-resort fallback is `System.err` |

---

## 3. Package layout

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/
├── events/
│   ├── BusEvent.kt                 # MODIFY — pull taskInstanceId/moduleId to interface; add 9 subtypes
│   ├── EventBus.kt                 # unchanged from Plan 1
│   ├── PrivacyScrubber.kt          # CREATE — pure fun over sealed hierarchy
│   └── SubscriberRegistry.kt       # CREATE — append-only registration surface
│
└── logging/
    ├── LoggerScope.kt              # CREATE — single-thread dispatcher + scope lifecycle
    ├── SessionManager.kt           # CREATE — mints sessionId, emits session lifecycle
    ├── LogLevel.kt                 # CREATE — enum + event→level mapping
    ├── LogConfig.kt                # CREATE — data class loaded from env/sysprops
    ├── LogDirLayout.kt             # CREATE — per-session dir path + retention prune
    ├── RotatingFileSink.kt         # CREATE — 10 MB rotation helper
    ├── LocalJsonlWriter.kt         # CREATE — fast-path subscriber, full fidelity, unscrubbed
    ├── LocalSummaryWriter.kt       # CREATE — fast-path subscriber, scrubbed + level-filtered
    ├── PerformanceAggregator.kt    # CREATE — periodic sampler, re-emits PerformanceSample
    ├── BoundedChannelSubscriber.kt # CREATE — base class for slow-path subscribers
    ├── TelemetrySubscriber.kt      # CREATE — interface only (Plan 8 provides real impl)
    ├── ReplaySubscriber.kt         # CREATE — interface only (Plan 4 provides real impl)
    ├── NoOpTelemetrySubscriber.kt  # CREATE — default no-op wired at bootstrap
    ├── NoOpReplaySubscriber.kt     # CREATE — default no-op wired at bootstrap
    └── UncaughtExceptionHandler.kt # CREATE — emits UnhandledException via tryEmit

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/
│   └── PrivacyScrubberTest.kt
├── core/logging/
│   ├── SessionManagerTest.kt
│   ├── LocalJsonlWriterTest.kt
│   ├── LocalSummaryWriterTest.kt
│   ├── PerformanceAggregatorTest.kt
│   ├── RotatingFileSinkTest.kt
│   ├── LogDirLayoutTest.kt
│   └── LoggerScopeOrderingTest.kt
├── arch/
│   └── LoggingArchitectureTest.kt  # 8 architecture invariants
└── integration/
    └── PluginBootstrapIntegrationTest.kt
```

---

## 4. Component architecture

### 4.1 Composition root

Wired in Plan 1's `BuildCorePlugin.startUp()`:

```kotlin
override fun startUp()
{
    eventBus = EventBus()
    loggerScope = LoggerScope()
    sessionManager = SessionManager(eventBus, loggerScope)

    subscriberRegistry = SubscriberRegistry().also
    {
        val sessionDir = LogDirLayout.sessionDir(sessionManager.sessionId)
        it.register(LocalJsonlWriter(sessionDir))
        it.register(LocalSummaryWriter(sessionDir, level = LogConfig.load().level))
        it.register(PerformanceAggregator(intervalMillis = 300_000))
        it.register(NoOpTelemetrySubscriber())
        it.register(NoOpReplaySubscriber())
    }
    subscriberRegistry.attachAll(eventBus, loggerScope)

    sessionManager.start()
    installUncaughtExceptionHandler(bus = eventBus, sessionId = sessionManager.sessionId)

    runner = Runner(eventBus, sessionManager.sessionId, …)
}

override fun shutDown()
{
    runner.requestStop()
    runBlocking { sessionManager.requestStop() }
    loggerScope.close()
}
```

### 4.2 `LoggerScope`

```kotlin
class LoggerScope : AutoCloseable
{
    @OptIn(DelicateCoroutinesApi::class)
    private val dispatcher = newSingleThreadContext("buildcore-logger")
    private val job = SupervisorJob()
    val coroutineScope = CoroutineScope(dispatcher + job + CoroutineName("LoggerScope"))

    suspend fun drain(deadlineMillis: Long = 500)
    {
        withTimeoutOrNull(deadlineMillis) { job.children.forEach { it.join() } }
    }

    override fun close()
    {
        job.cancel()
        dispatcher.close()
    }
}
```

All fast-path subscribers launch one collector on this scope. Because there is exactly one thread in the dispatcher, handle calls across subscribers are serialized per event. No mutex around file writes. Order of subscribers per event is the order they were registered.

### 4.3 `SubscriberRegistry`

Append-only. No `unregister`. Plugins attach at boot and detach at session end. Matches spec §13 "subscribers attach at startup and consume forward".

```kotlin
class SubscriberRegistry
{
    private val subscribers = mutableListOf<LogSubscriber>()

    fun register(s: LogSubscriber): SubscriberRegistry { subscribers += s; return this }

    fun attachAll(bus: EventBus, scope: LoggerScope)
    {
        subscribers.forEach { it.attach(bus, scope) }
    }

    suspend fun drainAll() { subscribers.forEach { it.drain() } }
}
```

### 4.4 `LogSubscriber` contract

```kotlin
interface LogSubscriber
{
    val name: String
    val isFastPath: Boolean
    fun attach(bus: EventBus, loggerScope: LoggerScope)
    suspend fun drain()
}
```

Fast-path subscribers (`LocalJsonlWriter`, `LocalSummaryWriter`, `PerformanceAggregator`):

```kotlin
override fun attach(bus: EventBus, loggerScope: LoggerScope)
{
    loggerScope.coroutineScope.launch { bus.events.collect { event -> handle(event) } }
}
```

Slow-path subscribers (`TelemetrySubscriber`, `ReplaySubscriber`) extend `BoundedChannelSubscriber`:

```kotlin
abstract class BoundedChannelSubscriber(
    private val capacity: Int = 1024,
    private val overflowTombstone: (dropped: Int) -> BusEvent
) : LogSubscriber
{
    private val channel = Channel<BusEvent>(capacity, BufferOverflow.DROP_OLDEST)
    private val droppedCount = AtomicInteger(0)
    private var ownScope: CoroutineScope? = null

    override fun attach(bus: EventBus, loggerScope: LoggerScope)
    {
        ownScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName(name))
        loggerScope.coroutineScope.launch {
            bus.events.collect { event ->
                if (!channel.trySend(event).isSuccess) droppedCount.incrementAndGet()
            }
        }
        ownScope!!.launch {
            for (event in channel)
            {
                process(event)
                val dropped = droppedCount.getAndSet(0)
                if (dropped > 0) bus.tryEmit(overflowTombstone(dropped))
            }
        }
    }

    abstract suspend fun process(event: BusEvent)
}
```

### 4.5 Data flow for one event

```
emitter  (Runner, SessionManager, RestrictionEngine, UncaughtHandler)
    │ bus.emit(event)      — suspending, common case
    │ bus.tryEmit(event)   — fatal / non-coroutine paths
    ▼
MutableSharedFlow<BusEvent>  (replay=0, extraBufferCapacity=256)
    │ collect on buildcore-logger thread
    ▼
┌────────────────────┬──────────────────────┬──────────────────┐
│ LocalJsonlWriter   │ LocalSummaryWriter   │ PerfAggregator   │
│ raw event          │ scrub → filter level │ increment counts │
│ → events.jsonl     │ → summary.log        │ → tick 5min      │
└────────────────────┴──────────────────────┴──────────────────┘
                                                   │
                                                   └─ emit PerformanceSample back to bus

(future) TelemetrySubscriber / ReplaySubscriber — fan out via their own channels; overflow → tombstone event
```

---

## 5. `BusEvent` taxonomy changes

### 5.1 Interface additions

Plan 3 pulls two fields up to the `BusEvent` sealed interface:

```kotlin
sealed interface BusEvent
{
    val eventId: UUID
    val timestamp: Instant
    val sessionId: UUID
    val schemaVersion: Int
    val taskInstanceId: UUID?   // ADDED
    val moduleId: String?       // ADDED
}
```

Plan 2's 13 existing subtypes (task-lifecycle + method/path + `TestPing`) are rewritten to `override val taskInstanceId`. Events without a task context (`SessionStart`, `SessionEnd`, `PerformanceSample`, `UnhandledException`) override with `null`. The interface declares them nullable; subtypes narrow to non-null where applicable via override.

### 5.2 New subtypes (9)

| Subtype | Emitter | When | Key fields (beyond interface) |
|---|---|---|---|
| `SessionStart` | `SessionManager.start()` | once at plugin boot | `buildcoreVersion`, `archetype?`, `launchMode` |
| `SessionEnd` | `SessionManager.requestStop()` | once at shutdown | `reason: StopReason (USER/CRASH/SAFE_STOP/UPDATE)` |
| `SessionSummary` | `SessionManager.requestStop()` | before `SessionEnd` | `durationMillis`, `taskCounts: Map<String, TaskCounter>`, `totalEvents` |
| `SafeStopRequested` | `Runner` / `SafeStopContract` | when stop orchestrator begins | `reason: String` |
| `SafeStopCompleted` | `Runner` / `SafeStopContract` | when the final task reports `canStopNow=true` | `durationMillis` |
| `UnhandledException` | `UncaughtExceptionHandler` | any uncaught throwable on a BuildCore thread | `threadName`, `exceptionClass`, `message: String`, `stackTrace: String` |
| `ValidationFailed` | `Runner` (for plan validation) / `ModuleRegistry` (for module shape) | structural validation rejects a plan or module | `subject: String`, `detail: String` |
| `RestrictionViolated` | `RestrictionEngine` (Plan 2, modified) | three-moment validator rejects an effect | `restrictionId`, `effectSummary`, `moment: Moment (EDIT/START/RUNTIME)` |
| `PerformanceSample` | `PerformanceAggregator` | every 5 min | `intervalSeconds`, `eventRatePerSec`, `jvmHeapUsedMb`, `jvmHeapMaxMb`, `loggerLagMaxMs`, `droppedEventsSinceLastSample` |

### 5.3 `TaskValidated` vs `ValidationFailed`

Plan 2 already emits `TaskValidated(pass: Boolean, rejectReason: String?)` for individual task validation. `ValidationFailed` is strictly for higher-scope validation (a `Plan` or `Module` rejected at load time) and does not overlap. A failed task validation emits only `TaskValidated(pass=false, …)`.

---

## 6. Privacy scrubber

### 6.1 Implementation

```kotlin
object PrivacyScrubber
{
    fun scrub(event: BusEvent): BusEvent = when (event)
    {
        is SessionStart        -> event
        is SessionEnd          -> event
        is SessionSummary      -> event
        is TaskQueued          -> event
        is TaskValidated       -> event.copy(rejectReason = event.rejectReason?.let(::scrubString))
        is TaskStarted         -> event
        is TaskProgress        -> event
        is TaskCompleted       -> event
        is TaskFailed          -> event.copy(reasonDetail = scrubString(event.reasonDetail))
        is TaskRetrying        -> event
        is TaskSkipped         -> event.copy(reason = scrubString(event.reason))
        is TaskPaused          -> event.copy(reason = scrubString(event.reason))
        is TaskResumed         -> event
        is MethodPicked        -> event
        is PathPicked          -> event
        is SafeStopRequested   -> event
        is SafeStopCompleted   -> event
        is UnhandledException  -> event.copy(stackTrace = scrubStackTrace(event.stackTrace),
                                              message = scrubString(event.message))
        is ValidationFailed    -> event.copy(detail = scrubString(event.detail))
        is RestrictionViolated -> event
        is PerformanceSample   -> event
        is TestPing            -> event.copy(payload = "«scrubbed»")
    }

    private fun scrubString(s: String): String = s
        .replace(Regex("""(?i)(password|token|apikey|bearer)\s*[=:]\s*\S+"""), "$1=«redacted»")
        .replace(Regex("""(?i)username\s*[=:]\s*(\S+)""")) { m -> "username=${hmacHex(m.groupValues[1])}" }

    private fun scrubStackTrace(trace: String): String =
        trace.lines().joinToString("\n") { scrubString(it) }

    private fun hmacHex(value: String): String
    {
        // HMAC-SHA256 keyed by sessionId, 12 hex chars, stable within a session
        // key rotates per session — cannot cross-correlate a username across sessions
    }
}
```

### 6.2 Who calls `scrub`

| Subscriber | Scrubs? | Rationale |
|---|---|---|
| `LocalJsonlWriter` | no | Local disk is considered private app data; raw fidelity preserved for replay tooling |
| `LocalSummaryWriter` | yes | Human-readable log may be shared when asking for support |
| `TelemetrySubscriber` (future) | yes | Crosses the network boundary |
| `ReplaySubscriber` (future) | no | Replay requires byte-identical events; replay files already scoped to local disk |

This matches spec §13: *"Local disk gets everything; telemetry samples the high-volume."*

---

## 7. File format & layout

### 7.1 Directory structure

```
${BUILDCORE_LOG_DIR:-$HOME/.vitalclient/buildcore/logs}/
├── <sessionId-uuid>/
│   ├── events.jsonl              current (unscrubbed, full fidelity)
│   ├── events.jsonl.1            previous rotation (10 MB each)
│   ├── events.jsonl.2
│   ├── events.jsonl.3
│   ├── summary.log               human-readable, scrubbed, level-filtered
│   └── session.meta.json         quick-index metadata
└── <older-sessionId>/            pruned at startup if >30 dirs exist
```

`BUILDCORE_LOG_DIR` env var overrides the root (tests use `tmp/`). Per-session dir is created when `SessionManager.start()` fires.

### 7.2 `events.jsonl` line format

One JSON object per line, one event per line, newline-terminated.

```json
{"type":"TaskStarted","eventId":"b34e…","timestamp":"2026-04-23T18:22:04.331Z","sessionId":"7f1a…","schemaVersion":1,"taskInstanceId":"d2a0…","moduleId":"combat.cow-killer","taskId":"cow-kill","methodId":"lumbridge-cows","pathId":"ironman-path"}
```

- `type` = simple class name of the subtype. Jackson with a sealed-class-walking `@JsonTypeInfo` resolver.
- `timestamp` = ISO-8601 UTC (`Instant.toString()`).
- No free-form fields. Line size bounded by subtype field set.
- Jackson `jackson-module-kotlin` (already transitive via RuneLite). No second JSON library introduced.

### 7.3 `summary.log` line format

Human-readable. Fixed-column for easy eyeball + basic awk/grep.

```
2026-04-23 14:22:04.331 EDT  INFO  task   [cow-kill]       started via method=lumbridge-cows path=ironman-path
2026-04-23 14:22:07.882 EDT  WARN  retry  [cow-kill]       attempt 2/3 backoff=1500ms reason=NoBankWindow
2026-04-23 14:22:31.004 EDT  ERROR restr  [buy-bonds]      RestrictionViolated: IRONMAN.NoTrade vs Effect.AcquiresItem(via=TRADE)
```

- Timestamp in EDT (matches VitalExchange-Server convention).
- Level column 5 chars fixed-width; category column 6 chars fixed-width.
- Subject `[moduleId]` or `[taskId]` bracketed, 16 chars fixed-width (truncated with `…`).
- Message body is subtype-specific, produced by `LocalSummaryWriter.formatLine(event)` — one `when` arm per handled subtype; unhandled subtypes skipped silently.

### 7.4 Which subtypes go to `summary.log`

Spec §13 "Task lifecycle + Safety + Session" plus errors:

- Session: `SessionStart`, `SessionEnd`, `SessionSummary`
- Task lifecycle: `TaskQueued`, `TaskValidated`, `TaskStarted`, `TaskCompleted`, `TaskFailed`, `TaskRetrying`, `TaskSkipped`, `TaskPaused`, `TaskResumed`
- Method/Path: `MethodPicked`, `PathPicked`
- Safe stop: `SafeStopRequested`, `SafeStopCompleted`
- Errors: `UnhandledException`, `ValidationFailed`, `RestrictionViolated`

Not in summary: `TaskProgress` (too chatty, only in jsonl), `PerformanceSample` (numeric, only in jsonl), `TestPing` (internal).

### 7.5 Rotation

`RotatingFileSink` checks `events.jsonl` size after each write. When ≥ 10 MB:

1. flush, close
2. rename `.3 → .4` (deleted if exists), `.2 → .3`, `.1 → .2`, current `.jsonl → .1`
3. open new `.jsonl`

Three rotation files per session ⇒ 30 MB hard cap per session on jsonl.

`summary.log` is size-capped at 10 MB with head-truncation (drop oldest lines, keep newest). The jsonl is the source of truth; the summary is a view.

### 7.6 `session.meta.json`

Written once at `SessionStart`, updated once at `SessionEnd`. Fast index for tooling that lists sessions without scanning `events.jsonl`.

```json
{
  "sessionId": "7f1a…",
  "startedAt": "2026-04-23T18:22:04.331Z",
  "endedAt": "2026-04-23T20:04:12.019Z",
  "buildcoreVersion": "0.1.0",
  "state": "ended",
  "logLevel": "INFO",
  "eventCount": 4182,
  "droppedEventCount": 0
}
```

### 7.7 Compression and UTC / local

- No compression in Plan 3. Rotated files stay plain text so users can still `tail`/`grep` during a session.
- `events.jsonl` always UTC; `summary.log` always EDT. Tooling reads jsonl, humans read summary — each gets the form that serves it.

---

## 8. Session lifecycle

### 8.1 `SessionManager`

```kotlin
class SessionManager(
    private val bus: EventBus,
    private val loggerScope: LoggerScope,
    private val clock: Clock = Clock.systemUTC(),
    private val buildcoreVersion: String = BuildCoreVersion.CURRENT
)
{
    val sessionId: UUID = UUID.randomUUID()
    private val startedAt: Instant = clock.instant()
    private val taskCounters = ConcurrentHashMap<String, TaskCounter>()
    private val totalEvents = AtomicLong(0)
    private val ended = AtomicBoolean(false)

    fun start()
    {
        LogDirLayout.pruneOldSessions(keep = LogConfig.load().retentionSessions)
        LogDirLayout.createSessionDir(sessionId)
        writeSessionMeta(state = "running")

        bus.tryEmit(SessionStart(
            eventId = UUID.randomUUID(),
            timestamp = startedAt,
            sessionId = sessionId,
            schemaVersion = 1,
            taskInstanceId = null,
            moduleId = null,
            buildcoreVersion = buildcoreVersion,
            archetype = null,
            launchMode = LaunchMode.NORMAL
        ))

        loggerScope.coroutineScope.launch {
            bus.events.collect { e -> updateCounters(e) }
        }
    }

    suspend fun requestStop(reason: StopReason = StopReason.USER)
    {
        if (!ended.compareAndSet(false, true)) return
        val durationMs = Duration.between(startedAt, clock.instant()).toMillis()

        bus.emit(SessionSummary(
            sessionId = sessionId, durationMillis = durationMs,
            taskCounts = taskCounters.toMap(), totalEvents = totalEvents.get(), /* … */
        ))
        bus.emit(SessionEnd(sessionId = sessionId, reason = reason))

        loggerScope.drain(deadlineMillis = 500)
        writeSessionMeta(state = "ended")
    }

    private fun updateCounters(e: BusEvent)
    {
        totalEvents.incrementAndGet()
        when (e)
        {
            is TaskStarted   -> counter(e.taskId).started.incrementAndGet()
            is TaskCompleted -> counter(e.taskId).completed.incrementAndGet()
            is TaskFailed    -> counter(e.taskId).failed.incrementAndGet()
            is TaskSkipped   -> counter(e.taskId).skipped.incrementAndGet()
            else             -> { /* not counted per-task */ }
        }
    }

    private fun counter(taskId: String) = taskCounters.getOrPut(taskId) { TaskCounter() }
}
```

### 8.2 `SessionStart` uses `tryEmit`

Bootstrap runs on the plugin's main thread; making `startUp()` a suspend function would ripple into RuneLite's plugin lifecycle. `tryEmit` is safe because the bus has 256 buffer slots and no subscribers are slow at boot — the only caller is a single thread emitting a single event.

### 8.3 `Runner` stops minting sessionIds

Plan 2's `Runner` currently runs `private val sessionId: UUID = UUID.randomUUID()`. Plan 3 removes this. `Runner`'s constructor takes `sessionId: UUID` as a required argument. Every test that instantiates `Runner` must supply one; `TaskContext.sessionId` stops defaulting.

---

## 9. Error-event emitters

### 9.1 `UnhandledException`

```kotlin
fun installUncaughtExceptionHandler(bus: EventBus, sessionId: UUID)
{
    val prior = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val event = UnhandledException(
            sessionId = sessionId,
            threadName = thread.name,
            exceptionClass = throwable.javaClass.name,
            message = throwable.message ?: "",
            stackTrace = throwable.stackTraceToString()
        )
        val emitted = bus.tryEmit(event)
        if (!emitted) System.err.println("[buildcore] bus full; event dropped: $event")
        prior?.uncaughtException(thread, throwable)  // don't swallow — chain
    }
}
```

Never suspends; never throws. If the bus buffer is full and no subscriber drains in time, the last-resort `System.err` fallback ensures the exception is still visible.

### 9.2 `ValidationFailed` vs `TaskValidated`

- `TaskValidated(pass=false, rejectReason=…)` — individual task validation result. Plan 2 already emits this. Unchanged.
- `ValidationFailed(subject, detail)` — higher-scope validation. Emitted by `ModuleRegistry` when a module's structure is invalid, and by the Plan loader (Plan 7) when a saved plan fails schema. Plan 3 ships the event type and wires `ModuleRegistry` to emit it; the Plan loader wires in Plan 7.

### 9.3 `RestrictionViolated`

`RestrictionEngine` (Plan 2) is currently a pure function with no bus reference. Plan 3 modifies it:

```kotlin
class RestrictionEngine(private val bus: EventBus? = null)
{
    fun validate(profile: Profile, effects: List<Effect>, moment: Moment): ValidationResult
    {
        val result = pureValidate(profile, effects, moment)
        if (result is ValidationResult.Reject && bus != null)
        {
            result.rejections.forEach { rejection ->
                bus.tryEmit(RestrictionViolated(
                    sessionId = SessionContext.current,
                    restrictionId = rejection.restriction.id,
                    effectSummary = rejection.effect.summary(),
                    moment = moment
                ))
            }
        }
        return result
    }
}
```

Pure-function behaviour preserved (`bus = null` in tests, results collected from return value). When `bus` is non-null the engine additionally emits events; the tests for pure validation still pass.

---

## 10. Performance contract

| Property | Guarantee | Verification |
|---|---|---|
| Bus `emit` non-blocking in common case | `extraBufferCapacity=256` > typical burst | Plan 1 test (unchanged) |
| Fast-path writes don't block emitter thread | Collectors on `buildcore-logger` dispatcher | Unit test: emit from `runBlocking` on one thread, assert write happened on `buildcore-logger` |
| Write order preserved per sessionId | Single-thread dispatcher | Stress test: 10 k concurrent emits, jsonl line order matches emission timestamps |
| Slow-subscriber overflow doesn't block anything | `DROP_OLDEST` + `trySend` | Unit test: fill channel, emit, assert no suspend, assert tombstone on drain |
| Shutdown drains within 500 ms or cancels | `LoggerScope.drain(500)` deadline | Unit test: stall one writer, assert `drain` returns within budget + cancellation fires |
| Total added threads at steady state | 1 (`buildcore-logger`) | Integration test: count BuildCore-named threads after boot |

### 10.1 `PerformanceAggregator`

- Tick interval: 5 minutes (`LogConfig.performanceSampleIntervalMillis`).
- Metrics per tick:

| Field | Source |
|---|---|
| `intervalSeconds` | elapsed since last tick |
| `eventRatePerSec` | counter / elapsed |
| `jvmHeapUsedMb` | `Runtime.totalMemory() - freeMemory()` ÷ 1 MB |
| `jvmHeapMaxMb` | `Runtime.maxMemory()` ÷ 1 MB |
| `loggerLagMaxMs` | max `(handleInstant - event.timestamp)` observed this interval |
| `droppedEventsSinceLastSample` | summed from slow-subscriber overflow tombstones |

- Re-emits `PerformanceSample(…)` on the bus. `LocalJsonlWriter` catches it; no direct file write.
- No cross-session state.

---

## 11. Configuration surface

```kotlin
data class LogConfig(
    val level: LogLevel = LogLevel.INFO,
    val logRootDir: Path = defaultLogRoot(),
    val retentionSessions: Int = 30,
    val rotationSizeBytes: Long = 10L * 1024 * 1024,
    val performanceSampleIntervalMillis: Long = 300_000,
    val summaryCapBytes: Long = 10L * 1024 * 1024
)
{
    companion object
    {
        fun load(): LogConfig { /* env + sysprops + defaults */ }

        private fun defaultLogRoot(): Path =
            System.getenv("BUILDCORE_LOG_DIR")?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), ".vitalclient", "buildcore", "logs")
    }
}
```

| Setting | Env var | System property | Default |
|---|---|---|---|
| Log level | `BUILDCORE_LOG_LEVEL` | `-Dbuildcore.log.level` | `INFO` |
| Log root | `BUILDCORE_LOG_DIR` | `-Dbuildcore.log.dir` | `~/.vitalclient/buildcore/logs` |
| Retention | `BUILDCORE_LOG_RETENTION_SESSIONS` | `-Dbuildcore.log.retention` | 30 |
| Rotation size | `BUILDCORE_LOG_ROTATION_MB` | `-Dbuildcore.log.rotation.mb` | 10 |
| Perf sample interval | `BUILDCORE_PERF_INTERVAL_MS` | `-Dbuildcore.perf.interval.ms` | 300 000 |

GUI (Plan 10) and CLI flags (Plan 7) read the same `LogConfig` class once they land.

### 11.1 Log level filtering

`LogLevel` applies only to `LocalSummaryWriter`. `events.jsonl` always gets every event.

| Level | Emits |
|---|---|
| `DEBUG` | every subtype handled by summary writer |
| `INFO` (default) | all task/session/safe-stop/method-path |
| `WARN` | task retrying, skipped, paused, `ValidationFailed`, `RestrictionViolated` |
| `ERROR` | `TaskFailed`, `UnhandledException` |
| `FATAL` | `UnhandledException` only |

A `BusEvent` → `LogLevel` table lives in `LogLevel.kt` as a single `when` expression keyed by subtype. Adding a new subtype later requires adding an entry (or falling through to `DEBUG`); the architecture test enforces coverage.

---

## 12. Architecture tests

All in `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`, each with KDoc citing the spec section it enforces.

| # | Test | Enforces |
|---|---|---|
| 1 | `every_BusEvent_subtype_has_a_scrubber_case` | Reflection: for each sealed subtype of `BusEvent`, call `PrivacyScrubber.scrub(instance)` — must not throw `NotImplementedError` / `IllegalStateException`. Backup for the exhaustive `when`. |
| 2 | `no_free_form_Map_or_Any_fields_in_BusEvent_subtypes` | Konsist: subtypes' properties must be typed. No `Map<String, Any?>`, no `Any`, no `String` named `json`/`payload`/`extra`. |
| 3 | `correlation_ids_populated` | Konsist: every `BusEvent` subtype overrides `eventId`, `timestamp`, `sessionId`, `schemaVersion`. `taskInstanceId`/`moduleId` declared (may be `null`). |
| 4 | `logging_package_cannot_import_Runner_internals` | Konsist: `core.logging.*` does not reference `core.task.Runner` or `core.task.TaskInstance`. One-way dependency. |
| 5 | `MutableSharedFlow_only_in_events_package` | Extends Plan 2's existing layering test to cover `core.logging`. Writers may not own their own SharedFlow. |
| 6 | `PrivacyScrubber_is_pure` | Konsist: no mutable fields, no companion `var`, no calls out to `java.io` / `java.nio.file`. Paired with a 1 000-iteration unit test asserting stable outputs. |
| 7 | `log_dir_path_goes_through_LogDirLayout` | Konsist: no string literal `"logs"` or `.vitalclient` outside `LogDirLayout.kt`. Single source of path truth. |
| 8 | `fatal_path_uses_tryEmit` | Konsist: `UncaughtExceptionHandler.kt`'s call graph never reaches `bus.emit` — only `bus.tryEmit`. Prevents a suspending call from a fatal, non-coroutine context. |

---

## 13. Testing strategy

| Layer | Test file | Technique |
|---|---|---|
| Unit | `SessionManagerTest` | `runTest`, virtual clock, `TestBusCollector` |
| Unit | `LocalJsonlWriterTest` | write N events, read back, assert line count + JSON roundtrip |
| Unit | `LocalSummaryWriterTest` | per-level filter, fixed-width snapshot, scrub verification |
| Unit | `PerformanceAggregatorTest` | `TestDispatcher` `advanceTimeBy(5min)`, assert `PerformanceSample` emitted with expected metrics |
| Unit | `PrivacyScrubberTest` | per-subtype golden files + idempotency property `scrub(scrub(e)) == scrub(e)` + negative tests (password never survives) |
| Unit | `RotatingFileSinkTest` | write > 10 MB, assert `.jsonl.1` exists and `.jsonl` reopened |
| Unit | `LogDirLayoutTest` | `tmp/` root, create + prune, 30-session cap, sessionId UUID format |
| Unit | `SubscriberRegistryTest` | attach ordering, drain completes for all, fast-path vs slow-path separation |
| Stress | `LoggerScopeOrderingTest` | 10 k concurrent emits across threads, assert jsonl line order matches emission timestamps |
| Arch | `LoggingArchitectureTest` | the 8 invariants in §12 |
| Integration | `PluginBootstrapIntegrationTest` | boot plugin with headless mode, run `NoOpTask`, assert session dir populated, `SessionStart`/`SessionEnd` bracket log, scrubber applied to summary |

Target: ~25 new tests. Plan 2's 59 → Plan 3 end: ~84 passing.

---

## 14. What stays deferred

| Item | Lands in | Reason |
|---|---|---|
| `TelemetryClient` HTTP impl | Plan 8 | Needs BuildCore-Server endpoints |
| `ReplayRecorder` with RNG state | Plan 4 | Needs antiban RNG design |
| `WatchdogSubscriber` | Plan 6 | Needs confidence / watchdog subsystem |
| `FatigueModel` | Plan 4 | Needs antiban model |
| `GuiLiveStatus` | Plan 10 | Needs GUI Shell tabs |
| Log rotation compression | future | low priority; disk usage not yet a pain |
| GUI log-level toggle | Plan 10 | env var sufficient until GUI exists |
| CLI `--log-level=DEBUG` | Plan 7 | CLI argument parser lives in Config plan |
| `InputAction` event subtype | Plan 4 | emitted by antiban layer |
| `UserStartedPlan` / `UserStoppedPlan` / `UserEditedPlan` | Plan 7 | emitted by Config subsystem |
| `LicenseValidated` / `LicenseRevoked` / `UpdateAvailable` | Plan 9 | emitted by licensing client |
| `ServiceCallStart` / `ServiceCallEnd` | Plan 5 | emitted by service layer |
| Antiban + wilderness + confidence events (~16 subtypes) | Plans 4 / 6 | emitted alongside the features |

When each plan adds its subtypes, the exhaustive `when` in `PrivacyScrubber` — reinforced by architecture test #1 — fails compilation until a scrubber case is supplied. Correlation-ID test #3 and free-form-field test #2 likewise enforce shape. New plans extend; Plan 3's surface stays frozen.

---

## 15. Open questions

None blocking. Two defaults flagged during brainstorming, both accepted:

1. `SubscriberRegistry` is append-only; no `unregister`. Revisit if a plugin hot-reload story emerges.
2. `SessionStart` uses `tryEmit`. Revisit only if plugin bootstrap becomes suspend-friendly.
