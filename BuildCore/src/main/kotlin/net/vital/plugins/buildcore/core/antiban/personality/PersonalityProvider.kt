package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.PersonalityRng
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy-by-username personality resolver with ephemeral fallback for pre-login.
 *
 * - [forUsername] loads from [PersonalityStore] or generates and persists; cached
 *   in-process after the first call. Emits a [PersonalityResolved] event.
 * - [ephemeral] returns a transient per-session personality drawn from the given
 *   [SeededRng] (typically [net.vital.plugins.buildcore.core.antiban.rng.SessionRng]).
 *   Not persisted; not emitted as an event.
 * - [currentPersonality] is the primitive-side read path: returns the most-recently
 *   resolved account personality if any, else the ephemeral. Plan 5's Login service
 *   will drive [forUsername] calls to populate the cache.
 *
 * Spec §6.4.
 */
class PersonalityProvider(
	private val store: PersonalityStore,
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID
)
{

	private val cache = ConcurrentHashMap<String, PersonalityVector>()

	@Volatile
	private var ephemeralCache: PersonalityVector? = null

	fun forUsername(username: String): PersonalityVector
	{
		val key = hashKey(username)
		return cache.computeIfAbsent(key)
		{
			val loaded = store.load(key)
			if (loaded != null)
			{
				bus.tryEmit(PersonalityResolved(
					sessionId = sessionIdProvider(),
					usernameHash = key,
					generated = false
				))
				loaded
			}
			else
			{
				val generated = PersonalityGenerator.generate(PersonalityRng.forUsername(username))
				store.save(key, generated)
				bus.tryEmit(PersonalityResolved(
					sessionId = sessionIdProvider(),
					usernameHash = key,
					generated = true
				))
				generated
			}
		}
	}

	fun ephemeral(sessionRng: SeededRng): PersonalityVector
	{
		val existing = ephemeralCache
		if (existing != null) return existing
		val generated = PersonalityGenerator.generate(sessionRng)
		ephemeralCache = generated
		return generated
	}

	/**
	 * Primitive-side read path. Returns the username-resolved personality if exactly
	 * one has been cached; else falls back to ephemeral. Only deterministic when at
	 * most one username has been resolved — Plan 5's Login service owns the
	 * one-active-personality-at-a-time invariant.
	 */
	fun currentPersonality(sessionRng: SeededRng): PersonalityVector =
		cache.values.firstOrNull() ?: ephemeral(sessionRng)

	private fun hashKey(username: String): String
	{
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(username.lowercase().toByteArray(Charsets.UTF_8))
		return digest.take(6).joinToString("") { "%02x".format(it) }
	}
}
