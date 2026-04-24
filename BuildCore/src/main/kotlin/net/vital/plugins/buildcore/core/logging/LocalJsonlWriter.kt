package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.LogSubscriber
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * Fast-path subscriber. Every [BusEvent] is serialised as a single
 * JSON object on its own line and written to `events.jsonl` inside
 * the session's log dir. Unscrubbed — the raw JSONL is local-only.
 *
 * Spec §7.2.
 */
class LocalJsonlWriter(
	private val sessionDir: Path,
	private val capBytes: Long
) : LogSubscriber
{
	override val name: String = "local-jsonl"
	override val isFastPath: Boolean = true

	private val mapper: ObjectMapper = ObjectMapper()
		.registerKotlinModule()
		.registerModule(JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.setSerializationInclusion(JsonInclude.Include.ALWAYS)

	private val sink = RotatingFileSink(
		target = sessionDir.resolve("events.jsonl"),
		capBytes = capBytes,
		maxRotations = 3
	)

	@Volatile private var collectJob: Job? = null

	override fun attach(bus: EventBus, loggerScope: LoggerScope)
	{
		// Latch that trips as soon as the collect coroutine has registered
		// with the SharedFlow. Callers that emit immediately after attach()
		// would otherwise race against subscription setup (SharedFlow has
		// replay=0 — events emitted before subscription are silently dropped).
		val subscribed = CountDownLatch(1)
		collectJob = loggerScope.coroutineScope.launch {
			bus.events
				.onStart { subscribed.countDown() }
				.collect { event -> write(event) }
		}
		subscribed.await()
	}

	private fun write(event: BusEvent)
	{
		val node = mapper.valueToTree<ObjectNode>(event)
		// Insert "type" at the head for human readability / grep-friendliness.
		val out = mapper.createObjectNode()
		out.put("type", event::class.simpleName)
		node.fieldNames().forEach { fieldName -> out.set<ObjectNode>(fieldName, node.get(fieldName)) }
		sink.writeLine(mapper.writeValueAsString(out))
	}

	/**
	 * Cancel the collect coroutine (stops new events from being written),
	 * then close the file sink.
	 *
	 * Cancellation is safe: the coroutine is cooperatively cancelled at its
	 * next suspension point (inside [kotlinx.coroutines.flow.SharedFlow.collect]),
	 * so no partial write can occur.  The [RotatingFileSink] is then closed
	 * while nothing else holds a reference.
	 */
	override suspend fun drain()
	{
		collectJob?.cancel()
		collectJob?.join()
		sink.close()
	}
}
