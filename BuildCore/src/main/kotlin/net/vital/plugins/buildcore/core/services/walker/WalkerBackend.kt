package net.vital.plugins.buildcore.core.services.walker

import vital.api.entities.Tile

interface WalkerBackend
{
	suspend fun walkTo(tile: Tile): Boolean
	suspend fun walkExact(tile: Tile): Boolean
	suspend fun stop()
}
