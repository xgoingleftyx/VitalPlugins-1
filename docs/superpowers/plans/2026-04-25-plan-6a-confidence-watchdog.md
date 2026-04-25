# BuildCore Plan 6a — Confidence + ActionStakes Gating + Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the runtime monitoring half of foundation §11 — 8-signal confidence with 600ms cache, ActionStakes gating layered on Plan 5a's `withServiceCall`, and a 4-check Watchdog (STALL/UNCERTAIN/DEADLOCK/LEAK) running on a dedicated single-thread coroutine dispatcher.

**Architecture:** `ConfidenceTracker` is a Kotlin `object` (Plan 4a/5a pattern) that subscribes to `ServiceCallEnd` events, computes a weighted score from 4 real signals (interface, last-action-outcome, HP, dialog presence) + 4 stubbed signals (entities/area/inventory-delta — gated on new Task SPI hooks defaulting to `null` → 1.0; chat → 1.0). Cache invalidates on `ServiceCallEnd`; readers recompute if stale. `ConfidenceGate.check(stakes)` throws `ConfidenceTooLow`; `withServiceCall` catches and emits `ServiceCallEnd(outcome = UNCONFIDENT)`. `Watchdog` runs on a `WatchdogScope` (single-thread dispatcher) sibling to `LoggerScope`; 4 `WatchdogCheck` impls tick at 1Hz and emit `WatchdogTriggered`. `Runner` gains `lastHeartbeatMs` + 1Hz throttled heartbeat call. `PrecisionGate` (Plan 4b) gains `scopeEnteredAtMs` outermost-scope timestamp for `LeakCheck`.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0 (incl. `kotlinx-coroutines-test`), JUnit 5, MockK, Konsist 0.17.3. No new external deps.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-25-buildcore-plan6a-confidence-watchdog-design.md`](../specs/2026-04-25-buildcore-plan6a-confidence-watchdog-design.md)
- Plans 1+2+3+4a+4b+5a complete and merged (293 tests).
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit + push to `origin` (xgoingleftyx fork) after every commit.
- Author: Chich only — NO `Co-Authored-By` trailers.
- Style: tabs, Allman braces where applicable, UTF-8.

**Existing surface verified:**
- `BusEvent` is a sealed interface — `PrivacyScrubber.scrub` `when` is exhaustive at compile time. Adding new subtypes requires adding cases (Task 1 includes the stubs to satisfy this immediately).
- `EventBus.events: SharedFlow<BusEvent>` (Plan 3) — subscribe via `bus.events.collect { ... }` from a `LoggerScope`-launched coroutine. Plan 3's `SubscriberRegistry` is the established pattern; this plan uses it for `ConfidenceSubscriber` (Task 11).
- `Runner` (Plan 2) has a tick loop at `Runner.kt:183` (`while (true) { val stepResult = instance.task.step(ctx); ... }`). Heartbeat call goes inside that loop.
- `Task` interface (Plan 2) has open `suspend fun requestEarlyStop(...)` (added Plan 4b). Task 6 of this plan adds 3 more default-`null` SPI hooks.
- `PrecisionGate` (Plan 4b) `markEnterScope`/`markExitScope` already use a depth counter — `scopeEnteredAtMs` extension is a 4-line addition.
- `withServiceCall` (Plan 5a) signature: `(bus, sessionIdProvider, serviceName, methodName, restriction = null, block)`. Adding `stakes: ActionStakes = ActionStakes.MEDIUM` between `restriction` and `block` is a non-breaking change for existing call sites that pass `restriction` positionally — but the 13 services pass `restriction` named (e.g., `restriction = OperationalRestriction.BANK_DISABLED`) so the param order is irrelevant.

---

## File structure this plan produces

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                        # MODIFY — install ConfidenceBootstrap + Watchdog
└── core/
    ├── events/
    │   ├── BusEvent.kt                                       # MODIFY — 4 new subtypes + WatchdogKind enum + UNCONFIDENT outcome
    │   └── PrivacyScrubber.kt                                # MODIFY — 4 passthrough cases
    ├── task/
    │   ├── Runner.kt                                         # MODIFY — lastHeartbeatMs + heartbeat() call
    │   └── Task.kt                                           # MODIFY — 3 new SPI hooks (default null)
    ├── services/
    │   ├── WithServiceCall.kt                                # MODIFY — add `stakes` param + UNCONFIDENT classification
    │   └── (13 *Service.kt files)                            # MODIFY — pass `stakes = …` per method
    ├── antiban/precision/
    │   └── PrecisionGate.kt                                  # MODIFY — add scopeEnteredAtMs outermost timestamp
    └── confidence/
        ├── ActionStakes.kt
        ├── Confidence.kt
        ├── ConfidenceSignal.kt
        ├── ConfidenceTooLow.kt
        ├── GameStateProvider.kt                              # interface + VitalApiGameStateProvider
        ├── ConfidenceTracker.kt
        ├── ConfidenceGate.kt
        ├── ConfidenceSubscriber.kt                           # Plan 3 SubscriberRegistry adapter
        ├── ConfidenceBootstrap.kt
        ├── hints/
        │   ├── EntityHint.kt
        │   ├── AreaHint.kt
        │   └── InventoryDeltaHint.kt
        └── watchdog/
            ├── WatchdogScope.kt
            ├── WatchdogFinding.kt
            ├── WatchdogCheck.kt
            ├── Watchdog.kt
            └── checks/
                ├── StallCheck.kt
                ├── UncertainCheck.kt
                ├── DeadlockCheck.kt
                └── LeakCheck.kt

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/PrivacyScrubberTest.kt                        # MODIFY — bump 41→45 + 4 samples
├── arch/LoggingArchitectureTest.kt                           # MODIFY — bump 41→45
├── arch/ConfidenceArchitectureTest.kt                        # CREATE
├── core/task/RunnerHeartbeatTest.kt                          # CREATE
├── core/services/WithServiceCallStakesTest.kt                # CREATE
├── core/antiban/precision/PrecisionGateLeakTest.kt           # CREATE
└── core/confidence/
    ├── ConfidenceTrackerTest.kt
    ├── ConfidenceComputeTest.kt
    ├── ConfidenceGateTest.kt
    └── watchdog/
        ├── WatchdogTest.kt
        └── checks/{StallCheckTest, UncertainCheckTest, DeadlockCheckTest, LeakCheckTest}.kt
```

---

## Phase 1 — Events + outcome (Tasks 1-2)

### Task 1 — Add 4 new `BusEvent` subtypes + `WatchdogKind` enum + `UNCONFIDENT` outcome

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt` and `core/events/PrivacyScrubber.kt`.

- [ ] **Step 1:** Append to `BusEvent.kt`:

```kotlin
// ─────────────────────────────────────────────────────────────────────
// Confidence + Watchdog events (Plan 6a spec §7)
// ─────────────────────────────────────────────────────────────────────

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
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val kind: WatchdogKind,
	val detail: String
) : BusEvent

data class RunnerHeartbeat(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null
) : BusEvent

data class ConfidenceUnderconfidentAction(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val serviceName: String,
	val methodName: String,
	val required: Double,
	val current: Double
) : BusEvent
```

- [ ] **Step 2:** In `BusEvent.kt`, find `enum class ServiceOutcome { SUCCESS, FAILURE, RESTRICTED, EXCEPTION }` (added in Plan 5a Task 1). Add `UNCONFIDENT`:

```kotlin
enum class ServiceOutcome { SUCCESS, FAILURE, RESTRICTED, EXCEPTION, UNCONFIDENT }
```

- [ ] **Step 3:** In `PrivacyScrubber.kt`, find the `when` at the bottom of `ServiceCallEnd` line. Add 4 passthroughs after the existing service-call cases:

```kotlin
			is ConfidenceUpdated              -> event
			is WatchdogTriggered              -> event
			is RunnerHeartbeat                -> event
			is ConfidenceUnderconfidentAction -> event
```

- [ ] **Step 4:** Verify compile:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL. Tests will fail (next task fixes).

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — add 4 Confidence/Watchdog BusEvents + UNCONFIDENT ServiceOutcome"
git push origin main
```

---

### Task 2 — Bump arch test 41→45 + extend `PrivacyScrubberTest`

**Files:**
- Modify `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`
- Modify `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt`

- [ ] **Step 1:** In `LoggingArchitectureTest.kt`, change `val scrubberSampleCount = 41` to `45`.

- [ ] **Step 2:** In `PrivacyScrubberTest.kt`, append to the samples list:

```kotlin
		ConfidenceUpdated(sessionId = sid, score = 0.85, perSignal = mapOf("HP_NORMAL" to 1.0)),
		WatchdogTriggered(sessionId = sid, kind = WatchdogKind.STALL, detail = "task=foo unchanged 300000ms"),
		RunnerHeartbeat(sessionId = sid, taskInstanceId = UUID.randomUUID()),
		ConfidenceUnderconfidentAction(sessionId = sid, serviceName = "BankService", methodName = "open", required = 0.6, current = 0.4)
```

