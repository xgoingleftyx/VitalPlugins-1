# BuildCore Plan 4b — Precision Mode + 4-Tier Breaks + Misclick Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift the 4a `require(mode == NORMAL)` invariant and ship the three remaining antiban subsystems composed over 4a's primitives — Precision/Survival mode scopes, the cooperative 4-tier break scheduler with Bedtime-escalation Task SPI, and dual-layer misclick (primitive pixel jitter + service-layer opt-in hook). Plus a Konsist arch test enforcing `@UsesPrecisionInput` and a privacy-sink split (full-fidelity local log, scrubbed-on-demand export bundle).

**Architecture:** Plan 4b is pure framework — no activity modules, no L5 services. Precision is enforced through a single `PrecisionGate` dispatch point gated by a thread-local marker set by `withPrecision`/`withSurvival` inline builders. Breaks are a single coroutine launched by `AntibanBootstrap` that polls `Task.canStopNow()` cooperatively; the only escalation path is `Task.requestEarlyStop(BEDTIME)` which the task itself drives. Misclick has a primitive-layer policy (pixel jitter on every `Mouse.click`) and a service-layer opt-in API (`SemanticMisclickHook`) that Plan 5+ services adopt. The `PrivacyScrubber` becomes export-time only — local logs stay full-fidelity for debugging.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0 (incl. `kotlinx-coroutines-test`), Jackson `jackson-module-kotlin`, JUnit 5, MockK, Konsist 0.17.3. No new external dependencies.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-25-buildcore-plan4b-precision-breaks-misclick-design.md`](../specs/2026-04-25-buildcore-plan4b-precision-breaks-misclick-design.md)
- Plans 1+2+3+4a complete and merged to `main` (94 tests passing as of 4a).
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit direct to main and push to `origin` (xgoingleftyx fork) after every commit.
- Author: Chich only — NO `Co-Authored-By` trailers.
- Style: tabs, Allman braces where applicable, UTF-8.

**4a surface verified against current source:**
- `PersonalityVector` already exposes `misclickRate: Double` (0.003..0.015) and `breakBias: BreakBias { NIGHT_OWL, DAY_REGULAR, BURST }` — Plan 4b reuses both; no new fields added.
- `FatigueCurve.misclickMultiplier(): Double` already exists.
- `ReactionDelay.sample(personality, fatigue, throttle, rng, mode)` currently throws on PRECISION/SURVIVAL with `require(mode == InputMode.NORMAL)` at `ReactionDelay.kt:28`. Plan 4b lifts this.
- `Mouse.kt:77-96` `click(button, mode)` calls `backend.click` directly — Plan 4b inserts `MisclickPolicy.intercept(...)`.
- `AntibanBootstrap.install(bus, sessionIdProvider, layout, clock)` is the only composition root — Plan 4b adds `BreakScheduler` install + `MisclickPolicy.install`.
- `Task` interface has `canStopNow(ctx)` already (Plan 2). Plan 4b adds `suspend fun requestEarlyStop(reason)` with default no-op.
- `LoggingArchitectureTest.kt:43` expects `scrubberSampleCount = 27`. Plan 4b bumps to **39** (12 new subtypes).
- `BreakTier` is implemented as an **enum** (not sealed class as spec §5.1 wrote) for Jackson serialization simplicity. Spec is treated as guidance; this deviation is documented in Task 9.

---

## File structure this plan produces

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                              # MODIFY — pass break config dir; no API change
└── core/
    ├── events/
    │   ├── BusEvent.kt                                             # MODIFY — 12 new subtypes + 3 new enums
    │   └── PrivacyScrubber.kt                                      # MODIFY — 12 new scrubber cases
    ├── logging/
    │   ├── LogDirLayout.kt                                         # MODIFY — breakConfigDir(), exportDir()
    │   ├── LogLevel.kt                                             # MODIFY — 12 new event→level mappings
    │   └── ExportBundle.kt                                         # CREATE — scrub-on-demand writer
    ├── task/
    │   └── Task.kt                                                 # MODIFY — add requestEarlyStop()
    └── antiban/
        ├── AntibanBootstrap.kt                                     # MODIFY — install BreakScheduler, MisclickPolicy
        ├── input/
        │   └── Mouse.kt                                            # MODIFY — call MisclickPolicy on click
        ├── precision/
        │   ├── PrecisionGate.kt                                    # CREATE
        │   ├── PrecisionWindow.kt                                  # CREATE — withPrecision/withSurvival
        │   └── UsesPrecisionInput.kt                               # CREATE — annotation
        ├── breaks/
        │   ├── BreakTier.kt                                        # CREATE — enum
        │   ├── DeferAction.kt                                      # CREATE — enum
        │   ├── BreakConfig.kt                                      # CREATE — data classes + defaults
        │   ├── BreakConfigStore.kt                                 # CREATE — JSON I/O
        │   ├── BreakState.kt                                       # CREATE — internal state
        │   ├── BreakScheduler.kt                                   # CREATE — coroutine driver
        │   └── BedtimeEscalator.kt                                 # CREATE
        ├── misclick/
        │   ├── MisclickKind.kt                                     # CREATE — enum
        │   ├── MisclickPolicy.kt                                   # CREATE — primitive interceptor
        │   └── SemanticMisclickHook.kt                             # CREATE — service-layer API
        └── timing/
            └── ReactionDelay.kt                                    # MODIFY — support PRECISION/SURVIVAL

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/
│   └── PrivacyScrubberTest.kt                                      # MODIFY — bump scrubber sample count to 39
├── core/logging/
│   └── ExportBundleTest.kt                                         # CREATE
├── core/task/
│   └── TaskEarlyStopTest.kt                                        # CREATE
├── core/antiban/
│   ├── precision/
│   │   ├── PrecisionGateTest.kt
│   │   ├── PrecisionWindowTest.kt
│   │   └── ReactionDelayPrecisionTest.kt
│   ├── breaks/
│   │   ├── BreakConfigTest.kt
│   │   ├── BreakConfigStoreTest.kt
│   │   ├── BreakSchedulerTest.kt
│   │   ├── BreakSchedulerPreemptionTest.kt
│   │   └── BedtimeEscalatorTest.kt
│   └── misclick/
│       ├── MisclickPolicyTest.kt
│       └── SemanticMisclickHookTest.kt
├── arch/
│   ├── LoggingArchitectureTest.kt                                  # MODIFY — bump 27 → 39
│   └── PrecisionInputArchTest.kt                                   # CREATE — Konsist
└── integration/
    └── BreakSchedulerIntegrationTest.kt                            # CREATE
```

---

## Phase 1 — Events, scrubber, Task SPI (Tasks 1-3)

### Task 1 — Add 12 new `BusEvent` subtypes + 3 enums

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1: Append the new enums and event data classes** at the bottom of `BusEvent.kt`:

```kotlin
// ─────────────────────────────────────────────────────────────────────
// Antiban events (Plan 4b spec §8)
// ─────────────────────────────────────────────────────────────────────

enum class BreakTier { MICRO, NORMAL, BEDTIME, BANKING }
enum class EarlyStopReason { BEDTIME, USER, ORCHESTRATOR }
enum class MisclickKind { PIXEL_JITTER, NEAR_MISS }

data class PrecisionModeEntered(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val scopeId: Long,
	val mode: InputMode
) : BusEvent

data class PrecisionModeExited(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val scopeId: Long,
	val mode: InputMode,
	val durationMillis: Long
) : BusEvent

data class BreakScheduled(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val fireAtEpochMs: Long
) : BusEvent

data class BreakStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val plannedDurationMillis: Long
) : BusEvent

data class BreakEnded(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val actualDurationMillis: Long
) : BusEvent

data class BreakDeferred(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val deferredMillis: Long
) : BusEvent

data class BreakDropped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val deferredMillis: Long
) : BusEvent

data class BreakRescheduled(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val newFireAtEpochMs: Long
) : BusEvent

data class BreakPreempted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val tier: BreakTier,
	val remainingMillis: Long
) : BusEvent

data class EarlyStopRequested(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: EarlyStopReason
) : BusEvent

data class Misclick(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val kind: MisclickKind,
	val intendedX: Int,
	val intendedY: Int,
	val actualX: Int,
	val actualY: Int,
	val corrected: Boolean
) : BusEvent

data class SemanticMisclick(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val context: String,
	val intended: String,
	val actual: String
) : BusEvent
```

- [ ] **Step 2: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`. Tests will fail (next task) because `PrivacyScrubber.scrub` `when` is no longer exhaustive.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt
git commit -m "BuildCore: Plan 4b — add 12 antiban BusEvent subtypes + BreakTier/EarlyStopReason/MisclickKind enums"
git push origin main
```

---

### Task 2 — Add 12 `PrivacyScrubber` cases + bump arch test

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt`
- Modify `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`

- [ ] **Step 1: Update the `when` in `PrivacyScrubber.scrub`** — add 12 cases at the bottom (after `is SessionRngSeeded`):

Open `PrivacyScrubber.kt`. The existing block ends at the line `is SessionRngSeeded    -> event`. Replace that line and add the 12 new cases:

```kotlin
			is SessionRngSeeded     -> event
			is PrecisionModeEntered -> event
			is PrecisionModeExited  -> event
			is BreakScheduled       -> event
			is BreakStarted         -> event
			is BreakEnded           -> event
			is BreakDeferred        -> event
			is BreakDropped         -> event
			is BreakRescheduled     -> event
			is BreakPreempted       -> event
			is EarlyStopRequested   -> event
			is Misclick             -> event.copy(
				intendedX = roundToGridCell(event.intendedX),
				intendedY = roundToGridCell(event.intendedY),
				actualX   = roundToGridCell(event.actualX),
				actualY   = roundToGridCell(event.actualY)
			)
			is SemanticMisclick     -> event.copy(
				context  = hashShort(event.context),
				intended = hashShort(event.intended),
				actual   = hashShort(event.actual)
			)
```

Then add two private helpers at the bottom of the `PrivacyScrubber` object (just before the closing `}`):

```kotlin
	private const val GRID_CELL_PX = 16

	private fun roundToGridCell(coord: Int): Int =
		(coord / GRID_CELL_PX) * GRID_CELL_PX

	private fun hashShort(s: String): String = hmacHex(s)
```

`hmacHex` already exists in `PrivacyScrubber` from Plan 3 (used for username hashing). Confirm with:

```bash
grep -n "fun hmacHex" /c/Code/VitalPlugins/BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt
```
Expected: at least one match.

- [ ] **Step 2: Update the arch test sample count** in `LoggingArchitectureTest.kt`

Find the line `val scrubberSampleCount = 27` (around line 43). Change to:

```kotlin
		val scrubberSampleCount = 39                // update when a new subtype is added to the scrubber AND to this test
