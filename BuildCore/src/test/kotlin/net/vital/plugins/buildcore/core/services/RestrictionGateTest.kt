package net.vital.plugins.buildcore.core.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RestrictionGateTest
{
	@BeforeEach
	fun reset()
	{
		RestrictionGate.engine = null
	}

	@Test
	fun `null engine is pass-through`()
	{
		RestrictionGate.check(OperationalRestriction.BANK_DISABLED)   // must not throw
	}

	@Test
	fun `engine present + restriction in deny set throws RestrictionViolation`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.BANK_DISABLED))
		val ex = assertThrows(RestrictionViolation::class.java) {
			RestrictionGate.check(OperationalRestriction.BANK_DISABLED)
		}
		assertEquals(OperationalRestriction.BANK_DISABLED, ex.restriction)
	}

	@Test
	fun `engine present + restriction not in deny set is pass-through`()
	{
		RestrictionGate.engine = StaticRestrictionEngine(setOf(OperationalRestriction.GRAND_EXCHANGE_DISABLED))
		RestrictionGate.check(OperationalRestriction.BANK_DISABLED)   // must not throw
	}
}
