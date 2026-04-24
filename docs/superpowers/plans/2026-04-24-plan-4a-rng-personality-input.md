# BuildCore Plan 4a — RNG + Personality + Input Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the stateless antiban foundation — `SeededRng` + per-account `PersonalityVector` + Mouse/Keyboard/Camera singletons with real VitalAPI backends + WindMouse curve + reaction/fatigue/throttle timing stack. Leaves the NoOpReplaySubscriber from Plan 3 in place (Plan 4c replaces it).

**Architecture:** Everything in 4a is pure framework. Input primitives are Kotlin `object`s with `@Volatile internal var` backends that `AntibanBootstrap.install` swaps in from `BuildCorePlugin.startUp`. Tests swap the same fields for `FakeMouseBackend`/`FakeKeyboardBackend`/`FakeCameraBackend`. WindMouse is a pure-function iterative path generator; overshoot is a second WindMouse pass. `FatigueCurve` and `ReactionDelay` are stateless math wrappers over `SeededRng`. Persistence goes through a single `PersonalityStore` with atomic writes.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0, Jackson `jackson-module-kotlin` (added in Plan 3), JUnit 5, MockK, Konsist 0.17.3. New dependency: `compileOnly(libs.vital.api)` — added in Task 1.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-24-buildcore-plan4a-rng-personality-input-design.md`](../specs/2026-04-24-buildcore-plan4a-rng-personality-input-design.md)
- Plan 3 complete and merged to `main` (94 tests passing).
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit direct to main and push to `origin` (xgoingleftyx fork) after every commit.
- Author: Chich only — NO `Co-Authored-By` trailers.
- Style: tabs, Allman braces where applicable, UTF-8.

**VitalAPI surface — verified against `C:\Code\VitalAPI\src\vital\api\input\`:**
- `vital.api.input.Movement`: `moveMouseTo(int,int)`, `click(int,int,boolean)`, `appendTrailPoint(int,int)`, `moveCursor(int,int)`, `walkTo(int,int)`. **No `getCursorX/Y` accessor exists** — `VitalApiMouseBackend` tracks cursor position internally (starts at `Point(0,0)`, updated on every `appendTrailPoint` / `click` call).
- `vital.api.input.Keyboard`: `keyDown(vk)`, `keyUp(vk)`, `tap(vk)`, `type(text)`, `pressEscape()`, `pressEnter()`.
- `vital.api.input.Camera`: `getRotation()` (0-2047), `getPitch()` (~128-383), `rotateTo(targetRotation)`, `setPitchTo(targetPitch)`, `adjustTo(rot, pitch)`, `release()`, `getZoom()`, `zoom(scrollDelta)`. **Absolute rotation model**, not relative degrees — `CameraBackend.rotate/pitch` use the same semantics.

---

## File structure this plan produces

```
BuildCore/BuildCore.gradle.kts                                  # MODIFY — add compileOnly(libs.vital.api)

BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                           # MODIFY — call AntibanBootstrap.install()
└── core/
    ├── events/
    │   ├── BusEvent.kt                                          # MODIFY — 4 new subtypes + 2 new enums
    │   └── PrivacyScrubber.kt                                   # MODIFY — 4 new scrubber cases
    ├── logging/
    │   ├── LogDirLayout.kt                                      # MODIFY — add personalityDir()
    │   └── LogLevel.kt                                          # MODIFY — 4 new event→DEBUG mappings
    └── antiban/
        ├── rng/
        │   ├── SeededRng.kt                                     # CREATE
        │   ├── JavaUtilRng.kt                                   # CREATE
        │   ├── PersonalityRng.kt                                # CREATE
        │   └── SessionRng.kt                                    # CREATE
        ├── personality/
        │   ├── PersonalityVector.kt                             # CREATE (+ BreakBias enum)
        │   ├── PersonalityGenerator.kt                          # CREATE
        │   ├── PersonalityStore.kt                              # CREATE
        │   └── PersonalityProvider.kt                           # CREATE
        ├── input/
        │   ├── Point.kt                                         # CREATE (+ MouseButton, Key, CameraAngle, InputMode)
        │   ├── MouseBackend.kt                                  # CREATE (+ KeyboardBackend, CameraBackend)
        │   ├── FakeBackends.kt                                  # CREATE — 3 recording test fixtures
        │   ├── VitalApiMouseBackend.kt                          # CREATE
        │   ├── VitalApiKeyboardBackend.kt                       # CREATE
        │   ├── VitalApiCameraBackend.kt                         # CREATE
        │   ├── Mouse.kt                                         # CREATE — singleton with @Volatile backend
        │   ├── Keyboard.kt                                      # CREATE — singleton
        │   └── Camera.kt                                        # CREATE — singleton
        ├── curve/
        │   ├── WindMouse.kt                                     # CREATE
        │   └── Overshoot.kt                                     # CREATE
        ├── timing/
        │   ├── FatigueCurve.kt                                  # CREATE
        │   └── ReactionDelay.kt                                 # CREATE
        └── AntibanBootstrap.kt                                  # CREATE — install(bus, sessionIdProvider, layout)

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/
│   └── PrivacyScrubberTest.kt                                   # MODIFY — add 4 samples, bump count to 27
├── core/antiban/
│   ├── rng/
│   │   ├── JavaUtilRngTest.kt
│   │   ├── PersonalityRngTest.kt
│   │   └── SessionRngTest.kt
│   ├── personality/
│   │   ├── PersonalityVectorTest.kt
│   │   ├── PersonalityGeneratorTest.kt
│   │   ├── PersonalityStoreTest.kt
│   │   └── PersonalityProviderTest.kt
│   ├── curve/
│   │   ├── WindMouseTest.kt
│   │   └── OvershootTest.kt
│   └── timing/
│       ├── FatigueCurveTest.kt
│       ├── ReactionDelayTest.kt
│       └── GraduatedThrottleIntegrationTest.kt
├── arch/
│   ├── LoggingArchitectureTest.kt                               # MODIFY — bump scrubberSampleCount 23→27
│   └── AntibanArchitectureTest.kt                               # CREATE — 8 invariants
└── integration/
    └── AntibanBootstrapIntegrationTest.kt                       # CREATE
```

---

## Phase 1 — Foundation & events (Tasks 1-4)

### Task 1 — Add VitalAPI compileOnly dependency

**Files:** Modify `C:\Code\VitalPlugins\BuildCore\BuildCore.gradle.kts`

- [ ] **Step 1: Add `compileOnly(libs.vital.api)` to the `dependencies { … }` block**

Open `BuildCore/BuildCore.gradle.kts`. After the existing `implementation(rootProject.libs.jackson.*)` lines, insert:

```kotlin
	// VitalAPI — compileOnly because VitalShell's plugin classpath provides it at runtime
	compileOnly(rootProject.libs.vital.api)
```

- [ ] **Step 2: Verify compile succeeds** (no code uses the dep yet, but the alias must resolve)

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/BuildCore.gradle.kts
git commit -m "BuildCore: add compileOnly(libs.vital.api) dep for antiban input backends (Plan 4a)"
git push origin main
```

---

### Task 2 — Add 4 new `BusEvent` subtypes + 2 enums

**Files:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1: Append the new enums and event data classes** at the bottom of `BusEvent.kt`

```kotlin
// ─────────────────────────────────────────────────────────────────────
// Antiban events (Plan 4a spec §10)
// ─────────────────────────────────────────────────────────────────────

enum class InputKind {
	MOUSE_MOVE, MOUSE_CLICK,
	KEY_TAP, KEY_DOWN, KEY_UP, KEY_TYPE,
	CAMERA_ROTATE, CAMERA_PITCH
}

enum class InputMode { NORMAL, PRECISION, SURVIVAL }

data class InputAction(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val kind: InputKind,
	val targetX: Int? = null,
	val targetY: Int? = null,
	val durationMillis: Long,
	val mode: InputMode = InputMode.NORMAL
) : BusEvent

data class FatigueUpdated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val sessionAgeMillis: Long,
	val reactionMultiplier: Double,
	val misclickMultiplier: Double,
	val overshootVarianceMultiplier: Double,
	val fidgetRateMultiplier: Double
) : BusEvent

data class PersonalityResolved(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val usernameHash: String,
	val generated: Boolean
) : BusEvent

data class SessionRngSeeded(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val seed: Long
) : BusEvent
```

- [ ] **Step 2: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`. At this point the scrubber's exhaustive-when will fail to compile on the next test run — fine, Task 3 fixes that.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt
git commit -m "BuildCore: add 4 antiban BusEvent subtypes + InputKind/InputMode enums (Plan 4a spec §10.1)"
git push origin main
```

---

### Task 3 — Extend `PrivacyScrubber` + `LogLevel` + bump drift-detector

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt`
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt`
- Modify: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`
- Modify: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt`

- [ ] **Step 1: Add 4 pass-through cases to `PrivacyScrubber.scrub`**

Locate the `when (event) { … }` block in `PrivacyScrubber.kt` and add these four cases (pass-through — none of the new events have free-form text):

```kotlin
		is InputAction         -> event
		is FatigueUpdated      -> event
		is PersonalityResolved -> event
		is SessionRngSeeded    -> event
```

The compiler enforces exhaustiveness; without these, Plan 3's `every BusEvent subtype returns without throwing` test would fail. Add them to maintain the closed-taxonomy invariant.

- [ ] **Step 2: Add 4 mappings to `LogLevel.levelOf`**

Open `LogLevel.kt`. Add the imports:

```kotlin
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
```

