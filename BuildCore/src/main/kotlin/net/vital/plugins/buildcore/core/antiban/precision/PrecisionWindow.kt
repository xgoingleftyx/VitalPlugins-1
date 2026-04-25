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
	@PublishedApi @Volatile internal var bus: EventBus? = null
	@PublishedApi @Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	@PublishedApi internal val nextScopeId = AtomicLong(1L)

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
