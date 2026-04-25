package net.vital.plugins.buildcore.core.services.ge

import vital.api.ui.GrandExchange as VitalGE

/**
 * Default [GrandExchangeBackend] delegating to [vital.api.ui.GrandExchange].
 * Plan 5a spec §5.
 */
object VitalApiGrandExchangeBackend : GrandExchangeBackend
{
	override suspend fun open(): Boolean
	{
		VitalGE.open()
		return VitalGE.isOpen()
	}

	override suspend fun close(): Boolean
	{
		VitalGE.close()
		return !VitalGE.isOpen()
	}

	override suspend fun submitBuy(itemId: Int, qty: Int, pricePerEach: Int): Boolean =
		error("not implemented in 5a; wire GE buy offer submission when consumer needs it")

	override suspend fun submitSell(slot: Int, pricePerEach: Int): Boolean =
		error("not implemented in 5a; wire GE sell offer submission when consumer needs it")

	override suspend fun collectAll(): Boolean = VitalGE.collectAll()
}
