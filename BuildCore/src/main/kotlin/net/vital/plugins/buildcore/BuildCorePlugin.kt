package net.vital.plugins.buildcore

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.vital.plugins.buildcore.core.confidence.ConfidenceBootstrap
import net.vital.plugins.buildcore.core.confidence.ConfidenceSubscriber
import net.vital.plugins.buildcore.core.confidence.watchdog.Watchdog
import net.vital.plugins.buildcore.core.confidence.watchdog.WatchdogScope
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.DeadlockCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.LeakCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.StallCheck
import net.vital.plugins.buildcore.core.confidence.watchdog.checks.UncertainCheck
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
import net.vital.plugins.buildcore.core.services.ServiceBootstrap

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
	private var breakSchedulerJob: Job? = null
	private lateinit var watchdogScope: WatchdogScope
	private var watchdogJob: Job? = null

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
			.register(ConfidenceSubscriber  { sessionManager.sessionId })
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

		AntibanBootstrap.breakScheduler?.let { scheduler ->
			breakSchedulerJob = loggerScope.coroutineScope.launch { scheduler.run() }
		}

		ServiceBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId }
		)

		ConfidenceBootstrap.install(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId }
			// taskProvider defaults to null; updated when a Runner is alive (deferred)
		)

		watchdogScope = WatchdogScope()
		val checks = listOf(
			StallCheck(taskProvider = { null to null }),
			UncertainCheck(),
			DeadlockCheck(heartbeatProvider = { null }),
			LeakCheck()
		)
		val watchdog = Watchdog(
			bus = eventBus,
			sessionIdProvider = { sessionManager.sessionId },
			checks = checks
		)
		watchdogJob = watchdogScope.coroutineScope.launch { watchdog.run() }
	}

	override fun shutDown() {
		runBlocking {
			watchdogJob?.cancelAndJoin()
			breakSchedulerJob?.cancelAndJoin()
			performanceAggregator.stop()
			sessionManager.requestStop()
			subscriberRegistry.drainAll()
		}
		watchdogScope.close()
		loggerScope.close()
	}
}
