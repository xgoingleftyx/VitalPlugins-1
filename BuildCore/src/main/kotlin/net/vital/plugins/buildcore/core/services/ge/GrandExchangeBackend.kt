package net.vital.plugins.buildcore.core.services.ge

interface GrandExchangeBackend
{
	suspend fun open(): Boolean
	suspend fun close(): Boolean
	suspend fun submitBuy(itemId: Int, qty: Int, pricePerEach: Int): Boolean
	suspend fun submitSell(slot: Int, pricePerEach: Int): Boolean
	suspend fun collectAll(): Boolean
}
