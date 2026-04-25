package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrecisionGateTest
{
	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
	}

	@Test
	fun `NORMAL passes through without scope`()
	{
		val profile = PrecisionGate.enter(InputMode.NORMAL)
		assertEquals(false, profile.tightTimingFloor)
		assertEquals(true,  profile.fidgetEnabled)
		assertEquals(true,  profile.overshootEnabled)
		assertEquals(true,  profile.fatigueApplied)
	}

	@Test
	fun `PRECISION without scope throws`()
	{
		assertThrows(IllegalStateException::class.java) {
			PrecisionGate.enter(InputMode.PRECISION)
		}
	}

	@Test
	fun `PRECISION with marker returns tight profile`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val profile = PrecisionGate.enter(InputMode.PRECISION)
		assertEquals(true,  profile.tightTimingFloor)
		assertEquals(false, profile.fidgetEnabled)
		assertEquals(false, profile.overshootEnabled)
		assertEquals(false, profile.fatigueApplied)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `SURVIVAL invokes preempt callback`()
	{
		var preempts = 0
		PrecisionGate.preemptHook = { preempts++ }
		PrecisionGate.markEnterScope(InputMode.SURVIVAL)
		PrecisionGate.enter(InputMode.SURVIVAL)
		PrecisionGate.markExitScope()
		assertEquals(1, preempts)
	}

	@Test
	fun `nested PRECISION marker survives nested enter`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		PrecisionGate.markEnterScope(InputMode.PRECISION)  // re-entrant
		PrecisionGate.enter(InputMode.PRECISION)           // must not throw
		PrecisionGate.markExitScope()
		PrecisionGate.markExitScope()
		assertFalse(PrecisionGate.inScope())
	}
}
