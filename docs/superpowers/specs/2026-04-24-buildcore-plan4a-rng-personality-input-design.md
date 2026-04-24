# BuildCore Plan 4a — RNG + Personality + Input Primitives Design

**Status:** Spec (approved for implementation planning)
**Date:** 2026-04-24
**Author:** Chich
**Parent spec:** [`2026-04-21-buildcore-foundation-design.md`](2026-04-21-buildcore-foundation-design.md) §9
**Predecessors:** Plan 1 (Bootstrap), Plan 2 (Core Runtime + Task SPI + Restrictions), Plan 3 (Logging + Event Bus)

---

## 1. Overview

Plan 4a builds the deterministic, stateless foundation of the antiban layer: the RNG layer, per-account `PersonalityVector`, the Mouse/Keyboard/Camera API with real VitalAPI wiring, the WindMouse curve algorithm, and a session-scoped fatigue multiplier. It ships the primitives every subsequent plan composes against.

Plan 4 has been decomposed into three sub-plans:

- **4a (this spec)** — RNG + Personality + Input primitives + WindMouse + fatigue + throttle integration.
- **4b** — Precision Mode, 4-tier break system, Safe-Stop integration, `@UsesPrecisionInput` enforcement.
- **4c** — ReplayRecorder (replaces Plan 3's `NoOpReplaySubscriber`), ReplayRng decorator, ReplayServices. Likely lands after Plan 5 since replay intercepts service calls.

### 1.1 Goals

- Every antiban RNG draw is deterministic from a recorded seed (Plan 4c can replay).
- Every per-account personality is stable across code changes (persisted on disk, not recomputed from the vector generator).
- No raw VitalAPI input calls anywhere outside three named backend files — enforced by architecture test.
- Every Mouse/Keyboard/Camera primitive call emits an `InputAction` on the bus.
- Mouse movement uses the WindMouse algorithm with personality-parameterized gravity, wind, and overshoot.
- All reaction delays compose `personality × fatigue × graduatedThrottle` per foundation spec §9.
- Plan 3's existing `NoOpReplaySubscriber` stays in place — 4c replaces it later.

### 1.2 Non-goals

- Precision Mode (`InputMode.PRECISION` / `InputMode.SURVIVAL`) — defined in the enum for schema stability, but primitives throw if passed anything other than `NORMAL`. Plan 4b lifts the restriction.
- `@UsesPrecisionInput` annotation and build-time enforcement — Plan 4b.
- Break scheduling, Micro/Normal/Bedtime/Banking tiers, Safe-Stop integration — Plan 4b.
- ReplayRecorder / ReplayRng / ReplayServices — Plan 4c.
- Login hook that binds the real username — Plan 5 (antiban accepts an *ephemeral* personality before login, per §2 decision Q3).
- Misclick injection at the service layer (`FatigueCurve.misclickMultiplier` is wired but has no service consumer yet) — Plan 4b.
- Personality editing GUI / manual regeneration — Plan 10.

---

## 2. Scope summary (decision log)

| # | Decision | Outcome |
|---|---|---|
| Q1 | Scope of Plan 4 | Decompose into 4a / 4b / 4c |
| Q2 | VitalAPI integration | Backend interfaces + real `VitalApi*Backend` impls in 4a; `compileOnly(libs.vital.api)` added to `BuildCore.gradle.kts` |
| Q3 | PersonalityVector lifecycle | Lazy-by-username; `PersonalityProvider.forUsername(name)` loads or generates and persists at `~/.vitalclient/buildcore/personalities/<sha256(username).take12hex>.json`. Pre-login work uses `ephemeral(sessionRng)` personality (not persisted) |
| Q4 | Mouse-path algorithm | Full WindMouse in 4a |
| Q5 | Events added | `InputAction`, `FatigueUpdated`, `PersonalityResolved`, `SessionRngSeeded` (4 total — `SessionRngSeeded` is a 4a spec tightening so Plan 4c has nothing new to add at the BusEvent level) |
| Q6 | RNG implementation | `SecureRandom` seed (once per session) + `java.util.Random` for draws (deterministic from seed). Exposed via a `SeededRng` interface with a single `JavaUtilRng` implementation |
| Q7 | Primitive API shape | `object Mouse` / `object Keyboard` / `object Camera` singletons with `@Volatile internal var backend` (matches foundation spec literal), defaults to real `VitalApi*Backend`, swappable in tests |

---

## 3. Package layout

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/
├── rng/
│   ├── SeededRng.kt                 # CREATE — interface
│   ├── JavaUtilRng.kt               # CREATE — wraps java.util.Random
│   ├── PersonalityRng.kt            # CREATE — seeded from SHA256(username)
│   └── SessionRng.kt                # CREATE — seeded from SecureRandom.nextLong()
│
├── personality/
│   ├── PersonalityVector.kt         # CREATE — 15-field data class + BreakBias enum
│   ├── PersonalityGenerator.kt      # CREATE — pure SeededRng → PersonalityVector
│   ├── PersonalityStore.kt          # CREATE — filesystem persistence (atomic writes)
│   └── PersonalityProvider.kt       # CREATE — lazy load-or-generate facade
│
├── input/
│   ├── Point.kt                     # CREATE — packed Int/Int value class
│   ├── MouseButton.kt               # CREATE — enum
│   ├── Key.kt                       # CREATE — value class over VK code
│   ├── CameraAngle.kt               # CREATE — data class (yaw, pitch)
│   ├── InputMode.kt                 # CREATE — enum (NORMAL + future PRECISION/SURVIVAL)
│   ├── MouseBackend.kt              # CREATE — interface
│   ├── KeyboardBackend.kt           # CREATE — interface
│   ├── CameraBackend.kt             # CREATE — interface
│   ├── FakeMouseBackend.kt          # CREATE — recording test fixture
│   ├── FakeKeyboardBackend.kt       # CREATE — recording test fixture
│   ├── FakeCameraBackend.kt         # CREATE — recording test fixture
│   ├── VitalApiMouseBackend.kt      # CREATE — delegates to vital.api.input.Movement
│   ├── VitalApiKeyboardBackend.kt   # CREATE — delegates to vital.api.input.Keyboard
│   ├── VitalApiCameraBackend.kt     # CREATE — delegates to vital.api.input.Camera
│   ├── Mouse.kt                     # CREATE — object with @Volatile backend field
│   ├── Keyboard.kt                  # CREATE — object with @Volatile backend field
│   └── Camera.kt                    # CREATE — object with @Volatile backend field
│
├── curve/
│   ├── WindMouse.kt                 # CREATE — iterative path generator
│   └── Overshoot.kt                 # CREATE — secondary pass
│
├── timing/
│   ├── ReactionDelay.kt             # CREATE — log-normal × fatigue × throttle
│   └── FatigueCurve.kt              # CREATE — session-age → multipliers
│
└── AntibanBootstrap.kt              # CREATE — install() wires everything into the plugin

BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/
├── BusEvent.kt                      # MODIFY — +4 subtypes, +InputKind enum
└── PrivacyScrubber.kt               # MODIFY — +4 cases (all pass-through)

BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/
├── LogDirLayout.kt                  # MODIFY — add personalityDir()
└── LogLevel.kt                      # MODIFY — add 4 new events to levelOf table (all DEBUG)

BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
└── BuildCorePlugin.kt               # MODIFY — call AntibanBootstrap.install() in startUp()

BuildCore/BuildCore.gradle.kts       # MODIFY — compileOnly(rootProject.libs.vital.api)

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/antiban/rng/                # CREATE — 3 test files
├── core/antiban/personality/        # CREATE — 4 test files
├── core/antiban/input/              # CREATE — (fixtures used by other tests)
├── core/antiban/curve/              # CREATE — 2 test files
├── core/antiban/timing/             # CREATE — 3 test files
├── core/events/
│   └── PrivacyScrubberTest.kt       # MODIFY — bump sample count to 27, add 4 samples
├── arch/
│   └── AntibanArchitectureTest.kt   # CREATE — 8 invariants
└── integration/
    └── AntibanBootstrapIntegrationTest.kt   # CREATE — end-to-end primitive emits InputAction
```

---

## 4. Component architecture

### 4.1 Composition root

`AntibanBootstrap.install` is invoked from `BuildCorePlugin.startUp` after `UncaughtExceptionHandler.install`. It:

1. Creates `PersonalityStore(layout.personalityDir())` — atomic-write filesystem store.
2. Creates `PersonalityProvider(store, bus, sessionIdProvider)` — cached lazy load/generate.
3. Creates `SessionRng.fresh()` — captures the seed, emits `SessionRngSeeded(sessionId, seed)` via `bus.tryEmit`.
4. Creates `FatigueCurve(sessionStart = clock.instant(), clock, bus, sessionIdProvider)`.
5. Constructs the default `GraduatedThrottle(accountAgeDays = 999, totalXp = Long.MAX_VALUE)` — mature-account identity multiplier until Plan 7 supplies real values via `AntibanBootstrap.installThrottle(…)`.
6. Assigns backends and shared state into `Mouse` / `Keyboard` / `Camera`:
   - `backend = VitalApiMouseBackend` (resp. Keyboard, Camera).
   - `personalityProvider`, `sessionRng`, `fatigue`, `throttle`, `bus`, `sessionIdProvider`.

Tests replace the defaults via `@BeforeEach` helpers (fake backends, deterministic RNG, frozen clock).

### 4.2 Dependency diagram

```
BuildCorePlugin.startUp()
    │
    └─► AntibanBootstrap.install(bus, sessionIdProvider, cfg, layout)
            │
            ├─ PersonalityStore(layout.personalityDir())
            ├─ PersonalityProvider(store, bus, sessionIdProvider)
            ├─ SessionRng.fresh() ───► emits SessionRngSeeded(seed)
            ├─ FatigueCurve(sessionStart)
            ├─ GraduatedThrottle(default mature)
            │
            ├─ Mouse.backend    = VitalApiMouseBackend
            ├─ Mouse.{personalityProvider, sessionRng, fatigue, throttle, bus, sessionIdProvider}
            ├─ Keyboard.{…}
            └─ Camera.{…}

Mouse.moveTo(target)
    │
    ├─ ReactionDelay.sample(personality, fatigue, throttle, rng, mode) ──► suspend delay(N)
    ├─ WindMouse.generatePath(from, to, gravity, wind, speedCenter, rng) ──► List<Pair<Point, Int>>
    │     │ foreach (point, stepDelayMs):
    │     └─► backend.appendTrailPoint(point.x, point.y); suspend delay(stepDelayMs)
    ├─ rng.nextBoolean(personality.overshootTendency) → Overshoot.apply(…)
    ├─ backend.click(target.x, target.y, LEFT)   [only moveAndClick]
    └─ bus.tryEmit(InputAction(kind = MOUSE_MOVE, …))
```

### 4.3 Data flow contracts

- **`AntibanBootstrap.install` must be called exactly once per plugin startup.** Calling twice is a no-op guarded by an `AtomicBoolean`; calling zero times means every primitive throws `IllegalStateException("antiban not bootstrapped")` on first invocation.
- **Every primitive is `suspend fun`.** Reaction delays are non-optional; there is no "fast path" that skips them in 4a.
- **One `InputAction` per primitive call**, not per trail point. `Mouse.moveAndClick(target)` emits two events (`MOUSE_MOVE` then `MOUSE_CLICK`). The overshoot pass is bundled into the `MOUSE_MOVE` event's `durationMillis`.
- **`@Volatile` on all bootstrap-set fields.** Set once on the plugin main thread; read later from task/coroutine threads. Safe-publication idiom.

---

## 5. RNG layer

### 5.1 `SeededRng` interface

```kotlin
interface SeededRng {
    fun nextLong(): Long
    fun nextInt(): Int
    fun nextIntInRange(from: Int, until: Int): Int
    fun nextDouble(): Double
    fun nextDoubleInRange(from: Double, until: Double): Double
    fun nextGaussian(): Double
    fun nextLogNormal(mu: Double, sigma: Double): Double
    fun nextBoolean(p: Double): Boolean
    fun <T> shuffled(list: List<T>): List<T>
}
```

All 4a code depends on `SeededRng` only. `java.util.Random` appears exactly once in `JavaUtilRng.kt`, enforced by the architecture test.

### 5.2 `JavaUtilRng` — the sole `SeededRng` implementation

```kotlin
internal class JavaUtilRng(seed: Long) : SeededRng {
    private val rng = java.util.Random(seed)
    override fun nextLong() = rng.nextLong()
    override fun nextInt() = rng.nextInt()
    override fun nextIntInRange(from: Int, until: Int) = from + rng.nextInt(until - from)
    override fun nextDouble() = rng.nextDouble()
    override fun nextDoubleInRange(from: Double, until: Double) =
        from + rng.nextDouble() * (until - from)
    override fun nextGaussian() = rng.nextGaussian()
    override fun nextLogNormal(mu: Double, sigma: Double) =
        kotlin.math.exp(mu + sigma * rng.nextGaussian())
    override fun nextBoolean(p: Double) = rng.nextDouble() < p
    override fun <T> shuffled(list: List<T>) = list.shuffled(rng)
}
```

`internal` visibility confines `JavaUtilRng` to the BuildCore module. Outside callers must go through `PersonalityRng.forUsername(…)` or `SessionRng.fresh()`/`.fromSeed(…)`.

### 5.3 `PersonalityRng` — username-seeded factory

```kotlin
object PersonalityRng {
    fun forUsername(username: String): SeededRng {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(username.lowercase().toByteArray(Charsets.UTF_8))
        var seed = 0L
        for (i in 0 until 8) {
            seed = (seed shl 8) or (digest[i].toLong() and 0xFF)
        }
        return JavaUtilRng(seed)
    }
}
```

`username.lowercase()` normalizes case (Jagex login is case-insensitive). The seed is the first 8 bytes of the SHA-256 digest interpreted as a big-endian Long.

### 5.4 `SessionRng` — SecureRandom seed + Random draws

```kotlin
class SessionRng private constructor(
    val seed: Long,
    private val delegate: SeededRng
) : SeededRng by delegate {
    companion object {
        fun fresh(): SessionRng {
            val seed = SecureRandom().nextLong()
            return SessionRng(seed, JavaUtilRng(seed))
        }
        fun fromSeed(seed: Long): SessionRng = SessionRng(seed, JavaUtilRng(seed))
    }
}
```

- `seed` is public so replay (Plan 4c) can read it once and reproduce the session.
- Kotlin class delegation (`by delegate`) pulls the SeededRng methods in without boilerplate.
- `SecureRandom()` constructed per call — used exactly once per session (to draw the seed), then discarded.
- `fromSeed(seed)` is the test and replay entry point.

### 5.5 Session-seed event

`AntibanBootstrap.install` emits `SessionRngSeeded(sessionId, seed)` immediately after `SessionRng.fresh()`. One event per session, placed early in the bus stream so Plan 4c's recorder sees it before any `InputAction`.

---

## 6. PersonalityVector

### 6.1 Schema

```kotlin
data class PersonalityVector(
    val schemaVersion: Int = 1,
    val mouseSpeedCenter:        Double,  // 0.6–1.8
    val mouseCurveGravity:       Double,  // 8.0–12.0
    val mouseCurveWind:          Double,  // 3.0–7.0
    val overshootTendency:       Double,  // 0.02–0.12
    val reactionLogMean:         Double,  // 5.5–6.5
    val reactionLogStddev:       Double,  // 0.3–0.5
    val hotkeyPreference:        Double,  // 0.4–0.9
    val foodEatDelayCenterMs:    Int,     // 400–900
    val cameraFidgetRatePerMin:  Double,  // 0.8–3.5
    val bankWithdrawalPrecision: Double,  // 0.85–0.99
    val breakBias:               BreakBias,
    val misclickRate:            Double,  // 0.003–0.015
    val menuTopSelectionRate:    Double,  // 0.92–0.995
    val idleExamineRatePerMin:   Double,  // 0.5–2.5
    val tabSwapRatePerMin:       Double   // 0.3–1.8
) {
    init {
        require(schemaVersion == 1) { "unsupported schemaVersion=$schemaVersion" }
        require(mouseSpeedCenter        in 0.6..1.8)
        require(mouseCurveGravity       in 8.0..12.0)
        require(mouseCurveWind          in 3.0..7.0)
        require(overshootTendency       in 0.02..0.12)
        require(reactionLogMean         in 5.5..6.5)
        require(reactionLogStddev       in 0.3..0.5)
        require(hotkeyPreference        in 0.4..0.9)
        require(foodEatDelayCenterMs    in 400..900)
        require(cameraFidgetRatePerMin  in 0.8..3.5)
        require(bankWithdrawalPrecision in 0.85..0.99)
        require(misclickRate            in 0.003..0.015)
        require(menuTopSelectionRate    in 0.92..0.995)
        require(idleExamineRatePerMin   in 0.5..2.5)
        require(tabSwapRatePerMin       in 0.3..1.8)
    }
}

enum class BreakBias { NIGHT_OWL, DAY_REGULAR, BURST }
```

`require()` in `init` rejects out-of-range values on load — a disk-corruption / tampering guard.

### 6.2 Generator — pure function

```kotlin
object PersonalityGenerator {
    fun generate(rng: SeededRng): PersonalityVector = PersonalityVector(
        mouseSpeedCenter        = rng.nextDoubleInRange(0.6, 1.8),
        mouseCurveGravity       = rng.nextDoubleInRange(8.0, 12.0),
        mouseCurveWind          = rng.nextDoubleInRange(3.0, 7.0),
        overshootTendency       = rng.nextDoubleInRange(0.02, 0.12),
        reactionLogMean         = rng.nextDoubleInRange(5.5, 6.5),
        reactionLogStddev       = rng.nextDoubleInRange(0.3, 0.5),
        hotkeyPreference        = rng.nextDoubleInRange(0.4, 0.9),
        foodEatDelayCenterMs    = rng.nextIntInRange(400, 900),
        cameraFidgetRatePerMin  = rng.nextDoubleInRange(0.8, 3.5),
        bankWithdrawalPrecision = rng.nextDoubleInRange(0.85, 0.99),
        breakBias               = BreakBias.entries[rng.nextIntInRange(0, 3)],
        misclickRate            = rng.nextDoubleInRange(0.003, 0.015),
        menuTopSelectionRate    = rng.nextDoubleInRange(0.92, 0.995),
        idleExamineRatePerMin   = rng.nextDoubleInRange(0.5, 2.5),
        tabSwapRatePerMin       = rng.nextDoubleInRange(0.3, 1.8)
    )
}
```

**Field-order invariance:** the declaration order in `PersonalityVector` and the call order in `PersonalityGenerator.generate` are semantically significant. Reordering would silently change every existing persisted personality. Enforced by Architecture Test #6 (§10). Future dimensions go at the END, bump `schemaVersion`.

### 6.3 Persistence

- Root: `~/.vitalclient/buildcore/personalities/` — parallel to `logs/`, not nested inside it. Provided by `LogDirLayout.personalityDir()`.
- Filename: `<sha256(lowercased_username).take12hex>.json`. 12 hex chars = 6 bytes = 2⁴⁸ namespace.
- Format: pretty-printed Jackson JSON — `schemaVersion` first, fields in declaration order.
- Writes: write to `<name>.json.tmp`, then `Files.move(tmp, final, ATOMIC_MOVE, REPLACE_EXISTING)`. Prevents half-written files on crash.
- Read failures (missing, corrupt JSON, schema mismatch, `require()` violation) → log a warning event and regenerate.

### 6.4 `PersonalityProvider`

```kotlin
class PersonalityProvider(
    private val store: PersonalityStore,
    private val bus: EventBus,
    private val sessionIdProvider: () -> UUID
) {
    private val cache = ConcurrentHashMap<String, PersonalityVector>()
    @Volatile private var ephemeral: PersonalityVector? = null

    fun forUsername(username: String): PersonalityVector {
        val key = hashKey(username)
        return cache.computeIfAbsent(key) {
            val loaded = store.load(key)
            if (loaded != null) {
                bus.tryEmit(PersonalityResolved(
                    sessionId = sessionIdProvider(),
                    usernameHash = key, generated = false))
                loaded
            } else {
                val generated = PersonalityGenerator.generate(PersonalityRng.forUsername(username))
                store.save(key, generated)
                bus.tryEmit(PersonalityResolved(
                    sessionId = sessionIdProvider(),
                    usernameHash = key, generated = true))
                generated
            }
        }
    }

    fun ephemeral(sessionRng: SeededRng): PersonalityVector {
        val existing = ephemeral
        if (existing != null) return existing
        val generated = PersonalityGenerator.generate(sessionRng)
        ephemeral = generated
        return generated
    }

    /** Active personality for the current primitive call. Returns the username-resolved
     *  personality if one has been resolved (by Plan 5's Login service or a test),
     *  else falls back to the ephemeral session personality. */
    fun currentPersonality(sessionRng: SeededRng): PersonalityVector =
        cache.values.firstOrNull() ?: ephemeral(sessionRng)

    private fun hashKey(username: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(username.lowercase().toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
```

- `cache.computeIfAbsent` is atomic: same username requested twice → single disk read / generate.
- `currentPersonality(sessionRng)` is the primitive-side read path. Plan 4a has no Login service, so `cache` is empty and `ephemeral` is used. Plan 5 will call `forUsername(loggedInName)` after login, populating the cache; `currentPersonality` then returns it.
- Ephemeral personality is **not** emitted as a `PersonalityResolved` event — untraceable to an account; emitting adds noise without evidential value.
- `currentPersonality` reads `cache.values.firstOrNull()` which is only deterministic when at most one username has been resolved. Plan 5's Login service owns the "one active personality at a time" invariant: on logout, it must call a dedicated `clearActivePersonality()` helper (added in Plan 5) before the next `forUsername` call. Plan 4a ships only the happy-path reader; if a second username is resolved without a clear, whichever entry the `ConcurrentHashMap` returns first wins. Acceptable because Plan 4a has no path that calls `forUsername` at all.

---

## 7. Input primitives

### 7.1 Value types

```kotlin
@JvmInline value class Point(val packed: Long) {
    constructor(x: Int, y: Int) : this((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
    val x: Int get() = (packed ushr 32).toInt()
    val y: Int get() = packed.toInt()
}

enum class MouseButton { LEFT, RIGHT, MIDDLE }
@JvmInline value class Key(val vk: Int)
data class CameraAngle(val yawDegrees: Int, val pitchDegrees: Int)
enum class InputMode { NORMAL, PRECISION, SURVIVAL }
```

`Point` packs x/y into a Long to avoid per-call allocations during WindMouse path generation (hundreds of points per move).

### 7.2 Backend interfaces

```kotlin
interface MouseBackend {
    fun currentPosition(): Point
    fun appendTrailPoint(x: Int, y: Int)
    fun click(x: Int, y: Int, button: MouseButton)
}

interface KeyboardBackend {
    fun keyDown(vk: Int)
    fun keyUp(vk: Int)
    fun tap(vk: Int)
    fun type(text: String)
}

interface CameraBackend {
    fun currentYaw(): Int
    fun currentPitch(): Int
    fun rotate(yawDegrees: Int)
    fun pitch(degrees: Int)
}
```

### 7.3 VitalAPI backends

One file per backend, each a trivial delegate. Method names in `vital.api.input.*` must be verified against the current VitalAPI jar as the first task of the implementation plan — if names differ, the VitalApi* adapter is the one file that needs to change.

```kotlin
internal object VitalApiMouseBackend : MouseBackend { /* delegates to vital.api.input.Movement */ }
internal object VitalApiKeyboardBackend : KeyboardBackend { /* delegates to vital.api.input.Keyboard */ }
internal object VitalApiCameraBackend : CameraBackend { /* delegates to vital.api.input.Camera */ }
```

### 7.4 `Mouse` singleton (illustrative; `Keyboard` / `Camera` follow the same shape)

```kotlin
object Mouse {
    @Volatile internal var backend: MouseBackend = VitalApiMouseBackend
    @Volatile internal var personalityProvider: PersonalityProvider? = null
    @Volatile internal var sessionRng: SessionRng? = null
    @Volatile internal var fatigue: FatigueCurve? = null
    @Volatile internal var throttle: GraduatedThrottle? = null
    @Volatile internal var bus: EventBus? = null
    @Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

    suspend fun moveTo(target: Point, mode: InputMode = InputMode.NORMAL) {
        val provider = personalityProvider ?: error("antiban not bootstrapped")
        val rng = sessionRng ?: error("antiban not bootstrapped")
        val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
        val personality = provider.currentPersonality(rng)

        kotlinx.coroutines.delay(
            ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode)
        )

        val from = backend.currentPosition()
        val path = WindMouse.generatePath(from, target,
            personality.mouseCurveGravity, personality.mouseCurveWind,
            personality.mouseSpeedCenter, rng)
        var totalMs = 0L
        for ((p, stepMs) in path) {
            backend.appendTrailPoint(p.x, p.y)
            if (stepMs > 0) {
                kotlinx.coroutines.delay(stepMs.toLong())
                totalMs += stepMs
            }
        }

        if (rng.nextBoolean(personality.overshootTendency)) {
            Overshoot.apply(backend.currentPosition(), target, backend, personality, rng)
        }

        bus?.tryEmit(InputAction(
            sessionId = sessionIdProvider(),
            kind = InputKind.MOUSE_MOVE,
            targetX = target.x, targetY = target.y,
            durationMillis = totalMs,
            mode = mode
        ))
    }

    suspend fun click(button: MouseButton = MouseButton.LEFT, mode: InputMode = InputMode.NORMAL) { /* … */ }
    suspend fun moveAndClick(target: Point, button: MouseButton = MouseButton.LEFT,
                              mode: InputMode = InputMode.NORMAL) {
        moveTo(target, mode)
        click(button, mode)
    }
}
```

**`InputMode` gating:** `Mouse.moveTo` and all other primitives call `require(mode == InputMode.NORMAL)` via `ReactionDelay.sample`. PRECISION/SURVIVAL values exist in the enum for schema stability but are unreachable until Plan 4b.

---

## 8. WindMouse algorithm

### 8.1 Signature

```kotlin
object WindMouse {
    /** Returns (point, delay-since-previous-ms) pairs from `from` to `to`. */
    fun generatePath(
        from: Point, to: Point,
        gravity: Double,   // personality.mouseCurveGravity (8-12)
        wind: Double,      // personality.mouseCurveWind (3-7)
        speedCenter: Double,   // personality.mouseSpeedCenter (0.6-1.8)
        rng: SeededRng
    ): List<Pair<Point, Int>>
}
```

### 8.2 Algorithm outline

Iterative; at each step:

1. Compute `dx, dy, distance` to target.
2. If `distance < 1.0`, break.
3. Update two wind components: `windX = windX/√3 + (rnd*2-1)*windFactor/√5` (same for Y).
4. Update velocity: `velocityX += windX + gravity * dx/distance`.
5. Clamp velocity magnitude to a `stepCap` sampled per step: `stepCap = speedCenter * (3 + rnd*3)` → 3-6 pixel max step.
6. Step: `currentX += velocityX; currentY += velocityY`.
7. Record `(Point(currentX, currentY), rnd in 3-8ms)`.

After the loop, append `(to, 0)` as the final snap-to-target point.

**Safety cap:** `iterationCap = 2000`. Paths converge well before this for all plausible inputs; the cap exists so a regression in the algorithm can never produce an infinite path.

### 8.3 Overshoot

If `rng.nextBoolean(personality.overshootTendency)` is true, `Overshoot.apply`:
1. Compute overshoot point 3-12 pixels beyond target along the approach vector.
2. Run WindMouse from current → overshoot (appends all trail points directly).
3. Run WindMouse from overshoot → target (appends all trail points directly).

Overshoot does not emit its own event; its duration is included in the `MOUSE_MOVE` event.

---

## 9. Reaction delay, fatigue, and throttle

### 9.1 Formula

```
effectiveReactionDelay = personality.nextLogNormal(reactionLogMean, reactionLogStddev)
                       × fatigue.reactionMultiplier(sessionAge)
                       × throttle.reactionMultiplier(accountAge, totalXp)
```

With spec personality ranges, baseline medians are `[244ms, 665ms]` and 95th percentile up to ~1.5s — consistent with foundation spec §9's "250–700ms normal" band.

### 9.2 `ReactionDelay.sample`

```kotlin
object ReactionDelay {
    fun sample(
        personality: PersonalityVector,
        fatigue: FatigueCurve,
        throttle: GraduatedThrottle?,
        rng: SeededRng,
        mode: InputMode
    ): Long {
        require(mode == InputMode.NORMAL) {
            "Plan 4a supports only NORMAL; PRECISION/SURVIVAL land in Plan 4b"
        }
        val baseMs = rng.nextLogNormal(personality.reactionLogMean, personality.reactionLogStddev)
        val fatigueMult = fatigue.reactionMultiplier()
        val throttleMult = throttle?.reactionMultiplier() ?: 1.0
        val raw = baseMs * fatigueMult * throttleMult
        return raw.coerceIn(1.0, 5000.0).toLong()
    }
}
```

- `coerceIn(1, 5000)` caps pathological Gaussian tails at 5s.
- `require(mode == NORMAL)` is the single invariant that Plan 4b lifts.

### 9.3 `FatigueCurve`

Session-scoped. Four multipliers; each is a linear interp from 1.0 at `t=0` to a spec-pinned midpoint at `t=4h`, clamped past 4h:

| Multiplier | Creep over 4h | Midpoint at t=4h |
|---|---|---|
| `reactionMultiplier` | +5–15% | 1.10 |
| `misclickMultiplier` | +20–50% | 1.35 |
| `overshootVarianceMultiplier` | (qualitative "increases") | 1.25 |
| `fidgetRateMultiplier` | (qualitative "increases") | 1.40 |

`FatigueUpdated` event emitted on calls to any multiplier getter, but debounced to at most once per 60 seconds AND only when `reactionMultiplier` has drifted ≥0.01 since the last emission. Result: roughly 10–20 events per 4-hour session.

### 9.4 `GraduatedThrottle` integration

Plan 4a does not modify Plan 2's `GraduatedThrottle` class. Default install uses `GraduatedThrottle(accountAgeDays = 999, totalXp = Long.MAX_VALUE)` — mature-account identity (multiplier = 1.0), i.e. no behavioral change until Plan 7 supplies real values via `AntibanBootstrap.installThrottle(throttle: GraduatedThrottle)`.

---

## 10. Events and scrubber updates

### 10.1 New `BusEvent` subtypes (4)

`InputAction`, `FatigueUpdated`, `PersonalityResolved`, `SessionRngSeeded`. Full field layouts shown in Section 7 of the design discussion; summary:

- **`InputAction`** — kind (enum), targetX/Y (nullable Int), durationMillis (Long), mode (enum). One per Mouse/Keyboard/Camera primitive call.
- **`FatigueUpdated`** — sessionAgeMillis + four multipliers. Debounced.
- **`PersonalityResolved`** — usernameHash (12-char hex), generated (Boolean). One per `forUsername` call. Ephemeral does not emit.
- **`SessionRngSeeded`** — seed (Long). Exactly one per session, at antiban bootstrap.

`InputKind` enum: `MOUSE_MOVE, MOUSE_CLICK, KEY_TAP, KEY_DOWN, KEY_UP, KEY_TYPE, CAMERA_ROTATE, CAMERA_PITCH`.

### 10.2 `PrivacyScrubber` updates

Four new cases, all pass-through (no free-form fields to scrub):

```kotlin
is InputAction         -> event
is FatigueUpdated      -> event
is PersonalityResolved -> event
is SessionRngSeeded    -> event
```

### 10.3 Arch-test bumps

- Plan 3's architecture test #1 drift detector: `scrubberSampleCount = 27` (was 23).
- `PrivacyScrubberTest.every BusEvent subtype returns without throwing`: add the 4 new subtypes to the `samples: List<BusEvent>` list; update `assertEquals(27, samples.size, ...)`.

### 10.4 `LogLevel.levelOf` additions

All four new events → `LogLevel.DEBUG`. They are too chatty for summary.log at default INFO level. Always land in `events.jsonl` (fast path does not filter).

---

## 11. Architecture tests

New file: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/AntibanArchitectureTest.kt`. Each test cites foundation spec §9.

| # | Invariant | Enforces |
|---|---|---|
| 1 | `vital.api.input.*` imported only in `VitalApiMouseBackend.kt`, `VitalApiKeyboardBackend.kt`, `VitalApiCameraBackend.kt` | No raw-input escape hatches anywhere else in BuildCore |
| 2 | `java.util.Random` imported only in `JavaUtilRng.kt` | Single RNG abstraction point for Plan 4c's replay wrapper |
| 3 | `java.security.SecureRandom` imported only in `SessionRng.kt` | Controls seed-generation surface |
| 4 | `PersonalityVector` properties are all `val` | Immutability |
| 5 | `PersonalityVector` has exactly 16 properties (schemaVersion + 15 dimensions) | Drift detector — adding a 17th dimension fails until update is conscious |
| 6 | `PersonalityVector` property declaration order matches `PersonalityGenerator.generate` call order | Protects per-account personality stability across code changes |
| 7 | `Mouse`, `Keyboard`, `Camera` objects expose no public mutable state (all `var` fields are `internal`) | Backend is swappable by bootstrap + tests, invisible to downstream |
| 8 | All `fun` in `core.antiban.input.Mouse`, `Keyboard`, `Camera` are `suspend` | Reaction delays are non-optional |

Implementation uses Konsist 0.17.3 (same as Plan 3). Per-Konsist-API-version adaptations may be needed (per Plan 3 Task 21's experience).

---

## 12. Testing strategy

Target: ~30 new tests; actual inventory per Section 7 of the design discussion is 39 tests.

Breakdown:

| Area | File | Count |
|---|---|---|
| RNG | `JavaUtilRngTest` | 3 |
| RNG | `PersonalityRngTest` | 2 |
| RNG | `SessionRngTest` | 2 |
| Personality | `PersonalityVectorTest` | 2 |
| Personality | `PersonalityGeneratorTest` | 3 |
| Personality | `PersonalityStoreTest` | 3 |
| Personality | `PersonalityProviderTest` | 3 |
| Curve | `WindMouseTest` | 4 |
| Curve | `OvershootTest` | 1 |
| Timing | `ReactionDelayTest` | 2 |
| Timing | `FatigueCurveTest` | 3 |
| Timing (integration) | `GraduatedThrottleIntegrationTest` | 1 |
| Arch | `AntibanArchitectureTest` | 8 |
| Integration | `AntibanBootstrapIntegrationTest` | 2 |
| **Total** | | **39** |

Plus `PrivacyScrubberTest` is modified to add 4 samples + bump `assertEquals` count.

Plan 3 ended with 94 tests → Plan 4a end: ~133 passing.

Key patterns:

- **Fakes over mocks for backends.** Recording fakes (`FakeMouseBackend.trailPoints`, `FakeMouseBackend.clicks`) assert behavior more naturally than MockK verify blocks.
- **TestDispatcher + `advanceTimeBy` for reaction delays.** `FatigueCurveTest` uses a `MutableClock` test fixture; `ReactionDelayTest` uses deterministic RNG seeds.
- **Fresh `@TempDir` for personality store tests.** Same pattern as Plan 3.
- **`@BeforeEach`/`@AfterEach` to reset Mouse/Keyboard/Camera backends** between integration tests. Plan 4a ships a `AntibanTestFixtures` utility for this.

---

## 13. Configuration surface

No new `LogConfig` fields in Plan 4a. Future Plan 4b additions (precision budget, break schedule biases) will land there.

Environment overrides:

- `BUILDCORE_ANTIBAN_DISABLE_PERSONALITY_PERSIST=1` — debug-only. Forces `PersonalityProvider.forUsername` to behave like `ephemeral` (no disk read/write). Intended for tests and diagnostic runs where disk state should be pristine.

---

## 14. What stays deferred

| Item | Lands in | Reason |
|---|---|---|
| `InputMode.PRECISION` / `SURVIVAL` actually usable | 4b | Requires break-scheduler integration + precision-tick budget + lint |
| `@UsesPrecisionInput` annotation + build-time check | 4b | Belongs with Precision Mode enforcement |
| 4-tier break system (Micro / Normal / Bedtime / Banking) | 4b | Runtime orchestrator; composes over 4a primitives |
| Safe-Stop Contract ↔ Break integration | 4b | Requires break scheduler |
| Real `ReplayRecorder` (replaces `NoOpReplaySubscriber`) | 4c | Needs Plan 5's service call boundaries |
| `ReplayRng` decorator over `SeededRng` | 4c | Partner to ReplayRecorder |
| Login hook binding real username | Plan 5 | Requires the `Login` service |
| `FatigueCurve.misclickMultiplier` actually injecting misclicks | 4b | Service-layer concern |
| Real `GraduatedThrottle` values (from profile) | Plan 7 | Requires Profile system |
| Personality editing / regeneration UI | Plan 10 | GUI Shell |
| Antiban hot-rules (server-published tweaks) | Plan 8 | BuildCore-Server |

---

## 15. Open questions

None blocking. Four defaults flagged during brainstorming, all accepted:

1. **`PersonalityProvider` cache is append-only.** No eviction. Per-account personalities are ~150 bytes; a fleet of 300 accounts is ~45KB. Revisit only if a plugin hot-reload story forces it.
2. **`SecureRandom` uses JVM default algorithm.** OS-specific CSPRNG. Adequate for threat model.
3. **`nextBoolean(p)` uses `nextDouble() < p`.** Replay determinism requires the sequence of `SeededRng` method calls to match, not internal bit usage.
4. **`SessionRngSeeded` is a fourth new event subtype in 4a** (spec §5.2 originally listed 9 Plan 3 events + 1 overflow = 10; 4a mirrors that by adding the seed-capture event now so Plan 4c has zero BusEvent work). Scrubber sample count: 23 → 27.
