package net.vital.plugins.buildcore.core.services.interact

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object InteractService
{
	@Volatile internal var backend: InteractBackend = VitalApiInteractBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun tileObject(obj: vital.api.entities.TileObject, action: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "InteractService", "tileObject",
			stakes = ActionStakes.LOW) { backend.tileObject(obj, action) }

	suspend fun npc(npc: vital.api.entities.Npc, action: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "InteractService", "npc",
			stakes = ActionStakes.LOW) { backend.npc(npc, action) }

	suspend fun tileItem(item: vital.api.entities.TileItem, action: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "InteractService", "tileItem",
			stakes = ActionStakes.LOW) { backend.tileItem(item, action) }

	internal fun resetForTests()
	{
		backend = VitalApiInteractBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
