package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrecisionGateLeakTest
{
	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
	}

	@Test
	fun `scopeEnteredAtMs is null before any entry`()
	{
		assertNull(PrecisionGate.scopeEnteredAtMs)
	}

	@Test
	fun `outermost entry sets scopeEnteredAtMs`()
	{
		val before = System.currentTimeMillis()
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val ts = PrecisionGate.scopeEnteredAtMs
		assertNotNull(ts)
		assertTrue(ts!! >= before)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `nested entry does not change scopeEnteredAtMs`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val outer = PrecisionGate.scopeEnteredAtMs
		Thread.sleep(5L)
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		assertEquals(outer, PrecisionGate.scopeEnteredAtMs)
		PrecisionGate.markExitScope()
		assertEquals(outer, PrecisionGate.scopeEnteredAtMs)   // outer still active
		PrecisionGate.markExitScope()
		assertNull(PrecisionGate.scopeEnteredAtMs)
	}
}
