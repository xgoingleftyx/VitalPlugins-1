package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeakCheckTest
{
	@BeforeEach
	fun reset() { PrecisionGate.resetForTests() }

	@Test
	fun `fires when scope held longer than maxScopeMs`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val entered = PrecisionGate.scopeEnteredAtMs!!
		val check = LeakCheck()
		assertNull(check.tick(entered + 10_000L))
		val finding = check.tick(entered + 31_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.LEAK, finding!!.kind)
		PrecisionGate.markExitScope()
	}

	@Test
	fun `no scope returns null`()
	{
		val check = LeakCheck()
		assertNull(check.tick(System.currentTimeMillis()))
	}

	@Test
	fun `fires once per scope episode`()
	{
		PrecisionGate.markEnterScope(InputMode.PRECISION)
		val entered = PrecisionGate.scopeEnteredAtMs!!
		val check = LeakCheck()
		assertNotNull(check.tick(entered + 31_000L))
		assertNull(check.tick(entered + 32_000L))
		PrecisionGate.markExitScope()
	}
}
