package net.vital.plugins.buildcore.core.services.inventory

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object InventoryService
{
	@Volatile internal var backend: InventoryBackend = VitalApiInventoryBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun drop(itemId: Int): Boolean = withServiceCall(bus, sessionIdProvider, "InventoryService", "drop",
		stakes = ActionStakes.MEDIUM) { backend.drop(itemId) }

	suspend fun useOn(srcSlot: Int, destSlot: Int): Boolean = withServiceCall(bus, sessionIdProvider, "InventoryService", "useOn",
		stakes = ActionStakes.MEDIUM) { backend.useOn(srcSlot, destSlot) }

	suspend fun interact(slot: Int, action: String): Boolean = withServiceCall(bus, sessionIdProvider, "InventoryService", "interact",
		stakes = ActionStakes.MEDIUM) { backend.interact(slot, action) }

	internal fun resetForTests()
	{
		backend = VitalApiInventoryBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
