package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.StopReason
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class SessionManagerTest {

	@Test
	fun `start emits SessionStart and creates session dir`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout, retentionSessions = 30)

		val starts = CopyOnWriteArrayList<SessionStart>()
		// Use a latch to ensure the subscriber is registered before sm.start() tryEmits.
		val latch = CountDownLatch(1)
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<SessionStart>()
				.collect { starts += it }
		}
		latch.await()

		sm.start()

		withTimeout(1000) { while (starts.isEmpty()) { yield() } }
		assertEquals(sm.sessionId, starts.first().sessionId)
		assertTrue(Files.isDirectory(layout.sessionDir(sm.sessionId)))
		scope.close()
	}

	@Test
	fun `requestStop emits SessionSummary then SessionEnd`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout)

		// Use latches so both async collectors are subscribed before any events flow.
		val summaryLatch = CountDownLatch(1)
		val endLatch     = CountDownLatch(1)

		val summaryRef = AtomicReference<SessionSummary>()
		val endRef     = AtomicReference<SessionEnd>()

		// Launch on scope.coroutineScope (buildcore-logger thread) rather than runBlocking's
		// dispatcher — otherwise summaryLatch.await() below blocks the only thread that
		// could run these collectors, causing a deadlock before they ever subscribe.
		val summaryJob = scope.coroutineScope.launch {
			bus.events
				.onStart { summaryLatch.countDown() }
				.filterIsInstance<SessionSummary>()
				.first()
				.also { summaryRef.set(it) }
		}
		val endJob = scope.coroutineScope.launch {
			bus.events
				.onStart { endLatch.countDown() }
				.filterIsInstance<SessionEnd>()
				.first()
				.also { endRef.set(it) }
		}

		// Wait for both collectors to be registered with the shared flow.
		summaryLatch.await()
		endLatch.await()

		sm.start()
		// Stream a task event so counters are non-zero
		val sid = sm.sessionId
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = UUID.randomUUID(),
			taskId = "demo", methodId = "m", pathId = "p"))
		sm.requestStop(StopReason.USER)

		withTimeout(2000) { summaryJob.join() }
		withTimeout(2000) { endJob.join() }

		val s = summaryRef.get()!!
		val e = endRef.get()!!
		assertEquals(1, s.taskCounts["demo"]?.started)
		assertEquals(StopReason.USER, e.reason)
		scope.close()
	}
}
