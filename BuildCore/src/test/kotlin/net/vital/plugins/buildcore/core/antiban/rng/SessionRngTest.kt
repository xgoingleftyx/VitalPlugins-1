package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SessionRngTest {

	@Test
	fun `fresh returns distinct seeds across calls`() {
		// Two fresh SessionRngs should have different seeds with overwhelming probability
		val a = SessionRng.fresh()
		val b = SessionRng.fresh()
		assertNotEquals(a.seed, b.seed)
	}

	@Test
	fun `fromSeed produces deterministic sequence`() {
		val a = SessionRng.fromSeed(1234L)
		val b = SessionRng.fromSeed(1234L)
		assertEquals(a.seed, b.seed)
		repeat(20) { assertEquals(a.nextLong(), b.nextLong()) }
	}
}
