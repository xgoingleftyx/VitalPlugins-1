# BuildCore Plan 2 — Core Runtime + Task SPI + Restrictions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the L3/L4 stack — Task SPI (Task → Method → ExecutionPath with mandatory ironman path), Restriction Engine with archetype presets, task state machine + runner, retry policy, Safe-Stop Contract, graduated throttle, and a reference NoOpTask that demonstrably runs through the full lifecycle.

**Architecture:** Everything in Plan 2 is cross-cutting **framework** — no activity modules, no services, no real game interaction yet. Sealed taxonomies (Effect, Requirement, Restriction, ConfigSchema, StepResult, ValidationResult, RecoveryDecision) provide the type system future modules build on. PathSelector + MethodSelector are pure functions over those types. Runner is a single-threaded state-machine driver that emits bus events on every transition. Watchdog hooks are added (via `progressSignal()` and `canStopNow()`) but the watchdog thread itself waits for Plan 6. TaskContext is a minimal stub — Plan 5 will flesh it out with real services.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0, JUnit 5, MockK, Konsist. Built on Plan 1's event bus + FlatLaf shell.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md`](../specs/2026-04-21-buildcore-foundation-design.md) §6, §7, §8, §11, §16
- Plan 1 complete and merged to `main`: EventBus primitive at [`BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/EventBus.kt`](../../../BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/EventBus.kt)
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit direct to main and push to `origin` (xgoingleftyx fork) after every commit
- Author: Chich only — NO `Co-Authored-By` trailers
- Style: tabs, Allman braces where applicable, UTF-8

---

## File structure this plan produces

```
BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/
├── events/
│   └── BusEvent.kt                 # MODIFY — add 11 task-lifecycle event subtypes
├── restrictions/
│   ├── Restriction.kt              # CREATE — sealed taxonomy
│   ├── RestrictionSet.kt           # CREATE — add-only composition with mule tier override
│   ├── Archetype.kt                # CREATE — 7 preset bundles (HCIM flagged disabled)
│   ├── ViolationHandler.kt         # CREATE — enum of responses
│   └── RestrictionEngine.kt        # CREATE — three-moment validator
└── task/
    ├── Effect.kt                   # CREATE — sealed taxonomy
    ├── Requirement.kt              # CREATE — sealed taxonomy w/ AllOf/AnyOf/Not
    ├── ConfigSchema.kt             # CREATE — sealed ConfigField types
    ├── ProgressFingerprint.kt      # CREATE — data class
    ├── StepResult.kt               # CREATE — sealed (Continue / Complete / Fail)
    ├── ValidationResult.kt         # CREATE — sealed (Pass / Reject)
    ├── RecoveryDecision.kt         # CREATE — sealed
    ├── Criticality.kt              # CREATE — enum (CRITICAL_PATH / OPTIONAL)
    ├── Retry.kt                    # CREATE — RetryPolicy, Backoff, OnExhausted
    ├── ExecutionPath.kt            # CREATE — data class + PathKind enum
    ├── Method.kt                   # CREATE — interface
    ├── Task.kt                     # CREATE — interface
    ├── TaskContext.kt              # CREATE — minimal plan-2 version
    ├── TaskState.kt                # CREATE — 10-state enum
    ├── TaskInstance.kt             # CREATE — mutable wrapper
    ├── PathSelector.kt             # CREATE — pure algorithm
    ├── MethodSelector.kt           # CREATE — pure algorithm + RotationPolicy SPI
    ├── SafeStopContract.kt         # CREATE — stop orchestrator
    ├── GraduatedThrottle.kt        # CREATE — multiplier calc
    ├── ModuleRegistry.kt           # CREATE — simple Plan 2 registry
    ├── Runner.kt                   # CREATE — state-machine driver
    └── NoOpTask.kt                 # CREATE — reference task

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/restrictions/
│   ├── RestrictionSetCompositionTest.kt
│   └── RestrictionEngineTest.kt
├── core/task/
│   ├── PathSelectorTest.kt
│   ├── MethodSelectorTest.kt
│   ├── RetryPolicyTest.kt
│   ├── SafeStopContractTest.kt
│   ├── GraduatedThrottleTest.kt
│   ├── RunnerStateMachineTest.kt
│   └── NoOpTaskRunTest.kt
└── arch/
    └── TaskSpiArchitectureTest.kt   # additions: ironman path mandatory + no static state
```

---

## Phase 1 — Taxonomies (Tasks 1-4)

### Task 1 — Effect taxonomy

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Effect.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * What a task or method does to the account.
 *
 * Declared statically by task authors; consumed by the RestrictionEngine
 * to veto incompatible task+profile combinations at edit-time.
 *
 * Spec §7. Sealed — adding a new effect requires adding a case here
 * and updating architecture tests to cover it.
 */
sealed class Effect {
	data class GrantsXp(val skill: Skill, val rate: XpRange) : Effect()
	data class AcquiresItem(val itemId: Int, val qty: Int, val via: AcquisitionMethod) : Effect()
	data class SpendsGp(val estimated: GpRange) : Effect()
	data class EntersArea(val area: AreaTag) : Effect()
	data class CompletesQuest(val questId: QuestId) : Effect()
	data class RaisesCombatLevel(val delta: Int) : Effect()
	object RequiresMembership : Effect()
	data class TradesWithPlayer(val purpose: TradePurpose) : Effect()
	data class UsesGrandExchange(val action: GeAction) : Effect()
	data class CustomEffect(val tag: String) : Effect()
}

enum class Skill {
	ATTACK, STRENGTH, DEFENCE, HITPOINTS, RANGED, PRAYER, MAGIC,
	COOKING, WOODCUTTING, FLETCHING, FISHING, FIREMAKING, CRAFTING,
	SMITHING, MINING, HERBLORE, AGILITY, THIEVING, SLAYER, FARMING,
	RUNECRAFT, HUNTER, CONSTRUCTION
}

data class XpRange(val min: Long, val max: Long) {
	init { require(min >= 0 && max >= min) { "XpRange: invalid bounds min=$min max=$max" } }
}

data class GpRange(val min: Long, val max: Long) {
	init { require(min >= 0 && max >= min) { "GpRange: invalid bounds min=$min max=$max" } }
}

enum class AcquisitionMethod { MOB_DROP, SKILLING, GE, MULE, NPC_SHOP, QUEST, PLAYER_TRADE }

enum class AreaTag { SAFE_OVERWORLD, WILDERNESS, DUNGEON, PVP_WORLD, MINIGAME, INSTANCE }

enum class TradePurpose { MULE_RECEIVE_GP, MULE_RECEIVE_BOND, MULE_SEND_GP, MULE_RETURN }

enum class GeAction { BUY, SELL, COLLECT }

