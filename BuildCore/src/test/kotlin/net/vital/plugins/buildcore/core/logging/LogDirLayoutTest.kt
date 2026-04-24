package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.stream.Collectors

class LogDirLayoutTest
{
	@Test
	fun `createSessionDir creates nested directory`(@TempDir tmp: Path)
	{
		val layout = LogDirLayout(root = tmp)
		val sid = UUID.randomUUID()
		val dir = layout.createSessionDir(sid)
		assertTrue(Files.isDirectory(dir))
		assertEquals(sid.toString(), dir.fileName.toString())
	}

	@Test
	fun `pruneOldSessions keeps only N newest`(@TempDir tmp: Path)
	{
		val layout = LogDirLayout(root = tmp)
		val ids = (1..5).map { UUID.randomUUID() }
		ids.forEach { layout.createSessionDir(it); Thread.sleep(20) }
		layout.pruneOldSessions(keep = 3)
		val remaining = Files.list(tmp).use { it.collect(Collectors.toList()) }.map { it.fileName.toString() }
		assertEquals(3, remaining.size)
		// Oldest two (ids 0 and 1) should be pruned
		assertFalse(remaining.contains(ids[0].toString()))
		assertFalse(remaining.contains(ids[1].toString()))
	}

	@Test
	fun `pruneOldSessions no-op when below keep count`(@TempDir tmp: Path)
	{
		val layout = LogDirLayout(root = tmp)
		(1..2).map { UUID.randomUUID() }.forEach { layout.createSessionDir(it) }
		layout.pruneOldSessions(keep = 5)
		assertEquals(2, Files.list(tmp).use { it.collect(Collectors.toList()) }.size)
	}
}