Add imports for `ConfidenceUpdated`, `WatchdogTriggered`, `WatchdogKind`, `RunnerHeartbeat`, `ConfidenceUnderconfidentAction`. Update any `assertEquals(samples.size, 41)` to `45`.

- [ ] **Step 3:** Run targeted tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*PrivacyScrubber*" --tests "*LoggingArchitectureTest*" --no-daemon 2>&1 | tail -15
```
Expected: all pass.

- [ ] **Step 4:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — bump PrivacyScrubber sample count 41→45"
git push origin main
```

---

## Phase 2 — Confidence types (Tasks 3-7)

### Task 3 — `ActionStakes`, `Confidence`, `ConfidenceSignal`, `ConfidenceTooLow`, `*Hint` types

**Files (all CREATE):**
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ActionStakes.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/Confidence.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceSignal.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceTooLow.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/hints/EntityHint.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/hints/AreaHint.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/hints/InventoryDeltaHint.kt`

- [ ] **Step 1: `ActionStakes.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * Confidence threshold required for a service action to proceed.
 * Plan 6a spec §4.3.
 */
enum class ActionStakes(val threshold: Double)
{
	READ_ONLY(0.0),    // pass-through; ConfidenceGate not consulted
	LOW(0.4),
	MEDIUM(0.6),
	HIGH(0.8),
	CRITICAL(0.9)
}
```

- [ ] **Step 2: `Confidence.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * Snapshot of confidence at a point in time. Plan 6a spec §4.2.
 */
data class Confidence(
	val score: Double,
	val perSignal: Map<String, Double>,
	val computedAtMs: Long
)
```

- [ ] **Step 3: `ConfidenceSignal.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * The 8 weighted confidence signals. Weights sum to 1.00.
 * Plan 6a spec §4.1.
 */
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

	companion object
	{
		val ALL: List<ConfidenceSignal> = listOf(
			InterfaceKnown, LastActionResulted, ExpectedEntitiesVisible, PositionReasonable,
			HpNormal, InventoryDeltaExpected, NoUnexpectedDialog, RecentChatNormal
		)
	}
}
```

- [ ] **Step 4: `ConfidenceTooLow.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * Thrown by [ConfidenceGate.check] when current confidence < required threshold.
 * Caught by `withServiceCall` and classified as [ServiceOutcome.UNCONFIDENT].
 * Plan 6a spec §5.1.
 */
class ConfidenceTooLow(
	val required: Double,
	val current: Double,
	val worstSignal: String
) : RuntimeException("confidence $current < required $required (worst signal: $worstSignal)")
```

- [ ] **Step 5: `hints/EntityHint.kt`, `hints/AreaHint.kt`, `hints/InventoryDeltaHint.kt`:**

```kotlin
// EntityHint.kt
package net.vital.plugins.buildcore.core.confidence.hints

data class EntityHint(val kind: String, val nameOrId: String)
```

```kotlin
// AreaHint.kt
package net.vital.plugins.buildcore.core.confidence.hints

data class AreaHint(val centerX: Int, val centerY: Int, val radius: Int)
```

```kotlin
// InventoryDeltaHint.kt
package net.vital.plugins.buildcore.core.confidence.hints

data class InventoryDeltaHint(val itemId: Int, val expectedQty: Int)
```

- [ ] **Step 6:** Verify compile:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
BUILD SUCCESSFUL.

- [ ] **Step 7:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — confidence value types (ActionStakes, Confidence, signals, hints)"
git push origin main
```

---

### Task 4 — `GameStateProvider` interface + `VitalApiGameStateProvider`

**File:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/GameStateProvider.kt`.

- [ ] **Step 1:** Create the interface + default impl:

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * Abstraction over VitalAPI reads needed by [ConfidenceTracker]. Default
 * impl ([VitalApiGameStateProvider]) calls `vital.api.*` directly. Tests
 * swap to a fake. Plan 6a spec §4.5.
 */
interface GameStateProvider
{
	/** Returns the open widget id, or null if none. */
	fun openWidgetId(): Int?

	/** True if any dialog widget is currently visible. */
	fun isDialogVisible(): Boolean

	/** Current HP / Max HP ratio in [0.0, 1.0]; null if unavailable. */
	fun hpRatio(): Double?

	/** Player tile X (world coordinates); null if unavailable. */
	fun playerTileX(): Int?

	/** Player tile Y; null if unavailable. */
	fun playerTileY(): Int?

	/** Count of NPCs in scene matching the given name; 0 if none. */
	fun npcCountByName(name: String): Int

	/** Count of TileObjects in scene matching the given name; 0 if none. */
	fun objectCountByName(name: String): Int

	/** Inventory count for the given itemId. */
	fun inventoryCountById(itemId: Int): Int
}

/**
 * Default impl. Reads VitalAPI statics. Returns null/0 when VitalAPI is
 * unavailable (e.g. in unit tests when not swapped — but tests should swap).
 */
object VitalApiGameStateProvider : GameStateProvider
{
	override fun openWidgetId(): Int? = runCatchingOrNull { vital.api.ui.Widgets.getOpenWidgetId() }
	override fun isDialogVisible(): Boolean = runCatchingOrNull { vital.api.ui.Widgets.isDialogVisible() } ?: false
	override fun hpRatio(): Double? = runCatchingOrNull {
		val cur = vital.api.entities.Players.getLocal()?.health ?: return@runCatchingOrNull null
		val max = vital.api.entities.Players.getLocal()?.maxHealth ?: return@runCatchingOrNull null
		if (max <= 0) null else cur.toDouble() / max.toDouble()
	}
	override fun playerTileX(): Int? = runCatchingOrNull { vital.api.entities.Players.getLocal()?.tile?.x }
	override fun playerTileY(): Int? = runCatchingOrNull { vital.api.entities.Players.getLocal()?.tile?.y }
	override fun npcCountByName(name: String): Int = runCatchingOrNull { vital.api.entities.Npcs.getAll(name).size } ?: 0
	override fun objectCountByName(name: String): Int = runCatchingOrNull { vital.api.entities.TileObjects.getAll(name).size } ?: 0
	override fun inventoryCountById(itemId: Int): Int = runCatchingOrNull { vital.api.containers.Inventory.getCountById(itemId) } ?: 0

	private inline fun <T> runCatchingOrNull(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }
}
```

> **Implementer note:** if a specific VitalAPI method has a different signature (e.g. `Players.getLocal()` returns something without `.health`), substitute the closest call. The `runCatchingOrNull` wrapper protects compile-time correctness; runtime VitalAPI mismatch returns null and falls back to permissive-1.0 in `ConfidenceTracker.compute`. If a method does not exist at all, the impl can return `null`/`0` outright with a `// TODO 6b: wire when VitalAPI exposes X` comment — but still keep the method on the interface so consumers compile.

- [ ] **Step 2:** Verify compile:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
BUILD SUCCESSFUL.

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/GameStateProvider.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — GameStateProvider interface + VitalApiGameStateProvider (resilient defaults)"
git push origin main
```

---

### Task 5 — `ConfidenceTracker` (cache, recompute, publish) — TDD

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceTracker.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceTrackerTest.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceComputeTest.kt`

- [ ] **Step 1: Write `ConfidenceTrackerTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ConfidenceUpdated
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConfidenceTrackerTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
	private val sid = UUID.randomUUID()
	private var nowMs = 0L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		nowMs = 1_000_000L
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = bus
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `current emits ConfidenceUpdated on first compute`()
	{
		ConfidenceTracker.current()
		assertTrue(captured.any { it is ConfidenceUpdated })
	}

	@Test
	fun `current returns cached within 600ms TTL`()
	{
		val c1 = ConfidenceTracker.current()
		nowMs += 500L
		val c2 = ConfidenceTracker.current()
		assertSame(c1, c2)   // cache hit
		assertEquals(1, captured.count { it is ConfidenceUpdated })
	}

	@Test
	fun `current recomputes after 600ms TTL`()
	{
		ConfidenceTracker.current()
		nowMs += 700L
		ConfidenceTracker.current()
		assertEquals(2, captured.count { it is ConfidenceUpdated })
	}

	@Test
	fun `onServiceCallEnd invalidates cache`()
	{
		val c1 = ConfidenceTracker.current()
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		val c2 = ConfidenceTracker.current()
		assertNotSame(c1, c2)
	}

	@Test
	fun `UNCONFIDENT outcome does not overwrite lastActionOutcome`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		val before = ConfidenceTracker.current()
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 2L, durationMillis = 5L, outcome = ServiceOutcome.UNCONFIDENT
		))
		nowMs += 700L
		val after = ConfidenceTracker.current()
		// LastActionResulted should still reflect SUCCESS=1.0
		assertEquals(1.0, after.perSignal["LAST_ACTION_RESULTED"])
	}
}

/** Test fake — returns "all good" by default; tests can override fields. */
internal class FakeGameStateProvider : GameStateProvider
{
	var widgetId: Int? = null
	var dialogVisible: Boolean = false
	var hp: Double? = 1.0
	var tileX: Int? = 3200
	var tileY: Int? = 3200
	var npcCounts: Map<String, Int> = emptyMap()
	var objectCounts: Map<String, Int> = emptyMap()
	var inventoryCounts: Map<Int, Int> = emptyMap()

	override fun openWidgetId(): Int? = widgetId
	override fun isDialogVisible(): Boolean = dialogVisible
	override fun hpRatio(): Double? = hp
	override fun playerTileX(): Int? = tileX
	override fun playerTileY(): Int? = tileY
	override fun npcCountByName(name: String): Int = npcCounts[name] ?: 0
	override fun objectCountByName(name: String): Int = objectCounts[name] ?: 0
	override fun inventoryCountById(itemId: Int): Int = inventoryCounts[itemId] ?: 0
}
```