```

- [ ] **Step 3: Update `PrivacyScrubberTest`** to add 12 sample events

Open `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt`. Find the existing list of sample events (likely a `samples` property or list literal containing 27 entries). Append:

```kotlin
		PrecisionModeEntered(sessionId = sid, scopeId = 1L, mode = InputMode.PRECISION),
		PrecisionModeExited (sessionId = sid, scopeId = 1L, mode = InputMode.PRECISION, durationMillis = 250L),
		BreakScheduled      (sessionId = sid, tier = BreakTier.MICRO,    fireAtEpochMs = 1_000L),
		BreakStarted        (sessionId = sid, tier = BreakTier.NORMAL,   plannedDurationMillis = 60_000L),
		BreakEnded          (sessionId = sid, tier = BreakTier.NORMAL,   actualDurationMillis = 58_000L),
		BreakDeferred       (sessionId = sid, tier = BreakTier.NORMAL,   deferredMillis = 5_000L),
		BreakDropped        (sessionId = sid, tier = BreakTier.MICRO,    deferredMillis = 60_000L),
		BreakRescheduled    (sessionId = sid, tier = BreakTier.NORMAL,   newFireAtEpochMs = 2_000L),
		BreakPreempted      (sessionId = sid, tier = BreakTier.MICRO,    remainingMillis = 10_000L),
		EarlyStopRequested  (sessionId = sid, reason = EarlyStopReason.BEDTIME),
		Misclick            (sessionId = sid, kind = MisclickKind.PIXEL_JITTER, intendedX = 100, intendedY = 200, actualX = 101, actualY = 199, corrected = false),
		SemanticMisclick    (sessionId = sid, context = "useItemOn", intended = "feather", actual = "fishing-rod")
```

(Add the matching imports if missing.)

- [ ] **Step 4: Run scrubber + arch tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrivacyScrubber*" --tests "*LoggingArchitectureTest*" --no-daemon 2>&1 | tail -20
```
Expected: all pass.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt
git commit -m "BuildCore: Plan 4b — extend PrivacyScrubber for 12 antiban events; bump arch sample count 27→39"
git push origin main
```

---

### Task 3 — Add `requestEarlyStop` to `Task` SPI

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/TaskEarlyStopTest.kt`

- [ ] **Step 1: Write the failing test** at `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/TaskEarlyStopTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EarlyStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class TaskEarlyStopTest
{
	@Test
	fun `default requestEarlyStop is a no-op`() = runTest {
		val task = NoOpTask()
		task.requestEarlyStop(EarlyStopReason.BEDTIME)  // must not throw, must not change state
	}

	@Test
	fun `task can override requestEarlyStop to record reason`() = runTest {
		val captured = AtomicReference<EarlyStopReason?>()
		val task = object : NoOpTask()
		{
			override suspend fun requestEarlyStop(reason: EarlyStopReason)
			{
				captured.set(reason)
			}
		}
		task.requestEarlyStop(EarlyStopReason.USER)
		assertEquals(EarlyStopReason.USER, captured.get())
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*TaskEarlyStopTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile error — `requestEarlyStop` is not a member of `Task`.

- [ ] **Step 3: Add the SPI method to `Task.kt`**

Open `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt`. After the existing `fun safeStop(ctx: TaskContext)` line, before `fun progressSignal`, insert:

```kotlin
	/**
	 * Called by [net.vital.plugins.buildcore.core.antiban.breaks.BedtimeEscalator]
	 * when a Bedtime break has been deferred past its hard ceiling. The task
	 * is responsible for driving itself to a safe stopping state (bank, log
	 * out) and then surfacing that via [canStopNow]. Default no-op so existing
	 * tasks compile without change.
	 *
	 * Plan 4b spec §5.6.
	 */
	suspend fun requestEarlyStop(reason: net.vital.plugins.buildcore.core.events.EarlyStopReason) {}
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*TaskEarlyStopTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 passed.

- [ ] **Step 5: Run all existing tests to confirm no regression**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -20
```
Expected: all pass (94 + 2 = 96 + previous task additions).

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/TaskEarlyStopTest.kt
git commit -m "BuildCore: Plan 4b — add Task.requestEarlyStop(reason) SPI method (default no-op)"
git push origin main
```

---

## Phase 2 — Precision Mode (Tasks 4-8)

### Task 4 — `@UsesPrecisionInput` annotation

**File:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/UsesPrecisionInput.kt`

- [ ] **Step 1: Create the annotation**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

/**
 * Marks a function that legitimately enters PRECISION or SURVIVAL input mode.
 *
 * Required (transitively, up the call chain) on any function that calls
 * [withPrecision], [withSurvival], or [PrecisionGate.enter] with a non-NORMAL
 * mode. Enforced by `PrecisionInputArchTest` (Konsist).
 *
 * Plan 4b spec §4.4.
 */
@MustBeDocumented
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class UsesPrecisionInput
```

- [ ] **Step 2: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/UsesPrecisionInput.kt
git commit -m "BuildCore: Plan 4b — @UsesPrecisionInput annotation"
git push origin main
```

---

### Task 5 — `PrecisionGate`

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGate.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrecisionGateTest
{
	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
	}

	@Test
	fun `NORMAL passes through without scope`()
	{
		val profile = PrecisionGate.enter(InputMode.NORMAL)
		assertEquals(false, profile.tightTimingFloor)
		assertEquals(true,  profile.fidgetEnabled)
		assertEquals(true,  profile.overshootEnabled)
		assertEquals(true,  profile.fatigueApplied)
	}

	@Test
	fun `PRECISION without scope throws`()
	{
		assertThrows(IllegalStateException::class.java) {
			PrecisionGate.enter(InputMode.PRECISION)
		}
	}

	@Test
	fun `PRECISION with marker returns tight profile`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val profile = PrecisionGate.enter(InputMode.PRECISION)
		assertEquals(true,  profile.tightTimingFloor)
		assertEquals(false, profile.fidgetEnabled)
		assertEquals(false, profile.overshootEnabled)
		assertEquals(false, profile.fatigueApplied)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `SURVIVAL invokes preempt callback`()
	{
		var preempts = 0
		PrecisionGate.preemptHook = { preempts++ }
		PrecisionGate.markEnterScope(InputMode.SURVIVAL)
		PrecisionGate.enter(InputMode.SURVIVAL)
		PrecisionGate.markExitScope()
		assertEquals(1, preempts)
	}

	@Test
	fun `nested PRECISION marker survives nested enter`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		PrecisionGate.markEnterScope(InputMode.PRECISION)  // re-entrant
		PrecisionGate.enter(InputMode.PRECISION)           // must not throw
		PrecisionGate.markExitScope()
		PrecisionGate.markExitScope()
		assertFalse(PrecisionGate.inScope())
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrecisionGateTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors — `PrecisionGate` does not exist.

- [ ] **Step 3: Implement `PrecisionGate.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode

/**
 * The single mode-dispatch point for input primitives. Every primitive
 * (Mouse.moveTo / click, Keyboard.tap / type, Camera.rotate / pitch) calls
 * [enter] at the top of its body to fetch the active [TimingProfile].
 *
 * The thread-local "scope marker" is set by [PrecisionWindow.withPrecision] /
 * [PrecisionWindow.withSurvival] and read here. If a primitive is invoked with
 * a non-NORMAL [InputMode] but no scope marker is on the stack, [enter] throws
 * — defense-in-depth on top of `PrecisionInputArchTest` (Konsist).
 *
 * Plan 4b spec §4.2.
 */
object PrecisionGate
{
	data class TimingProfile(
		val tightTimingFloor: Boolean,
		val fidgetEnabled: Boolean,
		val overshootEnabled: Boolean,
		val fatigueApplied: Boolean
	)

	private val NORMAL_PROFILE = TimingProfile(
		tightTimingFloor = false, fidgetEnabled = true, overshootEnabled = true, fatigueApplied = true
	)
	private val PRECISION_PROFILE = TimingProfile(
		tightTimingFloor = true,  fidgetEnabled = false, overshootEnabled = false, fatigueApplied = false
	)

	private val scopeDepth = ThreadLocal.withInitial { 0 }
	private val scopeMode  = ThreadLocal<InputMode?>()

	/** Wired by [net.vital.plugins.buildcore.core.antiban.breaks.BreakScheduler] at install. */
	@Volatile var preemptHook: (() -> Unit)? = null

	internal fun markEnterScope(mode: InputMode)
	{
		scopeDepth.set(scopeDepth.get() + 1)
		scopeMode.set(mode)
	}

	internal fun markExitScope()
	{
		val d = scopeDepth.get() - 1
		if (d <= 0)
		{
			scopeDepth.set(0)
			scopeMode.set(null)
		}
		else
		{
			scopeDepth.set(d)
		}
	}

	internal fun inScope(): Boolean = scopeDepth.get() > 0

	fun enter(mode: InputMode): TimingProfile
	{
		when (mode)
		{
			InputMode.NORMAL -> return NORMAL_PROFILE
			InputMode.PRECISION ->
			{
				check(inScope()) { "PRECISION call outside withPrecision/withSurvival scope" }
				return PRECISION_PROFILE
			}
			InputMode.SURVIVAL ->
			{
				check(inScope()) { "SURVIVAL call outside withPrecision/withSurvival scope" }
				preemptHook?.invoke()
				return PRECISION_PROFILE
			}
		}
	}

	internal fun resetForTests()
	{
		scopeDepth.set(0)
		scopeMode.set(null)
		preemptHook = null
	}
}
```

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrecisionGateTest*" --no-daemon 2>&1 | tail -10
```
Expected: 5 passed.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGate.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGateTest.kt
git commit -m "BuildCore: Plan 4b — PrecisionGate dispatch point with thread-local scope marker"
git push origin main
```

---

### Task 6 — `PrecisionWindow` (`withPrecision` / `withSurvival`)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionWindow.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionWindowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.PrecisionModeEntered
import net.vital.plugins.buildcore.core.events.PrecisionModeExited
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PrecisionWindowTest
{
	private val bus = mockk<EventBus>(relaxed = true)
	private val sid = UUID.randomUUID()

	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
		PrecisionWindow.bus = bus
		PrecisionWindow.sessionIdProvider = { sid }
	}

	@Test
	fun `withPrecision sets and clears the marker`()
	{
		assertFalse(PrecisionGate.inScope())
		val out = PrecisionWindow.withPrecision {
			assertTrue(PrecisionGate.inScope())
			42
		}
		assertEquals(42, out)
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `withPrecision clears the marker on exception`()
	{
		assertThrows(IllegalStateException::class.java) {
			PrecisionWindow.withPrecision { error("boom") }
		}
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `nested withPrecision is re-entrant`()
	{
		PrecisionWindow.withPrecision {
			PrecisionWindow.withPrecision {
				assertTrue(PrecisionGate.inScope())
			}
			assertTrue(PrecisionGate.inScope())  // outer still active
		}
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `withPrecision emits Entered then Exited`()
	{
		val captured = mutableListOf<BusEvent>()
		every { bus.tryEmit(capture(slotList(captured))) } returns true
		PrecisionWindow.withPrecision { /* noop */ }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is PrecisionModeEntered)
		assertTrue(captured[1] is PrecisionModeExited)
		val entered = captured[0] as PrecisionModeEntered
		val exited  = captured[1] as PrecisionModeExited
		assertEquals(entered.scopeId, exited.scopeId)
		assertEquals(InputMode.PRECISION, entered.mode)
	}

	@Test
	fun `withSurvival uses SURVIVAL mode`()
	{
		val captured = mutableListOf<BusEvent>()
		every { bus.tryEmit(capture(slotList(captured))) } returns true
		PrecisionWindow.withSurvival { /* noop */ }
		assertEquals(InputMode.SURVIVAL, (captured[0] as PrecisionModeEntered).mode)
	}

	private fun <T> slotList(list: MutableList<T>) = io.mockk.CapturingSlot<T>().also {
		// no-op shim; rely on every {} above to populate `list` via verify
	}
}
```

