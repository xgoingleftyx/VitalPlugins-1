package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalSummaryWriterTest {

	@Test
	fun `filters events below configured level`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalSummaryWriter(sessionDir = tmp, level = LogLevel.ERROR)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "a", methodId = "m", pathId = "p"))
		bus.emit(TaskFailed(sessionId = sid, taskInstanceId = tid, taskId = "a", reasonType = "E", reasonDetail = "boom", attemptNumber = 1))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("summary.log")) ||
				Files.size(tmp.resolve("summary.log")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		val text = Files.readString(tmp.resolve("summary.log"))
		assertFalse(text.contains("TaskStarted"), "INFO line leaked at ERROR level")
		assertTrue(text.contains("ERROR"), "expected ERROR line present")
	}

	@Test
	fun `local log keeps full fidelity (password not scrubbed — scrubbing is export-time only)`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalSummaryWriter(sessionDir = tmp, level = LogLevel.ERROR)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "a",
			reasonType = "EX", reasonDetail = "password=hunter2 wrong", attemptNumber = 1
		))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("summary.log")) ||
				Files.size(tmp.resolve("summary.log")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		// Plan 4b §7.3: local log is full-fidelity; full scrubbing happens at export time via ExportBundle.
		// hashAccountIdOnly only hashes username fields — raw behavioral data (incl. passwords in detail
		// strings) is preserved locally for debugging.
		val text = Files.readString(tmp.resolve("summary.log"))
		assertTrue(text.contains("hunter2"), "local log must contain raw detail (full-fidelity, not scrubbed)")
	}
}
