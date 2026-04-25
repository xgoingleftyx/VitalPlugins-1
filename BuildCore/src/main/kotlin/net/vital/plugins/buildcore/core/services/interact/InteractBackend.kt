package net.vital.plugins.buildcore.core.services.interact

import vital.api.entities.Npc
import vital.api.entities.TileItem
import vital.api.entities.TileObject

interface InteractBackend
{
	suspend fun tileObject(obj: TileObject, action: String): Boolean
	suspend fun npc(npc: Npc, action: String): Boolean
	suspend fun tileItem(item: TileItem, action: String): Boolean
}