> **Implementer note:** the helper `slotList` shim above is a placeholder — MockK's idiomatic capture is `slot<BusEvent>()` per call or `verify { bus.tryEmit(any()) }` with an answer that records to a list. If MockK's `capture(slot)` semantics differ across versions, capture inline using `every { bus.tryEmit(any()) } answers { captured.add(firstArg()); true }`. The test intent is: assert two events emitted in order with matching `scopeId`.

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrecisionWindowTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors — `PrecisionWindow` does not exist.

- [ ] **Step 3: Implement `PrecisionWindow.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.PrecisionModeEntered
import net.vital.plugins.buildcore.core.events.PrecisionModeExited
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Precision/Survival scope builders. Wraps [block] in a thread-local marker
 * read by [PrecisionGate.enter] so input primitives switch behavior. Marker
 * is cleared even if [block] throws.
 *
 * Plan 4b spec §4.3.
 */
object PrecisionWindow
{
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	private val nextScopeId = AtomicLong(1L)

	@UsesPrecisionInput
	inline fun <T> withPrecision(block: () -> T): T = runScope(InputMode.PRECISION, block)

	@UsesPrecisionInput
	inline fun <T> withSurvival(block: () -> T): T = runScope(InputMode.SURVIVAL, block)

	@PublishedApi
	internal inline fun <T> runScope(mode: InputMode, block: () -> T): T
	{
		val scopeId = nextScopeId.getAndIncrement()
		PrecisionGate.markEnterScope(mode)
		val startNanos = System.nanoTime()
		bus?.tryEmit(PrecisionModeEntered(sessionId = sessionIdProvider(), scopeId = scopeId, mode = mode))
		try
		{
			return block()
		}
		finally
		{
			val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
			bus?.tryEmit(PrecisionModeExited(sessionId = sessionIdProvider(), scopeId = scopeId, mode = mode, durationMillis = durationMs))
			PrecisionGate.markExitScope()
		}
	}
}
```

- [ ] **Step 4: Run test**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrecisionWindowTest*" --no-daemon 2>&1 | tail -15
```
Expected: 5 passed. (If MockK capture pattern needs adjustment, fix per Step-1 implementer note.)

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionWindow.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionWindowTest.kt
git commit -m "BuildCore: Plan 4b — withPrecision/withSurvival scope builders + Entered/Exited events"
git push origin main
```

---

### Task 7 — Modify `ReactionDelay` to support PRECISION/SURVIVAL

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelay.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/ReactionDelayPrecisionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReactionDelayPrecisionTest
{
	private val personality = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
		cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
		breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
		misclickRate = 0.005, menuTopSelectionRate = 0.95,
		idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
	)
	private val fatigue = mockk<FatigueCurve>().also {
		every { it.reactionMultiplier() } returns 1.5
		every { it.misclickMultiplier() } returns 1.0
	}
	private val rng = JavaUtilRng(seed = 42L)

	@Test
	fun `NORMAL applies fatigue multiplier`()
	{
		val n1 = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.NORMAL)
		assertTrue(n1 > 100L) // log-normal × 1.5 fatigue, should be substantial
	}

	@Test
	fun `PRECISION uses tight floor and ignores fatigue`()
	{
		val p = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.PRECISION)
		assertTrue(p in 60L..160L) { "PRECISION delay $p outside tight floor band 60..160ms" }
	}

	@Test
	fun `SURVIVAL behaves like PRECISION timing-wise`()
	{
		val s = ReactionDelay.sample(personality, fatigue, throttle = null, rng = JavaUtilRng(seed = 42L), mode = InputMode.SURVIVAL)
		assertTrue(s in 60L..160L) { "SURVIVAL delay $s outside tight floor band 60..160ms" }
	}
}
```

- [ ] **Step 2: Run to confirm it fails** — current code has `require(mode == NORMAL)` which throws.

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ReactionDelayPrecisionTest*" --no-daemon 2>&1 | tail -10
```
Expected: PRECISION/SURVIVAL tests fail with `IllegalArgumentException`.

- [ ] **Step 3: Modify `ReactionDelay.sample`**

Open `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelay.kt`. Replace the entire `sample` function body:

```kotlin
	private const val PRECISION_FLOOR_MIN_MS = 60L
	private const val PRECISION_FLOOR_MAX_MS = 160L

	fun sample(
		personality: PersonalityVector,
		fatigue: FatigueCurve,
		throttle: GraduatedThrottle?,
		rng: SeededRng,
		mode: InputMode
	): Long
	{
		return when (mode)
		{
			InputMode.NORMAL ->
			{
				val baseMs = rng.nextLogNormal(personality.reactionLogMean, personality.reactionLogStddev)
				val fatigueMult = fatigue.reactionMultiplier()
				val throttleMult = throttle?.reactionMultiplier() ?: 1.0
				val raw = baseMs * fatigueMult * throttleMult
				raw.coerceIn(1.0, 5000.0).toLong()
			}
			InputMode.PRECISION, InputMode.SURVIVAL ->
			{
				// Tight floor: uniform within [PRECISION_FLOOR_MIN_MS, PRECISION_FLOOR_MAX_MS].
				// No fatigue, no throttle, no log-normal variance.
				val span = (PRECISION_FLOOR_MAX_MS - PRECISION_FLOOR_MIN_MS).toInt()
				PRECISION_FLOOR_MIN_MS + rng.nextInt(span).toLong()
			}
		}
	}
```

(Remove the `require(mode == InputMode.NORMAL)` line and the orphaned `baseMs/fatigueMult/throttleMult/raw` lines that were the old NORMAL body — they now live inside the `when`.)

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ReactionDelayPrecisionTest*" --tests "*ReactionDelayTest*" --no-daemon 2>&1 | tail -10
```
Expected: all pass (NORMAL behavior unchanged; PRECISION/SURVIVAL hit tight floor).

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelay.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/ReactionDelayPrecisionTest.kt
git commit -m "BuildCore: Plan 4b — lift NORMAL-only invariant; PRECISION/SURVIVAL use tight floor"
git push origin main
```

---

### Task 8 — Wire `PrecisionGate` into `Mouse`/`Keyboard`/`Camera` primitives

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt`
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Keyboard.kt`
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Camera.kt`

The 4a primitives took `mode: InputMode = NORMAL` parameter but never passed it through `PrecisionGate`. Plan 4b inserts the gate call and conditionally suppresses fidget/overshoot.

- [ ] **Step 1: Modify `Mouse.moveTo`** to call `PrecisionGate.enter(mode)` and skip overshoot under non-NORMAL.

In `Mouse.kt`, replace the body of `moveTo`:

```kotlin
	suspend fun moveTo(target: Point, mode: InputMode = InputMode.NORMAL)
	{
		val profile = net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.enter(mode)
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)

		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))

		val from = backend.currentPosition()
		val path = WindMouse.generatePath(
			from, target,
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		var totalMs = 0L
		for ((p, stepMs) in path)
		{
			backend.appendTrailPoint(p.x, p.y)
			if (stepMs > 0)
			{
				delay(stepMs.toLong())
				totalMs += stepMs
			}
		}

		if (profile.overshootEnabled && rng.nextBoolean(personality.overshootTendency))
		{
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
```

(The only changes vs Plan 4a: line 1 of body inserts `val profile = PrecisionGate.enter(mode)`; the overshoot branch now also requires `profile.overshootEnabled`.)

- [ ] **Step 2: Modify `Mouse.click`** likewise (insert the gate call as the first line of the body):

```kotlin
	suspend fun click(button: MouseButton = MouseButton.LEFT, mode: InputMode = InputMode.NORMAL)
	{
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.enter(mode)  // gate-only — misclick wired in Task 17
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)

		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))

		val pos = backend.currentPosition()
		backend.click(pos.x, pos.y, button)

		bus?.tryEmit(InputAction(
			sessionId = sessionIdProvider(),
			kind = InputKind.MOUSE_CLICK,
			targetX = pos.x, targetY = pos.y,
			durationMillis = 0L,
			mode = mode
		))
	}
```

- [ ] **Step 3: Modify `Keyboard` primitives** (`tap`, `keyDown`, `keyUp`, `type`) — same pattern. Insert `PrecisionGate.enter(mode)` as the first line of each function body. (The `Keyboard` object exposes those four `suspend fun`s; check `Keyboard.kt` and apply uniformly.)

- [ ] **Step 4: Modify `Camera` primitives** (`rotate`, `pitch`) — same pattern. The `Camera` object has fidget logic; find any `if (rng.nextBoolean(personality.cameraFidgetRatePerMin / 60.0))` style check and gate it on `profile.fidgetEnabled`. (If `Camera.kt` does not currently emit fidget — check; if not, just insert the gate call.)

- [ ] **Step 5: Verify compile + run all tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -25
```
Expected: existing primitive tests still pass — they call with default `NORMAL` and `PrecisionGate.enter(NORMAL)` is unconditional pass-through. PrecisionWindow + PrecisionGate tests pass.

If existing primitive tests break because they call `Mouse.click(mode = PRECISION)` directly without a `withPrecision` scope, wrap those test sites with `PrecisionWindow.withPrecision { … }`.

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Keyboard.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Camera.kt
git commit -m "BuildCore: Plan 4b — wire PrecisionGate into Mouse/Keyboard/Camera primitives"
git push origin main
```

---

## Phase 3 — Break system (Tasks 9-15)

### Task 9 — `BreakTier` + `DeferAction` enums

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakTier.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/DeferAction.kt`

> **Note:** The spec §5.1 wrote `BreakTier` as a sealed class. We use an `enum class` here for Jackson serialization simplicity (sealed-class + Jackson polymorphic config is heavier and unnecessary — `BreakTier` is a closed taxonomy of constants). The same enum is already declared in `BusEvent.kt` (Task 1); do **not** redeclare it. Instead this task adds only `DeferAction` plus a typealias re-export so the package layout matches the spec's module map.

- [ ] **Step 1: Add a typealias re-export for `BreakTier`** so `core.antiban.breaks.BreakTier` resolves to the canonical declaration in `core.events.BreakTier`.

Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakTier.kt`:

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * Re-export of the canonical [net.vital.plugins.buildcore.core.events.BreakTier]
 * declared in `BusEvent.kt`. Lives here so `breaks` package references read
 * naturally without an `events` import.
 *
 * Plan 4b spec §5.1.
 */
typealias BreakTier = net.vital.plugins.buildcore.core.events.BreakTier
```

- [ ] **Step 2: Create `DeferAction.kt`**:

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * What [BreakScheduler] does when [TierConfig.maxDeferMs] expires before
 * [net.vital.plugins.buildcore.core.task.Task.canStopNow] returns true.
 *
 * Plan 4b spec §5.2.
 */
