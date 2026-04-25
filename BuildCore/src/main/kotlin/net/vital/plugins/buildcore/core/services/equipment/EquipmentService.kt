package net.vital.plugins.buildcore.core.services.equipment

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object EquipmentService
{
	@Volatile internal var backend: EquipmentBackend = VitalApiEquipmentBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun equip(itemId: Int): Boolean = withServiceCall(bus, sessionIdProvider, "EquipmentService", "equip",
		stakes = ActionStakes.MEDIUM) { backend.equip(itemId) }

	suspend fun unequip(slot: Int): Boolean = withServiceCall(bus, sessionIdProvider, "EquipmentService", "unequip",
		stakes = ActionStakes.MEDIUM) { backend.unequip(slot) }

	internal fun resetForTests()
	{
		backend = VitalApiEquipmentBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
