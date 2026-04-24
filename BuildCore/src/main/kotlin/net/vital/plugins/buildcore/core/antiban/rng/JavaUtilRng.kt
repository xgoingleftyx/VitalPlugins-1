package net.vital.plugins.buildcore.core.antiban.rng

/**
 * The one and only [SeededRng] implementation in BuildCore. Architecture
 * test #2 forbids `java.util.Random` imports outside this file.
 *
 * Spec §5.2.
 */
internal class JavaUtilRng(seed: Long) : SeededRng {

	private val rng = java.util.Random(seed)

	override fun nextLong(): Long = rng.nextLong()
	override fun nextInt(): Int = rng.nextInt()
	override fun nextIntInRange(from: Int, until: Int): Int {
		require(until > from) { "until ($until) must be > from ($from)" }
		return from + rng.nextInt(until - from)
	}
	override fun nextDouble(): Double = rng.nextDouble()
	override fun nextDoubleInRange(from: Double, until: Double): Double {
		require(until > from) { "until ($until) must be > from ($from)" }
		return from + rng.nextDouble() * (until - from)
	}
	override fun nextGaussian(): Double = rng.nextGaussian()
	override fun nextLogNormal(mu: Double, sigma: Double): Double =
		kotlin.math.exp(mu + sigma * rng.nextGaussian())
	override fun nextBoolean(p: Double): Boolean = rng.nextDouble() < p
	override fun <T> shuffled(list: List<T>): List<T> = list.shuffled(rng)
}