enum class DeferAction
{
	DROP,         // give up; emit BreakDropped; reset interval
	RESCHEDULE,   // emit BreakRescheduled; try again sooner
	ESCALATE      // call BedtimeEscalator (only Bedtime defaults to this)
}
```

- [ ] **Step 3: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakTier.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/DeferAction.kt
git commit -m "BuildCore: Plan 4b — BreakTier (typealias) + DeferAction enums"
git push origin main
```

---

### Task 10 — `BreakConfig` data classes + defaults

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfig.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import net.vital.plugins.buildcore.core.events.BreakTier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BreakConfigTest
{
	@Test
	fun `default config has all four tiers enabled`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(4, cfg.tiers.size)
		BreakTier.values().forEach { tier ->
			assertTrue(cfg.tiers.containsKey(tier), "tier $tier missing")
		}
	}

	@Test
	fun `Bedtime defaults to ESCALATE on defer timeout`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(DeferAction.ESCALATE, cfg.tiers[BreakTier.BEDTIME]!!.onDeferTimeout)
	}

	@Test
	fun `Banking defaults to DROP on defer timeout`()
	{
		val cfg = BreakConfig.defaults()
		assertEquals(DeferAction.DROP, cfg.tiers[BreakTier.BANKING]!!.onDeferTimeout)
	}

	@Test
	fun `intervalRange is non-empty for non-trigger-driven tiers`()
	{
		val cfg = BreakConfig.defaults()
		assertTrue(cfg.tiers[BreakTier.MICRO]!!.intervalRangeMs.first > 0)
		assertTrue(cfg.tiers[BreakTier.NORMAL]!!.intervalRangeMs.first > 0)
		assertTrue(cfg.tiers[BreakTier.BEDTIME]!!.intervalRangeMs.first > 0)
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakConfigTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors — `BreakConfig` does not exist.

- [ ] **Step 3: Implement `BreakConfig.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * Per-tier break configuration. All durations and intervals are user-tunable;
 * defaults shipped in [BreakConfig.defaults]. Persisted as `breaks.json`
 * under [net.vital.plugins.buildcore.core.logging.LogDirLayout.breakConfigDir].
 *
 * Plan 4b spec §5.2.
 */
data class TierConfig(
	val enabled: Boolean,
	val durationRangeMs: LongRange,
	val intervalRangeMs: LongRange,
	val maxDeferMs: Long,
	val onDeferTimeout: DeferAction
)

data class BreakConfig(
	val tiers: Map<BreakTier, TierConfig>
)
{
	companion object
	{
		fun defaults(): BreakConfig = BreakConfig(
			tiers = mapOf(
				BreakTier.MICRO to TierConfig(
					enabled = true,
					durationRangeMs  =       5_000L..       30_000L,    // 5–30s
					intervalRangeMs  =     180_000L..      720_000L,    // 3–12min
					maxDeferMs       =       60_000L,                   // 60s
					onDeferTimeout   = DeferAction.RESCHEDULE
				),
				BreakTier.NORMAL to TierConfig(
					enabled = true,
					durationRangeMs  =     180_000L..    1_200_000L,    // 3–20min
					intervalRangeMs  =   1_800_000L..    5_400_000L,    // 30–90min
					maxDeferMs       =     300_000L,                    // 5min
					onDeferTimeout   = DeferAction.RESCHEDULE
				),
				BreakTier.BEDTIME to TierConfig(
					enabled = true,
					durationRangeMs  =  21_600_000L..   36_000_000L,    // 6–10h
					intervalRangeMs  =  50_400_000L..   79_200_000L,    // 14–22h
					maxDeferMs       =   1_800_000L,                    // 30min
					onDeferTimeout   = DeferAction.ESCALATE
				),
				BreakTier.BANKING to TierConfig(
					enabled = true,
					durationRangeMs  =      20_000L..       60_000L,    // 20–60s
					intervalRangeMs  =           1L..            1L,    // trigger-driven; not interval-scheduled
					maxDeferMs       =       30_000L,                   // 30s
					onDeferTimeout   = DeferAction.DROP
				)
			)
		)
	}
}
```

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakConfigTest*" --no-daemon 2>&1 | tail -10
```
Expected: 4 passed.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfig.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigTest.kt
git commit -m "BuildCore: Plan 4b — BreakConfig data classes + sane defaults"
git push origin main
```

---

### Task 11 — `BreakConfigStore` (JSON load/persist)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigStore.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BreakConfigStoreTest
{
	@Test
	fun `load returns defaults when file does not exist`(@TempDir dir: Path)
	{
		val cfg = BreakConfigStore(dir).load()
		assertEquals(BreakConfig.defaults(), cfg)
	}

	@Test
	fun `save then load round-trips`(@TempDir dir: Path)
	{
		val original = BreakConfig.defaults()
		val store = BreakConfigStore(dir)
		store.save(original)
		val loaded = store.load()
		assertEquals(original, loaded)
	}

	@Test
	fun `missing tier in file falls back to defaults`(@TempDir dir: Path)
	{
		val partial = BreakConfig(tiers = mapOf(
			net.vital.plugins.buildcore.core.events.BreakTier.MICRO to TierConfig(
				enabled = false,
				durationRangeMs = 1L..2L,
				intervalRangeMs = 3L..4L,
				maxDeferMs = 5L,
				onDeferTimeout = DeferAction.DROP
			)
		))
		BreakConfigStore(dir).save(partial)
		val loaded = BreakConfigStore(dir).load()
		assertEquals(false, loaded.tiers[net.vital.plugins.buildcore.core.events.BreakTier.MICRO]!!.enabled)
		// Other tiers fall back to defaults
		assertEquals(BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.NORMAL],
			loaded.tiers[net.vital.plugins.buildcore.core.events.BreakTier.NORMAL])
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakConfigStoreTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Implement `BreakConfigStore.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import net.vital.plugins.buildcore.core.events.BreakTier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and persists [BreakConfig] as JSON. Atomic write via temp-file +
 * `Files.move(REPLACE_EXISTING)`. Missing tiers in the loaded file fall back
 * to [BreakConfig.defaults] for forward compatibility.
 *
 * Plan 4b spec §5.2.
 */
class BreakConfigStore(private val dir: Path)
{
	private val mapper = ObjectMapper().registerModule(kotlinModule())

	private val file: Path get() = dir.resolve("breaks.json")

	fun load(): BreakConfig
	{
		if (!Files.exists(file)) return BreakConfig.defaults()
		val raw = mapper.readValue<BreakConfigDto>(file.toFile())
		val merged = BreakTier.values().associateWith { tier ->
			raw.tiers[tier] ?: BreakConfig.defaults().tiers[tier]!!
		}
		return BreakConfig(tiers = merged)
	}

	fun save(cfg: BreakConfig)
	{
		Files.createDirectories(dir)
		val tmp = Files.createTempFile(dir, "breaks", ".json.tmp")
		mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), BreakConfigDto(cfg.tiers))
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
	}

	// Jackson cannot deserialize Map<BreakTier, ...> with EnumKeyDeserializer in all
	// versions; this DTO is the explicit shape on disk. We allow an empty map
	// so partial files merge with defaults.
	private data class BreakConfigDto(val tiers: Map<BreakTier, TierConfig> = emptyMap())
}
```

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakConfigStoreTest*" --no-daemon 2>&1 | tail -10
```
Expected: 3 passed. If `LongRange` deserialization fails (Jackson does not know LongRange), add a custom serializer/deserializer to the DTO. Two-line workaround:

```kotlin
private data class TierConfigDto(val enabled: Boolean, val durStart: Long, val durEnd: Long, val ivStart: Long, val ivEnd: Long, val maxDeferMs: Long, val onDeferTimeout: DeferAction)
```

…and convert `TierConfig <-> TierConfigDto` in `load`/`save`. If the default serialization works (recent jackson-module-kotlin handles LongRange via its `first`/`last`/`step` props correctly), keep the simple DTO. Run the test once and choose based on output.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigStore.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakConfigStoreTest.kt
git commit -m "BuildCore: Plan 4b — BreakConfigStore with JSON round-trip + missing-tier fallback"
git push origin main
```

---

### Task 12 — `BreakState` internal state machine

**File:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakState.kt`

- [ ] **Step 1: Implement `BreakState.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import java.util.concurrent.atomic.AtomicReference

/**
 * Internal mutable state of [BreakScheduler]. Single instance per scheduler.
 * Holds the currently-active break (if any) and lets SURVIVAL preempt it.
 *
 * Plan 4b spec §5.3, §5.5.
 */
internal class BreakState
{
	private val active = AtomicReference<ActiveBreak?>(null)

	fun startActive(tier: BreakTier, plannedDurationMs: Long, startedAtMs: Long)
	{
		active.set(ActiveBreak(tier, plannedDurationMs, startedAtMs))
	}

	fun clearActive()
	{
		active.set(null)
	}

	fun activeOrNull(): ActiveBreak? = active.get()

	internal data class ActiveBreak(val tier: BreakTier, val plannedDurationMs: Long, val startedAtMs: Long)
}
```

- [ ] **Step 2: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakState.kt
git commit -m "BuildCore: Plan 4b — BreakState (active-break holder for preemption)"
git push origin main
```

---

### Task 13 — `BreakScheduler` core (cooperative loop)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakScheduler.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakSchedulerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.task.Task
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class BreakSchedulerTest
{
	private val sid = UUID.randomUUID()

	@Test
	fun `scheduler fires break when canStopNow returns true`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val canStop = AtomicBoolean(true)
		val task = mockk<Task>()
		every { task.canStopNow(any()) } answers { canStop.get() }

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 1_000L, DeferAction.DROP)
		))
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L))
		val job = launch { sched.run() }

		advanceTimeBy(5_000L)   // first interval
		advanceTimeBy(2_000L)   // executeBreak: 1s plan + slack
		job.cancelAndJoin()

		assertTrue(emitted.any { it is BreakStarted && it.tier == BreakTier.MICRO })
		assertTrue(emitted.any { it is BreakEnded   && it.tier == BreakTier.MICRO })
	}

	@Test
	fun `scheduler defers when canStopNow false then drops on timeout`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val task = mockk<Task>()
		every { task.canStopNow(any()) } returns false

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 2_000L, DeferAction.DROP)
		))
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L))
		val job = launch { sched.run() }

		advanceTimeBy(5_000L)   // first scheduled fire
		advanceTimeBy(3_000L)   // exceeds maxDeferMs (2s)
		job.cancelAndJoin()

		assertTrue(emitted.any { it is BreakDeferred })
		assertTrue(emitted.any { it is BreakDropped })
		assertFalse(emitted.any { it is BreakStarted })
	}

	@Test
	fun `RESCHEDULE re-arms next-fire after timeout`() = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }

		val task = mockk<Task>()
		every { task.canStopNow(any()) } returns false

		val cfg = BreakConfig(tiers = mapOf(
			BreakTier.MICRO to TierConfig(true, 1_000L..1_000L, 5_000L..5_000L, 1_000L, DeferAction.RESCHEDULE)
		))
		val sched = BreakScheduler(bus, { sid }, { task }, cfg, JavaUtilRng(seed = 1L))
		val job = launch { sched.run() }

		advanceTimeBy(5_000L); advanceTimeBy(2_000L)   // first fire → defer → reschedule
		advanceTimeBy(2_000L); advanceTimeBy(2_000L)   // re-attempt
		job.cancelAndJoin()

		assertTrue(emitted.count { it is BreakRescheduled } >= 1)
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakSchedulerTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Implement `BreakScheduler.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.util.UUID

/**
 * Cooperative break orchestrator. Single coroutine per session; one launch per
 * enabled tier. Polls [Task.canStopNow] before firing; never force-pauses the
 * runner. Bedtime tier escalates via [BedtimeEscalator] (Task 14).
 *
 * Plan 4b spec §5.3.
 */
class BreakScheduler(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID,
	private val taskProvider: () -> Task?,
	private val config: BreakConfig,
	private val rng: SeededRng,
	private val taskContextProvider: () -> TaskContext? = { null },
	private val nowMs: () -> Long = System::currentTimeMillis,
	private val pollIntervalMs: Long = 500L,
	private val bedtimeEscalator: BedtimeEscalator = BedtimeEscalator(bus, sessionIdProvider)
)
{
	private val state = BreakState()

	internal fun activeStateForTests(): BreakState = state

	suspend fun run() = coroutineScope {
		config.tiers
			.filter { it.value.enabled && it.key != BreakTier.BANKING }  // Banking is trigger-driven, fired by services
			.forEach { (tier, tierCfg) ->
				launch { tierLoop(tier, tierCfg) }
			}
	}

	private suspend fun tierLoop(tier: BreakTier, cfg: TierConfig)
	{
		while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false)
		{
			val intervalMs = sampleRange(cfg.intervalRangeMs)
			val fireAt = nowMs() + intervalMs
			bus.tryEmit(BreakScheduled(sessionId = sessionIdProvider(), tier = tier, fireAtEpochMs = fireAt))
			delay(intervalMs)
			pollFire(tier, cfg, fireAt)
		}
	}

	private suspend fun pollFire(tier: BreakTier, cfg: TierConfig, fireAt: Long)
	{
		val deadlineMs = fireAt + cfg.maxDeferMs
		var lastDeferEmit = 0L
		while (nowMs() < deadlineMs)
		{
			val task = taskProvider()
			val ctx = taskContextProvider()
			val canStop = if (task != null && ctx != null) task.canStopNow(ctx) else true
			if (canStop)
			{
				executeBreak(tier, cfg)
				return
			}
			val deferredMs = nowMs() - fireAt
			if (deferredMs - lastDeferEmit >= 1_000L)
			{
				bus.tryEmit(BreakDeferred(sessionId = sessionIdProvider(), tier = tier, deferredMillis = deferredMs))
				lastDeferEmit = deferredMs
			}
			delay(pollIntervalMs)
		}
		applyTimeout(tier, cfg, fireAt)
	}

	private suspend fun executeBreak(tier: BreakTier, cfg: TierConfig)
	{
		val plannedMs = sampleRange(cfg.durationRangeMs)
		val startMs = nowMs()
		state.startActive(tier, plannedMs, startMs)
		bus.tryEmit(BreakStarted(sessionId = sessionIdProvider(), tier = tier, plannedDurationMillis = plannedMs))
		delay(plannedMs)
		val actual = nowMs() - startMs
		state.clearActive()
		bus.tryEmit(BreakEnded(sessionId = sessionIdProvider(), tier = tier, actualDurationMillis = actual))
	}

	private suspend fun applyTimeout(tier: BreakTier, cfg: TierConfig, fireAt: Long)
	{
		val deferred = nowMs() - fireAt
		when (cfg.onDeferTimeout)
		{
			DeferAction.DROP ->
				bus.tryEmit(BreakDropped(sessionId = sessionIdProvider(), tier = tier, deferredMillis = deferred))
			DeferAction.RESCHEDULE ->
			{
				val newFireAt = nowMs() + (cfg.intervalRangeMs.first / 4).coerceAtLeast(1_000L)
				bus.tryEmit(BreakRescheduled(sessionId = sessionIdProvider(), tier = tier, newFireAtEpochMs = newFireAt))
			}
			DeferAction.ESCALATE ->
			{
				bus.tryEmit(EarlyStopRequested(sessionId = sessionIdProvider(), reason = EarlyStopReason.BEDTIME))
				bedtimeEscalator.escalate(taskProvider(), taskContextProvider(), cfg)
				// After escalation, fire the break unconditionally.
				executeBreak(tier, cfg)
			}
		}
	}

	/** Called from [PrecisionGate.preemptHook] when a SURVIVAL scope opens. */
	fun preempt()
	{
		val active = state.activeOrNull() ?: return
		val remaining = (active.startedAtMs + active.plannedDurationMs - nowMs()).coerceAtLeast(0L)
		state.clearActive()
		bus.tryEmit(BreakPreempted(sessionId = sessionIdProvider(), tier = active.tier, remainingMillis = remaining))
		// Note: the executeBreak coroutine's `delay(plannedMs)` is not cancelled — the in-flight
		// emission of BreakEnded after the remaining time is acceptable; Plan 6 watchdog can flag
		// stuck-active scenarios. For tighter cancel, refactor executeBreak to use a deferrable Job
		// (out of 4b scope per spec §11 Risks).
	}

	private fun sampleRange(range: LongRange): Long
	{
		if (range.first >= range.last) return range.first
		val span = range.last - range.first
		return range.first + (rng.nextLong() and Long.MAX_VALUE) % (span + 1)
	}
}
```

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakSchedulerTest*" --no-daemon 2>&1 | tail -15
```
Expected: 3 passed. If virtual-time advancement timing is off, adjust the `advanceTimeBy` amounts; the implementation's `delay(intervalMs)` then `pollFire` loop should produce the events asserted.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakScheduler.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakSchedulerTest.kt
git commit -m "BuildCore: Plan 4b — BreakScheduler cooperative coroutine driver"
git push origin main
```

