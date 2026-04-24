package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.TaskStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalJsonlWriterTest {

	@Test
	fun `writes one JSON object per event per line`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val writer = LocalJsonlWriter(sessionDir = tmp, capBytes = 10L * 1024 * 1024)
		writer.attach(bus, scope)

		val sid = UUID.randomUUID()
		val tid = UUID.randomUUID()
		bus.emit(SessionStart(sessionId = sid, buildcoreVersion = "0.1.0"))
		bus.emit(TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "x", methodId = "m", pathId = "p"))

		withTimeout(2000) {
			while (Files.notExists(tmp.resolve("events.jsonl")) ||
				Files.size(tmp.resolve("events.jsonl")) == 0L) delay(10)
		}
		writer.drain()
		scope.close()

		val lines = Files.readAllLines(tmp.resolve("events.jsonl"))
		// at least 2 — SessionStart + TaskStarted (order preserved)
		assertTrue(lines.size >= 2, "expected ≥2 lines, got ${lines.size}")
		val mapper = ObjectMapper().registerKotlinModule()
		val first = mapper.readTree(lines[0])
		val second = mapper.readTree(lines[1])
		assertEquals("SessionStart", first.get("type").asText())
		assertEquals("TaskStarted", second.get("type").asText())
		assertEquals("x", second.get("taskId").asText())
	}
}
