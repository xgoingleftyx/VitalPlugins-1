package net.vital.plugins.buildcore.core.services.combat

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object CombatService
{
	@Volatile internal var backend: CombatBackend = VitalApiCombatBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun attack(target: Targetable): Boolean = withServiceCall(bus, sessionIdProvider, "CombatService", "attack") { backend.attack(target) }

	suspend fun setAutoRetaliate(enabled: Boolean): Boolean = withServiceCall(bus, sessionIdProvider, "CombatService", "setAutoRetaliate") { backend.setAutoRetaliate(enabled) }

	internal fun resetForTests()
	{
		backend = VitalApiCombatBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
