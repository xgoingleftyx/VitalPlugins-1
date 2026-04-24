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