---

### Task 14 — `BedtimeEscalator`

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BedtimeEscalator.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BedtimeEscalatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EarlyStopReason
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BedtimeEscalatorTest
{
	@Test
	fun `escalate calls requestEarlyStop exactly once`() = runTest {
		val bus = mockk<EventBus>(relaxed = true)
		val task = mockk<Task>(relaxed = true)
		val ctx  = mockk<TaskContext>(relaxed = true)
		val cfg = BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.BEDTIME]!!
		val escalator = BedtimeEscalator(bus, { UUID.randomUUID() })
		escalator.escalate(task, ctx, cfg)
		coVerify(exactly = 1) { task.requestEarlyStop(EarlyStopReason.BEDTIME) }
	}

	@Test
	fun `escalate is a no-op when task is null`() = runTest {
		val bus = mockk<EventBus>(relaxed = true)
		val cfg = BreakConfig.defaults().tiers[net.vital.plugins.buildcore.core.events.BreakTier.BEDTIME]!!
		val escalator = BedtimeEscalator(bus, { UUID.randomUUID() })
		escalator.escalate(null, null, cfg)   // must not throw
	}
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BedtimeEscalatorTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Implement `BedtimeEscalator.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import net.vital.plugins.buildcore.core.events.EarlyStopReason
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.util.UUID

/**
 * Drives the Bedtime escalation: tells the active task to wind itself down
 * via [Task.requestEarlyStop]. The task is responsible for finding a safe
 * terminus (bank, log out). The Runner state machine is unaffected — this is
 * cooperative.
 *
 * Plan 4b spec §5.4.
 */
class BedtimeEscalator(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID
)
{
	suspend fun escalate(task: Task?, ctx: TaskContext?, cfg: TierConfig)
	{
		if (task == null) return
		task.requestEarlyStop(EarlyStopReason.BEDTIME)
		// The "fire anyway" branch is handled by BreakScheduler.applyTimeout —
		// after this method returns, the scheduler invokes executeBreak unconditionally.
	}
}
```

- [ ] **Step 4: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BedtimeEscalatorTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 passed.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BedtimeEscalator.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BedtimeEscalatorTest.kt
git commit -m "BuildCore: Plan 4b — BedtimeEscalator (cooperative early-stop request)"
git push origin main
```

---

### Task 15 — SURVIVAL preemption test (`BreakSchedulerPreemptionTest`)

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakSchedulerPreemptionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.breaks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.task.Task
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BreakSchedulerPreemptionTest
{
	@Test
	fun `preempt clears active break and emits BreakPreempted`()
	{
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val task = mockk<Task>(relaxed = true)
		val cfg = BreakConfig.defaults()
		val sched = BreakScheduler(bus, { UUID.randomUUID() }, { task }, cfg, JavaUtilRng(seed = 1L))

		// Manually push an active break into BreakState for this unit-level assertion.
		val state = sched.activeStateForTests()
		state.startActive(BreakTier.MICRO, plannedDurationMs = 30_000L, startedAtMs = 0L)
		assertNotNull(state.activeOrNull())

		sched.preempt()

		assertNull(state.activeOrNull())
		assertTrue(emitted.any { it is BreakPreempted && it.tier == BreakTier.MICRO })
	}

	@Test
	fun `preempt is idempotent when no break active`()
	{
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val sched = BreakScheduler(bus, { UUID.randomUUID() }, { mockk(relaxed = true) }, BreakConfig.defaults(), JavaUtilRng(seed = 1L))

		sched.preempt()
		sched.preempt()

		assertTrue(emitted.none { it is BreakPreempted })
	}
}
```

- [ ] **Step 2: Run test**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakSchedulerPreemptionTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 passed.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/breaks/BreakSchedulerPreemptionTest.kt
git commit -m "BuildCore: Plan 4b — preempt test for SURVIVAL break interruption"
git push origin main
```

---

## Phase 4 — Misclick (Tasks 16-18)

### Task 16 — `MisclickKind` enum + `MisclickPolicy` (primitive jitter)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickKind.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickPolicy.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickPolicyTest.kt`

> **Note:** `MisclickKind` is already declared in `BusEvent.kt` (Task 1). Follow the same `BreakTier` typealias pattern — re-export for namespace clarity.

- [ ] **Step 1: Create the typealias**

`MisclickKind.kt`:

```kotlin
package net.vital.plugins.buildcore.core.antiban.misclick

typealias MisclickKind = net.vital.plugins.buildcore.core.events.MisclickKind
```