@JvmInline
value class QuestId(val raw: String) {
	init { require(raw.isNotBlank()) { "QuestId must not be blank" } }
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Effect.kt
git commit -m "BuildCore: add Effect sealed taxonomy (spec §7)"
git push origin main
```

---

### Task 2 — Requirement taxonomy

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Requirement.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * What a task or method needs to run. Checked at validation time.
 *
 * Composable via [AllOf] / [AnyOf] / [Not] for expressing quest prereqs
 * like "(Cooks Assistant complete) AND (Mining >= 15) AND NOT (Ironman
 * with no access to X)".
 *
 * Spec §7.
 */
sealed class Requirement {

	/** Predicate: is this requirement satisfied by the given state? */
	abstract fun isSatisfied(state: AccountState): Boolean

	data class StatLevel(val skill: Skill, val min: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.levels[skill] ?: 1) >= min
	}

	data class QuestComplete(val questId: QuestId) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.quests[questId] == QuestStatus.COMPLETE
	}

	data class QuestPartial(val questId: QuestId, val step: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.questStep[questId] ?: 0) >= step
	}

	data class ItemInInventory(val itemId: Int, val qty: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.inventory[itemId] ?: 0) >= qty
	}

	data class ItemInBank(val itemId: Int, val qty: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.bank[itemId] ?: 0) >= qty
	}

	data class ItemEquipped(val itemId: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.equipped.contains(itemId)
	}

	data class GpOnHand(val amount: Long) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.gpTotal >= amount
	}

	data class MembershipStatus(val required: Membership) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.membership == required || (required == Membership.F2P && state.membership == Membership.MEMBER)
	}

	data class InArea(val area: AreaTag) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.currentArea == area
	}

	data class AccountFlag(val flag: String) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.flags.contains(flag)
	}

	data class AllOf(val reqs: List<Requirement>) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = reqs.all { it.isSatisfied(state) }
	}

	data class AnyOf(val reqs: List<Requirement>) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = reqs.any { it.isSatisfied(state) }
	}

	data class Not(val req: Requirement) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = !req.isSatisfied(state)
	}
}

/**
 * Snapshot of account state used for Requirement evaluation. Plan 5
 * populates this from live game state via the services layer. Plan 2
 * uses it only in tests (hand-constructed) and the validator.
 */
data class AccountState(
	val levels: Map<Skill, Int> = emptyMap(),
	val quests: Map<QuestId, QuestStatus> = emptyMap(),
	val questStep: Map<QuestId, Int> = emptyMap(),
	val inventory: Map<Int, Int> = emptyMap(),
	val bank: Map<Int, Int> = emptyMap(),
	val equipped: Set<Int> = emptySet(),
	val gpTotal: Long = 0L,
	val membership: Membership = Membership.F2P,
	val currentArea: AreaTag = AreaTag.SAFE_OVERWORLD,
	val flags: Set<String> = emptySet()
)

enum class Membership { F2P, MEMBER }

enum class QuestStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Requirement.kt
git commit -m "BuildCore: add Requirement sealed taxonomy with AllOf/AnyOf/Not composition (spec §7)"
git push origin main
```

---

### Task 3 — Restriction taxonomy

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/Restriction.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.AreaTag
import net.vital.plugins.buildcore.core.task.Skill

/**
 * Account-style constraints enforced cross-cutting.
 *
 * Spec §8. Sealed — new cases require code change by design.
 *
 * An account profile has a Set<Restriction>. The RestrictionEngine
 * vetoes any Effect that violates any Restriction.
 *
 * Mule restrictions are mutually exclusive — exactly one of
 * [NoMuleInteraction] / [MuleBondsOnly] / [MuleFull] in every set.
 */
sealed class Restriction {

	// XP
	data class XpCap(val skill: Skill, val maxLevel: Int) : Restriction() {
		init { require(maxLevel in 1..99) { "XpCap: maxLevel must be 1..99, was $maxLevel" } }
	}
	data class XpForbidden(val skill: Skill) : Restriction()
	data class QpCap(val maxQp: Int) : Restriction() {
		init { require(maxQp >= 0) { "QpCap: maxQp must be non-negative" } }
	}

	// Economy
	object NoGrandExchange : Restriction() { override fun toString() = "NoGrandExchange" }
	object NoPlayerTrade : Restriction() { override fun toString() = "NoPlayerTrade" }
	object NoDroppedLoot : Restriction() { override fun toString() = "NoDroppedLoot" }

	// Mule tier (exactly one of these three per profile)
	object NoMuleInteraction : Restriction() { override fun toString() = "NoMuleInteraction" }
	object MuleBondsOnly : Restriction() { override fun toString() = "MuleBondsOnly" }
	object MuleFull : Restriction() { override fun toString() = "MuleFull" }

	// Area
	object NoWilderness : Restriction() { override fun toString() = "NoWilderness" }
	object NoPvP : Restriction() { override fun toString() = "NoPvP" }
	data class NoArea(val area: AreaTag) : Restriction()

	// Safety (HCIM layer — deferred activation per spec §5)
	data class HpFleeFloor(val percent: Int) : Restriction() {
		init { require(percent in 0..100) { "HpFleeFloor: percent must be 0..100" } }
	}
	object NoHighRiskCombat : Restriction() { override fun toString() = "NoHighRiskCombat" }
	object HcimSafetyBundle : Restriction() { override fun toString() = "HcimSafetyBundle" }

	// Feature-flag
	object NoQuestsBeyondRequirements : Restriction() { override fun toString() = "NoQuestsBeyondRequirements" }
	object NoTasksThatRaiseCombatLevel : Restriction() { override fun toString() = "NoTasksThatRaiseCombatLevel" }

	// Escape hatch — avoid; prefer adding a first-class case
	data class CustomFlag(val tag: String) : Restriction()
}

/** Categories used by RestrictionSet to enforce the "exactly one mule tier" invariant. */
internal fun Restriction.isMuleTier(): Boolean = this is Restriction.NoMuleInteraction
	|| this is Restriction.MuleBondsOnly
	|| this is Restriction.MuleFull
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/Restriction.kt
git commit -m "BuildCore: add Restriction sealed taxonomy with mule-tier invariant helper (spec §8)"
git push origin main
```

---

### Task 4 — RestrictionSet + Archetype presets (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionSet.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/Archetype.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionSetCompositionTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
// RestrictionSetCompositionTest.kt
package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Skill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestrictionSetCompositionTest {

	@Test
	fun `archetype base + additional merges to a single set`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.PURE_1DEF,
			additional = setOf(Restriction.NoWilderness),
			muleOverride = null
		)
		assertTrue(Restriction.XpCap(Skill.DEFENCE, 1) in set.restrictions)
		assertTrue(Restriction.NoWilderness in set.restrictions)
		assertTrue(Restriction.MuleFull in set.restrictions)
	}

	@Test
	fun `muleOverride replaces archetype mule tier`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.PURE_1DEF,
			additional = emptySet(),
			muleOverride = Restriction.NoMuleInteraction
		)
		assertTrue(Restriction.NoMuleInteraction in set.restrictions)
		assertTrue(Restriction.MuleFull !in set.restrictions)
	}

	@Test
	fun `profile cannot remove archetype-imposed restriction via additional (additional only adds)`() {
		val set = RestrictionSet.compose(
			archetype = Archetype.IRONMAN,
			additional = setOf(Restriction.NoWilderness),
			muleOverride = null
		)
		// Ironman's NoGrandExchange survives even though additional doesn't include it
		assertTrue(Restriction.NoGrandExchange in set.restrictions)
	}

	@Test
	fun `invalid muleOverride that is not a mule tier is rejected`() {
		val ex = assertThrows(IllegalArgumentException::class.java) {
			RestrictionSet.compose(
				archetype = Archetype.MAIN,
				additional = emptySet(),
				muleOverride = Restriction.NoWilderness
			)
		}
		assertTrue(ex.message!!.contains("mule tier"), "got: ${ex.message}")
	}

	@Test
	fun `exactly one mule tier present in every composed set`() {
		val sets = listOf(Archetype.MAIN, Archetype.PURE_1DEF, Archetype.IRONMAN)
			.map { RestrictionSet.compose(it, emptySet(), muleOverride = null) }

		sets.forEach { set ->
			val muleTiers = set.restrictions.filter { it.isMuleTier() }
			assertEquals(1, muleTiers.size, "expected 1 mule tier in $set, got $muleTiers")
		}
	}

	@Test
	fun `HCIM archetype is flagged disabled`() {
		assertEquals(false, Archetype.HCIM.enabled)
	}

	@Test
	fun `HCIM archetype still composes correctly for future use`() {
		val set = RestrictionSet.compose(Archetype.HCIM, emptySet(), muleOverride = null)
		assertTrue(Restriction.NoWilderness in set.restrictions)
		assertTrue(Restriction.NoHighRiskCombat in set.restrictions)
		assertTrue(Restriction.HcimSafetyBundle in set.restrictions)
	}
}
```

- [ ] **Step 2: Verify test fails with unresolved references**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.restrictions.RestrictionSetCompositionTest" 2>&1 | tail -15
```
Expected: compilation fails (`Archetype`, `RestrictionSet.compose` unresolved).

- [ ] **Step 3: Write Archetype.kt**

```kotlin
package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Skill

/**
 * Named preset bundle of [Restriction]s. Data, not code branches.
 *
 * Spec §5, §8.
 *
 * Adding a new archetype = adding an entry to [Archetype.builtins]. The
 * archetype's [baseRestrictions] MUST include exactly one mule tier.
 */
data class Archetype(
	val id: String,
	val displayName: String,
	val baseRestrictions: Set<Restriction>,
	val enabled: Boolean = true
) {
	init {
		val muleTiers = baseRestrictions.filter { it.isMuleTier() }
		require(muleTiers.size == 1) {
			"Archetype $id must have exactly one mule tier in baseRestrictions, got $muleTiers"
		}
	}

	companion object {
		val MAIN = Archetype(
			id = "main",
			displayName = "Main",
			baseRestrictions = setOf(Restriction.MuleFull)
		)

		val PURE_1DEF = Archetype(
			id = "pure.1def",
			displayName = "1 Defence Pure",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.DEFENCE, 1),
				Restriction.XpCap(Skill.PRAYER, 1),
				Restriction.MuleFull
			)
		)

		val PURE_60ATT = Archetype(
			id = "pure.60att",
			displayName = "60 Attack Pure",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.ATTACK, 60),
				Restriction.XpCap(Skill.DEFENCE, 1),
				Restriction.XpCap(Skill.PRAYER, 1),
				Restriction.MuleFull
			)
		)

		val ZERKER = Archetype(
			id = "zerker",
			displayName = "Berserker",
			baseRestrictions = setOf(
				Restriction.XpCap(Skill.DEFENCE, 45),
				Restriction.XpCap(Skill.PRAYER, 52),
				Restriction.XpCap(Skill.HITPOINTS, 80),
				Restriction.MuleFull
			)
		)

		val SKILLER = Archetype(
			id = "skiller",
			displayName = "Skiller",
			baseRestrictions = setOf(
				Restriction.XpForbidden(Skill.ATTACK),
				Restriction.XpForbidden(Skill.STRENGTH),
				Restriction.XpForbidden(Skill.DEFENCE),
				Restriction.XpForbidden(Skill.HITPOINTS),
				Restriction.XpForbidden(Skill.RANGED),
				Restriction.XpForbidden(Skill.MAGIC),
				Restriction.XpForbidden(Skill.PRAYER),
				Restriction.MuleFull
			)
		)

		val IRONMAN = Archetype(
			id = "ironman",
			displayName = "Ironman",
			baseRestrictions = setOf(
				Restriction.NoGrandExchange,
				Restriction.NoPlayerTrade,
				Restriction.NoDroppedLoot,
				Restriction.MuleBondsOnly
			)
		)

		val HCIM = Archetype(
			id = "hcim",
			displayName = "Hardcore Ironman",
			enabled = false,
			baseRestrictions = IRONMAN.baseRestrictions + setOf(
				Restriction.HcimSafetyBundle,
				Restriction.NoWilderness,
				Restriction.NoHighRiskCombat,
				Restriction.HpFleeFloor(50)
			)
		)

		val builtins: List<Archetype> = listOf(
			MAIN, PURE_1DEF, PURE_60ATT, ZERKER, SKILLER, IRONMAN, HCIM
		)

		fun findById(id: String): Archetype? = builtins.firstOrNull { it.id == id }
	}
}
```

- [ ] **Step 4: Write RestrictionSet.kt**

```kotlin
package net.vital.plugins.buildcore.core.restrictions

/**
 * The effective restriction set for a profile at a point in time.
 *
 * Composition rule (spec §8): profiles can only ADD restrictions to
 * their archetype's base set. The exception is the mule tier, which
 * is exactly one of three values — a profile may override that one
 * slot, but cannot remove it entirely.
 *
 * A [RestrictionSet] is immutable. Re-compose to change.
 */
data class RestrictionSet(val restrictions: Set<Restriction>) {

	init {
		val muleTiers = restrictions.filter { it.isMuleTier() }
		require(muleTiers.size == 1) {
			"RestrictionSet must contain exactly one mule tier, got $muleTiers"
		}
	}

	operator fun contains(r: Restriction): Boolean = r in restrictions

	companion object {
		/**
		 * Compose a RestrictionSet from an [archetype], an [additional]
		 * set of extra restrictions, and an optional [muleOverride].
		 *
		 * Rules:
		 *  - archetype.baseRestrictions is included in full
		 *  - additional is added (cannot override archetype)
		 *  - if muleOverride is non-null it REPLACES the archetype's
		 *    mule tier; it must itself be a mule-tier Restriction
		 */
		fun compose(
			archetype: Archetype,
			additional: Set<Restriction>,
			muleOverride: Restriction?
		): RestrictionSet {
			if (muleOverride != null) {
				require(muleOverride.isMuleTier()) {
					"muleOverride must be a mule tier restriction (NoMuleInteraction / MuleBondsOnly / MuleFull), got $muleOverride"
				}
			}
			val base = if (muleOverride != null) {
				archetype.baseRestrictions.filterNot { it.isMuleTier() }.toSet() + muleOverride
			} else {
				archetype.baseRestrictions
			}
			val combined = base + additional
			// additional MUST NOT override the mule tier; if it includes one, reject
			require(additional.none { it.isMuleTier() }) {
				"additional restrictions must not include a mule tier; use muleOverride instead"
			}
			return RestrictionSet(combined)
		}
	}
}
```

- [ ] **Step 5: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.restrictions.RestrictionSetCompositionTest" 2>&1 | tail -15
```
Expected: 7 tests PASSED.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/Archetype.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionSet.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionSetCompositionTest.kt
git commit -m "BuildCore: add RestrictionSet composition + 7 Archetype presets (spec §5, §8)"
git push origin main
```

---

## Phase 2 — Task SPI types (Tasks 5-11)

### Task 5 — ConfigSchema sealed types

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ConfigSchema.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Schema describing a task's or method's configurable fields.
 *
 * Rendered automatically by Plan 10's GUI. Task authors never write
 * Swing — they declare their config fields here and the single GUI
 * renderer produces the form.
 *
 * Spec §7.
 */
data class ConfigSchema(val fields: List<ConfigField>) {
	companion object {
		/** Empty schema for tasks/methods with no configurable fields. */
		val EMPTY: ConfigSchema = ConfigSchema(emptyList())
	}
}

sealed class ConfigField {
	abstract val key: String
	abstract val label: String

	data class IntRange(
		override val key: String,
		override val label: String,
		val min: Int,
		val max: Int,
		val default: Int
	) : ConfigField() {
		init {
			require(max >= min) { "IntRange '$key': max must be >= min" }
			require(default in min..max) { "IntRange '$key': default $default not in [$min, $max]" }
		}
	}

	data class Toggle(
		override val key: String,
		override val label: String,
		val default: Boolean
	) : ConfigField()

	data class ItemPicker(
		override val key: String,
		override val label: String,
		val filter: ItemFilter = ItemFilter.ANY
	) : ConfigField()

	data class LocationPicker(
		override val key: String,
		override val label: String,
		val presets: List<LocationPreset> = emptyList()
	) : ConfigField()

	data class Enum(
		override val key: String,
		override val label: String,
		val options: List<String>,
		val default: String
	) : ConfigField() {
		init {
			require(options.isNotEmpty()) { "Enum '$key': options must not be empty" }
			require(default in options) { "Enum '$key': default '$default' not in $options" }
		}
	}

	data class CompletionPredicate(
		override val key: String,
		override val label: String,
		val defaults: List<PredicateTemplate> = emptyList()
	) : ConfigField()

	/**
	 * Wrapper that reveals [inner] only when the profile's current config
	 * satisfies [conditionKey] [conditionValue]. Used to hide dependent
	 * fields until their parent field is set.
	 */
	data class VisibleWhen(
		val conditionKey: String,
		val conditionValue: Any,
		val inner: ConfigField
	) : ConfigField() {
		override val key: String get() = inner.key
		override val label: String get() = inner.label
	}
}

/** What kind of items an ItemPicker presents. */
enum class ItemFilter { ANY, FOOD, POTION, WEAPON, ARMOR, TOOL, RESOURCE }

data class LocationPreset(val label: String, val worldX: Int, val worldY: Int, val plane: Int)

/** Predicate templates populated by Plan 2 with common options; expanded later. */
enum class PredicateTemplate {
	UNTIL_LEVEL,
	UNTIL_QUEST_COMPLETE,
	UNTIL_ITEM_QUANTITY,
	UNTIL_TIME_ELAPSED,
	UNTIL_XP_GAINED
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ConfigSchema.kt
git commit -m "BuildCore: add ConfigSchema sealed types for GUI-renderable config (spec §7)"
git push origin main
```

---

### Task 6 — StepResult + ValidationResult + RecoveryDecision + ProgressFingerprint

**Files:** Create 4 files in `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/`:
- `StepResult.kt`
- `ValidationResult.kt`
- `RecoveryDecision.kt`
- `ProgressFingerprint.kt`

- [ ] **Step 1: Write StepResult.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration

/**
 * Returned by [Task.step] on every tick. Drives the Runner's loop.
 *
 * Spec §6.
 */
sealed class StepResult {
	/** Continue running; caller schedules next tick after [cooldown]. */
	data class Continue(val cooldown: Duration = Duration.ZERO) : StepResult()

	/** Task has completed its work; transition to STOPPING. */
	object Complete : StepResult() { override fun toString() = "Complete" }

	/**
	 * Task failed with [reason]. Retry policy decides whether to
	 * schedule another attempt or mark FAILED terminally.
	 */
	data class Fail(val reason: FailureReason, val recoverable: Boolean = true) : StepResult()

	/** Task requests pause (e.g., user intervention needed). */
	data class Pause(val reason: String) : StepResult()
}

