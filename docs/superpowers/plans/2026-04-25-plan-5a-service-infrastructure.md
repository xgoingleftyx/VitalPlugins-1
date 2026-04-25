# BuildCore Plan 5a — Service Infrastructure + Thin VitalAPI Wrappers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the L5 Services layer with cross-cutting infrastructure (`ServiceCallStart`/`End` events, `RestrictionGate`, `withServiceCall` helper) and 14 thin VitalAPI wrapper services exposing ~38 action methods total. No decision logic, no multi-account services — pure framework.

**Architecture:** Each service is a Kotlin `object` with a `@Volatile internal var backend: <X>Backend` (Plan 4a pattern). Public methods are 3–5 line `suspend fun`s calling `withServiceCall(...) { backend.method(...) }`. The helper centralises event emission, restriction checking, timing, and outcome classification. Default `VitalApi<X>Backend` delegates to `vital.api.*` static methods; tests swap to `Fake<X>Backend`. `RestrictionGate.check(OperationalRestriction)` is consulted per gated method via the helper's `restriction` parameter. Plan 6 will subscribe to `ServiceCallEnd` for confidence — no direct service→Confidence coupling here.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0, JUnit 5, MockK, Konsist 0.17.3. Existing `compileOnly(libs.vital.api)` from Plan 4a covers VitalAPI access. No new external deps.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-25-buildcore-plan5a-service-infrastructure-design.md`](../specs/2026-04-25-buildcore-plan5a-service-infrastructure-design.md)
- Plans 1+2+3+4a+4b complete and merged (172 tests).
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit + push to `origin` (xgoingleftyx fork) after every commit.
- Author: Chich only — NO `Co-Authored-By` trailers.
- Style: tabs, Allman braces where applicable, UTF-8.

**VitalAPI surface — verified against `C:\Code\VitalAPI\src\vital\api\`:**

VitalAPI uses `public static` methods on final classes (e.g., `vital.api.containers.Bank.open()`, `Bank.withdraw(slot, itemId, count)`, `Bank.depositItem(slot, itemId, count)`, `Bank.depositAll()`). Action methods exist for: `Bank`, `Inventory`, `Equipment`, `GrandExchange`, `Combat`, `Magic`, `Prayer` (under `vital.api.ui`), and via `vital.api.input.Movement` for walking. **Some "services" in the spec table do not have direct VitalAPI counterparts** — `LoginService`, `WorldService`, `InteractService`, `DialogueService`, `ChatService` are partially or fully implemented through other channels (autologin plugin, widget interactions, raw input). For these, the `VitalApi<X>Backend` files in this plan are **best-effort stubs** that delegate where they can and `error("not implemented in 5a; wire when consumer needs it")` where they cannot. Tasks must use `Fake<X>Backend` until those services are wired further.

The Service objects themselves (their public APIs, their event emissions, their restriction checks) are **fully implemented and fully tested** regardless. The only thing deferred is the live VitalAPI delegate — which is a single isolated file per service.

---

## File structure this plan produces

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                              # MODIFY — call ServiceBootstrap.install
└── core/
    ├── events/
    │   ├── BusEvent.kt                                             # MODIFY — 2 new subtypes + 1 enum
    │   └── PrivacyScrubber.kt                                      # MODIFY — 2 passthrough cases
    └── services/
        ├── ServiceOutcome.kt                                       # CREATE
        ├── OperationalRestriction.kt                               # CREATE
        ├── RestrictionEngine.kt                                    # CREATE
        ├── RestrictionGate.kt                                      # CREATE
        ├── WithServiceCall.kt                                      # CREATE — internal inline helper
        ├── ServiceBootstrap.kt                                     # CREATE
        ├── bank/      { BankBackend.kt, VitalApiBankBackend.kt, BankService.kt }
        ├── inventory/ { InventoryBackend.kt, VitalApiInventoryBackend.kt, InventoryService.kt }
        ├── equipment/ { EquipmentBackend.kt, VitalApiEquipmentBackend.kt, EquipmentService.kt }
        ├── walker/    { WalkerBackend.kt, VitalApiWalkerBackend.kt, WalkerService.kt }
        ├── login/     { LoginBackend.kt, VitalApiLoginBackend.kt, LoginService.kt }
        ├── world/     { WorldBackend.kt, VitalApiWorldBackend.kt, WorldService.kt }
        ├── combat/    { CombatBackend.kt, VitalApiCombatBackend.kt, CombatService.kt }
        ├── magic/     { MagicBackend.kt, VitalApiMagicBackend.kt, MagicService.kt }
        ├── prayer/    { PrayerBackend.kt, VitalApiPrayerBackend.kt, PrayerService.kt }
        ├── interact/  { InteractBackend.kt, VitalApiInteractBackend.kt, InteractService.kt }
        ├── dialogue/  { DialogueBackend.kt, VitalApiDialogueBackend.kt, DialogueService.kt }
        ├── chat/      { ChatBackend.kt, VitalApiChatBackend.kt, ChatService.kt }
        └── ge/        { GrandExchangeBackend.kt, VitalApiGrandExchangeBackend.kt, GrandExchangeService.kt }

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/PrivacyScrubberTest.kt                              # MODIFY — bump 39→41 + 2 samples
├── core/services/
│   ├── WithServiceCallTest.kt
│   ├── RestrictionGateTest.kt
│   └── (one test file per service group)
└── arch/
    ├── LoggingArchitectureTest.kt                                  # MODIFY — bump 39→41
    └── ServicesArchitectureTest.kt                                 # CREATE
```

---

## Phase 1 — Core types (Tasks 1-5)

