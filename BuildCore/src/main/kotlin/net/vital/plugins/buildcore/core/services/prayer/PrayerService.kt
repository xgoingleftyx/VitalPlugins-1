package net.vital.plugins.buildcore.core.services.prayer

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import vital.api.ui.Prayer
import java.util.UUID

object PrayerService
{
	@Volatile internal var backend: PrayerBackend = VitalApiPrayerBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun toggle(prayer: Prayer): Boolean = withServiceCall(bus, sessionIdProvider, "PrayerService", "toggle") { backend.toggle(prayer) }

	suspend fun flick(prayer: Prayer): Boolean = withServiceCall(bus, sessionIdProvider, "PrayerService", "flick") { backend.flick(prayer) }

	internal fun resetForTests()
	{
		backend = VitalApiPrayerBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
