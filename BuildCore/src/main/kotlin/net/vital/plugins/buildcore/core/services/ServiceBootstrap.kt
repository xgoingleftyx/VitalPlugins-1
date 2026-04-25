package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.bank.BankService
import net.vital.plugins.buildcore.core.services.bank.VitalApiBankBackend
import net.vital.plugins.buildcore.core.services.equipment.EquipmentService
import net.vital.plugins.buildcore.core.services.equipment.VitalApiEquipmentBackend
import net.vital.plugins.buildcore.core.services.walker.VitalApiWalkerBackend
import net.vital.plugins.buildcore.core.services.walker.WalkerService
import net.vital.plugins.buildcore.core.services.inventory.InventoryService
import net.vital.plugins.buildcore.core.services.inventory.VitalApiInventoryBackend
import net.vital.plugins.buildcore.core.services.login.LoginService
import net.vital.plugins.buildcore.core.services.login.VitalApiLoginBackend
import net.vital.plugins.buildcore.core.services.world.VitalApiWorldBackend
import net.vital.plugins.buildcore.core.services.world.WorldService
import net.vital.plugins.buildcore.core.services.combat.CombatService
import net.vital.plugins.buildcore.core.services.combat.VitalApiCombatBackend
import net.vital.plugins.buildcore.core.services.magic.MagicService
import net.vital.plugins.buildcore.core.services.magic.VitalApiMagicBackend
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
		InventoryService.backend = VitalApiInventoryBackend
		InventoryService.bus = bus
		InventoryService.sessionIdProvider = sessionIdProvider
		EquipmentService.backend = VitalApiEquipmentBackend
		EquipmentService.bus = bus
		EquipmentService.sessionIdProvider = sessionIdProvider
		WalkerService.backend = VitalApiWalkerBackend
		WalkerService.bus = bus
		WalkerService.sessionIdProvider = sessionIdProvider
		LoginService.backend = VitalApiLoginBackend
		LoginService.bus = bus
		LoginService.sessionIdProvider = sessionIdProvider
		WorldService.backend = VitalApiWorldBackend
		WorldService.bus = bus
		WorldService.sessionIdProvider = sessionIdProvider
		CombatService.backend = VitalApiCombatBackend
		CombatService.bus = bus
		CombatService.sessionIdProvider = sessionIdProvider
		MagicService.backend = VitalApiMagicBackend
		MagicService.bus = bus
		MagicService.sessionIdProvider = sessionIdProvider
	}

	internal fun resetForTests()
	{
		installed.set(false)
		RestrictionGate.engine = null
		ServiceCallContext.resetForTests()
		BankService.resetForTests()
		InventoryService.resetForTests()
		EquipmentService.resetForTests()
		WalkerService.resetForTests()
		LoginService.resetForTests()
		WorldService.resetForTests()
		CombatService.resetForTests()
		MagicService.resetForTests()
	}
}
