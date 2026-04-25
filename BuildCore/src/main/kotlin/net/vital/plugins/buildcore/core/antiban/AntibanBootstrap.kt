package net.vital.plugins.buildcore.core.antiban

import net.vital.plugins.buildcore.core.antiban.breaks.BreakConfigStore
import net.vital.plugins.buildcore.core.antiban.breaks.BreakScheduler
import net.vital.plugins.buildcore.core.antiban.input.Camera
import net.vital.plugins.buildcore.core.antiban.input.Keyboard
import net.vital.plugins.buildcore.core.antiban.input.Mouse
import net.vital.plugins.buildcore.core.antiban.input.VitalApiCameraBackend
import net.vital.plugins.buildcore.core.antiban.input.VitalApiKeyboardBackend
import net.vital.plugins.buildcore.core.antiban.input.VitalApiMouseBackend
import net.vital.plugins.buildcore.core.antiban.misclick.MisclickPolicy
import net.vital.plugins.buildcore.core.antiban.misclick.SemanticMisclickHook
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityProvider
import net.vital.plugins.buildcore.core.antiban.personality.PersonalityStore
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionGate
import net.vital.plugins.buildcore.core.antiban.precision.PrecisionWindow
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.antiban.timing.FatigueCurve
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.logging.LogDirLayout
import net.vital.plugins.buildcore.core.task.GraduatedThrottle
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.TaskContext
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Plugin-bootstrap-time wiring for the antiban layer.
 *
 * Called exactly once from `BuildCorePlugin.startUp()` after
 * `UncaughtExceptionHandler.install`. Subsequent calls are no-ops.
 *
 * Spec §4.1.
 */
object AntibanBootstrap {

	private val installed = AtomicBoolean(false)

	@Volatile var personalityProvider: PersonalityProvider? = null
		private set

	@Volatile var sessionRng: SessionRng? = null
		private set

	@Volatile var fatigue: FatigueCurve? = null
		private set

	@Volatile var throttle: GraduatedThrottle = GraduatedThrottle(
		accountAgeDays = 999,
		totalXp = Long.MAX_VALUE
	)
		private set

	@Volatile var breakScheduler: BreakScheduler? = null
		private set

	private val activeTaskRef        = AtomicReference<Task?>(null)
	private val activeTaskContextRef = AtomicReference<TaskContext?>(null)

	/** Called by Runner (Plan 6) when a task starts/stops. */
	fun setActiveTask(task: Task?, ctx: TaskContext?)
	{
		activeTaskRef.set(task)
		activeTaskContextRef.set(ctx)
	}

	fun install(
		bus: EventBus,
		sessionIdProvider: () -> UUID,
		layout: LogDirLayout,
		clock: Clock = Clock.systemUTC()
	) {
		if (!installed.compareAndSet(false, true)) return

		val store = PersonalityStore(layout.personalityDir())
		val provider = PersonalityProvider(store, bus, sessionIdProvider)
		val rng = SessionRng.fresh()
		val fatigueCurve = FatigueCurve(
			sessionStart = clock.instant(),
			clock = clock,
			bus = bus,
			sessionIdProvider = sessionIdProvider
		)

		bus.tryEmit(SessionRngSeeded(sessionId = sessionIdProvider(), seed = rng.seed))

		personalityProvider = provider
		sessionRng = rng
		fatigue = fatigueCurve

		// Wire primitives. Backends remain the VitalApi defaults installed at object
		// construction — plugin startUp never needs to swap them.
		Mouse.backend = VitalApiMouseBackend
		Mouse.personalityProvider = provider
		Mouse.sessionRng = rng
		Mouse.fatigue = fatigueCurve
		Mouse.throttle = throttle
		Mouse.bus = bus
		Mouse.sessionIdProvider = sessionIdProvider

		Keyboard.backend = VitalApiKeyboardBackend
		Keyboard.personalityProvider = provider
		Keyboard.sessionRng = rng
		Keyboard.fatigue = fatigueCurve
		Keyboard.throttle = throttle
		Keyboard.bus = bus
		Keyboard.sessionIdProvider = sessionIdProvider

		Camera.backend = VitalApiCameraBackend
		Camera.personalityProvider = provider
		Camera.sessionRng = rng
		Camera.fatigue = fatigueCurve
		Camera.throttle = throttle
		Camera.bus = bus
		Camera.sessionIdProvider = sessionIdProvider

		// Plan 4b: precision-window observer
		PrecisionWindow.bus = bus
		PrecisionWindow.sessionIdProvider = sessionIdProvider

		// Plan 4b: misclick policy
		MisclickPolicy.bus = bus
		MisclickPolicy.sessionIdProvider = sessionIdProvider

		// Plan 4b: semantic-misclick hook
		SemanticMisclickHook.bus = bus
		SemanticMisclickHook.sessionIdProvider = sessionIdProvider
		SemanticMisclickHook.personalityProvider = { provider.currentPersonality(rng) }
		SemanticMisclickHook.fatigueProvider = { fatigueCurve }
		SemanticMisclickHook.rngProvider = { rng }

		// Plan 4b: break scheduler — config loaded from layout.breakConfigDir().
		// taskProvider/taskContextProvider come in from the Runner once Plan 6 wires it; for now,
		// they default to no-op (canStopNow=true), so all breaks fire immediately. Plan 6 replaces.
		val breakConfig = BreakConfigStore(layout.breakConfigDir()).load()
		val scheduler = BreakScheduler(
			bus = bus,
			sessionIdProvider = sessionIdProvider,
			taskProvider = { activeTaskRef.get() },
			config = breakConfig,
			rng = rng,
			taskContextProvider = { activeTaskContextRef.get() }
		)
		breakScheduler = scheduler
		PrecisionGate.preemptHook = { scheduler.preempt() }

		// The scheduler coroutine itself is launched by BuildCorePlugin from its CoroutineScope —
		// keep the reference here so the plugin can call schedulerJob = scope.launch { scheduler.run() }.
	}

	fun installThrottle(newThrottle: GraduatedThrottle) {
		throttle = newThrottle
		Mouse.throttle = newThrottle
		Keyboard.throttle = newThrottle
		Camera.throttle = newThrottle
	}

	// Test-only helper. Plan 4a's tests substitute fakes via @BeforeEach;
	// this resets installed state so a fresh install can run.
	internal fun resetForTests()
	{
		installed.set(false)
		personalityProvider = null
		sessionRng = null
		fatigue = null
		breakScheduler = null
		activeTaskRef.set(null)
		activeTaskContextRef.set(null)
		PrecisionGate.resetForTests()
		MisclickPolicy.resetForTests()
	}
}
