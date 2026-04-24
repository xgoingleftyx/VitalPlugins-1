package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.Konsist
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
				klass.hasModifier(KoModifier.DATA) || klass.hasModifier(KoModifier.VALUE)
			}
	}

	/**
	 * Spec §16: no class outside `core.events` may write directly to the
	 * bus's MutableSharedFlow — only via [EventBus.emit] / [EventBus.tryEmit].
	 *
	 * For now we enforce the broader rule: MutableSharedFlow is only used
	 * inside `core.events` in production sources. Tightens further in Plan 3.
	 *
	 * Test files are excluded — test utilities may import MutableSharedFlow
	 * to exercise the bus in isolation without violating the production invariant.
	 */
	@Test
	fun `MutableSharedFlow is only used in core-events package`() {
		Konsist
			.scopeFromProduction()
			.files
			.filter { file ->
				file.imports.any { imp -> imp.name == "kotlinx.coroutines.flow.MutableSharedFlow" }
			}
			.assertTrue { file ->
				file.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.events") == true
			}
	}
}
