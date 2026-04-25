package net.vital.plugins.buildcore.core.services.bank

import vital.api.containers.Bank as VitalBank

/**
 * Default [BankBackend] delegating to [vital.api.containers.Bank].
 * Plan 5a spec §5.
 */
object VitalApiBankBackend : BankBackend
{
	override suspend fun open(): Boolean
	{
		VitalBank.open()
		return VitalBank.isValid()
	}

	override suspend fun close(): Boolean
	{
		VitalBank.close()
		return !VitalBank.isValid()
	}

	override suspend fun deposit(itemId: Int, amount: Int): Boolean
	{
		val first = VitalBank.getFirstById(itemId) ?: return false
		val slot = first[0]
		return VitalBank.depositItem(slot, itemId, amount)
	}

	override suspend fun depositAll(): Boolean = VitalBank.depositAll()

	override suspend fun depositInventory(): Boolean =
		error("not implemented in 5a; use depositAll instead")

	override suspend fun withdraw(itemId: Int, amount: Int): Boolean
	{
		val first = VitalBank.getFirstById(itemId) ?: return false
		val slot = first[0]
		return VitalBank.withdraw(slot, itemId, amount)
	}
}
