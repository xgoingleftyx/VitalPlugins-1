package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class LoggerScopeOrderingTest {

	@Test
	fun `writes on the dedicated buildcore-logger thread`() = runBlocking {
		val scope = LoggerScope()
		val threadNames = CopyOnWriteArrayList<String>()
		val flow = MutableSharedFlow<Int>(extraBufferCapacity = 128)

		scope.coroutineScope.launch {
			flow.asSharedFlow().collect { threadNames += Thread.currentThread().name }
		}

		// Wait until the collector has subscribed before emitting, otherwise
		// MutableSharedFlow drops emissions that arrive before any subscriber.
		withTimeout(2000) { while (flow.subscriptionCount.value == 0) { yield() } }
		repeat(10) { flow.emit(it) }
		withTimeout(2000) { while (threadNames.size < 10) { yield() } }
		scope.close()

		assertEquals(10, threadNames.size)
		assert(threadNames.all { it.startsWith("buildcore-logger") }) {
			"expected all writes on buildcore-logger thread, got $threadNames"
		}
	}

	@Test
	fun `drain completes within deadline`() = runBlocking {
		val scope = LoggerScope()
		scope.coroutineScope.launch { /* short-lived */ }
		scope.drain(deadlineMillis = 500)   // should not throw
		scope.close()
	}
}
