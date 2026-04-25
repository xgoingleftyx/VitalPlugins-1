package net.vital.plugins.buildcore.core.confidence.watchdog.checks

import net.vital.plugins.buildcore.core.events.WatchdogKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeadlockCheckTest
{
	@Test
	fun `fires when heartbeat exceeds tolerance`()
	{
		var hb: Long? = 0L
		val check = DeadlockCheck({ hb })
		assertNull(check.tick(10_000L))
		val finding = check.tick(20_000L)
		assertNotNull(finding)
		assertEquals(WatchdogKind.DEADLOCK, finding!!.kind)
	}

	@Test
	fun `re-arms after heartbeat returns`()
	{
		var hb: Long? = 0L
		val check = DeadlockCheck({ hb })
		check.tick(20_000L)
		hb = 25_000L
		assertNull(check.tick(26_000L))
		hb = 25_000L
		assertNull(check.tick(35_000L))     // 35-25=10s, within toleranceMs=15s
	}

	@Test
	fun `null heartbeat returns null`()
	{
		val check = DeadlockCheck({ null })
		assertNull(check.tick(System.currentTimeMillis()))
	}
}
