package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RotatingFileSinkTest
{
	@Test
	fun `rotates when current file exceeds capBytes`(@TempDir tmp: Path)
	{
		val target = tmp.resolve("events.jsonl")
		val sink = RotatingFileSink(target = target, capBytes = 100, maxRotations = 3)
		// write 150 bytes in two writes — second write triggers rotation
		sink.writeLine("a".repeat(80))    // 80 + 1 newline = 81 bytes, under cap
		sink.writeLine("b".repeat(80))    // 81 + 81 = 162 bytes total, triggers
		sink.close()
		assertTrue(Files.exists(target))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.1")))
		assertFalse(Files.exists(target.resolveSibling("events.jsonl.2")))
	}

	@Test
	fun `drops oldest rotation when maxRotations exceeded`(@TempDir tmp: Path)
	{
		val target = tmp.resolve("events.jsonl")
		val sink = RotatingFileSink(target = target, capBytes = 10, maxRotations = 2)
		repeat(5) { sink.writeLine("x".repeat(12)) }   // each write exceeds cap → rotation
		sink.close()
		assertTrue(Files.exists(target))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.1")))
		assertTrue(Files.exists(target.resolveSibling("events.jsonl.2")))
		assertFalse(Files.exists(target.resolveSibling("events.jsonl.3")))
	}
}
