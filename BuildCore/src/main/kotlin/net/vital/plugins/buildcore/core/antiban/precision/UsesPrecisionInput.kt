package net.vital.plugins.buildcore.core.antiban.precision

/**
 * Marks a function that legitimately enters PRECISION or SURVIVAL input mode.
 *
 * Required (transitively, up the call chain) on any function that calls
 * [withPrecision], [withSurvival], or [PrecisionGate.enter] with a non-NORMAL
 * mode. Enforced by `PrecisionInputArchTest` (Konsist).
 *
 * Plan 4b spec §4.4.
 */
@MustBeDocumented
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class UsesPrecisionInput
