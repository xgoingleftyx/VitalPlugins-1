package net.vital.plugins.buildcore

import kotlinx.coroutines.runBlocking
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SubscriberRegistry
import net.vital.plugins.buildcore.core.logging.LocalJsonlWriter
import net.vital.plugins.buildcore.core.logging.LocalSummaryWriter
import net.vital.plugins.buildcore.core.logging.LogConfig
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.logging.LoggerScope
import net.vital.plugins.buildcore.core.logging.NoOpReplaySubscriber
import net.vital.plugins.buildcore.core.logging.NoOpTelemetrySubscriber
import net.vital.plugins.buildcore.core.logging.PerformanceAggregator
import net.vital.plugins.buildcore.core.logging.SessionManager
import net.vital.plugins.buildcore.core.antiban.AntibanBootstrap
import net.vital.plugins.buildcore.core.logging.UncaughtExceptionHandler

@PluginDescriptor(
	name = "BuildCore",
	description = "All-inclusive OSRS account builder foundation",
	tags = ["buildcore", "builder", "account", "framework"]
)
class BuildCorePlugin : Plugin() {

	internal lateinit var eventBus: EventBus
	private lateinit var loggerScope: LoggerScope
	private lateinit var sessionManager: SessionManager
	private lateinit var subscriberRegistry: SubscriberRegistry
	private lateinit var performanceAggregator: PerformanceAggregator

	override fun startUp() {
		val cfg = LogConfig.load()
		val layout = LogDirLayout(cfg.logRootDir)

		eventBus = EventBus()
		loggerScope = LoggerScope()
		sessionManager = SessionManager(
			bus = eventBus,
			loggerScope = loggerScope,
			layout = layout,
			retentionSessions = cfg.retentionSessions
		)
		val sessionDir = layout.sessionDir(sessionManager.sessionId)

		subscriberRegistry = SubscriberRegistry()
			.register(LocalJsonlWriter(sessionDir = sessionDir, capBytes = cfg.rotationSizeBytes))
			.register(LocalSummaryWriter(sessionDir = sessionDir, level = cfg.level, capBytes = cfg.summaryCapBytes))
			.register(NoOpTelemetrySubscriber { sessionManager.sessionId })
			.register(NoOpReplaySubscriber  { sessionManager.sessionId })
		subscriberRegistry.attachAll(eventBus, loggerScope)

		sessionManager.start()

		performanceAggregator = PerformanceAggregator(
			intervalMillis = cfg.performanceSampleIntervalMillis,
			sessionIdProvider = { sessionManager.sessionId }
		)
		performanceAggregator.start(eventBus)

		UncaughtExceptionHandler.install(eventBus) { sessionManager.sessionId }

		AntibanBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId },
			layout = layout
		)
	}

	override fun shutDown() {
		runBlocking {
			performanceAggregator.stop()
			sessionManager.requestStop()
			subscriberRegistry.drainAll()
		}
		loggerScope.close()
	}
}
