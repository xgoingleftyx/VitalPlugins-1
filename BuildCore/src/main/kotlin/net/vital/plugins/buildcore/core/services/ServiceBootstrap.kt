package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.events.EventBus
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires all 14 L5 service backends + restriction engine. Called once from
 * [net.vital.plugins.buildcore.BuildCorePlugin.startUp] after `AntibanBootstrap.install`.
 *
 * Plan 5a spec §6.
 */
object ServiceBootstrap
{
	private val installed = AtomicBoolean(false)

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		restrictionEngine: RestrictionEngine = StaticRestrictionEngine(emptySet())
	)
	{
		if (!installed.compareAndSet(false, true)) return
		RestrictionGate.engine = restrictionEngine
		// Service-specific wiring is added in Tasks 6–18.
	}

	internal fun resetForTests()
	{
		installed.set(false)
		RestrictionGate.engine = null
		ServiceCallContext.resetForTests()
		// Service-specific reset is added in Tasks 6–18.
	}
}
