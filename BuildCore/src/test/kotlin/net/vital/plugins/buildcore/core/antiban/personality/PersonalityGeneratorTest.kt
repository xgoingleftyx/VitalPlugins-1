package net.vital.plugins.buildcore.core.antiban.personality

import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import net.vital.plugins.buildcore.core.antiban.rng.PersonalityRng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalityGeneratorTest {

	@Test
	fun `generator produces all fields within spec ranges`() {
		// Use the first 100 seeds to survey the output space
		repeat(100) { seed ->
			val p = PersonalityGenerator.generate(JavaUtilRng(seed.toLong()))
			// Range validation happens in PersonalityVector.init; if we got here, it's valid.
			assertTrue(p.schemaVersion == 1)
		}
	}

	@Test
	fun `same rng seed produces identical personality`() {
		val a = PersonalityGenerator.generate(JavaUtilRng(42L))
		val b = PersonalityGenerator.generate(JavaUtilRng(42L))
		assertEquals(a, b)
	}

	@Test
	fun `personality for username is stable — chich always gets same result`() {
		val a = PersonalityGenerator.generate(PersonalityRng.forUsername("chich"))
		val b = PersonalityGenerator.generate(PersonalityRng.forUsername("chich"))
		assertEquals(a, b)
	}
}
