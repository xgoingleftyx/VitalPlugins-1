package net.vital.plugins.buildcore.core.antiban.personality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed PersonalityVector persistence.
 *
 * Writes are atomic: body written to `<name>.json.tmp`, then
 * [Files.move] with [StandardCopyOption.ATOMIC_MOVE] + [StandardCopyOption.REPLACE_EXISTING].
 * Read failures (missing, corrupt JSON, schema mismatch, range violation)
 * return null — the caller's policy is to regenerate.
 *
 * Spec §6.3.
 */
class PersonalityStore(private val root: Path)
{
	private val mapper = ObjectMapper().registerKotlinModule()

	fun save(key: String, vector: PersonalityVector)
	{
		Files.createDirectories(root)
		val finalPath = root.resolve("$key.json")
		val tempPath = root.resolve("$key.json.tmp")
		Files.writeString(tempPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(vector))
		try
		{
			Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		}
		catch (e: Exception)
		{
			// Some filesystems (e.g. tmpfs, WSL with Windows host FS) reject ATOMIC_MOVE. Fall back.
			Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	fun load(key: String): PersonalityVector?
	{
		val path = root.resolve("$key.json")
		if (!Files.exists(path)) return null
		return try
		{
			val body = Files.readString(path)
			mapper.readValue(body, PersonalityVector::class.java)
		}
		catch (e: Exception)
		{
			// Log-and-regenerate policy: corrupt files shouldn't brick the bot.
			// Caller (PersonalityProvider) treats null as "not present" and regenerates.
			null
		}
	}
}
