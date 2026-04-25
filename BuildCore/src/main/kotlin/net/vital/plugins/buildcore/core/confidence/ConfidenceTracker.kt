package net.vital.plugins.buildcore.core.confidence

import net.vital.plugins.buildcore.core.events.ConfidenceUpdated
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.time.Clock
import java.util.UUID

/**
 * Singleton confidence cache + recompute. Subscribes to ServiceCallEnd via
 * [ConfidenceSubscriber] (Task 11). Cache TTL 600ms (Plan 6a spec §4.5).
 */
object ConfidenceTracker
{
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }
	@Volatile internal var taskProvider: () -> Pair<Task?, TaskContext?> = { null to null }
	@Volatile internal var clock: Clock = Clock.systemUTC()
	@Volatile internal var gameStateProvider: GameStateProvider = VitalApiGameStateProvider

	@Volatile private var cached: Confidence? = null
	@Volatile private var lastActionOutcome: ServiceOutcome? = null

	private const val CACHE_TTL_MS = 600L

	fun current(): Confidence
	{
		val now = clock.millis()
		cached?.let { if (now - it.computedAtMs < CACHE_TTL_MS) return it }
		val fresh = compute(now)
		cached = fresh
		bus?.tryEmit(ConfidenceUpdated(sessionId = sessionIdProvider(), score = fresh.score, perSignal = fresh.perSignal))
		return fresh
	}

	internal fun onServiceCallEnd(end: ServiceCallEnd)
	{
		if (end.outcome != ServiceOutcome.UNCONFIDENT) lastActionOutcome = end.outcome
		cached = null
	}

	private fun compute(now: Long): Confidence
	{
		val (task, ctx) = taskProvider()
		val gsp = gameStateProvider

		val per = mutableMapOf<String, Double>()

		// InterfaceKnown
		val wid = gsp.openWidgetId()
		per[ConfidenceSignal.INTERFACE_KNOWN.signalName] = when
		{
			wid == null -> 1.0
			else        -> 0.5    // unknown widget id; v1 has no allowlist registry
		}

		// LastActionResulted
		per[ConfidenceSignal.LAST_ACTION_RESULTED.signalName] = when (lastActionOutcome)
		{
			ServiceOutcome.SUCCESS    -> 1.0
			ServiceOutcome.FAILURE    -> 0.5
			ServiceOutcome.EXCEPTION  -> 0.2
			ServiceOutcome.RESTRICTED -> 1.0
			ServiceOutcome.UNCONFIDENT, null -> 1.0
		}

		// HpNormal
		per[ConfidenceSignal.HP_NORMAL.signalName] = gsp.hpRatio()?.let {
			when
			{
				it >= 0.7 -> 1.0
				it >= 0.4 -> 0.5
				else      -> 0.0
			}
		} ?: 1.0

		// NoUnexpectedDialog
		per[ConfidenceSignal.NO_UNEXPECTED_DIALOG.signalName] = if (gsp.isDialogVisible()) 0.5 else 1.0

		// Stub signals — call SPI hooks; null → 1.0
		per[ConfidenceSignal.EXPECTED_ENTITIES_VISIBLE.signalName] = computeEntitiesSignal(task, ctx, gsp)
		per[ConfidenceSignal.POSITION_REASONABLE.signalName] = computePositionSignal(task, ctx, gsp)
		per[ConfidenceSignal.INVENTORY_DELTA_EXPECTED.signalName] = computeInventoryDeltaSignal(task, ctx)
		per[ConfidenceSignal.RECENT_CHAT_NORMAL.signalName] = 1.0    // v1 deferred

		var score = 0.0
		for (sig in ConfidenceSignal.ALL)
		{
			val v = per[sig.signalName] ?: 1.0
			score += sig.weight * v
		}
		score = score.coerceIn(0.0, 1.0)

		return Confidence(score, per.toMap(), now)
	}

	private fun computeEntitiesSignal(task: Task?, ctx: TaskContext?, gsp: GameStateProvider): Double
	{
		if (task == null || ctx == null) return 1.0
		val hints = task.expectedEntities(ctx) ?: return 1.0
		if (hints.isEmpty()) return 1.0
		val matched = hints.count { hint ->
			when (hint.kind)
			{
				"npc"    -> gsp.npcCountByName(hint.nameOrId) > 0
				"object" -> gsp.objectCountByName(hint.nameOrId) > 0
				else     -> true
			}
		}
		return matched.toDouble() / hints.size.toDouble()
	}

	private fun computePositionSignal(task: Task?, ctx: TaskContext?, gsp: GameStateProvider): Double
	{
		if (task == null || ctx == null) return 1.0
		val area = task.expectedArea(ctx) ?: return 1.0
		val px = gsp.playerTileX() ?: return 1.0
		val py = gsp.playerTileY() ?: return 1.0
		val dx = px - area.centerX
		val dy = py - area.centerY
		return if (dx * dx + dy * dy <= area.radius * area.radius) 1.0 else 0.0
	}

	private fun computeInventoryDeltaSignal(task: Task?, ctx: TaskContext?): Double
	{
		if (task == null || ctx == null) return 1.0
		task.expectedInventoryDelta(ctx) ?: return 1.0
		return 0.5    // hint exists but evaluation is deferred per spec §4.4
	}

	internal fun resetForTests()
	{
		cached = null
		lastActionOutcome = null
		bus = null
		sessionIdProvider = { UUID(0, 0) }
		taskProvider = { null to null }
		clock = Clock.systemUTC()
		gameStateProvider = VitalApiGameStateProvider
	}
}
