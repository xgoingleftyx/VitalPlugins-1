package net.vital.plugins.buildcore.core.logging

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.stream.Collectors

/**
 * Single source of truth for log directory paths.
 *
 * Architecture test #7 forbids string literals like `"logs"` or
 * `.vitalclient` anywhere else in the codebase — path construction
 * happens only here.
 *
 * Spec §7.1, §12.7.
 */
class LogDirLayout(val root: Path)
{
	fun sessionDir(sessionId: UUID): Path = root.resolve(sessionId.toString())

	fun createSessionDir(sessionId: UUID): Path
	{
		val dir = sessionDir(sessionId)
		Files.createDirectories(dir)
		return dir
	}

	/**
	 * Retain the [keep] most recently modified session directories;
	 * delete the rest (recursively). Safe to call with no directory
	 * existing — it only touches what's already there.
	 */
	fun pruneOldSessions(keep: Int)
	{
		if (!Files.isDirectory(root)) return
		val entries = Files.list(root).use { stream ->
			stream.filter { Files.isDirectory(it) }
				.sorted(compareBy { Files.getLastModifiedTime(it).toMillis() })
				.collect(Collectors.toList())
		}
		val toDelete = entries.size - keep
		if (toDelete <= 0) return
		entries.take(toDelete).forEach { deleteRecursively(it) }
	}

	private fun deleteRecursively(path: Path)
	{
		if (Files.isDirectory(path))
		{
			Files.list(path).use { stream -> stream.forEach(::deleteRecursively) }
		}
		Files.deleteIfExists(path)
	}
}