/** Categorization of why a step failed. Used by retry policy and telemetry. */
sealed class FailureReason {
	data class Transient(val detail: String) : FailureReason()
	data class PermanentRequirementUnmet(val detail: String) : FailureReason()
	data class PermanentRestrictionViolated(val detail: String) : FailureReason()
	data class UnknownState(val detail: String) : FailureReason()
	data class Exception(val throwable: Throwable) : FailureReason()
	data class Custom(val tag: String, val detail: String) : FailureReason()
}
```

- [ ] **Step 2: Write ValidationResult.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Returned by [Task.validate]. Runner calls validate before STARTING.
 *
 * Spec §6, §7.
 */
sealed class ValidationResult {
	object Pass : ValidationResult() { override fun toString() = "Pass" }

	data class Reject(val reason: String, val kind: RejectKind) : ValidationResult()
}

enum class RejectKind {
	REQUIREMENT_UNMET,
	RESTRICTION_VIOLATED,
	INCOMPATIBLE_ARCHETYPE,
	CONFIG_INVALID,
	CUSTOM
}
```

- [ ] **Step 3: Write RecoveryDecision.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Returned by [Task.onUnknownState]. Tells the Runner what to do when
 * confidence drops or state is unrecognized.
 *
 * Spec §11 — full Recovery Pipeline is Plan 6. Plan 2 ships the
 * decision types so the Runner's state machine can be written with
 * recovery-aware transitions.
 */
sealed class RecoveryDecision {
	object ContinueStandardPipeline : RecoveryDecision() {
		override fun toString() = "ContinueStandardPipeline"
	}
	data class CustomSteps(val steps: List<RecoveryStep>) : RecoveryDecision()
	object FailImmediately : RecoveryDecision() {
		override fun toString() = "FailImmediately"
	}
	object Resume : RecoveryDecision() {
		override fun toString() = "Resume"
	}
}

/** A single step inside a task-custom recovery sequence. */
data class RecoveryStep(val description: String, val action: String, val args: Map<String, String> = emptyMap())
```

- [ ] **Step 4: Write ProgressFingerprint.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Snapshot of "progress signals" used by the watchdog to detect stalls.
 *
 * Two consecutive fingerprints that are equal means no progress. The
 * watchdog (Plan 6) flags a stall after N minutes of unchanged
 * fingerprints; default composition is provided by the [Task] abstract
 * base class, tasks may override to add task-specific signals.
 *
 * Spec §7, §11.
 */
data class ProgressFingerprint(
	val xpTotals: Map<Skill, Long> = emptyMap(),
	val inventoryHash: Int = 0,
	val playerTileHash: Int = 0,
	val openInterfaceId: Int? = null,
	val custom: Map<String, String> = emptyMap()
) {
	companion object {
		val EMPTY: ProgressFingerprint = ProgressFingerprint()
	}
}
```

- [ ] **Step 5: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/StepResult.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ValidationResult.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/RecoveryDecision.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ProgressFingerprint.kt
git commit -m "BuildCore: add StepResult, ValidationResult, RecoveryDecision, ProgressFingerprint (spec §6, §7, §11)"
git push origin main
```

---

### Task 7 — Criticality + Retry types

**Files:** Create 2 files:
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Criticality.kt`
- `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Retry.kt`

- [ ] **Step 1: Write Criticality.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Tags a task in a plan as either required-for-progression (CRITICAL_PATH)
 * or nice-to-have (OPTIONAL).
 *
 * Runner behavior on terminal failure:
 *  - CRITICAL_PATH → runner enters STOPPING mode; session-end.
 *  - OPTIONAL     → skip, runner picks next task.
 *
 * Spec §6.
 */
enum class Criticality {
	CRITICAL_PATH,
	OPTIONAL
}
```

- [ ] **Step 2: Write Retry.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Per-task retry policy.
 *
 * Spec §6. Defaults: 3 attempts, exponential backoff 30s→600s,
 * skip-if-optional-else-safe-stop on exhaustion.
 */
data class RetryPolicy(
	val maxAttempts: Int = 3,
	val backoff: Backoff = Backoff.Exponential(base = 30.seconds, max = 600.seconds),
	val onExhausted: OnExhausted = OnExhausted.SKIP_IF_OPTIONAL_ELSE_SAFE_STOP,
	val resetOnSuccess: Boolean = true
) {
	init {
		require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
	}

	companion object {
		val DEFAULT: RetryPolicy = RetryPolicy()
	}

	/**
	 * Compute the delay before attempt #[attemptNumber] (1-indexed).
	 * Attempt 1 has zero delay; subsequent attempts use [backoff].
	 */
	fun delayBefore(attemptNumber: Int): Duration {
		require(attemptNumber >= 1) { "attemptNumber is 1-indexed" }
		if (attemptNumber == 1) return Duration.ZERO
		return backoff.delayFor(attemptNumber)
	}
}

sealed class Backoff {
	abstract fun delayFor(attemptNumber: Int): Duration

	/** Constant [delay] between attempts. */
	data class Constant(val delay: Duration) : Backoff() {
		override fun delayFor(attemptNumber: Int): Duration = delay
	}

	/** Doubled each retry starting from [base], capped at [max]. */
	data class Exponential(val base: Duration, val max: Duration) : Backoff() {
		init { require(max >= base) { "max ($max) must be >= base ($base)" } }
		override fun delayFor(attemptNumber: Int): Duration {
			val factor = 1 shl (attemptNumber - 2).coerceAtLeast(0)
			val raw = base * factor
			return if (raw > max) max else raw
		}
	}
}

enum class OnExhausted {
	SKIP_IF_OPTIONAL_ELSE_SAFE_STOP,
	SAFE_STOP_ALWAYS,
	SKIP_ALWAYS
}
```

- [ ] **Step 3: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Criticality.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Retry.kt
git commit -m "BuildCore: add Criticality + RetryPolicy/Backoff/OnExhausted (spec §6)"
git push origin main
```

---

### Task 8 — ExecutionPath

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ExecutionPath.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Restriction

/**
 * A single economic variant of a [Method].
 *
 * Invariant: every [Method] MUST have exactly one path with
 * [kind] == [PathKind.IRONMAN] and empty [gatingRestrictions].
 * This is enforced by Plan 2's architecture tests and Plan 2's
 * Method constructor-time checks.
 *
 * Spec §4, §7.
 */
data class ExecutionPath(
	val id: PathId,
	val kind: PathKind,
	val effects: Set<Effect> = emptySet(),
	val requirements: Requirement? = null,
	val estimatedRate: XpPerHour = XpPerHour.ZERO,
	val gatingRestrictions: Set<Restriction> = emptySet()
)

@JvmInline
value class PathId(val raw: String) {
	init { require(raw.isNotBlank()) { "PathId must not be blank" } }
}

enum class PathKind {
	/** Self-gather; no economy shortcuts. ALWAYS required, never gated. */
	IRONMAN,
	/** Uses GE to buy inputs. Gated by Restriction.NoGrandExchange. */
	GE,
	/** Receives items/gp from mule. Gated by MuleBondsOnly / NoMuleInteraction. */
	MULE,
	/** Combination — e.g., buy coal from GE, self-mine tin. */
	HYBRID
}

/** Estimated training rate; used by Path/Method selectors to pick fastest allowed. */
data class XpPerHour(val value: Long) {
	init { require(value >= 0) { "XpPerHour must be non-negative" } }
	companion object {
		val ZERO: XpPerHour = XpPerHour(0)
	}
	operator fun compareTo(other: XpPerHour): Int = value.compareTo(other.value)
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ExecutionPath.kt
git commit -m "BuildCore: add ExecutionPath + PathKind + PathId + XpPerHour (spec §4, §7)"
git push origin main
```

---

### Task 9 — Method interface

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Method.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Concrete execution strategy for a [Task].
 *
 * A task ships multiple methods (e.g., "Mining" task has methods for
 * iron at various locations, coal, granite 3-tick, etc.). User picks
 * one or more per task in the plan.
 *
 * Invariant enforced in [validate]: exactly one IRONMAN path with
 * no gating restrictions.
 *
 * Spec §4, §7.
 */
interface Method {
	val id: MethodId
	val displayName: String
	val description: String
	val paths: List<ExecutionPath>
	val requirements: Requirement?
	val effects: Set<Effect>
	val config: ConfigSchema
	val locationFootprint: Set<AreaTag>
	val risk: RiskProfile

	fun estimatedRate(accountState: AccountState): XpPerHour

	/**
	 * Structural validation — enforces the ironman-path invariant.
	 * Called by [ModuleRegistry] at registration time and by
	 * [PathSelector] before each run.
	 */
	fun validateStructure(): ValidationResult {
		val ironmanPaths = paths.filter { it.kind == PathKind.IRONMAN }
		if (ironmanPaths.size != 1) {
			return ValidationResult.Reject(
				"Method '$id' must have exactly 1 IRONMAN path, has ${ironmanPaths.size}",
				RejectKind.CUSTOM
			)
		}
		if (ironmanPaths.single().gatingRestrictions.isNotEmpty()) {
			return ValidationResult.Reject(
				"Method '$id' IRONMAN path must have no gatingRestrictions",
				RejectKind.CUSTOM
			)
		}
		return ValidationResult.Pass
	}
}

@JvmInline
value class MethodId(val raw: String) {
	init { require(raw.isNotBlank()) { "MethodId must not be blank" } }
}

enum class RiskProfile { NONE, LOW, MEDIUM, HIGH }
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Method.kt
git commit -m "BuildCore: add Method interface with ironman-path structural validator (spec §7)"
git push origin main
```

---

### Task 10 — Task interface

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A unit of intent in a plan — e.g., "Train Mining", "Do Waterfall Quest".
 *
 * A task ships one or more [methods]; the user picks which ones to use
 * via the plan. The Runner selects a method per tick via MethodSelector,
 * then a path within that method via PathSelector.
 *
 * Spec §3, §6, §7.
 */
interface Task {
	val id: TaskId
	val displayName: String
	val version: SemVer
	val moduleId: ModuleId
	val config: ConfigSchema
	val methods: List<Method>

	/** Tasks may override to tighten the stall deadline. Default 5min. */
	val stallThreshold: Duration get() = 5.minutes

	fun validate(ctx: TaskContext): ValidationResult
	fun onStart(ctx: TaskContext)
	fun step(ctx: TaskContext): StepResult
	fun isComplete(ctx: TaskContext): Boolean
	fun safeStop(ctx: TaskContext)

	fun progressSignal(ctx: TaskContext): ProgressFingerprint

	/**
	 * Returns true only if stopping RIGHT NOW would not damage the
	 * account (e.g., no incoming combat damage, no mid-action loss).
	 *
	 * Default: conservative "no open interface, not in combat, HP OK".
	 * Tasks entering dangerous areas (wilderness, bossing) MUST override.
	 */
	fun canStopNow(ctx: TaskContext): Boolean

	/**
	 * Called by the Runner when the task enters RECOVERING state.
	 * Defaults to running the standard recovery pipeline (Plan 6).
	 */
	fun onUnknownState(ctx: TaskContext): RecoveryDecision =
		RecoveryDecision.ContinueStandardPipeline

	/** Structural validation — enforces each method's IRONMAN invariant. */
	fun validateStructure(): ValidationResult {
		methods.forEach { m ->
			val r = m.validateStructure()
			if (r is ValidationResult.Reject) return r
		}
		if (methods.isEmpty()) {
			return ValidationResult.Reject(
				"Task '$id' must have at least one method",
				RejectKind.CUSTOM
			)
		}
		return ValidationResult.Pass
	}
}

@JvmInline
value class TaskId(val raw: String) {
	init { require(raw.isNotBlank()) { "TaskId must not be blank" } }
}

@JvmInline
value class ModuleId(val raw: String) {
	init { require(raw.isNotBlank()) { "ModuleId must not be blank" } }
}

/** Simple major.minor.patch. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
	override fun compareTo(other: SemVer): Int = when {
		major != other.major -> major.compareTo(other.major)
		minor != other.minor -> minor.compareTo(other.minor)
		else -> patch.compareTo(other.patch)
	}
	override fun toString(): String = "$major.$minor.$patch"
	companion object {
		fun parse(raw: String): SemVer {
			val parts = raw.split(".").map { it.toInt() }
			require(parts.size == 3) { "SemVer must have 3 parts, got '$raw'" }
			return SemVer(parts[0], parts[1], parts[2])
		}
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Task.kt
git commit -m "BuildCore: add Task interface with canStopNow + validateStructure (spec §6, §7)"
git push origin main
```

---

### Task 11 — TaskContext (minimal Plan 2 version)

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskContext.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import java.util.UUID

/**
 * The context every Task receives per-call.
 *
 * Plan 2 ships a minimal version: just enough to let Runner + NoOpTask
 * prove the state machine works. Plan 5 adds services; Plan 6 adds
 * confidence queries; Plan 7 adds profile + plan access.
 *
 * Spec §6.
 */
