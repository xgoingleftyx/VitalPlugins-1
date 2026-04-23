package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import java.util.UUID

/**
 * The context every Task receives per-call.
 *
 * Plan 2 ships a minimal version: just enough to let Runner + NoOpTask
 * prove the state machine works. Plan 5 adds services; Plan 6 adds
 * confidence queries; Plan 7 adds profile + plan access.
 *
 * Spec §6.
 */
interface TaskContext {
	val sessionId: UUID
	val taskInstanceId: UUID
	val restrictions: RestrictionSet
	val accountState: AccountState
	val eventBus: EventBus
	val attemptNumber: Int

	/** Tasks read their config via this map. Keys match [ConfigField.key]. */
	val taskConfig: Map<String, Any>
	val methodConfig: Map<String, Any>
}

/**
 * Plain, immutable implementation for Plan 2 + tests.
 * Plan 5 will introduce a richer implementation with service accessors.
 */
data class SimpleTaskContext(
	override val sessionId: UUID = UUID.randomUUID(),
	override val taskInstanceId: UUID = UUID.randomUUID(),
	override val restrictions: RestrictionSet,
	override val accountState: AccountState = AccountState(),
	override val eventBus: EventBus,
	override val attemptNumber: Int = 1,
	override val taskConfig: Map<String, Any> = emptyMap(),
	override val methodConfig: Map<String, Any> = emptyMap()
) : TaskContext
