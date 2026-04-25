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
import net.vital.plugins.buildcore.core.events.InputMode
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
object Mouse
{
	@Volatile internal var backend: MouseBackend = VitalApiMouseBackend
	@Volatile internal var personalityProvider: PersonalityProvider? = null
	@Volatile internal var sessionRng: SessionRng? = null
	@Volatile internal var fatigue: FatigueCurve? = null
	@Volatile internal var throttle: GraduatedThrottle? = null
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun moveTo(target: Point, mode: InputMode = InputMode.NORMAL)
	{
		val profile = net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.enter(mode)
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
		for ((p, stepMs) in path)
		{
			backend.appendTrailPoint(p.x, p.y)
			if (stepMs > 0)
			{
				delay(stepMs.toLong())
				totalMs += stepMs
			}
		}

		if (profile.overshootEnabled && rng.nextBoolean(personality.overshootTendency))
		{
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

	suspend fun click(button: MouseButton = MouseButton.LEFT, mode: InputMode = InputMode.NORMAL)
	{
		net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.enter(mode)
		val provider = personalityProvider ?: error("antiban not bootstrapped")
		val rng = sessionRng ?: error("antiban not bootstrapped")
		val fatigueCurve = fatigue ?: error("antiban not bootstrapped")
		val personality = provider.currentPersonality(rng)

		delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))

		val pos = backend.currentPosition()
		val intendedX = pos.x
		val intendedY = pos.y

		if (net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.decide(personality, fatigueCurve, rng, mode))
		{
			val kind = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.pickKind(rng)
			when (kind)
			{
				net.vital.plugins.buildcore.core.events.MisclickKind.PIXEL_JITTER ->
				{
					val (dx, dy) = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.samplePixelJitterOffset(rng)
					net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.recordPixelJitter(intendedX, intendedY, dx, dy)
					backend.click(intendedX + dx, intendedY + dy, button)
				}
				net.vital.plugins.buildcore.core.events.MisclickKind.NEAR_MISS ->
				{
					val (dx, dy) = net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.sampleNearMissOffset(rng)
					net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy.recordNearMiss(intendedX, intendedY, dx, dy)
					backend.click(intendedX + dx, intendedY + dy, button)
					delay(ReactionDelay.sample(personality, fatigueCurve, throttle, rng, mode))
					backend.click(intendedX, intendedY, button)   // corrective click
				}
			}
		}
		else
		{
			backend.click(intendedX, intendedY, button)
		}

		bus?.tryEmit(InputAction(
			sessionId = sessionIdProvider(),
			kind = InputKind.MOUSE_CLICK,
			targetX = intendedX, targetY = intendedY,
			durationMillis = 0L,
			mode = mode
		))
	}

	suspend fun moveAndClick(target: Point, button: MouseButton = MouseButton.LEFT,
	                         mode: InputMode = InputMode.NORMAL)
	{
		moveTo(target, mode)
		click(button, mode)
	}
}
