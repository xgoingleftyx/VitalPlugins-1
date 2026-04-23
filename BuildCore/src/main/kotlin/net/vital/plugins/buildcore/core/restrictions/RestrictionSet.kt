package net.vital.plugins.buildcore.core.restrictions

/**
 * The effective restriction set for a profile at a point in time.
 *
 * Composition rule (spec §8): profiles can only ADD restrictions to
 * their archetype's base set. The exception is the mule tier, which
 * is exactly one of three values — a profile may override that one
 * slot, but cannot remove it entirely.
 *
 * A [RestrictionSet] is immutable. Re-compose to change.
 */
data class RestrictionSet(val restrictions: Set<Restriction>) {

	init {
		val muleTiers = restrictions.filter { it.isMuleTier() }
		require(muleTiers.size == 1) {
			"RestrictionSet must contain exactly one mule tier, got $muleTiers"
		}
	}

	operator fun contains(r: Restriction): Boolean = r in restrictions

	companion object {
		/**
		 * Compose a RestrictionSet from an [archetype], an [additional]
		 * set of extra restrictions, and an optional [muleOverride].
		 *
		 * Rules:
		 *  - archetype.baseRestrictions is included in full
		 *  - additional is added (cannot override archetype)
		 *  - if muleOverride is non-null it REPLACES the archetype's
		 *    mule tier; it must itself be a mule-tier Restriction
		 */
		fun compose(
			archetype: Archetype,
			additional: Set<Restriction>,
			muleOverride: Restriction?
		): RestrictionSet {
			if (muleOverride != null) {
				require(muleOverride.isMuleTier()) {
					"muleOverride must be a mule tier restriction (NoMuleInteraction / MuleBondsOnly / MuleFull), got $muleOverride"
				}
			}
			val base = if (muleOverride != null) {
				archetype.baseRestrictions.filterNot { it.isMuleTier() }.toSet() + muleOverride
			} else {
				archetype.baseRestrictions
			}
			val combined = base + additional
			// additional MUST NOT override the mule tier; if it includes one, reject
			require(additional.none { it.isMuleTier() }) {
				"additional restrictions must not include a mule tier; use muleOverride instead"
			}
			return RestrictionSet(combined)
		}
	}
}