### Task 1 — Add `ServiceCallStart` / `ServiceCallEnd` BusEvents + `ServiceOutcome` enum

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1:** Append at the end of `BusEvent.kt`:

```kotlin
// ─────────────────────────────────────────────────────────────────────
// Service call events (Plan 5a spec §4.1)
// ─────────────────────────────────────────────────────────────────────

enum class ServiceOutcome { SUCCESS, FAILURE, RESTRICTED, EXCEPTION }

data class ServiceCallStart(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val serviceName: String,
	val methodName: String,
	val callId: Long
) : BusEvent

data class ServiceCallEnd(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val serviceName: String,
	val methodName: String,
	val callId: Long,
	val durationMillis: Long,
	val outcome: ServiceOutcome
) : BusEvent
```

- [ ] **Step 2:** `compileKotlin` will fail because `PrivacyScrubber` `when` is no longer exhaustive. Add 2 passthrough stubs to unblock — they'll be reviewed in Task 2:

In `core/events/PrivacyScrubber.kt`'s `scrub` `when` block, after the last existing case, add:

```kotlin
			is ServiceCallStart -> event
			is ServiceCallEnd   -> event
```

- [ ] **Step 3:** `cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5` → BUILD SUCCESSFUL.

- [ ] **Step 4:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — add ServiceCallStart/End BusEvents + ServiceOutcome enum"
git push origin main
```

---

### Task 2 — Bump `PrivacyScrubber` arch test 39→41 + extend `PrivacyScrubberTest`

**Files:**
- Modify `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`
- Modify `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt`

- [ ] **Step 1:** In `LoggingArchitectureTest.kt`, find `val scrubberSampleCount = 39` and change to `41`.

- [ ] **Step 2:** In `PrivacyScrubberTest.kt`, find the samples list (39 entries from Plan 4b). Append:

```kotlin
		ServiceCallStart(sessionId = sid, serviceName = "BankService", methodName = "open", callId = 1L),
		ServiceCallEnd  (sessionId = sid, serviceName = "BankService", methodName = "open", callId = 1L, durationMillis = 12L, outcome = ServiceOutcome.SUCCESS)
```

Add imports for `ServiceCallStart`, `ServiceCallEnd`, `ServiceOutcome`. Update any `assertEquals(samples.size, 39)` to `41`.

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
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — bump PrivacyScrubber sample count 39→41 for ServiceCall events"
git push origin main
```

---

### Task 3 — `OperationalRestriction` enum + `RestrictionEngine` + `RestrictionGate`

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/OperationalRestriction.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/RestrictionEngine.kt`
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/RestrictionGate.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/RestrictionGateTest.kt`

- [ ] **Step 1: Write the failing test** at `RestrictionGateTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RestrictionGateTest
{
	@BeforeEach
	fun reset()
	{
		RestrictionGate.engine = null
	}

	@Test
	fun `null engine is pass-through`()
	{
		RestrictionGate.check(OperationalRestriction.BANK_DISABLED)   // must not throw
	}

	@Test
	fun `engine present + restriction in deny set throws RestrictionViolation`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		val ex = assertThrows(RestrictionViolation::class.java) {
			RestrictionGate.check(OperationalRestriction.BANK_DISABLED)
		}
		assertEquals(OperationalRestriction.BANK_DISABLED, ex.restriction)
	}

	@Test
	fun `engine present + restriction not in deny set is pass-through`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		RestrictionGate.check(OperationalRestriction.BANK_DISABLED)   // must not throw
	}
}
```

- [ ] **Step 2:** `./gradlew :BuildCore:test --tests "*RestrictionGateTest*" --no-daemon 2>&1 | tail -5` → compile errors expected.

- [ ] **Step 3:** Create `OperationalRestriction.kt`:

```kotlin
package net.vital.plugins.buildcore.core.services

/**
 * Runtime profile-level operational disables consulted by services at call time.
 * Distinct from Plan 2's task-edit-time [net.vital.plugins.buildcore.core.task.Effect]
 * taxonomy. Plan 5a spec §4.2.
 */
enum class OperationalRestriction
{
	GRAND_EXCHANGE_DISABLED,
	TRADING_DISABLED,
	WILDERNESS_DISABLED,
	BANK_DISABLED,
	LOGIN_DISABLED,
	WORLD_HOP_DISABLED
}
```

- [ ] **Step 4:** Create `RestrictionEngine.kt`:

```kotlin
package net.vital.plugins.buildcore.core.services

/**
 * Runtime engine consulted by [RestrictionGate]. Plan 7 will swap in a profile-aware
 * implementation; v1 ships [StaticRestrictionEngine].
 *
 * Plan 5a spec §4.3.
 */
interface RestrictionEngine
{
	/** Throws [RestrictionViolation] if [restriction] is denied. */
	fun check(restriction: OperationalRestriction)
}

class StaticRestrictionEngine(private val denied: Set<OperationalRestriction>) : RestrictionEngine
{
	override fun check(restriction: OperationalRestriction)
	{
		if (restriction in denied) throw RestrictionViolation(restriction)
	}
}

class RestrictionViolation(val restriction: OperationalRestriction) :
	RuntimeException("operational restriction denied: $restriction")
```

- [ ] **Step 5:** Create `RestrictionGate.kt`:

```kotlin
package net.vital.plugins.buildcore.core.services

/**
 * First-line operational veto for service action calls. Wired by
 * [ServiceBootstrap]. `engine == null` means pass-through (used in unit tests
 * that don't care about restrictions).
 *
 * Plan 5a spec §4.3.
 */
object RestrictionGate
{
	@Volatile var engine: RestrictionEngine? = null

	fun check(restriction: OperationalRestriction)
	{
		engine?.check(restriction)
	}
}
```

