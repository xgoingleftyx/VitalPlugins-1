package net.vital.plugins.buildcore.core.services.magic

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.combat.Targetable
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object MagicService
{
	@Volatile internal var backend: MagicBackend = VitalApiMagicBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun cast(spell: vital.api.ui.Spell): Boolean = withServiceCall(bus, sessionIdProvider, "MagicService", "cast",
		stakes = ActionStakes.MEDIUM) { backend.cast(spell) }

	suspend fun castOn(spell: vital.api.ui.Spell, target: Targetable): Boolean = withServiceCall(bus, sessionIdProvider, "MagicService", "castOn",
		stakes = ActionStakes.MEDIUM) { backend.castOn(spell, target) }

	internal fun resetForTests()
	{
		backend = VitalApiMagicBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
