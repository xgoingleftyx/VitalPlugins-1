package net.vital.plugins.buildcore.core.task

/**
 * Reference task: does nothing useful, but ticks through the full
 * state machine. Used by Plan 2's RunnerStateMachineTest and as a
 * smoke-test vehicle for Plan 3's logging/telemetry subscribers.
 *
 * The task "completes" after [tickBudget] successful [step] calls,
 * simulating a workload.
 *
 * Spec §6 (reference task goal).
 */
class NoOpTask(
	override val id: TaskId = TaskId("buildcore.noop"),
	override val displayName: String = "No-op Task",
	override val version: SemVer = SemVer(1, 0, 0),
	override val moduleId: ModuleId = ModuleId("buildcore.core"),
	private val tickBudget: Int = 3
) : Task {

	override val config: ConfigSchema = ConfigSchema.EMPTY

	override val methods: List<Method> = listOf(NoOpMethod)

	private var ticksTaken: Int = 0

	override fun validate(ctx: TaskContext): ValidationResult = ValidationResult.Pass

	override fun onStart(ctx: TaskContext) {
		ticksTaken = 0
	}

	override fun step(ctx: TaskContext): StepResult {
		ticksTaken += 1
		return if (ticksTaken >= tickBudget) StepResult.Complete else StepResult.Continue()
	}

	override fun isComplete(ctx: TaskContext): Boolean = ticksTaken >= tickBudget

	override fun safeStop(ctx: TaskContext) {
		// No-op task has nothing to clean up
	}

	override fun progressSignal(ctx: TaskContext): ProgressFingerprint =
		ProgressFingerprint(custom = mapOf("ticks" to ticksTaken.toString()))

	override fun canStopNow(ctx: TaskContext): Boolean = true
}

private object NoOpMethod : Method {
	override val id = MethodId("buildcore.noop.default")
	override val displayName = "Default"
	override val description = "Tick and complete"
	override val paths = listOf(
		ExecutionPath(
			id = PathId("buildcore.noop.default.ironman"),
			kind = PathKind.IRONMAN,
			estimatedRate = XpPerHour.ZERO
		)
	)
	override val requirements: Requirement? = null
	override val effects: Set<Effect> = emptySet()
	override val config = ConfigSchema.EMPTY
	override val locationFootprint: Set<AreaTag> = emptySet()
	override val risk = RiskProfile.NONE
	override fun estimatedRate(accountState: AccountState) = XpPerHour.ZERO
}
