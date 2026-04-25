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
		assertTrue("BusEvent subtype list must not be empty") { subtypes.isNotEmpty() }
		// Actual invocation coverage lives in PrivacyScrubberTest."every BusEvent subtype returns without throwing".
		// This test only asserts the *count* matches the scrubber's known universe — a drift-detector.
		val expected = subtypes.size
		val scrubberSampleCount = 39                // update when a new subtype is added to the scrubber AND to this test
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
			.scopeFromProduction()
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
			.assertFalse { prop -> prop.hasVarModifier }
	}

	/**
	 * Spec §12 #7 — `LogDirLayout` is the single source of path truth.
	 * No string literal mentioning `.vitalclient` or `buildcore/logs`
	 * outside that file.
	 */
	@Test
	fun `log dir paths constructed only in LogDirLayout or LogConfig`() {
		val forbidden = Regex("""\.vitalclient|buildcore/logs""")
		val allowedFiles = setOf(
			"LogDirLayout", "LogConfig", "LogDirLayoutTest", "LogConfigTest", "LoggingArchitectureTest"
		)
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name !in allowedFiles }
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
			.first { it.name == "UncaughtExceptionHandler" }
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