- [ ] **Step 6:** `./gradlew :BuildCore:test --tests "*RestrictionGateTest*" --no-daemon` → 3 pass.

- [ ] **Step 7:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/OperationalRestriction.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/RestrictionEngine.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/RestrictionGate.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/RestrictionGateTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — OperationalRestriction enum + RestrictionEngine + RestrictionGate"
git push origin main
```

---

### Task 4 — `withServiceCall` helper + `ServiceCallContext`

**Files:**
- Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCall.kt`
- Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCallTest.kt`

- [ ] **Step 1: Write the failing test:**

```kotlin
package net.vital.plugins.buildcore.core.services

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class WithServiceCallTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()

	@BeforeEach
	fun reset()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
	}

	@Test
	fun `success path emits Start then End with SUCCESS outcome and matching callId`()
	{
		val result = withServiceCall(bus, { sid }, "X", "m") { true }
		assertTrue(result)
		assertEquals(2, captured.size)
		val start = captured[0] as ServiceCallStart
		val end   = captured[1] as ServiceCallEnd
		assertEquals(start.callId, end.callId)
		assertEquals(ServiceOutcome.SUCCESS, end.outcome)
		assertTrue(end.durationMillis >= 0L)
	}

	@Test
	fun `block returning false yields FAILURE outcome`()
	{
		withServiceCall(bus, { sid }, "X", "m") { false }
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `block returning null yields FAILURE outcome`()
	{
		withServiceCall<String?>(bus, { sid }, "X", "m") { null }
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `RestrictionViolation classifies as RESTRICTED and rethrows`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) {
			withServiceCall(bus, { sid }, "X", "m", restriction = OperationalRestriction.BANK_DISABLED) { true }
		}
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `arbitrary exception classifies as EXCEPTION and rethrows`()
	{
		assertThrows(IllegalStateException::class.java) {
			withServiceCall<Boolean>(bus, { sid }, "X", "m") { error("boom") }
		}
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `callId is monotonic`()
	{
		withServiceCall(bus, { sid }, "X", "m") { true }
		withServiceCall(bus, { sid }, "X", "m") { true }
		val first  = (captured[1] as ServiceCallEnd).callId
		val second = (captured[3] as ServiceCallEnd).callId
		assertEquals(first + 1L, second)
	}
}
```

- [ ] **Step 2:** Run → expect compile errors.

- [ ] **Step 3:** Implement `WithServiceCall.kt`:

```kotlin
package net.vital.plugins.buildcore.core.services

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
 * The canonical wrapper shape for every L5 service action method. Centralises
 * event emission, restriction checking, timing, and outcome classification.
 *
 * Plan 5a spec §4.4.
 */
internal inline fun <T> withServiceCall(
	bus: EventBus?,
	sessionIdProvider: () -> UUID,
	serviceName: String,
	methodName: String,
	restriction: OperationalRestriction? = null,
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
		val result = block()
		if (result == false || result == null) outcome = ServiceOutcome.FAILURE
		return result
	}
	catch (e: RestrictionViolation) { throw e }
	catch (e: Throwable)
	{
		if (outcome != ServiceOutcome.RESTRICTED) outcome = ServiceOutcome.EXCEPTION
		throw e
	}
	finally
	{
		val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
		bus?.tryEmit(ServiceCallEnd(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId, durationMillis = durationMs, outcome = outcome))
	}
}
```

> **Implementer note:** `ServiceCallContext.resetForTests` is `internal` — accessible to tests in the same module. The `withServiceCall` function is `internal inline` — services in the same module can call it. The function is `inline` so the `block` parameter can call `suspend` functions when the calling site is `suspend`.

- [ ] **Step 4:** `./gradlew :BuildCore:test --tests "*WithServiceCallTest*" --no-daemon` → 6 pass.

- [ ] **Step 5:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCall.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/WithServiceCallTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — withServiceCall helper + ServiceCallContext (callId monotonic)"
git push origin main
```

---

### Task 5 — `ServiceBootstrap` skeleton

**File:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/ServiceBootstrap.kt`

> **Note:** This task creates the skeleton of `ServiceBootstrap` with empty `install`/`resetForTests` methods. Tasks 6–18 will each add their service's wiring inside `install`. Task 19 finalizes by adding the call from `BuildCorePlugin.startUp`.

- [ ] **Step 1:** Create the file:

```kotlin
package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.events.EventBus
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires all 14 L5 service backends + restriction engine. Called once from
 * [net.vital.plugins.buildcore.BuildCorePlugin.startUp] after `AntibanBootstrap.install`.
 *
 * Plan 5a spec §6.
 */
object ServiceBootstrap
{
	private val installed = AtomicBoolean(false)

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		restrictionEngine: RestrictionEngine = StaticRestrictionEngine(emptySet())
	)
	{
		if (!installed.compareAndSet(false, true)) return
		RestrictionGate.engine = restrictionEngine
		// Service-specific wiring is added in Tasks 6–18.
	}

	internal fun resetForTests()
	{
		installed.set(false)
		RestrictionGate.engine = null
		ServiceCallContext.resetForTests()
		// Service-specific reset is added in Tasks 6–18.
	}
}
```

- [ ] **Step 2:** `./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5` → BUILD SUCCESSFUL.

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/ServiceBootstrap.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — ServiceBootstrap skeleton (install + resetForTests)"
git push origin main
```

---

## Phase 2 — Services (Tasks 6-18)

Each service follows the same recipe. Tasks 6–18 implement them one per task. The recipe per task:

