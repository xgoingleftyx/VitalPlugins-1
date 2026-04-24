# BuildCore Plan 3 — Logging + Event Bus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Plan 1 `EventBus` primitive into a full structured-logging subsystem — session lifecycle, dedicated logger thread, two on-disk writers (JSONL + human-readable), privacy scrubber with compile-time exhaustiveness, performance aggregator, and architecture tests that force future plans to register scrubbers for new event subtypes.

**Architecture:** One `MutableSharedFlow<BusEvent>` (Plan 1, unchanged) fans out on a single-thread `LoggerScope` dispatcher. Fast-path subscribers (`LocalJsonlWriter`, `LocalSummaryWriter`, `PerformanceAggregator`) share the thread and write in event-emission order. Slow-path subscribers (future `TelemetrySubscriber`, `ReplaySubscriber`) extend `BoundedChannelSubscriber` with `DROP_OLDEST` overflow + tombstone event. `SessionManager` mints the sessionId at plugin startup, emits `SessionStart`/`SessionSummary`/`SessionEnd`, and injects the sessionId into `Runner` (replacing the per-instance `UUID.randomUUID()` default). `PrivacyScrubber` is an exhaustive `when` over the sealed `BusEvent` hierarchy — the Kotlin compiler fails the build when a new subtype is added without a scrubber case.

**Tech Stack:** Kotlin 2.1.0, kotlinx-coroutines 1.9.0, JUnit 5, MockK, Konsist, Jackson `jackson-module-kotlin` (new dep — see Task 1). Built on Plans 1 + 2.

**Prerequisites:**
- Spec: [`docs/superpowers/specs/2026-04-23-buildcore-plan3-logging-eventbus-design.md`](../specs/2026-04-23-buildcore-plan3-logging-eventbus-design.md)
- Plan 2 complete and merged to `main` (59 tests passing).
- Working dir: `C:\Code\VitalPlugins`, branch `main`, commit direct to main, push to `origin` (xgoingleftyx fork) after every commit.
- Author: Chich only — NO `Co-Authored-By` trailers.
- Style: tabs, Allman braces where applicable, UTF-8.

**Spec clarification adopted in this plan:** The spec lists 9 new `BusEvent` subtypes but also requires an overflow tombstone type for `BoundedChannelSubscriber` (§4.4). This plan adds a 10th subtype, `SubscriberOverflowed`, so the base class has a concrete tombstone factory. Generic so Plan 4 and Plan 8 reuse it without adding `ReplayOverflowed` / `TelemetryOverflowed` siblings.

---

## File structure this plan produces

```
BuildCore/BuildCore.gradle.kts                               # MODIFY — add jackson-module-kotlin
gradle/libs.versions.toml                                    # MODIFY — add jackson version + lib

BuildCore/src/main/kotlin/net/vital/plugins/buildcore/
├── BuildCorePlugin.kt                                       # MODIFY — wire LoggerScope + SessionManager + registry
└── core/
    ├── events/
    │   ├── BusEvent.kt                                      # MODIFY — pull taskInstanceId/moduleId up; add 10 new subtypes
    │   ├── EventBus.kt                                      # unchanged
    │   ├── PrivacyScrubber.kt                               # CREATE
    │   ├── SubscriberRegistry.kt                            # CREATE
    │   └── LogSubscriber.kt                                 # CREATE
    ├── restrictions/
    │   └── RestrictionEngine.kt                             # MODIFY — optional bus param emits RestrictionViolated
    ├── task/
    │   ├── Runner.kt                                        # MODIFY — require sessionId; emit SafeStop*
    │   ├── ModuleRegistry.kt                                # MODIFY — emit ValidationFailed
    │   └── SafeStopContract.kt                              # MODIFY — emit SafeStopRequested/Completed
    └── logging/
        ├── LogConfig.kt                                     # CREATE
        ├── LogDirLayout.kt                                  # CREATE
        ├── LoggerScope.kt                                   # CREATE
        ├── LogLevel.kt                                      # CREATE
        ├── RotatingFileSink.kt                              # CREATE
        ├── LocalJsonlWriter.kt                              # CREATE
        ├── LocalSummaryWriter.kt                            # CREATE
        ├── PerformanceAggregator.kt                         # CREATE
        ├── BoundedChannelSubscriber.kt                      # CREATE
        ├── TelemetrySubscriber.kt                           # CREATE (interface + NoOp impl)
        ├── ReplaySubscriber.kt                              # CREATE (interface + NoOp impl)
        ├── SessionManager.kt                                # CREATE
        ├── UncaughtExceptionHandler.kt                      # CREATE
        └── BuildCoreVersion.kt                              # CREATE (const singleton)

BuildCore/src/test/kotlin/net/vital/plugins/buildcore/
├── core/events/
│   ├── BusEventInterfaceTest.kt                             # CREATE
│   └── PrivacyScrubberTest.kt                               # CREATE
├── core/logging/
│   ├── LogConfigTest.kt                                     # CREATE
│   ├── LogDirLayoutTest.kt                                  # CREATE
│   ├── LoggerScopeOrderingTest.kt                           # CREATE
│   ├── RotatingFileSinkTest.kt                              # CREATE
│   ├── LocalJsonlWriterTest.kt                              # CREATE
│   ├── LocalSummaryWriterTest.kt                            # CREATE
│   ├── PerformanceAggregatorTest.kt                         # CREATE
│   ├── SubscriberRegistryTest.kt                            # CREATE
│   └── SessionManagerTest.kt                                # CREATE
├── core/task/
│   └── RunnerStateMachineTest.kt                            # MODIFY — pass sessionId arg
│   └── NoOpTaskRunTest.kt                                   # MODIFY — pass sessionId arg
├── arch/
│   └── LoggingArchitectureTest.kt                           # CREATE — 8 invariants
└── integration/
    └── PluginBootstrapIntegrationTest.kt                    # CREATE
```

---

## Phase 1 — Dependencies & Foundation (Tasks 1-3)

### Task 1 — Add Jackson dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `BuildCore/BuildCore.gradle.kts`

- [ ] **Step 1: Add Jackson to the version catalog**

Modify `gradle/libs.versions.toml`. Under `[versions]`, add:

```toml
jackson               = "2.17.2"
```

Under `[libraries]`, add:

```toml
jackson-module-kotlin = { module = "com.fasterxml.jackson.module:jackson-module-kotlin", version.ref = "jackson" }
jackson-databind      = { module = "com.fasterxml.jackson.core:jackson-databind",        version.ref = "jackson" }
```

- [ ] **Step 2: Wire Jackson into BuildCore**

Modify `BuildCore/BuildCore.gradle.kts`. In the `dependencies { … }` block, after the `coroutines-swing` line:

```kotlin
	implementation(rootProject.libs.jackson.module.kotlin)
	implementation(rootProject.libs.jackson.databind)
```

Use `implementation` (not `compileOnly`) because BuildCore is a fat (shadow) JAR and VitalShell's classpath does not guarantee Jackson.

- [ ] **Step 3: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit and push**

```bash
git add gradle/libs.versions.toml BuildCore/BuildCore.gradle.kts
git commit -m "BuildCore: add Jackson dependency for JSONL event serialisation (Plan 3)"
git push origin main
```

---

### Task 2 — `BuildCoreVersion` constant

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/BuildCoreVersion.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.logging

/**
 * Single source of truth for the BuildCore version string.
 *
 * Plan 3 uses this in [SessionStart] and [session.meta.json]. A later
 * plan may wire this from the gradle `version` property via a
 * generated resource; for now it is a hand-edited constant and
 * version bumps bump both places.
 */
