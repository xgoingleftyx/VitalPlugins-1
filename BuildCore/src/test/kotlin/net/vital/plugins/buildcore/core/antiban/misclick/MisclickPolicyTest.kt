package net.vital.plugins.buildcore.core.antiban.misclick

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityVector
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MisclickPolicyTest
{
	private lateinit var personality: PersonalityVector
	private lateinit var fatigue: FatigueCurve
	private val emitted = mutableListOf<BusEvent>()
	private val bus = mockk<EventBus>().also {
		every { it.tryEmit(any()) } answers { emitted.add(firstArg()); true }
	}

	@BeforeEach
	fun reset()
	{
		emitted.clear()
		PrecisionGate.resetForTests()
		MisclickPolicy.resetForTests()
		personality = PersonalityVector(
			mouseSpeedCenter = 1.0, mouseCurveGravity = 9.0, mouseCurveWind = 5.0,
			overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
			hotkeyPreference = 0.6, foodEatDelayCenterMs = 600,
			cameraFidgetRatePerMin = 1.5, bankWithdrawalPrecision = 0.95,
			breakBias = net.vital.plugins.buildcore.core.antiban.personality.BreakBias.DAY_REGULAR,
			misclickRate = 0.015,   // max
			menuTopSelectionRate = 0.95, idleExamineRatePerMin = 1.0, tabSwapRatePerMin = 0.8
		)
		fatigue = mockk()
		every { fatigue.misclickMultiplier() } returns 5.0   // amplify so we see misclicks at finite seed
	}

	@Test
	fun `decide returns false in PRECISION mode`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val rng = JavaUtilRng(seed = 1L)
		repeat(100) {
			assertFalse(MisclickPolicy.decide(personality, fatigue, rng, mode = InputMode.PRECISION))
		}
		PrecisionGate.markExitScope()
	}

	@Test
	fun `decide produces misclicks at expected rate over many samples`()
	{
		val rng = JavaUtilRng(seed = 42L)
		val total = 10_000
		// Use a fixed nowMs that won't trigger burst cooldown across iterations
		var nowMs = 0L
		val hits = (1..total).count {
			nowMs += MisclickPolicy.BURST_COOLDOWN_MS + 1
			MisclickPolicy.decide(personality, fatigue, rng, mode = InputMode.NORMAL, nowMs = nowMs)
		}
		// expected p = misclickRate (0.015) * fatigue 5.0 = 0.075
		// observed should land in 0.05..0.10 with seeded RNG
		val observed = hits.toDouble() / total
		assertTrue(observed in 0.05..0.10) { "observed misclick rate $observed outside expected band" }
	}

	@Test
	fun `intercept emits Misclick event with corrected=false for PIXEL_JITTER`()
	{
		val sid = UUID.randomUUID()
		MisclickPolicy.bus = bus
		MisclickPolicy.sessionIdProvider = { sid }
		MisclickPolicy.recordPixelJitter(intendedX = 100, intendedY = 200, dx = 2, dy = -1)
		val ev = emitted.single() as Misclick
		assertEquals(MisclickKind.PIXEL_JITTER, ev.kind)
		assertEquals(100, ev.intendedX)
		assertEquals(102, ev.actualX)
		assertEquals(199, ev.actualY)
		assertEquals(false, ev.corrected)
	}
}