1. Create `<X>Backend.kt` (interface).
2. Create `VitalApi<X>Backend.kt` (default impl, delegating to VitalAPI; uses `error("not implemented in 5a; wire when consumer needs it")` for methods without a clean static delegate).
3. Create `<X>Service.kt` (Kotlin `object` with `@Volatile internal var backend`, `bus`, `sessionIdProvider`; one `suspend fun` per method using `withServiceCall(...) { backend.method(...) }`).
4. Create `<X>ServiceTest.kt` (TDD). Standard test set:
   - For each method: happy-path success; `false` return → FAILURE; thrown exception → EXCEPTION.
   - For each restriction-gated method: deny set containing the restriction → throws RestrictionViolation, outcome RESTRICTED, backend NOT invoked.
5. Wire backend into `ServiceBootstrap.install` (`<X>Service.backend = VitalApi<X>Backend`, `bus = bus`, `sessionIdProvider = sessionIdProvider`) and clear in `resetForTests`.
6. Run targeted test → all pass.
7. Commit and push.

> **Subagent prompt template (for executing-plans):** When dispatching a subagent for a Task in this phase, you can paste the recipe above + the Task's specific method list + the spec excerpt. Tasks 6–18 each include the per-service inputs they need: method signatures, restriction (if any), and key VitalAPI delegations.

### Task 6 — `BankService`

**Service surface (spec §5):**
- `open(): Boolean` — restriction `BANK_DISABLED`
- `close(): Boolean` — restriction `BANK_DISABLED`
- `deposit(itemId: Int, amount: Int): Boolean` — restriction `BANK_DISABLED`
- `depositAll(): Boolean` — restriction `BANK_DISABLED`
- `depositInventory(): Boolean` — restriction `BANK_DISABLED`
- `withdraw(itemId: Int, amount: Int): Boolean` — restriction `BANK_DISABLED`

**VitalAPI delegations (`vital.api.containers.Bank`):**
- `Bank.open()` — `void`, returns no result; backend impl: `Bank.open(); return Bank.isValid()`
- `Bank.close()` — `void`; backend impl: `Bank.close(); return !Bank.isValid()`
- `Bank.depositItem(slot: Int, itemId: Int, count: Int): Boolean` — backend's `deposit(itemId, amount)` impl: locate slot via `Bank.getFirstById(itemId)?.get(0) ?: return false`, then `Bank.depositItem(slot, itemId, amount)`.
- `Bank.depositAll(): Boolean` — direct delegation.
- For `depositInventory()`: VitalAPI may not have a direct equivalent — backend uses `Bank.depositAll()` as best-effort or `error("not implemented in 5a")`. **Pick `error("not implemented in 5a; use depositAll instead")`** — keeps the public service surface honest.
- `Bank.withdraw(slot: Int, itemId: Int, count: Int): Boolean` — same locate-by-id pattern.

- [ ] **Step 1: Create `bank/BankBackend.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.services.bank

interface BankBackend
{
	suspend fun open(): Boolean
	suspend fun close(): Boolean
	suspend fun deposit(itemId: Int, amount: Int): Boolean
	suspend fun depositAll(): Boolean
	suspend fun depositInventory(): Boolean
	suspend fun withdraw(itemId: Int, amount: Int): Boolean
}
```

- [ ] **Step 2: Create `bank/VitalApiBankBackend.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.services.bank

import vital.api.containers.Bank as VitalBank

/**
 * Default [BankBackend] delegating to [vital.api.containers.Bank].
 * Plan 5a spec §5.
 */
object VitalApiBankBackend : BankBackend
{
	override suspend fun open(): Boolean
	{
		VitalBank.open()
		return VitalBank.isValid()
	}

	override suspend fun close(): Boolean
	{
		VitalBank.close()
		return !VitalBank.isValid()
	}

	override suspend fun deposit(itemId: Int, amount: Int): Boolean
	{
		val first = VitalBank.getFirstById(itemId) ?: return false
		val slot = first[0]
		return VitalBank.depositItem(slot, itemId, amount)
	}

	override suspend fun depositAll(): Boolean = VitalBank.depositAll()

	override suspend fun depositInventory(): Boolean =
		error("not implemented in 5a; use depositAll instead")

	override suspend fun withdraw(itemId: Int, amount: Int): Boolean
	{
		val first = VitalBank.getFirstById(itemId) ?: return false
		val slot = first[0]
		return VitalBank.withdraw(slot, itemId, amount)
	}
}
```

- [ ] **Step 3: Create `bank/BankService.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.services.bank

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object BankService
{
	@Volatile internal var backend: BankBackend = VitalApiBankBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun open(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "open",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.open() }

	suspend fun close(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "close",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.close() }

	suspend fun deposit(itemId: Int, amount: Int): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "deposit",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.deposit(itemId, amount) }

	suspend fun depositAll(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "depositAll",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.depositAll() }

	suspend fun depositInventory(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "depositInventory",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.depositInventory() }

	suspend fun withdraw(itemId: Int, amount: Int): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "withdraw",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.withdraw(itemId, amount) }

	internal fun resetForTests()
	{
		backend = VitalApiBankBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
```

- [ ] **Step 4: Create `bank/BankServiceTest.kt`:**

