package net.vital.plugins.buildcore.core.services.equipment

/**
 * Default [EquipmentBackend]. equip/unequip require inventory.interact wiring
 * not available in VitalAPI v1 — both are stubbed pending Plan 5b.
 * Plan 5a spec §5.
 */
object VitalApiEquipmentBackend : EquipmentBackend
{
	override suspend fun equip(itemId: Int): Boolean =
		error("not implemented in 5a; needs inventory interact wiring")

	override suspend fun unequip(slot: Int): Boolean =
		error("not implemented in 5a; needs inventory interact wiring")
}