interface TaskContext {
	val sessionId: UUID
	val taskInstanceId: UUID
	val restrictions: RestrictionSet
	val accountState: AccountState
	val eventBus: EventBus
	val attemptNumber: Int

	/** Tasks read their config via this map. Keys match [ConfigField.key]. */
	val taskConfig: Map<String, Any>
	val methodConfig: Map<String, Any>
}

/**
 * Plain, immutable implementation for Plan 2 + tests.
 * Plan 5 will introduce a richer implementation with service accessors.
 */
data class SimpleTaskContext(
	override val sessionId: UUID = UUID.randomUUID(),
	override val taskInstanceId: UUID = UUID.randomUUID(),
	override val restrictions: RestrictionSet,
	override val accountState: AccountState = AccountState(),
	override val eventBus: EventBus,
	override val attemptNumber: Int = 1,
	override val taskConfig: Map<String, Any> = emptyMap(),
	override val methodConfig: Map<String, Any> = emptyMap()
) : TaskContext
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskContext.kt
git commit -m "BuildCore: add minimal TaskContext + SimpleTaskContext for Plan 2 runner (spec §6)"
git push origin main
```

---

## Phase 3 — Restriction Engine (Tasks 12-13)

### Task 12 — ViolationHandler + RestrictionEngine (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/ViolationHandler.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionEngine.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionEngineTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
// RestrictionEngineTest.kt
package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Effect
import net.vital.plugins.buildcore.core.task.GeAction
import net.vital.plugins.buildcore.core.task.Skill
import net.vital.plugins.buildcore.core.task.XpRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestrictionEngineTest {

	private val ironman = RestrictionSet.compose(Archetype.IRONMAN, emptySet(), muleOverride = null)
	private val pure1def = RestrictionSet.compose(Archetype.PURE_1DEF, emptySet(), muleOverride = null)
	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `ironman cannot use GE`() {
		val effect = Effect.UsesGrandExchange(GeAction.BUY)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `main account can use GE`() {
		val effect = Effect.UsesGrandExchange(GeAction.BUY)
		val result = RestrictionEngine.isEffectAllowed(effect, main)
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `pure 1 def cannot gain defence XP that would push over level 1`() {
		val effect = Effect.GrantsXp(Skill.DEFENCE, XpRange(min = 100, max = 250))
		val result = RestrictionEngine.isEffectAllowed(effect, pure1def)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `pure can gain attack XP (no cap on attack)`() {
		val effect = Effect.GrantsXp(Skill.ATTACK, XpRange(min = 100, max = 250))
		val result = RestrictionEngine.isEffectAllowed(effect, pure1def)
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `skiller cannot gain any combat XP`() {
		val skiller = RestrictionSet.compose(Archetype.SKILLER, emptySet(), muleOverride = null)
		val effect = Effect.GrantsXp(Skill.STRENGTH, XpRange(min = 10, max = 20))
		val result = RestrictionEngine.isEffectAllowed(effect, skiller)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `ironman cannot receive items from players`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_GP)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `mule bonds only allows bond trade but not gp trade`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_BOND)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		// Ironman has MuleBondsOnly; receiving a bond IS allowed even though
		// NoPlayerTrade is present — the bond purpose overrides.
		assertEquals(RestrictionEngine.Result.Allowed, result)
	}

	@Test
	fun `mule bonds only forbids gp mule delivery`() {
		val effect = Effect.TradesWithPlayer(net.vital.plugins.buildcore.core.task.TradePurpose.MULE_RECEIVE_GP)
		val result = RestrictionEngine.isEffectAllowed(effect, ironman)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}

	@Test
	fun `wilderness-entering effect blocked by NoWilderness`() {
		val set = RestrictionSet.compose(
			Archetype.MAIN, setOf(Restriction.NoWilderness), muleOverride = null
		)
		val effect = Effect.EntersArea(net.vital.plugins.buildcore.core.task.AreaTag.WILDERNESS)
		val result = RestrictionEngine.isEffectAllowed(effect, set)
		assertTrue(result is RestrictionEngine.Result.Vetoed)
	}
}
```

- [ ] **Step 2: Run test — expect FAIL (compilation)**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.restrictions.RestrictionEngineTest" 2>&1 | tail -10
```
Expected: unresolved references to `ViolationHandler` / `RestrictionEngine`.

- [ ] **Step 3: Write ViolationHandler.kt**

```kotlin
package net.vital.plugins.buildcore.core.restrictions

/**
 * How the Runner responds to a restriction violation detected at
 * runtime (as opposed to edit-time, where the incompatible task is
 * hidden from the UI).
 *
 * Spec §8.
 */
enum class ViolationHandler {
	/** Task fails immediately with PermanentRestrictionViolated. */
	HARD_FAIL,

	/** Try the next ExecutionPath within the same method. */
	PATH_FALLBACK,

	/** Try the next Method within the same task. */
	METHOD_FALLBACK,

	/** Log and keep going (for purely advisory restrictions). */
	LOG_AND_CONTINUE
}
```

- [ ] **Step 4: Write RestrictionEngine.kt**

```kotlin
package net.vital.plugins.buildcore.core.restrictions

import net.vital.plugins.buildcore.core.task.Effect
import net.vital.plugins.buildcore.core.task.Skill
import net.vital.plugins.buildcore.core.task.TradePurpose

/**
 * Pure-function engine that decides whether a given [Effect] is
 * compatible with a given [RestrictionSet].
 *
 * Spec §8. No side effects, no mutable state.
 *
 * Usage:
 *   - Edit-time validator (GUI hides tasks whose methods' effects vetoes)
 *   - Plan-start validator (full plan re-check)
 *   - Runtime validator (per-step sanity check)
 *   - Path/Method selectors (pick highest-rate *allowed* alternative)
 */
object RestrictionEngine {

	sealed class Result {
		object Allowed : Result() { override fun toString() = "Allowed" }
		data class Vetoed(val by: Restriction, val reason: String) : Result()
	}

	/**
	 * Check a single effect against a single restriction set. Returns
	 * the first restriction that vetoes, or [Result.Allowed] if none.
	 */
	fun isEffectAllowed(effect: Effect, set: RestrictionSet): Result {
		set.restrictions.forEach { r ->
			val veto = vetoFor(effect, r, set)
			if (veto != null) return Result.Vetoed(r, veto)
		}
		return Result.Allowed
	}

	/**
	 * Decide whether the full bundle of effects is allowed. Returns
	 * the first violation found.
	 */
	fun areEffectsAllowed(effects: Collection<Effect>, set: RestrictionSet): Result {
		effects.forEach { e ->
			val r = isEffectAllowed(e, set)
			if (r is Result.Vetoed) return r
		}
		return Result.Allowed
	}

	/** Returns a non-null reason string if [r] vetoes [effect]. */
	private fun vetoFor(effect: Effect, r: Restriction, set: RestrictionSet): String? = when (r) {
		is Restriction.XpCap -> xpCapVeto(effect, r)
		is Restriction.XpForbidden -> xpForbiddenVeto(effect, r)
		is Restriction.QpCap -> qpCapVeto(effect, r)
		Restriction.NoGrandExchange -> noGeVeto(effect)
		Restriction.NoPlayerTrade -> noPlayerTradeVeto(effect, set)
		Restriction.NoDroppedLoot -> null // checked by Plan 5 services; no Effect case for this
		Restriction.NoMuleInteraction -> noMuleVeto(effect)
		Restriction.MuleBondsOnly -> muleBondsOnlyVeto(effect)
		Restriction.MuleFull -> null // permissive
		Restriction.NoWilderness -> wildernessVeto(effect)
		Restriction.NoPvP -> pvpVeto(effect)
		is Restriction.NoArea -> if (effect is Effect.EntersArea && effect.area == r.area) {
			"effect enters forbidden area ${r.area}"
		} else null
		is Restriction.HpFleeFloor -> null // checked by Plan 5 Health service
		Restriction.NoHighRiskCombat -> null // advisory; tasks opt-in via Effect.CustomEffect("highRiskCombat")
		Restriction.HcimSafetyBundle -> null // bundle; individual restrictions do the work
		Restriction.NoQuestsBeyondRequirements -> null // advisory
		Restriction.NoTasksThatRaiseCombatLevel -> if (effect is Effect.RaisesCombatLevel) {
			"effect raises combat level (blocked by NoTasksThatRaiseCombatLevel)"
		} else null
		is Restriction.CustomFlag -> null // by convention, tasks test custom flags themselves
	}

	private fun xpCapVeto(effect: Effect, r: Restriction.XpCap): String? =
		if (effect is Effect.GrantsXp && effect.skill == r.skill) {
			"task grants ${r.skill} XP (capped at level ${r.maxLevel})"
		} else null

	private fun xpForbiddenVeto(effect: Effect, r: Restriction.XpForbidden): String? =
		if (effect is Effect.GrantsXp && effect.skill == r.skill) {
			"task grants ${r.skill} XP (forbidden)"
		} else null

	private fun qpCapVeto(effect: Effect, r: Restriction.QpCap): String? = null
	// QP cap enforced by plan-time validator against cumulative plan QP, not per-effect

	private fun noGeVeto(effect: Effect): String? =
		if (effect is Effect.UsesGrandExchange) "task uses Grand Exchange (blocked)" else null

	private fun noPlayerTradeVeto(effect: Effect, set: RestrictionSet): String? {
		if (effect !is Effect.TradesWithPlayer) return null
		// Bond reception is permitted under MuleBondsOnly even with NoPlayerTrade present
		if (effect.purpose == TradePurpose.MULE_RECEIVE_BOND && Restriction.MuleBondsOnly in set.restrictions) return null
		return "task trades with another player (blocked by NoPlayerTrade)"
	}

	private fun noMuleVeto(effect: Effect): String? =
		if (effect is Effect.TradesWithPlayer && isMuleTrade(effect.purpose)) {
			"task mule-trades (blocked by NoMuleInteraction)"
		} else null

	private fun muleBondsOnlyVeto(effect: Effect): String? {
		if (effect !is Effect.TradesWithPlayer) return null
		if (!isMuleTrade(effect.purpose)) return null
		return if (effect.purpose == TradePurpose.MULE_RECEIVE_BOND) null else {
			"MuleBondsOnly forbids ${effect.purpose}"
		}
	}

	private fun isMuleTrade(p: TradePurpose): Boolean = when (p) {
		TradePurpose.MULE_RECEIVE_GP, TradePurpose.MULE_RECEIVE_BOND,
		TradePurpose.MULE_SEND_GP, TradePurpose.MULE_RETURN -> true
	}

	private fun wildernessVeto(effect: Effect): String? =
		if (effect is Effect.EntersArea && effect.area == net.vital.plugins.buildcore.core.task.AreaTag.WILDERNESS) {
			"effect enters wilderness (blocked)"
		} else null

	private fun pvpVeto(effect: Effect): String? =
		if (effect is Effect.EntersArea && effect.area == net.vital.plugins.buildcore.core.task.AreaTag.PVP_WORLD) {
			"effect enters PvP world (blocked)"
		} else null
}
```

- [ ] **Step 5: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.restrictions.RestrictionEngineTest" 2>&1 | tail -20
```
Expected: 9 tests PASSED.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/ViolationHandler.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionEngine.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/restrictions/RestrictionEngineTest.kt
git commit -m "BuildCore: add RestrictionEngine + ViolationHandler (spec §8)"
git push origin main
```

---

## Phase 4 — Selectors (Tasks 13-14)

### Task 13 — PathSelector (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/PathSelector.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/PathSelectorTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
// PathSelectorTest.kt
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.Restriction
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PathSelectorTest {

	private fun paths(): List<ExecutionPath> = listOf(
		ExecutionPath(
			id = PathId("ge"),
			kind = PathKind.GE,
			estimatedRate = XpPerHour(150_000),
			gatingRestrictions = setOf(Restriction.NoGrandExchange)
		),
		ExecutionPath(
			id = PathId("ironman"),
			kind = PathKind.IRONMAN,
			estimatedRate = XpPerHour(60_000)
		)
	)

	private val mainSet = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)
	private val ironmanSet = RestrictionSet.compose(Archetype.IRONMAN, emptySet(), muleOverride = null)
	private val mainNoGe = RestrictionSet.compose(Archetype.MAIN, setOf(Restriction.NoGrandExchange), muleOverride = null)

	@Test
	fun `main with GE allowed picks highest rate (GE path)`() {
		val picked = PathSelector.pick(paths(), mainSet, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.GE, picked!!.kind)
	}

	@Test
	fun `ironman falls back to IRONMAN path`() {
		val picked = PathSelector.pick(paths(), ironmanSet, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.IRONMAN, picked!!.kind)
	}

	@Test
	fun `main with user-disabled GE falls back to IRONMAN path`() {
		val picked = PathSelector.pick(paths(), mainNoGe, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.IRONMAN, picked!!.kind)
	}

	@Test
	fun `returns null when no path is allowed (e g requirements unmet on all)`() {
		val blockedPaths = listOf(
			ExecutionPath(
				id = PathId("only"),
				kind = PathKind.IRONMAN,
				requirements = Requirement.StatLevel(Skill.MINING, 85)
			)
		)
		val state = AccountState(levels = mapOf(Skill.MINING to 40))
		val picked = PathSelector.pick(blockedPaths, mainSet, state)
		assertNull(picked)
	}

	@Test
	fun `path with higher rate but unmet requirements is skipped`() {
		val pathSet = listOf(
			ExecutionPath(
				id = PathId("fast-but-locked"),
				kind = PathKind.GE,
				estimatedRate = XpPerHour(400_000),
				requirements = Requirement.StatLevel(Skill.SMITHING, 99),
				gatingRestrictions = setOf(Restriction.NoGrandExchange)
			),
			ExecutionPath(
				id = PathId("slow-but-ready"),
				kind = PathKind.IRONMAN,
				estimatedRate = XpPerHour(50_000)
			)
		)
		val state = AccountState(levels = mapOf(Skill.SMITHING to 15))
		val picked = PathSelector.pick(pathSet, mainSet, state)
		assertNotNull(picked)
		assertEquals(PathId("slow-but-ready"), picked!!.id)
	}
}
```

