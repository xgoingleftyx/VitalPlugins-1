package net.vital.plugins.buildcore.core.services.world

/**
 * Default [WorldBackend] delegating to VitalAPI.
 * Plan 5a spec §5.
 */
object VitalApiWorldBackend : WorldBackend
{
	override suspend fun hop(targetWorld: Int): Boolean =
		error("not implemented in 5a")
}