And add cases to the `when (event)` in `levelOf`, **before** the `else -> LogLevel.DEBUG` line (so they're explicit, not default-by-accident):

```kotlin
	is InputAction, is FatigueUpdated,
	is PersonalityResolved, is SessionRngSeeded -> LogLevel.DEBUG
```

All four are DEBUG — too chatty for summary.log at default INFO level; always land in events.jsonl regardless.

- [ ] **Step 3: Bump the drift-detector in `LoggingArchitectureTest`**

In `LoggingArchitectureTest.kt`, find the line in the `every BusEvent subtype has a scrubber case` test that reads:

```kotlin
		val scrubberSampleCount = 23                // update when a new subtype is added to the scrubber AND to this test
```

Change to:

```kotlin
		val scrubberSampleCount = 27                // update when a new subtype is added to the scrubber AND to this test
```

- [ ] **Step 4: Bump `PrivacyScrubberTest.every BusEvent subtype returns without throwing` — add 4 samples, update count**

In `PrivacyScrubberTest.kt`, locate the `samples: List<BusEvent> = listOf(…)` list. Append:

```kotlin
			InputAction(sessionId = sid, kind = InputKind.MOUSE_CLICK, durationMillis = 5),
			FatigueUpdated(sessionId = sid, sessionAgeMillis = 0,
				reactionMultiplier = 1.0, misclickMultiplier = 1.0,
				overshootVarianceMultiplier = 1.0, fidgetRateMultiplier = 1.0),
			PersonalityResolved(sessionId = sid, usernameHash = "abc123def456", generated = true),
			SessionRngSeeded(sessionId = sid, seed = 42L)
```

Add imports at top of file:

```kotlin
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
```

And update the count assertion:

```kotlin
		// Must cover all 27 current subtypes — update this list when Plans 4b/6/8 add new ones.
		assertEquals(27, samples.size, "update the sample list when a new BusEvent subtype is added")
```

- [ ] **Step 5: Run full suite — should go from 94 → 94 still (no new tests yet, the modified test must still pass)**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: 94 tests passing, BUILD SUCCESSFUL.

Known Windows gradle file-lock quirk — if it fails:
```bash
tasklist | grep java.exe | awk '{print $2}' | while read pid; do taskkill //PID $pid //F; done
rm -rf /c/Code/VitalPlugins/BuildCore/build/test-results
./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt
git commit -m "BuildCore: extend scrubber + LogLevel for 4 new antiban events (Plan 4a spec §10)"
git push origin main
```

---

## Phase 2 — RNG layer (Tasks 4-7)

### Task 4 — `SeededRng` interface + `JavaUtilRng`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SeededRng.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/JavaUtilRng.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/JavaUtilRngTest.kt`

- [ ] **Step 1: Write the failing test**

Create `JavaUtilRngTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp

class JavaUtilRngTest {

	@Test
	fun `same seed produces same sequence`() {
		val a = JavaUtilRng(42L)
		val b = JavaUtilRng(42L)
		repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
	}

	@Test
	fun `nextIntInRange is within half-open bounds`() {
		val rng = JavaUtilRng(1L)
		repeat(1000) {
			val v = rng.nextIntInRange(10, 20)
			assertTrue(v in 10..19, "got $v")
		}
	}

	@Test
	fun `nextLogNormal produces positive values in expected neighbourhood`() {
		val rng = JavaUtilRng(1L)
		val mu = 6.0
		val sigma = 0.4
		val theoreticalMedian = exp(mu)
		val samples = List(1000) { rng.nextLogNormal(mu, sigma) }
		val sortedMedian = samples.sorted()[500]
		assertTrue(samples.all { it > 0.0 })
		assertTrue(abs(sortedMedian - theoreticalMedian) / theoreticalMedian < 0.25,
			"sample median $sortedMedian not within 25% of theoretical $theoreticalMedian")
	}
}
```

- [ ] **Step 2: Run test to verify it fails** (compile error — types not defined)

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests JavaUtilRngTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `SeededRng.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

/**
 * The single RNG abstraction used by the antiban layer.
 *
 * Plan 4a provides exactly one implementation ([JavaUtilRng]). Plan 4c
 * will wrap this interface with a recording decorator so every draw can
 * be replayed from a recorded seed + draw log.
 *
 * Spec §5.
 */
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

- [ ] **Step 4: Write `JavaUtilRng.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

/**
 * The one and only [SeededRng] implementation in BuildCore. Architecture
 * test #2 forbids `java.util.Random` imports outside this file.
 *
 * Spec §5.2.
 */
internal class JavaUtilRng(seed: Long) : SeededRng {

	private val rng = java.util.Random(seed)

	override fun nextLong(): Long = rng.nextLong()
	override fun nextInt(): Int = rng.nextInt()
	override fun nextIntInRange(from: Int, until: Int): Int {
		require(until > from) { "until ($until) must be > from ($from)" }
		return from + rng.nextInt(until - from)
	}
	override fun nextDouble(): Double = rng.nextDouble()
	override fun nextDoubleInRange(from: Double, until: Double): Double {
		require(until > from) { "until ($until) must be > from ($from)" }
		return from + rng.nextDouble() * (until - from)
	}
	override fun nextGaussian(): Double = rng.nextGaussian()
	override fun nextLogNormal(mu: Double, sigma: Double): Double =
		kotlin.math.exp(mu + sigma * rng.nextGaussian())
	override fun nextBoolean(p: Double): Boolean = rng.nextDouble() < p
	override fun <T> shuffled(list: List<T>): List<T> = list.shuffled(rng)
}
```

- [ ] **Step 5: Run tests to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests JavaUtilRngTest --no-daemon 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SeededRng.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/JavaUtilRng.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/JavaUtilRngTest.kt
git commit -m "BuildCore: add SeededRng interface + JavaUtilRng implementation (Plan 4a spec §5.1-5.2)"
git push origin main
```

---

### Task 5 — `PersonalityRng`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/PersonalityRng.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/PersonalityRngTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PersonalityRngTest {

	@Test
	fun `same username produces same sequence`() {
		val a = PersonalityRng.forUsername("chich")
		val b = PersonalityRng.forUsername("chich")
		repeat(20) { assertEquals(a.nextLong(), b.nextLong()) }
	}

	@Test
	fun `username is case-insensitive`() {
		val lower = PersonalityRng.forUsername("chich")
		val upper = PersonalityRng.forUsername("CHICH")
		val mixed = PersonalityRng.forUsername("Chich")
		repeat(20) {
			val expected = lower.nextLong()
			assertEquals(expected, upper.nextLong())
			assertEquals(expected, mixed.nextLong())
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityRngTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `PersonalityRng.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

import java.security.MessageDigest

/**
 * Username-seeded factory. `SHA-256(lowercase(username))` → first 8 bytes
 * interpreted as big-endian Long → seeds [JavaUtilRng]. Same username
 * always produces the same sequence, which means the same PersonalityVector.
 *
 * Spec §5.3.
 */
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

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityRngTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/PersonalityRng.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/PersonalityRngTest.kt
git commit -m "BuildCore: add PersonalityRng with case-insensitive SHA-256 username seed (Plan 4a spec §5.3)"
git push origin main
```

---

### Task 6 — `SessionRng`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SessionRng.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SessionRngTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SessionRngTest {

	@Test
	fun `fresh returns distinct seeds across calls`() {
		// Two fresh SessionRngs should have different seeds with overwhelming probability
		val a = SessionRng.fresh()
		val b = SessionRng.fresh()
		assertNotEquals(a.seed, b.seed)
	}

	@Test
	fun `fromSeed produces deterministic sequence`() {
		val a = SessionRng.fromSeed(1234L)
		val b = SessionRng.fromSeed(1234L)
		assertEquals(a.seed, b.seed)
		repeat(20) { assertEquals(a.nextLong(), b.nextLong()) }
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SessionRngTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `SessionRng.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.rng

import java.security.SecureRandom

/**
 * Per-session RNG. Seed is drawn once from [SecureRandom] (unpredictable) and
 * exposed as a public `val` so Plan 4c's replay recorder can log it at session
 * start. All subsequent draws go through [JavaUtilRng] (deterministic from seed).
 *
 * The class-delegation pattern (`SeededRng by delegate`) inherits every
 * [SeededRng] method without boilerplate.
 *
 * Spec §5.4.
 */
class SessionRng private constructor(
	val seed: Long,
	private val delegate: SeededRng
) : SeededRng by delegate {

	companion object {
		fun fresh(): SessionRng {
			val seed = SecureRandom().nextLong()
			return SessionRng(seed, JavaUtilRng(seed))
		}

		/** Deterministic constructor for tests and (future) replay. */
		fun fromSeed(seed: Long): SessionRng = SessionRng(seed, JavaUtilRng(seed))
	}
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SessionRngTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SessionRng.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/rng/SessionRngTest.kt
git commit -m "BuildCore: add SessionRng with SecureRandom seed + class delegation (Plan 4a spec §5.4)"
git push origin main
```

---

## Phase 3 — PersonalityVector + Generator + Store + Provider (Tasks 7-10)

### Task 7 — `PersonalityVector` data class + `BreakBias`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityVector.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityVectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PersonalityVectorTest {

	private fun valid() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `constructor with all valid fields succeeds`() {
		valid()   // no throw
	}

	@Test
	fun `out of range mouseCurveGravity is rejected`() {
		assertThrows(IllegalArgumentException::class.java) {
			valid().copy(mouseCurveGravity = 7.5)   // below 8.0
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityVectorTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `PersonalityVector.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

/**
 * Sixteen-dimensional account-stable personality. Values are drawn once per
 * account (seeded by SHA-256(username)) and persisted so the same account
 * always gets the same personality across BuildCore versions.
 *
 * Field declaration order is SEMANTICALLY SIGNIFICANT — reordering silently
 * changes every existing persisted personality. Architecture Test #6 enforces
 * the order matches [PersonalityGenerator.generate].
 *
 * Ranges match foundation spec §9; [require] guards reject out-of-range values
 * on load (disk-tampering / corrupted-file guard).
 *
 * Spec §6.1.
 */
data class PersonalityVector(
	val schemaVersion: Int = 1,
	val mouseSpeedCenter:        Double,
	val mouseCurveGravity:       Double,
	val mouseCurveWind:          Double,
	val overshootTendency:       Double,
	val reactionLogMean:         Double,
	val reactionLogStddev:       Double,
	val hotkeyPreference:        Double,
	val foodEatDelayCenterMs:    Int,
	val cameraFidgetRatePerMin:  Double,
	val bankWithdrawalPrecision: Double,
	val breakBias:               BreakBias,
	val misclickRate:            Double,
	val menuTopSelectionRate:    Double,
	val idleExamineRatePerMin:   Double,
	val tabSwapRatePerMin:       Double
) {
	init {
		require(schemaVersion == 1) { "unsupported schemaVersion=$schemaVersion" }
		require(mouseSpeedCenter        in 0.6..1.8)    { "mouseSpeedCenter out of range: $mouseSpeedCenter" }
		require(mouseCurveGravity       in 8.0..12.0)   { "mouseCurveGravity out of range: $mouseCurveGravity" }
		require(mouseCurveWind          in 3.0..7.0)    { "mouseCurveWind out of range: $mouseCurveWind" }
		require(overshootTendency       in 0.02..0.12)  { "overshootTendency out of range: $overshootTendency" }
		require(reactionLogMean         in 5.5..6.5)    { "reactionLogMean out of range: $reactionLogMean" }
		require(reactionLogStddev       in 0.3..0.5)    { "reactionLogStddev out of range: $reactionLogStddev" }
		require(hotkeyPreference        in 0.4..0.9)    { "hotkeyPreference out of range: $hotkeyPreference" }
		require(foodEatDelayCenterMs    in 400..900)    { "foodEatDelayCenterMs out of range: $foodEatDelayCenterMs" }
		require(cameraFidgetRatePerMin  in 0.8..3.5)    { "cameraFidgetRatePerMin out of range: $cameraFidgetRatePerMin" }
		require(bankWithdrawalPrecision in 0.85..0.99)  { "bankWithdrawalPrecision out of range: $bankWithdrawalPrecision" }
		require(misclickRate            in 0.003..0.015){ "misclickRate out of range: $misclickRate" }
		require(menuTopSelectionRate    in 0.92..0.995) { "menuTopSelectionRate out of range: $menuTopSelectionRate" }
		require(idleExamineRatePerMin   in 0.5..2.5)    { "idleExamineRatePerMin out of range: $idleExamineRatePerMin" }
		require(tabSwapRatePerMin       in 0.3..1.8)    { "tabSwapRatePerMin out of range: $tabSwapRatePerMin" }
	}
}

enum class BreakBias { NIGHT_OWL, DAY_REGULAR, BURST }
```

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityVectorTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityVector.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityVectorTest.kt
git commit -m "BuildCore: add PersonalityVector with 15 dimensions + range guards (Plan 4a spec §6.1)"
git push origin main
```

---

### Task 8 — `PersonalityGenerator`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityGenerator.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.rng.PersonalityRng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalityGeneratorTest {

	@Test
	fun `generator produces all fields within spec ranges`() {
		// Use the first 100 seeds to survey the output space
		repeat(100) { seed ->
			val p = PersonalityGenerator.generate(JavaUtilRng(seed.toLong()))
			// Range validation happens in PersonalityVector.init; if we got here, it's valid.
			assertTrue(p.schemaVersion == 1)
		}
	}

	@Test
	fun `same rng seed produces identical personality`() {
		val a = PersonalityGenerator.generate(JavaUtilRng(42L))
		val b = PersonalityGenerator.generate(JavaUtilRng(42L))
		assertEquals(a, b)
	}

	@Test
	fun `personality for username is stable — chich always gets same result`() {
		val a = PersonalityGenerator.generate(PersonalityRng.forUsername("chich"))
		val b = PersonalityGenerator.generate(PersonalityRng.forUsername("chich"))
		assertEquals(a, b)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityGeneratorTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `PersonalityGenerator.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.SeededRng

/**
 * Pure function from [SeededRng] to [PersonalityVector].
 *
 * Draw order matches [PersonalityVector]'s field declaration order. Architecture
 * Test #6 enforces this — reordering draws would silently change every existing
 * persisted personality. Future plans that add a 16th+ dimension must append at
 * the END and bump [PersonalityVector.schemaVersion].
 *
 * Spec §6.2.
 */
object PersonalityGenerator {

	fun generate(rng: SeededRng): PersonalityVector = PersonalityVector(
		mouseSpeedCenter        = rng.nextDoubleInRange(0.6, 1.8),
		mouseCurveGravity       = rng.nextDoubleInRange(8.0, 12.0),
		mouseCurveWind          = rng.nextDoubleInRange(3.0, 7.0),
		overshootTendency       = rng.nextDoubleInRange(0.02, 0.12),
		reactionLogMean         = rng.nextDoubleInRange(5.5, 6.5),
		reactionLogStddev       = rng.nextDoubleInRange(0.3, 0.5),
		hotkeyPreference        = rng.nextDoubleInRange(0.4, 0.9),
		foodEatDelayCenterMs    = rng.nextIntInRange(400, 901),   // until is exclusive, +1 so 900 is reachable
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

Note: `nextIntInRange(400, 901)` — `until` is exclusive per the SeededRng contract. The spec range `400..900` is inclusive at both ends; passing `901` makes 900 reachable. Spec §6.1's `require(foodEatDelayCenterMs in 400..900)` already allows 400 through 900 inclusive.

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityGeneratorTest --no-daemon 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityGenerator.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityGeneratorTest.kt
git commit -m "BuildCore: add PersonalityGenerator — pure function SeededRng → PersonalityVector (Plan 4a spec §6.2)"
git push origin main
```

---

### Task 9 — `LogDirLayout.personalityDir()` + `PersonalityStore`

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityStore.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityStoreTest.kt`

- [ ] **Step 1: Add `personalityDir()` to `LogDirLayout`**

Open `LogDirLayout.kt`. Add one method:

```kotlin
	/**
	 * Sibling directory to the logs root — `.../personalities/` (not nested under logs).
	 * Plan 4a's [PersonalityStore] persists per-account personalities here.
	 *
	 * Spec §6.3.
	 */
	fun personalityDir(): Path {
		val dir = root.resolveSibling("personalities")
		Files.createDirectories(dir)
		return dir
	}
```

- [ ] **Step 2: Write the failing test for `PersonalityStore`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PersonalityStoreTest {

	private fun sample() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `save then load returns equal PersonalityVector`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		val original = sample()
		store.save("abc123def456", original)
		val loaded = store.load("abc123def456")
		assertEquals(original, loaded)
	}

	@Test
	fun `load returns null for missing key`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		assertNull(store.load("deadbeef0000"))
	}

	@Test
	fun `load returns null and logs warning on corrupt file`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		Files.writeString(tmp.resolve("abc123def456.json"), "not valid json")
		assertNull(store.load("abc123def456"))
	}

	@Test
	fun `save writes atomically via tmp then move`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		store.save("abc123def456", sample())
		val final = tmp.resolve("abc123def456.json")
		val temp = tmp.resolve("abc123def456.json.tmp")
		assertTrue(Files.exists(final))
		assertTrue(!Files.exists(temp))   // tmp has been moved, not left behind
	}
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityStoreTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 4: Write `PersonalityStore.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed PersonalityVector persistence.
 *
 * Writes are atomic: body written to `<name>.json.tmp`, then
 * [Files.move] with [StandardCopyOption.ATOMIC_MOVE] + [StandardCopyOption.REPLACE_EXISTING].
 * Read failures (missing, corrupt JSON, schema mismatch, range violation)
 * return null — the caller's policy is to regenerate.
 *
 * Spec §6.3.
 */
class PersonalityStore(private val root: Path) {

	private val mapper = ObjectMapper().registerKotlinModule()

	fun save(key: String, vector: PersonalityVector) {
		Files.createDirectories(root)
		val finalPath = root.resolve("$key.json")
		val tempPath = root.resolve("$key.json.tmp")
		Files.writeString(tempPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(vector))
		try {
			Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (e: Exception) {
			// Some filesystems (e.g. tmpfs, WSL with Windows host FS) reject ATOMIC_MOVE. Fall back.
			Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	fun load(key: String): PersonalityVector? {
		val path = root.resolve("$key.json")
		if (!Files.exists(path)) return null
		return try {
			val body = Files.readString(path)
			mapper.readValue(body, PersonalityVector::class.java)
		} catch (e: Exception) {
			// Log-and-regenerate policy: corrupt files shouldn't brick the bot.
			// Caller (PersonalityProvider) treats null as "not present" and regenerates.
			null
		}
	}
}
```

- [ ] **Step 5: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityStoreTest --no-daemon 2>&1 | tail -10
```
Expected: 4 tests passing.

- [ ] **Step 6: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityStore.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityStoreTest.kt
git commit -m "BuildCore: add PersonalityStore atomic persistence + LogDirLayout.personalityDir() (Plan 4a spec §6.3)"
git push origin main
```

---

### Task 10 — `PersonalityProvider`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityProvider.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityProviderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.logging.LoggerScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PersonalityProviderTest {

	@Test
	fun `forUsername generates + persists on first call, loads on second`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val sid = UUID.randomUUID()
		val store = PersonalityStore(tmp)
		val provider = PersonalityProvider(store, bus) { sid }

		val p1 = provider.forUsername("chich")
		// Second call hits the cache (same JVM); also ensure disk has the file
		val p2 = provider.forUsername("chich")
		assertEquals(p1, p2)
		assertTrue(java.nio.file.Files.list(tmp).use { it.toList() }.isNotEmpty())
	}

	@Test
	fun `forUsername emits PersonalityResolved with generated=true on fresh create`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val store = PersonalityStore(tmp)
		val provider = PersonalityProvider(store, bus) { sid }

		val latch = CountDownLatch(1)
		val captured = AtomicReference<PersonalityResolved>()
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<PersonalityResolved>()
				.first()
				.also { captured.set(it) }
		}
		latch.await()

		provider.forUsername("chich")

		withTimeout(2000) {
			while (captured.get() == null) kotlinx.coroutines.yield()
		}
		val event = captured.get()
		assertNotNull(event)
		assertTrue(event.generated)
		scope.close()
	}

	@Test
	fun `ephemeral returns same vector on repeated calls`(@TempDir tmp: Path) {
		val bus = EventBus()
		val sid = UUID.randomUUID()
		val provider = PersonalityProvider(PersonalityStore(tmp), bus) { sid }
		val rng = SessionRng.fromSeed(42L)
		val a = provider.ephemeral(rng)
		val b = provider.ephemeral(rng)
		assertEquals(a, b)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityProviderTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `PersonalityProvider.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.PersonalityRng
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy-by-username personality resolver with ephemeral fallback for pre-login.
 *
 * - [forUsername] loads from [PersonalityStore] or generates and persists; cached
 *   in-process after the first call. Emits a [PersonalityResolved] event.
 * - [ephemeral] returns a transient per-session personality drawn from the given
 *   [SeededRng] (typically [net.vital.plugins.buildcore.core.antiban.rng.SessionRng]).
 *   Not persisted; not emitted as an event.
 * - [currentPersonality] is the primitive-side read path: returns the most-recently
 *   resolved account personality if any, else the ephemeral. Plan 5's Login service
 *   will drive [forUsername] calls to populate the cache.
 *
 * Spec §6.4.
 */
class PersonalityProvider(
	private val store: PersonalityStore,
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID
) {

	private val cache = ConcurrentHashMap<String, PersonalityVector>()

	@Volatile
	private var ephemeralCache: PersonalityVector? = null

	fun forUsername(username: String): PersonalityVector {
		val key = hashKey(username)
		return cache.computeIfAbsent(key) {
			val loaded = store.load(key)
			if (loaded != null) {
				bus.tryEmit(PersonalityResolved(
					sessionId = sessionIdProvider(),
					usernameHash = key,
					generated = false
				))
				loaded
			} else {
				val generated = PersonalityGenerator.generate(PersonalityRng.forUsername(username))
				store.save(key, generated)
				bus.tryEmit(PersonalityResolved(
					sessionId = sessionIdProvider(),
					usernameHash = key,
					generated = true
				))
				generated
			}
		}
	}

	fun ephemeral(sessionRng: SeededRng): PersonalityVector {
		val existing = ephemeralCache
		if (existing != null) return existing
		val generated = PersonalityGenerator.generate(sessionRng)
		ephemeralCache = generated
		return generated
	}

	/**
	 * Primitive-side read path. Returns the username-resolved personality if exactly
	 * one has been cached; else falls back to ephemeral. Only deterministic when at
	 * most one username has been resolved — Plan 5's Login service owns the
	 * one-active-personality-at-a-time invariant.
	 */
	fun currentPersonality(sessionRng: SeededRng): PersonalityVector =
		cache.values.firstOrNull() ?: ephemeral(sessionRng)

	private fun hashKey(username: String): String {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(username.lowercase().toByteArray(Charsets.UTF_8))
		return digest.take(6).joinToString("") { "%02x".format(it) }
	}
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PersonalityProviderTest --no-daemon 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityProvider.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/personality/PersonalityProviderTest.kt
git commit -m "BuildCore: add PersonalityProvider lazy load/generate + ephemeral fallback (Plan 4a spec §6.4)"
git push origin main
```

---

## Phase 4 — Input value types + backends (Tasks 11-14)

### Task 11 — Value types: `Point`, `MouseButton`, `Key`, `CameraAngle`, `InputMode`

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Point.kt`

- [ ] **Step 1: Write the file** (all five types in one file for cohesion)

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

/**
 * Screen-pixel coordinate pair. Packed into a Long to avoid allocation
 * pressure inside WindMouse's per-step path generation (hundreds of
 * points per move).
 *
 * Spec §7.1.
 */
@JvmInline
value class Point(val packed: Long) {
	constructor(x: Int, y: Int) : this(
		(x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
	)
	val x: Int get() = (packed ushr 32).toInt()
	val y: Int get() = packed.toInt()
	override fun toString(): String = "Point($x, $y)"
}

enum class MouseButton { LEFT, RIGHT, MIDDLE }

@JvmInline
value class Key(val vk: Int)

data class CameraAngle(val rotation: Int, val pitch: Int)

// InputMode is re-declared in core.events.BusEvent for schema stability;
// imported here for primitive API clarity.
typealias InputMode = net.vital.plugins.buildcore.core.events.InputMode
```

- [ ] **Step 2: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Point.kt
git commit -m "BuildCore: add input value types — Point, MouseButton, Key, CameraAngle (Plan 4a spec §7.1)"
git push origin main
```

---

### Task 12 — Backend interfaces + Fake recording backends

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/MouseBackend.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/FakeBackends.kt`

- [ ] **Step 1: Write `MouseBackend.kt` (contains all three backend interfaces)**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

/**
 * Backend for [Mouse] primitive. `currentPosition` is tracked client-side
 * because VitalAPI's [vital.api.input.Movement] exposes no cursor-position
 * accessor — we generate the trail, so we know where the cursor is.
 *
 * Spec §7.2.
 */
interface MouseBackend {
	fun currentPosition(): Point
	fun appendTrailPoint(x: Int, y: Int)
	fun click(x: Int, y: Int, button: MouseButton)
}

/** Spec §7.2. */
interface KeyboardBackend {
	fun keyDown(vk: Int)
	fun keyUp(vk: Int)
	fun tap(vk: Int)
	fun type(text: String)
}

/**
 * Backend for [Camera] primitive. Uses VitalAPI's absolute rotation model
 * (`rotation` ∈ 0..2047, `pitch` ∈ ~128..383), NOT relative degrees.
 *
 * Spec §7.2.
 */
interface CameraBackend {
	fun currentRotation(): Int
	fun currentPitch(): Int
	fun rotateTo(targetRotation: Int)
	fun setPitchTo(targetPitch: Int)
}
```

- [ ] **Step 2: Write `FakeBackends.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

/**
 * Recording test fixtures. All three fakes track calls in public mutable
 * state so tests can assert behavior directly (no mocking framework needed).
 *
 * Spec §7.2 (fake backends).
 */
class FakeMouseBackend : MouseBackend {
	val trailPoints = mutableListOf<Point>()
	val clicks = mutableListOf<Triple<Int, Int, MouseButton>>()
	var position = Point(0, 0)

	override fun currentPosition(): Point = position
	override fun appendTrailPoint(x: Int, y: Int) {
		trailPoints += Point(x, y)
		position = Point(x, y)
	}
	override fun click(x: Int, y: Int, button: MouseButton) {
		clicks += Triple(x, y, button)
	}
}

class FakeKeyboardBackend : KeyboardBackend {
	val keyDowns = mutableListOf<Int>()
	val keyUps = mutableListOf<Int>()
	val taps = mutableListOf<Int>()
	val typed = mutableListOf<String>()

	override fun keyDown(vk: Int) { keyDowns += vk }
	override fun keyUp(vk: Int) { keyUps += vk }
	override fun tap(vk: Int) { taps += vk }
	override fun type(text: String) { typed += text }
}

class FakeCameraBackend : CameraBackend {
	var rotation = 0
	var pitch = 256
	val rotateToCalls = mutableListOf<Int>()
	val pitchToCalls = mutableListOf<Int>()

	override fun currentRotation(): Int = rotation
	override fun currentPitch(): Int = pitch
	override fun rotateTo(targetRotation: Int) {
		rotateToCalls += targetRotation
		rotation = targetRotation
	}
	override fun setPitchTo(targetPitch: Int) {
		pitchToCalls += targetPitch
		pitch = targetPitch
	}
}
```

- [ ] **Step 3: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
./gradlew :BuildCore:compileTestKotlin --no-daemon 2>&1 | tail -5
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/MouseBackend.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/FakeBackends.kt
git commit -m "BuildCore: add Mouse/Keyboard/Camera backend interfaces + Fake recording fixtures (Plan 4a spec §7.2)"
git push origin main
```

---

### Task 13 — VitalAPI backends

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiMouseBackend.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiKeyboardBackend.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiCameraBackend.kt`

- [ ] **Step 1: Write `VitalApiMouseBackend.kt`**

`VitalApiMouseBackend` tracks cursor position internally — VitalAPI provides no `getCursorX/Y` accessor. Every `appendTrailPoint` and `click` call updates the internal `position`. Starting position is `Point(0, 0)` (before any input the real cursor could be anywhere, but the first WindMouse move will still converge correctly because WindMouse reads `backend.currentPosition()` every call).

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Movement

/**
 * Real [MouseBackend] backed by [vital.api.input.Movement]. Cursor position
 * is tracked client-side because VitalAPI exposes no accessor; every
 * [appendTrailPoint] / [click] call updates the cached [position].
 *
 * Spec §7.3.
 */
internal object VitalApiMouseBackend : MouseBackend {

	@Volatile
	private var position: Point = Point(0, 0)

	override fun currentPosition(): Point = position

	override fun appendTrailPoint(x: Int, y: Int) {
		Movement.appendTrailPoint(x, y)
		position = Point(x, y)
	}

	override fun click(x: Int, y: Int, button: MouseButton) {
		Movement.click(x, y, button == MouseButton.RIGHT)
		position = Point(x, y)
	}
}
```

- [ ] **Step 2: Write `VitalApiKeyboardBackend.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Keyboard as VitalKeyboard

/**
 * Real [KeyboardBackend] backed by [vital.api.input.Keyboard].
 *
 * Spec §7.3.
 */
internal object VitalApiKeyboardBackend : KeyboardBackend {
	override fun keyDown(vk: Int)   = VitalKeyboard.keyDown(vk)
	override fun keyUp(vk: Int)     = VitalKeyboard.keyUp(vk)
	override fun tap(vk: Int)       = VitalKeyboard.tap(vk)
	override fun type(text: String) = VitalKeyboard.type(text)
}
```

- [ ] **Step 3: Write `VitalApiCameraBackend.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Camera as VitalCamera

/**
 * Real [CameraBackend] backed by [vital.api.input.Camera]. Uses VitalAPI's
 * absolute rotation model (0-2047 yaw units, ~128-383 pitch units).
 *
 * Spec §7.3.
 */
internal object VitalApiCameraBackend : CameraBackend {
	override fun currentRotation(): Int = VitalCamera.getRotation()
	override fun currentPitch(): Int = VitalCamera.getPitch()
	override fun rotateTo(targetRotation: Int) = VitalCamera.rotateTo(targetRotation)
	override fun setPitchTo(targetPitch: Int) = VitalCamera.setPitchTo(targetPitch)
}
```

- [ ] **Step 4: Verify compile**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`. If you get `unresolved reference: vital`, verify Task 1's `compileOnly(libs.vital.api)` made it into `BuildCore/BuildCore.gradle.kts`.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiMouseBackend.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiKeyboardBackend.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/VitalApiCameraBackend.kt
git commit -m "BuildCore: add VitalAPI-backed Mouse/Keyboard/Camera backends (Plan 4a spec §7.3)"
git push origin main
```

---

## Phase 5 — WindMouse + Overshoot (Task 14)

### Task 14 — WindMouse algorithm + Overshoot

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/curve/WindMouse.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/curve/Overshoot.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/curve/WindMouseTest.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/curve/OvershootTest.kt`

- [ ] **Step 1: Write the failing test for WindMouse**

```kotlin
package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class WindMouseTest {

	@Test
	fun `path terminates at target`() {
		val path = WindMouse.generatePath(
			from = Point(100, 100), to = Point(400, 300),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0,
			rng = JavaUtilRng(42L)
		)
		assertEquals(Point(400, 300), path.last().first)
	}

	@Test
	fun `same seed produces identical path`() {
		val a = WindMouse.generatePath(Point(0, 0), Point(500, 500),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(7L))
		val b = WindMouse.generatePath(Point(0, 0), Point(500, 500),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(7L))
		assertEquals(a, b)
	}

	@Test
	fun `from equals to returns single-point path`() {
		val path = WindMouse.generatePath(Point(200, 200), Point(200, 200),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(1L))
		assertEquals(1, path.size)
		assertEquals(Point(200, 200), path.first().first)
	}

	@Test
	fun `higher wind produces more curve deviation from straight line`() {
		val from = Point(0, 0); val target = Point(500, 0)
		fun maxOffset(windValue: Double): Int {
			val path = WindMouse.generatePath(from, target,
				gravity = 10.0, wind = windValue, speedCenter = 1.0, rng = JavaUtilRng(42L))
			return path.maxOf { (p, _) -> kotlin.math.abs(p.y) }
		}
		val lowWind = maxOffset(3.0)
		val highWind = maxOffset(7.0)
		assertTrue(highWind > lowWind, "expected high-wind ($highWind) > low-wind ($lowWind)")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests WindMouseTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `WindMouse.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Iterative mouse-path generator after Benjamin J. Land's WindMouse.
 *
 * Each step updates a `wind` vector (random perturbation damped by √3 / √5)
 * and a velocity accumulator pulled toward the target by `gravity`. Velocity
 * magnitude is clamped to a per-step cap sampled from `speedCenter`.
 *
 * Returns (point, delay-since-previous-ms) pairs. The final element is always
 * the exact target with 0-ms delay.
 *
 * Spec §8.
 */
object WindMouse {

	private const val ITERATION_CAP = 2000

	fun generatePath(
		from: Point,
		to: Point,
		gravity: Double,
		wind: Double,
		speedCenter: Double,
		rng: SeededRng
	): List<Pair<Point, Int>> {
		// Edge case — cursor already at target
		if (from == to) return listOf(to to 0)

		val result = mutableListOf<Pair<Point, Int>>()
		var currentX = from.x.toDouble()
		var currentY = from.y.toDouble()
		var velocityX = 0.0
		var velocityY = 0.0
		var windX = 0.0
		var windY = 0.0
		val sqrt3 = sqrt(3.0)
		val sqrt5 = sqrt(5.0)
		val targetX = to.x.toDouble()
		val targetY = to.y.toDouble()

		var iterations = 0
		while (iterations < ITERATION_CAP) {
			iterations++
			val dx = targetX - currentX
			val dy = targetY - currentY
			val distance = hypot(dx, dy)
			if (distance < 1.0) break

			val windFactor = min(wind, distance)
			windX = windX / sqrt3 + (rng.nextDouble() * 2.0 - 1.0) * windFactor / sqrt5
			windY = windY / sqrt3 + (rng.nextDouble() * 2.0 - 1.0) * windFactor / sqrt5

			velocityX += windX + gravity * dx / distance
			velocityY += windY + gravity * dy / distance

			val velocityMag = hypot(velocityX, velocityY)
			val stepCap = speedCenter * (3.0 + rng.nextDouble() * 3.0)
			if (velocityMag > stepCap) {
				val randomDamping = stepCap / 2.0 + rng.nextDouble() * stepCap / 2.0
				velocityX = velocityX / velocityMag * randomDamping
				velocityY = velocityY / velocityMag * randomDamping
			}

			currentX += velocityX
			currentY += velocityY
			val delayMs = (rng.nextDouble() * 5.0 + 3.0).toInt()
			result += Point(currentX.toInt(), currentY.toInt()) to delayMs
		}

		// Snap to exact target
		result += to to 0
		return result
	}
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests WindMouseTest --no-daemon 2>&1 | tail -10
```
Expected: 4 tests passing.

- [ ] **Step 5: Write the failing test for Overshoot**

```kotlin
package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.FakeMouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OvershootTest {

	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.08, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `overshoot pushes the trail past the target and returns`() {
		val backend = FakeMouseBackend()
		backend.position = Point(100, 100)
		val target = Point(500, 100)
		Overshoot.apply(backend.currentPosition(), target, backend,
			samplePersonality(), JavaUtilRng(42L))
		// At least one recorded trail point must have x > target.x (i.e. overshot)
		val overshootPointExists = backend.trailPoints.any { it.x > target.x }
		assertTrue(overshootPointExists,
			"expected at least one trail point with x > ${target.x}, got ${backend.trailPoints.takeLast(5)}")
	}
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests OvershootTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 7: Write `Overshoot.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.MouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import kotlin.math.hypot

/**
 * Two-phase overshoot: fly past the target by 3-12 pixels along the approach
 * vector, then return via a second WindMouse pass.
 *
 * Spec §8.3.
 */
object Overshoot {

	fun apply(
		current: Point,
		target: Point,
		backend: MouseBackend,
		personality: PersonalityVector,
		rng: SeededRng
	) {
		val dx = (target.x - current.x).toDouble()
		val dy = (target.y - current.y).toDouble()
		val mag = hypot(dx, dy).coerceAtLeast(1.0)
		val overshootDistance = 3.0 + rng.nextDoubleInRange(0.0, 9.0)
		val overshootX = target.x + (dx / mag * overshootDistance).toInt()
		val overshootY = target.y + (dy / mag * overshootDistance).toInt()

		val pathOut = WindMouse.generatePath(
			current, Point(overshootX, overshootY),
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		for ((p, _) in pathOut) backend.appendTrailPoint(p.x, p.y)

		val pathBack = WindMouse.generatePath(
			Point(overshootX, overshootY), target,
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		for ((p, _) in pathBack) backend.appendTrailPoint(p.x, p.y)
	}
}
```

- [ ] **Step 8: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests OvershootTest --no-daemon 2>&1 | tail -10
```
Expected: 1 test passing.

- [ ] **Step 9: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/curve/WindMouse.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/curve/Overshoot.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/curve/WindMouseTest.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/curve/OvershootTest.kt
git commit -m "BuildCore: add WindMouse path algorithm + Overshoot secondary pass (Plan 4a spec §8)"
git push origin main
```

---

## Phase 6 — Timing: FatigueCurve + ReactionDelay (Tasks 15-16)

### Task 15 — `FatigueCurve`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/FatigueCurve.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/FatigueCurveTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.events.EventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FatigueCurveTest {

	private class MutableClock(var now: Instant) : Clock() {
		override fun instant(): Instant = now
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(zone: java.time.ZoneId?) = this
	}

	@Test
	fun `reaction multiplier is 1_0 at session start`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		assertEquals(1.0, fatigue.reactionMultiplier(), 0.001)
	}

	@Test
	fun `reaction multiplier is 1_10 at 4 hours`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		clock.now = start.plusSeconds(4L * 3600L)
		assertEquals(1.10, fatigue.reactionMultiplier(), 0.001)
	}

	@Test
	fun `reaction multiplier plateaus past 4 hours`() {
		val start = Instant.ofEpochSecond(1_000_000)
		val clock = MutableClock(start)
		val fatigue = FatigueCurve(start, clock)
		clock.now = start.plusSeconds(8L * 3600L)
		assertEquals(1.10, fatigue.reactionMultiplier(), 0.001)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests FatigueCurveTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `FatigueCurve.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Session-scoped fatigue multipliers. Linear interp from 1.0 at session start
 * to spec-pinned midpoints at 4h, clamped past 4h.
 *
 * Emits a debounced [FatigueUpdated] event: at most once per 60s AND only when
 * the reaction multiplier has drifted ≥0.01 since the last emission.
 *
 * Spec §9.3.
 */
class FatigueCurve(
	private val sessionStart: Instant,
	private val clock: Clock = Clock.systemUTC(),
	private val bus: EventBus? = null,
	private val sessionIdProvider: () -> UUID = { UUID(0, 0) }
) {

	@Volatile private var lastEmitAt: Instant = Instant.EPOCH
	@Volatile private var lastEmitReaction: Double = 1.0

	fun reactionMultiplier(): Double = multiplier(MAX_REACTION_CREEP).also { maybeEmit(it) }
	fun misclickMultiplier(): Double = multiplier(MAX_MISCLICK_CREEP)
	fun overshootVarianceMultiplier(): Double = multiplier(MAX_OVERSHOOT_VARIANCE_CREEP)
	fun fidgetRateMultiplier(): Double = multiplier(MAX_FIDGET_CREEP)

	private fun multiplier(maxCreep: Double): Double {
		val ageMs = Duration.between(sessionStart, clock.instant()).toMillis().coerceAtLeast(0)
		val t = (ageMs.toDouble() / FOUR_HOURS_MS).coerceIn(0.0, 1.0)
		return 1.0 + t * maxCreep
	}

	private fun maybeEmit(reactionMult: Double) {
		val bus = bus ?: return
		val now = clock.instant()
		val sinceLast = Duration.between(lastEmitAt, now)
		val drift = abs(reactionMult - lastEmitReaction)
		if (sinceLast.seconds < 60L && drift < 0.01) return
		lastEmitAt = now
		lastEmitReaction = reactionMult
		val ageMs = Duration.between(sessionStart, now).toMillis().coerceAtLeast(0)
		bus.tryEmit(FatigueUpdated(
			sessionId = sessionIdProvider(),
			sessionAgeMillis = ageMs,
			reactionMultiplier = reactionMult,
			misclickMultiplier = misclickMultiplier(),
			overshootVarianceMultiplier = overshootVarianceMultiplier(),
			fidgetRateMultiplier = fidgetRateMultiplier()
		))
	}

	companion object {
		private const val FOUR_HOURS_MS: Long = 4L * 60L * 60L * 1000L
		private const val MAX_REACTION_CREEP = 0.10
		private const val MAX_MISCLICK_CREEP = 0.35
		private const val MAX_OVERSHOOT_VARIANCE_CREEP = 0.25
		private const val MAX_FIDGET_CREEP = 0.40
	}
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests FatigueCurveTest --no-daemon 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/FatigueCurve.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/FatigueCurveTest.kt
git commit -m "BuildCore: add FatigueCurve with linear-interp multipliers + debounced emit (Plan 4a spec §9.3)"
git push origin main
```

---

### Task 16 — `ReactionDelay`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelay.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelayTest.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/GraduatedThrottleIntegrationTest.kt`

- [ ] **Step 1: Check Plan 2's GraduatedThrottle surface**

```bash
grep -n "class GraduatedThrottle\|fun reactionMultiplier\|fun xpTaskSwitchMultiplier" /c/Code/VitalPlugins/BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottle.kt
```

If the method name in Plan 2 is `reactionTimeMultiplier` or anything other than `reactionMultiplier`, adapt the `ReactionDelay.sample` call site. Expected Plan 2 shape:
```kotlin
class GraduatedThrottle(accountAgeDays: Int, totalXp: Long) {
    fun reactionMultiplier(): Double { /* 1.5 → 1.0 */ }
}
```

- [ ] **Step 2: Write the failing tests**

`ReactionDelayTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class ReactionDelayTest {

	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `same seed and state produce identical delay`() {
		val personality = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		val a = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(42L), InputMode.NORMAL)
		val b = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(42L), InputMode.NORMAL)
		assertEquals(a, b)
	}

	@Test
	fun `delay is within bounded range`() {
		val personality = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		repeat(100) { seed ->
			val delay = ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(seed.toLong()), InputMode.NORMAL)
			assertTrue(delay in 1L..5000L, "delay=$delay out of bounds on seed=$seed")
		}
	}

	@Test
	fun `PRECISION mode throws — 4b not yet installed`() {
		val personality = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		assertThrows(IllegalArgumentException::class.java) {
			ReactionDelay.sample(personality, fatigue, null, JavaUtilRng(1L), InputMode.PRECISION)
		}
	}
}
```

`GraduatedThrottleIntegrationTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.BreakBias
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class GraduatedThrottleIntegrationTest {

	private fun samplePersonality() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `fresh throttle produces longer delay than mature throttle on same seed`() {
		val p = samplePersonality()
		val fatigue = FatigueCurve(Instant.ofEpochSecond(0), Clock.fixed(Instant.ofEpochSecond(0), java.time.ZoneOffset.UTC))
		val fresh = GraduatedThrottle(accountAgeDays = 0, totalXp = 0L)
		val mature = GraduatedThrottle(accountAgeDays = 999, totalXp = Long.MAX_VALUE)
		val freshDelay = ReactionDelay.sample(p, fatigue, fresh, JavaUtilRng(42L), InputMode.NORMAL)
		val matureDelay = ReactionDelay.sample(p, fatigue, mature, JavaUtilRng(42L), InputMode.NORMAL)
		assertTrue(freshDelay > matureDelay, "fresh=$freshDelay matureDelay=$matureDelay")
	}
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests ReactionDelayTest --tests GraduatedThrottleIntegrationTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 4: Write `ReactionDelay.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.timing

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.task.GraduatedThrottle

/**
 * Samples a pre-action reaction delay in milliseconds as:
 *
 *   effectiveDelay = personality.nextLogNormal(reactionLogMean, reactionLogStddev)
 *                  × fatigue.reactionMultiplier()
 *                  × throttle.reactionMultiplier()
 *
 * Clamped to [1, 5000]ms.
 *
 * Spec §9.2.
 */
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

- [ ] **Step 5: Adapt to Plan 2's real GraduatedThrottle shape if needed**

If Step 1's grep showed that `GraduatedThrottle` in Plan 2 does not expose `reactionMultiplier()`, open its Kotlin file and add the adapter:

```kotlin
// Add to GraduatedThrottle.kt if not already present
fun reactionMultiplier(): Double = /* existing freshness-based multiplier */
```

- [ ] **Step 6: Run tests to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests ReactionDelayTest --tests GraduatedThrottleIntegrationTest --no-daemon 2>&1 | tail -10
```
Expected: 4 tests passing (3 ReactionDelayTest + 1 integration).

- [ ] **Step 7: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelay.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/ReactionDelayTest.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/antiban/timing/GraduatedThrottleIntegrationTest.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/GraduatedThrottle.kt
git commit -m "BuildCore: add ReactionDelay — personality × fatigue × throttle composition (Plan 4a spec §9)"
git push origin main
```

(If GraduatedThrottle wasn't modified, drop that from the `git add`.)

---

## Phase 7 — Primitive singletons (Tasks 17-19)

### Task 17 — `Mouse` singleton

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt`

- [ ] **Step 1: Write `Mouse.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.antiban.curve.Overshoot
import net.vital.plugins.buildcore.core.antiban.curve.WindMouse
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import java.util.UUID

/**
 * The one public mouse API. All fields except [backend] are installed by
 * [net.vital.plugins.buildcore.core.antiban.AntibanBootstrap.install]; tests
 * swap any field.
 *
 * Every primitive is `suspend fun` because reaction delays use
 * [kotlinx.coroutines.delay].
 *
 * Spec §7.4.
 */
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

		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))

		val from = backend.currentPosition()
		val path = WindMouse.generatePath(
			from, target,
			personality.mouseCurveGravity, personality.mouseCurveWind,
			personality.mouseSpeedCenter, rng
		)
		var totalMs = 0L
		for ((p, stepMs) in path) {
			backend.appendTrailPoint(p.x, p.y)
			if (stepMs > 0) {
				delay(stepMs.toLong())
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

	suspend fun click(button: MouseButton = MouseButton.LEFT, mode: InputMode = InputMode.NORMAL) {
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

	suspend fun moveAndClick(target: Point, button: MouseButton = MouseButton.LEFT,
	                         mode: InputMode = InputMode.NORMAL) {
		moveTo(target, mode)
		click(button, mode)
	}
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
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Mouse.kt
git commit -m "BuildCore: add Mouse singleton (moveTo/click/moveAndClick with full timing) (Plan 4a spec §7.4)"
git push origin main
```

---

### Task 18 — `Keyboard` singleton

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Keyboard.kt`

- [ ] **Step 1: Write `Keyboard.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import java.util.UUID

/**
 * The one public keyboard API. Spec §7.4.
 */
object Keyboard {

	@Volatile internal var backend: KeyboardBackend = VitalApiKeyboardBackend
	@Volatile internal var personalityProvider: PersonalityProvider? = null
	@Volatile internal var sessionRng: SessionRng? = null
	@Volatile internal var fatigue: FatigueCurve? = null
	@Volatile internal var throttle: GraduatedThrottle? = null
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun tap(key: Key, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.tap(key.vk)
		emit(InputKind.KEY_TAP, mode)
	}

	suspend fun keyDown(key: Key, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.keyDown(key.vk)
		emit(InputKind.KEY_DOWN, mode)
	}

	suspend fun keyUp(key: Key, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.keyUp(key.vk)
		emit(InputKind.KEY_UP, mode)
	}

	suspend fun type(text: String, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.type(text)
		emit(InputKind.KEY_TYPE, mode, durationMillis = text.length.toLong())
	}

	private suspend fun reactionDelay(mode: InputMode) {
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)
		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))
	}

	private fun emit(kind: InputKind, mode: InputMode, durationMillis: Long = 0L) {
		bus?.tryEmit(InputAction(
			sessionId = sessionIdProvider(),
			kind = kind,
			targetX = null, targetY = null,
			durationMillis = durationMillis,
			mode = mode
		))
	}
}
```

- [ ] **Step 2: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Keyboard.kt
git commit -m "BuildCore: add Keyboard singleton (tap/keyDown/keyUp/type) (Plan 4a spec §7.4)"
git push origin main
```

---

### Task 19 — `Camera` singleton

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Camera.kt`

- [ ] **Step 1: Write `Camera.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban.input

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import java.util.UUID

/**
 * The one public camera API. Uses VitalAPI's absolute rotation model
 * (`targetRotation` ∈ 0..2047, `targetPitch` ∈ ~128..383) — NOT relative
 * degrees.
 *
 * Spec §7.4.
 */
object Camera {

	@Volatile internal var backend: CameraBackend = VitalApiCameraBackend
	@Volatile internal var personalityProvider: PersonalityProvider? = null
	@Volatile internal var sessionRng: SessionRng? = null
	@Volatile internal var fatigue: FatigueCurve? = null
	@Volatile internal var throttle: GraduatedThrottle? = null
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun rotateTo(targetRotation: Int, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.rotateTo(targetRotation)
		emit(InputKind.CAMERA_ROTATE, mode)
	}

	suspend fun setPitchTo(targetPitch: Int, mode: InputMode = InputMode.NORMAL) {
		reactionDelay(mode)
		backend.setPitchTo(targetPitch)
		emit(InputKind.CAMERA_PITCH, mode)
	}

	private suspend fun reactionDelay(mode: InputMode) {
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)
		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))
	}

	private fun emit(kind: InputKind, mode: InputMode) {
		bus?.tryEmit(InputAction(
			sessionId = sessionIdProvider(),
			kind = kind,
			targetX = null, targetY = null,
			durationMillis = 0L,
			mode = mode
		))
	}
}
```

- [ ] **Step 2: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/input/Camera.kt
git commit -m "BuildCore: add Camera singleton with VitalAPI absolute rotation model (Plan 4a spec §7.4)"
git push origin main
```

---

## Phase 8 — Bootstrap wiring (Tasks 20-21)

### Task 20 — `AntibanBootstrap`

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/AntibanBootstrap.kt`

- [ ] **Step 1: Write `AntibanBootstrap.kt`**

```kotlin
package net.vital.plugins.buildcore.core.antiban

import net.vital.plugins.buildcore.core.antiban.input.Camera
import net.vital.plugins.buildcore.core.antiban.input.Keyboard
import net.vital.plugins.buildcore.core.antiban.input.Mouse
import net.vital.plugins.buildcore.core.antiban.input.VitalApiCameraBackend
import net.vital.plugins.buildcore.core.antiban.input.VitalApiKeyboardBackend
import net.vital.plugins.buildcore.core.antiban.input.VitalApiMouseBackend
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityStore
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin-bootstrap-time wiring for the antiban layer.
 *
 * Called exactly once from `BuildCorePlugin.startUp()` after
 * `UncaughtExceptionHandler.install`. Subsequent calls are no-ops.
 *
 * Spec §4.1.
 */
object AntibanBootstrap {

	private val installed = AtomicBoolean(false)

	@Volatile var personalityProvider: PersonalityProvider? = null
		private set

	@Volatile var sessionRng: SessionRng? = null
		private set

	@Volatile var fatigue: FatigueCurve? = null
		private set

	@Volatile var throttle: GraduatedThrottle = GraduatedThrottle(
		accountAgeDays = 999,
		totalXp = Long.MAX_VALUE
	)
		private set

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		layout: LogDirLayout,
		clock: Clock = Clock.systemUTC()
	) {
		if (!installed.compareAndSet(false, true)) return

		val store = PersonalityStore(layout.personalityDir())
		val provider = PersonalityProvider(store, bus, sessionIdProvider)
		val rng = SessionRng.fresh()
		val fatigueCurve = FatigueCurve(
			sessionStart = clock.instant(),
			clock = clock,
			bus = bus,
			sessionIdProvider = sessionIdProvider
		)

		bus.tryEmit(SessionRngSeeded(sessionId = sessionIdProvider(), seed = rng.seed))

		personalityProvider = provider
		sessionRng = rng
		fatigue = fatigueCurve

		// Wire primitives. Backends remain the VitalApi defaults installed at object
		// construction — plugin startUp never needs to swap them.
		Mouse.backend = VitalApiMouseBackend
		Mouse.personalityProvider = provider
		Mouse.sessionRng = rng
		Mouse.fatigue = fatigueCurve
		Mouse.throttle = throttle
		Mouse.bus = bus
		Mouse.sessionIdProvider = sessionIdProvider

		Keyboard.backend = VitalApiKeyboardBackend
		Keyboard.personalityProvider = provider
		Keyboard.sessionRng = rng
		Keyboard.fatigue = fatigueCurve
		Keyboard.throttle = throttle
		Keyboard.bus = bus
		Keyboard.sessionIdProvider = sessionIdProvider

		Camera.backend = VitalApiCameraBackend
		Camera.personalityProvider = provider
		Camera.sessionRng = rng
		Camera.fatigue = fatigueCurve
		Camera.throttle = throttle
		Camera.bus = bus
		Camera.sessionIdProvider = sessionIdProvider
	}

	fun installThrottle(newThrottle: GraduatedThrottle) {
		throttle = newThrottle
		Mouse.throttle = newThrottle
		Keyboard.throttle = newThrottle
		Camera.throttle = newThrottle
	}

	// Test-only helper. Plan 4a's tests substitute fakes via @BeforeEach;
	// this resets installed state so a fresh install can run.
	internal fun resetForTests() {
		installed.set(false)
		personalityProvider = null
		sessionRng = null
		fatigue = null
	}
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
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/AntibanBootstrap.kt
git commit -m "BuildCore: add AntibanBootstrap wiring + SessionRngSeeded emit (Plan 4a spec §4.1)"
git push origin main
```

---

### Task 21 — Wire `BuildCorePlugin.startUp` + integration test

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/AntibanBootstrapIntegrationTest.kt`

- [ ] **Step 1: Add `AntibanBootstrap.install` call to `BuildCorePlugin.startUp`**

In `BuildCorePlugin.kt`, find the last line of `startUp()` (probably `UncaughtExceptionHandler.install(...)`). After it, add:

```kotlin
		AntibanBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId },
			layout = layout
		)
```

Add the import:

```kotlin
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
```

- [ ] **Step 2: Write the integration test**

```kotlin
package net.vital.plugins.buildcore.integration

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
import net.vital.plugins.buildcore.core.antiban.input.FakeKeyboardBackend
import net.vital.plugins.buildcore.core.antiban.input.FakeMouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Keyboard
import net.vital.plugins.buildcore.core.antiban.input.Mouse
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LoggerScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class AntibanBootstrapIntegrationTest {

	@BeforeEach fun reset() { AntibanBootstrap.resetForTests() }
	@AfterEach fun tearDown() { AntibanBootstrap.resetForTests() }

	@Test
	fun `install emits SessionRngSeeded on the bus`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(tmp)

		val latch = CountDownLatch(1)
		val captured = CopyOnWriteArrayList<SessionRngSeeded>()
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<SessionRngSeeded>()
				.collect { captured += it }
		}
		latch.await()

		AntibanBootstrap.install(bus, { sid }, layout)

		withTimeout(2000) { while (captured.isEmpty()) yield() }
		assertTrue(captured.first().seed != 0L)
		scope.close()
	}

	@Test
	fun `Mouse moveAndClick with fake backend emits InputAction events`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(tmp)

		val actions = CopyOnWriteArrayList<InputAction>()
		val latch = CountDownLatch(1)
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<InputAction>()
				.collect { actions += it }
		}
		latch.await()

		AntibanBootstrap.install(bus, { sid }, layout)
		// Swap the real VitalAPI backend for the fake — tests can't call real VitalAPI
		val fakeMouse = FakeMouseBackend()
		Mouse.backend = fakeMouse

		Mouse.moveAndClick(Point(200, 200))

		withTimeout(10_000) { while (actions.size < 2) yield() }
		assertTrue(actions.any { it.kind == InputKind.MOUSE_MOVE })
		assertTrue(actions.any { it.kind == InputKind.MOUSE_CLICK })
		assertTrue(fakeMouse.trailPoints.isNotEmpty())
		assertTrue(fakeMouse.clicks.isNotEmpty())
		scope.close()
	}
}
```

Note: the second test has a 10-second timeout because reaction delay + path generation can take that long on slow machines; the test uses deterministic fakes so completion is guaranteed.

- [ ] **Step 3: Run tests to verify pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests AntibanBootstrapIntegrationTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 4: Run the full suite — critical gate**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: 94 (Plan 3) + ~30 new = ~124+ passing, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/AntibanBootstrapIntegrationTest.kt
git commit -m "BuildCore: wire AntibanBootstrap.install into plugin startUp + integration test (Plan 4a spec §4.1)"
git push origin main
```

---

## Phase 9 — Architecture tests (Task 22)

### Task 22 — `AntibanArchitectureTest` (8 invariants)

**Files:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/AntibanArchitectureTest.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the antiban layer. Each test cites the
 * foundation spec §9 or Plan 4a spec section it enforces.
 *
 * Spec §11 (Plan 4a).
 */
class AntibanArchitectureTest {

	/**
	 * Plan 4a spec §11 #1 — vital.api.input.* imports only in the
	 * three VitalApi*Backend.kt files. Everywhere else in BuildCore
	 * must go through Mouse/Keyboard/Camera singletons.
	 */
	@Test
	fun `vital api input imports only in VitalApi backend files`() {
		val allowedFiles = setOf(
			"VitalApiMouseBackend", "VitalApiKeyboardBackend", "VitalApiCameraBackend"
		)
		Konsist
			.scopeFromProduction()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name !in allowedFiles }
			.assertFalse { file ->
				file.imports.any { it.name.startsWith("vital.api.input.") }
			}
	}

	/**
	 * Plan 4a spec §11 #2 — java.util.Random only in JavaUtilRng.kt.
	 * All other code uses the SeededRng interface so Plan 4c can wrap it.
	 */
	@Test
	fun `java util Random imported only in JavaUtilRng`() {
		Konsist
			.scopeFromProduction()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name != "JavaUtilRng" }
			.assertFalse { file ->
				file.imports.any { it.name == "java.util.Random" }
			}
	}

	/**
	 * Plan 4a spec §11 #3 — java.security.SecureRandom only in SessionRng.kt.
	 */
	@Test
	fun `SecureRandom imported only in SessionRng`() {
		Konsist
			.scopeFromProduction()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name != "SessionRng" }
			.assertFalse { file ->
				file.imports.any { it.name == "java.security.SecureRandom" }
			}
	}

	/**
	 * Plan 4a spec §11 #4 — all PersonalityVector properties are `val`, none mutable.
	 */
	@Test
	fun `PersonalityVector properties are all val`() {
		Konsist
			.scopeFromProduction()
			.classes()
			.filter { it.name == "PersonalityVector" }
			.flatMap { it.properties() }
			.assertFalse { prop -> prop.hasVarModifier }
	}

	/**
	 * Plan 4a spec §11 #5 — PersonalityVector has exactly 16 properties
	 * (schemaVersion + 15 dimensions). Adding a 17th is a conscious change
	 * that bumps this count.
	 */
	@Test
	fun `PersonalityVector has exactly 16 properties`() {
		val klass = Konsist
			.scopeFromProduction()
			.classes()
			.first { it.name == "PersonalityVector" }
		val count = klass.properties().size
		val expected = 16
		if (count != expected) {
			throw AssertionError("PersonalityVector property count is $count, expected $expected — " +
				"adding/removing a dimension requires bumping this test and PersonalityVector.schemaVersion")
		}
	}

	/**
	 * Plan 4a spec §11 #6 — PersonalityVector property declaration order matches
	 * PersonalityGenerator.generate call order. Reordering silently changes every
	 * existing persisted personality.
	 *
	 * Enforcement: extract the property names in the order they appear in
	 * PersonalityVector, then compare with the assignment order parsed from
	 * PersonalityGenerator. Using file text scan because Konsist's call-expr
	 * inspection is heavy.
	 */
	@Test
	fun `PersonalityGenerator draw order matches PersonalityVector field order`() {
		val vectorClass = Konsist.scopeFromProduction().classes()
			.first { it.name == "PersonalityVector" }
		val fieldOrder = vectorClass.properties()
			.map { it.name }
			.filter { it != "schemaVersion" }   // schemaVersion is not drawn

		val generatorFile = Konsist.scopeFromProduction().files
			.first { it.name == "PersonalityGenerator" }
		val genBody = generatorFile.text
		// Extract assignments like "mouseSpeedCenter = rng..." in order
		val pattern = Regex("""(\w+)\s*=\s*(?:rng|BreakBias)""")
		val generatorOrder = pattern.findAll(genBody).map { it.groupValues[1] }.toList()

		if (fieldOrder != generatorOrder) {
			throw AssertionError(
				"PersonalityVector field order and PersonalityGenerator draw order must match.\n" +
				"Vector:    $fieldOrder\n" +
				"Generator: $generatorOrder"
			)
		}
	}

	/**
	 * Plan 4a spec §11 #7 — Mouse/Keyboard/Camera expose no public mutable state.
	 * All `var` fields must be `internal var` (visible to AntibanBootstrap + tests,
	 * invisible to consumers).
	 */
	@Test
	fun `Mouse Keyboard Camera have no public var fields`() {
		val targets = setOf("Mouse", "Keyboard", "Camera")
		Konsist
			.scopeFromProduction()
			.objects()
			.filter { it.name in targets && it.resideInPackage("net.vital.plugins.buildcore.core.antiban.input..") }
			.flatMap { it.properties() }
			.filter { it.hasVarModifier }
			.assertTrue { prop -> prop.hasInternalModifier }
	}

	/**
	 * Plan 4a spec §11 #8 — all primitives are `suspend fun` because reaction
	 * delays are non-optional. A non-suspend primitive would bypass timing.
	 */
	@Test
	fun `Mouse Keyboard Camera primitive functions are all suspend`() {
		val targets = setOf("Mouse", "Keyboard", "Camera")
		Konsist
			.scopeFromProduction()
			.objects()
			.filter { it.name in targets && it.resideInPackage("net.vital.plugins.buildcore.core.antiban.input..") }
			.flatMap { it.functions() }
			.filter { it.hasPublicOrDefaultModifier && !it.name.startsWith("emit") && !it.name.startsWith("reactionDelay") }
			.assertTrue { fn -> fn.hasSuspendModifier }
	}
}
```

- [ ] **Step 2: Run the architecture tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests AntibanArchitectureTest --no-daemon 2>&1 | tail -20
```
Expected: 8 tests passing. If any fail due to Konsist 0.17.3 API quirks (same caveat from Plan 3 Task 21), adapt as follows:

- `hasVarModifier` vs `hasModifier(KoModifier.VAR)` — Konsist 0.17.3 uses `hasVarModifier`.
- `hasInternalModifier` / `hasPublicOrDefaultModifier` / `hasSuspendModifier` — these boolean properties exist on KoDeclaration.
- `scopeFromProduction()` to avoid scanning test files.

If a test fails for a real reason (actual violation), fix the violation — don't weaken the test.

- [ ] **Step 3: Run the full suite**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: all tests passing.

- [ ] **Step 4: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/AntibanArchitectureTest.kt
git commit -m "BuildCore: add 8 antiban architecture invariants (Plan 4a spec §11)"
git push origin main
```

---

## Phase 10 — Docs (Task 23)

### Task 23 — Update BuildCore CLAUDE.md

**File:** Modify `BuildCore/CLAUDE.md`

- [ ] **Step 1: Update Status section**

Replace the existing Status block in `BuildCore/CLAUDE.md` with:

```markdown
## Status

**Foundation phase — Plans 1 + 2 + 3 + 4a complete.**

Future plans in `../docs/superpowers/plans/`:
- ~~Plan 2 — Core Runtime + Task SPI + Restrictions~~ (done)
- ~~Plan 3 — Logging + Event Bus~~ (done)
- ~~Plan 4a — RNG + Personality + Input Primitives~~ (done)
- Plan 4b — Precision Mode + 4-tier break system + Safe-Stop integration
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
Current invariants (Plans 2 + 3 + 4a):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions.
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet.
- Every `BusEvent` subtype has a `PrivacyScrubber` case (exhaustive-when + drift test, 27 subtypes).
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
```

- [ ] **Step 3: Commit and push**

```bash
cd /c/Code/VitalPlugins
git add BuildCore/CLAUDE.md
git commit -m "BuildCore: mark Plan 4a complete in subproject CLAUDE.md"
git push origin main
```

---

## Self-review

### Spec coverage

| Spec section | Implementing task(s) |
|---|---|
| §1 scope & deliverables | Phases 1-10 collectively |
| §2 decision log | Embedded in Tasks 1-23 |
| §3 package layout | Tasks 4-22 produce exactly this layout |
| §4.1 composition root | Tasks 20-21 |
| §4.2 dependency diagram | Tasks 4-21 |
| §4.3 data flow contracts | Task 17 (Mouse), 20 (bootstrap) |
| §5 RNG layer | Tasks 4-6 |
| §6 PersonalityVector schema & persistence | Tasks 7-10 |
| §7 Input primitives + backends | Tasks 11-13, 17-19 |
| §8 WindMouse | Task 14 |
| §9 Reaction delay / fatigue / throttle | Tasks 15-16 |
| §10 Events & scrubber updates | Tasks 2-3 |
| §11 Architecture tests | Task 22 |
| §12 Testing strategy | Embedded in Tasks 4-22 (39 new tests) |
| §13 Configuration surface | N/A in 4a (no new LogConfig fields) |
| §14 Deferrals | Task 23 (CLAUDE.md + deferrals listed in spec) |

All sections mapped. No gaps.

### Placeholder scan

No `TBD`, `TODO`, `FIXME`, or "similar to" references. Every code block is complete. Every commit uses a concrete message. One adaptive instruction in Task 16 Step 1 — "check whether GraduatedThrottle has `reactionMultiplier` or a different name" — is unavoidable because Plan 2's exact method surface isn't re-declared in this plan; the implementer's adaptation scope is strictly "add one method to GraduatedThrottle if missing".

### Type consistency

Verified:
- `SeededRng` methods: `nextLong`, `nextInt`, `nextIntInRange`, `nextDouble`, `nextDoubleInRange`, `nextGaussian`, `nextLogNormal`, `nextBoolean`, `shuffled`. Used consistently across `JavaUtilRng`, `WindMouse`, `PersonalityGenerator`, `ReactionDelay`, `Overshoot`.
- `PersonalityVector` field names match across Vector → Generator → Store → ReactionDelay consumers.
- `MouseBackend.appendTrailPoint(x: Int, y: Int)` signature matches between interface, fakes, `VitalApiMouseBackend`, and `WindMouse` caller site.
- `CameraBackend` uses absolute rotation model consistently (`currentRotation`, `currentPitch`, `rotateTo`, `setPitchTo`) — matches VitalAPI.
- `InputAction.kind: InputKind` enum values consistently emitted: `MOUSE_MOVE`, `MOUSE_CLICK`, `KEY_TAP`, `KEY_DOWN`, `KEY_UP`, `KEY_TYPE`, `CAMERA_ROTATE`, `CAMERA_PITCH`.
- `PersonalityProvider.currentPersonality(sessionRng)` signature matches calls from Mouse, Keyboard, Camera singletons.
- `AntibanBootstrap.install(bus, sessionIdProvider, layout, clock?)` parameters match `BuildCorePlugin.startUp` call site.

No drift.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-24-plan-4a-rng-personality-input.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
