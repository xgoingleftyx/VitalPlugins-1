package net.vital.plugins.buildcore.core.services.inventory

/**
 * Default [InventoryBackend] delegating to [vital.api.containers.Inventory].
 * Plan 5a spec §5. dropAll/useOn/interact have no clean static delegate in VitalAPI v1.
 */
object VitalApiInventoryBackend : InventoryBackend
{
	override suspend fun drop(itemId: Int): Boolean =
		error("not implemented in 5a; wire when consumer needs it")

	override suspend fun useOn(srcSlot: Int, destSlot: Int): Boolean =
		error("not implemented in 5a; wire when consumer needs it")

	override suspend fun interact(slot: Int, action: String): Boolean =
		error("not implemented in 5a; wire when consumer needs it")
}