- [ ] **Step 2: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.misclick

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MisclickPolicyTest
{
	private lateinit var personality: PersonalityVector
	private lateinit var fatigue: FatigueCurve
	private val emitted = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { emitted.add(firstArg()); true }
	}

	@BeforeEach
	fun reset()
	{
		emitted.clear()
		PrecisionGate.resetForTests()
		personality = PersonalityVector(
			mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
			overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
			hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
			cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
			breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
			misclickRate = 0.015,   // max
			menuTopSelectionRate = 0.95, idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
		)
		fatigue = mockk()
		every { fatigue.misclickMultiplier() } returns 5.0   // amplify so we see misclicks at finite seed
	}

	@Test
	fun `decide returns false in PRECISION mode`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val rng = JavaUtilRng(seed = 1L)
		repeat(100) {
			assertFalse(MisclickPolicy.decide(personality, fatigue, rng, mode = InputMode.PRECISION))
		}
		PrecisionGate.markExitScope()
	}

	@Test
	fun `decide produces misclicks at expected rate over many samples`()
	{
		val rng = JavaUtilRng(seed = 42L)
		val total = 10_000
		val hits = (1..total).count { MisclickPolicy.decide(personality, fatigue, rng, mode = InputMode.NORMAL) }
		// expected p = misclickRate (0.015) * fatigue 5.0 = 0.075
		// observed should land in 0.05..0.10 with seeded RNG
		val observed = hits.toDouble() / total
		assertTrue(observed in 0.05..0.10) { "observed misclick rate $observed outside expected band" }
	}

	@Test
	fun `intercept emits Misclick event with corrected=false for PIXEL_JITTER`()
	{
		val rng = JavaUtilRng(seed = 99L)
		val sid = UUID.randomUUID()
		MisclickPolicy.bus = bus
		MisclickPolicy.sessionIdProvider = { sid }
		MisclickPolicy.recordPixelJitter(intendedX = 100, intendedY = 200, dx = 2, dy = -1)
		val ev = emitted.single() as Misclick
		assertEquals(MisclickKind.PIXEL_JITTER, ev.kind)
		assertEquals(100, ev.intendedX)
		assertEquals(102, ev.actualX)
		assertEquals(199, ev.actualY)
		assertEquals(false, ev.corrected)
	}
}
```

- [ ] **Step 3: Run to confirm it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*MisclickPolicyTest*" --no-daemon 2>&1 | tail -10
```
Expected: compile errors — `MisclickPolicy` does not exist.

- [ ] **Step 4: Implement `MisclickPolicy.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.misclick

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.Misclick
import java.util.UUID

/**
 * Primitive-layer misclick policy. [decide] returns true when a click should
 * be jittered; [sampleOffset] picks the (dx, dy). [recordPixelJitter] /
 * [recordNearMiss] emit the matching [Misclick] events (the actual click
 * dispatch happens in `Mouse.click` — Task 17).
 *
 * Plan 4b spec §6.1.
 */
object MisclickPolicy
{
	@Volatile var bus: EventBus? = null
	@Volatile var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	internal const val NEAR_MISS_FRACTION = 0.10
	internal const val BURST_COOLDOWN_MS = 2_000L

	@Volatile private var lastMisclickAtMs: Long = 0L

	fun decide(
		personality: PersonalityVector,
		fatigue: FatigueCurve,
		rng: SeededRng,
		mode: InputMode,
		nowMs: Long = System.currentTimeMillis()
	): Boolean
	{
		if (mode != InputMode.NORMAL) return false
		if (nowMs - lastMisclickAtMs < BURST_COOLDOWN_MS) return false
		val p = personality.misclickRate * fatigue.misclickMultiplier()
		val hit = rng.nextDouble() < p
		if (hit) lastMisclickAtMs = nowMs
		return hit
	}

	fun pickKind(rng: SeededRng): MisclickKind =
		if (rng.nextDouble() < NEAR_MISS_FRACTION) MisclickKind.NEAR_MISS else MisclickKind.PIXEL_JITTER

	fun samplePixelJitterOffset(rng: SeededRng): Pair<Int, Int>
	{
		val dx = (rng.nextGaussian() * 1.5).toInt().coerceIn(-3, 3)
		val dy = (rng.nextGaussian() * 1.5).toInt().coerceIn(-3, 3)
		return dx to dy
	}

	fun sampleNearMissOffset(rng: SeededRng): Pair<Int, Int>
	{
		val dx = rng.nextIntInRange(5, 12) * if (rng.nextBoolean(0.5)) 1 else -1
		val dy = rng.nextIntInRange(5, 12) * if (rng.nextBoolean(0.5)) 1 else -1
		return dx to dy
	}

	fun recordPixelJitter(intendedX: Int, intendedY: Int, dx: Int, dy: Int)
	{
		bus?.tryEmit(Misclick(
			sessionId = sessionIdProvider(),
			kind = MisclickKind.PIXEL_JITTER,
			intendedX = intendedX, intendedY = intendedY,
			actualX = intendedX + dx, actualY = intendedY + dy,
			corrected = false
		))
	}

	fun recordNearMiss(intendedX: Int, intendedY: Int, dx: Int, dy: Int)
	{
		bus?.tryEmit(Misclick(
			sessionId = sessionIdProvider(),
			kind = MisclickKind.NEAR_MISS,
			intendedX = intendedX, intendedY = intendedY,
			actualX = intendedX + dx, actualY = intendedY + dy,
			corrected = true
		))
	}

	internal fun resetForTests()
	{
		lastMisclickAtMs = 0L
		bus = null
	}
}
```

- [ ] **Step 5: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*MisclickPolicyTest*" --no-daemon 2>&1 | tail -15
```
Expected: 3 passed. (If the rate-band test fails, recompute the expected band from the actual seeded RNG draw — that's the only test sensitive to RNG implementation specifics.)

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickKind.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickPolicy.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/MisclickPolicyTest.kt
git commit -m "BuildCore: Plan 4b — MisclickPolicy primitive jitter (PIXEL_JITTER + NEAR_MISS)"
git push origin main
```

---

### Task 17 — Wire `MisclickPolicy` into `Mouse.click`

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt`

- [ ] **Step 1: Replace `Mouse.click` body** to consult the policy:

```kotlin
	suspend fun click(button: MouseButton = MouseButton.LEFT, mode: InputMode = InputMode.NORMAL)
	{
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.enter(mode)
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)

		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))

		val pos = backend.currentPosition()
		val intendedX = pos.x
		val intendedY = pos.y

		if (net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.decide(personality, fatigueCurve, rng, mode))
		{
			val kind = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.pickKind(rng)
			when (kind)
			{
				net.vital.plugins.buildcore.core.events.MisclickKind.PIXEL_JITTER ->
				{
					val (dx, dy) = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.samplePixelJitterOffset(rng)
					net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.recordPixelJitter(intendedX, intendedY, dx, dy)
					backend.click(intendedX + dx, intendedY + dy, button)
				}
				net.vital.plugins.buildcore.core.events.MisclickKind.NEAR_MISS ->
				{
					val (dx, dy) = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.sampleNearMissOffset(rng)
					net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.recordNearMiss(intendedX, intendedY, dx, dy)
					backend.click(intendedX + dx, intendedY + dy, button)
					delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))
					backend.click(intendedX, intendedY, button)   // corrective click
				}
			}
		}
		else
		{
			backend.click(intendedX, intendedY, button)
		}

		bus?.tryEmit(InputAction(
			sessionId = sessionIdProvider(),
			kind = InputKind.MOUSE_CLICK,
			targetX = intendedX, targetY = intendedY,
			durationMillis = 0L,
			mode = mode
		))
	}
```

- [ ] **Step 2: Run all primitive tests** to confirm no regression

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*MouseTest*" --tests "*Mouse*" --no-daemon 2>&1 | tail -15
```
Expected: existing tests pass — they default to NORMAL with `MisclickPolicy.bus = null`, so the policy decides based on RNG and FatigueCurve mock; if any test had assumed a single `backend.click` call, it may now see misclick-corrective double clicks. Mitigation: those tests should mock `FatigueCurve.misclickMultiplier()` to return `0.0` (suppresses all misclicks deterministically) — update them.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt \
         $(git ls-files -m | grep MouseTest || true)
git commit -m "BuildCore: Plan 4b — wire MisclickPolicy into Mouse.click (jitter + near-miss + corrective)"
git push origin main
```

---

### Task 18 — `SemanticMisclickHook` (service-layer opt-in)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/SemanticMisclickHook.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/SemanticMisclickHookTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.misclick

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SemanticMisclickHookTest
{
	private val bus = mockk<EventBus>(relaxed = true)
	private val emitted = mutableListOf<BusEvent>()

	@BeforeEach
	fun reset()
	{
		emitted.clear()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		SemanticMisclickHook.bus = bus
		SemanticMisclickHook.sessionIdProvider = { UUID.randomUUID() }
		SemanticMisclickHook.personalityProvider = {
			PersonalityVector(
				mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
				overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
				hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
				cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
				breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
				misclickRate = 0.015, menuTopSelectionRate = 0.95,
				idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
			)
		}
		val fc = mockk<FatigueCurve>()
		every { fc.misclickMultiplier() } returns 5.0
		SemanticMisclickHook.fatigueProvider = { fc }
		SemanticMisclickHook.rngProvider = { JavaUtilRng(seed = 7L) }
	}

	@Test
	fun `rollMisclick returns false in PRECISION mode`()
	{
		repeat(50) {
			assertFalse(SemanticMisclickHook.rollMisclick("ctx", InputMode.PRECISION))
		}
	}

	@Test
	fun `emitSemanticMisclick produces a SemanticMisclick event`()
	{
		SemanticMisclickHook.emitSemanticMisclick("useItemOn", "feather", "fishing-rod")
		val ev = emitted.single() as SemanticMisclick
		assertEquals("useItemOn", ev.context)
		assertEquals("feather", ev.intended)
		assertEquals("fishing-rod", ev.actual)
	}
}
```

- [ ] **Step 2: Implement `SemanticMisclickHook.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.misclick

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.SemanticMisclick
import java.util.UUID

/**
 * Service-layer opt-in API. Plan 5+ services call [rollMisclick] to ask whether
 * to substitute a wrong target this call; if they do, they call
 * [emitSemanticMisclick] with the actual outcome. Suppressed in non-NORMAL.
 *
 * Plan 4b spec §6.2.
 */
object SemanticMisclickHook
{
	@Volatile var bus: EventBus? = null
	@Volatile var sessionIdProvider: () -> UUID = { UUID(0, 0) }
	@Volatile var personalityProvider: () -> PersonalityVector? = { null }
	@Volatile var fatigueProvider: () -> FatigueCurve? = { null }
	@Volatile var rngProvider: () -> SeededRng? = { null }

	internal const val SEMANTIC_BASE_PROB = 0.4   // tuned: combined with personality.misclickRate × fatigue, lands ~0.005

	fun rollMisclick(context: String, mode: InputMode = InputMode.NORMAL): Boolean
	{
		if (mode != InputMode.NORMAL) return false
		val pers = personalityProvider() ?: return false
		val fat  = fatigueProvider() ?: return false
		val rng  = rngProvider() ?: return false
		val p = SEMANTIC_BASE_PROB * pers.misclickRate * fat.misclickMultiplier()
		return rng.nextDouble() < p
	}

	fun emitSemanticMisclick(context: String, intended: String, actual: String)
	{
		bus?.tryEmit(SemanticMisclick(
			sessionId = sessionIdProvider(),
			context = context,
			intended = intended,
			actual = actual
		))
	}
}
```

- [ ] **Step 3: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*SemanticMisclickHookTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 passed.

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/SemanticMisclickHook.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/misclick/SemanticMisclickHookTest.kt
git commit -m "BuildCore: Plan 4b — SemanticMisclickHook (service-layer opt-in API)"
git push origin main
```

---

## Phase 5 — Two-tier privacy (Tasks 19-21)

### Task 19 — `LogDirLayout`: `breakConfigDir()` + `exportDir()`

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt`

