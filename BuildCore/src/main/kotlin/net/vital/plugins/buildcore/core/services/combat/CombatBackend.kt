package net.vital.plugins.buildcore.core.services.combat

interface CombatBackend
{
	suspend fun attack(target: Targetable): Boolean
	suspend fun setAutoRetaliate(enabled: Boolean): Boolean
}
