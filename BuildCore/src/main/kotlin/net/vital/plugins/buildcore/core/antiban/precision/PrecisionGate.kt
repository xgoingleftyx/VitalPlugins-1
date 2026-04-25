package net.vital.plugins.buildcore.core.antiban.precision

import net.vital.plugins.buildcore.core.events.InputMode

/**
 * The single mode-dispatch point for input primitives. Every primitive
 * (Mouse.moveTo / click, Keyboard.tap / type, Camera.rotate / pitch) calls
 * [enter] at the top of its body to fetch the active [TimingProfile].
 *
 * The thread-local "scope marker" is set by [PrecisionWindow.withPrecision] /
 * [PrecisionWindow.withSurvival] and read here. If a primitive is invoked with
 * a non-NORMAL [InputMode] but no scope marker is on the stack, [enter] throws
 * — defense-in-depth on top of `PrecisionInputArchTest` (Konsist).
 *
 * Plan 4b spec §4.2.
 */
object PrecisionGate
{
	data class TimingProfile(
		val tightTimingFloor: Boolean,
		val fidgetEnabled: Boolean,
		val overshootEnabled: Boolean,
		val fatigueApplied: Boolean
	)

	private val NORMAL_PROFILE = TimingProfile(
		tightTimingFloor = false, fidgetEnabled = true, overshootEnabled = true, fatigueApplied = true
	)
	private val PRECISION_PROFILE = TimingProfile(
		tightTimingFloor = true,  fidgetEnabled = false, overshootEnabled = false, fatigueApplied = false
	)

	private val scopeDepth = ThreadLocal.withInitial { 0 }
	private val scopeMode  = ThreadLocal<InputMode?>()

	/** Wired by [net.vital.plugins.buildcore.core.antiban.breaks.BreakScheduler] at install. */
	@Volatile var preemptHook: (() -> Unit)? = null

	internal fun markEnterScope(mode: InputMode)
	{
		scopeDepth.set(scopeDepth.get() + 1)
		scopeMode.set(mode)
	}

	internal fun markExitScope()
	{
		val d = scopeDepth.get() - 1
		if (d <= 0)
		{
			scopeDepth.set(0)
			scopeMode.set(null)
		}
		else
		{
			scopeDepth.set(d)
		}
	}

	internal fun inScope(): Boolean = scopeDepth.get() > 0

	fun enter(mode: InputMode): TimingProfile
	{
		when (mode)
		{
			InputMode.NORMAL -> return NORMAL_PROFILE
			InputMode.PRECISION ->
			{
				check(inScope()) { "PRECISION call outside withPrecision/withSurvival scope" }
				return PRECISION_PROFILE
			}
			InputMode.SURVIVAL ->
			{
				check(inScope()) { "SURVIVAL call outside withPrecision/withSurvival scope" }
				preemptHook?.invoke()
				return PRECISION_PROFILE
			}
		}
	}

	internal fun resetForTests()
	{
		scopeDepth.set(0)
		scopeMode.set(null)
		preemptHook = null
	}
}
