package net.vital.plugins.buildcore.core.services.inventory

interface InventoryBackend
{
	suspend fun drop(itemId: Int): Boolean
	suspend fun useOn(srcSlot: Int, destSlot: Int): Boolean
	suspend fun interact(slot: Int, action: String): Boolean
}
