package net.vital.plugins.buildcore.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogConfigTest {

	@Test
	fun `defaults when no overrides present`() {
		val cfg = LogConfig.load(env = emptyMap(), sysprops = emptyMap())
		assertEquals(LogLevel.INFO, cfg.level)
		assertEquals(30, cfg.retentionSessions)
		assertEquals(10L * 1024 * 1024, cfg.rotationSizeBytes)
		assertEquals(300_000L, cfg.performanceSampleIntervalMillis)
	}

	@Test
	fun `env var overrides default log level`() {
		val cfg = LogConfig.load(env = mapOf("BUILDCORE_LOG_LEVEL" to "DEBUG"), sysprops = emptyMap())
		assertEquals(LogLevel.DEBUG, cfg.level)
	}

	@Test
	fun `sysprop beats env for log level`() {
		val cfg = LogConfig.load(
			env = mapOf("BUILDCORE_LOG_LEVEL" to "DEBUG"),
			sysprops = mapOf("buildcore.log.level" to "WARN")
		)
		assertEquals(LogLevel.WARN, cfg.level)
	}

	@Test
	fun `log root dir uses env when set`() {
		val cfg = LogConfig.load(env = mapOf("BUILDCORE_LOG_DIR" to "/tmp/bc-logs"), sysprops = emptyMap())
		assertEquals("/tmp/bc-logs", cfg.logRootDir.toString().replace('\\', '/'))
	}
}
