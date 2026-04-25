package net.vital.plugins.buildcore.core.services

/**
 * First-line operational veto for service action calls. Wired by
 * [ServiceBootstrap]. `engine == null` means pass-through (used in unit tests
 * that don't care about restrictions).
 *
 * Plan 5a spec §4.3.
 */
object RestrictionGate
{
	@Volatile var engine: RestrictionEngine? = null

	fun check(restriction: OperationalRestriction)
	{
		engine?.check(restriction)
	}
}