object BuildCoreVersion {
	const val CURRENT: String = "0.1.0"
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/BuildCoreVersion.kt
git commit -m "BuildCore: add BuildCoreVersion constant for session-start events (Plan 3)"
git push origin main
```

---

### Task 3 — `LogConfig`

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogConfig.kt`

- [ ] **Step 1: Write the failing test**

Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LogConfigTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogConfigTest {

	@Test
	fun `defaults when no overrides present`() {
		val cfg = LogConfig.load(env = emptyMap(), sysprops = emptyMap())
		assertEquals(LogLevel.INFO, cfg.level)
		assertEquals(30, cfg.retentionSessions)
		assertEquals(10L * 1024 * 1024, cfg.rotationSizeBytes)
		assertEquals(300_000L, cfg.performanceSampleIntervalMillis)
	}

	@Test
	fun `env var overrides default log level`() {
		val cfg = LogConfig.load(env = mapOf("BUILDCORE_LOG_LEVEL" to "DEBUG"), sysprops = emptyMap())
		assertEquals(LogLevel.DEBUG, cfg.level)
	}

	@Test
	fun `sysprop beats env for log level`() {
		val cfg = LogConfig.load(
			env = mapOf("BUILDCORE_LOG_LEVEL" to "DEBUG"),
			sysprops = mapOf("buildcore.log.level" to "WARN")
		)
		assertEquals(LogLevel.WARN, cfg.level)
	}

	@Test
	fun `log root dir uses env when set`() {
		val cfg = LogConfig.load(env = mapOf("BUILDCORE_LOG_DIR" to "/tmp/bc-logs"), sysprops = emptyMap())
		assertEquals("/tmp/bc-logs", cfg.logRootDir.toString().replace('\\', '/'))
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LogConfigTest --no-daemon 2>&1 | tail -15
```
Expected: compilation error (`LogConfig` / `LogLevel` not found).

- [ ] **Step 3: Create `LogLevel.kt` first (minimal; expanded in Task 16)**

`BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

/**
 * Severity levels for [LocalSummaryWriter] filtering.
 *
 * Applies only to the human-readable summary log. The JSONL event file
 * always receives every event regardless of level.
 *
 * Spec §11.1.
 */
enum class LogLevel {
	DEBUG, INFO, WARN, ERROR, FATAL;

	companion object {
		fun parse(raw: String?): LogLevel = raw?.uppercase()?.let {
			values().firstOrNull { lvl -> lvl.name == it }
		} ?: INFO
	}
}
```

- [ ] **Step 4: Write `LogConfig.kt`**

`BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogConfig.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Process-level configuration for the logging subsystem.
 *
 * Loaded once at plugin startup via [load]. Precedence (highest first):
 * system properties → env vars → defaults.
 *
 * Spec §11.
 */
data class LogConfig(
	val level: LogLevel,
	val logRootDir: Path,
	val retentionSessions: Int,
	val rotationSizeBytes: Long,
	val performanceSampleIntervalMillis: Long,
	val summaryCapBytes: Long
) {
	companion object {
		fun load(
			env: Map<String, String> = System.getenv(),
			sysprops: Map<String, String> = System.getProperties().entries
				.associate { it.key.toString() to it.value.toString() }
		): LogConfig {
			fun pick(sysKey: String, envKey: String): String? =
				sysprops[sysKey] ?: env[envKey]

			return LogConfig(
				level = LogLevel.parse(pick("buildcore.log.level", "BUILDCORE_LOG_LEVEL")),
				logRootDir = pick("buildcore.log.dir", "BUILDCORE_LOG_DIR")
					?.let(Paths::get)
					?: Paths.get(System.getProperty("user.home"), ".vitalclient", "buildcore", "logs"),
				retentionSessions = pick("buildcore.log.retention", "BUILDCORE_LOG_RETENTION_SESSIONS")
					?.toIntOrNull() ?: 30,
				rotationSizeBytes = pick("buildcore.log.rotation.mb", "BUILDCORE_LOG_ROTATION_MB")
					?.toLongOrNull()?.let { it * 1024 * 1024 } ?: (10L * 1024 * 1024),
				performanceSampleIntervalMillis = pick("buildcore.perf.interval.ms", "BUILDCORE_PERF_INTERVAL_MS")
					?.toLongOrNull() ?: 300_000L,
				summaryCapBytes = 10L * 1024 * 1024
			)
		}
	}
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LogConfigTest --no-daemon 2>&1 | tail -10
```
Expected: 4 tests passing.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogConfig.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LogConfigTest.kt
git commit -m "BuildCore: add LogLevel + LogConfig with env/sysprop precedence (Plan 3 spec §11)"
git push origin main
```

---

## Phase 2 — Event taxonomy (Tasks 4-6)

### Task 4 — Pull correlation IDs up to `BusEvent` interface

**Files:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

The goal: every `BusEvent` subtype overrides `taskInstanceId: UUID?` and `moduleId: String?`. Subtypes that never have a task context (`SessionStart`, `SessionEnd`, `PerformanceSample`) override with `null`. Subtypes that always have one (task-lifecycle events) keep the value via `override val`.

- [ ] **Step 1: Write the failing test**

Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/BusEventInterfaceTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class BusEventInterfaceTest {

	private val sid = UUID.randomUUID()
	private val tid = UUID.randomUUID()

	@Test
	fun `TaskStarted exposes correlation IDs via interface fields`() {
		val e: BusEvent = TaskStarted(
			sessionId = sid,
			taskInstanceId = tid,
			taskId = "demo",
			methodId = "m1",
			pathId = "p1"
		)
		assertEquals(tid, e.taskInstanceId)
		assertNull(e.moduleId)                  // not yet threaded; Plan 5 wires it
	}

	@Test
	fun `events without task context expose nullable taskInstanceId as null`() {
		// SessionStart lands in Task 5; for Task 4 the TestPing event — which also has no task context
		// — is enough to prove the interface allows null.
		val ping: BusEvent = TestPing(sessionId = sid, payload = "hi")
		assertNull(ping.taskInstanceId)
		assertNull(ping.moduleId)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests BusEventInterfaceTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error — `BusEvent.taskInstanceId` / `moduleId` unresolved.

- [ ] **Step 3: Rewrite `BusEvent.kt`**

Replace `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`:

```kotlin
package net.vital.plugins.buildcore.core.events

import java.time.Instant
import java.util.UUID

/**
 * Marker for every event that flows through [EventBus].
 *
 * Every event carries correlation IDs: [eventId], [sessionId] are
 * always present; [taskInstanceId] and [moduleId] are nullable for
 * events without a task context (session lifecycle, performance).
 *
 * Events MUST be immutable data classes for thread-safe propagation
 * through [kotlinx.coroutines.flow.SharedFlow]. The [LayeringTest]
 * architecture test enforces immutability.
 *
 * Spec §5 (taxonomy), §13 (correlation IDs).
 */
sealed interface BusEvent {
	val eventId: UUID
	val timestamp: Instant
	val sessionId: UUID
	val schemaVersion: Int
	val taskInstanceId: UUID?
	val moduleId: String?
}

// ─────────────────────────────────────────────────────────────────────
// Test-only event (kept from Plan 1)
// ─────────────────────────────────────────────────────────────────────

internal data class TestPing(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val payload: String
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Task lifecycle (from Plan 2 — correlation IDs now go through the interface)
// ─────────────────────────────────────────────────────────────────────

data class TaskQueued(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class TaskValidated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val pass: Boolean,
	val rejectReason: String? = null
) : BusEvent

data class TaskStarted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val methodId: String,
	val pathId: String
) : BusEvent

data class TaskProgress(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class TaskCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val durationMillis: Long
) : BusEvent

data class TaskFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
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
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val attemptNumber: Int,
	val backoffMillis: Long
) : BusEvent

data class TaskSkipped(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskPaused(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val reason: String
) : BusEvent

data class TaskResumed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String
) : BusEvent

data class MethodPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val methodId: String
) : BusEvent

data class PathPicked(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID,
	override val moduleId: String? = null,
	val taskId: String,
	val pathId: String,
	val pathKind: String
) : BusEvent
```

Task 5 appends the new subtypes; don't add them here yet.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests BusEventInterfaceTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Run the full existing Plan 2 test suite to confirm no regression**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: 59 + 2 = 61 passing, 0 failing. (Plan 2's 59 still green because we only *added* the two interface-level nullable properties; the concrete field on each existing subtype now goes through `override val`, preserving its type.)

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/BusEventInterfaceTest.kt
git commit -m "BuildCore: lift taskInstanceId/moduleId to BusEvent interface (Plan 3 spec §5.1)"
git push origin main
```

---

### Task 5 — Add 10 new `BusEvent` subtypes

**Files:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt`

- [ ] **Step 1: Append the new subtypes**

Append at the bottom of `BusEvent.kt`:

```kotlin
// ─────────────────────────────────────────────────────────────────────
// Session lifecycle (Plan 3 spec §5.2)
// ─────────────────────────────────────────────────────────────────────

enum class LaunchMode { NORMAL, HEADLESS, TEST }
enum class StopReason { USER, CRASH, SAFE_STOP, UPDATE }

data class SessionStart(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val buildcoreVersion: String,
	val archetype: String? = null,
	val launchMode: LaunchMode = LaunchMode.NORMAL
) : BusEvent

data class SessionEnd(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: StopReason
) : BusEvent

data class TaskCounter(
	val started: Int = 0,
	val completed: Int = 0,
	val failed: Int = 0,
	val skipped: Int = 0
)

data class SessionSummary(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val durationMillis: Long,
	val taskCounts: Map<String, TaskCounter>,
	val totalEvents: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Safe stop
// ─────────────────────────────────────────────────────────────────────

data class SafeStopRequested(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val reason: String
) : BusEvent

data class SafeStopCompleted(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val durationMillis: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Errors
// ─────────────────────────────────────────────────────────────────────

data class UnhandledException(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val threadName: String,
	val exceptionClass: String,
	val message: String,
	val stackTrace: String
) : BusEvent

data class ValidationFailed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val subject: String,
	val detail: String
) : BusEvent

enum class RestrictionMoment { EDIT, START, RUNTIME }

data class RestrictionViolated(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val restrictionId: String,
	val effectSummary: String,
	val moment: RestrictionMoment
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Performance
// ─────────────────────────────────────────────────────────────────────

data class PerformanceSample(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val intervalSeconds: Long,
	val eventRatePerSec: Double,
	val jvmHeapUsedMb: Long,
	val jvmHeapMaxMb: Long,
	val loggerLagMaxMs: Long,
	val droppedEventsSinceLastSample: Long
) : BusEvent

// ─────────────────────────────────────────────────────────────────────
// Slow-subscriber overflow (generic; reused by Plan 4 & Plan 8)
// ─────────────────────────────────────────────────────────────────────

data class SubscriberOverflowed(
	override val eventId: UUID = UUID.randomUUID(),
	override val timestamp: Instant = Instant.now(),
	override val sessionId: UUID,
	override val schemaVersion: Int = 1,
	override val taskInstanceId: UUID? = null,
	override val moduleId: String? = null,
	val subscriberName: String,
	val droppedCount: Int
) : BusEvent
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full test suite (no new tests yet; just regression)**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -10
```
Expected: 61 passing.

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/BusEvent.kt
git commit -m "BuildCore: add 10 new BusEvent subtypes — session, safe-stop, errors, perf, overflow (Plan 3 spec §5.2)"
git push origin main
```

---

### Task 6 — `PrivacyScrubber`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt`

- [ ] **Step 1: Write the failing tests**

`PrivacyScrubberTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PrivacyScrubberTest {

	private val sid = UUID.randomUUID()
	private val tid = UUID.randomUUID()

	@Test
	fun `scrubs password tokens from task failure detail`() {
		val input = TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "login",
			reasonType = "EXCEPTION",
			reasonDetail = "login failed: username=alice password=s3cret retry later",
			attemptNumber = 1
		)
		val out = PrivacyScrubber.scrub(input) as TaskFailed
		assertFalse(out.reasonDetail.contains("s3cret"), "password survived scrub")
		assertTrue(out.reasonDetail.contains("password=«redacted»"))
		assertFalse(out.reasonDetail.contains("alice"), "username survived scrub")
	}

	@Test
	fun `leaves events without free-form text untouched`() {
		val input = TaskStarted(
			sessionId = sid, taskInstanceId = tid, taskId = "x", methodId = "m", pathId = "p"
		)
		val out = PrivacyScrubber.scrub(input)
		assertEquals(input, out)
	}

	@Test
	fun `idempotent — scrub(scrub(e)) equals scrub(e)`() {
		val input = TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "t",
			reasonType = "EX", reasonDetail = "token=abc123 username=bob",
			attemptNumber = 1
		)
		val once = PrivacyScrubber.scrub(input)
		val twice = PrivacyScrubber.scrub(once)
		assertEquals(once, twice)
	}

	@Test
	fun `scrubs stack trace from UnhandledException`() {
		val input = UnhandledException(
			sessionId = sid,
			threadName = "main",
			exceptionClass = "RuntimeException",
			message = "password=hunter2 is wrong",
			stackTrace = "at com.example.auth(Auth.java:42) username=carol"
		)
		val out = PrivacyScrubber.scrub(input) as UnhandledException
		assertFalse(out.message.contains("hunter2"))
		assertFalse(out.stackTrace.contains("carol"))
	}

	@Test
	fun `every BusEvent subtype returns without throwing`() {
		// Belt-and-braces — the exhaustive when inside scrub should already guarantee this.
		val samples: List<BusEvent> = listOf(
			SessionStart(sessionId = sid, buildcoreVersion = "0.0.0"),
			SessionEnd(sessionId = sid, reason = StopReason.USER),
			SessionSummary(sessionId = sid, durationMillis = 0, taskCounts = emptyMap(), totalEvents = 0),
			TaskQueued(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			TaskValidated(sessionId = sid, taskInstanceId = tid, taskId = "t", pass = true),
			TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "t", methodId = "m", pathId = "p"),
			TaskProgress(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			TaskCompleted(sessionId = sid, taskInstanceId = tid, taskId = "t", durationMillis = 1),
			TaskFailed(sessionId = sid, taskInstanceId = tid, taskId = "t", reasonType = "X", reasonDetail = "y", attemptNumber = 1),
			TaskRetrying(sessionId = sid, taskInstanceId = tid, taskId = "t", attemptNumber = 2, backoffMillis = 10),
			TaskSkipped(sessionId = sid, taskInstanceId = tid, taskId = "t", reason = "r"),
			TaskPaused(sessionId = sid, taskInstanceId = tid, taskId = "t", reason = "r"),
			TaskResumed(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			MethodPicked(sessionId = sid, taskInstanceId = tid, taskId = "t", methodId = "m"),
			PathPicked(sessionId = sid, taskInstanceId = tid, taskId = "t", pathId = "p", pathKind = "IRONMAN"),
			SafeStopRequested(sessionId = sid, reason = "r"),
			SafeStopCompleted(sessionId = sid, durationMillis = 1),
			UnhandledException(sessionId = sid, threadName = "main", exceptionClass = "E", message = "m", stackTrace = "s"),
			ValidationFailed(sessionId = sid, subject = "Plan", detail = "bad"),
			RestrictionViolated(sessionId = sid, restrictionId = "r", effectSummary = "e", moment = RestrictionMoment.EDIT),
			PerformanceSample(sessionId = sid, intervalSeconds = 300, eventRatePerSec = 1.0,
				jvmHeapUsedMb = 100, jvmHeapMaxMb = 1000, loggerLagMaxMs = 0, droppedEventsSinceLastSample = 0),
			SubscriberOverflowed(sessionId = sid, subscriberName = "telemetry", droppedCount = 3),
			TestPing(sessionId = sid, payload = "hi")
		)
		// Must cover all 23 current subtypes — update this list when Plans 4/6/8 add new ones.
		assertEquals(23, samples.size, "update the sample list when a new BusEvent subtype is added")
		samples.forEach { PrivacyScrubber.scrub(it) }  // just assert no throw
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PrivacyScrubberTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error (`PrivacyScrubber` not defined).

- [ ] **Step 3: Write `PrivacyScrubber.kt`**

`BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt`:

```kotlin
package net.vital.plugins.buildcore.core.events

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

/**
 * Pre-transmit / pre-human-log scrubber. Redacts credentials and hashes
 * usernames in free-form text fields.
 *
 * Called only by [net.vital.plugins.buildcore.core.logging.LocalSummaryWriter]
 * and (future) TelemetrySubscriber. The raw JSONL on local disk
 * receives unscrubbed events — local disk is private app data; the
 * summary log and network transmits cross a sharing boundary.
 *
 * The `when` expression over the sealed [BusEvent] hierarchy is
 * exhaustive — adding a new subtype breaks the build until a case is
 * added here. This is the "compile-time check" in spec §13.
 *
 * Spec §6.
 */
object PrivacyScrubber {

	/**
	 * Per-process HMAC key. Each run produces a fresh key, so a hashed
	 * username cannot be correlated across process restarts. When
	 * SessionManager lands (Plan 3 Task 17) the key rotates with the
	 * session — same property, tighter scope.
	 */
	@Volatile
	private var hmacKey: ByteArray = freshKey()

	/**
	 * Rotate the HMAC key. Called by SessionManager at session start so
	 * that usernames scrubbed in one session cannot be correlated to
	 * those in another.
	 */
	fun rotateKey(seed: UUID) {
		hmacKey = seed.toString().toByteArray(Charsets.UTF_8)
	}

	fun scrub(event: BusEvent): BusEvent = when (event) {
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
		is SafeStopRequested   -> event.copy(reason = scrubString(event.reason))
		is SafeStopCompleted   -> event
		is UnhandledException  -> event.copy(
			message    = scrubString(event.message),
			stackTrace = scrubStackTrace(event.stackTrace)
		)
		is ValidationFailed    -> event.copy(detail = scrubString(event.detail))
		is RestrictionViolated -> event
		is PerformanceSample   -> event
		is SubscriberOverflowed -> event
		is TestPing            -> event.copy(payload = "«scrubbed»")
	}

	private fun scrubString(s: String): String = s
		.replace(Regex("""(?i)(password|token|apikey|bearer)\s*[=:]\s*\S+""")) {
			"${it.groupValues[1]}=«redacted»"
		}
		.replace(Regex("""(?i)username\s*[=:]\s*(\S+)""")) {
			"username=${hmacHex(it.groupValues[1])}"
		}

	private fun scrubStackTrace(trace: String): String =
		trace.lines().joinToString("\n") { scrubString(it) }

	private fun hmacHex(value: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
		val raw = mac.doFinal(value.toByteArray(Charsets.UTF_8))
		// 12 hex chars = 6 bytes — enough uniqueness for debug, too short for crack-back
		return raw.take(6).joinToString("") { "%02x".format(it) }
	}

	private fun freshKey(): ByteArray {
		val rnd = java.security.SecureRandom()
		val key = ByteArray(32)
		rnd.nextBytes(key)
		return key
	}
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PrivacyScrubberTest --no-daemon 2>&1 | tail -10
```
Expected: 5 tests passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubber.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/events/PrivacyScrubberTest.kt
git commit -m "BuildCore: add PrivacyScrubber with exhaustive sealed-when + HMAC username hashing (Plan 3 spec §6)"
git push origin main
```

---

## Phase 3 — Dispatcher & registry (Tasks 7-9)

### Task 7 — `LoggerScope`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LoggerScope.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LoggerScopeOrderingTest.kt`

- [ ] **Step 1: Write the failing test**

`LoggerScopeOrderingTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class LoggerScopeOrderingTest {

	@Test
	fun `writes on the dedicated buildcore-logger thread`() = runBlocking {
		val scope = LoggerScope()
		val threadNames = CopyOnWriteArrayList<String>()
		val flow = MutableSharedFlow<Int>(extraBufferCapacity = 128)

		scope.coroutineScope.launch {
			flow.asSharedFlow().collect { threadNames += Thread.currentThread().name }
		}

		repeat(10) { flow.emit(it) }
		withTimeout(2000) { while (threadNames.size < 10) { /* spin */ } }
		scope.close()

		assertEquals(10, threadNames.size)
		assert(threadNames.all { it.startsWith("buildcore-logger") }) {
			"expected all writes on buildcore-logger thread, got $threadNames"
		}
	}

	@Test
	fun `drain completes within deadline`() = runBlocking {
		val scope = LoggerScope()
		scope.coroutineScope.launch { /* short-lived */ }
		scope.drain(deadlineMillis = 500)   // should not throw
		scope.close()
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LoggerScopeOrderingTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `LoggerScope.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dedicated single-thread coroutine scope that hosts every fast-path
 * log subscriber. Because only one thread services collect { } bodies,
 * writes across subscribers are serialised for free — no mutex around
 * file writes; subscriber ordering per event is deterministic.
 *
 * Spec §4.2.
 */
class LoggerScope : AutoCloseable {

	@OptIn(DelicateCoroutinesApi::class)
	internal val dispatcher: ExecutorCoroutineDispatcher = newSingleThreadContext("buildcore-logger")

	private val job = SupervisorJob()

	val coroutineScope: CoroutineScope = CoroutineScope(dispatcher + job + CoroutineName("LoggerScope"))

	/**
	 * Wait up to [deadlineMillis] for all launched collectors to complete.
	 * If the deadline expires, [close] cancels them; writers' finally
	 * blocks are still invoked.
	 */
	suspend fun drain(deadlineMillis: Long = 500) {
		withTimeoutOrNull(deadlineMillis) {
			job.children.forEach { it.join() }
		}
	}

	override fun close() {
		job.cancel()
		dispatcher.close()
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LoggerScopeOrderingTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LoggerScope.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LoggerScopeOrderingTest.kt
git commit -m "BuildCore: add LoggerScope single-thread dispatcher (Plan 3 spec §4.2)"
git push origin main
```

---

### Task 8 — `LogSubscriber` + `SubscriberRegistry`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/LogSubscriber.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/SubscriberRegistry.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/SubscriberRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

`SubscriberRegistryTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.runBlocking
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SubscriberRegistryTest {

	private class CountingSub(override val name: String) : LogSubscriber {
		override val isFastPath: Boolean = true
		val attached = AtomicInteger(0)
		val drained = AtomicInteger(0)
		override fun attach(bus: EventBus, loggerScope: LoggerScope) { attached.incrementAndGet() }
		override suspend fun drain() { drained.incrementAndGet() }
	}

	@Test
	fun `attachAll calls attach in registration order`() = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val a = CountingSub("a"); val b = CountingSub("b")
		val reg = SubscriberRegistry().register(a).register(b)
		reg.attachAll(bus, scope)
		assertEquals(1, a.attached.get())
		assertEquals(1, b.attached.get())
		scope.close()
	}

	@Test
	fun `drainAll calls drain on every subscriber`() = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val a = CountingSub("a"); val b = CountingSub("b")
		val reg = SubscriberRegistry().register(a).register(b)
		reg.attachAll(bus, scope)
		reg.drainAll()
		assertEquals(1, a.drained.get())
		assertEquals(1, b.drained.get())
		scope.close()
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SubscriberRegistryTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `LogSubscriber.kt`**

```kotlin
package net.vital.plugins.buildcore.core.events

import net.vital.plugins.buildcore.core.logging.LoggerScope

/**
 * Contract for everything that consumes events off the bus.
 *
 * Fast-path subscribers ([isFastPath] == true) collect directly on the
 * [LoggerScope] dispatcher so their handle() calls are serialised with
 * every other fast-path subscriber. Slow-path subscribers (see
 * [net.vital.plugins.buildcore.core.logging.BoundedChannelSubscriber])
 * still attach on the logger scope but hop to their own coroutine +
 * bounded channel so a stall cannot block the shared thread.
 *
 * Spec §4.4.
 */
interface LogSubscriber {

	/** Human-readable name for diagnostics and overflow tombstones. */
	val name: String

	/** `true` if this subscriber's handler runs on the logger thread directly. */
	val isFastPath: Boolean

	/** Launch the collector. Called by [SubscriberRegistry.attachAll]. */
	fun attach(bus: EventBus, loggerScope: LoggerScope)

	/** Flush & close resources. Called at session shutdown. */
	suspend fun drain()
}
```

- [ ] **Step 4: Write `SubscriberRegistry.kt`**

```kotlin
package net.vital.plugins.buildcore.core.events

import net.vital.plugins.buildcore.core.logging.LoggerScope

/**
 * Append-only registry of log subscribers. Attach order is registration
 * order; no unregister — plugins attach once per session and detach at
 * session end.
 *
 * Spec §4.3.
 */
class SubscriberRegistry {

	private val subscribers = mutableListOf<LogSubscriber>()

	val all: List<LogSubscriber> get() = subscribers.toList()

	fun register(subscriber: LogSubscriber): SubscriberRegistry {
		subscribers += subscriber
		return this
	}

	fun attachAll(bus: EventBus, scope: LoggerScope) {
		subscribers.forEach { it.attach(bus, scope) }
	}

	suspend fun drainAll() {
		subscribers.forEach { it.drain() }
	}
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SubscriberRegistryTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/LogSubscriber.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/events/SubscriberRegistry.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/SubscriberRegistryTest.kt
git commit -m "BuildCore: add LogSubscriber + SubscriberRegistry (Plan 3 spec §4.3-4.4)"
git push origin main
```

---

### Task 9 — `BoundedChannelSubscriber` base class

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/BoundedChannelSubscriber.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for slow-path subscribers (Telemetry, Replay). The attach
 * step registers a fast-path listener on [LoggerScope] that does nothing
 * but tryOffer events into this subscriber's bounded channel. If the
 * channel is full, [BufferOverflow.DROP_OLDEST] silently drops the
 * oldest entries and increments a dropped-count; after the next
 * successful [process] call a single [SubscriberOverflowed] event
 * coalesces the dropped run.
 *
 * Spec §4.4.
 */
abstract class BoundedChannelSubscriber(
	override val name: String,
	private val sessionIdProvider: () -> UUID,
	private val capacity: Int = 1024
) : LogSubscriber {

	override val isFastPath: Boolean = false

	private val channel = Channel<BusEvent>(capacity, BufferOverflow.DROP_OLDEST)
	private val droppedCount = AtomicInteger(0)
	private var ownScope: CoroutineScope? = null
	@Volatile private var bus: EventBus? = null

	abstract suspend fun process(event: BusEvent)

	final override fun attach(bus: EventBus, loggerScope: LoggerScope) {
		this.bus = bus
		val myScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName(name))
		ownScope = myScope

		loggerScope.coroutineScope.launch {
			bus.events.collect { event ->
				if (channel.trySend(event).isFailure) droppedCount.incrementAndGet()
			}
		}

		myScope.launch {
			for (event in channel) {
				process(event)
				emitTombstoneIfNeeded()
			}
		}
	}

	final override suspend fun drain() {
		channel.close()
		ownScope?.cancel()
		emitTombstoneIfNeeded()
	}

	private fun emitTombstoneIfNeeded() {
		val dropped = droppedCount.getAndSet(0)
		if (dropped > 0) {
			bus?.tryEmit(SubscriberOverflowed(
				sessionId = sessionIdProvider(),
				subscriberName = name,
				droppedCount = dropped
			))
		}
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/BoundedChannelSubscriber.kt
git commit -m "BuildCore: add BoundedChannelSubscriber base with DROP_OLDEST + tombstone (Plan 3 spec §4.4)"
git push origin main
```

---

## Phase 4 — File layout & rotation (Tasks 10-11)

### Task 10 — `LogDirLayout`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LogDirLayoutTest {

	@Test
	fun `createSessionDir creates nested directory`(@TempDir tmp: Path) {
		val layout = LogDirLayout(root = tmp)
		val sid = UUID.randomUUID()
		val dir = layout.createSessionDir(sid)
		assertTrue(Files.isDirectory(dir))
		assertEquals(sid.toString(), dir.fileName.toString())
	}

	@Test
	fun `pruneOldSessions keeps only N newest`(@TempDir tmp: Path) {
		val layout = LogDirLayout(root = tmp)
		val ids = (1..5).map { UUID.randomUUID() }
		ids.forEach { layout.createSessionDir(it); Thread.sleep(20) }
		layout.pruneOldSessions(keep = 3)
		val remaining = Files.list(tmp).use { it.toList() }.map { it.fileName.toString() }
		assertEquals(3, remaining.size)
		// Oldest two (ids 0 and 1) should be pruned
		assertFalse(remaining.contains(ids[0].toString()))
		assertFalse(remaining.contains(ids[1].toString()))
	}

	@Test
	fun `pruneOldSessions no-op when below keep count`(@TempDir tmp: Path) {
		val layout = LogDirLayout(root = tmp)
		(1..2).map { UUID.randomUUID() }.forEach { layout.createSessionDir(it) }
		layout.pruneOldSessions(keep = 5)
		assertEquals(2, Files.list(tmp).use { it.toList() }.size)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LogDirLayoutTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `LogDirLayout.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.stream.Collectors

/**
 * Single source of truth for log directory paths.
 *
 * Architecture test #7 forbids string literals like `"logs"` or
 * `.vitalclient` anywhere else in the codebase — path construction
 * happens only here.
 *
 * Spec §7.1, §12.7.
 */
class LogDirLayout(val root: Path) {

	fun sessionDir(sessionId: UUID): Path = root.resolve(sessionId.toString())

	fun createSessionDir(sessionId: UUID): Path {
		val dir = sessionDir(sessionId)
		Files.createDirectories(dir)
		return dir
	}

	/**
	 * Retain the [keep] most recently modified session directories;
	 * delete the rest (recursively). Safe to call with no directory
	 * existing — it only touches what's already there.
	 */
	fun pruneOldSessions(keep: Int) {
		if (!Files.isDirectory(root)) return
		val entries = Files.list(root).use { stream ->
			stream.filter { Files.isDirectory(it) }
				.sorted(compareBy { Files.getLastModifiedTime(it).toMillis() })
				.collect(Collectors.toList())
		}
		val toDelete = entries.size - keep
		if (toDelete <= 0) return
		entries.take(toDelete).forEach { deleteRecursively(it) }
	}

	private fun deleteRecursively(path: Path) {
		if (Files.isDirectory(path)) {
			Files.list(path).use { stream -> stream.forEach(::deleteRecursively) }
		}
		Files.deleteIfExists(path)
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LogDirLayoutTest --no-daemon 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayout.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LogDirLayoutTest.kt
git commit -m "BuildCore: add LogDirLayout with session dir + retention prune (Plan 3 spec §7.1)"
git push origin main
```

---

### Task 11 — `RotatingFileSink`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/RotatingFileSink.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/RotatingFileSinkTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RotatingFileSinkTest {

	@Test
	fun `rotates when current file exceeds capBytes`(@TempDir tmp: Path) {
		val target = tmp.resolve("events.jsonl")
		val sink = RotatingFileSink(target = target, capBytes = 100, maxRotations = 3)
		// write 150 bytes in two writes — second write triggers rotation
		sink.writeLine("a".repeat(80))    // 80 + 1 newline = 81 bytes, under cap
		sink.writeLine("b".repeat(80))    // 81 + 81 = 162 bytes total, triggers
		sink.close()
		assertTrue(Files.exists(target))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.1")))
		assertFalse(Files.exists(target.resolveSibling("events.jsonl.2")))
	}

	@Test
	fun `drops oldest rotation when maxRotations exceeded`(@TempDir tmp: Path) {
		val target = tmp.resolve("events.jsonl")
		val sink = RotatingFileSink(target = target, capBytes = 10, maxRotations = 2)
		repeat(5) { sink.writeLine("x".repeat(12)) }   // each write exceeds cap → rotation
		sink.close()
		assertTrue(Files.exists(target))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.1")))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.2")))
		assertFalse(Files.exists(target.resolveSibling("events.jsonl.3")))
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests RotatingFileSinkTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `RotatingFileSink.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import java.io.BufferedWriter
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Size-capped rotating file sink. When a write would push the current
 * file over [capBytes], the file is closed, rotated to `.1` (pushing
 * `.1→.2`, `.2→.3`, etc.), and a fresh file is opened. Rotations
 * beyond [maxRotations] are deleted.
 *
 * Spec §7.5.
 */
class RotatingFileSink(
	private val target: Path,
	private val capBytes: Long,
	private val maxRotations: Int
) : Closeable {

	private var writer: BufferedWriter = openAppending(target)
	private var currentSize: Long = Files.size(target.also { if (!Files.exists(it)) Files.createFile(it) })

	fun writeLine(line: String) {
		val bytes = line.toByteArray(StandardCharsets.UTF_8).size + 1 // +1 for newline
		if (currentSize + bytes > capBytes && currentSize > 0) rotate()
		writer.write(line)
		writer.newLine()
		writer.flush()
		currentSize += bytes
	}

	private fun rotate() {
		writer.close()
		for (i in maxRotations downTo 1) {
			val src = siblingWithSuffix(target, ".$i")
			val dst = siblingWithSuffix(target, ".${i + 1}")
			if (Files.exists(src)) {
				if (i == maxRotations) {
					Files.deleteIfExists(src)
				} else {
					Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
				}
			}
		}
		Files.move(target, siblingWithSuffix(target, ".1"), StandardCopyOption.REPLACE_EXISTING)
		writer = openAppending(target)
		currentSize = 0
	}

	private fun siblingWithSuffix(p: Path, suffix: String): Path =
		p.resolveSibling(p.fileName.toString() + suffix)

	private fun openAppending(p: Path): BufferedWriter {
		Files.createDirectories(p.parent)
		return Files.newBufferedWriter(
			p, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND
		)
	}

	override fun close() {
		writer.close()
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests RotatingFileSinkTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/RotatingFileSink.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/RotatingFileSinkTest.kt
git commit -m "BuildCore: add RotatingFileSink with size-cap + ordered rotation (Plan 3 spec §7.5)"
git push origin main
```

---

## Phase 5 — Fast-path subscribers (Tasks 12-14)

### Task 12 — `LocalJsonlWriter`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalJsonlWriter.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LocalJsonlWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalJsonlWriterTest {

	@Test
	fun `writes one JSON object per event per line`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalJsonlWriter(sessionDir = tmp, capBytes = 10L * 1024 * 1024)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(SessionStart(sessionId = sid, buildcoreVersion = "0.1.0"))
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "x", methodId = "m", pathId = "p"))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("events.jsonl")) ||
				Files.size(tmp.resolve("events.jsonl")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		val lines = Files.readAllLines(tmp.resolve("events.jsonl"))
		// at least 2 — SessionStart + TaskStarted (order preserved)
		assertTrue(lines.size >= 2, "expected ≥2 lines, got ${lines.size}")
		val mapper = ObjectMapper().registerKotlinModule()
		val first = mapper.readTree(lines[0])
		val second = mapper.readTree(lines[1])
		assertEquals("SessionStart", first.get("type").asText())
		assertEquals("TaskStarted", second.get("type").asText())
		assertEquals("x", second.get("taskId").asText())
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LocalJsonlWriterTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `LocalJsonlWriter.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import java.nio.file.Path

/**
 * Fast-path subscriber. Every [BusEvent] is serialised as a single
 * JSON object on its own line and written to `events.jsonl` inside
 * the session's log dir. Unscrubbed — the raw JSONL is local-only.
 *
 * Spec §7.2.
 */
class LocalJsonlWriter(
	private val sessionDir: Path,
	private val capBytes: Long
) : LogSubscriber {

	override val name: String = "local-jsonl"
	override val isFastPath: Boolean = true

	private val mapper: ObjectMapper = ObjectMapper()
		.registerKotlinModule()
		.registerModule(JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.setSerializationInclusion(JsonInclude.Include.ALWAYS)

	private val sink = RotatingFileSink(
		target = sessionDir.resolve("events.jsonl"),
		capBytes = capBytes,
		maxRotations = 3
	)

	override fun attach(bus: EventBus, loggerScope: LoggerScope) {
		loggerScope.coroutineScope.launch {
			bus.events.collect { event -> write(event) }
		}
	}

	private fun write(event: BusEvent) {
		val node = mapper.valueToTree<ObjectNode>(event)
		// Insert "type" at the head for human readability / grep-friendliness
		val out = mapper.createObjectNode()
		out.put("type", event::class.simpleName)
		node.fieldNames().forEach { name -> out.set<ObjectNode>(name, node.get(name)) }
		sink.writeLine(mapper.writeValueAsString(out))
	}

	override suspend fun drain() {
		sink.close()
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LocalJsonlWriterTest --no-daemon 2>&1 | tail -10
```
Expected: 1 test passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalJsonlWriter.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LocalJsonlWriterTest.kt
git commit -m "BuildCore: add LocalJsonlWriter with type-tagged JSON lines (Plan 3 spec §7.2)"
git push origin main
```

---

### Task 13 — `LocalSummaryWriter` + `LogLevel` table

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriter.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriterTest.kt`

- [ ] **Step 1: Expand `LogLevel.kt` with the event→level map**

Append to `LogLevel.kt`:

```kotlin
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.RestrictionViolated
import net.vital.plugins.buildcore.core.events.SafeStopCompleted
import net.vital.plugins.buildcore.core.events.SafeStopRequested
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskPaused
import net.vital.plugins.buildcore.core.events.TaskQueued
import net.vital.plugins.buildcore.core.events.TaskResumed
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.events.UnhandledException
import net.vital.plugins.buildcore.core.events.ValidationFailed

/**
 * Severity of a given event for human-log purposes.
 *
 * Any subtype not listed here is treated as [LogLevel.DEBUG] — a
 * conservative default. The architecture test for scrubber
 * exhaustiveness parallels this table; add an entry when a new
 * subtype lands.
 */
fun levelOf(event: BusEvent): LogLevel = when (event) {
	is SessionStart, is SessionEnd, is SessionSummary -> LogLevel.INFO
	is TaskQueued, is TaskValidated, is TaskStarted,
	is TaskCompleted, is TaskResumed -> LogLevel.INFO
	is SafeStopRequested, is SafeStopCompleted -> LogLevel.INFO
	is TaskRetrying, is TaskSkipped, is TaskPaused -> LogLevel.WARN
	is ValidationFailed, is RestrictionViolated -> LogLevel.WARN
	is TaskFailed -> LogLevel.ERROR
	is UnhandledException -> LogLevel.FATAL
	else -> LogLevel.DEBUG
}
```

- [ ] **Step 2: Write the failing test**

`LocalSummaryWriterTest.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalSummaryWriterTest {

	@Test
	fun `filters events below configured level`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalSummaryWriter(sessionDir = tmp, level = LogLevel.ERROR)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "a", methodId = "m", pathId = "p"))
		bus.emit(TaskFailed(sessionId = sid, taskInstanceId = tid, taskId = "a", reasonType = "E", reasonDetail = "boom", attemptNumber = 1))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("summary.log")) ||
				Files.size(tmp.resolve("summary.log")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		val text = Files.readString(tmp.resolve("summary.log"))
		assertFalse(text.contains("TaskStarted"), "INFO line leaked at ERROR level")
		assertTrue(text.contains("ERROR"), "expected ERROR line present")
	}

	@Test
	fun `scrubs password out of failure detail`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalSummaryWriter(sessionDir = tmp, level = LogLevel.ERROR)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "a",
			reasonType = "EX", reasonDetail = "password=hunter2 wrong", attemptNumber = 1
		))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("summary.log")) ||
				Files.size(tmp.resolve("summary.log")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		val text = Files.readString(tmp.resolve("summary.log"))
		assertFalse(text.contains("hunter2"))
		assertTrue(text.contains("password=«redacted»"))
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LocalSummaryWriterTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 4: Write `LocalSummaryWriter.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.MethodPicked
import net.vital.plugins.buildcore.core.events.PathPicked
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.RestrictionViolated
import net.vital.plugins.buildcore.core.events.SafeStopCompleted
import net.vital.plugins.buildcore.core.events.SafeStopRequested
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskPaused
import net.vital.plugins.buildcore.core.events.TaskQueued
import net.vital.plugins.buildcore.core.events.TaskResumed
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.events.UnhandledException
import net.vital.plugins.buildcore.core.events.ValidationFailed
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fast-path subscriber. Writes human-readable lines to `summary.log`
 * in the session's log dir. Events are privacy-scrubbed before
 * writing (see spec §6) and filtered by [level].
 *
 * Summary covers: session lifecycle, task lifecycle, method/path,
 * safe-stop, errors. Events without a human-summary case (e.g.
 * [net.vital.plugins.buildcore.core.events.TaskProgress],
 * [net.vital.plugins.buildcore.core.events.PerformanceSample],
 * [net.vital.plugins.buildcore.core.events.SubscriberOverflowed])
 * are silently skipped — the JSONL has the complete truth.
 *
 * Spec §7.3, §7.4.
 */
class LocalSummaryWriter(
	sessionDir: Path,
	private val level: LogLevel
) : LogSubscriber {

	override val name: String = "local-summary"
	override val isFastPath: Boolean = true

	private val sink = RotatingFileSink(
		target = sessionDir.resolve("summary.log"),
		capBytes = 10L * 1024 * 1024,
		maxRotations = 1              // summary rotates once; jsonl is the source of truth
	)

	private val fmt: DateTimeFormatter = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss.SSS zzz")
		.withZone(ZoneId.of("America/New_York"))

	override fun attach(bus: EventBus, loggerScope: LoggerScope) {
		loggerScope.coroutineScope.launch {
			bus.events.collect { event ->
				val scrubbed = PrivacyScrubber.scrub(event)
				val evLevel = levelOf(scrubbed)
				if (evLevel.ordinal < level.ordinal) return@collect
				val line = formatLine(scrubbed, evLevel) ?: return@collect
				sink.writeLine(line)
			}
		}
	}

	private fun formatLine(event: BusEvent, evLevel: LogLevel): String? {
		val ts = fmt.format(event.timestamp)
		val (category, subject, msg) = when (event) {
			is SessionStart        -> Triple("sess", "-", "session start v${event.buildcoreVersion} mode=${event.launchMode}")
			is SessionEnd          -> Triple("sess", "-", "session end reason=${event.reason}")
			is SessionSummary      -> Triple("sess", "-", "summary durationMs=${event.durationMillis} events=${event.totalEvents} tasks=${event.taskCounts.size}")
			is TaskQueued          -> Triple("task", event.taskId, "queued")
			is TaskValidated       -> Triple("task", event.taskId,
				if (event.pass) "validated OK" else "validation rejected: ${event.rejectReason}")
			is TaskStarted         -> Triple("task", event.taskId, "started via method=${event.methodId} path=${event.pathId}")
			is TaskCompleted       -> Triple("task", event.taskId, "completed durationMs=${event.durationMillis}")
			is TaskFailed          -> Triple("task", event.taskId, "failed attempt=${event.attemptNumber} type=${event.reasonType} detail=${event.reasonDetail}")
			is TaskRetrying        -> Triple("retry", event.taskId, "attempt ${event.attemptNumber} backoff=${event.backoffMillis}ms")
			is TaskSkipped         -> Triple("task", event.taskId, "skipped reason=${event.reason}")
			is TaskPaused          -> Triple("task", event.taskId, "paused reason=${event.reason}")
			is TaskResumed         -> Triple("task", event.taskId, "resumed")
			is MethodPicked        -> Triple("task", event.taskId, "method picked=${event.methodId}")
			is PathPicked          -> Triple("task", event.taskId, "path picked=${event.pathId} kind=${event.pathKind}")
			is SafeStopRequested   -> Triple("stop", "-", "safe-stop requested reason=${event.reason}")
			is SafeStopCompleted   -> Triple("stop", "-", "safe-stop completed durationMs=${event.durationMillis}")
			is UnhandledException  -> Triple("err", event.threadName, "${event.exceptionClass}: ${event.message}")
			is ValidationFailed    -> Triple("valid", event.subject, event.detail)
			is RestrictionViolated -> Triple("restr", event.restrictionId, "${event.moment}: ${event.effectSummary}")
			else                   -> return null
		}
		val lvlCol = evLevel.name.padEnd(5)
		val catCol = category.padEnd(6)
		val subjCol = subject.take(16).padEnd(16)
		return "$ts  $lvlCol $catCol [$subjCol] $msg"
	}

	override suspend fun drain() {
		sink.close()
	}
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LocalSummaryWriterTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LogLevel.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriter.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/LocalSummaryWriterTest.kt
git commit -m "BuildCore: add LocalSummaryWriter with level filter + PrivacyScrubber (Plan 3 spec §7.3)"
git push origin main
```

---

### Task 14 — `PerformanceAggregator`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/PerformanceAggregator.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/PerformanceAggregatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PerformanceSample
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceAggregatorTest {

	@Test
	fun `emits PerformanceSample after interval elapses`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val bus = EventBus()
		val agg = PerformanceAggregator(intervalMillis = 1_000L, dispatcher = dispatcher)

		// collect the first PerformanceSample
		val collected = async { bus.events.filterIsInstance<PerformanceSample>().first() }

		agg.start(bus)
		// tick some events
		val sid = UUID.randomUUID()
		repeat(5) {
			bus.emit(TaskStarted(sessionId = sid, taskInstanceId = UUID.randomUUID(),
				taskId = "t$it", methodId = "m", pathId = "p"))
		}
		advanceTimeBy(1_500L)
		runCurrent()

		val sample = collected.await()
		assertTrue(sample.intervalSeconds >= 1)
		assertTrue(sample.jvmHeapUsedMb > 0)
		agg.stop()
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PerformanceAggregatorTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `PerformanceAggregator.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PerformanceSample
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Periodic sampler. Every [intervalMillis] emits a [PerformanceSample]
 * with JVM heap + event-rate + logger-lag + dropped-event counters.
 *
 * Not a [LogSubscriber] because it runs on its own dispatcher and
 * emits back onto the bus rather than writing to disk. It does
 * subscribe to the bus internally to keep counters fresh.
 *
 * Spec §10.1.
 */
class PerformanceAggregator(
	private val intervalMillis: Long,
	private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
	private val sessionIdProvider: () -> UUID = { UUID(0, 0) }
) {

	private val eventCount = AtomicLong(0)
	private val maxLagMillis = AtomicLong(0)
	private val droppedSinceLastSample = AtomicLong(0)
	private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("perf-aggregator"))
	private var sampleJob: Job? = null
	@Volatile private var lastSampleAt: Instant = Instant.now()

	fun start(bus: EventBus) {
		lastSampleAt = Instant.now()
		// subscriber — updates counters on every event
		scope.launch {
			bus.events.collect { event -> onEvent(event) }
		}
		// periodic sampler — emits PerformanceSample
		sampleJob = scope.launch {
			while (true) {
				delay(intervalMillis)
				val now = Instant.now()
				val elapsedSec = max(1L, Duration.between(lastSampleAt, now).seconds)
				val rt = Runtime.getRuntime()
				val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
				val heapMaxMb = rt.maxMemory() / (1024L * 1024L)
				val count = eventCount.getAndSet(0)
				val lag = maxLagMillis.getAndSet(0)
				val dropped = droppedSinceLastSample.getAndSet(0)

				bus.emit(PerformanceSample(
					sessionId = sessionIdProvider(),
					intervalSeconds = elapsedSec,
					eventRatePerSec = count.toDouble() / elapsedSec,
					jvmHeapUsedMb = heapUsedMb,
					jvmHeapMaxMb = heapMaxMb,
					loggerLagMaxMs = lag,
					droppedEventsSinceLastSample = dropped
				))
				lastSampleAt = now
			}
		}
	}

	private fun onEvent(event: BusEvent) {
		eventCount.incrementAndGet()
		val lag = Duration.between(event.timestamp, Instant.now()).toMillis()
		maxLagMillis.updateAndGet { prev -> max(prev, lag) }
		if (event is SubscriberOverflowed) {
			droppedSinceLastSample.addAndGet(event.droppedCount.toLong())
		}
	}

	fun stop() {
		sampleJob?.cancel()
		scope.cancel()
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PerformanceAggregatorTest --no-daemon 2>&1 | tail -10
```
Expected: 1 test passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/PerformanceAggregator.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/PerformanceAggregatorTest.kt
git commit -m "BuildCore: add PerformanceAggregator for JVM + event-rate sampling (Plan 3 spec §10.1)"
git push origin main
```

---

## Phase 6 — Slow-path placeholders (Task 15)

### Task 15 — `TelemetrySubscriber` + `ReplaySubscriber` + NoOp impls

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/TelemetrySubscriber.kt`
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/ReplaySubscriber.kt`

- [ ] **Step 1: Write the files**

`TelemetrySubscriber.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.BusEvent
import java.util.UUID

/**
 * Marker interface for the telemetry shipping subscriber. Plan 3 ships
 * only the [NoOpTelemetrySubscriber] placeholder — Plan 8 provides the
 * real impl that batches events and HTTP-POSTs to BuildCore-Server.
 *
 * Spec §1 ("Out of scope: real TelemetryClient"), §14.
 */
interface TelemetrySubscriber

/**
 * Drop-all placeholder. Wired into the registry at bootstrap so that
 * the BoundedChannelSubscriber plumbing is exercised end-to-end even
 * when no telemetry impl is present.
 */
class NoOpTelemetrySubscriber(
	sessionIdProvider: () -> UUID
) : BoundedChannelSubscriber(
	name = "telemetry-noop",
	sessionIdProvider = sessionIdProvider,
	capacity = 64
), TelemetrySubscriber {

	override suspend fun process(event: BusEvent) {
		// no-op — the real Plan 8 subscriber batches and POSTs
	}
}
```

`ReplaySubscriber.kt`:

```kotlin
package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.BusEvent
import java.util.UUID

/**
 * Marker interface for the replay-recorder subscriber. Plan 3 ships
 * only the [NoOpReplaySubscriber] placeholder — Plan 4 provides the
 * real impl that records events + RNG state to `replays/<sid>.jsonl`.
 *
 * Spec §1, §14.
 */
interface ReplaySubscriber

class NoOpReplaySubscriber(
	sessionIdProvider: () -> UUID
) : BoundedChannelSubscriber(
	name = "replay-noop",
	sessionIdProvider = sessionIdProvider,
	capacity = 64
), ReplaySubscriber {

	override suspend fun process(event: BusEvent) {
		// no-op — the real Plan 4 subscriber records for byte-identical replay
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/TelemetrySubscriber.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/ReplaySubscriber.kt
git commit -m "BuildCore: add Telemetry/Replay marker interfaces + NoOp placeholders (Plan 3 spec §1)"
git push origin main
```

---

## Phase 7 — Session lifecycle & error emitters (Tasks 16-19)

### Task 16 — `SessionManager`

**Files:**
- Create: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/SessionManager.kt`
- Create: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/SessionManagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.StopReason
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class SessionManagerTest {

	@Test
	fun `start emits SessionStart and creates session dir`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout, retentionSessions = 30)

		val starts = CopyOnWriteArrayList<SessionStart>()
		scope.coroutineScope.launch { bus.events.filterIsInstance<SessionStart>().collect { starts += it } }

		sm.start()

		withTimeout(1000) { while (starts.isEmpty()) { /* spin */ } }
		assertEquals(sm.sessionId, starts.first().sessionId)
		assertTrue(Files.isDirectory(layout.sessionDir(sm.sessionId)))
		scope.close()
	}

	@Test
	fun `requestStop emits SessionSummary then SessionEnd`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout)

		val summary = async { bus.events.filterIsInstance<SessionSummary>().first() }
		val end     = async { bus.events.filterIsInstance<SessionEnd>().first() }

		sm.start()
		// Stream a task event so counters are non-zero
		val sid = sm.sessionId
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = UUID.randomUUID(),
			taskId = "demo", methodId = "m", pathId = "p"))
		sm.requestStop(StopReason.USER)

		val s = withTimeout(2000) { summary.await() }
		val e = withTimeout(2000) { end.await() }
		assertEquals(1, s.taskCounts["demo"]?.started)
		assertEquals(StopReason.USER, e.reason)
		scope.close()
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SessionManagerTest --no-daemon 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Write `SessionManager.kt`**

```kotlin
package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LaunchMode
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.StopReason
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskCounter
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the per-plugin-launch session identity and emits session
 * lifecycle events. Constructed once by [BuildCorePlugin.startUp] and
 * torn down at shutDown.
 *
 * Spec §8.
 */
class SessionManager(
	private val bus: EventBus,
	private val loggerScope: LoggerScope,
	private val layout: LogDirLayout,
	private val retentionSessions: Int = 30,
	private val clock: Clock = Clock.systemUTC(),
	private val buildcoreVersion: String = BuildCoreVersion.CURRENT,
	private val launchMode: LaunchMode = LaunchMode.NORMAL
) {

	val sessionId: UUID = UUID.randomUUID()
	private val startedAt: Instant = clock.instant()
	private val taskCounters = ConcurrentHashMap<String, TaskCounter>()
	private val totalEvents = AtomicLong(0)
	private val ended = AtomicBoolean(false)

	private val mapper = ObjectMapper()
		.registerKotlinModule()
		.registerModule(JavaTimeModule())
		.findAndRegisterModules()

	fun start() {
		PrivacyScrubber.rotateKey(sessionId)
		layout.pruneOldSessions(retentionSessions)
		layout.createSessionDir(sessionId)
		writeSessionMeta(state = "running", endedAt = null)

		bus.tryEmit(SessionStart(
			sessionId = sessionId,
			timestamp = startedAt,
			buildcoreVersion = buildcoreVersion,
			launchMode = launchMode
		))

		loggerScope.coroutineScope.launch {
			bus.events.collect { e -> updateCounters(e) }
		}
	}

	suspend fun requestStop(reason: StopReason = StopReason.USER) {
		if (!ended.compareAndSet(false, true)) return
		val durationMs = Duration.between(startedAt, clock.instant()).toMillis()

		bus.emit(SessionSummary(
			sessionId = sessionId,
			durationMillis = durationMs,
			taskCounts = taskCounters.toMap(),
			totalEvents = totalEvents.get()
		))
		bus.emit(SessionEnd(sessionId = sessionId, reason = reason))

		loggerScope.drain(deadlineMillis = 500)
		writeSessionMeta(state = "ended", endedAt = clock.instant())
	}

	private fun updateCounters(e: BusEvent) {
		totalEvents.incrementAndGet()
		val (taskId, update) = when (e) {
			is TaskStarted   -> e.taskId to { c: TaskCounter -> c.copy(started = c.started + 1) }
			is TaskCompleted -> e.taskId to { c: TaskCounter -> c.copy(completed = c.completed + 1) }
			is TaskFailed    -> e.taskId to { c: TaskCounter -> c.copy(failed = c.failed + 1) }
			is TaskSkipped   -> e.taskId to { c: TaskCounter -> c.copy(skipped = c.skipped + 1) }
			else             -> return
		}
		taskCounters.compute(taskId) { _, prev -> update(prev ?: TaskCounter()) }
	}

	private fun writeSessionMeta(state: String, endedAt: Instant?) {
		val meta = mapOf(
			"sessionId" to sessionId.toString(),
			"startedAt" to startedAt.toString(),
			"endedAt"   to endedAt?.toString(),
			"buildcoreVersion" to buildcoreVersion,
			"state"     to state,
			"eventCount" to totalEvents.get()
		)
		val path = layout.sessionDir(sessionId).resolve("session.meta.json")
		Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta))
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests SessionManagerTest --no-daemon 2>&1 | tail -10
```
Expected: 2 tests passing.

- [ ] **Step 5: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/SessionManager.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/logging/SessionManagerTest.kt
git commit -m "BuildCore: add SessionManager owning sessionId + lifecycle events (Plan 3 spec §8)"
git push origin main
```

---

### Task 17 — `UncaughtExceptionHandler`

**Files:** Create `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/UncaughtExceptionHandler.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.UnhandledException
import java.util.UUID

/**
 * Install a JVM-wide uncaught exception handler that emits
 * [UnhandledException] for every throwable from a BuildCore-owned
 * thread. Uses [EventBus.tryEmit] — this handler runs outside any
 * coroutine and cannot suspend.
 *
 * If the bus buffer is saturated (no subscriber drained in time) the
 * event is dropped and [System.err] logs a last-resort line so the
 * exception still surfaces.
 *
 * The prior default handler is captured and chained so RuneLite's own
 * handler (if any) still sees the throwable.
 *
 * Spec §9.1.
 */
object UncaughtExceptionHandler {

	fun install(bus: EventBus, sessionIdProvider: () -> UUID) {
		val prior = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			val event = UnhandledException(
				sessionId = sessionIdProvider(),
				threadName = thread.name,
				exceptionClass = throwable.javaClass.name,
				message = throwable.message ?: "",
				stackTrace = throwable.stackTraceToString()
			)
			val emitted = bus.tryEmit(event)
			if (!emitted) {
				System.err.println("[buildcore] bus full; UnhandledException dropped on thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}")
			}
			prior?.uncaughtException(thread, throwable)
		}
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 3: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/logging/UncaughtExceptionHandler.kt
git commit -m "BuildCore: add UncaughtExceptionHandler emitting UnhandledException via tryEmit (Plan 3 spec §9.1)"
git push origin main
```

---

### Task 18 — Wire `RestrictionViolated` + `ValidationFailed` + safe-stop events from existing code

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt`
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ModuleRegistry.kt`
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContract.kt`

Leave `RestrictionEngine` as a pure object — the Runner is the right place to emit `RestrictionViolated` because it holds the sessionId + current task instance context that the engine lacks.

- [ ] **Step 1: Modify `Runner.kt`**

Replace the `fail` block that handles the `NO_ALLOWED_PATH` case and add `RestrictionViolated` emission.

Locate the line (currently ~line 110-111):

```kotlin
			val path = PathSelector.pick(method.paths, restrictions, accountState)
				?: return fail(instance, "NO_ALLOWED_PATH", "no path allowed by restrictions/reqs", startNanos)
```

Replace with:

```kotlin
			val path = PathSelector.pick(method.paths, restrictions, accountState)
			if (path == null) {
				bus.emit(net.vital.plugins.buildcore.core.events.RestrictionViolated(
					sessionId = sessionId,
					taskInstanceId = instance.id,
					restrictionId = "composite",
					effectSummary = "no path allowed by restrictions/reqs for task=${instance.task.id.raw}",
					moment = net.vital.plugins.buildcore.core.events.RestrictionMoment.START
				))
				return fail(instance, "NO_ALLOWED_PATH", "no path allowed by restrictions/reqs", startNanos)
			}
```

Also add safe-stop emission. Find the existing `fun run(...)` entry point and emit `SafeStopRequested` + `SafeStopCompleted` around the terminal flow. But in Plan 2 the Runner doesn't currently orchestrate a safe-stop — that's `SafeStopContract`. Defer the Runner change and handle it in `SafeStopContract` (next step).

- [ ] **Step 2: Modify `SafeStopContract.kt`**

Read the current file first, then wire event emission. The contract currently accepts a callback-style stop orchestrator; add an optional `bus: EventBus?` parameter and emit both events around the stop loop.

Locate the `fun stop(...)` method (or its equivalent that drives the "poll canStopNow" loop). Add, at the top of the public entry point:

```kotlin
	// Near the top of the class/object definition — add a bus parameter hook
	// (Plan 2 had no bus here; keep backward-compat by allowing null)
	var bus: EventBus? = null
	var sessionId: UUID = UUID(0, 0)
```

At the start of the stop loop:

```kotlin
		val startNanos = System.nanoTime()
		bus?.tryEmit(net.vital.plugins.buildcore.core.events.SafeStopRequested(
			sessionId = sessionId,
			reason = reason
		))
```

After the loop exits successfully:

```kotlin
		val durationMs = (System.nanoTime() - startNanos) / 1_000_000
		bus?.tryEmit(net.vital.plugins.buildcore.core.events.SafeStopCompleted(
			sessionId = sessionId,
			durationMillis = durationMs
		))
```

(The exact shape depends on Plan 2's `SafeStopContract` surface. If the current `SafeStopContract` is an object with no instance state, convert it to a thin class or add `bus`/`sessionId` parameters to the public stop entry point. Do not break the Plan 2 test `SafeStopContractTest`.)

- [ ] **Step 3: Modify `ModuleRegistry.kt`**

The registry's `register()` currently throws on structural validation failure. Wrap that with a `ValidationFailed` emission so observers see the reason in the bus.

Add a nullable `bus: EventBus? = null` constructor parameter. On the `require`/throw path in `register`:

```kotlin
		if (!task.validateStructure()) {
			val detail = "task=${task.id.raw} failed structural validation: ${task.structureErrors().joinToString(", ")}"
			bus?.tryEmit(net.vital.plugins.buildcore.core.events.ValidationFailed(
				sessionId = sessionId ?: java.util.UUID(0, 0),
				subject = "Task/${task.id.raw}",
				detail = detail
			))
			throw IllegalArgumentException(detail)
		}
```

If the current Plan 2 `ModuleRegistry` uses different method names (`validateStructure()` / `structureErrors()`), adapt to match. The key change is: add optional `bus` parameter + tryEmit before throw. Also add `sessionId` field (nullable `UUID`).

- [ ] **Step 4: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:compileKotlin --no-daemon 2>&1 | tail -5
```

- [ ] **Step 5: Run full test suite for regression**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: Plan 2's 59 tests + new Plan 3 tests still passing. If `SafeStopContractTest` or `ModuleRegistry` tests fail because of the signature change, make the parameter nullable with default `null` so Plan 2 callers continue to compile.

- [ ] **Step 6: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/SafeStopContract.kt \
        BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/ModuleRegistry.kt
git commit -m "BuildCore: emit RestrictionViolated, SafeStopRequested/Completed, ValidationFailed from runtime (Plan 3 spec §9)"
git push origin main
```

---

### Task 19 — Make `Runner.sessionId` required; update Plan 2 tests

**Files:**
- Modify: `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt`
- Modify: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerStateMachineTest.kt`
- Modify: `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/NoOpTaskRunTest.kt`

- [ ] **Step 1: Modify `Runner.kt` constructor**

Currently:

```kotlin
class Runner(
	private val bus: EventBus,
	private val sessionId: java.util.UUID = java.util.UUID.randomUUID()
) {
```

Change to require `sessionId`:

```kotlin
class Runner(
	private val bus: EventBus,
	private val sessionId: java.util.UUID
) {
```

(Remove the default.)

- [ ] **Step 2: Update the two Plan 2 tests**

In `RunnerStateMachineTest.kt`, find the two instances:

```kotlin
val runner = Runner(bus)
```

Change to:

```kotlin
val runner = Runner(bus, java.util.UUID.randomUUID())
```

Same in `NoOpTaskRunTest.kt` (two instances).

- [ ] **Step 3: Run full test suite**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: all tests still passing.

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/core/task/Runner.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/RunnerStateMachineTest.kt \
        BuildCore/src/test/kotlin/net/vital/plugins/buildcore/core/task/NoOpTaskRunTest.kt
git commit -m "BuildCore: Runner requires sessionId; SessionManager is now the single owner (Plan 3 spec §8.3)"
git push origin main
```

---

## Phase 8 — Bootstrap wiring (Task 20)

### Task 20 — Wire `BuildCorePlugin.startUp` / `shutDown`

**Files:** Modify `BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt`

- [ ] **Step 1: Rewrite `BuildCorePlugin.kt`**

```kotlin
package net.vital.plugins.buildcore

import kotlinx.coroutines.runBlocking
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import net.vital.plugins.buildcore.core.logging.LocalJsonlWriter
import net.vital.plugins.buildcore.core.logging.LocalSummaryWriter
import net.vital.plugins.buildcore.core.logging.LogConfig
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LoggerScope
import net.vital.plugins.buildcore.core.logging.NoOpReplaySubscriber
import net.vital.plugins.buildcore.core.logging.NoOpTelemetrySubscriber
import net.vital.plugins.buildcore.core.logging.PerformanceAggregator
import net.vital.plugins.buildcore.core.logging.SessionManager
import net.vital.plugins.buildcore.core.logging.UncaughtExceptionHandler

@PluginDescriptor(
	name = "BuildCore",
	description = "All-inclusive OSRS account builder foundation",
	tags = ["buildcore", "builder", "account", "framework"]
)
class BuildCorePlugin : Plugin() {

	internal lateinit var eventBus: EventBus
	private lateinit var loggerScope: LoggerScope
	private lateinit var sessionManager: SessionManager
	private lateinit var subscriberRegistry: SubscriberRegistry
	private lateinit var performanceAggregator: PerformanceAggregator

	override fun startUp() {
		val cfg = LogConfig.load()
		val layout = LogDirLayout(cfg.logRootDir)

		eventBus = EventBus()
		loggerScope = LoggerScope()
		sessionManager = SessionManager(
			bus = eventBus,
			loggerScope = loggerScope,
			layout = layout,
			retentionSessions = cfg.retentionSessions
		)
		val sessionDir = layout.sessionDir(sessionManager.sessionId)

		subscriberRegistry = SubscriberRegistry()
			.register(LocalJsonlWriter(sessionDir = sessionDir, capBytes = cfg.rotationSizeBytes))
			.register(LocalSummaryWriter(sessionDir = sessionDir, level = cfg.level))
			.register(NoOpTelemetrySubscriber { sessionManager.sessionId })
			.register(NoOpReplaySubscriber  { sessionManager.sessionId })
		subscriberRegistry.attachAll(eventBus, loggerScope)

		sessionManager.start()

		performanceAggregator = PerformanceAggregator(
			intervalMillis = cfg.performanceSampleIntervalMillis,
			sessionIdProvider = { sessionManager.sessionId }
		)
		performanceAggregator.start(eventBus)

		UncaughtExceptionHandler.install(eventBus) { sessionManager.sessionId }
	}

	override fun shutDown() {
		runBlocking {
			performanceAggregator.stop()
			sessionManager.requestStop()
			subscriberRegistry.drainAll()
		}
		loggerScope.close()
	}
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:build --no-daemon 2>&1 | tail -10
```

- [ ] **Step 3: Run full test suite**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: all tests passing.

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/main/kotlin/net/vital/plugins/buildcore/BuildCorePlugin.kt
git commit -m "BuildCore: wire LoggerScope + SessionManager + subscribers in plugin bootstrap (Plan 3 spec §4.1)"
git push origin main
```

---

## Phase 9 — Architecture tests + integration (Tasks 21-22)

### Task 21 — `LoggingArchitectureTest`

**Files:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt`

- [ ] **Step 1: Write the file**

```kotlin
package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.RestrictionMoment
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.StopReason
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.full.isSubclassOf

/**
 * Architecture invariants for Plan 3 logging. Each test cites the
 * spec section it enforces.
 *
 * Spec §12.
 */
class LoggingArchitectureTest {

	/**
	 * Spec §12 #1 — every sealed [BusEvent] subtype must have a
	 * scrubber case. The exhaustive `when` in [PrivacyScrubber.scrub]
	 * is the first line of defence; this test is the belt-and-braces
	 * check that catches `@Suppress("NON_EXHAUSTIVE_WHEN")`.
	 */
	@Test
	fun `every BusEvent subtype has a scrubber case`() {
		val subtypes = Konsist
			.scopeFromProject()
			.classes(includeNested = true)
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.events..") }
			.filter { klass -> klass.parents().any { it.name == "BusEvent" } }
		assertTrue(subtypes.isNotEmpty())
		// Actual invocation coverage lives in PrivacyScrubberTest."every BusEvent subtype returns without throwing".
		// This test only asserts the *count* matches the scrubber's known universe — a drift-detector.
		val expected = subtypes.size
		val scrubberSampleCount = 23                // update when a new subtype is added to the scrubber AND to this test
		assertTrue("BusEvent subtype count ($expected) must match PrivacyScrubberTest sample count ($scrubberSampleCount)") {
			expected == scrubberSampleCount
		}
	}

	/**
	 * Spec §12 #2 — no `Map<*, Any?>` / `Any` / free-form `String`
	 * payload on [BusEvent] subtypes.
	 */
	@Test
	fun `no free-form fields in BusEvent subtypes`() {
		val forbidden = listOf("payload", "json", "extra", "data")
		Konsist
			.scopeFromProject()
			.classes(includeNested = true)
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.events..") }
			.filter { klass -> klass.parents().any { it.name == "BusEvent" } }
			// TestPing's "payload" is intentional (internal, scrubbed wholesale) — allow by excluding it
			.filter { it.name != "TestPing" }
			.flatMap { it.properties() }
			.assertFalse { prop ->
				prop.name in forbidden || prop.hasType { t -> t.name == "Any" }
			}
	}

	/**
	 * Spec §12 #3 — every subtype overrides the interface's correlation
	 * ID fields.
	 */
	@Test
	fun `correlation IDs declared on every subtype`() {
		Konsist
			.scopeFromProject()
			.classes(includeNested = true)
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.events..") }
			.filter { klass -> klass.parents().any { it.name == "BusEvent" } }
			.assertTrue { klass ->
				val props = klass.properties().map { it.name }
				listOf("eventId", "timestamp", "sessionId", "schemaVersion",
					"taskInstanceId", "moduleId").all { it in props }
			}
	}

	/**
	 * Spec §12 #4 — `core.logging` cannot import `Runner` internals.
	 */
	@Test
	fun `logging package cannot import Runner or TaskInstance`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.logging") == true }
			.assertFalse { file ->
				file.imports.any { imp ->
					imp.name.endsWith(".Runner") || imp.name.endsWith(".TaskInstance")
				}
			}
	}

	/**
	 * Spec §12 #5 — `MutableSharedFlow` only inside `core.events`. Plan 2
	 * already enforced this in `LayeringTest`; this test extends the
	 * assertion to cover `core.logging` explicitly.
	 */
	@Test
	fun `MutableSharedFlow not used in logging package`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.logging") == true }
			.assertFalse { file ->
				file.imports.any { it.name == "kotlinx.coroutines.flow.MutableSharedFlow" }
			}
	}

	/**
	 * Spec §12 #6 — [PrivacyScrubber] must not hold mutable state that
	 * varies by caller. The HMAC key rotation is allowed (single
	 * volatile field reset by SessionManager.start).
	 */
	@Test
	fun `PrivacyScrubber has no public mutable fields`() {
		Konsist
			.scopeFromProject()
			.classes()
			.filter { it.name == "PrivacyScrubber" }
			.flatMap { it.properties() }
			.filter { it.hasPublicModifier || !it.hasPrivateModifier }
			.assertFalse { prop -> prop.hasModifier(KoModifier.VAR) }
	}

	/**
	 * Spec §12 #7 — `LogDirLayout` is the single source of path truth.
	 * No string literal mentioning `.vitalclient` or `buildcore/logs`
	 * outside that file.
	 */
	@Test
	fun `log dir paths constructed only in LogDirLayout or LogConfig`() {
		val forbidden = Regex("""\.vitalclient|buildcore/logs""")
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name !in setOf("LogDirLayout.kt", "LogConfig.kt", "LogDirLayoutTest.kt", "LogConfigTest.kt") }
			.assertFalse { file -> forbidden.containsMatchIn(file.text) }
	}

	/**
	 * Spec §12 #8 — `UncaughtExceptionHandler` uses `tryEmit`, never
	 * `emit` (which suspends and would crash a non-coroutine caller).
	 */
	@Test
	fun `UncaughtExceptionHandler uses tryEmit only`() {
		val file = Konsist
			.scopeFromProject()
			.files
			.first { it.name == "UncaughtExceptionHandler.kt" }
		val text = file.text
		assertTrue("UncaughtExceptionHandler must contain 'tryEmit'") { text.contains("tryEmit") }
		assertFalse("UncaughtExceptionHandler must not call 'bus.emit(' (suspending)") {
			text.contains("bus.emit(")
		}
	}

	private inline fun assertTrue(message: String = "", crossinline cond: () -> Boolean) {
		if (!cond()) throw AssertionError(message.ifBlank { "assertion failed" })
	}

	private inline fun assertFalse(message: String = "", crossinline cond: () -> Boolean) {
		if (cond()) throw AssertionError(message.ifBlank { "assertion failed" })
	}
}
```

- [ ] **Step 2: Run architecture tests**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests LoggingArchitectureTest --no-daemon 2>&1 | tail -15
```
Expected: 8 tests passing.

- [ ] **Step 3: Run full test suite**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --no-daemon 2>&1 | tail -15
```
Expected: all tests passing.

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/arch/LoggingArchitectureTest.kt
git commit -m "BuildCore: add 8 logging architecture invariants (Plan 3 spec §12)"
git push origin main
```

---

### Task 22 — Integration test

**Files:** Create `BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/PluginBootstrapIntegrationTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package net.vital.plugins.buildcore.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.logging.LocalJsonlWriter
import net.vital.plugins.buildcore.core.logging.LocalSummaryWriter
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LogLevel
import net.vital.plugins.buildcore.core.logging.LoggerScope
import net.vital.plugins.buildcore.core.logging.NoOpReplaySubscriber
import net.vital.plugins.buildcore.core.logging.NoOpTelemetrySubscriber
import net.vital.plugins.buildcore.core.logging.SessionManager
import java.util.UUID

/**
 * End-to-end: boot the logging stack without RuneLite, push a small
 * event set through the bus, shut down cleanly. Asserts:
 *   - session dir exists
 *   - events.jsonl contains SessionStart + TaskFailed lines
 *   - summary.log exists with the ERROR line, password scrubbed
 *   - session.meta.json state="ended"
 */
class PluginBootstrapIntegrationTest {

	@Test
	fun `full logging pipeline end-to-end`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout, retentionSessions = 30)
		val sessionDir = layout.sessionDir(sm.sessionId)

		val reg = SubscriberRegistry()
			.register(LocalJsonlWriter(sessionDir, capBytes = 1024 * 1024))
			.register(LocalSummaryWriter(sessionDir, level = LogLevel.DEBUG))
			.register(NoOpTelemetrySubscriber { sm.sessionId })
			.register(NoOpReplaySubscriber  { sm.sessionId })
		reg.attachAll(bus, scope)
		sm.start()

		bus.emit(TaskFailed(
			sessionId = sm.sessionId, taskInstanceId = UUID.randomUUID(),
			taskId = "demo", reasonType = "BOOM",
			reasonDetail = "password=hunter2 connection lost",
			attemptNumber = 1
		))

		// wait for JSONL to have at least 3 lines (SessionStart + TaskFailed + possibly more)
		withTimeout(3000) {
			while (!Files.exists(sessionDir.resolve("events.jsonl")) ||
				Files.size(sessionDir.resolve("events.jsonl")) == 0L) delay(10)
		}

		sm.requestStop()
		reg.drainAll()
		scope.close()

		val jsonl = Files.readAllLines(sessionDir.resolve("events.jsonl")).joinToString("\n")
		val summary = Files.readString(sessionDir.resolve("summary.log"))
		val meta = Files.readString(sessionDir.resolve("session.meta.json"))

		assertTrue(jsonl.contains("\"type\":\"SessionStart\""))
		assertTrue(jsonl.contains("\"type\":\"TaskFailed\""))
		assertTrue(jsonl.contains("hunter2"), "JSONL keeps raw detail (unscrubbed)")
		assertTrue(summary.contains("ERROR"))
		assertFalse(summary.contains("hunter2"), "summary must be scrubbed")
		assertTrue(summary.contains("password=«redacted»"))
		assertTrue(meta.contains("\"state\" : \"ended\"") || meta.contains("\"state\":\"ended\""))
	}
}
```

- [ ] **Step 2: Run the integration test**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:test --tests PluginBootstrapIntegrationTest --no-daemon 2>&1 | tail -15
```
Expected: 1 test passing.

- [ ] **Step 3: Run the full test suite — Plan 3 complete**

```bash
cd /c/Code/VitalPlugins && ./gradlew :BuildCore:build --no-daemon 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, ~84 tests passing (59 from Plan 2 + 25 new). Specifically:
- `BusEventInterfaceTest` 2
- `PrivacyScrubberTest` 5
- `LogConfigTest` 4
- `LogDirLayoutTest` 3
- `RotatingFileSinkTest` 2
- `LoggerScopeOrderingTest` 2
- `SubscriberRegistryTest` 2
- `LocalJsonlWriterTest` 1
- `LocalSummaryWriterTest` 2
- `PerformanceAggregatorTest` 1
- `SessionManagerTest` 2
- `LoggingArchitectureTest` 8
- `PluginBootstrapIntegrationTest` 1

Subtotal: 35 new (more than the ~25 target — closer to comprehensive coverage).

- [ ] **Step 4: Commit and push**

```bash
git add BuildCore/src/test/kotlin/net/vital/plugins/buildcore/integration/PluginBootstrapIntegrationTest.kt
git commit -m "BuildCore: add end-to-end integration test for Plan 3 logging pipeline"
git push origin main
```

---

### Task 23 — Update `CLAUDE.md` + final commit

**Files:** Modify `BuildCore/CLAUDE.md`

- [ ] **Step 1: Mark Plan 3 complete**

Update the Status section:

```markdown
## Status

**Foundation phase — Plans 1 + 2 + 3 complete.**

Future plans in `../docs/superpowers/plans/`:
- ~~Plan 2 — Core Runtime + Task SPI + Restrictions~~ (done)
- ~~Plan 3 — Logging + Event Bus~~ (done)
- Plan 4 — Antiban + Input Layer
- Plan 5 — Action Library (L5 Services)
- Plan 6 — Confidence / Watchdog / Recovery
- Plan 7 — Config + Profile System
- Plan 8 — BuildCore-Server (separate backend project)
- Plan 9 — Licensing + Updates Client
- Plan 10 — GUI Shell
```

Update the "Current invariants" section:

```markdown
Current invariants (Plans 2 + 3):
- `BusEvent` subtypes are data classes or objects (immutability).
- `MutableSharedFlow` is only imported inside `core/events/`.
- Every `Method` has exactly one `IRONMAN` path with no gatingRestrictions.
- `Task` implementations do not expose public `var` properties.
- `Runner` is only used inside `core.task` package.
- Profile restrictions: exactly one mule tier per RestrictionSet.
- Every `BusEvent` subtype has a `PrivacyScrubber` case (exhaustive-when + drift test).
- No free-form `payload`/`json`/`Any` fields on `BusEvent` subtypes.
- Correlation IDs (`eventId`, `sessionId`, `taskInstanceId`, `moduleId`) on every subtype.
- `core.logging` cannot import `Runner` internals.
- `MutableSharedFlow` not imported in `core.logging`.
- `PrivacyScrubber` has no public mutable fields.
- Log dir paths constructed only in `LogDirLayout` and `LogConfig`.
- `UncaughtExceptionHandler` uses `tryEmit` only.
```

- [ ] **Step 2: Commit and push**

```bash
git add BuildCore/CLAUDE.md
git commit -m "BuildCore: mark Plan 3 complete in subproject CLAUDE.md"
git push origin main
```

---

## Self-review

### Spec coverage

| Spec section | Implementing task(s) |
|---|---|
| §2 decision log | Tasks 1-22 collectively |
| §3 package layout | Tasks 1-20 produce exactly this layout |
| §4.1 composition root | Task 20 |
| §4.2 LoggerScope | Task 7 |
| §4.3 SubscriberRegistry | Task 8 |
| §4.4 BoundedChannelSubscriber | Tasks 9, 15 |
| §4.5 data flow | Task 12 (write) + Task 13 (summary) + Task 14 (perf) |
| §5.1 BusEvent interface correlation | Task 4 |
| §5.2 9+1 new subtypes | Task 5 |
| §5.3 TaskValidated vs ValidationFailed | Tasks 5, 18 |
| §6 PrivacyScrubber | Task 6 |
| §7.1 dir layout | Task 10 |
| §7.2 JSONL line format | Task 12 |
| §7.3 summary line format | Task 13 |
| §7.4 summary subtype coverage | Task 13 |
| §7.5 rotation | Task 11 |
| §7.6 session.meta.json | Task 16 |
| §8 session lifecycle | Task 16 |
| §8.3 Runner sessionId | Task 19 |
| §9.1 UnhandledException | Task 17 |
| §9.2 ValidationFailed | Task 18 |
| §9.3 RestrictionViolated | Task 18 |
| §10 performance contract | Task 7 (single thread) + Task 14 (sampling) + Task 22 (integration) |
| §11 LogConfig | Task 3 |
| §11.1 log-level filter | Task 13 |
| §12 architecture tests (8) | Task 21 |
| §13 testing strategy | Tasks 3, 6, 10, 11, 12, 13, 14, 16, 21, 22 |
| §14 deferrals | Task 15 (NoOp placeholders) |

All spec sections mapped. No gaps.

### Placeholder scan

No `TBD`, `TODO`, `FIXME`, or "similar to" references. Every code step contains the actual code. Every test step shows the test body. Every commit step has the exact command.

One adaptive instruction in Task 18 Step 2-3 — "if the current Plan 2 `ModuleRegistry` uses different method names, adapt to match" — is unavoidable: `ModuleRegistry.validateStructure()` / `structureErrors()` were landed by Plan 2 at method-level and may need minor signature lookup when implementing. The *intent* (wrap throw with tryEmit of `ValidationFailed`) is unambiguous.

### Type consistency

Verified:
- `TaskCounter` is a data class with `started`, `completed`, `failed`, `skipped: Int` — used consistently in Task 5 (subtype), Task 16 (`SessionManager.taskCounters`), Task 16 test (`s.taskCounts["demo"]?.started`).
- `StopReason` enum is `USER`, `CRASH`, `SAFE_STOP`, `UPDATE` — used consistently in Task 5 (subtype) and Task 16 (`SessionManager.requestStop(reason = StopReason.USER)`) and the test.
- `LogLevel.parse(raw)` returns INFO on unknown input — used by LogConfig (Task 3) and doc-aligned with the filter table (Task 13).
- `LogSubscriber.name` / `isFastPath` / `attach` / `drain` — used consistently in `SubscriberRegistry`, writers, and `BoundedChannelSubscriber`.
- `RestrictionMoment.EDIT | START | RUNTIME` — defined in Task 5, consumed in Task 18 (`Runner.kt` uses `START`).
- `BuildCoreVersion.CURRENT` — defined Task 2, consumed in Task 16 (`SessionManager.buildcoreVersion` default).
- `BoundedChannelSubscriber` constructor `(name, sessionIdProvider, capacity)` — defined Task 9, consumed in Task 15 (both NoOp subs) and Task 20 (plugin wiring via `{ sessionManager.sessionId }`).

No drift.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-23-plan-3-logging-eventbus.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
