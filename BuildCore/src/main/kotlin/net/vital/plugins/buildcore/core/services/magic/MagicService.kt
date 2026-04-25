package net.vital.plugins.buildcore.core.services.magic

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.combat.Targetable
import net.vital.plugins.buildcore.core.services.withServiceCall
import vital.api.ui.Spell
import java.util.UUID

object MagicService
{
	@Volatile internal var backend: MagicBackend = VitalApiMagicBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun cast(spell: Spell): Boolean = withServiceCall(bus, sessionIdProvider, "MagicService", "cast") { backend.cast(spell) }

	suspend fun castOn(spell: Spell, target: Targetable): Boolean = withServiceCall(bus, sessionIdProvider, "MagicService", "castOn") { backend.castOn(spell, target) }

	internal fun resetForTests()
	{
		backend = VitalApiMagicBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
