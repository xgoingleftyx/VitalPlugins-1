package net.vital.plugins.buildcore.core.antiban.rng

/**
 * The single RNG abstraction used by the antiban layer.
 *
 * Plan 4a provides exactly one implementation ([JavaUtilRng]). Plan 4c
 * will wrap this interface with a recording decorator so every draw can
 * be replayed from a recorded seed + draw log.
 *
 * Spec §5.
 */
interface SeededRng {
	fun nextLong(): Long
	fun nextInt(): Int
	fun nextIntInRange(from: Int, until: Int): Int
	fun nextDouble(): Double
	fun nextDoubleInRange(from: Double, until: Double): Double
	fun nextGaussian(): Double
	fun nextLogNormal(mu: Double, sigma: Double): Double
	fun nextBoolean(p: Double): Boolean
	fun <T> shuffled(list: List<T>): List<T>
}
