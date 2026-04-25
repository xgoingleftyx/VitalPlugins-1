package net.vital.plugins.buildcore.core.events

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

/**
 * Pre-transmit / pre-human-log scrubber. Redacts credentials and hashes
 * usernames in free-form text fields.
 *
 * Called only by [net.vital.plugins.buildcore.core.logging.LocalSummaryWriter]
 * and (future) TelemetrySubscriber. The raw JSONL on local disk
 * receives unscrubbed events — local disk is private app data; the
 * summary log and network transmits cross a sharing boundary.
 *
 * The `when` expression over the sealed [BusEvent] hierarchy is
 * exhaustive — adding a new subtype breaks the build until a case is
 * added here. This is the "compile-time check" in spec §13.
 *
 * Spec §6.
 */
object PrivacyScrubber {

	/** Matches the 12-char lowercase hex strings we emit from [hmacHex]. */
	private val ALREADY_HASHED = Regex("""^[0-9a-f]{12}$""")

	/**
	 * Per-process HMAC key. Each run produces a fresh key, so a hashed
	 * username cannot be correlated across process restarts. When
	 * SessionManager lands (Plan 3 Task 17) the key rotates with the
	 * session — same property, tighter scope.
	 */
	@Volatile
	private var hmacKey: ByteArray = freshKey()

	/**
	 * Rotate the HMAC key. Called by SessionManager at session start so
	 * that usernames scrubbed in one session cannot be correlated to
	 * those in another.
	 */
	fun rotateKey(seed: UUID) {
		hmacKey = seed.toString().toByteArray(Charsets.UTF_8)
	}

	fun scrub(event: BusEvent): BusEvent = when (event) {
		is SessionStart         -> event
		is SessionEnd           -> event
		is SessionSummary       -> event
		is TaskQueued           -> event
		is TaskValidated        -> event.copy(rejectReason = event.rejectReason?.let(::scrubString))
		is TaskStarted          -> event
		is TaskProgress         -> event
		is TaskCompleted        -> event
		is TaskFailed           -> event.copy(reasonDetail = scrubString(event.reasonDetail))
		is TaskRetrying         -> event
		is TaskSkipped          -> event.copy(reason = scrubString(event.reason))
		is TaskPaused           -> event.copy(reason = scrubString(event.reason))
		is TaskResumed          -> event
		is MethodPicked         -> event
		is PathPicked           -> event
		is SafeStopRequested    -> event.copy(reason = scrubString(event.reason))
		is SafeStopCompleted    -> event
		is UnhandledException   -> event.copy(
			message    = scrubString(event.message),
			stackTrace = scrubStackTrace(event.stackTrace)
		)
		is ValidationFailed     -> event.copy(detail = scrubString(event.detail))
		is RestrictionViolated  -> event
		is PerformanceSample    -> event
		is SubscriberOverflowed -> event
		is TestPing             -> event.copy(payload = "«scrubbed»")
		is InputAction         -> event
		is FatigueUpdated      -> event
		is PersonalityResolved -> event
		is SessionRngSeeded    -> event
		is PrecisionModeEntered -> event
		is PrecisionModeExited  -> event
		is BreakScheduled       -> event
		is BreakStarted         -> event
		is BreakEnded           -> event
		is BreakDeferred        -> event
		is BreakDropped         -> event
		is BreakRescheduled     -> event
		is BreakPreempted       -> event
		is EarlyStopRequested   -> event
		is Misclick             -> event
		is SemanticMisclick     -> event
	}

	private fun scrubString(s: String): String = s
		.replace(Regex("""(?i)(password|token|apikey|bearer)\s*[=:]\s*\S+""")) {
			"${it.groupValues[1]}=«redacted»"
		}
		.replace(Regex("""(?i)username\s*[=:]\s*(\S+)""")) { match ->
			val value = match.groupValues[1]
			// Skip re-hashing if already a 12-char hex hash from a prior scrub pass
			if (ALREADY_HASHED.matches(value)) match.value
			else "username=${hmacHex(value)}"
		}

	private fun scrubStackTrace(trace: String): String =
		trace.lines().joinToString("\n") { scrubString(it) }

	private fun hmacHex(value: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
		val raw = mac.doFinal(value.toByteArray(Charsets.UTF_8))
		// 12 hex chars = 6 bytes — enough uniqueness for debug, too short for crack-back
		return raw.take(6).joinToString("") { "%02x".format(it) }
	}

	private fun freshKey(): ByteArray {
		val rnd = java.security.SecureRandom()
		val key = ByteArray(32)
		rnd.nextBytes(key)
		return key
	}
}
