package net.vital.plugins.buildcore.core.antiban.rng

import java.security.MessageDigest

/**
 * Username-seeded factory. `SHA-256(lowercase(username))` → first 8 bytes
 * interpreted as big-endian Long → seeds [JavaUtilRng]. Same username
 * always produces the same sequence, which means the same PersonalityVector.
 *
 * Spec §5.3.
 */
object PersonalityRng {

	fun forUsername(username: String): SeededRng {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(username.lowercase().toByteArray(Charsets.UTF_8))
		var seed = 0L
		for (i in 0 until 8) {
			seed = (seed shl 8) or (digest[i].toLong() and 0xFF)
		}
		return JavaUtilRng(seed)
	}
}
