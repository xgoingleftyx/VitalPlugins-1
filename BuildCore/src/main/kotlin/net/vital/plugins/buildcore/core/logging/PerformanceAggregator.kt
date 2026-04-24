package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PerformanceSample
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Periodic sampler. Every [intervalMillis] emits a [PerformanceSample]
 * with JVM heap + event-rate + logger-lag + dropped-event counters.
 *
 * Not a [LogSubscriber] because it runs on its own dispatcher and
 * emits back onto the bus rather than writing to disk. It does
 * subscribe to the bus internally to keep counters fresh.
 *
 * Spec §10.1.
 */
class PerformanceAggregator(
	private val intervalMillis: Long,
	private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
	private val sessionIdProvider: () -> UUID = { UUID(0, 0) }
)
{

	private val eventCount = AtomicLong(0)
	private val maxLagMillis = AtomicLong(0)
	private val droppedSinceLastSample = AtomicLong(0)
	private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("perf-aggregator"))
	private var sampleJob: Job? = null
	@Volatile private var lastSampleAt: Instant = Instant.now()

	fun start(bus: EventBus)
	{
		lastSampleAt = Instant.now()
		// subscriber — updates counters on every event
		scope.launch {
			bus.events.collect { event -> onEvent(event) }
		}
		// periodic sampler — emits PerformanceSample
		sampleJob = scope.launch {
			while (true)
			{
				delay(intervalMillis)
				val now = Instant.now()
				val elapsedSec = max(1L, Duration.between(lastSampleAt, now).seconds)
				val rt = Runtime.getRuntime()
				val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
				val heapMaxMb = rt.maxMemory() / (1024L * 1024L)
				val count = eventCount.getAndSet(0)
				val lag = maxLagMillis.getAndSet(0)
				val dropped = droppedSinceLastSample.getAndSet(0)

				bus.emit(PerformanceSample(
					sessionId = sessionIdProvider(),
					intervalSeconds = elapsedSec,
					eventRatePerSec = count.toDouble() / elapsedSec,
					jvmHeapUsedMb = heapUsedMb,
					jvmHeapMaxMb = heapMaxMb,
					loggerLagMaxMs = lag,
					droppedEventsSinceLastSample = dropped
				))
				lastSampleAt = now
			}
		}
	}

	private fun onEvent(event: BusEvent)
	{
		eventCount.incrementAndGet()
		val lag = Duration.between(event.timestamp, Instant.now()).toMillis()
		maxLagMillis.updateAndGet { prev -> max(prev, lag) }
		if (event is SubscriberOverflowed)
		{
			droppedSinceLastSample.addAndGet(event.droppedCount.toLong())
		}
	}

	fun stop()
	{
		sampleJob?.cancel()
		scope.cancel()
	}
}
