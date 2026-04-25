package net.vital.plugins.buildcore.core.services.combat

/**
 * Default [CombatBackend] delegating to VitalAPI.
 * Plan 5a spec §5.
 */
object VitalApiCombatBackend : CombatBackend
{
	override suspend fun attack(target: Targetable): Boolean =
		error("not implemented in 5a")

	override suspend fun setAutoRetaliate(enabled: Boolean): Boolean =
		error("not implemented in 5a; setter not directly available, only toggleAutoRetaliate")
}
