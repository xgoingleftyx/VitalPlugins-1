package net.vital.plugins.buildcore.core.services.equipment

interface EquipmentBackend
{
	suspend fun equip(itemId: Int): Boolean
	suspend fun unequip(slot: Int): Boolean
}