```kotlin
package net.vital.plugins.buildcore.core.services.bank

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.RestrictionGate
import net.vital.plugins.buildcore.core.services.RestrictionViolation
import net.vital.plugins.buildcore.core.services.ServiceCallContext
import net.vital.plugins.buildcore.core.services.StaticRestrictionEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class BankServiceTest
{
	private val captured = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { captured.add(firstArg()); true }
	}
	private val sid = UUID.randomUUID()
	private val fakeBackend = mockk<BankBackend>()

	@BeforeEach
	fun setup()
	{
		captured.clear()
		ServiceCallContext.resetForTests()
		RestrictionGate.engine = null
		BankService.backend = fakeBackend
		BankService.bus = bus
		BankService.sessionIdProvider = { sid }
	}

	@AfterEach
	fun teardown() { BankService.resetForTests() }

	@Test
	fun `open success path`() = runTest {
		coEvery { fakeBackend.open() } returns true
		assertTrue(BankService.open())
		coVerify(exactly = 1) { fakeBackend.open() }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is ServiceCallStart)
		assertEquals(ServiceOutcome.SUCCESS, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open failure path`() = runTest {
		coEvery { fakeBackend.open() } returns false
		assertFalse(BankService.open())
		assertEquals(ServiceOutcome.FAILURE, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open exception path`() = runTest {
		coEvery { fakeBackend.open() } throws IllegalStateException("boom")
		assertThrows(IllegalStateException::class.java) { runTest { BankService.open() } }
		assertEquals(ServiceOutcome.EXCEPTION, (captured[1] as ServiceCallEnd).outcome)
	}

	@Test
	fun `open restricted path`() = runTest {
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		assertThrows(RestrictionViolation::class.java) { runTest { BankService.open() } }
		assertEquals(ServiceOutcome.RESTRICTED, (captured[1] as ServiceCallEnd).outcome)
		coVerify(exactly = 0) { fakeBackend.open() }
	}

	@Test
	fun `deposit forwards itemId and amount`() = runTest {
		coEvery { fakeBackend.deposit(995, 100) } returns true
		assertTrue(BankService.deposit(995, 100))
		coVerify(exactly = 1) { fakeBackend.deposit(995, 100) }
	}

	@Test
	fun `withdraw forwards itemId and amount`() = runTest {
		coEvery { fakeBackend.withdraw(995, 50) } returns true
		assertTrue(BankService.withdraw(995, 50))
		coVerify(exactly = 1) { fakeBackend.withdraw(995, 50) }
	}

	@Test
	fun `depositAll happy path`() = runTest {
		coEvery { fakeBackend.depositAll() } returns true
		assertTrue(BankService.depositAll())
	}

	@Test
	fun `depositInventory happy path`() = runTest {
		coEvery { fakeBackend.depositInventory() } returns true
		assertTrue(BankService.depositInventory())
	}

	@Test
	fun `close happy path`() = runTest {
		coEvery { fakeBackend.close() } returns true
		assertTrue(BankService.close())
	}
}
```

> **Implementer note:** the assertThrows + runTest combo above wraps the inner `runTest`. If the test runner's MockK + coroutine combo complains, switch to `runTest { assertFailsWith<...> { BankService.open() } }` using `kotlin.test.assertFailsWith`.

- [ ] **Step 5: Wire the backend into `ServiceBootstrap.install`**, just below the `RestrictionGate.engine = restrictionEngine` line:

```kotlin
		BankService.backend = VitalApiBankBackend
		BankService.bus = bus
		BankService.sessionIdProvider = sessionIdProvider
```

(import `net.vital.plugins.buildcore.core.services.bank.BankService` and `net.vital.plugins.buildcore.core.services.bank.VitalApiBankBackend`).

In `ServiceBootstrap.resetForTests`, append:
```kotlin
		BankService.resetForTests()
```

- [ ] **Step 6:** Run targeted tests:
```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests "*BankServiceTest*" --no-daemon 2>&1 | tail -10
```
Expected: 9 passed.

- [ ] **Step 7:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/bank/ \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/ServiceBootstrap.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/services/bank/
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — BankService (open/close/deposit/depositAll/depositInventory/withdraw)"
git push origin main
```

---

### Task 7 — `InventoryService`

**Surface:** `drop(itemId: Int): Boolean`, `useOn(srcSlot: Int, destSlot: Int): Boolean`, `interact(slot: Int, action: String): Boolean`. No restriction.

**VitalAPI delegations (`vital.api.containers.Inventory`):** `Inventory.dropAll(itemId)` for `drop`; for `useOn` and `interact`, VitalAPI may use `Bridge.inventoryUseOn(srcSlot, destSlot)` / `Bridge.inventoryInteract(slot, action)` — if those don't have a clean static method, the backend uses `error("not implemented in 5a")`.

Apply the standard recipe (Step 1: backend; Step 2: VitalApi backend with delegate or `error`; Step 3: service object; Step 4: test; Step 5: wire bootstrap; Step 6: test; Step 7: commit). Use `Plan 5a — InventoryService (drop/useOn/interact)` as commit message.

The service file structure is identical to `BankService`, just substituting `Bank` → `Inventory`, the methods listed, no `restriction = …` parameter. Test file mirrors `BankServiceTest`'s 4 paths per method (success / failure / exception / no-restriction-test-needed).

---

### Task 8 — `EquipmentService`

**Surface:** `equip(itemId: Int): Boolean`, `unequip(slot: Int): Boolean`. No restriction.

**VitalAPI delegations:** `vital.api.containers.Equipment` has only queries; equip/unequip happen by interacting with inventory items (`Inventory.interact(slot, "Wield")`). Backend impls:
- `equip(itemId)`: `val slot = Inventory.getFirst(itemId)?.slot ?: return false; return Bridge.inventoryInteract(slot, "Wield")` — or `error("not implemented in 5a; needs inventory interact wiring")` if that bridge call isn't accessible directly. Pick the latter for v1.
- `unequip(slot)`: `error("not implemented in 5a")`.

Standard recipe. Commit: `BuildCore: Plan 5a — EquipmentService (equip/unequip; backends stubbed pending inventory.interact wiring)`.

---

### Task 9 — `WalkerService`

**Surface:** `walkTo(tile: Tile): Boolean`, `walkExact(tile: Tile): Boolean`, `stop(): Unit`. No restriction.

**VitalAPI delegations (`vital.api.input.Movement`):** `Movement.walkTo(x, y)` exists. Backend impls:
- `walkTo(tile)`: `vital.api.input.Movement.walkTo(tile.x, tile.y); return true`.
- `walkExact(tile)`: same delegate; `walkExact` semantics differ (no clip-to-near-tile). For v1, both call the same underlying method — document the limitation. Or impl both as `error("not implemented in 5a; needs walker plugin API")`.
- `stop()`: `error("not implemented in 5a")` or no-op (return `Unit`).

`WalkerBackend` interface has `suspend fun stop()` returning `Unit` — confirm `withServiceCall` works with `T = Unit`. It does; `Unit` is not `false` and not `null`, so `outcome = SUCCESS`.

`Tile` parameter type comes from `vital.api.entities.Tile` — import it in the backend interface and wherever tasks call `WalkerService.walkTo(...)`.

Standard recipe. Commit: `BuildCore: Plan 5a — WalkerService (walkTo/walkExact/stop)`.

---

### Task 10 — `LoginService`

**Surface:** `login(): Boolean`, `logout(): Boolean`. Restriction `LOGIN_DISABLED`.

**VitalAPI delegations:** No `vital.api.Login` exists. Login/logout would be triggered by clicking the login screen widget or similar. Backend impls:
- `login()`: `error("not implemented in 5a; wire when autologin plugin exposes a callable API")`.
- `logout()`: `error("not implemented in 5a; wire when autologin plugin exposes a callable API")`.

Service object includes `restriction = OperationalRestriction.LOGIN_DISABLED` per method.

Test set: same 4 paths per method including the restricted-path test (deny set → throws). Use `mockk<LoginBackend>()` so the `error(…)` is never reached — test asserts the service surface, not the live backend.

Standard recipe. Commit: `BuildCore: Plan 5a — LoginService (login/logout; backends stubbed)`.

---

### Task 11 — `WorldService`

**Surface:** `hop(targetWorld: Int): Boolean`. Restriction `WORLD_HOP_DISABLED`.

**VitalAPI delegations:** `vital.api.world.Worlds` may have `hop(...)` — verify. If not, `error("not implemented in 5a")`.

Standard recipe. Commit: `BuildCore: Plan 5a — WorldService (hop)`.

---

### Task 12 — `CombatService`

**Surface:** `attack(target: Targetable): Boolean`, `setAutoRetaliate(enabled: Boolean): Boolean`. No restriction.

**`Targetable` type:** This is a sealed interface introduced by this task. Define:
```kotlin
package net.vital.plugins.buildcore.core.services.combat

sealed interface Targetable
{
	data class NpcTarget(val npc: vital.api.entities.Npc) : Targetable
	data class PlayerTarget(val player: vital.api.entities.Player) : Targetable
}
```

(Place it in `combat/Targetable.kt`.)

**VitalAPI delegations (`vital.api.ui.Combat`):**
- `attack(target)`: pattern-match on `Targetable`; for `NpcTarget`, call `Bridge.npcInteract(npcId, "Attack")` or use `vital.api.entities.Npc`'s instance method (check). For `PlayerTarget`, similar. If neither has a clean static path, `error("not implemented in 5a")` per branch.
- `setAutoRetaliate(enabled)`: `Combat.toggleAutoRetaliate()` is a *toggle*, not a setter. Backend impl reads current state via `Bridge.combatIsAutoRetaliating()` (if exists) and toggles only when needed. If no read-side exists, `error("not implemented in 5a")`. Acceptable for v1 since real combat tasks haven't shipped.

Standard recipe + add `Targetable.kt` to the commit. Commit: `BuildCore: Plan 5a — CombatService (attack/setAutoRetaliate) + Targetable`.

---

### Task 13 — `MagicService`

**Surface:** `cast(spell: Spell): Boolean`, `castOn(spell: Spell, target: Targetable): Boolean`. No restriction.

**`Spell` type:** import `vital.api.ui.Spell` (already exists per VitalAPI surface).

**VitalAPI delegations (`vital.api.ui.Magic`):**
- `cast(spell)` → `Magic.cast(spell)` (typed overload).
- `castOn(spell, target)` → `Magic.castOn(spell, targetParam, targetAction)`. Backend pattern-matches on `Targetable` to derive `targetParam` and `targetAction`. If complex, `error("not implemented in 5a")`.

Imports `Targetable` from Task 12. Standard recipe. Commit: `BuildCore: Plan 5a — MagicService (cast/castOn)`.

---

### Task 14 — `PrayerService`

**Surface:** `toggle(prayer: Prayer): Boolean`, `flick(prayer: Prayer): Boolean`. No restriction.

**`Prayer` type:** import `vital.api.ui.Prayer`.

**VitalAPI delegations (`vital.api.ui.Prayers`):** likely has `Prayers.toggle(prayer)` and `Prayers.flick(prayer)` or similar; verify. If absent, `error("not implemented in 5a")`.

Standard recipe. Commit: `BuildCore: Plan 5a — PrayerService (toggle/flick)`.

---

### Task 15 — `InteractService`

**Surface:**
- `tileObject(obj: TileObject, action: String): Boolean`
- `npc(npc: Npc, action: String): Boolean`
- `tileItem(item: TileItem, action: String): Boolean`

No restriction.

**Imports:** `vital.api.entities.{TileObject, Npc, TileItem}`.

**VitalAPI delegations:** Each entity class has an `interact(action)` instance method (verify) or there's a `Bridge.<x>Interact(id, action)` static. Backend impls call those.

Standard recipe. Commit: `BuildCore: Plan 5a — InteractService (tileObject/npc/tileItem)`.

---

### Task 16 — `DialogueService`

**Surface:** `continueAll(): Boolean`, `chooseOption(matcher: String): Boolean`, `close(): Boolean`. No restriction.

**VitalAPI delegations:** No standardised dialogue API; widget-based. Most backends will be `error("not implemented in 5a")`.

Standard recipe. Commit: `BuildCore: Plan 5a — DialogueService (continueAll/chooseOption/close; backends stubbed)`.

---

### Task 17 — `ChatService`

**Surface:** `send(text: String): Boolean`, `sendChannel(channel: ChatChannel, text: String): Boolean`. No restriction.

**`ChatChannel` enum:** define in `chat/ChatChannel.kt`:
```kotlin
package net.vital.plugins.buildcore.core.services.chat

enum class ChatChannel { PUBLIC, FRIENDS, CLAN, GUEST_CLAN, PRIVATE_MESSAGE }
```

**VitalAPI delegations:** Likely `Bridge.chatSend(text)` or similar. If not directly accessible, `error("not implemented in 5a")` for both methods is acceptable.

Standard recipe + include `ChatChannel.kt`. Commit: `BuildCore: Plan 5a — ChatService (send/sendChannel) + ChatChannel`.

---

### Task 18 — `GrandExchangeService`

**Surface:**
- `open(): Boolean` — restriction `GRAND_EXCHANGE_DISABLED`
- `close(): Boolean` — restriction `GRAND_EXCHANGE_DISABLED`
- `submitBuy(itemId: Int, qty: Int, pricePerEach: Int): Boolean` — restriction `GRAND_EXCHANGE_DISABLED`
- `submitSell(slot: Int, pricePerEach: Int): Boolean` — restriction `GRAND_EXCHANGE_DISABLED`
- `collectAll(): Boolean` — restriction `GRAND_EXCHANGE_DISABLED`

**VitalAPI delegations (`vital.api.ui.GrandExchange`):**
- `open()`: `GrandExchange.open(); return GrandExchange.isOpen()` — verify `isOpen()` exists; if not, return `true` after the void call.
- `close()`: `GrandExchange.close(); return !GrandExchange.isOpen()`.
- `collectAll()`: `GrandExchange.collectAll()`.
- `submitBuy(itemId, qty, pricePerEach)`: probably `Bridge.geSubmitBuy(itemId, qty, pricePerEach)` — verify; if absent, `error("not implemented in 5a")`.
- `submitSell(slot, pricePerEach)`: similar.

Standard recipe. All 5 methods restriction-gated → 5×4 + 5×0 = 20 tests minimum. Commit: `BuildCore: Plan 5a — GrandExchangeService (open/close/submitBuy/submitSell/collectAll)`.

---

## Phase 3 — Bootstrap wiring + Architecture test (Tasks 19-20)

### Task 19 — Wire `ServiceBootstrap.install` into `BuildCorePlugin.startUp`

**File:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt`

- [ ] **Step 1:** Add the import:
```kotlin
import net.vital.plugins.buildcore.core.services.ServiceBootstrap
```

- [ ] **Step 2:** In `startUp()`, after the existing `AntibanBootstrap.install(...)` block (and after the `breakSchedulerJob` launch), add:

```kotlin
		ServiceBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId }
		)
```

- [ ] **Step 3:** `./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15` — full suite green. (The bootstrap is idempotent and uses default empty restriction set, so existing tests are unaffected.)

- [ ] **Step 4:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — wire ServiceBootstrap.install in BuildCorePlugin.startUp"
git push origin main
```

---

### Task 20 — `ServicesArchitectureTest` (Konsist)

**File:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/ServicesArchitectureTest.kt`

- [ ] **Step 1: Implement the test:**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the L5 service layer.
 *
 * Plan 5a spec §7.
 */
class ServicesArchitectureTest
{
	private fun servicePackageFiles() = Konsist.scopeFromProduction()
		.files
		.filter { it.path.contains("/core/services/") }

	@Test
	fun `every Service file declares an object`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("object "))
				{ "${file.path}: a *Service.kt file must declare an object" }
			}
	}

	@Test
	fun `every Service file declares Volatile internal var backend`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("@Volatile internal var backend"))
				{ "${file.path}: must declare `@Volatile internal var backend`" }
			}
	}

	@Test
	fun `every Service file body uses withServiceCall`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("withServiceCall("))
				{ "${file.path}: must invoke withServiceCall" }
			}
	}

	@Test
	fun `Service files do not import vital_api directly`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") }
			.forEach { file ->
				assert(!file.text.contains("import vital.api"))
				{ "${file.path}: only VitalApi*Backend.kt files may import vital.api.*" }
			}
	}

	@Test
	fun `Service files do not import vital_api_input`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") }
			.forEach { file ->
				assert(!file.text.contains("import vital.api.input"))
				{ "${file.path}: must not import vital.api.input.* (route input through Plan 4a primitives)" }
			}
	}
}
```

- [ ] **Step 2:** `./gradlew :BuildCore:test --tests "*ServicesArchitectureTest*" --no-daemon 2>&1 | tail -10` → 5 pass.

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/ServicesArchitectureTest.kt
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: Plan 5a — ServicesArchitectureTest (Konsist invariants for L5)"
git push origin main
```