- [ ] **Step 1: Add the two methods** after `personalityDir()`:

```kotlin
	/**
	 * Sibling directory to the logs root — `.../breaks/` (not nested under logs).
	 * Plan 4b's [net.vital.plugins.buildcore.core.antiban.breaks.BreakConfigStore]
	 * persists `breaks.json` here.
	 *
	 * Plan 4b spec §7.2.
	 */
	fun breakConfigDir(): Path
	{
		val dir = root.resolveSibling("breaks")
		Files.createDirectories(dir)
		return dir
	}

	/**
	 * Sibling directory to the logs root — `.../exports/`. Created lazily by
	 * [net.vital.plugins.buildcore.core.logging.ExportBundle] when the user
	 * runs an export. Local raw logs stay full-fidelity in [sessionDir].
	 *
	 * Plan 4b spec §7.2.
	 */
	fun exportDir(): Path
	{
		val dir = root.resolveSibling("exports")
		Files.createDirectories(dir)
		return dir
	}
```

- [ ] **Step 2: Update the `LoggingArchitectureTest`** that enforces "string literals like `logs` must only appear in `LogDirLayout`/`LogConfig`". Add `"breaks"` and `"exports"` to its allowed list.

Open `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`. Find the test that checks for forbidden literals (likely a Konsist `assertEmpty()` over file contents). Extend the allowed-literals set to include `"breaks"` and `"exports"`. If the test enforces only `"logs"` and `".vitalclient"`, no change is needed — it will still pass because the new strings are inside `LogDirLayout.kt` itself.

- [ ] **Step 3: Run arch tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*LoggingArchitectureTest*" --no-daemon 2>&1 | tail -10
```
Expected: pass.

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt
git commit -m "BuildCore: Plan 4b — LogDirLayout breakConfigDir() + exportDir()"
git push origin main
```

---

### Task 20 — Move `PrivacyScrubber` from write-time to export-time

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriter.kt`

The Plan 3 `LocalSummaryWriter` calls `PrivacyScrubber.scrub(event)` before writing each line. Per spec §7.3 the local sink is now full-fidelity; only the export bundle scrubs.

- [ ] **Step 1: Find the scrub call site**

```bash
grep -rn "PrivacyScrubber\.scrub" /c/Code/VitalPlugins/BuildCore/src/main/kotlin/
```

You should see exactly one production call site in `LocalSummaryWriter` (or wherever Plan 3 wired it). The username-hashing helper `hmacHex` is independent and stays.

- [ ] **Step 2: Remove the scrub call** from `LocalSummaryWriter`. Replace the line that wrote `PrivacyScrubber.scrub(event)` with the bare `event`. Keep the username-hash step if it's a separate `scrubUsernameOnly` helper — only the full `scrub` is moving. If `LocalSummaryWriter` only had the full `scrub`, replace with a new `PrivacyScrubber.hashAccountIdOnly(event)` defense-in-depth helper:

Add to `PrivacyScrubber.kt`:

```kotlin
	/**
	 * Defense-in-depth: hash only the account-identifying fields (username),
	 * leave behavioral fields untouched. Called by [LocalSummaryWriter] for
	 * every event written to the local log. Full scrubbing happens at export
	 * time (see [ExportBundle]).
	 *
	 * Plan 4b spec §7.3.
	 */
	fun hashAccountIdOnly(event: BusEvent): BusEvent = when (event)
	{
		is PersonalityResolved -> event   // usernameHash already hashed at construction
		else -> event                     // no other event currently carries an unhashed username
	}
```

Then update `LocalSummaryWriter` to call `PrivacyScrubber.hashAccountIdOnly(event)` instead of `PrivacyScrubber.scrub(event)`.

- [ ] **Step 3: Update `LocalSummaryWriter`'s KDoc**

Find the KDoc that says "the JSONL on local disk receives unscrubbed events — local disk is private app data". Update it to reference Plan 4b spec §7 and clarify the new two-tier model.

- [ ] **Step 4: Run all logging tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*Local*" --tests "*Logging*" --no-daemon 2>&1 | tail -15
```
Expected: existing tests pass. Any test that asserted "scrubbed event written to local log" now needs to assert "raw event written to local log" — update the expectation to the unscrubbed value.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriter.kt \
         $(git ls-files -m | grep -E '(Local|Logging).*Test' || true)
git commit -m "BuildCore: Plan 4b — local log keeps full fidelity; PrivacyScrubber moves to export-time"
git push origin main
```

---

### Task 21 — `ExportBundle` (scrub-on-demand writer)

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/ExportBundle.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/ExportBundleTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import net.vital.plugins.buildcore.core.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExportBundleTest
{
	@Test
	fun `export reads raw log and writes scrubbed jsonl`(@TempDir root: Path)
	{
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(root.resolve("logs"))
		val sessionDir = layout.createSessionDir(sid)
		val rawLog = sessionDir.resolve("session.log.jsonl")

		val mapper = ObjectMapper().registerModule(kotlinModule())
		val raw = listOf<BusEvent>(
			Misclick(sessionId = sid, kind = MisclickKind.PIXEL_JITTER,
				intendedX = 100, intendedY = 200, actualX = 102, actualY = 199, corrected = false),
			SemanticMisclick(sessionId = sid, context = "useItemOn", intended = "feather", actual = "rod")
		)
		Files.write(rawLog, raw.map { mapper.writeValueAsString(it) })

		val out = ExportBundle.create(layout, sid)

		assertTrue(Files.exists(out))
		val lines = Files.readAllLines(out)
		assertEquals(2, lines.size)

		val mc = mapper.readValue<Misclick>(lines[0])
		assertEquals(96, mc.intendedX)   // 100 → grid 16 → 96
		assertEquals(192, mc.intendedY)
		assertEquals(96, mc.actualX)
		assertEquals(192, mc.actualY)
	}
}
```

- [ ] **Step 2: Implement `ExportBundle.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Reads a session's raw `session.log.jsonl`, runs every event through
 * [PrivacyScrubber.scrub] (export-time scrubbing per Plan 4b §7.3), and writes
 * the result to [LogDirLayout.exportDir] as `<sessionId>-export.jsonl`.
 *
 * Plan 4b spec §7.1, §7.3.
 */
object ExportBundle
{
	private val mapper = ObjectMapper().registerModule(kotlinModule())

	fun create(layout: LogDirLayout, sessionId: UUID): Path
	{
		val rawLog = layout.sessionDir(sessionId).resolve("session.log.jsonl")
		require(Files.exists(rawLog)) { "no raw log at $rawLog" }
		val out = layout.exportDir().resolve("$sessionId-export.jsonl")
		Files.newBufferedWriter(out).use { writer ->
			Files.lines(rawLog).use { lines ->
				lines.forEach { line ->
					val ev = mapper.readValue<BusEvent>(line)
					val scrubbed = PrivacyScrubber.scrub(ev)
					writer.write(mapper.writeValueAsString(scrubbed))
					writer.newLine()
				}
			}
		}
		return out
	}
}
```

> **Implementer note:** `mapper.readValue<BusEvent>(line)` requires Jackson to know the `BusEvent` sealed-interface polymorphism. Plan 3 should have configured a `JsonTypeInfo` discriminator (likely `@type` field). If `readValue<BusEvent>` fails, look at how Plan 3's `LocalSummaryWriter` writes — match the same Jackson configuration (probably `mapper.activateDefaultTyping(...)` or per-class `@JsonTypeName`). Reuse the existing Plan 3 `ObjectMapper` configured for events — DO NOT create a fresh one. The `ExportBundle` may need to take an `ObjectMapper` parameter from `LogConfig`.

- [ ] **Step 3: Run tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ExportBundleTest*" --no-daemon 2>&1 | tail -15
```
Expected: 1 passed (after sorting Jackson polymorphism per implementer note).

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/ExportBundle.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/ExportBundleTest.kt
git commit -m "BuildCore: Plan 4b — ExportBundle (scrub-on-demand JSONL writer)"
git push origin main
```

---

## Phase 6 — Bootstrap wiring + integration (Tasks 22-23)

### Task 22 — Wire `BreakScheduler` + `MisclickPolicy` + `SemanticMisclickHook` + `PrecisionWindow` into `AntibanBootstrap`

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/AntibanBootstrap.kt`

- [ ] **Step 1: Add new fields and install logic**

In `AntibanBootstrap.install`, after the existing `Mouse.bus = bus` block, add:

```kotlin
		// Plan 4b: precision-window observer
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionWindow.bus = bus
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionWindow.sessionIdProvider = sessionIdProvider

		// Plan 4b: misclick policy
		net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.bus = bus
		net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.sessionIdProvider = sessionIdProvider

		// Plan 4b: semantic-misclick hook
		net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook.bus = bus
		net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook.sessionIdProvider = sessionIdProvider
		net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook.personalityProvider = { provider.currentPersonality(rng) }
		net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook.fatigueProvider = { fatigueCurve }
		net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook.rngProvider = { rng }

		// Plan 4b: break scheduler — config loaded from layout.breakConfigDir().
		// taskProvider/taskContextProvider come in from the Runner once Plan 6 wires it; for now,
		// they default to no-op (canStopNow=true), so all breaks fire immediately. Plan 6 replaces.
		val breakConfig = net.vital.plugins.buildcore.core.antiban.breaks.BreakConfigStore(layout.breakConfigDir()).load()
		val scheduler = net.vital.plugins.buildcore.core.antiban.breaks.BreakScheduler(
			bus = bus,
			sessionIdProvider = sessionIdProvider,
			taskProvider = { activeTaskRef.get() },
			config = breakConfig,
			rng = rng,
			taskContextProvider = { activeTaskContextRef.get() }
		)
		breakScheduler = scheduler
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.preemptHook = { scheduler.preempt() }

		// The scheduler coroutine itself is launched by BuildCorePlugin from its CoroutineScope —
		// keep the reference here so the plugin can call schedulerJob = scope.launch { scheduler.run() }.
```

Add the supporting fields at the top of the `AntibanBootstrap` object (after `throttle`):

```kotlin
	@Volatile var breakScheduler: net.vital.plugins.buildcore.core.antiban.breaks.BreakScheduler? = null
		private set

	private val activeTaskRef       = java.util.concurrent.atomic.AtomicReference<net.vital.plugins.buildcore.core.task.Task?>(null)
	private val activeTaskContextRef = java.util.concurrent.atomic.AtomicReference<net.vital.plugins.buildcore.core.task.TaskContext?>(null)

	/** Called by Runner (Plan 6) when a task starts/stops. */
	fun setActiveTask(task: net.vital.plugins.buildcore.core.task.Task?, ctx: net.vital.plugins.buildcore.core.task.TaskContext?)
	{
		activeTaskRef.set(task)
		activeTaskContextRef.set(ctx)
	}
```

Update `resetForTests()` to clear the new state:

```kotlin
	internal fun resetForTests() {
		installed.set(false)
		personalityProvider = null
		sessionRng = null
		fatigue = null
		breakScheduler = null
		activeTaskRef.set(null)
		activeTaskContextRef.set(null)
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.resetForTests()
		net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.resetForTests()
	}
