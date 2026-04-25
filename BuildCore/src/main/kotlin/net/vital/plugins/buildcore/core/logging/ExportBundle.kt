package net.vital.plugins.buildcore.core.logging

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.BreakDeferred
import net.vital.plugins.buildcore.core.events.BreakDropped
import net.vital.plugins.buildcore.core.events.BreakEnded
import net.vital.plugins.buildcore.core.events.BreakPreempted
import net.vital.plugins.buildcore.core.events.BreakRescheduled
import net.vital.plugins.buildcore.core.events.BreakScheduled
import net.vital.plugins.buildcore.core.events.BreakStarted
import net.vital.plugins.buildcore.core.events.EarlyStopRequested
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.MethodPicked
import net.vital.plugins.buildcore.core.events.Misclick
import net.vital.plugins.buildcore.core.events.PathPicked
import net.vital.plugins.buildcore.core.events.PerformanceSample
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.events.PrecisionModeEntered
import net.vital.plugins.buildcore.core.events.PrecisionModeExited
import net.vital.plugins.buildcore.core.events.PrivacyScrubber
import net.vital.plugins.buildcore.core.events.RestrictionViolated
import net.vital.plugins.buildcore.core.events.SafeStopCompleted
import net.vital.plugins.buildcore.core.events.SafeStopRequested
import net.vital.plugins.buildcore.core.events.SemanticMisclick
import net.vital.plugins.buildcore.core.events.SessionEnd
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.events.SessionStart
import net.vital.plugins.buildcore.core.events.SessionSummary
import net.vital.plugins.buildcore.core.events.SubscriberOverflowed
import net.vital.plugins.buildcore.core.events.TaskCompleted
import net.vital.plugins.buildcore.core.events.TaskFailed
import net.vital.plugins.buildcore.core.events.TaskPaused
import net.vital.plugins.buildcore.core.events.TaskProgress
import net.vital.plugins.buildcore.core.events.TaskQueued
import net.vital.plugins.buildcore.core.events.TaskResumed
import net.vital.plugins.buildcore.core.events.TaskRetrying
import net.vital.plugins.buildcore.core.events.TaskSkipped
import net.vital.plugins.buildcore.core.events.TaskStarted
import net.vital.plugins.buildcore.core.events.TaskValidated
import net.vital.plugins.buildcore.core.events.UnhandledException
import net.vital.plugins.buildcore.core.events.ValidationFailed
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Reads a session's raw `session.log.jsonl`, runs every event through
 * [PrivacyScrubber.scrub] (export-time scrubbing per Plan 4b §7.3), and writes
 * the result to [LogDirLayout.exportDir] as `<sessionId>-export.jsonl`.
 *
 * Raw lines are expected in [LocalJsonlWriter] format: a JSON object with a
 * leading `"type"` field (the event's simple class name) followed by the
 * event's own fields. Lines with an unrecognised type are passed through
 * unscrubbed (forward-compatibility).
 *
 * Plan 4b spec §7.1, §7.3.
 */
object ExportBundle
{
	private val mapper: ObjectMapper = ObjectMapper()
		.registerKotlinModule()
		.registerModule(JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

	/** Maps class simple name → Java class for deserialisation. */
	private val typeRegistry: Map<String, Class<out BusEvent>> = mapOf(
		"SessionStart"         to SessionStart::class.java,
		"SessionEnd"           to SessionEnd::class.java,
		"SessionSummary"       to SessionSummary::class.java,
		"TaskQueued"           to TaskQueued::class.java,
		"TaskValidated"        to TaskValidated::class.java,
		"TaskStarted"          to TaskStarted::class.java,
		"TaskProgress"         to TaskProgress::class.java,
		"TaskCompleted"        to TaskCompleted::class.java,
		"TaskFailed"           to TaskFailed::class.java,
		"TaskRetrying"         to TaskRetrying::class.java,
		"TaskSkipped"          to TaskSkipped::class.java,
		"TaskPaused"           to TaskPaused::class.java,
		"TaskResumed"          to TaskResumed::class.java,
		"MethodPicked"         to MethodPicked::class.java,
		"PathPicked"           to PathPicked::class.java,
		"SafeStopRequested"    to SafeStopRequested::class.java,
		"SafeStopCompleted"    to SafeStopCompleted::class.java,
		"UnhandledException"   to UnhandledException::class.java,
		"ValidationFailed"     to ValidationFailed::class.java,
		"RestrictionViolated"  to RestrictionViolated::class.java,
		"PerformanceSample"    to PerformanceSample::class.java,
		"SubscriberOverflowed" to SubscriberOverflowed::class.java,
		"InputAction"          to InputAction::class.java,
		"FatigueUpdated"       to FatigueUpdated::class.java,
		"PersonalityResolved"  to PersonalityResolved::class.java,
		"SessionRngSeeded"     to SessionRngSeeded::class.java,
		"PrecisionModeEntered" to PrecisionModeEntered::class.java,
		"PrecisionModeExited"  to PrecisionModeExited::class.java,
		"BreakScheduled"       to BreakScheduled::class.java,
		"BreakStarted"         to BreakStarted::class.java,
		"BreakEnded"           to BreakEnded::class.java,
		"BreakDeferred"        to BreakDeferred::class.java,
		"BreakDropped"         to BreakDropped::class.java,
		"BreakRescheduled"     to BreakRescheduled::class.java,
		"BreakPreempted"       to BreakPreempted::class.java,
		"EarlyStopRequested"   to EarlyStopRequested::class.java,
		"Misclick"             to Misclick::class.java,
		"SemanticMisclick"     to SemanticMisclick::class.java
	)

	/**
	 * Read [layout]'s `<sessionId>/session.log.jsonl`, scrub each event, and
	 * write the result to `<exportDir>/<sessionId>-export.jsonl`.
	 *
	 * @return path of the written export file.
	 */
	fun create(layout: LogDirLayout, sessionId: UUID): Path
	{
		val rawLog = layout.sessionDir(sessionId).resolve("session.log.jsonl")
		require(Files.exists(rawLog)) { "no raw log at $rawLog" }
		val out = layout.exportDir().resolve("$sessionId-export.jsonl")
		Files.newBufferedWriter(out).use { writer ->
			Files.lines(rawLog).use { lines ->
				lines.forEach { line ->
					val ev = deserialise(line)
					val scrubbed = PrivacyScrubber.scrub(ev)
					writer.write(mapper.writeValueAsString(scrubbed))
					writer.newLine()
				}
			}
		}
		return out
	}

	private fun deserialise(line: String): BusEvent
	{
		val tree = mapper.readTree(line)
		val typeName = tree.get("type")?.asText()
		val targetClass = typeName?.let { typeRegistry[it] }
			?: return mapper.treeToValue(tree, BusEvent::class.java)
		return mapper.treeToValue(tree, targetClass)
	}
}
