# BuildCore Plan 1 — Project Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the BuildCore subproject inside VitalPlugins with a Gradle+Kotlin build, package skeleton, event bus primitive, architecture-test infrastructure, minimal Swing+FlatLaf window that opens, CI scaffolding, and documentation — leaving every later plan free to focus on its own subsystem.

**Architecture:** Gradle subproject at `VitalPlugins/BuildCore/` mirroring the existing `walker`/`autologin` pattern, with Kotlin 2.1.0 + Java 11 target. Packages laid out for all foundation subsystems (empty placeholders). Event bus implemented as a Kotlin `MutableSharedFlow<BusEvent>` — the single primitive on which all future logging, telemetry, GUI, watchdog work will build. Architecture tests via Konsist. GUI via Swing+FlatLaf (IntelliJ Darcula). PF4J is the plugin framework (inherited from VitalPlugins root build).

**Tech Stack:** Kotlin 2.1.0 on JDK 11, Gradle 8.8 (wrapper included), kotlinx-coroutines 1.9.0, FlatLaf 3.5 (IntelliJ themes), JUnit 5 (Jupiter) + MockK 1.13 for tests, Konsist 0.17 for architecture tests, PF4J + RuneLite plugin plumbing (existing root config).

**Prerequisites (engineer reads before starting):**
- Spec: [`docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md`](../specs/2026-04-21-buildcore-foundation-design.md)
- Existing patterns: [`VitalPlugins/walker/walker.gradle.kts`](../../../walker/walker.gradle.kts), root [`build.gradle.kts`](../../../build.gradle.kts), [`settings.gradle.kts`](../../../settings.gradle.kts), [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml)
- Working dir: `C:\Code\VitalPlugins` (Windows, bash shell)
- Author identity: commits authored as "Chich" only — **no Co-Authored-By trailers**

---

## File structure this plan produces

```
VitalPlugins/                                   # existing repo
├── settings.gradle.kts                         # MODIFY — add "BuildCore" to include list
├── gradle/libs.versions.toml                   # MODIFY — add kotlin, coroutines, flatlaf, konsist, junit, mockk
├── CLAUDE.md                                   # MODIFY — add BuildCore subproject to project table
├── .github/workflows/ci.yml                    # CREATE — minimal CI (build + test)
├── docs/
│   ├── specs/                                  # existing
│   └── plans/
│       └── 2026-04-21-plan-1-bootstrap.md      # THIS FILE
└── BuildCore/                                  # NEW subproject
    ├── buildcore.gradle.kts                    # CREATE — Kotlin plugin, deps, manifest
    ├── CLAUDE.md                               # CREATE — subproject guidance
    ├── README.md                               # CREATE — dev setup
    └── src/
        ├── main/kotlin/net/vital/plugins/buildcore/
        │   ├── BuildCorePlugin.kt              # CREATE — RuneLite Plugin entry
        │   ├── core/
        │   │   ├── events/
        │   │   │   ├── BusEvent.kt             # CREATE — sealed class marker
        │   │   │   └── EventBus.kt             # CREATE — SharedFlow wrapper
        │   │   ├── task/package-info.kt        # CREATE — placeholder
        │   │   ├── restrictions/package-info.kt
        │   │   ├── antiban/package-info.kt
        │   │   ├── services/package-info.kt
        │   │   ├── watchdog/package-info.kt
        │   │   ├── config/package-info.kt
        │   │   ├── logging/package-info.kt
        │   │   └── licensing/package-info.kt
        │   └── gui/
        │       ├── BuildCoreWindow.kt          # CREATE — minimal Swing window
        │       └── theme/
        │           └── FlatLafTheme.kt         # CREATE — Darcula setup
        └── test/kotlin/net/vital/plugins/buildcore/
            ├── core/events/
            │   └── EventBusTest.kt             # CREATE
            ├── arch/
            │   └── LayeringTest.kt             # CREATE — Konsist arch tests
            └── gui/
                └── BuildCoreWindowSmokeTest.kt # CREATE
```

---

## Task 1 — Register BuildCore subproject in Gradle

**Files:**
- Modify: `settings.gradle.kts`
- Create: `BuildCore/buildcore.gradle.kts` (placeholder — fleshed out in later tasks)
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/.gitkeep`

- [ ] **Step 1: Add BuildCore to settings.gradle.kts include list**

Read current file first. Replace the `include(...)` call to add `"BuildCore"`:

```kotlin
// settings.gradle.kts
rootProject.name = "vital-plugins"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
	"walker",
	"autologin",
	"cow-killer",
	"test",
	"BuildCore"
)

for (project in rootProject.children) {
	project.apply {
		projectDir = file(name)
		buildFileName = "$name.gradle.kts"

		require(projectDir.isDirectory) { "Project '${project.path}' must have a $projectDir directory" }
		require(buildFile.isFile)       { "Project '${project.path}' must have a $buildFile build script" }
	}
}
```

Note: the loop expects `buildFileName = "$name.gradle.kts"` — since the project name is `BuildCore` the file must be `BuildCore/BuildCore.gradle.kts`. Windows is case-insensitive on filesystem but Gradle is case-sensitive on identifiers. Use exactly `BuildCore` throughout.

- [ ] **Step 2: Create placeholder BuildCore.gradle.kts**

```kotlin
// BuildCore/BuildCore.gradle.kts
version = "0.1.0"

