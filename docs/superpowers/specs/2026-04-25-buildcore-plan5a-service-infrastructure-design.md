# BuildCore Plan 5a — Service Infrastructure + Thin VitalAPI Wrappers Design

> **Status:** Spec — pending plan + implementation. Brainstormed and approved 2026-04-25.
> **Branch:** `main` on `xgoingleftyx/VitalPlugins` fork. Direct commit + push.
> **Author:** Chich only — no `Co-Authored-By`.
> **Builds on:** Plans 1, 2, 3, 4a, 4b (all merged; 172 tests).

---

## 1. Goal

Fill BuildCore's **L5 Services** layer with a minimal, mechanically-uniform set of thin wrappers over VitalAPI's static action methods, plus the cross-cutting infrastructure those wrappers compose:

1. **`ServiceCallStart` / `ServiceCallEnd` BusEvents** — emitted around every service action call. Plan 6 (Watchdog/Confidence) will subscribe.
2. **`RestrictionGate`** — first-line operational veto consulted by services that touch restricted subsystems (GE, Bank, Trading, Login, Wilderness, World Hop).
3. **`withServiceCall { … }` helper** — single ergonomic shape every service method follows; centralizes event emission, restriction checking, timing, and outcome classification.
4. **14 thin services** — `BankService`, `InventoryService`, `EquipmentService`, `WalkerService`, `LoginService`, `WorldService`, `CombatService`, `MagicService`, `PrayerService`, `InteractService`, `DialogueService`, `ChatService`, `GrandExchangeService`, plus an aggregating `ServiceBootstrap`.
5. **`OperationalRestriction` enum** — small set of profile-level operational disables, distinct from Plan 2's task-edit-time `Effect` taxonomy.

Plan 5a is **pure framework**. No decision-making logic, no multi-account services, no automation. Tasks consume these wrappers; subsequent plans (5b, 5c) build richer services on top.

## 2. Out of scope (deferred)

- **Plan 5b — Services with logic:** `FoodPolicy`, `GearLoadout`, `StaminaPolicy`, `TeleportPlanner`, `HotRulesClient`.
- **Plan 5c — Cross-account / safety services:** `MuleService`, `WildernessThreatAnalyzer`, `TradeSafetyFilter`, `ChatSafetyListener`. These require BuildCore-Server (Plan 8) or shipped tasks to validate against.
- **Read-only VitalAPI queries** (`Bank.containsId`, `Inventory.getCount`, etc.) are NOT wrapped. Tasks call `vital.api.containers.Bank.containsId(...)` directly. Only state-changing actions cross the L5 boundary, because cross-cutting concerns (antiban, events, restrictions, confidence) are only meaningful for actions.
- **`Confidence` integration** — services do NOT call a `ConfidenceTracker` directly. Plan 6 subscribes to `ServiceCallEnd` events on the existing event bus. Loose coupling; zero 5a code dedicated to confidence.
- **Annotations enforcing restriction declarations** (`@RequiresRestriction(...)`) — not needed for v1; arch test verifies presence of `withServiceCall(` calls instead.
- **Method-level retries / timeouts / waits** — not in v1. Wrappers are minimal `suspend fun`s; tasks compose retry/wait themselves until a real need surfaces.

## 3. Architecture

### 3.1 Module map

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                   # MODIFY — call ServiceBootstrap.install
└── core/
    ├── events/
    │   ├── BusEvent.kt                                  # MODIFY — 2 new subtypes + 1 enum
    │   └── PrivacyScrubber.kt                           # MODIFY — 2 passthrough cases
    └── services/
        ├── ServiceOutcome.kt                            # CREATE — enum
        ├── OperationalRestriction.kt                    # CREATE — enum
        ├── RestrictionEngine.kt                         # CREATE — minimal in-process gate engine
        ├── RestrictionGate.kt                           # CREATE — public object delegating to engine
        ├── WithServiceCall.kt                           # CREATE — internal inline helper
        ├── ServiceBootstrap.kt                          # CREATE — installs all 14 service backends
        ├── bank/
        │   ├── BankBackend.kt
        │   ├── VitalApiBankBackend.kt
        │   └── BankService.kt
        ├── inventory/   { Backend, VitalApi*, *Service }
        ├── equipment/   { … }
        ├── walker/      { … }   # delegates to VitalPlugins/walker plugin's API
        ├── login/       { … }   # delegates to VitalPlugins/autologin plugin's API
        ├── world/       { … }
        ├── combat/      { … }
        ├── magic/       { … }
        ├── prayer/      { … }
        ├── interact/    { … }
        ├── dialogue/    { … }
        ├── chat/        { … }
        └── ge/          { … }

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/services/
│   ├── WithServiceCallTest.kt
│   ├── RestrictionGateTest.kt
│   ├── bank/BankServiceTest.kt
│   ├── inventory/InventoryServiceTest.kt
│   ├── equipment/EquipmentServiceTest.kt
│   ├── walker/WalkerServiceTest.kt
│   ├── login/LoginServiceTest.kt
│   ├── world/WorldServiceTest.kt
│   ├── combat/CombatServiceTest.kt
│   ├── magic/MagicServiceTest.kt
│   ├── prayer/PrayerServiceTest.kt
│   ├── interact/InteractServiceTest.kt
│   ├── dialogue/DialogueServiceTest.kt
│   ├── chat/ChatServiceTest.kt
│   └── ge/GrandExchangeServiceTest.kt
└── arch/
    ├── LoggingArchitectureTest.kt                       # MODIFY — bump 39 → 41
    └── ServicesArchitectureTest.kt                      # CREATE
