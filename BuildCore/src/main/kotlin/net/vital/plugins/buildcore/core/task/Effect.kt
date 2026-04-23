package net.vital.plugins.buildcore.core.task

/**
 * What a task or method does to the account.
 *
 * Declared statically by task authors; consumed by the RestrictionEngine
 * to veto incompatible task+profile combinations at edit-time.
 *
 * Spec §7. Sealed — adding a new effect requires adding a case here
 * and updating architecture tests to cover it.
 */
sealed class Effect {
	data class GrantsXp(val skill: Skill, val rate: XpRange) : Effect()
	data class AcquiresItem(val itemId: Int, val qty: Int, val via: AcquisitionMethod) : Effect()
	data class SpendsGp(val estimated: GpRange) : Effect()
	data class EntersArea(val area: AreaTag) : Effect()
	data class CompletesQuest(val questId: QuestId) : Effect()
	data class RaisesCombatLevel(val delta: Int) : Effect()
	object RequiresMembership : Effect()
	data class TradesWithPlayer(val purpose: TradePurpose) : Effect()
	data class UsesGrandExchange(val action: GeAction) : Effect()
	data class CustomEffect(val tag: String) : Effect()
}

enum class Skill {
	ATTACK, STRENGTH, DEFENCE, HITPOINTS, RANGED, PRAYER, MAGIC,
	COOKING, WOODCUTTING, FLETCHING, FISHING, FIREMAKING, CRAFTING,
	SMITHING, MINING, HERBLORE, AGILITY, THIEVING, SLAYER, FARMING,
	RUNECRAFT, HUNTER, CONSTRUCTION
}

data class XpRange(val min: Long, val max: Long) {
	init { require(min >= 0 && max >= min) { "XpRange: invalid bounds min=$min max=$max" } }
}

data class GpRange(val min: Long, val max: Long) {
	init { require(min >= 0 && max >= min) { "GpRange: invalid bounds min=$min max=$max" } }
}

enum class AcquisitionMethod { MOB_DROP, SKILLING, GE, MULE, NPC_SHOP, QUEST, PLAYER_TRADE }

enum class AreaTag { SAFE_OVERWORLD, WILDERNESS, DUNGEON, PVP_WORLD, MINIGAME, INSTANCE }

enum class TradePurpose { MULE_RECEIVE_GP, MULE_RECEIVE_BOND, MULE_SEND_GP, MULE_RETURN }

enum class GeAction { BUY, SELL, COLLECT }

@JvmInline
value class QuestId(val raw: String) {
	init { require(raw.isNotBlank()) { "QuestId must not be blank" } }
}
