package net.vital.plugins.buildcore.core.services.prayer

import vital.api.ui.Prayer

interface PrayerBackend
{
	suspend fun toggle(prayer: Prayer): Boolean
	suspend fun flick(prayer: Prayer): Boolean
}
