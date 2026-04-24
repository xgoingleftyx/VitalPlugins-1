package net.vital.plugins.buildcore.core.antiban.rng

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PersonalityRngTest {

	@Test
	fun `same username produces same sequence`() {
		val a = PersonalityRng.forUsername("chich")
		val b = PersonalityRng.forUsername("chich")
		repeat(20) { assertEquals(a.nextLong(), b.nextLong()) }
	}

	@Test
	fun `username is case-insensitive`() {
		val lower = PersonalityRng.forUsername("chich")
		val upper = PersonalityRng.forUsername("CHICH")
		val mixed = PersonalityRng.forUsername("Chich")
		repeat(20) {
			val expected = lower.nextLong()
			assertEquals(expected, upper.nextLong())
			assertEquals(expected, mixed.nextLong())
		}
	}
}
