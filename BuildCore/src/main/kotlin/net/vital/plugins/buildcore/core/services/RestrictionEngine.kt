package net.vital.plugins.buildcore.core.services

/**
 * Runtime engine consulted by [RestrictionGate]. Plan 7 will swap in a profile-aware
 * implementation; v1 ships [StaticRestrictionEngine].
 *
 * Plan 5a spec §4.3.
 */
interface RestrictionEngine
{
	/** Throws [RestrictionViolation] if [restriction] is denied. */
	fun check(restriction: OperationalRestriction)
}

class StaticRestrictionEngine(private val denied: Set<OperationalRestriction>) : RestrictionEngine
{
	override fun check(restriction: OperationalRestriction)
	{
		if (restriction in denied) throw RestrictionViolation(restriction)
	}
}

class RestrictionViolation(val restriction: OperationalRestriction) :
	RuntimeException("operational restriction denied: $restriction")
