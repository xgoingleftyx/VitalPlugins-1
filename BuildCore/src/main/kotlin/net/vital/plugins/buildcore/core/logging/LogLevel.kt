package net.vital.plugins.buildcore.core.logging

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
			values().firstOrNull { lvl -> lvl.name == it }
		} ?: INFO
	}
}