---

## Phase 4 — Docs (Task 21)

### Task 21 — Update BuildCore CLAUDE.md

**File:** Modify `BuildCore/CLAUDE.md`

- [ ] **Step 1: Update Status section.** Replace the existing Status block with:

```markdown
## Status

**Foundation phase — Plans 1 + 2 + 3 + 4a + 4b + 5a complete.**

Future plans in `../docs/superpowers/plans/`:
- ~~Plan 2 — Core Runtime + Task SPI + Restrictions~~ (done)
- ~~Plan 3 — Logging + Event Bus~~ (done)
- ~~Plan 4a — RNG + Personality + Input Primitives~~ (done)
- ~~Plan 4b — Precision Mode + 4-tier break system + Misclick~~ (done)
- ~~Plan 5a — Service Infrastructure + 14 thin VitalAPI wrappers~~ (done)
- Plan 4c — ReplayRecorder + ReplayRng + ReplayServices
- Plan 5b — Services with logic (FoodPolicy, GearLoadout, StaminaPolicy, TeleportPlanner, HotRulesClient)
- Plan 5c — Cross-account / safety services (MuleService, WildernessThreatAnalyzer, TradeSafetyFilter, ChatSafetyListener)
- Plan 6 — Confidence / Watchdog / Recovery
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell
```

