package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import net.vital.plugins.buildcore.core.events.MethodPicked
import net.vital.plugins.buildcore.core.events.PathPicked
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.RestrictionViolated
import net.vital.plugins.buildcore.core.events.SafeStopCompleted
import net.vital.plugins.buildcore.core.events.SafeStopRequested
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskPaused
import net.vital.plugins.buildcore.core.events.TaskQueued
import net.vital.plugins.buildcore.core.events.TaskResumed
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.events.UnhandledException
import net.vital.plugins.buildcore.core.events.ValidationFailed
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch

/**
 * Fast-path subscriber. Writes human-readable lines to `summary.log`
 * in the session's log dir. Events are privacy-scrubbed before
 * writing (see spec §6) and filtered by [level].
 *
 * Summary covers: session lifecycle, task lifecycle, method/path,
 * safe-stop, errors. Events without a human-summary case (e.g.
 * [net.vital.plugins.buildcore.core.events.TaskProgress],
 * [net.vital.plugins.buildcore.core.events.PerformanceSample],
 * [net.vital.plugins.buildcore.core.events.SubscriberOverflowed])
 * are silently skipped — the JSONL has the complete truth.
 *
 * Lifecycle mirrors [LocalJsonlWriter]: the collect [Job] is tracked
 * so [drain] can cancel+join it before closing the sink, preventing
 * the "Stream closed" race when the scope is torn down mid-write.
 *
 * Spec §7.3, §7.4.
 */
class LocalSummaryWriter(
	sessionDir: Path,
	private val level: LogLevel
) : LogSubscriber {

	override val name: String = "local-summary"
	override val isFastPath: Boolean = true

	private val sink = RotatingFileSink(
		target = sessionDir.resolve("summary.log"),
		capBytes = 10L * 1024 * 1024,
		maxRotations = 1              // summary rotates once; jsonl is the source of truth
	)

	private val fmt: DateTimeFormatter = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss.SSS zzz")
		.withZone(ZoneId.of("America/New_York"))

	@Volatile private var collectJob: Job? = null

	override fun attach(bus: EventBus, loggerScope: LoggerScope)
	{
		// Latch ensures subscription is live before attach() returns, so
		// callers that emit immediately don't race past the SharedFlow
		// subscription (replay=0 — pre-subscription events are dropped).
		val subscribed = CountDownLatch(1)
		collectJob = loggerScope.coroutineScope.launch {
			bus.events
				.onStart { subscribed.countDown() }
				.collect { event ->
					val scrubbed = PrivacyScrubber.scrub(event)
					val evLevel = levelOf(scrubbed)
					if (evLevel.ordinal < level.ordinal) return@collect
					val line = formatLine(scrubbed, evLevel) ?: return@collect
					sink.writeLine(line)
				}
		}
		subscribed.await()
	}

	private fun formatLine(event: BusEvent, evLevel: LogLevel): String?
	{
		val ts = fmt.format(event.timestamp)
		val (category, subject, msg) = when (event) {
			is SessionStart        -> Triple("sess", "-", "session start v${event.buildcoreVersion} mode=${event.launchMode}")
			is SessionEnd          -> Triple("sess", "-", "session end reason=${event.reason}")
			is SessionSummary      -> Triple("sess", "-", "summary durationMs=${event.durationMillis} events=${event.totalEvents} tasks=${event.taskCounts.size}")
			is TaskQueued          -> Triple("task", event.taskId, "queued")
			is TaskValidated       -> Triple("task", event.taskId,
				if (event.pass) "validated OK" else "validation rejected: ${event.rejectReason}")
			is TaskStarted         -> Triple("task", event.taskId, "started via method=${event.methodId} path=${event.pathId}")
			is TaskCompleted       -> Triple("task", event.taskId, "completed durationMs=${event.durationMillis}")
			is TaskFailed          -> Triple("task", event.taskId, "failed attempt=${event.attemptNumber} type=${event.reasonType} detail=${event.reasonDetail}")
			is TaskRetrying        -> Triple("retry", event.taskId, "attempt ${event.attemptNumber} backoff=${event.backoffMillis}ms")
			is TaskSkipped         -> Triple("task", event.taskId, "skipped reason=${event.reason}")
			is TaskPaused          -> Triple("task", event.taskId, "paused reason=${event.reason}")
			is TaskResumed         -> Triple("task", event.taskId, "resumed")
			is MethodPicked        -> Triple("task", event.taskId, "method picked=${event.methodId}")
			is PathPicked          -> Triple("task", event.taskId, "path picked=${event.pathId} kind=${event.pathKind}")
			is SafeStopRequested   -> Triple("stop", "-", "safe-stop requested reason=${event.reason}")
			is SafeStopCompleted   -> Triple("stop", "-", "safe-stop completed durationMs=${event.durationMillis}")
			is UnhandledException  -> Triple("err", event.threadName, "${event.exceptionClass}: ${event.message}")
			is ValidationFailed    -> Triple("valid", event.subject, event.detail)
			is RestrictionViolated -> Triple("restr", event.restrictionId, "${event.moment}: ${event.effectSummary}")
			else                   -> return null
		}
		val lvlCol = evLevel.name.padEnd(5)
		val catCol = category.padEnd(6)
		val subjCol = subject.take(16).padEnd(16)
		return "$ts  $lvlCol $catCol [$subjCol] $msg"
	}

	/**
	 * Cancel the collect coroutine (stops new events from being written),
	 * then close the file sink.
	 *
	 * Cancellation is safe: the coroutine is cooperatively cancelled at its
	 * next suspension point (inside [kotlinx.coroutines.flow.SharedFlow.collect]),
	 * so no partial write can occur. The [RotatingFileSink] is then closed
	 * while nothing else holds a reference.
	 */
	override suspend fun drain()
	{
		collectJob?.cancel()
		collectJob?.join()
		sink.close()
	}
}
