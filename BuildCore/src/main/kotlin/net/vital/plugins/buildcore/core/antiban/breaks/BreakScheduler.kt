package net.vital.plugins.buildcore.core.antiban.breaks

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.antiban.rng.SeededRng
import net.vital.plugins.buildcore.core.events.*
import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import net.vital.plugins.buildcore.core.task.AccountState
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.util.UUID

/**
 * Cooperative break orchestrator. Single coroutine per session; one launch per
 * enabled tier. Polls [Task.canStopNow] before firing; never force-pauses the
 * runner. Bedtime tier escalates via [BedtimeEscalator] (Task 14).
 *
 * Plan 4b spec §5.3.
 */
class BreakScheduler(
	private val bus: EventBus,
	private val sessionIdProvider: () -> UUID,
	private val taskProvider: () -> Task?,
	private val config: BreakConfig,
	private val rng: SeededRng,
	private val taskContextProvider: () -> TaskContext? = { null },
	private val nowMs: () -> Long = System::currentTimeMillis,
	private val pollIntervalMs: Long = 500L,
	private val bedtimeEscalator: BedtimeEscalator = BedtimeEscalator(bus, sessionIdProvider)
)
{
	private val state = BreakState()

	internal fun activeStateForTests(): BreakState = state

	suspend fun run() = coroutineScope {
		config.tiers
			.filter { it.value.enabled && it.key != BreakTier.BANKING }  // Banking is trigger-driven, fired by services
			.forEach { (tier, tierCfg) ->
				launch { tierLoop(tier, tierCfg) }
			}
	}

	private suspend fun tierLoop(tier: BreakTier, cfg: TierConfig)
	{
		while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false)
		{
			val intervalMs = sampleRange(cfg.intervalRangeMs)
			val fireAt = nowMs() + intervalMs
			bus.tryEmit(BreakScheduled(sessionId = sessionIdProvider(), tier = tier, fireAtEpochMs = fireAt))
			delay(intervalMs)
			pollFire(tier, cfg, fireAt)
		}
	}

	private suspend fun pollFire(tier: BreakTier, cfg: TierConfig, fireAt: Long)
	{
		val deadlineMs = fireAt + cfg.maxDeferMs
		var lastDeferEmit = 0L
		while (nowMs() < deadlineMs)
		{
			val task = taskProvider()
			val ctx = taskContextProvider() ?: STUB_CTX
			val canStop = if (task != null) task.canStopNow(ctx) else true
			if (canStop)
			{
				executeBreak(tier, cfg)
				return
			}
			val deferredMs = nowMs() - fireAt
			if (deferredMs - lastDeferEmit >= 1_000L)
			{
				bus.tryEmit(BreakDeferred(sessionId = sessionIdProvider(), tier = tier, deferredMillis = deferredMs))
				lastDeferEmit = deferredMs
			}
			delay(pollIntervalMs)
		}
		applyTimeout(tier, cfg, fireAt)
	}

	private suspend fun executeBreak(tier: BreakTier, cfg: TierConfig)
	{
		val plannedMs = sampleRange(cfg.durationRangeMs)
		val startMs = nowMs()
		state.startActive(tier, plannedMs, startMs)
		bus.tryEmit(BreakStarted(sessionId = sessionIdProvider(), tier = tier, plannedDurationMillis = plannedMs))
		delay(plannedMs)
		val actual = nowMs() - startMs
		state.clearActive()
		bus.tryEmit(BreakEnded(sessionId = sessionIdProvider(), tier = tier, actualDurationMillis = actual))
	}

	private suspend fun applyTimeout(tier: BreakTier, cfg: TierConfig, fireAt: Long)
	{
		val deferred = nowMs() - fireAt
		when (cfg.onDeferTimeout)
		{
			DeferAction.DROP ->
				bus.tryEmit(BreakDropped(sessionId = sessionIdProvider(), tier = tier, deferredMillis = deferred))
			DeferAction.RESCHEDULE ->
			{
				val newFireAt = nowMs() + (cfg.intervalRangeMs.first / 4).coerceAtLeast(1_000L)
				bus.tryEmit(BreakRescheduled(sessionId = sessionIdProvider(), tier = tier, newFireAtEpochMs = newFireAt))
			}
			DeferAction.ESCALATE ->
			{
				bus.tryEmit(EarlyStopRequested(sessionId = sessionIdProvider(), reason = EarlyStopReason.BEDTIME))
				bedtimeEscalator.escalate(taskProvider(), taskContextProvider(), cfg)
				// After escalation, fire the break unconditionally.
				executeBreak(tier, cfg)
			}
		}
	}

	/** Called from [net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate.preemptHook] when a SURVIVAL scope opens. */
	fun preempt()
	{
		val active = state.activeOrNull() ?: return
		val remaining = (active.startedAtMs + active.plannedDurationMs - nowMs()).coerceAtLeast(0L)
		state.clearActive()
		bus.tryEmit(BreakPreempted(sessionId = sessionIdProvider(), tier = active.tier, remainingMillis = remaining))
		// Note: the executeBreak coroutine's `delay(plannedMs)` is not cancelled — the in-flight
		// emission of BreakEnded after the remaining time is acceptable; Plan 6 watchdog can flag
		// stuck-active scenarios. For tighter cancel, refactor executeBreak to use a deferrable Job
		// (out of 4b scope per spec §11 Risks).
	}

	private fun sampleRange(range: LongRange): Long
	{
		if (range.first >= range.last) return range.first
		val span = range.last - range.first
		return range.first + (rng.nextLong() and Long.MAX_VALUE) % (span + 1)
	}

	companion object
	{
		/**
		 * Minimal stub [TaskContext] used when no real context is available
		 * (e.g. scheduler fires before the runner has set a context). Real tasks
		 * only see this stub if [taskContextProvider] is not wired; in production
		 * it should always be wired by [AntibanBootstrap].
		 */
		private val STUB_CTX: TaskContext = object : TaskContext
		{
			private val _restrictions = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)
			private val _bus by lazy { net.vital.plugins.buildcore.core.events.EventBus() }
			override val sessionId: UUID = UUID(0, 0)
			override val taskInstanceId: UUID = UUID(0, 0)
			override val restrictions get() = _restrictions
			override val accountState = AccountState()
			override val eventBus get() = _bus
			override val attemptNumber = 0
			override val taskConfig: Map<String, Any> = emptyMap()
			override val methodConfig: Map<String, Any> = emptyMap()
		}
	}
}
