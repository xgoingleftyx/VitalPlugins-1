package net.vital.plugins.buildcore.core.antiban.input

import kotlinx.coroutines.delay
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.antiban.timing.ReactionDelay
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.events.InputMode
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