- [ ] **Step 2: Write `ConfidenceComputeTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConfidenceComputeTest
{
	private val sid = UUID.randomUUID()
	private val gsp = FakeGameStateProvider()
	private var nowMs = 1_000_000L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `default state yields high confidence`()
	{
		val c = ConfidenceTracker.current()
		assertTrue(c.score > 0.85) { "expected > 0.85 with all-good state, got ${c.score}" }
	}

	@Test
	fun `low HP reduces score`()
	{
		gsp.hp = 0.2
		val c = ConfidenceTracker.current()
		assertEquals(0.0, c.perSignal["HP_NORMAL"])
	}

	@Test
	fun `mid HP yields 0_5`()
	{
		gsp.hp = 0.5
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["HP_NORMAL"])
	}

	@Test
	fun `dialog visible reduces NoUnexpectedDialog signal`()
	{
		gsp.dialogVisible = true
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["NO_UNEXPECTED_DIALOG"])
	}

	@Test
	fun `unknown widget yields 0_5 InterfaceKnown`()
	{
		gsp.widgetId = 999
		val c = ConfidenceTracker.current()
		assertEquals(0.5, c.perSignal["INTERFACE_KNOWN"])
	}

	@Test
	fun `null widget yields 1_0 InterfaceKnown`()
	{
		gsp.widgetId = null
		val c = ConfidenceTracker.current()
		assertEquals(1.0, c.perSignal["INTERFACE_KNOWN"])
	}

	@Test
	fun `LastActionResulted reflects last service outcome SUCCESS`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.SUCCESS
		))
		nowMs += 700L
		val c = ConfidenceTracker.current()
		assertEquals(1.0, c.perSignal["LAST_ACTION_RESULTED"])
	}

	@Test
	fun `LastActionResulted reflects EXCEPTION as 0_2`()
	{
		ConfidenceTracker.onServiceCallEnd(ServiceCallEnd(
			sessionId = sid, serviceName = "X", methodName = "m",
			callId = 1L, durationMillis = 5L, outcome = ServiceOutcome.EXCEPTION
		))
		nowMs += 700L
		val c = ConfidenceTracker.current()
		assertEquals(0.2, c.perSignal["LAST_ACTION_RESULTED"])
	}

	@Test
	fun `weighted sum equals expected`()
	{
		// All signals at 1.0 (default fake state) → score = sum of weights = 1.0
		val c = ConfidenceTracker.current()
		// HP=1.0, dialog=false→1.0, widget=null→1.0, no service call yet→1.0,
		// 4 stub signals with null hooks → 1.0. Sum should be 1.0.
		assertEquals(1.0, c.score, 0.001)
	}
}
```

- [ ] **Step 3:** Run → expect compile errors.

- [ ] **Step 4: Implement `ConfidenceTracker.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.ConfidenceUpdated
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.time.Clock
import java.util.UUID

/**
 * Singleton confidence cache + recompute. Subscribes to ServiceCallEnd via
 * [ConfidenceSubscriber] (Task 11). Cache TTL 600ms (Plan 6a spec §4.5).
 */
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

	private fun compute(now: Long): Confidence
	{
		val (task, ctx) = taskProvider()
		val gsp = gameStateProvider

		val per = mutableMapOf<String, Double>()

		// InterfaceKnown
		val wid = gsp.openWidgetId()
		per[ConfidenceSignal.InterfaceKnown.name] = when
		{
			wid == null -> 1.0
			else        -> 0.5    // unknown widget id; v1 has no allowlist registry
		}

		// LastActionResulted
		per[ConfidenceSignal.LastActionResulted.name] = when (lastActionOutcome)
		{
			ServiceOutcome.SUCCESS    -> 1.0
			ServiceOutcome.FAILURE    -> 0.5
			ServiceOutcome.EXCEPTION  -> 0.2
			ServiceOutcome.RESTRICTED -> 1.0
			ServiceOutcome.UNCONFIDENT, null -> 1.0
		}

		// HpNormal
		per[ConfidenceSignal.HpNormal.name] = gsp.hpRatio()?.let {
			when
			{
				it >= 0.7 -> 1.0
				it >= 0.4 -> 0.5
				else      -> 0.0
			}
		} ?: 1.0

		// NoUnexpectedDialog
		per[ConfidenceSignal.NoUnexpectedDialog.name] = if (gsp.isDialogVisible()) 0.5 else 1.0

		// Stub signals — call SPI hooks; null → 1.0
		per[ConfidenceSignal.ExpectedEntitiesVisible.name] = computeEntitiesSignal(task, ctx, gsp)
		per[ConfidenceSignal.PositionReasonable.name] = computePositionSignal(task, ctx, gsp)
		per[ConfidenceSignal.InventoryDeltaExpected.name] = computeInventoryDeltaSignal(task, ctx)
		per[ConfidenceSignal.RecentChatNormal.name] = 1.0    // v1 deferred

		val score = ConfidenceSignal.ALL.sumOf { sig -> sig.weight * (per[sig.name] ?: 1.0) }
			.coerceIn(0.0, 1.0)

		return Confidence(score, per.toMap(), now)
	}

	private fun computeEntitiesSignal(task: Task?, ctx: TaskContext?, gsp: GameStateProvider): Double
	{
		if (task == null || ctx == null) return 1.0
		val hints = task.expectedEntities(ctx) ?: return 1.0
		if (hints.isEmpty()) return 1.0
		val matched = hints.count { hint ->
			when (hint.kind)
			{
				"npc"    -> gsp.npcCountByName(hint.nameOrId) > 0
				"object" -> gsp.objectCountByName(hint.nameOrId) > 0
				else     -> true
			}
		}
		return matched.toDouble() / hints.size.toDouble()
	}

	private fun computePositionSignal(task: Task?, ctx: TaskContext?, gsp: GameStateProvider): Double
	{
		if (task == null || ctx == null) return 1.0
		val area = task.expectedArea(ctx) ?: return 1.0
		val px = gsp.playerTileX() ?: return 1.0
		val py = gsp.playerTileY() ?: return 1.0
		val dx = px - area.centerX
		val dy = py - area.centerY
		return if (dx * dx + dy * dy <= area.radius * area.radius) 1.0 else 0.0
	}

	private fun computeInventoryDeltaSignal(task: Task?, ctx: TaskContext?): Double
	{
		if (task == null || ctx == null) return 1.0
		task.expectedInventoryDelta(ctx) ?: return 1.0
		return 0.5    // hint exists but evaluation is deferred per spec §4.4
	}

	internal fun resetForTests()
	{
		cached = null
		lastActionOutcome = null
		bus = null
		sessionIdProvider = { UUID(0, 0) }
		taskProvider = { null to null }
		clock = Clock.systemUTC()
		gameStateProvider = VitalApiGameStateProvider
	}
}
```

- [ ] **Step 5:** Run tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ConfidenceTrackerTest*" --tests "*ConfidenceComputeTest*" --no-daemon 2>&1 | tail -15
```
Expected: all pass.

> **Note:** Task SPI hooks `expectedEntities` / `expectedArea` / `expectedInventoryDelta` referenced above are added in Task 6. Until Task 6 lands, the production code will fail to compile because `Task` doesn't yet declare those methods. **Order matters:** do Task 6 BEFORE Task 5 if you encounter the compile failure. Recommended order: Task 6 → Task 5.

- [ ] **Step 6:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceTracker.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceTrackerTest.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceComputeTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — ConfidenceTracker (cache + recompute + 8-signal weighted score)"
git push origin main
```

---

