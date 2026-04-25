package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.bank.BankService
import net.vital.plugins.buildcore.core.services.bank.VitalApiBankBackend
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
		BankService.backend = VitalApiBankBackend
		BankService.bus = bus
		BankService.sessionIdProvider = sessionIdProvider
	}

	internal fun resetForTests()
	{
		installed.set(false)
		RestrictionGate.engine = null
		ServiceCallContext.resetForTests()
		BankService.resetForTests()
	}
}
