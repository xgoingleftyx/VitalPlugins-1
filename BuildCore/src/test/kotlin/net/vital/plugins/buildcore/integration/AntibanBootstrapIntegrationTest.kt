package net.vital.plugins.buildcore.integration

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
import net.vital.plugins.buildcore.core.antiban.input.FakeKeyboardBackend
import net.vital.plugins.buildcore.core.antiban.input.FakeMouseBackend
import net.vital.plugins.buildcore.core.antiban.input.Keyboard
import net.vital.plugins.buildcore.core.antiban.input.Mouse
import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LoggerScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class AntibanBootstrapIntegrationTest {

	@BeforeEach fun reset() { AntibanBootstrap.resetForTests() }
	@AfterEach fun tearDown() { AntibanBootstrap.resetForTests() }

	@Test
	fun `install emits SessionRngSeeded on the bus`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(tmp)

		val latch = CountDownLatch(1)
		val captured = CopyOnWriteArrayList<SessionRngSeeded>()
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<SessionRngSeeded>()
				.collect { captured += it }
		}
		latch.await()

		AntibanBootstrap.install(bus, { sid }, layout)

		withTimeout(2000) { while (captured.isEmpty()) yield() }
		assertTrue(captured.first().seed != 0L)
		scope.close()
	}

	@Test
	fun `Mouse moveAndClick with fake backend emits InputAction events`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(tmp)

		val actions = CopyOnWriteArrayList<InputAction>()
		val latch = CountDownLatch(1)
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<InputAction>()
				.collect { actions += it }
		}
		latch.await()

		AntibanBootstrap.install(bus, { sid }, layout)
		// Swap the real VitalAPI backend for the fake — tests can't call real VitalAPI
		val fakeMouse = FakeMouseBackend()
		Mouse.backend = fakeMouse

		Mouse.moveAndClick(Point(200, 200))

		withTimeout(10_000) { while (actions.size < 2) yield() }
		assertTrue(actions.any { it.kind == InputKind.MOUSE_MOVE })
		assertTrue(actions.any { it.kind == InputKind.MOUSE_CLICK })
		assertTrue(fakeMouse.trailPoints.isNotEmpty())
		assertTrue(fakeMouse.clicks.isNotEmpty())
		scope.close()
	}
}