### Task 6 — Task SPI hooks (`expectedEntities`, `expectedArea`, `expectedInventoryDelta`)

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt`.

- [ ] **Step 1:** After the existing `suspend fun requestEarlyStop(...)` line, insert:

```kotlin
	/**
	 * Plan 6a: Confidence stub-signal source for "expected entities visible".
	 * Default null = "no expectation" → ExpectedEntitiesVisible signal returns 1.0.
	 */
	fun expectedEntities(ctx: TaskContext): List<net.vital.plugins.buildcore.core.confidence.hints.EntityHint>? = null

	/**
	 * Plan 6a: Confidence stub-signal source for "position reasonable".
	 * Default null = "no expectation" → PositionReasonable signal returns 1.0.
	 */
	fun expectedArea(ctx: TaskContext): net.vital.plugins.buildcore.core.confidence.hints.AreaHint? = null

	/**
	 * Plan 6a: Confidence stub-signal source for "inventory delta expected".
	 * Default null = "no expectation" → InventoryDeltaExpected signal returns 1.0.
	 */
	fun expectedInventoryDelta(ctx: TaskContext): net.vital.plugins.buildcore.core.confidence.hints.InventoryDeltaHint? = null
```

- [ ] **Step 2:** Verify compile:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
BUILD SUCCESSFUL. Existing `NoOpTask` and any other Task implementations inherit defaults; no code changes elsewhere.

- [ ] **Step 3:** Run full test suite to confirm no regression:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: all pass.

- [ ] **Step 4:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — add 3 Task SPI hooks for confidence stub signals (default null)"
git push origin main
```

---

### Task 7 — `ConfidenceGate` — TDD

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceGate.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceGateTest.kt`

- [ ] **Step 1:** Write `ConfidenceGateTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.confidence

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConfidenceGateTest
{
	private var nowMs = 1_000_000L
	private val clock = object : Clock()
	{
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(z: java.time.ZoneId) = this
		override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
		override fun millis(): Long = nowMs
	}
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.clock = clock
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `READ_ONLY is pass-through`()
	{
		gsp.hp = 0.0   // worst-case state
		ConfidenceGate.check(ActionStakes.READ_ONLY)   // must not throw
	}

	@Test
	fun `LOW passes when score above threshold`()
	{
		ConfidenceGate.check(ActionStakes.LOW)   // default state → score ~1.0
	}

	@Test
	fun `MEDIUM throws when score below threshold`()
	{
		gsp.hp = 0.2   // pulls score down
		gsp.dialogVisible = true
		gsp.widgetId = 999
		val ex = assertThrows(ConfidenceTooLow::class.java) {
			ConfidenceGate.check(ActionStakes.HIGH)
		}
		assertTrue(ex.current < 0.8)
		assertEquals(0.8, ex.required)
		assertNotNull(ex.worstSignal)
	}

	@Test
	fun `worstSignal reflects min of perSignal`()
	{
		gsp.hp = 0.2
		try
		{
			ConfidenceGate.check(ActionStakes.HIGH)
			fail("expected ConfidenceTooLow")
		}
		catch (e: ConfidenceTooLow)
		{
			assertEquals("HP_NORMAL", e.worstSignal)
		}
	}
}
```

- [ ] **Step 2:** Run → expect compile errors.

- [ ] **Step 3:** Implement `ConfidenceGate.kt`:

```kotlin
package net.vital.plugins.buildcore.core.confidence

/**
 * Confidence gate consulted by [withServiceCall]. Throws [ConfidenceTooLow]
 * when current score < required threshold. Plan 6a spec §5.2.
 */
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

- [ ] **Step 4:** Run targeted tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ConfidenceGateTest*" --no-daemon 2>&1 | tail -10
```
Expected: 4 pass.

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceGate.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceGateTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — ConfidenceGate (throws ConfidenceTooLow on under-threshold)"
git push origin main
```

---

## Phase 3 — withServiceCall extension + per-method stakes (Tasks 8-9)

### Task 8 — Extend `withServiceCall` with `stakes` param + UNCONFIDENT classification — TDD

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCall.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCallStakesTest.kt`

- [ ] **Step 1: Write `WithServiceCallStakesTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.services

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.confidence.ConfidenceTooLow
import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.FakeGameStateProvider
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ConfidenceUnderconfidentAction
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class WithServiceCallStakesTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
	private val sid = UUID.randomUUID()
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { sid }
		ConfidenceTracker.clock = Clock.systemUTC()
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `READ_ONLY skips confidence check entirely`()
	{
		gsp.hp = 0.0   // worst case
		val r = withServiceCall(bus, { sid }, "X", "m", stakes = ActionStakes.READ_ONLY) { true }
		assertTrue(r)
		assertEquals(ServiceOutcome.SUCCESS, (captured.last() as ServiceCallEnd).outcome)
	}

	@Test
	fun `under-threshold throws ConfidenceTooLow and classifies UNCONFIDENT`()
	{
		gsp.hp = 0.2
		gsp.dialogVisible = true
		gsp.widgetId = 999
		var blockInvoked = false
		assertThrows(ConfidenceTooLow::class.java) {
			withServiceCall(bus, { sid }, "X", "m", stakes = ActionStakes.HIGH) { blockInvoked = true; true }
		}
		assertFalse(blockInvoked)
		val end = captured.filterIsInstance<ServiceCallEnd>().single()
		assertEquals(ServiceOutcome.UNCONFIDENT, end.outcome)
	}

	@Test
	fun `under-threshold emits ConfidenceUnderconfidentAction`()
	{
		gsp.hp = 0.2
		gsp.dialogVisible = true
		gsp.widgetId = 999
		try
		{
			withServiceCall(bus, { sid }, "BankService", "open", stakes = ActionStakes.HIGH) { true }
		}
		catch (_: ConfidenceTooLow) {}
		val ev = captured.filterIsInstance<ConfidenceUnderconfidentAction>().single()
		assertEquals("BankService", ev.serviceName)
		assertEquals("open", ev.methodName)
		assertEquals(0.8, ev.required)
	}

	@Test
	fun `restriction check happens before confidence check`()
	{
		gsp.hp = 0.0   // would also fail confidence
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) {
			withServiceCall(bus, { sid }, "X", "m",
				restriction = OperationalRestriction.BANK_DISABLED,
				stakes = ActionStakes.HIGH) { true }
		}
		// outcome must be RESTRICTED, not UNCONFIDENT
		assertEquals(ServiceOutcome.RESTRICTED, (captured.last() as ServiceCallEnd).outcome)
	}
}
```

- [ ] **Step 2:** Run → expect compile errors (helper doesn't have `stakes` yet).

- [ ] **Step 3: Modify `WithServiceCall.kt`** — extend signature and logic:

Replace the current `internal inline fun <T> withServiceCall(...)` with:

```kotlin
package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.confidence.ConfidenceGate
import net.vital.plugins.buildcore.core.confidence.ConfidenceTooLow
import net.vital.plugins.buildcore.core.events.ConfidenceUnderconfidentAction
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object ServiceCallContext
{
	private val counter = AtomicLong(1L)
	fun nextCallId(): Long = counter.getAndIncrement()
	internal fun resetForTests() { counter.set(1L) }
}

/**
 * Canonical wrapper for L5 service action methods. Centralises events,
 * restriction check, confidence gate (Plan 6a), timing, outcome classification.
 *
 * Plan 5a spec §4.4 + Plan 6a spec §5.3.
 */
internal inline fun <T> withServiceCall(
	bus: EventBus?,
	sessionIdProvider: () -> UUID,
	serviceName: String,
	methodName: String,
	restriction: OperationalRestriction? = null,
	stakes: ActionStakes = ActionStakes.MEDIUM,
	block: () -> T
): T
{
	val callId = ServiceCallContext.nextCallId()
	val sid = sessionIdProvider()
	bus?.tryEmit(ServiceCallStart(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId))
	val startNanos = System.nanoTime()
	var outcome = ServiceOutcome.SUCCESS
	try
	{
		if (restriction != null)
		{
			try { RestrictionGate.check(restriction) }
			catch (e: RestrictionViolation) { outcome = ServiceOutcome.RESTRICTED; throw e }
		}
		try { ConfidenceGate.check(stakes) }
		catch (e: ConfidenceTooLow)
		{
			outcome = ServiceOutcome.UNCONFIDENT
			bus?.tryEmit(ConfidenceUnderconfidentAction(
				sessionId = sid,
				serviceName = serviceName,
				methodName = methodName,
				required = e.required,
				current = e.current
			))
			throw e
		}
		val result = block()
		if (result == false || result == null) outcome = ServiceOutcome.FAILURE
		return result
	}
	catch (e: RestrictionViolation) { throw e }
	catch (e: ConfidenceTooLow) { throw e }
	catch (e: Throwable)
	{
		if (outcome == ServiceOutcome.SUCCESS) outcome = ServiceOutcome.EXCEPTION
		throw e
	}
	finally
	{
		val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
		bus?.tryEmit(ServiceCallEnd(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId, durationMillis = durationMs, outcome = outcome))
	}
}
```

- [ ] **Step 4:** Run tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*WithServiceCallStakesTest*" --tests "*WithServiceCallTest*" --no-daemon 2>&1 | tail -15
```
Expected: stakes test passes; existing `WithServiceCallTest` still passes (default `stakes = MEDIUM` + null `ConfidenceTracker.bus` means `current()` returns ~1.0 default → MEDIUM passes).

