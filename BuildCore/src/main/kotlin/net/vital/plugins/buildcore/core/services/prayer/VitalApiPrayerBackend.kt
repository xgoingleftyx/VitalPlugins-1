package net.vital.plugins.buildcore.core.services.prayer

import vital.api.ui.Prayer
import vital.api.ui.Prayers

/**
 * Default [PrayerBackend] delegating to VitalAPI.
 * Plan 5a spec §5.
 */
object VitalApiPrayerBackend : PrayerBackend
{
	override suspend fun toggle(prayer: Prayer): Boolean = Prayers.toggle(prayer)

	override suspend fun flick(prayer: Prayer): Boolean =
		error("not implemented in 5a")
}
