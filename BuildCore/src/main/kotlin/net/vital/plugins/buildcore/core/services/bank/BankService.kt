package net.vital.plugins.buildcore.core.services.bank

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object BankService
{
	@Volatile internal var backend: BankBackend = VitalApiBankBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun open(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "open",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.open() }

	suspend fun close(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "close",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.close() }

	suspend fun deposit(itemId: Int, amount: Int): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "deposit",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.deposit(itemId, amount) }

	suspend fun depositAll(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "depositAll",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.depositAll() }

	suspend fun depositInventory(): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "depositInventory",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.depositInventory() }

	suspend fun withdraw(itemId: Int, amount: Int): Boolean = withServiceCall(bus, sessionIdProvider, "BankService", "withdraw",
		restriction = OperationalRestriction.BANK_DISABLED) { backend.withdraw(itemId, amount) }

	internal fun resetForTests()
	{
		backend = VitalApiBankBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