project.extra["PluginName"]        = "BuildCore"
project.extra["PluginDescription"] = "All-inclusive OSRS account builder foundation"
```

- [ ] **Step 3: Create source directory placeholder**

```bash
mkdir -p BuildCore/src/main/kotlin/net/vital/plugins/buildcore
touch BuildCore/src/main/kotlin/net/vital/plugins/buildcore/.gitkeep
```

- [ ] **Step 4: Verify Gradle discovers the subproject**

Run:
```bash
./gradlew.bat :BuildCore:tasks --no-daemon 2>&1 | head -30
```

Expected: prints standard Gradle task list (build, check, test, etc.) with `Project ':BuildCore'` in header. No error about missing build file or project directory.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts BuildCore/
git commit -m "BuildCore: register empty subproject in Gradle"
```

---

## Task 2 — Add Kotlin + test + FlatLaf versions to the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add new versions and libraries to the catalog**

Replace contents of `gradle/libs.versions.toml` with:

```toml
[versions]
runelite              = "1.12.20-SNAPSHOT"
vital-api             = "1.0.0"
lombok                = "1.18.30"
pf4j                  = "3.6.0"
guice                 = "4.1.0"
javax-annotation      = "1.3.2"
jetbrains-annotations = "23.0.0"
kotlin                = "2.1.0"
coroutines            = "1.9.0"
flatlaf               = "3.5"
junit                 = "5.10.3"
mockk                 = "1.13.13"
konsist               = "0.17.3"

[libraries]
runelite-api          = { module = "net.vital:runelite-api",             version.ref = "runelite" }
runelite-client       = { module = "net.vital:client",                   version.ref = "runelite" }
vital-api             = { module = "net.vital:vital-api",                version.ref = "vital-api" }
lombok                = { module = "org.projectlombok:lombok",           version.ref = "lombok" }
guice                 = { module = "com.google.inject:guice",            version.ref = "guice" }
javax-annotation      = { module = "javax.annotation:javax.annotation-api", version.ref = "javax-annotation" }
jetbrains-annotations = { module = "org.jetbrains:annotations",          version.ref = "jetbrains-annotations" }
pf4j                  = { module = "org.pf4j:pf4j",                      version.ref = "pf4j" }

kotlin-stdlib         = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
kotlin-reflect        = { module = "org.jetbrains.kotlin:kotlin-reflect", version.ref = "kotlin" }
coroutines-core       = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-swing      = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
coroutines-test       = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

flatlaf               = { module = "com.formdev:flatlaf",                version.ref = "flatlaf" }
flatlaf-intellij      = { module = "com.formdev:flatlaf-intellij-themes", version.ref = "flatlaf" }
flatlaf-extras        = { module = "com.formdev:flatlaf-extras",         version.ref = "flatlaf" }

junit-bom             = { module = "org.junit:junit-bom",                version.ref = "junit" }
junit-jupiter         = { module = "org.junit.jupiter:junit-jupiter" }
mockk                 = { module = "io.mockk:mockk",                     version.ref = "mockk" }
konsist               = { module = "com.lemonappdev:konsist",            version.ref = "konsist" }
```

- [ ] **Step 2: Verify catalog parses**

Run:
```bash
./gradlew.bat :BuildCore:tasks --no-daemon 2>&1 | tail -5
```

Expected: no catalog errors. Task list prints normally.

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "BuildCore: add Kotlin, coroutines, FlatLaf, JUnit, MockK, Konsist to version catalog"
```

---

## Task 3 — Configure BuildCore.gradle.kts with Kotlin + all dependencies

**Files:**
- Modify: `BuildCore/BuildCore.gradle.kts`

- [ ] **Step 1: Replace BuildCore.gradle.kts with full configuration**

```kotlin
// BuildCore/BuildCore.gradle.kts
plugins {
	kotlin("jvm") version "2.1.0"
}

version = "0.1.0"

project.extra["PluginName"]        = "BuildCore"
project.extra["PluginDescription"] = "All-inclusive OSRS account builder foundation"

dependencies {
	// Kotlin runtime — bundled into shipped JAR since VitalShell classpath is unknown
	implementation(rootProject.libs.kotlin.stdlib)
	implementation(rootProject.libs.kotlin.reflect)
	implementation(rootProject.libs.coroutines.core)
	implementation(rootProject.libs.coroutines.swing)

	// GUI
	implementation(rootProject.libs.flatlaf)
	implementation(rootProject.libs.flatlaf.intellij)
	implementation(rootProject.libs.flatlaf.extras)

	// Test
	testImplementation(platform(rootProject.libs.junit.bom))
	testImplementation(rootProject.libs.junit.jupiter)
	testImplementation(rootProject.libs.mockk)
	testImplementation(rootProject.libs.konsist)
	testImplementation(rootProject.libs.coroutines.test)
}

kotlin {
	jvmToolchain(11)
}

tasks.test {
	useJUnitPlatform()
	testLogging {
		events("passed", "failed", "skipped")
		showStandardStreams = true
	}
}