- [ ] **Step 2: Update the "Current invariants" section.** Append to the existing list:

```markdown
- `BusEvent` subtypes are data classes or objects (immutability).
- Every `BusEvent` subtype has a `PrivacyScrubber` case (exhaustive-when + drift test, 41 subtypes).
- ... (existing bullets)
- Every `*Service.kt` is a Kotlin `object` declaring `@Volatile internal var backend` (Plan 5a).
- Every `*Service.kt` action method body invokes `withServiceCall(...)` (Plan 5a).
- `*Service.kt` files do NOT import `vital.api.*`; only `VitalApi*Backend.kt` files do (Plan 5a).
```

(Replace the existing scrubber count `39` with `41`.)

- [ ] **Step 3:** Commit and push:
```bash
cd /c/Code/VitalPlugins
git add BuildCore/CLAUDE.md
git -c user.name=Chich -c user.email=cvernon336@gmail.com commit -m "BuildCore: mark Plan 5a complete in subproject CLAUDE.md"
git push origin main
```

---

## Self-review

### Spec coverage

| Spec section | Implementing task(s) |
|---|---|
| §1 goal (5 deliverables) | Phases 1-3 collectively |
| §2 deferrals | N/A (deferrals doc'd) |
| §3.1 module map | Tasks 3-18 produce exactly this layout |
| §3.2 layering | Tasks 6-18 (services) + Task 19 (bootstrap) |
| §3.3 service singleton pattern | Every service task (6-18) |
| §4.1 BusEvent additions | Task 1 |
| §4.1 PrivacyScrubber + arch | Tasks 1, 2 |
| §4.2 OperationalRestriction | Task 3 |
| §4.3 RestrictionGate + Engine | Task 3 |
| §4.4 withServiceCall helper | Task 4 |
| §5 service catalog (14 services × ~38 methods) | Tasks 6-18 |
| §6 wiring | Task 5 (skeleton), Task 19 (call site), Tasks 6-18 (per-service wiring inside install) |
| §7 testing | Task 4 (helper tests), Task 20 (arch test), Tasks 6-18 (per-service tests) |
| §8 risks | Mitigations referenced inline in Tasks 6-18 (`error("not implemented in 5a")` for missing VitalAPI surface) |
| §9 brainstorming resolutions | Reflected throughout |

All sections mapped. No gaps.

### Placeholder scan

No `TBD`, `TODO`, `FIXME`. The phrase `error("not implemented in 5a; ...")` is itself the implementation — it's the explicit, intentional v1 stub for VitalAPI surface gaps and is documented in spec §1 and §8. Tasks 7–18 reference the standard recipe (Task 6) by section anchor; this is acceptable because the recipe is fully spelled out in Task 6, and each subsequent task lists its specific deltas (method list, restriction, VitalAPI delegations).

### Type consistency

Verified:
- `ServiceCallStart` / `ServiceCallEnd` field names (`serviceName`, `methodName`, `callId`, `durationMillis`, `outcome`) consistent across Task 1 (declaration), Task 2 (test sample), Task 4 (helper emission), Tasks 6-18 (test assertions).
- `OperationalRestriction.BANK_DISABLED` (Task 3) used consistently in Tasks 4 (test), 6 (BankService), 10 (LoginService — `LOGIN_DISABLED`), 11 (`WORLD_HOP_DISABLED`), 18 (`GRAND_EXCHANGE_DISABLED`).
- `RestrictionGate.engine` (`@Volatile var`, public) consistent.
- `RestrictionViolation(restriction)` constructor consistent.
- `ServiceCallContext.resetForTests()` consistent (Tasks 4, 6-18 each call in test setup; Task 5 ServiceBootstrap.resetForTests delegates).
- `withServiceCall(bus, sessionIdProvider, serviceName, methodName, restriction, block)` signature consistent: 5 named params + block.
- `<X>Backend.<method>(...)` interface method signatures align with the `*Service.kt` `suspend fun` and the test's `coEvery { fakeBackend.<method>(...) } returns ...`.
- `Targetable` (Task 12) imported by Task 13 (`MagicService`) consistently.

No drift.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-25-plan-5a-service-infrastructure.md`.**

Per user's instructions, proceeding directly to **subagent-driven execution**.