- [ ] **Step 2: Run test — expect FAIL (PathSelector unresolved)**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.PathSelectorTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write PathSelector.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.RestrictionSet

/**
 * Pure function: given a method's [paths], a profile's [restrictions],
 * and the current [accountState], pick the highest-rate allowed path
 * whose requirements are met.
 *
 * Contract (spec §4, §7):
 *   1. Sort paths by estimatedRate descending.
 *   2. Return the first path whose gatingRestrictions are all absent
 *      from [restrictions] AND whose requirements are all satisfied.
 *   3. If no path qualifies, return null.
 */
object PathSelector {
	fun pick(
		paths: List<ExecutionPath>,
		restrictions: RestrictionSet,
		accountState: AccountState
	): ExecutionPath? {
		return paths
			.sortedByDescending { it.estimatedRate.value }
			.firstOrNull { path ->
				pathIsAllowed(path, restrictions) && pathRequirementsMet(path, accountState)
			}
	}

	private fun pathIsAllowed(path: ExecutionPath, restrictions: RestrictionSet): Boolean =
		path.gatingRestrictions.none { it in restrictions }

	private fun pathRequirementsMet(path: ExecutionPath, state: AccountState): Boolean =
		path.requirements?.isSatisfied(state) ?: true
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.PathSelectorTest" 2>&1 | tail -10
```
Expected: 5 tests PASSED.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/PathSelector.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/PathSelectorTest.kt
git commit -m "BuildCore: add PathSelector (highest-rate-allowed-ready wins, deterministic) (spec §7)"
git push origin main
```

---

### Task 14 — MethodSelector + RotationPolicy (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/MethodSelector.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/MethodSelectorTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
// MethodSelectorTest.kt
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class MethodSelectorTest {

	private fun method(id: String, risk: RiskProfile = RiskProfile.NONE): Method = object : Method {
		override val id = MethodId(id)
		override val displayName = id
		override val description = id
		override val paths = listOf(
			ExecutionPath(PathId("$id.ironman"), PathKind.IRONMAN, estimatedRate = XpPerHour(50_000))
		)
		override val requirements: Requirement? = null
		override val effects: Set<Effect> = emptySet()
		override val config = ConfigSchema.EMPTY
		override val locationFootprint: Set<AreaTag> = emptySet()
		override val risk = risk
		override fun estimatedRate(accountState: AccountState) = XpPerHour(50_000)
	}

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `single selected method is always picked`() {
		val m = method("a")
		val sel = MethodSelector(
			allMethods = listOf(m),
			selectedIds = setOf(m.id),
			weights = mapOf(m.id to 1.0)
		)
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertEquals(m.id, picked!!.id)
	}

	@Test
	fun `empty selection returns null`() {
		val m = method("a")
		val sel = MethodSelector(allMethods = listOf(m), selectedIds = emptySet(), weights = emptyMap())
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertNull(picked)
	}

	@Test
	fun `WEIGHTED_NO_REPEAT rotation never picks same method twice in a row when multiple available`() {
		val a = method("a")
		val b = method("b")
		val sel = MethodSelector(
			allMethods = listOf(a, b),
			selectedIds = setOf(a.id, b.id),
			weights = mapOf(a.id to 0.99, b.id to 0.01)
		)

		// Force a pick for "a" first, then subsequent pick must be "b"
		val picked = sel.pickNext(main, AccountState(), lastPickedId = a.id, rotation = RotationPolicy.WEIGHTED_NO_REPEAT)
		assertNotNull(picked)
		assertNotEquals(a.id, picked!!.id)
	}

	@Test
	fun `method whose requirements are unmet is skipped`() {
		val a = method("a")
		val hardMethod = object : Method {
			override val id = MethodId("hard")
			override val displayName = "hard"
			override val description = "hard"
			override val paths = listOf(
				ExecutionPath(
					PathId("hard.ironman"),
					PathKind.IRONMAN,
					estimatedRate = XpPerHour(200_000),
					requirements = Requirement.StatLevel(Skill.SLAYER, 85)
				)
			)
			override val requirements = Requirement.StatLevel(Skill.SLAYER, 85)
			override val effects: Set<Effect> = emptySet()
			override val config = ConfigSchema.EMPTY
			override val locationFootprint: Set<AreaTag> = emptySet()
			override val risk = RiskProfile.NONE
			override fun estimatedRate(accountState: AccountState) = XpPerHour(200_000)
		}
		val sel = MethodSelector(
			allMethods = listOf(a, hardMethod),
			selectedIds = setOf(a.id, hardMethod.id),
			weights = mapOf(a.id to 1.0, hardMethod.id to 1.0)
		)
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertNotNull(picked)
		assertEquals(a.id, picked!!.id)
	}

	@Test
	fun `structurally-invalid method (multiple IRONMAN paths) is rejected before selection`() {
		val broken = object : Method {
			override val id = MethodId("broken")
			override val displayName = "broken"
			override val description = "broken"
			override val paths = listOf(
				ExecutionPath(PathId("a"), PathKind.IRONMAN),
				ExecutionPath(PathId("b"), PathKind.IRONMAN) // second ironman path — illegal
			)
			override val requirements: Requirement? = null
			override val effects: Set<Effect> = emptySet()
			override val config = ConfigSchema.EMPTY
			override val locationFootprint: Set<AreaTag> = emptySet()
			override val risk = RiskProfile.NONE
			override fun estimatedRate(accountState: AccountState) = XpPerHour(50_000)
		}
		assertTrue(broken.validateStructure() is ValidationResult.Reject)
	}
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.MethodSelectorTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write MethodSelector.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.RestrictionEngine
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet

/**
 * Picks which [Method] to run next for a task that has multiple
 * selected methods (rotation pool).
 *
 * Spec §7.
 *
 * Plan 2 provides two rotation policies:
 *  - [RotationPolicy.WEIGHTED]         — always pick by weight
 *  - [RotationPolicy.WEIGHTED_NO_REPEAT] — pick by weight, but skip
 *    the method that matches [lastPickedId] if another is available
 *
 * Plan 4 adds [RotationPolicy.WEIGHTED_WITH_ANTIBAN_BIAS] which further
 * perturbs weights by the seeded personality vector.
 */
class MethodSelector(
	private val allMethods: List<Method>,
	private val selectedIds: Set<MethodId>,
	private val weights: Map<MethodId, Double>,
	private val rngSeed: Long = 0L
) {
	fun pickNext(
		restrictions: RestrictionSet,
		accountState: AccountState,
		lastPickedId: MethodId?,
		rotation: RotationPolicy
	): Method? {
		val eligible = allMethods
			.filter { it.id in selectedIds }
			.filter { it.validateStructure() is ValidationResult.Pass }
			.filter { methodAllowedByRestrictions(it, restrictions) }
			.filter { methodRequirementsMet(it, accountState) }

		if (eligible.isEmpty()) return null
		if (eligible.size == 1) return eligible.single()

		val pool = when (rotation) {
			RotationPolicy.WEIGHTED -> eligible
			RotationPolicy.WEIGHTED_NO_REPEAT -> {
				val filtered = eligible.filter { it.id != lastPickedId }
				if (filtered.isEmpty()) eligible else filtered
			}
			RotationPolicy.WEIGHTED_WITH_ANTIBAN_BIAS -> eligible // Plan 4 extends
		}

		return weightedPick(pool)
	}

	private fun methodAllowedByRestrictions(method: Method, restrictions: RestrictionSet): Boolean {
		val effectSet = method.effects + method.paths.flatMap { it.effects }
		return RestrictionEngine.areEffectsAllowed(effectSet, restrictions) is RestrictionEngine.Result.Allowed
	}

	private fun methodRequirementsMet(method: Method, state: AccountState): Boolean =
		method.requirements?.isSatisfied(state) ?: true

	private fun weightedPick(pool: List<Method>): Method {
		val weighted = pool.map { it to (weights[it.id] ?: 1.0).coerceAtLeast(0.0) }
		val total = weighted.sumOf { it.second }
		if (total <= 0.0) return pool.random(kotlin.random.Random(rngSeed))
		val rand = kotlin.random.Random(rngSeed).nextDouble() * total
		var running = 0.0
		weighted.forEach { (m, w) ->
			running += w
			if (rand < running) return m
		}
		return weighted.last().first
	}
}

enum class RotationPolicy {
	WEIGHTED,
	WEIGHTED_NO_REPEAT,
	WEIGHTED_WITH_ANTIBAN_BIAS
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.MethodSelectorTest" 2>&1 | tail -10
```
Expected: 5 tests PASSED.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/MethodSelector.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/MethodSelectorTest.kt
git commit -m "BuildCore: add MethodSelector + RotationPolicy (WEIGHTED, NO_REPEAT, ANTIBAN_BIAS stub) (spec §7)"
git push origin main
```

---

## Phase 5 — Runner infrastructure (Tasks 15-23)

### Task 15 — TaskState enum + TaskInstance

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskState.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskInstance.kt`

- [ ] **Step 1: Write TaskState.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * States in a task instance's lifecycle.
 *
 * Spec §6.
 *
 * Transition table (runner enforces):
 *
 *   PENDING     → VALIDATE
 *   VALIDATE    → STARTING | FAILED
 *   STARTING    → RUNNING | FAILED
 *   RUNNING     → STOPPING | RECOVERING | FAILED | PAUSED
 *   RECOVERING  → RUNNING | FAILED
 *   STOPPING    → COMPLETED | FAILED
 *   PAUSED      → RUNNING | FAILED (on resume)
 *   COMPLETED   → (terminal)
 *   FAILED      → (terminal)
 */
enum class TaskState {
	PENDING,
	VALIDATE,
	STARTING,
	RUNNING,
	STOPPING,
	RECOVERING,
	COMPLETED,
	FAILED,
	PAUSED;

	val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
}
```

- [ ] **Step 2: Write TaskInstance.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable per-run wrapper around a [Task].
 *
 * Holds the runtime state (state, attemptNumber, lastFingerprint,
 * lastError). All mutations are serialized through the runner's
 * single-threaded execution loop, so no synchronization needed beyond
 * the atomic state reference (for watchdog reads).
 *
 * Spec §6.
 */
class TaskInstance(
	val id: UUID = UUID.randomUUID(),
	val task: Task,
	val criticality: Criticality,
	val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
) {
	private val _state = AtomicReference(TaskState.PENDING)
	val state: TaskState get() = _state.get()

	var attemptNumber: Int = 0
		internal set

	var lastFingerprint: ProgressFingerprint = ProgressFingerprint.EMPTY
		internal set

	var lastFailure: FailureReason? = null
		internal set

	/**
	 * Internal transition. Does NOT validate legality — the runner
	 * should check [canTransition] first and emit events around it.
	 */
	internal fun setState(next: TaskState) {
		_state.set(next)
	}

	/** Check whether [next] is a legal transition from the current state. */
	fun canTransition(next: TaskState): Boolean {
		val from = state
		return when (from) {
			TaskState.PENDING     -> next == TaskState.VALIDATE
			TaskState.VALIDATE    -> next == TaskState.STARTING || next == TaskState.FAILED
			TaskState.STARTING    -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.RUNNING     -> next == TaskState.STOPPING || next == TaskState.RECOVERING
				|| next == TaskState.FAILED || next == TaskState.PAUSED
			TaskState.RECOVERING  -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.STOPPING    -> next == TaskState.COMPLETED || next == TaskState.FAILED
			TaskState.PAUSED      -> next == TaskState.RUNNING || next == TaskState.FAILED
			TaskState.COMPLETED   -> false
			TaskState.FAILED      -> false
		}
	}

	override fun toString(): String =
		"TaskInstance(id=$id, task=${task.id}, state=$state, attempt=$attemptNumber)"
}
```

- [ ] **Step 3: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskState.kt \
         BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/TaskInstance.kt
git commit -m "BuildCore: add TaskState enum (10 states) + TaskInstance with transition table (spec §6)"
git push origin main
```

---

### Task 16 — Task lifecycle BusEvent subtypes (MODIFY existing file)

**Files:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1: Read current file**

Current contents (preserve everything):
```kotlin
package net.vital.plugins.buildcore.core.events

import java.time.Instant
import java.util.UUID

sealed interface BusEvent {
	val eventId: UUID
	val timestamp: Instant
	val sessionId: UUID
	val schemaVersion: Int
}

internal data class TestPing(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val payload: String
) : BusEvent
```

- [ ] **Step 2: Append task lifecycle events**

Add the following below `TestPing` in the same file (do not delete TestPing — Plan 3 removes it when the real taxonomy lands):

```kotlin

// ─────────────────────────────────────────────────────────────────────
// Task lifecycle events (spec §13)
// Plan 3 completes the full ~50-type taxonomy; Plan 2 adds only those
// emitted by the Runner during state transitions.
// ─────────────────────────────────────────────────────────────────────

data class TaskQueued(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class TaskValidated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val pass: Boolean,
	val rejectReason: String? = null
) : BusEvent

data class TaskStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val methodId: String,
	val pathId: String
) : BusEvent

data class TaskProgress(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class TaskCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val durationMillis: Long
) : BusEvent

data class TaskFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reasonType: String,
	val reasonDetail: String,
	val attemptNumber: Int
) : BusEvent

data class TaskRetrying(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val attemptNumber: Int,
	val backoffMillis: Long
) : BusEvent

data class TaskSkipped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskPaused(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskResumed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String
) : BusEvent

data class MethodPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val methodId: String
) : BusEvent

data class PathPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val taskInstanceId: UUID,
	val taskId: String,
	val pathId: String,
	val pathKind: String
) : BusEvent
```

- [ ] **Step 3: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 4: Verify architecture tests still pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.arch.LayeringTest" 2>&1 | tail -10
```
Expected: both arch tests still PASS — all new subtypes are data classes (immutable).

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt
git commit -m "BuildCore: add 11 task lifecycle events (TaskQueued..PathPicked) (spec §13)"
git push origin main
```

---

### Task 17 — RetryPolicy tests (TDD)

**Files:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RetryPolicyTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package net.vital.plugins.buildcore.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

	@Test
	fun `attempt 1 has zero delay`() {
		val policy = RetryPolicy()
		assertEquals(Duration.ZERO, policy.delayBefore(1))
	}

	@Test
	fun `exponential backoff doubles each attempt capped at max`() {
		val policy = RetryPolicy(
			backoff = Backoff.Exponential(base = 10.seconds, max = 200.seconds)
		)
		assertEquals(10.seconds, policy.delayBefore(2))
		assertEquals(20.seconds, policy.delayBefore(3))
		assertEquals(40.seconds, policy.delayBefore(4))
		assertEquals(80.seconds, policy.delayBefore(5))
		assertEquals(160.seconds, policy.delayBefore(6))
		assertEquals(200.seconds, policy.delayBefore(7)) // capped
		assertEquals(200.seconds, policy.delayBefore(10)) // still capped
	}

	@Test
	fun `constant backoff returns same delay each attempt`() {
		val policy = RetryPolicy(backoff = Backoff.Constant(45.seconds))
		assertEquals(Duration.ZERO, policy.delayBefore(1))
		assertEquals(45.seconds, policy.delayBefore(2))
		assertEquals(45.seconds, policy.delayBefore(3))
	}

	@Test
	fun `delayBefore rejects zero or negative attempt numbers`() {
		val policy = RetryPolicy()
		assertThrows(IllegalArgumentException::class.java) { policy.delayBefore(0) }
		assertThrows(IllegalArgumentException::class.java) { policy.delayBefore(-1) }
	}

	@Test
	fun `exponential backoff rejects max less than base`() {
		assertThrows(IllegalArgumentException::class.java) {
			Backoff.Exponential(base = 100.seconds, max = 10.seconds)
		}
	}

	@Test
	fun `maxAttempts must be at least 1`() {
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(maxAttempts = 0)
		}
	}
}
```

- [ ] **Step 2: Run tests — expect PASS (no new code needed, types already written)**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.RetryPolicyTest" 2>&1 | tail -10
```
Expected: 6 tests PASSED.

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RetryPolicyTest.kt
git commit -m "BuildCore: add RetryPolicy + Backoff unit tests (spec §6)"
git push origin main
```

---

### Task 18 — SafeStopContract orchestrator (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContract.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContractTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SafeStopContractTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	private fun stubTask(canStopNowResults: List<Boolean>, safeStopRunsCounter: IntArray = intArrayOf(0)): Task {
		val iter = canStopNowResults.iterator()
		return object : Task {
			override val id = TaskId("stub")
			override val displayName = "stub"
			override val version = SemVer(0, 0, 1)
			override val moduleId = ModuleId("test")
			override val config = ConfigSchema.EMPTY
			override val methods = listOf(stubMethod())
			override fun validate(ctx: TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: TaskContext) {}
			override fun step(ctx: TaskContext) = StepResult.Continue()
			override fun isComplete(ctx: TaskContext) = false
			override fun safeStop(ctx: TaskContext) { safeStopRunsCounter[0]++ }
			override fun progressSignal(ctx: TaskContext) = ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: TaskContext): Boolean =
				if (iter.hasNext()) iter.next() else true
		}
	}

	private fun stubMethod(): Method = object : Method {
		override val id = MethodId("stub.m")
		override val displayName = "stub.m"
		override val description = "stub.m"
		override val paths = listOf(ExecutionPath(PathId("stub.m.iron"), PathKind.IRONMAN))
		override val requirements: Requirement? = null
		override val effects: Set<Effect> = emptySet()
		override val config = ConfigSchema.EMPTY
		override val locationFootprint: Set<AreaTag> = emptySet()
		override val risk = RiskProfile.NONE
		override fun estimatedRate(accountState: AccountState) = XpPerHour.ZERO
	}

	@Test
	fun `safe-stop waits for canStopNow to return true`() = runTest(UnconfinedTestDispatcher()) {
		val ctr = intArrayOf(0)
		val task = stubTask(canStopNowResults = listOf(false, false, true), safeStopRunsCounter = ctr)
		val instance = TaskInstance(task = task, criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.RUNNING)
		val bus = EventBus()
		val ctx = SimpleTaskContext(restrictions = main, eventBus = bus)

		val result = SafeStopContract.perform(
			instance = instance,
			ctx = ctx,
			pollInterval = 1.milliseconds,
			maxDefer = 1000.milliseconds
		)

		assertTrue(result.completed)
		assertEquals(1, ctr[0]) // safeStop() invoked once
	}

	@Test
	fun `safe-stop gives up after maxDefer and still runs safeStop`() = runTest(UnconfinedTestDispatcher()) {
		val ctr = intArrayOf(0)
		// Returns false forever
		val task = stubTask(canStopNowResults = List(100) { false }, safeStopRunsCounter = ctr)
		val instance = TaskInstance(task = task, criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.RUNNING)
		val bus = EventBus()
		val ctx = SimpleTaskContext(restrictions = main, eventBus = bus)

		val result = SafeStopContract.perform(
			instance = instance,
			ctx = ctx,
			pollInterval = 1.milliseconds,
			maxDefer = 10.milliseconds
		)

		assertEquals(false, result.canStopNowReachedTrue)
		assertTrue(result.completed)
		assertEquals(1, ctr[0])
	}
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.SafeStopContractTest" 2>&1 | tail -5
```

- [ ] **Step 3: Write SafeStopContract.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Orchestrates the Safe-Stop Contract (spec §6).
 *
 * When a stop is requested, we poll the task's [Task.canStopNow] up to
 * [maxDefer]. If it becomes true, we transition the instance to
 * [TaskState.STOPPING] and call [Task.safeStop]. If [maxDefer] elapses
 * without canStopNow returning true, we STILL call [Task.safeStop] —
 * "best-effort safe" is better than an abrupt kill. We record that
 * maxDefer was exceeded in the returned [Outcome].
 *
 * Hard exceptions (unhandled throwable in canStopNow, safeStop) are
 * the only paths that skip this contract — documented in spec §6.
 */
object SafeStopContract {

