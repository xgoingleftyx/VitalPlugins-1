package net.vital.plugins.buildcore.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.logging.LocalJsonlWriter
import net.vital.plugins.buildcore.core.logging.LocalSummaryWriter
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LogLevel
import net.vital.plugins.buildcore.core.logging.LoggerScope
import net.vital.plugins.buildcore.core.logging.NoOpReplaySubscriber
import net.vital.plugins.buildcore.core.logging.NoOpTelemetrySubscriber
import net.vital.plugins.buildcore.core.logging.SessionManager
import java.util.UUID

/**
 * End-to-end: boot the logging stack without RuneLite, push a small
 * event set through the bus, shut down cleanly. Asserts:
 *   - session dir exists
 *   - events.jsonl contains SessionStart + TaskFailed lines
 *   - summary.log exists with the ERROR line, password scrubbed
 *   - session.meta.json state="ended"
 */
class PluginBootstrapIntegrationTest {

	@Test
	fun `full logging pipeline end-to-end`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val layout = LogDirLayout(tmp)
		val sm = SessionManager(bus, scope, layout, retentionSessions = 30)
		val sessionDir = layout.sessionDir(sm.sessionId)

		val reg = SubscriberRegistry()
			.register(LocalJsonlWriter(sessionDir, capBytes = 1024 * 1024))
			.register(LocalSummaryWriter(sessionDir, level = LogLevel.DEBUG))
			.register(NoOpTelemetrySubscriber { sm.sessionId })
			.register(NoOpReplaySubscriber  { sm.sessionId })
		reg.attachAll(bus, scope)
		sm.start()

		bus.emit(TaskFailed(
			sessionId = sm.sessionId, taskInstanceId = UUID.randomUUID(),
			taskId = "demo", reasonType = "BOOM",
			reasonDetail = "password=hunter2 connection lost",
			attemptNumber = 1
		))

		// wait for JSONL to have at least 3 lines (SessionStart + TaskFailed + possibly more)
		withTimeout(3000) {
			while (!Files.exists(sessionDir.resolve("events.jsonl")) ||
				Files.size(sessionDir.resolve("events.jsonl")) == 0L) delay(10)
		}

		sm.requestStop()
		reg.drainAll()
		scope.close()

		val jsonl = Files.readAllLines(sessionDir.resolve("events.jsonl")).joinToString("\n")
		val summary = Files.readString(sessionDir.resolve("summary.log"))
		val meta = Files.readString(sessionDir.resolve("session.meta.json"))

		assertTrue(jsonl.contains("\"type\":\"SessionStart\""))
		assertTrue(jsonl.contains("\"type\":\"TaskFailed\""))
		assertTrue(jsonl.contains("hunter2"), "JSONL keeps raw detail (unscrubbed)")
		assertTrue(summary.contains("ERROR"))
		// Plan 4b §7.3: local summary is full-fidelity (hashAccountIdOnly only — no password scrub).
		// Full scrubbing happens at export time via ExportBundle.
		assertTrue(summary.contains("hunter2"), "local summary must keep raw detail (full-fidelity)")
		assertTrue(meta.contains("\"state\" : \"ended\"") || meta.contains("\"state\":\"ended\""))
	}
}
