package net.vital.plugins.buildcore.core.services.ge

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object GrandExchangeService
{
	@Volatile internal var backend: GrandExchangeBackend = VitalApiGrandExchangeBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun open(): Boolean = withServiceCall(bus, sessionIdProvider, "GrandExchangeService", "open",
		restriction = OperationalRestriction.GRAND_EXCHANGE_DISABLED) { backend.open() }

	suspend fun close(): Boolean = withServiceCall(bus, sessionIdProvider, "GrandExchangeService", "close",
		restriction = OperationalRestriction.GRAND_EXCHANGE_DISABLED) { backend.close() }

	suspend fun submitBuy(itemId: Int, qty: Int, pricePerEach: Int): Boolean = withServiceCall(bus, sessionIdProvider, "GrandExchangeService", "submitBuy",
		restriction = OperationalRestriction.GRAND_EXCHANGE_DISABLED) { backend.submitBuy(itemId, qty, pricePerEach) }

	suspend fun submitSell(slot: Int, pricePerEach: Int): Boolean = withServiceCall(bus, sessionIdProvider, "GrandExchangeService", "submitSell",
		restriction = OperationalRestriction.GRAND_EXCHANGE_DISABLED) { backend.submitSell(slot, pricePerEach) }

	suspend fun collectAll(): Boolean = withServiceCall(bus, sessionIdProvider, "GrandExchangeService", "collectAll",
		restriction = OperationalRestriction.GRAND_EXCHANGE_DISABLED) { backend.collectAll() }

	internal fun resetForTests()
	{
		backend = VitalApiGrandExchangeBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