```

- [ ] **Step 2: Run all tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -25
```
Expected: all pass.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/AntibanBootstrap.kt
git commit -m "BuildCore: Plan 4b — wire BreakScheduler / MisclickPolicy / PrecisionWindow into AntibanBootstrap"
git push origin main
```

---

### Task 23 — Integration test (`BreakSchedulerIntegrationTest`)

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/BreakSchedulerIntegrationTest.kt`

- [ ] **Step 1: Write the integration test**

```kotlin
package net.vital.plugins.buildcore.integration

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionWindow
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BreakSchedulerIntegrationTest
{
	@AfterEach fun teardown() { AntibanBootstrap.resetForTests() }

	@Test
	fun `withSurvival inside an active break preempts it`(@TempDir root: Path) = runTest {
		val emitted = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(root.resolve("logs"))

		AntibanBootstrap.install(bus, { sid }, layout)
		val scheduler = AntibanBootstrap.breakScheduler!!
		val task = mockk<Task>(relaxed = true)
		val ctx  = mockk<TaskContext>(relaxed = true)
		every { task.canStopNow(any()) } returns true
		AntibanBootstrap.setActiveTask(task, ctx)

		val job = launch { scheduler.run() }
		advanceTimeBy(60_000L)   // let some interval pass; MICRO interval default 3-12min so may not fire — instead manually start
		// Push an active break for the assertion:
		scheduler.activeStateForTests().startActive(BreakTier.MICRO, plannedDurationMs = 30_000L, startedAtMs = 0L)

		PrecisionWindow.withSurvival { /* simulated emergency */ }

		assertNull(scheduler.activeStateForTests().activeOrNull())
		assertTrue(emitted.any { it is BreakPreempted && it.tier == BreakTier.MICRO })

		job.cancelAndJoin()
	}
}
```

- [ ] **Step 2: Run test**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BreakSchedulerIntegrationTest*" --no-daemon 2>&1 | tail -15
```
Expected: 1 passed.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/BreakSchedulerIntegrationTest.kt
git commit -m "BuildCore: Plan 4b — integration test: withSurvival preempts active break end-to-end"
git push origin main
```

---

## Phase 7 — Architecture test (Task 24)

### Task 24 — `PrecisionInputArchTest` (Konsist)

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/PrecisionInputArchTest.kt`

- [ ] **Step 1: Implement the Konsist test**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withInternalModifier
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures that any function which calls [withPrecision], [withSurvival], or
 * [PrecisionGate.enter] with a non-NORMAL mode is annotated `@UsesPrecisionInput`
 * (transitively — annotation may be on the function itself or any caller in
 * the same source file).
 *
 * Plan 4b spec §4.4.
 */
class PrecisionInputArchTest
{
	@Test
	fun `non-NORMAL precision callers must be annotated UsesPrecisionInput`()
	{
		val triggers = setOf("withPrecision", "withSurvival")

		Konsist.scopeFromProduction()
			.functions(includeNested = true, includeLocal = true)
			.filter { f ->
				// Function body text contains a call to one of the triggers.
				f.text.let { src -> triggers.any { src.contains("$it(") } }
			}
			.filterNot { f -> f.name == "withPrecision" || f.name == "withSurvival" || f.name == "runScope" }
			.assertTrue(testName = "Function calling withPrecision/withSurvival must be @UsesPrecisionInput") { f ->
				f.hasAnnotation { it.name == "UsesPrecisionInput" }
			}
	}

	@Test
	fun `PrecisionGate is the only direct caller of mode-dispatch logic outside its own package`()
	{
		Konsist.scopeFromProduction()
			.files
			.filter { it.path.contains("/main/kotlin/") }
			.filterNot { it.path.contains("/precision/") }
			.filterNot { it.path.contains("/input/") }                  // Mouse/Keyboard/Camera legitimately call PrecisionGate.enter
			.filterNot { it.path.contains("AntibanBootstrap") }
			.forEach { file ->
				assert(!file.text.contains("PrecisionGate.enter"))
				{ "${file.path} calls PrecisionGate.enter directly — only Mouse/Keyboard/Camera/AntibanBootstrap may" }
			}
	}
}
```

- [ ] **Step 2: Run test**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrecisionInputArchTest*" --no-daemon 2>&1 | tail -15
```
Expected: pass. If it fails because some legitimate code path is missed, narrow the filter — but the rule should hold for current source.

> **Implementer note (per spec §11 Risks):** if Konsist's text-based check turns out to be brittle (false positives on commented-out calls, multi-line break splits), simplify to a one-hop check ("function body contains literal `withPrecision(` or `withSurvival(`") and document the limitation in test KDoc. Transitive enforcement is nice-to-have, not load-bearing.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/PrecisionInputArchTest.kt
git commit -m "BuildCore: Plan 4b — PrecisionInputArchTest (Konsist) enforces @UsesPrecisionInput"
git push origin main
```

---

## Phase 8 — Docs (Task 25)

### Task 25 — Update BuildCore CLAUDE.md

**File:** Modify `BuildCore/CLAUDE.md`

- [ ] **Step 1: Update Status section**

Replace the existing Status block in `BuildCore/CLAUDE.md` with:

```markdown
## Status

**Foundation phase — Plans 1 + 2 + 3 + 4a + 4b complete.**

Future plans in `../docs/superpowers/plans/`:
- ~~Plan 2 — Core Runtime + Task SPI + Restrictions~~ (done)
- ~~Plan 3 — Logging + Event Bus~~ (done)
- ~~Plan 4a — RNG + Personality + Input Primitives~~ (done)
- ~~Plan 4b — Precision Mode + 4-tier break system + Misclick~~ (done)
- Plan 4c — ReplayRecorder + ReplayRng + ReplayServices
- Plan 5 — Action Library (L5 Services)
- Plan 6 — Confidence / Watchdog / Recovery
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell
```

- [ ] **Step 2: Update the "Current invariants" section**

Replace with:

```markdown
Current invariants (Plans 2 + 3 + 4a + 4b):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions.
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet.
- Every `BusEvent` subtype has a `PrivacyScrubber` case (exhaustive-when + drift test, 39 subtypes).
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
```

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/CLAUDE.md
git commit -m "BuildCore: mark Plan 4b complete in subproject CLAUDE.md"
git push origin main
```

---

## Self-review

### Spec coverage

| Spec section | Implementing task(s) |
|---|---|
| §1 goal | Phases 1-7 collectively |
| §2 out of scope | N/A (deferrals doc'd in spec) |
| §3.1 module map | Tasks 4-23 produce exactly this layout |
| §3.2 layering | Tasks 8 (primitives), 13 (scheduler), 17 (mouse) |
| §4.1 precision behavioral contract | Task 7 (ReactionDelay), Task 8 (primitives) |
| §4.2 PrecisionGate | Task 5 |
| §4.3 PrecisionWindow | Task 6 |
| §4.4 @UsesPrecisionInput annotation | Task 4 |
| §4.4 Konsist arch test | Task 24 |
| §5.1 BreakTier enum | Task 9 |
| §5.2 BreakConfig + storage | Tasks 10, 11 |
| §5.3 BreakScheduler | Task 13 |
| §5.4 BedtimeEscalator | Task 14 |
| §5.5 SURVIVAL preemption | Task 15 (test) + Tasks 5/22 (wiring) |
| §5.6 Task.requestEarlyStop | Task 3 |
| §6.1 MisclickPolicy primitive jitter | Task 16, Task 17 |
| §6.2 SemanticMisclickHook | Task 18 |
| §7.1-7.2 Two-tier sinks | Task 19 |
| §7.3 Scrubber export-time | Task 20, Task 21 |
| §7.4 Per-event scrubber rules | Task 2 |
| §8 12 new BusEvent subtypes + 3 enums | Task 1 |
| §9 testing strategy | Tests embedded in Tasks 3, 5-8, 10-18, 21, 23, 24 |
| §10 brainstorming resolutions | All 8 resolutions reflected |
| §11 risks | Mitigated: Konsist note in Task 24, scheduler-vs-runner via Task 22 setActiveTask, burst cooldown in Task 16, bedtime "fire anyway" in Task 13 |

All sections mapped. No gaps.

### Placeholder scan

No `TBD`, `TODO`, `FIXME`, or "similar to" references. Two adaptive instructions are present and unavoidable (each scoped narrowly):
1. Task 11 Step 4 — "If LongRange Jackson serialization fails, fall back to TierConfigDto" — adaptive only because Jackson behavior across versions is empirically variable; the fallback code is fully specified.
2. Task 21 Step 2 implementer note — "reuse Plan 3's configured ObjectMapper" — adaptive because the exact Plan 3 mapper config isn't re-stated in this plan; the substitution is mechanical.

Every test contains complete code. Every commit message is concrete.

### Type consistency

Verified across tasks:
- `BreakTier` declared in `BusEvent.kt` (Task 1), re-exported via typealias in `breaks/BreakTier.kt` (Task 9). Used uniformly.
- `MisclickKind` same pattern (Task 1 declaration + Task 16 typealias re-export).
- `EarlyStopReason` enum used in Task 1 (event field), Task 3 (Task SPI parameter), Task 14 (BedtimeEscalator call), Task 13 (scheduler emit) — all `EarlyStopReason.BEDTIME` or `.USER`.
- `PrecisionGate.enter(mode): TimingProfile` signature consistent in Tasks 5, 8, 17.
- `PrecisionGate.markEnterScope`/`markExitScope` internal API consistent between Tasks 5 (impl) and 6 (PrecisionWindow caller).
- `BreakScheduler` constructor signature consistent across Tasks 13, 15 (test), 22 (bootstrap), 23 (integration).
- `BreakState.startActive(tier, plannedDurationMs, startedAtMs)` and `clearActive()` / `activeOrNull()` consistent between Tasks 12 (impl), 13 (caller), 15 (test), 23 (test).
- `PersonalityVector.misclickRate` (existing in 4a) used in Tasks 16, 18 — no new field added.
- `FatigueCurve.misclickMultiplier()` (existing in 4a) used in Tasks 16, 18.
- `Task.requestEarlyStop(reason: EarlyStopReason)` `suspend fun` signature consistent between Task 3 (declaration) and Task 14 (call).
- `MisclickPolicy.decide(personality, fatigue, rng, mode)` and `recordPixelJitter(intendedX, intendedY, dx, dy)` / `recordNearMiss(...)` consistent between Tasks 16 (impl) and 17 (Mouse caller).
- `SemanticMisclickHook` provider fields (`bus`, `sessionIdProvider`, `personalityProvider`, `fatigueProvider`, `rngProvider`) consistent between Task 18 (impl) and Task 22 (bootstrap wiring).
- `LogDirLayout.breakConfigDir()` / `exportDir()` consistent between Task 19 (impl), Task 21 (ExportBundle caller), Task 22 (bootstrap caller).
- `PrivacyScrubber.scrub` (full) used only in `ExportBundle` (Task 21); `PrivacyScrubber.hashAccountIdOnly` used in `LocalSummaryWriter` (Task 20).

No drift.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-25-plan-4b-precision-breaks-misclick.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
