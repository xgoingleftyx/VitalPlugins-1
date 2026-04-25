package net.vital.plugins.buildcore.core.antiban.misclick

import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.SemanticMisclick
import java.util.UUID

/**
 * Service-layer opt-in API. Plan 5+ services call [rollMisclick] to ask whether
 * to substitute a wrong target this call; if they do, they call
 * [emitSemanticMisclick] with the actual outcome. Suppressed in non-NORMAL.
 *
 * Plan 4b spec §6.2.
 */
object SemanticMisclickHook
{
	@Volatile var bus: EventBus? = null
	@Volatile var sessionIdProvider: () -> UUID = { UUID(0, 0) }
	@Volatile var personalityProvider: () -> PersonalityVector? = { null }
	@Volatile var fatigueProvider: () -> FatigueCurve? = { null }
	@Volatile var rngProvider: () -> SeededRng? = { null }

	internal const val SEMANTIC_BASE_PROB = 0.4   // tuned: combined with personality.misclickRate × fatigue, lands ~0.005

	fun rollMisclick(context: String, mode: InputMode = InputMode.NORMAL): Boolean
	{
		if (mode != InputMode.NORMAL) return false
		val pers = personalityProvider() ?: return false
		val fat  = fatigueProvider() ?: return false
		val rng  = rngProvider() ?: return false
		val p = SEMANTIC_BASE_PROB * pers.misclickRate * fat.misclickMultiplier()
		return rng.nextDouble() < p
	}

	fun emitSemanticMisclick(context: String, intended: String, actual: String)
	{
		bus?.tryEmit(SemanticMisclick(
			sessionId = sessionIdProvider(),
			context = context,
			intended = intended,
			actual = actual
		))
	}
}
