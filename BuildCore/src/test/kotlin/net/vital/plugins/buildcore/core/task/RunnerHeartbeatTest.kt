package net.vital.plugins.buildcore.core.task

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.RunnerHeartbeat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerHeartbeatTest
{
	@Test
	fun `lastHeartbeatMs is initialized at construction`()
	{
		val bus = mockk<EventBus>(relaxed = true)
		val r = Runner(bus, UUID.randomUUID())
		assertTrue(r.lastHeartbeatMs > 0L)
	}
}