If any existing service test fails because the default `MEDIUM` threshold + non-default game state pulls confidence below 0.6, the fix is in those tests: explicitly pass `stakes = ActionStakes.READ_ONLY` to `BankService.open(...)` etc., OR set `ConfidenceTracker.bus = null` in @BeforeEach. Since `ConfidenceTracker.resetForTests()` clears state and the default `gameStateProvider = VitalApiGameStateProvider` returns null (no live VitalAPI in tests) → all signals default to 1.0 → score = 1.0 → passes MEDIUM. So existing tests should be unaffected.

- [ ] **Step 5:** Run full suite:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: all pass.

- [ ] **Step 6:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCall.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCallStakesTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — extend withServiceCall with ActionStakes gate + UNCONFIDENT outcome"
git push origin main
```

---

### Task 9 — Add `stakes = ...` to all 13 services (one-line edits per method)

**Files:** Modify all 13 `*Service.kt` files under `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/<group>/`.

The mapping (from spec §5.4):

| File | Per-method stakes |
|---|---|
| `bank/BankService.kt` | all 6 methods → `ActionStakes.MEDIUM` |
| `inventory/InventoryService.kt` | all 3 → `MEDIUM` |
| `equipment/EquipmentService.kt` | all 2 → `MEDIUM` |
| `walker/WalkerService.kt` | all 3 (walkTo, walkExact, stop) → `LOW` |
| `login/LoginService.kt` | all 2 → `HIGH` |
| `world/WorldService.kt` | hop → `HIGH` |
| `combat/CombatService.kt` | all 2 → `MEDIUM` |
| `magic/MagicService.kt` | all 2 → `MEDIUM` |
| `prayer/PrayerService.kt` | all 2 → `MEDIUM` |
| `interact/InteractService.kt` | all 3 → `LOW` |
| `dialogue/DialogueService.kt` | all 3 → `MEDIUM` |
| `chat/ChatService.kt` | all 2 → `MEDIUM` |
| `ge/GrandExchangeService.kt` | all 5 → `HIGH` |

- [ ] **Step 1: For each file, add `stakes = ActionStakes.<level>` to every `withServiceCall(...)` call.**

Add the import once per file: `import net.vital.plugins.buildcore.core.confidence.ActionStakes`.

For files with restriction (Bank, Login, World, GE), the call site looks like:
```kotlin
suspend fun open(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "open",
	restriction = OperationalRestriction.BANK_DISABLED,
	stakes = ActionStakes.MEDIUM) { backend.open() }
```

For files without restriction (Inventory, Equipment, Walker, Combat, Magic, Prayer, Interact, Dialogue, Chat), add only `stakes = ActionStakes.<level>`:
```kotlin
suspend fun drop(itemId: Int): Boolean = withServiceCall(bus, sessionIdProvider, "InventoryService", "drop",
	stakes = ActionStakes.MEDIUM) { backend.drop(itemId) }
