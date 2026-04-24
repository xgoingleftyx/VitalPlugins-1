package net.vital.plugins.buildcore.core.logging

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Process-level configuration for the logging subsystem.
 *
 * Loaded once at plugin startup via [load]. Precedence (highest first):
 * system properties → env vars → defaults.
 *
 * Spec §11.
 */
data class LogConfig(
	val level: LogLevel,
	val logRootDir: Path,
	val retentionSessions: Int,
	val rotationSizeBytes: Long,
	val performanceSampleIntervalMillis: Long,
	val summaryCapBytes: Long
) {
	companion object {
		fun load(
			env: Map<String, String> = System.getenv(),
			sysprops: Map<String, String> = System.getProperties().entries
				.associate { it.key.toString() to it.value.toString() }
		): LogConfig {
			fun pick(sysKey: String, envKey: String): String? =
				sysprops[sysKey] ?: env[envKey]

			return LogConfig(
				level = LogLevel.parse(pick("buildcore.log.level", "BUILDCORE_LOG_LEVEL")),
				logRootDir = pick("buildcore.log.dir", "BUILDCORE_LOG_DIR")
					?.let(Paths::get)
					?: Paths.get(System.getProperty("user.home"), ".vitalclient", "buildcore", "logs"),
				retentionSessions = pick("buildcore.log.retention", "BUILDCORE_LOG_RETENTION_SESSIONS")
					?.toIntOrNull() ?: 30,
				rotationSizeBytes = pick("buildcore.log.rotation.mb", "BUILDCORE_LOG_ROTATION_MB")
					?.toLongOrNull()?.let { it * 1024 * 1024 } ?: (10L * 1024 * 1024),
				performanceSampleIntervalMillis = pick("buildcore.perf.interval.ms", "BUILDCORE_PERF_INTERVAL_MS")
					?.toLongOrNull() ?: 300_000L,
				summaryCapBytes = 10L * 1024 * 1024
			)
		}
	}
}