tasks {
	jar {
		manifest {
			attributes(mapOf(
				"Plugin-Version"     to project.version,
				"Plugin-Id"          to project.name,
				"Plugin-Provider"    to project.extra["PluginProvider"],
				"Plugin-Description" to project.extra["PluginDescription"],
				"Plugin-License"     to project.extra["PluginLicense"]
			))
		}
	}
}
```

- [ ] **Step 2: Verify build file parses and dependencies resolve**

Run:
```bash
./gradlew.bat :BuildCore:dependencies --configuration runtimeClasspath --no-daemon 2>&1 | tail -40
```

Expected: dependency tree prints, including `kotlin-stdlib`, `kotlinx-coroutines-core`, `flatlaf`. No unresolved dependency errors.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/BuildCore.gradle.kts
git commit -m "BuildCore: configure Kotlin 2.1.0 + FlatLaf + JUnit5 + MockK + Konsist"
```

---

## Task 4 — Create package skeleton

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/restrictions/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/antiban/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/services/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/watchdog/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/config/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/licensing/package-info.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/package-info.kt`
- Delete: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/.gitkeep`

- [ ] **Step 1: Create package marker files**

Kotlin doesn't require `package-info.java`, but we use these as package-documentation markers. Each file has the same shape — only the package differs.

Template (replace `<SUBPACKAGE>` for each):

```kotlin
/**
 * BuildCore <SUBPACKAGE> subsystem.
 *
 * See docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md
 * for the overall architecture and this subsystem's contracts.
 */
package net.vital.plugins.buildcore.core.<SUBPACKAGE>
```

Concrete files to create (one per directory):

```bash
# Create directories first
mkdir -p BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/{events,task,restrictions,antiban,services,watchdog,config,logging,licensing}
mkdir -p BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/theme
```

Then write each `package-info.kt` with its correct package line. For example, `core/events/package-info.kt`:

```kotlin
/**
 * BuildCore event-bus subsystem.
 *
 * See docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md §13
 * for the event taxonomy and subscriber model.
 */
package net.vital.plugins.buildcore.core.events
```

Repeat pattern for: `core/task`, `core/restrictions`, `core/antiban`, `core/services`, `core/watchdog`, `core/config`, `core/logging`, `core/licensing`, and `gui` (the `gui` one references §15 instead of §13).

- [ ] **Step 2: Remove the .gitkeep placeholder**

```bash
rm BuildCore/src/main/kotlin/net/vital/plugins/buildcore/.gitkeep
```

- [ ] **Step 3: Verify Gradle compiles empty packages**

```bash
./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. No errors. Zero source files compiled (package-info files are compiled to empty classes but that's fine).

- [ ] **Step 4: Commit**

```bash
git add BuildCore/src/main/kotlin/
git commit -m "BuildCore: create package skeleton for all foundation subsystems"
```

---

## Task 5 — BusEvent sealed class (stub)

This is the root of the event taxonomy. Plan 3 will populate it with the ~50 concrete event types; Plan 1 only needs the marker.

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1: Write the stub**

```kotlin
package net.vital.plugins.buildcore.core.events

import java.time.Instant
import java.util.UUID

/**
 * Marker for every event that flows through [EventBus].
 *
 * Every event MUST carry correlation IDs. See spec §13 for the full
 * taxonomy — this class will acquire ~50 sealed subtypes in Plan 3.
 *
 * Events MUST be immutable data classes for thread-safe propagation
 * through [kotlinx.coroutines.flow.SharedFlow]. The [LayeringTest]
 * architecture test enforces this invariant.
 */
sealed interface BusEvent {
	val eventId: UUID
	val timestamp: Instant
	val sessionId: UUID
	val schemaVersion: Int
}

/**
 * Test-only event used to prove bus plumbing works. Plan 3 removes this
 * when the real event taxonomy lands.
 */
internal data class TestPing(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	val payload: String
) : BusEvent
```

- [ ] **Step 2: Verify compiles**

```bash
./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt
git commit -m "BuildCore: add BusEvent sealed interface with correlation IDs"
```

---

## Task 6 — EventBus test (failing)

**Files:**
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/EventBusTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package net.vital.plugins.buildcore.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

	@Test
	fun `emitted events are received by subscribers`() = runTest {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()
		val event = TestPing(sessionId = sessionId, payload = "hello")

		val received = mutableListOf<BusEvent>()
		val subscription = launch {
			bus.events.take(1).toList(received)
		}

		bus.emit(event)
		subscription.join()

		assertEquals(1, received.size)
		assertEquals("hello", (received[0] as TestPing).payload)
	}

	@Test
	fun `bus has replay buffer zero — late subscribers do not see past events`() = runTest {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()

		bus.emit(TestPing(sessionId = sessionId, payload = "early"))

		// Attach subscriber AFTER emit
		val second = TestPing(sessionId = sessionId, payload = "late")
		val received = mutableListOf<BusEvent>()
		val subscription = launch {
			bus.events.take(1).toList(received)
		}
		bus.emit(second)
		subscription.join()

		assertEquals(1, received.size)
		assertEquals("late", (received[0] as TestPing).payload)
	}

	@Test
	fun `multiple subscribers all receive the same event`() = runTest {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()
		val event = TestPing(sessionId = sessionId, payload = "fanout")

		val a = mutableListOf<BusEvent>()
		val b = mutableListOf<BusEvent>()

		val subA = launch { bus.events.take(1).toList(a) }
		val subB = launch { bus.events.take(1).toList(b) }

		bus.emit(event)
		subA.join()
		subB.join()

		assertEquals(1, a.size)
		assertEquals(1, b.size)
		assertTrue(a[0] === b[0] || a[0] == b[0])
	}
}
```

- [ ] **Step 2: Run the test — expect FAIL (EventBus does not exist)**

