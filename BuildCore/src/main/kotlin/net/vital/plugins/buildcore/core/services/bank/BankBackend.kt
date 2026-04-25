package net.vital.plugins.buildcore.core.services.bank

interface BankBackend
{
	suspend fun open(): Boolean
	suspend fun close(): Boolean
	suspend fun deposit(itemId: Int, amount: Int): Boolean
	suspend fun depositAll(): Boolean
	suspend fun depositInventory(): Boolean
	suspend fun withdraw(itemId: Int, amount: Int): Boolean
}