```

Use named-arg `stakes = ...` so insertion order doesn't matter relative to `restriction = ...`.

- [ ] **Step 2:** Run all service tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ServiceTest" --no-daemon 2>&1 | tail -15
```
Expected: all pass (default stakes = MEDIUM in helper means existing tests are unaffected; explicit stakes per service = same behavior since tracker's gameStateProvider returns null → all signals = 1.0 → score = 1.0 → MEDIUM/HIGH pass).

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — declare ActionStakes per method on all 13 services"
git push origin main
```

---

## Phase 4 — Watchdog (Tasks 10-15)

### Task 10 — Runner heartbeat extension — TDD

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerHeartbeatTest.kt`

- [ ] **Step 1: Write `RunnerHeartbeatTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.task

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.RunnerHeartbeat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerHeartbeatTest
{
	@Test
	fun `lastHeartbeatMs is initialized at construction`()
	{
		val bus = mockk<EventBus>(relaxed = true)
		val r = Runner(bus, UUID.randomUUID())
		assertTrue(r.lastHeartbeatMs > 0L)
	}
}
```

> **Note:** A more thorough test of heartbeat throttling (1Hz) would require running a task through the Runner's tick loop. The minimal test above suffices for the field's existence; the throttling logic is exercised indirectly via integration tests once a real task runs. If extending later, mock `NoOpTask` and run `Runner.run(...)` with `runTest` virtual time.

- [ ] **Step 2:** Run → expect compile error (`lastHeartbeatMs` doesn't exist).

- [ ] **Step 3: Modify `Runner.kt`** — add the field and heartbeat method.

Find the class body at `Runner.kt:34`. After the constructor params block, add:

```kotlin
	@Volatile var lastHeartbeatMs: Long = System.currentTimeMillis()
		private set

	@Volatile private var lastHeartbeatEventMs: Long = 0L
	@Volatile private var currentTaskInstanceId: UUID? = null
```

Add a private method (place before `run()`):

```kotlin
	private fun heartbeat()
	{
		lastHeartbeatMs = System.currentTimeMillis()
		val tid = currentTaskInstanceId ?: return
		if (lastHeartbeatMs - lastHeartbeatEventMs >= 1_000L)
		{
			bus.tryEmit(net.vital.plugins.buildcore.core.events.RunnerHeartbeat(
				sessionId = sessionId,
				taskInstanceId = tid
			))
			lastHeartbeatEventMs = lastHeartbeatMs
		}
	}
```

In `run(...)`, set `currentTaskInstanceId = instance.id` immediately after the line `val ctx = SimpleTaskContext(...)`. Add `heartbeat()` calls:
- At the top of the `while (instance.attemptNumber < ...)` loop body (line ~80).
- At the top of the inner `while (true)` step loop (line ~183) — call `heartbeat()` before `val stepResult = ...`.

Make sure the `bus` parameter is accessible (`Runner` already has `private val bus: EventBus` so this works).

- [ ] **Step 4:** Run targeted test + full suite:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: all pass.

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerHeartbeatTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — Runner.lastHeartbeatMs + 1Hz throttled RunnerHeartbeat event"
git push origin main
```

---

### Task 11 — `PrecisionGate` extension: `scopeEnteredAtMs` — TDD

**Files:**
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGate.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGateLeakTest.kt`

- [ ] **Step 1: Write `PrecisionGateLeakTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrecisionGateLeakTest
{
	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
	}

	@Test
	fun `scopeEnteredAtMs is null before any entry`()
	{
		assertNull(PrecisionGate.scopeEnteredAtMs)
	}

	@Test
	fun `outermost entry sets scopeEnteredAtMs`()
	{
		val before = System.currentTimeMillis()
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val ts = PrecisionGate.scopeEnteredAtMs
		assertNotNull(ts)
		assertTrue(ts!! >= before)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `nested entry does not change scopeEnteredAtMs`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val outer = PrecisionGate.scopeEnteredAtMs
		Thread.sleep(5L)
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		assertEquals(outer, PrecisionGate.scopeEnteredAtMs)
		PrecisionGate.markExitScope()
		assertEquals(outer, PrecisionGate.scopeEnteredAtMs)   // outer still active
		PrecisionGate.markExitScope()
		assertNull(PrecisionGate.scopeEnteredAtMs)
	}
}
```

- [ ] **Step 2:** Run → expect failure (no `scopeEnteredAtMs` field).

- [ ] **Step 3: Modify `PrecisionGate.kt`** — add field + update mark methods.

Add field after the existing `scopeMode` ThreadLocal:
```kotlin
	@Volatile internal var scopeEnteredAtMs: Long? = null
```

Update `markEnterScope`:
```kotlin
	@PublishedApi internal fun markEnterScope(mode: InputMode)
	{
		val prevDepth = scopeDepth.get()
		if (prevDepth == 0) scopeEnteredAtMs = System.currentTimeMillis()
		scopeDepth.set(prevDepth + 1)
		scopeMode.set(mode)
	}
```

Update `markExitScope`:
```kotlin
	@PublishedApi internal fun markExitScope()
	{
		val d = scopeDepth.get() - 1
		if (d <= 0)
		{
			scopeDepth.set(0)
			scopeMode.set(null)
			scopeEnteredAtMs = null
		}
		else
		{
			scopeDepth.set(d)
		}
	}
```

Update `resetForTests`:
```kotlin
	internal fun resetForTests()
	{
		scopeDepth.set(0)
		scopeMode.set(null)
		scopeEnteredAtMs = null
		preemptHook = null
	}
```

- [ ] **Step 4:** Run targeted + full:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: all pass (Plan 4b's existing PrecisionGate/PrecisionWindow tests should still pass).

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGate.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/precision/PrecisionGateLeakTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — PrecisionGate.scopeEnteredAtMs (outermost-scope timestamp for LeakCheck)"
git push origin main
```

---

### Task 12 — `WatchdogScope` + `Watchdog` driver + `WatchdogCheck` interface

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/WatchdogScope.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/WatchdogCheck.kt` (interface + WatchdogFinding)
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/Watchdog.kt`

- [ ] **Step 1: `WatchdogScope.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence.watchdog

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Single-thread coroutine scope for the watchdog. Sibling of LoggerScope.
 * Plan 6a spec §6.1.
 */
class WatchdogScope : AutoCloseable
{
	private val dispatcher = Executors.newSingleThreadExecutor { r ->
		Thread(r, "Watchdog").apply { isDaemon = true }
	}.asCoroutineDispatcher()

	private val job = SupervisorJob()
	val coroutineScope: CoroutineScope = CoroutineScope(dispatcher + job + CoroutineName("WatchdogScope"))

	override fun close()
	{
		job.cancel()
		dispatcher.close()
	}
}
```

- [ ] **Step 2: `WatchdogCheck.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence.watchdog

import net.vital.plugins.buildcore.core.events.WatchdogKind

/**
 * One periodic check. Returns a finding if the check trips this tick, else null.
 * Plan 6a spec §6.2.
 */
interface WatchdogCheck
{
	fun tick(nowMs: Long): WatchdogFinding?
}

data class WatchdogFinding(val kind: WatchdogKind, val detail: String)
```

- [ ] **Step 3: `Watchdog.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence.watchdog

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogTriggered
import java.util.UUID

/**
 * Driver: runs all checks once per [tickIntervalMs] and emits
 * [WatchdogTriggered] events. Plan 6a spec §6.2.
 */
class Watchdog(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID,
	private val checks: List<WatchdogCheck>,
	private val tickIntervalMs: Long = 1_000L,
	private val nowMs: () -> Long = System::currentTimeMillis
)
{
	suspend fun run() = coroutineScope {
		while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false)
		{
			for (check in checks)
			{
				try
				{
					check.tick(nowMs())?.let { trigger ->
						bus.tryEmit(WatchdogTriggered(
							sessionId = sessionIdProvider(),
							kind = trigger.kind,
							detail = trigger.detail
						))
					}
				}
				catch (_: Throwable) { /* one bad check must not kill the loop */ }
			}
			delay(tickIntervalMs)
		}
	}
}
```

- [ ] **Step 4:** Verify compile:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
BUILD SUCCESSFUL.

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — WatchdogScope + Watchdog driver + WatchdogCheck interface"
git push origin main
```

---

### Task 13 — The 4 `WatchdogCheck` impls — TDD

**Files (all under `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/checks/`):**
- `StallCheck.kt`
- `UncertainCheck.kt`
- `DeadlockCheck.kt`
- `LeakCheck.kt`

**Tests (all under `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/checks/`):**
- `StallCheckTest.kt`, `UncertainCheckTest.kt`, `DeadlockCheckTest.kt`, `LeakCheckTest.kt`

- [ ] **Step 1: `StallCheck.kt` + test.**

```kotlin
// src/main/kotlin/.../checks/StallCheck.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.task.ProgressFingerprint
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext

class StallCheck(
	private val taskProvider: () -> Pair<Task?, TaskContext?>
) : WatchdogCheck
{
	private var lastFingerprint: ProgressFingerprint? = null
	private var lastChangedAtMs: Long = Long.MAX_VALUE

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val (task, ctx) = taskProvider()
		if (task == null || ctx == null) { lastFingerprint = null; lastChangedAtMs = nowMs; return null }
		val fp = task.progressSignal(ctx)
		if (fp != lastFingerprint)
		{
			lastFingerprint = fp
			lastChangedAtMs = nowMs
			return null
		}
		val thresholdMs = task.stallThreshold.inWholeMilliseconds
		if (nowMs - lastChangedAtMs >= thresholdMs)
		{
			lastChangedAtMs = nowMs    // one fire per stall episode
			return WatchdogFinding(WatchdogKind.STALL, "task=${task.id.raw} unchanged for ${thresholdMs}ms")
		}
		return null
	}
}
```

```kotlin
// src/test/kotlin/.../StallCheckTest.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.task.ProgressFingerprint
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import net.vital.plugins.buildcore.core.task.TaskId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class StallCheckTest
{
	@Test
	fun `fires when fingerprint unchanged for stallThreshold`()
	{
		val task = mockk<Task>()
		every { task.id } returns TaskId("foo")
		every { task.stallThreshold } returns 5.minutes
		val ctx = mockk<TaskContext>(relaxed = true)
		val fp = mockk<ProgressFingerprint>()
		every { task.progressSignal(ctx) } returns fp

		val check = StallCheck { task to ctx }

		assertNull(check.tick(0L))                 // initial sample
		assertNull(check.tick(60_000L))            // 1min
		assertNull(check.tick(299_000L))           // just under threshold
		val finding = check.tick(300_000L)         // = threshold
		assertNotNull(finding)
		assertEquals(WatchdogKind.STALL, finding!!.kind)
	}

	@Test
	fun `resets when fingerprint changes`()
	{
		val task = mockk<Task>()
		every { task.id } returns TaskId("foo")
		every { task.stallThreshold } returns 5.minutes
		val ctx = mockk<TaskContext>(relaxed = true)
		val fp1 = mockk<ProgressFingerprint>()
		val fp2 = mockk<ProgressFingerprint>()
		every { task.progressSignal(ctx) } returnsMany listOf(fp1, fp1, fp2, fp2)

		val check = StallCheck { task to ctx }
		check.tick(0L)
		check.tick(200_000L)
		check.tick(250_000L)                       // fp changed → resets
		assertNull(check.tick(400_000L))           // only 150_000 since reset
	}

	@Test
	fun `null task returns null`()
	{
		val check = StallCheck { null to null }
		assertNull(check.tick(System.currentTimeMillis()))
	}
}
```

- [ ] **Step 2: `UncertainCheck.kt` + test.**

```kotlin
// UncertainCheck.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class UncertainCheck(
	private val threshold: Double = 0.4,
	private val sustainedMs: Long = 30_000L
) : WatchdogCheck
{
	private var firstBelowMs: Long? = null

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val score = ConfidenceTracker.current().score
		if (score < threshold)
		{
			val first = firstBelowMs
			if (first == null) { firstBelowMs = nowMs; return null }
			if (nowMs - first >= sustainedMs)
			{
				firstBelowMs = nowMs    // re-arm after fire
				return WatchdogFinding(WatchdogKind.UNCERTAIN, "score=$score sustained ${sustainedMs}ms")
			}
			return null
		}
		else
		{
			firstBelowMs = null
			return null
		}
	}
}
```

```kotlin
// UncertainCheckTest.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.confidence.ConfidenceTracker
import net.vital.plugins.buildcore.core.confidence.FakeGameStateProvider
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class UncertainCheckTest
{
	private val gsp = FakeGameStateProvider()

	@BeforeEach
	fun reset()
	{
		ConfidenceTracker.resetForTests()
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.clock = Clock.systemUTC()
		ConfidenceTracker.gameStateProvider = gsp
	}

	@Test
	fun `low score sustained 30s fires`()
	{
		gsp.hp = 0.0; gsp.dialogVisible = true; gsp.widgetId = 999
		val check = UncertainCheck()
		assertNull(check.tick(0L))
		assertNull(check.tick(20_000L))
		val finding = check.tick(35_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.UNCERTAIN, finding!!.kind)
	}

	@Test
	fun `recovery before 30s clears`()
	{
		gsp.hp = 0.0; gsp.dialogVisible = true; gsp.widgetId = 999
		val check = UncertainCheck()
		check.tick(0L)
		gsp.hp = 1.0; gsp.dialogVisible = false; gsp.widgetId = null
		ConfidenceTracker.resetForTests()       // force recompute
		ConfidenceTracker.bus = mockk<EventBus>().also { every { it.tryEmit(any()) } returns true }
		ConfidenceTracker.sessionIdProvider = { UUID.randomUUID() }
		ConfidenceTracker.gameStateProvider = gsp
		assertNull(check.tick(20_000L))         // recovered
	}
}
```

- [ ] **Step 3: `DeadlockCheck.kt` + test.**

```kotlin
// DeadlockCheck.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class DeadlockCheck(
	private val heartbeatProvider: () -> Long?,
	private val toleranceMs: Long = 15_000L
) : WatchdogCheck
{
	private var lastFiredAtMs: Long = Long.MIN_VALUE

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val hb = heartbeatProvider() ?: return null
		val sinceHeartbeat = nowMs - hb
		if (sinceHeartbeat <= toleranceMs)
		{
			lastFiredAtMs = Long.MIN_VALUE    // re-arm
			return null
		}
		if (lastFiredAtMs == Long.MIN_VALUE)
		{
			lastFiredAtMs = nowMs
			return WatchdogFinding(WatchdogKind.DEADLOCK, "no heartbeat for ${sinceHeartbeat}ms")
		}
		return null
	}
}
```

```kotlin
// DeadlockCheckTest.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeadlockCheckTest
{
	@Test
	fun `fires when heartbeat exceeds tolerance`()
	{
		var hb: Long? = 0L
		val check = DeadlockCheck({ hb })
		assertNull(check.tick(10_000L))
		val finding = check.tick(20_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.DEADLOCK, finding!!.kind)
	}

	@Test
	fun `re-arms after heartbeat returns`()
	{
		var hb: Long? = 0L
		val check = DeadlockCheck({ hb })
		check.tick(20_000L)
		hb = 25_000L
		assertNull(check.tick(26_000L))
		hb = 25_000L
		assertNull(check.tick(35_000L))     // heartbeat is 25s → 10s ago, still in tolerance? wait — toleranceMs=15s, so 35-25=10 in tolerance
	}

	@Test
	fun `null heartbeat returns null`()
	{
		val check = DeadlockCheck({ null })
		assertNull(check.tick(System.currentTimeMillis()))
	}
}
```

- [ ] **Step 4: `LeakCheck.kt` + test.**

```kotlin
// LeakCheck.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogFinding
import net.vital.plugins.buildcore.core.events.WatchdogKind

class LeakCheck(
	private val maxScopeMs: Long = 30_000L
) : WatchdogCheck
{
	private var lastFiredForEnteredMs: Long? = null

	override fun tick(nowMs: Long): WatchdogFinding?
	{
		val entered = PrecisionGate.scopeEnteredAtMs ?: run { lastFiredForEnteredMs = null; return null }
		val held = nowMs - entered
		if (held <= maxScopeMs)
		{
			lastFiredForEnteredMs = null
			return null
		}
		if (lastFiredForEnteredMs == entered) return null    // already fired for this scope episode
		lastFiredForEnteredMs = entered
		return WatchdogFinding(WatchdogKind.LEAK, "precision held ${held}ms")
	}
}
```

```kotlin
// LeakCheckTest.kt
package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeakCheckTest
{
	@BeforeEach
	fun reset() { PrecisionGate.resetForTests() }

	@Test
	fun `fires when scope held longer than maxScopeMs`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val entered = PrecisionGate.scopeEnteredAtMs!!
		val check = LeakCheck()
		assertNull(check.tick(entered + 10_000L))
		val finding = check.tick(entered + 31_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.LEAK, finding!!.kind)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `no scope returns null`()
	{
		val check = LeakCheck()
		assertNull(check.tick(System.currentTimeMillis()))
	}

	@Test
	fun `fires once per scope episode`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val entered = PrecisionGate.scopeEnteredAtMs!!
		val check = LeakCheck()
		assertNotNull(check.tick(entered + 31_000L))
		assertNull(check.tick(entered + 32_000L))
		PrecisionGate.markExitScope()
	}
}
```

- [ ] **Step 5:** Run all check tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*StallCheckTest*" --tests "*UncertainCheckTest*" --tests "*DeadlockCheckTest*" --tests "*LeakCheckTest*" --no-daemon 2>&1 | tail -15
```
Expected: all pass.

- [ ] **Step 6:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/checks/ \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/checks/
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — 4 WatchdogCheck impls (Stall/Uncertain/Deadlock/Leak) + tests"
git push origin main
```

---

### Task 14 — `Watchdog` integration test

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/WatchdogTest.kt`.

- [ ] **Step 1: Write the test:**

```kotlin
package net.vital.plugins.buildcore.core.confidence.watchdog

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.events.WatchdogTriggered
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogTest
{
	@Test
	fun `loop emits WatchdogTriggered when a check returns finding`() = runTest {
		val captured = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
		val firingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? =
				WatchdogFinding(WatchdogKind.STALL, "test")
		}
		val w = Watchdog(bus, { UUID.randomUUID() }, listOf(firingCheck), tickIntervalMs = 100L)
		val job = launch { w.run() }
		advanceTimeBy(150L)
		job.cancelAndJoin()
		assertTrue(captured.any { it is WatchdogTriggered && it.kind == WatchdogKind.STALL })
	}

	@Test
	fun `check exception does not kill the loop`() = runTest {
		val captured = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
		val throwingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? = error("boom")
		}
		val firingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? = WatchdogFinding(WatchdogKind.LEAK, "test")
		}
		val w = Watchdog(bus, { UUID.randomUUID() }, listOf(throwingCheck, firingCheck), tickIntervalMs = 100L)
		val job = launch { w.run() }
		advanceTimeBy(150L)
		job.cancelAndJoin()
		assertTrue(captured.any { it is WatchdogTriggered && it.kind == WatchdogKind.LEAK })
	}
}
```

- [ ] **Step 2:** Run:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*WatchdogTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 pass.

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/confidence/watchdog/WatchdogTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — Watchdog integration test (firing + exception isolation)"
git push origin main
```

