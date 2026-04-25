package net.vital.plugins.buildcore.core.services.walker

import vital.api.entities.Tile
import vital.api.input.Movement

/**
 * Default [WalkerBackend] delegating to [vital.api.input.Movement].
 * walkExact shares the same underlying call as walkTo in v1 — clip-to-near-tile
 * distinction deferred to Plan 5b when the walker plugin API is wired.
 * stop() is a no-op — no VitalAPI surface exists in v1.
 * Plan 5a spec §5.
 */
object VitalApiWalkerBackend : WalkerBackend
{
	override suspend fun walkTo(tile: Tile): Boolean
	{
		Movement.walkTo(tile.sceneX, tile.sceneY)
		return true
	}

	override suspend fun walkExact(tile: Tile): Boolean
	{
		Movement.walkTo(tile.sceneX, tile.sceneY)
		return true
	}

	override suspend fun stop()
	{
		// no-op: no VitalAPI stop surface in v1
	}
}