	data class Outcome(
		val completed: Boolean,
		val canStopNowReachedTrue: Boolean,
		val maxDeferExceeded: Boolean
	)

	suspend fun perform(
		instance: TaskInstance,
		ctx: TaskContext,
		pollInterval: Duration,
		maxDefer: Duration
	): Outcome {
		require(pollInterval > Duration.ZERO) { "pollInterval must be positive" }
		require(maxDefer >= Duration.ZERO) { "maxDefer must be non-negative" }

		val deadline = System.nanoTime() + maxDefer.inWholeNanoseconds
		var canStopReached = false
		while (System.nanoTime() < deadline) {
			if (instance.task.canStopNow(ctx)) {
				canStopReached = true
				break
			}
			delay(pollInterval)
		}

		// Transition to STOPPING and run the task's safe-stop routine.
		if (instance.canTransition(TaskState.STOPPING)) {
			instance.setState(TaskState.STOPPING)
		}
		instance.task.safeStop(ctx)

		return Outcome(
			completed = true,
			canStopNowReachedTrue = canStopReached,
			maxDeferExceeded = !canStopReached
		)
	}
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.SafeStopContractTest" 2>&1 | tail -10
```
Expected: 2 tests PASSED.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContract.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContractTest.kt
git commit -m "BuildCore: add SafeStopContract orchestrator (polls canStopNow, runs safeStop) (spec §6)"
git push origin main
```

---

### Task 19 — GraduatedThrottle (TDD)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottle.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottleTest.kt`

- [ ] **Step 1: Write the failing test first**

```kotlin
package net.vital.plugins.buildcore.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class GraduatedThrottleTest {

	@Test
	fun `throttle active when account is fresh by XP`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle active when account created within 24h`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofHours(12)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle active when explicit fresh flag is set`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 10_000_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(365)),
			explicitFreshFlag = true,
			now = Instant.now()
		)
		assertTrue(t.active)
	}

	@Test
	fun `throttle inactive when account is established`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertFalse(t.active)
	}

	@Test
	fun `active throttle has reaction multiplier 1_5`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(1.5, t.reactionMultiplier, 0.0001)
	}

	@Test
	fun `inactive throttle has multiplier 1_0`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 500_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(1.0, t.reactionMultiplier, 0.0001)
	}

	@Test
	fun `active throttle reduces XP-per-hour cap to 60 percent`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(0.6, t.xpCapMultiplier, 0.0001)
	}

	@Test
	fun `active throttle reduces task-switch cap to 60 percent`() {
		val t = GraduatedThrottle.evaluate(
			accountTotalXp = 50_000L,
			accountCreatedAt = Instant.now().minus(Duration.ofDays(30)),
			explicitFreshFlag = false,
			now = Instant.now()
		)
		assertEquals(0.6, t.taskSwitchRateMultiplier, 0.0001)
	}
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.GraduatedThrottleTest" 2>&1 | tail -5
```

- [ ] **Step 3: Write GraduatedThrottle.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import java.time.Duration
import java.time.Instant

/**
 * Fresh-account throttle (spec §6).
 *
 * Pure calculation — given account metrics, returns a [State] indicating
 * whether to throttle AND the multipliers to apply across the runtime.
 *
 * Triggers (ANY → active):
 *   - totalXp < 100_000
 *   - accountCreatedAt within last 24 hours
 *   - explicitFreshFlag = true
 *
 * While active:
 *   - reactionMultiplier = 1.5 (slower reactions)
 *   - xpCapMultiplier = 0.6 (max 60% of declared rate)
 *   - taskSwitchRateMultiplier = 0.6 (40% fewer task switches/hr)
 *
 * Plan 4 will multiply these into the antiban sampling distributions.
 * Plan 2 just produces the values.
 */
object GraduatedThrottle {

	private val FRESH_AGE_THRESHOLD: Duration = Duration.ofHours(24)
	private const val FRESH_XP_THRESHOLD: Long = 100_000L

	data class State(
		val active: Boolean,
		val reactionMultiplier: Double,
		val xpCapMultiplier: Double,
		val taskSwitchRateMultiplier: Double,
		val reason: String
	)

