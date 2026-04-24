package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.runBlocking
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SubscriberRegistryTest {

	private class CountingSub(override val name: String) : LogSubscriber {
		override val isFastPath: Boolean = true
		val attached = AtomicInteger(0)
		val drained = AtomicInteger(0)
		override fun attach(bus: EventBus, loggerScope: LoggerScope) { attached.incrementAndGet() }
		override suspend fun drain() { drained.incrementAndGet() }
	}

	@Test
	fun `attachAll calls attach in registration order`() = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val a = CountingSub("a"); val b = CountingSub("b")
		val reg = SubscriberRegistry().register(a).register(b)
		reg.attachAll(bus, scope)
		assertEquals(1, a.attached.get())
		assertEquals(1, b.attached.get())
		scope.close()
	}

	@Test
	fun `drainAll calls drain on every subscriber`() = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val a = CountingSub("a"); val b = CountingSub("b")
		val reg = SubscriberRegistry().register(a).register(b)
		reg.attachAll(bus, scope)
		reg.drainAll()
		assertEquals(1, a.drained.get())
		assertEquals(1, b.drained.get())
		scope.close()
	}
}
