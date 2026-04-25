package net.vital.plugins.buildcore.core.services.interact

import vital.api.entities.Npc
import vital.api.entities.TileItem
import vital.api.entities.TileObject

/**
 * Default [InteractBackend] delegating to VitalAPI entity instance methods.
 * Plan 5a spec §5.
 */
object VitalApiInteractBackend : InteractBackend
{
	override suspend fun tileObject(obj: TileObject, action: String): Boolean =
		obj.interact(action)

	override suspend fun npc(npc: Npc, action: String): Boolean =
		npc.interact(action)

	override suspend fun tileItem(item: TileItem, action: String): Boolean =
		item.interact(action)
}