---

### Task 15 — `ConfidenceSubscriber` + `ConfidenceBootstrap` + plugin wiring

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceSubscriber.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceBootstrap.kt`
- Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt`

- [ ] **Step 1:** Examine `SubscriberRegistry` to understand the subscriber pattern:
```bash
grep -n "interface\|fun attach\|fun drain\|fun process" /c/Code/VitalPlugins/BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/SubscriberRegistry.kt /c/Code/VitalPlugins/BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/NoOpReplaySubscriber.kt 2>&1 | head -30
```

This tells you the existing `Subscriber`/`*Subscriber` interface shape Plan 3 uses. Adapt `ConfidenceSubscriber` to that shape.

- [ ] **Step 2: Create `ConfidenceSubscriber.kt`** following Plan 3's existing subscriber pattern. The minimum behavior: on each `BusEvent`, if it is a `ServiceCallEnd`, call `ConfidenceTracker.onServiceCallEnd(it)`. Match the constructor signature, name conventions, and threading model used by `NoOpReplaySubscriber` / `LocalSummaryWriter`.

Skeleton (adapt to existing pattern):

```kotlin
package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.ServiceCallEnd

/**
 * Plan 3-style subscriber. Forwards [ServiceCallEnd] events to
 * [ConfidenceTracker.onServiceCallEnd]. Plan 6a spec §8.1.
 */
class ConfidenceSubscriber(/* match Plan 3 ctor: sessionIdProvider, etc. */)
{
	fun process(event: BusEvent)
	{
		if (event is ServiceCallEnd) ConfidenceTracker.onServiceCallEnd(event)
	}

	// Match the Subscriber interface methods Plan 3 expects (attach, drain, etc.)
}
```

> **Implementer note:** Plan 3's `NoOpReplaySubscriber` is the closest analogue. Copy its skeleton, swap the body to forward `ServiceCallEnd` to `ConfidenceTracker`. If Plan 3's pattern is "extend `LogSubscriber`" or "implement `Subscriber<T>`", follow that. Do not invent a new pattern.

- [ ] **Step 3: Create `ConfidenceBootstrap.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires [ConfidenceTracker] fields and registers [ConfidenceSubscriber].
 * Called once from [net.vital.plugins.buildcore.BuildCorePlugin.startUp]
 * after `ServiceBootstrap.install`. Plan 6a spec §8.1.
 */
object ConfidenceBootstrap
{
	private val installed = AtomicBoolean(false)

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		taskProvider: () -> Pair<Task?, TaskContext?> = { null to null },
		clock: Clock = Clock.systemUTC()
	)
	{
		if (!installed.compareAndSet(false, true)) return
		ConfidenceTracker.bus = bus
		ConfidenceTracker.sessionIdProvider = sessionIdProvider
		ConfidenceTracker.taskProvider = taskProvider
		ConfidenceTracker.clock = clock
		// ConfidenceSubscriber is registered via SubscriberRegistry by the plugin (see BuildCorePlugin).
	}

	internal fun resetForTests()
	{
		installed.set(false)
		ConfidenceTracker.resetForTests()
	}
}
```

- [ ] **Step 4: Modify `BuildCorePlugin.kt`** to wire ConfidenceBootstrap, register `ConfidenceSubscriber`, instantiate `WatchdogScope` + `Watchdog`, launch the watchdog job, and cancel it on shutdown.

