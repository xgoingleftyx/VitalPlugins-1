package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp

class JavaUtilRngTest {

	@Test
	fun `same seed produces same sequence`() {
		val a = JavaUtilRng(42L)
		val b = JavaUtilRng(42L)
		repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
	}

	@Test
	fun `nextIntInRange is within half-open bounds`() {
		val rng = JavaUtilRng(1L)
		repeat(1000) {
			val v = rng.nextIntInRange(10, 20)
			assertTrue(v in 10..19, "got $v")
		}
	}

	@Test
	fun `nextLogNormal produces positive values in expected neighbourhood`() {
		val rng = JavaUtilRng(1L)
		val mu = 6.0
		val sigma = 0.4
		val theoreticalMedian = exp(mu)
		val samples = List(1000) { rng.nextLogNormal(mu, sigma) }
		val sortedMedian = samples.sorted()[500]
		assertTrue(samples.all { it > 0.0 })
		assertTrue(abs(sortedMedian - theoreticalMedian) / theoreticalMedian < 0.25,
			"sample median $sortedMedian not within 25% of theoretical $theoreticalMedian")
	}
}
