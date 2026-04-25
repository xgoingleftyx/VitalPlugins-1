package net.vital.plugins.buildcore.core.antiban.precision

import io.mockk.every
import io.mockk.mockk
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.PrecisionModeEntered
import net.vital.plugins.buildcore.core.events.PrecisionModeExited
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PrecisionWindowTest
{
	private val bus = mockk<EventBus>(relaxed = true)
	private val sid = UUID.randomUUID()

	@BeforeEach
	fun reset()
	{
		PrecisionGate.resetForTests()
		PrecisionWindow.bus = bus
		PrecisionWindow.sessionIdProvider = { sid }
	}

	@Test
	fun `withPrecision sets and clears the marker`()
	{
		assertFalse(PrecisionGate.inScope())
		val out = PrecisionWindow.withPrecision {
			assertTrue(PrecisionGate.inScope())
			42
		}
		assertEquals(42, out)
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `withPrecision clears the marker on exception`()
	{
		assertThrows(IllegalStateException::class.java) {
			PrecisionWindow.withPrecision { error("boom") }
		}
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `nested withPrecision is re-entrant`()
	{
		PrecisionWindow.withPrecision {
			PrecisionWindow.withPrecision {
				assertTrue(PrecisionGate.inScope())
			}
			assertTrue(PrecisionGate.inScope())  // outer still active
		}
		assertFalse(PrecisionGate.inScope())
	}

	@Test
	fun `withPrecision emits Entered then Exited`()
	{
		val captured = mutableListOf<BusEvent>()
		every { bus.tryEmit(any()) } answers { captured.add(firstArg()); true }
		PrecisionWindow.withPrecision { /* noop */ }
		assertEquals(2, captured.size)
		assertTrue(captured[0] is PrecisionModeEntered)
		assertTrue(captured[1] is PrecisionModeExited)
		val entered = captured[0] as PrecisionModeEntered
		val exited  = captured[1] as PrecisionModeExited
		assertEquals(entered.scopeId, exited.scopeId)
		assertEquals(InputMode.PRECISION, entered.mode)
	}

	@Test
	fun `withSurvival uses SURVIVAL mode`()
	{
		val captured = mutableListOf<BusEvent>()
		every { bus.tryEmit(any()) } answers { captured.add(firstArg()); true }
		PrecisionWindow.withSurvival { /* noop */ }
		assertEquals(InputMode.SURVIVAL, (captured[0] as PrecisionModeEntered).mode)
	}
}
