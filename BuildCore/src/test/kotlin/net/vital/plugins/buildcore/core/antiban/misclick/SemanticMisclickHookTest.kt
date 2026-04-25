package net.vital.plugins.buildcore.core.antiban.misclick

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SemanticMisclickHookTest
{
	private val bus = mockk<EventBus>(relaxed = true)
	private val emitted = mutableListOf<BusEvent>()

	@BeforeEach
	fun reset()
	{
		emitted.clear()
		every { bus.tryEmit(any()) } answers { emitted.add(firstArg()); true }
		SemanticMisclickHook.bus = bus
		SemanticMisclickHook.sessionIdProvider = { UUID.randomUUID() }
		SemanticMisclickHook.personalityProvider = {
			PersonalityVector(
				mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
				overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
				hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
				cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
				breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
				misclickRate = 0.015, menuTopSelectionRate = 0.95,
				idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
			)
		}
		val fc = mockk<FatigueCurve>()
		every { fc.misclickMultiplier() } returns 5.0
		SemanticMisclickHook.fatigueProvider = { fc }
		SemanticMisclickHook.rngProvider = { JavaUtilRng(seed = 7L) }
	}

	@Test
	fun `rollMisclick returns false in PRECISION mode`()
	{
		repeat(50) {
			assertFalse(SemanticMisclickHook.rollMisclick("ctx", InputMode.PRECISION))
		}
	}

	@Test
	fun `emitSemanticMisclick produces a SemanticMisclick event`()
	{
		SemanticMisclickHook.emitSemanticMisclick("useItemOn", "feather", "fishing-rod")
		val ev = emitted.single() as SemanticMisclick
		assertEquals("useItemOn", ev.context)
		assertEquals("feather", ev.intended)
		assertEquals("fishing-rod", ev.actual)
	}
}
