package net.vital.plugins.buildcore.core.logging

import java.io.BufferedWriter
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Size-capped rotating file sink. When a write would push the current
 * file over [capBytes], the file is closed, rotated to `.1` (pushing
 * `.1→.2`, `.2→.3`, etc.), and a fresh file is opened. Rotations
 * beyond [maxRotations] are deleted.
 *
 * Spec §7.5.
 */
class RotatingFileSink(
	private val target: Path,
	private val capBytes: Long,
	private val maxRotations: Int
) : Closeable
{
	private var writer: BufferedWriter = openAppending(target)
	private var currentSize: Long = Files.size(target.also { if (!Files.exists(it)) Files.createFile(it) })

	fun writeLine(line: String)
	{
		val bytes = line.toByteArray(StandardCharsets.UTF_8).size + 1 // +1 for newline
		if (currentSize + bytes > capBytes && currentSize > 0) rotate()
		writer.write(line)
		writer.newLine()
		writer.flush()
		currentSize += bytes
	}

	private fun rotate()
	{
		writer.close()
		for (i in maxRotations downTo 1)
		{
			val src = siblingWithSuffix(target, ".$i")
			val dst = siblingWithSuffix(target, ".${i + 1}")
			if (Files.exists(src))
			{
				if (i == maxRotations)
				{
					Files.deleteIfExists(src)
				}
				else
				{
					Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
				}
			}
		}
		Files.move(target, siblingWithSuffix(target, ".1"), StandardCopyOption.REPLACE_EXISTING)
		writer = openAppending(target)
		currentSize = 0
	}

	private fun siblingWithSuffix(p: Path, suffix: String): Path =
		p.resolveSibling(p.fileName.toString() + suffix)

	private fun openAppending(p: Path): BufferedWriter
	{
		Files.createDirectories(p.parent)
		return Files.newBufferedWriter(
			p, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND
		)
	}

	override fun close()
	{
		writer.close()
	}
}
