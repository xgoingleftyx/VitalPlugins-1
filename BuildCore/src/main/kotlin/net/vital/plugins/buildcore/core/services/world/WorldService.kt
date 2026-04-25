package net.vital.plugins.buildcore.core.services.world

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object WorldService
{
	@Volatile internal var backend: WorldBackend = VitalApiWorldBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun hop(targetWorld: Int): Boolean = withServiceCall(bus, sessionIdProvider, "WorldService", "hop",
		restriction = OperationalRestriction.WORLD_HOP_DISABLED) { backend.hop(targetWorld) }

	internal fun resetForTests()
	{
		backend = VitalApiWorldBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