```

### 3.2 Layering

L5 only. Composes:
- L7 input primitives (Plan 4a/4b) — services do not bypass them; `VitalApi*Backend`s call VitalAPI static methods which internally route input via the antiban primitives.
- L4 Task SPI (Plan 2) — tasks call services directly; services do not know about Task.
- L3 Runner (Plan 2) — Runner calls task `step()` which calls service methods.
- Plan 2's restriction system is consulted via `RestrictionGate`; the underlying `RestrictionEngine` is a separate, minimal piece (§4.3) — the `RestrictionEngine` from Plan 2 covers task-edit-time validation and is not reused at runtime.

No new layer. No layering inversion.

### 3.3 Service singleton pattern (Q4 decision)

Every service is a Kotlin `object` with `@Volatile internal var` fields wired by `ServiceBootstrap` from `BuildCorePlugin.startUp`. Tests swap any field. Same pattern as Plan 4a's `Mouse`/`Keyboard`/`Camera`.

```kotlin
object BankService
{
    @Volatile internal var backend: BankBackend = VitalApiBankBackend
    @Volatile internal var bus: EventBus? = null
    @Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }
    // ... suspend fun methods
}
```

## 4. Core types

### 4.1 BusEvent additions

```kotlin
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

`callId` is a session-monotonic `Long` allocated inside `withServiceCall` so a Plan 6 subscriber can pair start↔end without timestamp guessing.

`PrivacyScrubber` cases: both passthrough — `serviceName`/`methodName` are static identifiers (e.g. `"BankService"`, `"open"`), not personal.

### 4.2 OperationalRestriction

```kotlin
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

This is **distinct** from Plan 2's `Effect` taxonomy, which describes what tasks do (consumed at edit-time by `RestrictionEngine` to veto incompatible task+profile combinations). `OperationalRestriction` is a runtime profile flag-set consulted at the call site. The two systems coexist; 5a does not modify Plan 2's Effect.

### 4.3 RestrictionGate + RestrictionEngine

```kotlin
interface RestrictionEngine
{
    /** Throws RestrictionViolation if `restriction` is currently denied. */
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

object RestrictionGate
{
    @Volatile internal var engine: RestrictionEngine? = null

    fun check(restriction: OperationalRestriction)
    {
        engine?.check(restriction)
    }
}
```

`StaticRestrictionEngine` is the v1 implementation: a fixed `Set<OperationalRestriction>` from the active profile. Plan 7 (Profile system) will swap in a profile-aware engine. `engine == null` (i.e., before bootstrap or in unit tests) means pass-through — useful for tests that don't care about restrictions.

### 4.4 withServiceCall helper

```kotlin
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
    catch (e: Throwable) { outcome = ServiceOutcome.EXCEPTION; throw e }
    finally
    {
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
        bus?.tryEmit(ServiceCallEnd(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId, durationMillis = durationMs, outcome = outcome))
    }
}

