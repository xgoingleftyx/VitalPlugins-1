package net.vital.plugins.buildcore.core.task

/**
 * What a task or method needs to run. Checked at validation time.
 *
 * Composable via [AllOf] / [AnyOf] / [Not] for expressing quest prereqs
 * like "(Cooks Assistant complete) AND (Mining >= 15) AND NOT (Ironman
 * with no access to X)".
 *
 * Spec §7.
 */
sealed class Requirement {

	/** Predicate: is this requirement satisfied by the given state? */
	abstract fun isSatisfied(state: AccountState): Boolean

	data class StatLevel(val skill: Skill, val min: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.levels[skill] ?: 1) >= min
	}

	data class QuestComplete(val questId: QuestId) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.quests[questId] == QuestStatus.COMPLETE
	}

	data class QuestPartial(val questId: QuestId, val step: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.questStep[questId] ?: 0) >= step
	}

	data class ItemInInventory(val itemId: Int, val qty: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.inventory[itemId] ?: 0) >= qty
	}

	data class ItemInBank(val itemId: Int, val qty: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			(state.bank[itemId] ?: 0) >= qty
	}

	data class ItemEquipped(val itemId: Int) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.equipped.contains(itemId)
	}

	data class GpOnHand(val amount: Long) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.gpTotal >= amount
	}

	data class MembershipStatus(val required: Membership) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.membership == required || (required == Membership.F2P && state.membership == Membership.MEMBER)
	}

	data class InArea(val area: AreaTag) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.currentArea == area
	}

	data class AccountFlag(val flag: String) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean =
			state.flags.contains(flag)
	}

	data class AllOf(val reqs: List<Requirement>) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = reqs.all { it.isSatisfied(state) }
	}

	data class AnyOf(val reqs: List<Requirement>) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = reqs.any { it.isSatisfied(state) }
	}

	data class Not(val req: Requirement) : Requirement() {
		override fun isSatisfied(state: AccountState): Boolean = !req.isSatisfied(state)
	}
}

/**
 * Snapshot of account state used for Requirement evaluation. Plan 5
 * populates this from live game state via the services layer. Plan 2
 * uses it only in tests (hand-constructed) and the validator.
 */
data class AccountState(
	val levels: Map<Skill, Int> = emptyMap(),
	val quests: Map<QuestId, QuestStatus> = emptyMap(),
	val questStep: Map<QuestId, Int> = emptyMap(),
	val inventory: Map<Int, Int> = emptyMap(),
	val bank: Map<Int, Int> = emptyMap(),
	val equipped: Set<Int> = emptySet(),
	val gpTotal: Long = 0L,
	val membership: Membership = Membership.F2P,
	val currentArea: AreaTag = AreaTag.SAFE_OVERWORLD,
	val flags: Set<String> = emptySet()
)

enum class Membership { F2P, MEMBER }

enum class QuestStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }
