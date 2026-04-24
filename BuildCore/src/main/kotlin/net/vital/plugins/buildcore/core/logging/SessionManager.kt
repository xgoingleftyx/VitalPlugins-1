package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LaunchMode
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.StopReason
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskCounter
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the per-plugin-launch session identity and emits session
 * lifecycle events. Constructed once by [BuildCorePlugin.startUp] and
 * torn down at shutDown.
 *
 * Spec §8.
 */
class SessionManager(
	private val bus: EventBus,
	private val loggerScope: LoggerScope,
	private val layout: LogDirLayout,
	private val retentionSessions: Int = 30,
	private val clock: Clock = Clock.systemUTC(),
	private val buildcoreVersion: String = BuildCoreVersion.CURRENT,
	private val launchMode: LaunchMode = LaunchMode.NORMAL
)
{
	val sessionId: UUID = UUID.randomUUID()
	private val startedAt: Instant = clock.instant()
	private val taskCounters = ConcurrentHashMap<String, TaskCounter>()
	private val totalEvents = AtomicLong(0)
	private val ended = AtomicBoolean(false)

	private val mapper = ObjectMapper()
		.registerKotlinModule()
		.registerModule(JavaTimeModule())
		.findAndRegisterModules()

	fun start()
	{
		PrivacyScrubber.rotateKey(sessionId)
		layout.pruneOldSessions(retentionSessions)
		layout.createSessionDir(sessionId)
		writeSessionMeta(state = "running", endedAt = null)

		// Register the counter-subscriber BEFORE emitting SessionStart so that:
		// (a) it sees SessionStart (harmless — it doesn't count that type), and
		// (b) any event callers emit immediately after start() returns is not
		//     raced past the subscriber's registration. The latch ensures
		//     subscription is active before start() returns to the caller.
		val subscribed = java.util.concurrent.CountDownLatch(1)
		loggerScope.coroutineScope.launch {
			bus.events
				.onStart { subscribed.countDown() }
				.collect { e -> updateCounters(e) }
		}
		subscribed.await()

		bus.tryEmit(
			SessionStart(
				sessionId = sessionId,
				timestamp = startedAt,
				buildcoreVersion = buildcoreVersion,
				launchMode = launchMode
			)
		)
	}

	suspend fun requestStop(reason: StopReason = StopReason.USER)
	{
		if (!ended.compareAndSet(false, true)) return
		val durationMs = Duration.between(startedAt, clock.instant()).toMillis()

		// Hop to the logger thread so any events queued before requestStop
		// was called have been processed by the counter-subscriber (FIFO on
		// a single-thread dispatcher). Without this, taskCounters may be
		// stale when we snapshot it for SessionSummary below.
		withContext(loggerScope.dispatcher) { /* barrier */ }

		bus.emit(
			SessionSummary(
				sessionId = sessionId,
				durationMillis = durationMs,
				taskCounts = taskCounters.toMap(),
				totalEvents = totalEvents.get()
			)
		)
		bus.emit(SessionEnd(sessionId = sessionId, reason = reason))

		loggerScope.drain(deadlineMillis = 500)
		writeSessionMeta(state = "ended", endedAt = clock.instant())
	}

	private fun updateCounters(e: BusEvent)
	{
		totalEvents.incrementAndGet()
		val (taskId, update) = when (e)
		{
			is TaskStarted   -> e.taskId to { c: TaskCounter -> c.copy(started = c.started + 1) }
			is TaskCompleted -> e.taskId to { c: TaskCounter -> c.copy(completed = c.completed + 1) }
			is TaskFailed    -> e.taskId to { c: TaskCounter -> c.copy(failed = c.failed + 1) }
			is TaskSkipped   -> e.taskId to { c: TaskCounter -> c.copy(skipped = c.skipped + 1) }
			else             -> return
		}
		taskCounters.compute(taskId) { _, prev -> update(prev ?: TaskCounter()) }
	}

	private fun writeSessionMeta(state: String, endedAt: Instant?)
	{
		val meta = mapOf(
			"sessionId"          to sessionId.toString(),
			"startedAt"          to startedAt.toString(),
			"endedAt"            to endedAt?.toString(),
			"buildcoreVersion"   to buildcoreVersion,
			"state"              to state,
			"eventCount"         to totalEvents.get()
		)
		val path = layout.sessionDir(sessionId).resolve("session.meta.json")
		Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta))
	}
}
