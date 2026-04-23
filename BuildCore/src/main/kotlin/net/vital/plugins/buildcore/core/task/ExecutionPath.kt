package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Restriction

/**
 * A single economic variant of a [Method].
 *
 * Invariant: every [Method] MUST have exactly one path with
 * [kind] == [PathKind.IRONMAN] and empty [gatingRestrictions].
 * This is enforced by Plan 2's architecture tests and Plan 2's
 * Method constructor-time checks.
 *
 * Spec §4, §7.
 */
data class ExecutionPath(
	val id: PathId,
	val kind: PathKind,
	val effects: Set<Effect> = emptySet(),
	val requirements: Requirement? = null,
	val estimatedRate: XpPerHour = XpPerHour.ZERO,
	val gatingRestrictions: Set<Restriction> = emptySet()
)

@JvmInline
value class PathId(val raw: String) {
	init { require(raw.isNotBlank()) { "PathId must not be blank" } }
}

enum class PathKind {
	/** Self-gather; no economy shortcuts. ALWAYS required, never gated. */
	IRONMAN,
	/** Uses GE to buy inputs. Gated by Restriction.NoGrandExchange. */
	GE,
	/** Receives items/gp from mule. Gated by MuleBondsOnly / NoMuleInteraction. */
	MULE,
	/** Combination — e.g., buy coal from GE, self-mine tin. */
	HYBRID
}

/** Estimated training rate; used by Path/Method selectors to pick fastest allowed. */
data class XpPerHour(val value: Long) {
	init { require(value >= 0) { "XpPerHour must be non-negative" } }
	companion object {
		val ZERO: XpPerHour = XpPerHour(0)
	}
	operator fun compareTo(other: XpPerHour): Int = value.compareTo(other.value)
}
