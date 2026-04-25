package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.Misclick
import net.vital.plugins.buildcore.core.events.MisclickKind
import net.vital.plugins.buildcore.core.events.SemanticMisclick
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExportBundleTest
{
	/** Serialise [event] in the same format [LocalJsonlWriter] uses: fields + a leading "type" key. */
	private fun writeWithType(mapper: ObjectMapper, event: BusEvent): String
	{
		val node = mapper.valueToTree<ObjectNode>(event)
		val out = mapper.createObjectNode()
		out.put("type", event::class.simpleName)
		node.fieldNames().forEach { field -> out.set<ObjectNode>(field, node.get(field)) }
		return mapper.writeValueAsString(out)
	}

	@Test
	fun `export reads raw log and writes scrubbed jsonl`(@TempDir root: Path)
	{
		val sid = UUID.randomUUID()
		val layout = LogDirLayout(root.resolve("logs"))
		val sessionDir = layout.createSessionDir(sid)
		val rawLog = sessionDir.resolve("session.log.jsonl")

		val mapper = ObjectMapper()
			.registerKotlinModule()
			.registerModule(JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		val raw = listOf<BusEvent>(
			Misclick(sessionId = sid, kind = MisclickKind.PIXEL_JITTER,
				intendedX = 100, intendedY = 200, actualX = 102, actualY = 199, corrected = false),
			SemanticMisclick(sessionId = sid, context = "useItemOn", intended = "feather", actual = "rod")
		)
		// Write in LocalJsonlWriter format (type field + event fields)
		Files.write(rawLog, raw.map { writeWithType(mapper, it) })

		val out = ExportBundle.create(layout, sid)

		assertTrue(Files.exists(out))
		val lines = Files.readAllLines(out)
		assertEquals(2, lines.size)

		val mc = mapper.readValue<Misclick>(lines[0])
		assertEquals(96, mc.intendedX)   // 100 → grid cell floor → 96
		assertEquals(192, mc.intendedY)  // 200 → 192
		assertEquals(96, mc.actualX)     // 102 → 96
		assertEquals(192, mc.actualY)    // 199 → 192
	}
}
