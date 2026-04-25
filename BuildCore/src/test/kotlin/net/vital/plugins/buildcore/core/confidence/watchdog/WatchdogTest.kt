package net.vital.plugins.buildcore.core.confidence.watchdog

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.WatchdogKind
import net.vital.plugins.buildcore.core.events.WatchdogTriggered
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogTest
{
	@Test
	fun `loop emits WatchdogTriggered when a check returns finding`() = runTest {
		val captured = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
		val firingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? =
				WatchdogFinding(WatchdogKind.STALL, "test")
		}
		val w = Watchdog(bus, { UUID.randomUUID() }, listOf(firingCheck), tickIntervalMs = 100L)
		val job = launch { w.run() }
		advanceTimeBy(150L)
		job.cancelAndJoin()
		assertTrue(captured.any { it is WatchdogTriggered && it.kind == WatchdogKind.STALL })
	}

	@Test
	fun `check exception does not kill the loop`() = runTest {
		val captured = mutableListOf<BusEvent>()
		val bus = mockk<EventBus>().also { every { it.tryEmit(any()) } answers { captured.add(firstArg()); true } }
		val throwingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? = error("boom")
		}
		val firingCheck = object : WatchdogCheck
		{
			override fun tick(nowMs: Long): WatchdogFinding? = WatchdogFinding(WatchdogKind.LEAK, "test")
		}
		val w = Watchdog(bus, { UUID.randomUUID() }, listOf(throwingCheck, firingCheck), tickIntervalMs = 100L)
		val job = launch { w.run() }
		advanceTimeBy(150L)
		job.cancelAndJoin()
		assertTrue(captured.any { it is WatchdogTriggered && it.kind == WatchdogKind.LEAK })
	}
}