	fun evaluate(
		accountTotalXp: Long,
		accountCreatedAt: Instant?,
		explicitFreshFlag: Boolean,
		now: Instant
	): State {
		val lowXp = accountTotalXp < FRESH_XP_THRESHOLD
		val fresh = accountCreatedAt != null
			&& Duration.between(accountCreatedAt, now).abs() < FRESH_AGE_THRESHOLD

		val active = lowXp || fresh || explicitFreshFlag
		val reason = buildString {
			if (lowXp) append("low total XP ($accountTotalXp < $FRESH_XP_THRESHOLD); ")
			if (fresh) append("account age < 24h; ")
			if (explicitFreshFlag) append("explicit fresh flag set; ")
			if (!active) append("established")
		}.trimEnd(';', ' ')

		return if (active) {
			State(true, 1.5, 0.6, 0.6, reason)
		} else {
			State(false, 1.0, 1.0, 1.0, reason)
		}
	}
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.task.GraduatedThrottleTest" 2>&1 | tail -10
```
Expected: 8 tests PASSED.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottle.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottleTest.kt
git commit -m "BuildCore: add GraduatedThrottle (fresh-account multipliers) (spec §6)"
git push origin main
```

---

### Task 20 — ModuleRegistry (simple Plan 2 version)

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ModuleRegistry.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Plan 2's simplest possible module registry.
 *
 * Holds all currently-registered [Task] implementations in memory.
 * Plan 7's plan-loading validator consults this registry to resolve
 * taskId strings in Plans.
 *
 * No hot-loading, no classpath scanning — explicit register() calls
 * only. Plan 7 may extend this with an annotation-processor-generated
 * auto-register hook.
 *
 * Thread-safety: intended to be populated at startup before the Runner
 * begins. Once registered, tasks are read-only.
 */
class ModuleRegistry {

	private val tasks = mutableMapOf<TaskId, Task>()

	fun register(task: Task): ModuleRegistry {
		val validation = task.validateStructure()
		require(validation is ValidationResult.Pass) {
			"Cannot register task '${task.id}': $validation"
		}
		require(task.id !in tasks) {
			"Task '${task.id}' is already registered"
		}
		tasks[task.id] = task
		return this
	}

	fun unregisterAll() {
		tasks.clear()
	}

	fun findById(id: TaskId): Task? = tasks[id]

	fun all(): List<Task> = tasks.values.toList()

	fun size(): Int = tasks.size
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ModuleRegistry.kt
git commit -m "BuildCore: add ModuleRegistry (simple in-memory Task registry) (spec §7)"
git push origin main
```

---

### Task 21 — NoOpTask reference implementation

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/NoOpTask.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

/**
 * Reference task: does nothing useful, but ticks through the full
 * state machine. Used by Plan 2's RunnerStateMachineTest and as a
 * smoke-test vehicle for Plan 3's logging/telemetry subscribers.
 *
 * The task "completes" after [tickBudget] successful [step] calls,
 * simulating a workload.
 *
 * Spec §6 (reference task goal).
 */
class NoOpTask(
	override val id: TaskId = TaskId("buildcore.noop"),
	override val displayName: String = "No-op Task",
	override val version: SemVer = SemVer(1, 0, 0),
	override val moduleId: ModuleId = ModuleId("buildcore.core"),
	private val tickBudget: Int = 3
) : Task {

	override val config: ConfigSchema = ConfigSchema.EMPTY

	override val methods: List<Method> = listOf(NoOpMethod)

	private var ticksTaken: Int = 0

	override fun validate(ctx: TaskContext): ValidationResult = ValidationResult.Pass

	override fun onStart(ctx: TaskContext) {
		ticksTaken = 0
	}

	override fun step(ctx: TaskContext): StepResult {
		ticksTaken += 1
		return if (ticksTaken >= tickBudget) StepResult.Complete else StepResult.Continue()
	}

	override fun isComplete(ctx: TaskContext): Boolean = ticksTaken >= tickBudget

	override fun safeStop(ctx: TaskContext) {
		// No-op task has nothing to clean up
	}

	override fun progressSignal(ctx: TaskContext): ProgressFingerprint =
		ProgressFingerprint(custom = mapOf("ticks" to ticksTaken.toString()))

	override fun canStopNow(ctx: TaskContext): Boolean = true
}

private object NoOpMethod : Method {
	override val id = MethodId("buildcore.noop.default")
	override val displayName = "Default"
	override val description = "Tick and complete"
	override val paths = listOf(
		ExecutionPath(
			id = PathId("buildcore.noop.default.ironman"),
			kind = PathKind.IRONMAN,
			estimatedRate = XpPerHour.ZERO
		)
	)
	override val requirements: Requirement? = null
	override val effects: Set<Effect> = emptySet()
	override val config = ConfigSchema.EMPTY
	override val locationFootprint: Set<AreaTag> = emptySet()
	override val risk = RiskProfile.NONE
	override fun estimatedRate(accountState: AccountState) = XpPerHour.ZERO
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/NoOpTask.kt
git commit -m "BuildCore: add NoOpTask reference implementation (spec §6)"
git push origin main
```

---

### Task 22 — Runner (state-machine driver) — part 1: API + basic loop

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt`

The Runner is large enough that this task only creates the skeleton — Task 23 tests its state transitions to validate the logic.

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.MethodPicked
import net.vital.plugins.buildcore.core.events.PathPicked
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskProgress
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single-threaded state-machine driver for a task instance.
 *
 * Plan 2's Runner handles one [TaskInstance] at a time through its
 * entire lifecycle and returns terminal state. Plan 2 does NOT
 * implement the plan-queue scheduler yet — that's part of Plan 7
 * (config/profile system) which loads the ordered task list.
 *
 * Lifecycle invocation order:
 *   PENDING → VALIDATE → STARTING → RUNNING (loop) → STOPPING → COMPLETED
 *
 * On [StepResult.Fail]:
 *   - if retries available: delay(backoff), increment attemptNumber, back to STARTING
 *   - else: STOPPING (for CRITICAL_PATH) or skip (for OPTIONAL) → terminal
 *
 * Spec §6.
 */
class Runner(
	private val bus: EventBus,
	private val sessionId: java.util.UUID = java.util.UUID.randomUUID()
) {

	/**
	 * Run [instance] through its lifecycle until a terminal state.
	 * Returns the terminal [TaskState] (COMPLETED, FAILED, or skipped-as-FAILED).
	 */
	suspend fun run(
		instance: TaskInstance,
		restrictions: RestrictionSet,
		accountState: AccountState = AccountState(),
		taskConfig: Map<String, Any> = emptyMap(),
		methodConfig: Map<String, Any> = emptyMap()
	): TaskState {
		val startNanos = System.nanoTime()

		// PENDING → VALIDATE
		transition(instance, TaskState.VALIDATE)

		val ctx = SimpleTaskContext(
			sessionId = sessionId,
			taskInstanceId = instance.id,
			restrictions = restrictions,
			accountState = accountState,
			eventBus = bus,
			attemptNumber = instance.attemptNumber + 1,
			taskConfig = taskConfig,
			methodConfig = methodConfig
		)

		val validation = instance.task.validate(ctx)
		bus.emit(
			TaskValidated(
				sessionId = sessionId,
				taskInstanceId = instance.id,
				taskId = instance.task.id.raw,
				pass = validation is ValidationResult.Pass,
				rejectReason = (validation as? ValidationResult.Reject)?.reason
			)
		)
		if (validation is ValidationResult.Reject) {
			return fail(instance, "VALIDATE_REJECT", validation.reason, startNanos)
		}

		while (instance.attemptNumber < instance.retryPolicy.maxAttempts) {
			instance.attemptNumber += 1

			// Apply backoff before attempt 2+
			val delayBefore = instance.retryPolicy.delayBefore(instance.attemptNumber)
			if (delayBefore.inWholeMilliseconds > 0) {
				bus.emit(
					TaskRetrying(
						sessionId = sessionId,
						taskInstanceId = instance.id,
						taskId = instance.task.id.raw,
						attemptNumber = instance.attemptNumber,
						backoffMillis = delayBefore.inWholeMilliseconds
					)
				)
				delay(delayBefore)
			}

			// Pick a method + path. Plan 2: single-method tasks pick the sole method.
			val method = instance.task.methods.firstOrNull()
				?: return fail(instance, "NO_METHODS", "task has no methods", startNanos)
			bus.emit(
				MethodPicked(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					methodId = method.id.raw
				)
			)

			val path = PathSelector.pick(method.paths, restrictions, accountState)
				?: return fail(instance, "NO_ALLOWED_PATH", "no path allowed by restrictions/reqs", startNanos)
			bus.emit(
				PathPicked(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					pathId = path.id.raw,
					pathKind = path.kind.name
				)
			)

			// STARTING
			if (!instance.canTransition(TaskState.STARTING)) return instance.state
			transition(instance, TaskState.STARTING)
			instance.task.onStart(ctx)
			bus.emit(
				TaskStarted(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					methodId = method.id.raw,
					pathId = path.id.raw
				)
			)

			// RUNNING loop
			transition(instance, TaskState.RUNNING)
			val running = runLoop(instance, ctx)

			if (running.success) {
				// STOPPING → COMPLETED
				transition(instance, TaskState.STOPPING)
				instance.task.safeStop(ctx)
				transition(instance, TaskState.COMPLETED)
				bus.emit(
					TaskCompleted(
						sessionId = sessionId,
						taskInstanceId = instance.id,
						taskId = instance.task.id.raw,
						durationMillis = (System.nanoTime() - startNanos) / 1_000_000
					)
				)
				return TaskState.COMPLETED
			}

			if (!running.retryable) {
				return fail(instance, running.failureType, running.failureDetail, startNanos)
			}
			// Otherwise loop to next attempt
		}

		// Retries exhausted
		return fail(instance, "RETRIES_EXHAUSTED", "max attempts reached", startNanos)
	}

	private data class LoopResult(
		val success: Boolean,
		val retryable: Boolean = false,
		val failureType: String = "",
		val failureDetail: String = ""
	)

	private suspend fun runLoop(instance: TaskInstance, ctx: TaskContext): LoopResult {
		while (true) {
			val stepResult = try {
				instance.task.step(ctx)
			} catch (ex: Exception) {
				return LoopResult(
					success = false,
					retryable = true,
					failureType = "EXCEPTION",
					failureDetail = ex.message ?: ex::class.simpleName ?: "unknown"
				)
			}

			when (stepResult) {
				is StepResult.Continue -> {
					instance.lastFingerprint = instance.task.progressSignal(ctx)
					bus.emit(
						TaskProgress(
							sessionId = sessionId,
							taskInstanceId = instance.id,
							taskId = instance.task.id.raw
						)
					)
					if (stepResult.cooldown.inWholeMilliseconds > 0) {
						delay(stepResult.cooldown)
					}
				}
				StepResult.Complete -> return LoopResult(success = true)
				is StepResult.Fail -> return LoopResult(
					success = false,
					retryable = stepResult.recoverable,
					failureType = stepResult.reason::class.simpleName ?: "Fail",
					failureDetail = stepResult.reason.toString()
				)
				is StepResult.Pause -> {
					transition(instance, TaskState.PAUSED)
					return LoopResult(
						success = false,
						retryable = false,
						failureType = "PAUSED",
						failureDetail = stepResult.reason
					)
				}
			}
		}
	}

	private fun transition(instance: TaskInstance, next: TaskState) {
		if (instance.canTransition(next)) {
			instance.setState(next)
		}
	}

	private suspend fun fail(instance: TaskInstance, type: String, detail: String, startNanos: Long): TaskState {
		if (instance.canTransition(TaskState.FAILED)) instance.setState(TaskState.FAILED)
		instance.lastFailure = FailureReason.Custom(type, detail)

		bus.emit(
			TaskFailed(
				sessionId = sessionId,
				taskInstanceId = instance.id,
				taskId = instance.task.id.raw,
				reasonType = type,
				reasonDetail = detail,
				attemptNumber = instance.attemptNumber
			)
		)

		// OPTIONAL tasks are skipped rather than session-ended
		if (instance.criticality == Criticality.OPTIONAL) {
			bus.emit(
				TaskSkipped(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					taskId = instance.task.id.raw,
					reason = detail
				)
			)
		}
		return TaskState.FAILED
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt
git commit -m "BuildCore: add Runner state-machine driver with retry + backoff + event emission (spec §6)"
git push origin main
```

---

### Task 23 — Runner state machine test + NoOpTask integration test

**Files:**
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerStateMachineTest.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/NoOpTaskRunTest.kt`

- [ ] **Step 1: Write RunnerStateMachineTest.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerStateMachineTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `TaskInstance starts in PENDING`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		assertEquals(TaskState.PENDING, instance.state)
	}

	@Test
	fun `canTransition rejects skipping to COMPLETED from PENDING`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		assertEquals(false, instance.canTransition(TaskState.COMPLETED))
		assertEquals(true, instance.canTransition(TaskState.VALIDATE))
	}

	@Test
	fun `terminal states reject all transitions`() {
		val instance = TaskInstance(task = NoOpTask(), criticality = Criticality.OPTIONAL)
		instance.setState(TaskState.COMPLETED)
		TaskState.entries.forEach { assertEquals(false, instance.canTransition(it), "from COMPLETED to $it") }

		instance.setState(TaskState.FAILED)
		TaskState.entries.forEach { assertEquals(false, instance.canTransition(it), "from FAILED to $it") }
	}

	@Test
	fun `NoOpTask runs through full lifecycle and reaches COMPLETED`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val instance = TaskInstance(task = NoOpTask(tickBudget = 2), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)

		assertEquals(TaskState.COMPLETED, finalState)
		assertTrue(instance.state.isTerminal)
	}

	@Test
	fun `failing task reaches FAILED terminal state`() = runTest(UnconfinedTestDispatcher()) {
		val failingTask = object : Task {
			override val id = TaskId("failing")
			override val displayName = "failing"
			override val version = SemVer(0, 0, 1)
			override val moduleId = ModuleId("test")
			override val config = ConfigSchema.EMPTY
			override val methods = listOf(NoOpTask().methods[0])
			override fun validate(ctx: TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: TaskContext) {}
			override fun step(ctx: TaskContext): StepResult =
				StepResult.Fail(FailureReason.Custom("synthetic", "test"), recoverable = false)
			override fun isComplete(ctx: TaskContext) = false
			override fun safeStop(ctx: TaskContext) {}
			override fun progressSignal(ctx: TaskContext) = ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: TaskContext) = true
		}

		val bus = EventBus()
		val instance = TaskInstance(
			task = failingTask,
			criticality = Criticality.OPTIONAL,
			retryPolicy = RetryPolicy(maxAttempts = 1)
		)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)
		assertEquals(TaskState.FAILED, finalState)
	}
}
```

- [ ] **Step 2: Write NoOpTaskRunTest.kt**

```kotlin
package net.vital.plugins.buildcore.core.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoOpTaskRunTest {

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `NoOpTask emits validated started completed events in order`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val received = mutableListOf<BusEvent>()
		val subscription = launch {
			bus.events.take(10).toList(received)
		}

		val instance = TaskInstance(task = NoOpTask(tickBudget = 1), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		runner.run(instance, main)
		subscription.join()

		val types = received.map { it::class.simpleName }
		assertTrue("TaskValidated" in types, "missing TaskValidated in $types")
		assertTrue("TaskStarted" in types, "missing TaskStarted in $types")
		assertTrue("TaskCompleted" in types, "missing TaskCompleted in $types")

		// Order: Validated must come before Started, Started before Completed.
		val validatedIdx = received.indexOfFirst { it is TaskValidated }
		val startedIdx = received.indexOfFirst { it is TaskStarted }
		val completedIdx = received.indexOfFirst { it is TaskCompleted }
		assertTrue(validatedIdx < startedIdx, "validated=$validatedIdx started=$startedIdx")
		assertTrue(startedIdx < completedIdx, "started=$startedIdx completed=$completedIdx")
	}

	@Test
	fun `NoOpTask terminates in COMPLETED with one method pick per attempt`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val instance = TaskInstance(task = NoOpTask(tickBudget = 5), criticality = Criticality.OPTIONAL)
		val runner = Runner(bus)

		val finalState = runner.run(instance, main)

		assertEquals(TaskState.COMPLETED, finalState)
	}
}
```

- [ ] **Step 3: Run both test classes — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon \
  --tests "net.vital.plugins.buildcore.core.task.RunnerStateMachineTest" \
  --tests "net.vital.plugins.buildcore.core.task.NoOpTaskRunTest" 2>&1 | tail -20
```
Expected: 7 tests PASSED (5 + 2).

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerStateMachineTest.kt \
         BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/NoOpTaskRunTest.kt
git commit -m "BuildCore: add Runner state-machine + NoOpTask lifecycle tests (spec §6)"
git push origin main
```

---

## Phase 6 — Architecture tests (Task 24)

### Task 24 — TaskSpiArchitectureTest

**Files:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/TaskSpiArchitectureTest.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import net.vital.plugins.buildcore.core.task.Method
import net.vital.plugins.buildcore.core.task.ModuleRegistry
import net.vital.plugins.buildcore.core.task.NoOpTask
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Architecture tests for the Task SPI.
 *
 * Spec §16. Guardrails for future edits.
 */
class TaskSpiArchitectureTest {

