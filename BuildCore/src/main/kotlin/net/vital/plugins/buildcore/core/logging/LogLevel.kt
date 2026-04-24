package net.vital.plugins.buildcore.core.logging

import net.vital.plugins.buildcore.core.events.BusEvent
import net.vital.plugins.buildcore.core.events.FatigueUpdated
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.events.RestrictionViolated
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
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

/**
 * Severity levels for [LocalSummaryWriter] filtering.
 *
 * Applies only to the human-readable summary log. The JSONL event file
 * always receives every event regardless of level.
 *
 * Spec §11.1.
 */
enum class LogLevel {
	DEBUG, INFO, WARN, ERROR, FATAL;

	companion object {
		fun parse(raw: String?): LogLevel = raw?.uppercase()?.let {
			entries.firstOrNull { lvl -> lvl.name == it }
		} ?: INFO
	}
}

/**
 * Severity of a given event for human-log purposes.
 *
 * Any subtype not listed here is treated as [LogLevel.DEBUG] — a
 * conservative default. The architecture test for scrubber
 * exhaustiveness parallels this table; add an entry when a new
 * subtype lands.
 */
fun levelOf(event: BusEvent): LogLevel = when (event) {
	is SessionStart, is SessionEnd, is SessionSummary -> LogLevel.INFO
	is TaskQueued, is TaskValidated, is TaskStarted,
	is TaskCompleted, is TaskResumed -> LogLevel.INFO
	is SafeStopRequested, is SafeStopCompleted -> LogLevel.INFO
	is TaskRetrying, is TaskSkipped, is TaskPaused -> LogLevel.WARN
	is ValidationFailed, is RestrictionViolated -> LogLevel.WARN
	is TaskFailed -> LogLevel.ERROR
	is UnhandledException -> LogLevel.FATAL
	is InputAction, is FatigueUpdated,
	is PersonalityResolved, is SessionRngSeeded -> LogLevel.DEBUG
	else -> LogLevel.DEBUG
}
