package net.vital.plugins.buildcore.core.services.walker

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object WalkerService
{
	@Volatile internal var backend: WalkerBackend = VitalApiWalkerBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun walkTo(tile: vital.api.entities.Tile): Boolean = withServiceCall(bus, sessionIdProvider, "WalkerService", "walkTo",
		stakes = ActionStakes.LOW) { backend.walkTo(tile) }

	suspend fun walkExact(tile: vital.api.entities.Tile): Boolean = withServiceCall(bus, sessionIdProvider, "WalkerService", "walkExact",
		stakes = ActionStakes.LOW) { backend.walkExact(tile) }

	suspend fun stop(): Unit = withServiceCall(bus, sessionIdProvider, "WalkerService", "stop",
		stakes = ActionStakes.LOW) { backend.stop() }

	internal fun resetForTests()
	{
		backend = VitalApiWalkerBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