	/**
	 * Spec §7: every concrete Method must have exactly one IRONMAN path
	 * with no gatingRestrictions. ModuleRegistry enforces this at
	 * register time; this test asserts the enforcement machinery works
	 * for the known-good NoOpTask.
	 */
	@Test
	fun `NoOpTask passes structural validation`() {
		val task = NoOpTask()
		val result = task.validateStructure()
		assertEquals(ValidationResult.Pass, result)
	}

	/**
	 * Spec §16: ModuleRegistry refuses to register a task whose
	 * structural validation fails.
	 */
	@Test
	fun `ModuleRegistry rejects task with zero methods`() {
		val badTask = object : Task {
			override val id = net.vital.plugins.buildcore.core.task.TaskId("bad")
			override val displayName = "bad"
			override val version = net.vital.plugins.buildcore.core.task.SemVer(0, 0, 1)
			override val moduleId = net.vital.plugins.buildcore.core.task.ModuleId("test")
			override val config = net.vital.plugins.buildcore.core.task.ConfigSchema.EMPTY
			override val methods: List<Method> = emptyList()
			override fun validate(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: net.vital.plugins.buildcore.core.task.TaskContext) {}
			override fun step(ctx: net.vital.plugins.buildcore.core.task.TaskContext) =
				net.vital.plugins.buildcore.core.task.StepResult.Complete
			override fun isComplete(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = true
			override fun safeStop(ctx: net.vital.plugins.buildcore.core.task.TaskContext) {}
			override fun progressSignal(ctx: net.vital.plugins.buildcore.core.task.TaskContext) =
				net.vital.plugins.buildcore.core.task.ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = true
		}
		val registry = ModuleRegistry()
		val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
			registry.register(badTask)
		}
		assertEquals(true, ex.message!!.contains("at least one method"))
	}

	/**
	 * Spec §16: Task sealed-interface-ish discipline. Concrete Task
	 * implementations must not be `object` singletons (would imply
	 * shared mutable state across sessions) and must not expose
	 * public `var` properties (mutable state outside step() is a smell).
	 *
	 * For Plan 2 we only assert the NoOpTask's properties are declared
	 * using `val` — future plan additions extend this rule.
	 */
	@Test
	fun `Task implementations do not expose public var properties`() {
		Konsist
			.scopeFromProject()
			.classes()
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.task..") }
			.filter { klass -> klass.parents().any { it.name == "Task" } }
			.assertTrue { klass ->
				klass.properties().none { prop ->
					prop.hasPublicOrDefaultModifier && !prop.hasValModifier
				}
			}
	}

	/**
	 * Spec §16: no class outside `core.task` may import `Runner` directly.
	 * The Runner is an internal driver; other code interacts with tasks
	 * via the Task interface, not the Runner.
	 *
	 * Plan 7 will tighten this further; Plan 2 only enforces the
	 * package boundary.
	 */
	@Test
	fun `Runner is only used inside core-task package`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { file ->
				file.imports.any { imp ->
					imp.name == "net.vital.plugins.buildcore.core.task.Runner"
				}
			}
			.assertTrue { file ->
				file.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.task") == true
					|| file.packagee?.name?.startsWith("net.vital.plugins.buildcore.arch") == true
			}
	}
}
```

- [ ] **Step 2: Run tests — expect PASS**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.arch.TaskSpiArchitectureTest" 2>&1 | tail -10
```
Expected: 4 tests PASSED.

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/TaskSpiArchitectureTest.kt
git commit -m "BuildCore: add architecture tests for Task SPI + Runner encapsulation (spec §16)"
git push origin main
```

---

## Phase 7 — Final verification (Tasks 25-26)

### Task 25 — Full regression

**Files:** None; verification only.

- [ ] **Step 1: Clean build + all tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew.bat :BuildCore:clean :BuildCore:build --no-daemon 2>&1 | tail -40
```

Expected output shows these test classes passing:
- `RestrictionSetCompositionTest` (7)
- `RestrictionEngineTest` (9)
- `PathSelectorTest` (5)
- `MethodSelectorTest` (5)
- `RetryPolicyTest` (6)
- `SafeStopContractTest` (2)
- `GraduatedThrottleTest` (8)
- `RunnerStateMachineTest` (5)
- `NoOpTaskRunTest` (2)
- `TaskSpiArchitectureTest` (4)
- `LayeringTest` (2 — still passing from Plan 1)
- `EventBusTest` (3 — still passing from Plan 1)
- `BuildCoreWindowSmokeTest` (1 — still passing from Plan 1)

**Total: 59 tests, all PASS. BUILD SUCCESSFUL. JAR ~8–10MB at `BuildCore/build/libs/BuildCore-0.1.0.jar`.**

- [ ] **Step 2: Verify the fat JAR still bundles runtime deps**

```bash
cd /c/Code/VitalPlugins && unzip -l BuildCore/build/libs/BuildCore-0.1.0.jar | grep -cE "^\s+[0-9]+.*(kotlin/|com/formdev/flatlaf|kotlinx/coroutines)"
```
Expected: non-zero (thousands — Shadow plugin from Plan 1 amendment still bundling).

- [ ] **Step 3: No commit.**

---

### Task 26 — Update BuildCore/CLAUDE.md with Plan 2 completion status

**Files:** Modify `BuildCore/CLAUDE.md`

- [ ] **Step 1: Read current file**

The "Status" section says:

> **Foundation phase — Plan 1 (Project Bootstrap) complete.**

- [ ] **Step 2: Update status and current-invariants lists**

Replace the Status line with:

> **Foundation phase — Plans 1 + 2 complete.**

Replace the "Current invariants" section with:

```markdown
Current invariants (Plan 2):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions (enforced at ModuleRegistry.register and by Method.validateStructure).
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet, additional cannot override archetype base.

Plan 3 onward adds many more. Never weaken an architecture test — extend it or add a new one.
```

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins && git add BuildCore/CLAUDE.md && git commit -m "BuildCore: mark Plan 2 complete in subproject CLAUDE.md" && git push origin main
```

---

## Self-review checklist (run BEFORE handing to executing agent)

- [ ] Every task that creates or modifies a file lists the exact path.
- [ ] Every code step shows complete, runnable code — no `// ...` placeholders.
- [ ] Every test step has a command AND an expected outcome.
- [ ] Every task ends with a commit + push (except verification tasks 25).
- [ ] Commit messages follow `BuildCore: <what changed>` convention.
- [ ] No `Co-Authored-By` trailers.
- [ ] The plan covers every Plan-2 item from the decomposition: task state machine, Task/Method/ExecutionPath interfaces, Effect + Requirement + Restriction taxonomies, Archetype presets, Path/Method selectors, RestrictionEngine with three-moment validation, retry policy, Safe-Stop Contract, GraduatedThrottle, task lifecycle bus events, ModuleRegistry, NoOpTask, Runner, and corresponding architecture tests.
- [ ] No plan step references a type or method not defined in an earlier step.
- [ ] No "TODO" / "TBD" / "fill in details" phrases.
- [ ] Task ordering respects dependencies: taxonomies first, then types, then engine, then selectors, then runner infra, then integration tests.
- [ ] Coroutine tests use `runTest(UnconfinedTestDispatcher())` per Plan 1's discovered fix.

---

## What this plan deliberately does NOT do

- Implement the Watchdog thread (Plan 6's Confidence / Watchdog / Recovery concern — but the Task SPI exposes the hooks: `progressSignal`, `canStopNow`, `stallThreshold`, `onUnknownState`).
- Implement Confidence Layer, snapshots, or the 7-step recovery pipeline (all Plan 6).
- Wire the Runner into a plan queue or DAG of tasks (Plan 7 — profile/plan system ships the queue shape).
- Any services — Bank, GE, Mule, Walker, Dialogue, etc. (Plans 4, 5).
- Real personality-seeded antiban RNG (Plan 4). `MethodSelector.RotationPolicy.WEIGHTED_WITH_ANTIBAN_BIAS` is reserved but delegates to `WEIGHTED` for now.
- Structured logging / telemetry subscribers (Plan 3). The 11 task-lifecycle events land here because the Runner emits them; Plan 3 adds subscribers.
- BuildCore-Server HTTP endpoints or WebSocket (Plan 8).
- Licensing / entitlement gates on task execution (Plan 9).
- GUI integration of the Runner (Plan 10 — the Swing shell from Plan 1 doesn't yet launch any runner).
- Hot-rules data binding for tasks (Plan 7 — quest steps live in server-served JSON).

Every deferral above is covered by a later plan. Plan 2's job is **framework only**: the type system, the state machine, the restriction engine, and a working end-to-end NoOpTask demonstrating the runner drives tasks through their lifecycle correctly.