```bash
./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.events.EventBusTest" 2>&1 | tail -20
```

Expected: compilation fails with `Unresolved reference: EventBus` or similar. This is the failing-test checkpoint.

- [ ] **Step 3: Commit the failing test**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/EventBusTest.kt
git commit -m "BuildCore: add failing EventBus contract tests"
```

---

## Task 7 — EventBus implementation

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/EventBus.kt`

- [ ] **Step 1: Write the implementation**

```kotlin
package net.vital.plugins.buildcore.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The single event bus for the entire BuildCore runtime.
 *
 * Contract:
 *  - Implemented as a [MutableSharedFlow] with replay buffer 0 — late
 *    subscribers do NOT see past events. This is a deliberate choice:
 *    logging, telemetry, GUI status, and watchdog each attach at startup
 *    and consume forward.
 *  - Emission is non-blocking from the caller's perspective. Slow
 *    subscribers must buffer on their own side; see spec §13.
 *  - Events MUST be immutable data classes or objects. Enforced by
 *    architecture test [net.vital.plugins.buildcore.arch.LayeringTest].
 *
 * Thread safety: [MutableSharedFlow] is thread-safe for concurrent
 * emitters and collectors.
 */
class EventBus {

	private val _events = MutableSharedFlow<BusEvent>(
		replay = 0,
		extraBufferCapacity = 256
	)

	/** Read-side flow for subscribers. */
	val events: SharedFlow<BusEvent> = _events.asSharedFlow()

	/**
	 * Emit an event. Suspends only if extraBufferCapacity is exhausted
	 * AND at least one subscriber is present and slow. In typical use
	 * this returns immediately.
	 */
	suspend fun emit(event: BusEvent) {
		_events.emit(event)
	}

	/**
	 * Non-suspending emit. Returns false if the buffer was full and the
	 * event was dropped. Use for call sites that cannot suspend (e.g.,
	 * from inside JNI callbacks or fatal paths).
	 */
	fun tryEmit(event: BusEvent): Boolean = _events.tryEmit(event)
}
```

- [ ] **Step 2: Run the tests — expect PASS**

```bash
./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.core.events.EventBusTest" 2>&1 | tail -15
```

Expected:
```
EventBusTest > emitted events are received by subscribers() PASSED
EventBusTest > bus has replay buffer zero — late subscribers do not see past events() PASSED
EventBusTest > multiple subscribers all receive the same event() PASSED
BUILD SUCCESSFUL
```

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/EventBus.kt
git commit -m "BuildCore: implement EventBus on MutableSharedFlow (replay buffer 0)"
```

---

## Task 8 — Architecture test infrastructure (Konsist)

Konsist walks the compiled code and asserts architectural rules. We wire it up now with one simple rule; later plans add more rules as subsystems land.

**Files:**
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LayeringTest.kt`

- [ ] **Step 1: Write the architecture-test class**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Architecture tests for BuildCore.
 *
 * These are guardrails — they fail the build if future edits violate
 * core layering rules from the foundation spec §16.
 *
 * Each rule here should cite the spec section it enforces.
 */
class LayeringTest {

	/**
	 * Spec §16, §13: bus events must be immutable — implemented as
	 * Kotlin data classes or objects (no var properties).
	 *
	 * Prevents mutation-in-flight that would break thread safety when
	 * events fan out to many subscribers on a SharedFlow.
	 */
	@Test
	fun `BusEvent subtypes are data classes or objects`() {
		Konsist
			.scopeFromProject()
			.classes()
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.events..") }
			.filter { klass -> klass.parents().any { it.name == "BusEvent" } }
			.assertTrue { klass ->
				klass.hasDataModifier || klass.hasValueModifier
			}
	}

	/**
	 * Spec §16: no class outside `core.events` may write directly to the
	 * bus's MutableSharedFlow — only via [EventBus.emit] / [EventBus.tryEmit].
	 *
	 * For now we enforce the broader rule: MutableSharedFlow is only used
	 * inside `core.events`. Tightens further in Plan 3.
	 */
	@Test
	fun `MutableSharedFlow is only used in core-events package`() {
		Konsist
			.scopeFromProject()
			.files
			.filter {
				it.imports.any { imp -> imp.name == "kotlinx.coroutines.flow.MutableSharedFlow" }
			}
			.assertTrue { file ->
				file.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.events") == true
			}
	}
}
```

- [ ] **Step 2: Run the architecture tests**

```bash
./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.arch.LayeringTest" 2>&1 | tail -10
```

Expected:
```
LayeringTest > BusEvent subtypes are data classes or objects() PASSED
LayeringTest > MutableSharedFlow is only used in core-events package() PASSED
BUILD SUCCESSFUL
```

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LayeringTest.kt
git commit -m "BuildCore: add Konsist architecture tests (BusEvent immutability + SharedFlow isolation)"
```

---

## Task 9 — FlatLaf theme setup

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/theme/FlatLafTheme.kt`

- [ ] **Step 1: Write the theme installer**

```kotlin
package net.vital.plugins.buildcore.gui.theme

import com.formdev.flatlaf.intellijthemes.FlatDarculaIJTheme
import javax.swing.UIManager

/**
 * BuildCore GUI theme.
 *
 * FlatLaf's IntelliJ Darcula as the base. Spec §15 calls for this plus
 * an accent color; full accent support lands in Plan 10's GUI shell
 * work. This bootstrap task just gets the base theme installed.
 *
 * Call [install] once at GUI startup, before any Swing component is
 * constructed.
 */