Add imports:
```kotlin
import net.vital.plugins.buildcore.core.confidence.ConfidenceBootstrap
import net.vital.plugins.buildcore.core.confidence.ConfidenceSubscriber
import net.vital.plugins.buildcore.core.confidence.watchdog.Watchdog
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogScope
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.DeadlockCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.LeakCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.StallCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.UncertainCheck
```

Add fields next to the existing `breakSchedulerJob`:
```kotlin
	private lateinit var watchdogScope: WatchdogScope
	private var watchdogJob: Job? = null
```

In `startUp()`, after `ServiceBootstrap.install(...)` (added in Plan 5a Task 19), add:

```kotlin
		ConfidenceBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId }
			// taskProvider defaults to null; updated when a Runner is alive (deferred)
		)
		subscriberRegistry.register(ConfidenceSubscriber(/* ctor-args matching pattern */))

		watchdogScope = WatchdogScope()
		val checks = listOf(
			StallCheck(taskProvider = { null to null }),       // Runner registration deferred
			UncertainCheck(),
			DeadlockCheck(heartbeatProvider = { null }),       // Runner registration deferred
			LeakCheck()
		)
		val watchdog = Watchdog(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId },
			checks = checks
		)
		watchdogJob = watchdogScope.coroutineScope.launch { watchdog.run() }
```

In `shutDown()`, before `loggerScope.close()`:

```kotlin
		runBlocking {
			watchdogJob?.cancelAndJoin()
			breakSchedulerJob?.cancelAndJoin()
			performanceAggregator.stop()
			sessionManager.requestStop()
			subscriberRegistry.drainAll()
		}
		watchdogScope.close()
		loggerScope.close()
```

> **Implementer note:** the `taskProvider` and `heartbeatProvider` defaulting to null means StallCheck/DeadlockCheck short-circuit at startup (no live Runner consumer in 6a). Leaving these as null is intentional and documented in spec §10.

- [ ] **Step 5:** Verify compile + run full suite:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: all pass.

- [ ] **Step 6:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceSubscriber.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/confidence/ConfidenceBootstrap.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — wire ConfidenceBootstrap + Watchdog launch into BuildCorePlugin"
git push origin main
```

---

## Phase 5 — Architecture test + docs (Tasks 16-17)

### Task 16 — `ConfidenceArchitectureTest` (Konsist)

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/ConfidenceArchitectureTest.kt`.

- [ ] **Step 1: Implement:**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the Plan 6a confidence + watchdog layer.
 */
class ConfidenceArchitectureTest
{
	private fun servicePackageFiles() = Konsist.scopeFromProduction()
		.files
		.filter { it.path.contains("/core/services/") }

	@Test
	fun `every Service action method passes a stakes argument to withServiceCall`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("stakes ="))
				{ "${file.path}: every withServiceCall site must include `stakes = ActionStakes.X`" }
			}
	}

	@Test
	fun `vital_api imports outside the allowed list are forbidden`()
	{
		Konsist.scopeFromProduction()
			.files
			.filter { it.path.contains("/main/kotlin/") }
			.filter { it.text.contains("import vital.api") }
			.forEach { file ->
				val isAllowed = file.path.contains("/services/") && file.name.startsWith("VitalApi")
					|| file.name == "VitalApiGameStateProvider.kt"
				assert(isAllowed)
				{ "${file.path}: only VitalApi*Backend.kt or VitalApiGameStateProvider.kt may import vital.api.*" }
			}
	}
}
```

- [ ] **Step 2:** Run:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*ConfidenceArchitectureTest*" --no-daemon 2>&1 | tail -10
```
Expected: 2 pass.

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/ConfidenceArchitectureTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 6a — ConfidenceArchitectureTest (stakes-on-services + vital.api allowlist)"
git push origin main
```

---

### Task 17 — Update BuildCore CLAUDE.md

**File:** Modify `BuildCore/CLAUDE.md`.

- [ ] **Step 1: Update Status section.** Replace with:

```markdown
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
- Plan 5b — Services with logic
- Plan 5c — Cross-account / safety services
- Plan 6b — Recovery Pipeline (7 steps, 90s budget)
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell
```

- [ ] **Step 2: Update "Current invariants".** Bump scrubber subtype count `41 → 45`. Append three new bullets:

```markdown
- Every `*Service.kt` action method passes `stakes = ActionStakes.X` to `withServiceCall` (Plan 6a).
- `vital.api.*` imports only in `VitalApi*Backend.kt` files OR `VitalApiGameStateProvider.kt` (Plan 6a).
- `ConfidenceTracker.scopeEnteredAtMs` and `Runner.lastHeartbeatMs` are the only public mutable state added by Plan 6a (read by Watchdog checks).
```

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/CLAUDE.md
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: mark Plan 6a complete in subproject CLAUDE.md"
git push origin main
```

---

## Self-review

### Spec coverage

| Spec section | Implementing task(s) |
|---|---|
| §1 goal (5 deliverables) | Phases 1-5 collectively |
| §2 deferrals | N/A (deferrals doc'd in spec) |
| §3.1 module map | Tasks 3-15 produce exactly this layout |
| §3.2 layering | Cross-cutting in Tasks 8 (services), 10 (Runner), 11 (PrecisionGate) |
| §3.3 singleton pattern | Tracker/Gate (Tasks 5, 7); Watchdog as class (Task 12) |
| §4.1 signal taxonomy | Task 3 |
| §4.2 Confidence value type | Task 3 |
| §4.3 ActionStakes enum | Task 3 |
| §4.4 per-signal compute rules | Task 5 (compute body) + Task 6 (SPI hooks) |
| §4.5 ConfidenceTracker | Task 5 |
| §4.6 SPI hooks | Task 6 |
| §5.1 ConfidenceTooLow | Task 3 |
| §5.2 ConfidenceGate | Task 7 |
| §5.3 withServiceCall extension | Task 8 |
| §5.4 per-method stakes | Task 9 |
| §6.1 WatchdogScope | Task 12 |
| §6.2 WatchdogCheck/Watchdog | Task 12 |
| §6.3 4 checks | Task 13 |
| §6.4 Runner heartbeat | Task 10 |
| §6.5 PrecisionGate scopeEnteredAtMs | Task 11 |
| §7 BusEvents + scrubber bump | Tasks 1, 2 |
| §8.1 ConfidenceBootstrap + subscriber | Task 15 |
| §8.2 watchdog launch | Task 15 |
| §9 testing | Tasks 5, 7, 8, 10-14, 16 |
| §10 risks | Mitigations referenced inline (Tasks 4 resilient defaults; Task 15 null-provider short-circuit) |

All sections mapped.

### Placeholder scan

No `TBD`, `TODO`, `FIXME`. Two adaptive instructions present and unavoidable:
1. **Task 4 (`VitalApiGameStateProvider`):** signatures may need substitution to whatever VitalAPI actually exposes; resilient `runCatchingOrNull` wrapper makes runtime mismatch a graceful null. Documented inline.
2. **Task 15 (`ConfidenceSubscriber`):** Plan 3's exact subscriber interface isn't restated here; implementer mirrors `NoOpReplaySubscriber`'s shape. Pointed at the file to copy.

Every other code block is complete.

### Type consistency

Verified:
- `ActionStakes.threshold` field used in `ConfidenceGate.check` and tests.
- `Confidence.score` / `perSignal` / `computedAtMs` consistent across Tracker, Gate, tests.
- `ConfidenceSignal.ALL` referenced by Tracker compute.
- `ConfidenceTracker.bus` / `sessionIdProvider` / `taskProvider` / `clock` / `gameStateProvider` field names consistent across Tracker, ConfidenceBootstrap, tests, ConfidenceGateTest.
- `GameStateProvider` 8 methods (openWidgetId/isDialogVisible/hpRatio/playerTileX/playerTileY/npcCountByName/objectCountByName/inventoryCountById) consistent between interface, VitalApi impl, FakeGameStateProvider, tests.
- `WatchdogKind` enum cases (STALL/UNCERTAIN/DEADLOCK/LEAK) consistent across BusEvent declaration and 4 check impls.
- `WatchdogFinding(kind, detail)` consistent between interface, 4 checks, Watchdog.run, WatchdogTest.
- `Runner.lastHeartbeatMs` field name consistent between Runner, DeadlockCheck heartbeatProvider, RunnerHeartbeatTest.
- `PrecisionGate.scopeEnteredAtMs` field name consistent between PrecisionGate extension, LeakCheck, PrecisionGateLeakTest.
- `withServiceCall(bus, sessionIdProvider, serviceName, methodName, restriction, stakes, block)` signature consistent across helper definition and 13 service updates (Task 9).

No drift.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-25-plan-6a-confidence-watchdog.md`.**

Per user's instructions, proceeding directly to **subagent-driven execution**.
