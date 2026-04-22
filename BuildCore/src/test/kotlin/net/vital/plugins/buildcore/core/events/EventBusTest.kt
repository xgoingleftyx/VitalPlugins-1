package net.vital.plugins.buildcore.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

	@Test
	fun `emitted events are received by subscribers`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()
		val event = TestPing(sessionId = sessionId, payload = "hello")

		val received = mutableListOf<BusEvent>()
		val subscription = launch {
			bus.events.take(1).toList(received)
		}

		bus.emit(event)
		subscription.join()

		assertEquals(1, received.size)
		assertEquals("hello", (received[0] as TestPing).payload)
	}

	@Test
	fun `bus has replay buffer zero — late subscribers do not see past events`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()

		bus.emit(TestPing(sessionId = sessionId, payload = "early"))

		val second = TestPing(sessionId = sessionId, payload = "late")
		val received = mutableListOf<BusEvent>()
		val subscription = launch {
			bus.events.take(1).toList(received)
		}
		bus.emit(second)
		subscription.join()

		assertEquals(1, received.size)
		assertEquals("late", (received[0] as TestPing).payload)
	}

	@Test
	fun `multiple subscribers all receive the same event`() = runTest(UnconfinedTestDispatcher()) {
		val bus = EventBus()
		val sessionId = UUID.randomUUID()
		val event = TestPing(sessionId = sessionId, payload = "fanout")

		val a = mutableListOf<BusEvent>()
		val b = mutableListOf<BusEvent>()

		val subA = launch { bus.events.take(1).toList(a) }
		val subB = launch { bus.events.take(1).toList(b) }

		bus.emit(event)
		subA.join()
		subB.join()

		assertEquals(1, a.size)
		assertEquals(1, b.size)
		assertTrue(a[0] === b[0] || a[0] == b[0])
	}
}