object FlatLafTheme {

	/** Default accent color (BuildCore purple). Used by Plan 10. */
	const val DEFAULT_ACCENT_HEX = "#8A6BFF"

	/**
	 * Install the theme on the current thread's UIManager.
	 *
	 * Must be called before any JFrame/JPanel construction, or the
	 * components will render with the default Metal LaF.
	 */
	fun install() {
		try {
			UIManager.setLookAndFeel(FlatDarculaIJTheme())
		} catch (ex: Exception) {
			System.err.println("BuildCore: failed to install FlatLaf theme: ${ex.message}")
			// Fall back to system LaF so the GUI still renders something.
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		}
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/theme/FlatLafTheme.kt
git commit -m "BuildCore: install FlatLaf IntelliJ Darcula theme"
```

---

## Task 10 — Minimal BuildCoreWindow (Swing shell)

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/BuildCoreWindow.kt`

- [ ] **Step 1: Write the window class**

```kotlin
package net.vital.plugins.buildcore.gui

import net.vital.plugins.buildcore.gui.theme.FlatLafTheme
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * BuildCore's main window — minimal shell for Plan 1.
 *
 * Plan 10 replaces the body of this class with the full 9-tab GUI from
 * spec §15. Plan 1 only proves that:
 *   - FlatLaf installs cleanly
 *   - A JFrame opens at the target size and can be closed
 *   - No AWT-thread violations occur during startup/shutdown
 *
 * Construct on the Swing EDT via [openOnEdt]. Never call the constructor
 * from the plugin's main thread.
 */
class BuildCoreWindow : JFrame("BuildCore") {

	init {
		preferredSize = Dimension(1440, 900)
		minimumSize = Dimension(1280, 800)
		defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		contentPane.add(JLabel("BuildCore foundation bootstrap — Plan 1 shell", JLabel.CENTER))
		pack()
		setLocationRelativeTo(null)
	}

	companion object {
		/**
		 * Install theme + construct + show the window on the Swing EDT.
		 * Returns the window reference on the calling thread once
		 * initialization completes (blocks briefly on invokeAndWait).
		 */
		fun openOnEdt(): BuildCoreWindow {
			var ref: BuildCoreWindow? = null
			SwingUtilities.invokeAndWait {
				FlatLafTheme.install()
				ref = BuildCoreWindow().apply { isVisible = true }
			}
			return ref ?: error("BuildCoreWindow failed to initialize on EDT")
		}
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/gui/BuildCoreWindow.kt
git commit -m "BuildCore: add minimal Swing shell window (opens on EDT, FlatLaf Darcula)"
```

---

## Task 11 — Window smoke test (headless-safe)

A smoke test that constructs the window in a headless JVM would fail (no display). Instead, we unit-test the construction logic by asserting size/title properties on a non-visible instance. True visibility is proven at runtime when the plugin loads in VitalClient.

**Files:**
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/gui/BuildCoreWindowSmokeTest.kt`

- [ ] **Step 1: Write the smoke test**

```kotlin
package net.vital.plugins.buildcore.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

class BuildCoreWindowSmokeTest {

	@Test
	fun `window constructs with expected title and dimensions`() {
		// JVM on CI might be headless — skip if so.
		assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display")

		var window: BuildCoreWindow? = null
		SwingUtilities.invokeAndWait {
			window = BuildCoreWindow()
		}
		val w = window ?: error("window not created")

		try {
			assertEquals("BuildCore", w.title)
			assertEquals(1280, w.minimumSize.width)
			assertEquals(800, w.minimumSize.height)
			assertEquals(WindowConstants.DISPOSE_ON_CLOSE, w.defaultCloseOperation)
			assertFalse(w.isVisible, "window must not auto-show from constructor")
		} finally {
			SwingUtilities.invokeAndWait { w.dispose() }
		}
	}
}
```

- [ ] **Step 2: Run the test**

```bash
./gradlew.bat :BuildCore:test --no-daemon --tests "net.vital.plugins.buildcore.gui.BuildCoreWindowSmokeTest" 2>&1 | tail -10
```

Expected on a machine with a display: PASS. Expected on a headless CI runner: SKIPPED. Both are success outcomes.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/gui/BuildCoreWindowSmokeTest.kt
git commit -m "BuildCore: add window smoke test (headless-safe with assumeFalse guard)"
```

---

## Task 12 — BuildCorePlugin entry point

The RuneLite/PF4J plugin class. Instantiated by VitalShell's plugin loader. For Plan 1 it only has to start cleanly and shut down cleanly; GUI-on-startup wiring comes in Plan 10.

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt`

- [ ] **Step 1: Write the plugin class**

```kotlin
package net.vital.plugins.buildcore

import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.vital.plugins.buildcore.core.events.EventBus

/**
 * BuildCore RuneLite plugin entry point.
 *
 * Lifecycle is owned by VitalShell's plugin manager. Plan 1 only
 * establishes startup/shutdown paths — Plan 2 wires the task runner,
 * Plan 10 wires the full GUI launch.
 *
 * The [eventBus] field is exposed as package-visible so early
 * subsystems can share a single bus instance for their integration
 * tests. Plan 2 replaces this with a Guice-provided binding.
 */
@PluginDescriptor(
	name = "BuildCore",
	description = "All-inclusive OSRS account builder foundation",
	tags = ["buildcore", "builder", "account", "framework"]
)
class BuildCorePlugin : Plugin() {

	internal val eventBus: EventBus = EventBus()

	override fun startUp() {
		log("BuildCore plugin starting — v${version()}")
	}

	override fun shutDown() {
		log("BuildCore plugin shutting down")
	}

	private fun version(): String = javaClass.`package`?.implementationVersion ?: "dev"

	private fun log(message: String) {
		// Plan 3 replaces with structured event emission. For now, stderr
		// so we can see it in the VitalClient console on first load.
		System.err.println("[BuildCore] $message")
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
./gradlew.bat :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt
git commit -m "BuildCore: add minimal BuildCorePlugin entry point with EventBus wiring"
```

---

## Task 13 — Full build passes end-to-end

**Files:**
- None created; verification only.

- [ ] **Step 1: Clean build from scratch**

```bash
./gradlew.bat :BuildCore:clean :BuildCore:build --no-daemon 2>&1 | tail -30
```

Expected:
- All Kotlin compilation succeeds.
- All tests pass (EventBusTest, LayeringTest, BuildCoreWindowSmokeTest).
- `BUILD SUCCESSFUL`.
- Output JAR present at `BuildCore/build/libs/BuildCore-0.1.0.jar`.
- JAR auto-deployed to `~/.vitalclient/sideloaded-plugins/BuildCore-0.1.0.jar` by the root build's `afterEvaluate` hook.

- [ ] **Step 2: Inspect the shipped JAR manifest**

```bash
unzip -p BuildCore/build/libs/BuildCore-0.1.0.jar META-INF/MANIFEST.MF
```

Expected to include:
```
Plugin-Id: BuildCore
Plugin-Version: 0.1.0
Plugin-Provider: Vital
Plugin-Description: All-inclusive OSRS account builder foundation
Plugin-License: BSD 2-Clause License
```

- [ ] **Step 3: Inspect that Kotlin runtime + FlatLaf are bundled**

```bash
unzip -l BuildCore/build/libs/BuildCore-0.1.0.jar | grep -E "(kotlin/Unit|flatlaf)" | head -5
```

Expected: at least one `kotlin/Unit.class` entry (Kotlin stdlib bundled) and references to `com/formdev/flatlaf` (FlatLaf bundled).

**If neither is present**, the JAR is using compile-only deps and the plugin would fail to load in VitalShell. In that case, amend `BuildCore/BuildCore.gradle.kts` to add the Shadow plugin (`id("com.github.johnrengelman.shadow") version "8.1.1"`) and prefer `shadowJar` over `jar` for the output artifact. This is an escape hatch — most Gradle+Kotlin setups shade stdlib into the `implementation` output by default when there's no parent configuration forcing otherwise.

- [ ] **Step 4: No commit** — this is verification only.

---

## Task 14 — Subproject CLAUDE.md

**Files:**
- Create: `BuildCore/CLAUDE.md`

- [ ] **Step 1: Write the subproject CLAUDE.md**

```markdown
# BuildCore — CLAUDE.md

Subproject guidance for BuildCore within the VitalPlugins Gradle multi-project.

## What BuildCore is

All-inclusive OSRS account builder foundation for the VitalClient platform. See the design spec at [../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md) for the full architecture.

## Status

**Foundation phase — Plan 1 (Project Bootstrap) complete.**

Future plans in `../docs/superpowers/plans/`:
- Plan 2 — Core Runtime + Task SPI + Restrictions
- Plan 3 — Logging + Event Bus
- Plan 4 — Antiban + Input Layer
- Plan 5 — Action Library (L5 Services)
- Plan 6 — Confidence / Watchdog / Recovery
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell

## Build

```bash
# From VitalPlugins root
./gradlew :BuildCore:build                   # full build + tests
./gradlew :BuildCore:test                    # tests only
./gradlew :BuildCore:compileKotlin           # compile check
./gradlew :BuildCore:jar                     # build + deploy to ~/.vitalclient/sideloaded-plugins/
```

## Architecture

Follow the layered model in spec §4:
- L1 GUI Shell (`gui/`)
- L2 Profile/Plan (`core/config/`)
- L3 Task Runner (`core/task/`)
- L4 Task SPI (`core/task/`)
- L5 Services (`core/services/`)
- L6 Confidence/Watchdog (`core/watchdog/`)
- L7 Input (`core/antiban/`)
- L8 VitalAPI bridge (external)

Cross-cutting: `core/restrictions/`, `core/antiban/`, `core/events/`, `core/logging/`, `core/licensing/`.

## Key invariants (enforced by architecture tests)

Architecture tests live in `src/test/kotlin/net/vital/plugins/buildcore/arch/`. Each test cites the spec section it enforces. Adding a new rule = adding a new `@Test` in that directory.

Current invariants:
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.

Plan 3 onward adds many more. Never weaken an architecture test — extend it or add a new one.

## Code style

Matches the rest of VitalPlugins:
- **Tabs** for indentation (not spaces)
- Allman braces (opening brace on its own line)
- Always use braces on control flow
- UTF-8 files

Kotlin-specific:
- Prefer `val` over `var`
- Prefer immutable collections (`listOf`, `mapOf`)
- Prefer sealed classes/interfaces for closed taxonomies
- Data classes for value types; `object` for stateless singletons

## Testing

- Unit tests: JUnit 5 (Jupiter) + MockK
- Architecture tests: Konsist
- Coroutine tests: `kotlinx-coroutines-test` with `runTest { ... }`
- Headless CI tolerance: GUI tests use `assumeFalse(GraphicsEnvironment.isHeadless())`

## Commit conventions

- Author: Chich only — NO `Co-Authored-By` trailers
- Message prefix: `BuildCore: <what changed>`
- Commit after every logical step (per root-repo convention)
```

- [ ] **Step 2: Commit**

```bash
git add BuildCore/CLAUDE.md
git commit -m "BuildCore: add subproject CLAUDE.md"
```

---

## Task 15 — BuildCore README

**Files:**
- Create: `BuildCore/README.md`

- [ ] **Step 1: Write README**

```markdown
# BuildCore

All-inclusive OSRS account builder foundation for VitalClient.

**Status:** Foundation phase — framework only. No activity modules (quests, skills, etc.) yet; those ship as separate subprojects in later phases.

## Quick start

Prerequisites (from `../VitalShell` and `../VitalAPI`):

```bash
cd ../VitalShell && ./gradlew publishRuneliteApiPublicationToMavenLocal
cd ../VitalShell && ./gradlew :client:publishRunelite-clientPublicationToMavenLocal
cd ../VitalAPI   && ./gradlew publishToMavenLocal
```

Build:

```bash
cd ../                         # back to VitalPlugins root
./gradlew :BuildCore:build
```

The resulting JAR lands at:
- `BuildCore/build/libs/BuildCore-0.1.0.jar`
- `~/.vitalclient/sideloaded-plugins/BuildCore-0.1.0.jar` (auto-deployed)

## What's inside

See [CLAUDE.md](./CLAUDE.md) for the package layout, build system, and architecture overview. The full design is in [docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](../docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md).

## Contributing

Private project — not accepting external contributions at this time. See the project owner for access.

## License

BSD 2-Clause License (matches VitalPlugins root).
```

- [ ] **Step 2: Commit**

```bash
git add BuildCore/README.md
git commit -m "BuildCore: add README with quickstart and project pointers"
```

---

## Task 16 — Update root VitalPlugins CLAUDE.md to list BuildCore

**Files:**
- Modify: `VitalPlugins/CLAUDE.md`

- [ ] **Step 1: Read current file and find the "Current plugins:" line**

The existing [`VitalPlugins/CLAUDE.md`](../../../CLAUDE.md) says:

> Current plugins: `walker`, `autologin`. Both were copied (with package rename) from `VitalShell/runelite-client/src/main/java/net/runelite/client/plugins/` — the originals remain in place in VitalShell.

Update to include BuildCore and the other existing plugins (`cow-killer`, `test`) actually present in the repo:

- [ ] **Step 2: Apply the edit**

Replace the above line with:

```markdown
Current plugins: `walker`, `autologin`, `cow-killer`, `test`, and `BuildCore` (foundation framework for account builders — see [BuildCore/CLAUDE.md](./BuildCore/CLAUDE.md) and the spec at [docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md](./docs/superpowers/specs/2026-04-21-buildcore-foundation-design.md)).
```

Also update the "Adding a New Plugin" section's example filenames if any still reference the old set — no changes needed if it uses generic `<plugin-name>` placeholders.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "BuildCore: register subproject in VitalPlugins root CLAUDE.md"
```

---

## Task 17 — Minimal CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow**

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  buildcore:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "11"

      - name: Validate Gradle Wrapper
        uses: gradle/actions/wrapper-validation@v3

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/libs.versions.toml') }}
          restore-keys: gradle-${{ runner.os }}-

      - name: Build BuildCore
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: ./gradlew :BuildCore:build --no-daemon --stacktrace

      - name: Upload test reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: BuildCore/build/reports/tests/
```

Notes for the executing engineer:
- The `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars are required because the root `build.gradle.kts` configures Maven repos at `maven.pkg.github.com/Vitalflea/VitalAPI` and `.../VitalShell` with those credentials. CI uses the automatic `GITHUB_TOKEN` with `read:packages` scope.
- **This may fail until `Vitalflea` publishes the artifacts publicly** OR the CI token is granted cross-org `read:packages`. If it fails on token scope, add a repository secret `GPR_TOKEN` with a personal-access-token that has `read:packages` on `Vitalflea/VitalAPI` and `Vitalflea/VitalShell`, then swap `GITHUB_TOKEN` for `secrets.GPR_TOKEN`. Document the decision in commit message.

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "BuildCore: add GitHub Actions CI (build + test + failure artifacts)"
```

- [ ] **Step 3: Do not push yet** — push is the final step in Task 20 after everything is green locally.

---

## Task 18 — Git-subtree mirror to standalone repo (documentation only)

Spec §3 decided: BuildCore lives as a subproject inside VitalPlugins AND is mirrored to a standalone private repo for future portability to other clients.

Plan 1 does **not** create the remote or push — that's a one-time admin step the owner performs. This task captures the procedure so it's not lost.

**Files:**
- Create: `BuildCore/docs/git-subtree-mirror.md`

- [ ] **Step 1: Write the procedure doc**

```bash
mkdir -p BuildCore/docs
```

```markdown
# Git-subtree mirror procedure

BuildCore lives inside `VitalPlugins` as a subproject, and is mirrored to a
standalone private repo at `git@github.com:xgoingleftyx/BuildCore.git` (or
wherever the owner configures). This document captures the one-time setup
and the recurring push command.

## One-time setup (owner performs once)

1. Create the empty private remote on GitHub: `BuildCore`.
2. From the VitalPlugins working copy:

```bash
cd /c/Code/VitalPlugins
git remote add buildcore-mirror git@github.com:xgoingleftyx/BuildCore.git
```

3. First push seeds the mirror with the full BuildCore history:

```bash
git subtree push --prefix=BuildCore buildcore-mirror main
```

## Recurring mirror (periodic, ad-hoc)

After any merge to `main` that touches `BuildCore/`:

```bash
git subtree push --prefix=BuildCore buildcore-mirror main
```

This pushes only the BuildCore subtree's commits to the mirror. Commits
that don't touch `BuildCore/` are filtered out automatically.

## If the mirror diverges

Subtree push is fast-forward only. If the mirror repo has commits that
aren't in VitalPlugins (e.g., a direct edit on the mirror), you must
either:
1. Pull those back via `git subtree pull --prefix=BuildCore buildcore-mirror main`, OR
2. Force-push the mirror: `git push buildcore-mirror $(git subtree split --prefix=BuildCore main):main --force`

Prefer option 1 unless you know the mirror's divergent commits were a
mistake.

## Not implemented yet

- Automated periodic mirror push (cron job, GitHub Action)
- Importing BuildCore into an unrelated client repo (this is the
  eventual portability story — deferred until we need it)
```

- [ ] **Step 2: Commit**

```bash
git add BuildCore/docs/git-subtree-mirror.md
git commit -m "BuildCore: document git-subtree mirror procedure for future standalone repo"
```

---

## Task 19 — Full regression — clean build + all tests

**Files:**
- None; verification only.

- [ ] **Step 1: Clean slate**

```bash
./gradlew.bat :BuildCore:clean --no-daemon
```

- [ ] **Step 2: Build + test**

```bash
./gradlew.bat :BuildCore:build --no-daemon 2>&1 | tail -30
```

Expected output summary:
```
> Task :BuildCore:compileKotlin
> Task :BuildCore:test
  EventBusTest > emitted events are received by subscribers() PASSED
  EventBusTest > bus has replay buffer zero — late subscribers do not see past events() PASSED
  EventBusTest > multiple subscribers all receive the same event() PASSED
  LayeringTest > BusEvent subtypes are data classes or objects() PASSED
  LayeringTest > MutableSharedFlow is only used in core-events package() PASSED
  BuildCoreWindowSmokeTest > window constructs with expected title and dimensions() PASSED
> Task :BuildCore:jar
BUILD SUCCESSFUL
6 tests completed, 0 failed, 0 skipped
```

If `BuildCoreWindowSmokeTest` skips rather than passes (e.g., running on a headless system via SSH), that's also a pass.

- [ ] **Step 3: Do not commit** — verification only.

---

## Task 20 — Final push

**Files:**
- None; git operation only.

- [ ] **Step 1: Review the commit log**

```bash
git log --oneline main...origin/main 2>&1 | head -25
```

Expected: 17-ish commits, one per major task, all prefixed `BuildCore:`.

- [ ] **Step 2: Push to origin (user's fork)**

Per the root `CLAUDE.md` "Workflow" note: "Always commit after every change (push only when explicitly requested)."

The user has explicitly chosen to implement this plan, so pushing at the end is appropriate. However, confirm with user before pushing:

```bash
git status   # must be clean
git push origin main
```

- [ ] **Step 3: Verify CI runs**

Open the repo's Actions tab on GitHub. The `CI` workflow should trigger on the push. Watch the `buildcore` job succeed (or investigate failure per Task 17 notes about GitHub Packages auth).

- [ ] **Step 4: No commit** — push itself.

---

## Self-review checklist (run BEFORE handing to executing agent)

- [ ] Every task that creates or modifies a file lists the exact path.
- [ ] Every code step shows complete, runnable code — no `// ...` or "similar to above."
- [ ] Every test step has a command AND an expected output.
- [ ] Every task ends with a commit (except pure verification tasks 13, 19, 20).
- [ ] Commit messages follow `BuildCore: <what changed>` convention.
- [ ] No `Co-Authored-By` trailers.
- [ ] The plan covers every item the spec §1 and §4 say ships in foundation for Plan 1's scope (event bus, GUI shell skeleton, package layout, architecture tests, build, CI).
- [ ] No plan step references a type, method, or property not defined in an earlier step.
- [ ] No "TODO" / "TBD" / "fill in details" phrases.
- [ ] Task ordering respects dependencies: catalog before Gradle config, source before tests that reference it, build verification after all creation tasks.

---

## What this plan deliberately does NOT do

- Wire the plugin's startup to open `BuildCoreWindow` — Plan 10's concern.
- Create any concrete `BusEvent` subtype beyond `TestPing` — Plan 3's concern.
- Set up Guice bindings for the plugin — Plan 2's concern.
- Add any task-SPI interface or class — Plan 2's concern.
- Integrate with VitalAPI beyond its compile-time availability — all later plans.
- Test that the JAR actually loads in a running VitalClient — pending VitalClient setup availability; manual smoke only.
- Configure the git-subtree remote — Task 18 documents the procedure; owner executes later.

Every one of these is explicitly scoped to a later plan. This keeps Plan 1 self-contained, reviewable, and fast to complete.
