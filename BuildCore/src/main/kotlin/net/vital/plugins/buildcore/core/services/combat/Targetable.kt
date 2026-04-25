package net.vital.plugins.buildcore.core.services.combat

sealed interface Targetable
{
	data class NpcTarget(val npc: vital.api.entities.Npc) : Targetable
	data class PlayerTarget(val player: vital.api.entities.Player) : Targetable
}