internal object ServiceCallContext
{
    private val counter = java.util.concurrent.atomic.AtomicLong(1L)
    fun nextCallId(): Long = counter.getAndIncrement()
    internal fun resetForTests() { counter.set(1L) }
}
```

Outcome classification:
- `RestrictionViolation` thrown by `RestrictionGate.check` → `RESTRICTED`, rethrown.
- Block returns `false` (Boolean) or `null` → `FAILURE`. Block returns anything else (`true`, non-null T) → `SUCCESS`.
- Any other exception → `EXCEPTION`, rethrown.

The `inline` keyword preserves call-stack visibility for debugging and lets the block use suspend functions when callers are also suspend.

## 5. Service catalog (curated MVP)

| Service | Method | Restriction |
|---|---|---|
| **BankService** | `open(): Boolean` | BANK_DISABLED |
| | `close(): Boolean` | BANK_DISABLED |
| | `deposit(itemId: Int, amount: Int): Boolean` | BANK_DISABLED |
| | `depositAll(): Boolean` | BANK_DISABLED |
| | `depositInventory(): Boolean` | BANK_DISABLED |
| | `withdraw(itemId: Int, amount: Int): Boolean` | BANK_DISABLED |
| **InventoryService** | `drop(itemId: Int): Boolean` | none |
| | `useOn(srcSlot: Int, destSlot: Int): Boolean` | none |
| | `interact(slot: Int, action: String): Boolean` | none |
| **EquipmentService** | `equip(itemId: Int): Boolean` | none |
| | `unequip(slot: Int): Boolean` | none |
| **WalkerService** | `walkTo(tile: Tile): Boolean` | none |
| | `walkExact(tile: Tile): Boolean` | none |
| | `stop(): Unit` | none |
| **LoginService** | `login(): Boolean` | LOGIN_DISABLED |
| | `logout(): Boolean` | LOGIN_DISABLED |
| **WorldService** | `hop(targetWorld: Int): Boolean` | WORLD_HOP_DISABLED |
| **CombatService** | `attack(target: Targetable): Boolean` | none |
| | `setAutoRetaliate(enabled: Boolean): Boolean` | none |
| **MagicService** | `cast(spell: Spell): Boolean` | none |
| | `castOn(spell: Spell, target: Targetable): Boolean` | none |
| **PrayerService** | `toggle(prayer: Prayer): Boolean` | none |
| | `flick(prayer: Prayer): Boolean` | none |
| **InteractService** | `tileObject(obj: TileObject, action: String): Boolean` | none |
| | `npc(npc: Npc, action: String): Boolean` | none |
| | `tileItem(item: TileItem, action: String): Boolean` | none |
| **DialogueService** | `continueAll(): Boolean` | none |
| | `chooseOption(matcher: String): Boolean` | none |
| | `close(): Boolean` | none |
| **ChatService** | `send(text: String): Boolean` | none |
| | `sendChannel(channel: ChatChannel, text: String): Boolean` | none |
| **GrandExchangeService** | `open(): Boolean` | GRAND_EXCHANGE_DISABLED |
| | `close(): Boolean` | GRAND_EXCHANGE_DISABLED |
| | `submitBuy(itemId: Int, qty: Int, pricePerEach: Int): Boolean` | GRAND_EXCHANGE_DISABLED |
| | `submitSell(slot: Int, pricePerEach: Int): Boolean` | GRAND_EXCHANGE_DISABLED |
| | `collectAll(): Boolean` | GRAND_EXCHANGE_DISABLED |

**Total: ~38 action methods across 14 services.** Each method is 3–5 lines:

```kotlin
suspend fun open(): Boolean = withServiceCall(
    bus = bus,
    sessionIdProvider = sessionIdProvider,
    serviceName = "BankService",
    methodName  = "open",
    restriction = OperationalRestriction.BANK_DISABLED
) {
    backend.open()
}
```

**Targetable / Tile / Spell / Prayer / TileObject / Npc / TileItem / ChatChannel** — all type aliases or imports from `vital.api.entities` / `vital.api.ui`. The wrappers do not redefine the domain model; they just thread it through.

**WalkerService / LoginService backends** delegate into the existing VitalPlugins/walker and VitalPlugins/autologin plugins (currently sibling subprojects in the same Gradle build). Implementation note: these backends' `VitalApi*Backend` files actually go through the walker/autologin plugin APIs, not raw VitalAPI. The naming is for consistency; the plan will call this out.

## 6. Wiring

`ServiceBootstrap.install(bus, sessionIdProvider, restrictionEngine)`:
1. For each of the 14 services: set `backend = VitalApi<X>Backend`, `bus = bus`, `sessionIdProvider = sessionIdProvider`.
2. Set `RestrictionGate.engine = restrictionEngine`.

Called from `BuildCorePlugin.startUp` after `AntibanBootstrap.install`. The `restrictionEngine` for v1 comes from a hard-coded `StaticRestrictionEngine(emptySet())` (no restrictions); Plan 7 wires the real profile-derived engine.

`resetForTests()` clears all 14 service fields and resets `RestrictionGate.engine` and `ServiceCallContext.counter`.

## 7. Testing

**Per service** (~14 files):
Each `*ServiceTest.kt`:
- Set `service.backend = Fake<X>Backend` (a recording/scriptable test double).
- Set `service.bus` to a `mockk<EventBus>` capturing `tryEmit` calls into a list.
- Set `service.sessionIdProvider = { sid }`.
- Per method:
  - **Happy path:** call → backend invoked once with correct args → 1× `ServiceCallStart` + 1× `ServiceCallEnd(SUCCESS)` with matching `callId`.
  - **Failure path:** fake backend returns `false` → outcome `FAILURE`.
  - **Exception path:** fake backend throws → outcome `EXCEPTION`, exception rethrown.
- Per restriction-gated method (one extra test):
  - Set `RestrictionGate.engine = StaticRestrictionEngine(setOf(restriction))` → call throws `RestrictionViolation`, outcome `RESTRICTED`, backend NOT invoked.

**Cross-cutting:**
- `WithServiceCallTest.kt` — direct unit test: callId monotonic, duration captured >= 0, outcome classification matches each branch (SUCCESS / FAILURE / RESTRICTED / EXCEPTION).
- `RestrictionGateTest.kt` — engine null = pass-through; engine present + restriction in set → throws `RestrictionViolation`; engine present + restriction not in set → no throw.

**Architecture / drift:**
- `LoggingArchitectureTest`'s `scrubberSampleCount`: **39 → 41** (2 new BusEvent subtypes).
- `PrivacyScrubberTest` samples extended for the 2 new subtypes.
- New `ServicesArchitectureTest`:
  1. Every `*Service.kt` under `core/services/<group>/` is a Kotlin `object`.
  2. Every `*Service.kt` declares a `@Volatile internal var backend:` field.
  3. Every public `suspend fun` body in a `*Service.kt` contains the literal substring `withServiceCall(`.
  4. No `*Service.kt` file imports `vital.api.*` directly (only `VitalApi*Backend.kt` files do).
  5. No `*Service.kt` file imports `vital.api.input.*` (Plan 4a's input arch test already enforces this; this is a sanity check).

**Estimated test count:**
- 14 services × ~3 tests/method × ~2.7 methods/service ≈ ~115
- Cross-cutting ≈ 8
- Architecture ≈ 5
**≈ 128 new tests, total → ~300.**

> **Spec-review correction:** the brainstorming Section 5 estimate of 68 was low — every method gets multiple test paths. The actual count is closer to ~115. Plan can budget accordingly.

## 8. Risks

- **VitalAPI surface drift.** If VitalAPI renames or removes a static method, the corresponding `VitalApi<X>Backend` breaks at compile time. Mitigation: backends are isolated in single files; one rename = one file to fix. Service tests use fakes and stay unaffected.
- **Walker / Autologin coupling.** WalkerService's backend depends on the sibling `walker` plugin's API surface (likely a `Walker` object with `walkTo(...)`). If that plugin's API changes, WalkerService breaks. Mitigation: single backend file isolates the coupling; document the contract in `VitalApiWalkerBackend.kt`'s KDoc.
- **`StaticRestrictionEngine(emptySet())` default at bootstrap** means no restrictions are enforced until Plan 7 lands. This is acceptable because no tasks ship in 5a — there is nothing to restrict. Plan 7 supersedes.
- **`callId` overflow.** A `Long` counter at 1 call/ms takes ~290 million years to overflow. Not a practical concern.
- **Inline + `suspend` block.** `withServiceCall` is `inline`. The block parameter `block: () -> T` is not `suspend`, but inline functions allow suspend inside the block when the caller is suspend. Verify with a compile test that `BankService.open` (a `suspend fun` calling `withServiceCall { backend.open() }` where `backend.open` is suspend) compiles.

## 9. Brainstorming resolutions

| # | Question | Resolution |
|---|----------|------------|
| 1 | Wrapper API breadth | Curated MVP per service (3–6 methods), expand on demand |
| 2 | RestrictionGate wiring | Per-method hardcoded `restriction = ...` parameter |
| 3 | Confidence hooks | Plan 6 subscribes to `ServiceCallEnd` events; no service→Confidence direct coupling in 5a |
| 4 | Service handoff to tasks | Kotlin `object` singletons with `@Volatile internal var` backends (Plan 4a pattern) |
| 5 | Read-only queries vs actions | Wrap actions only; tasks query VitalAPI directly |
| 6 | Testability of static VitalAPI | Backend interface per service + `VitalApi<X>Backend` delegate (Plan 4a pattern) |
| extra | Effect vs OperationalRestriction | Introduced separate `OperationalRestriction` enum; Plan 2's `Effect` stays as task-edit-time concern |

## 10. References

- Foundation spec §10 (Action Library): `2026-04-21-buildcore-foundation-design.md`
- Plan 2 (Effect / Restriction taxonomy): `2026-04-21-plan-2-core-runtime.md`
- Plan 3 (BusEvent / EventBus / PrivacyScrubber): `2026-04-23-plan-3-logging-eventbus.md`
- Plan 4a (object singleton + backend pattern): `2026-04-24-plan-4a-rng-personality-input.md`
- Plan 4b (PrecisionGate, two-tier privacy, scrubber count): `2026-04-25-plan-4b-precision-breaks-misclick.md`
- VitalAPI source: `C:\Code\VitalAPI\src\vital\api\` (containers/, entities/, ui/, world/)
