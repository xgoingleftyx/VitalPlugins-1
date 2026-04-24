package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the antiban layer. Each test cites the
 * foundation spec §9 or Plan 4a spec section it enforces.
 *
 * Spec §11 (Plan 4a).
 */
class AntibanArchitectureTest {

	/**
	 * Plan 4a spec §11 #1 — vital.api.input.* imports only in the
	 * three VitalApi*Backend.kt files. Everywhere else in BuildCore
	 * must go through Mouse/Keyboard/Camera singletons.
	 */
	@Test
	fun `vital api input imports only in VitalApi backend files`() {
		val allowedFiles = setOf(
			"VitalApiMouseBackend", "VitalApiKeyboardBackend", "VitalApiCameraBackend"
		)
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name !in allowedFiles }
			.assertFalse { file ->
				file.imports.any { it.name.startsWith("vital.api.input.") }
			}
	}

	/**
	 * Plan 4a spec §11 #2 — java.util.Random only in JavaUtilRng.kt.
	 * All other code uses the SeededRng interface so Plan 4c can wrap it.
	 */
	@Test
	fun `java util Random imported only in JavaUtilRng`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name != "JavaUtilRng" }
			.assertFalse { file ->
				file.imports.any { it.name == "java.util.Random" }
			}
	}

	/**
	 * Plan 4a spec §11 #3 — java.security.SecureRandom only in SessionRng.kt.
	 */
	@Test
	fun `SecureRandom imported only in SessionRng`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { it.packagee?.name?.startsWith("net.vital.plugins.buildcore") == true }
			.filter { it.name != "SessionRng" }
			.assertFalse { file ->
				file.imports.any { it.name == "java.security.SecureRandom" }
			}
	}

	/**
	 * Plan 4a spec §11 #4 — all PersonalityVector properties are `val`, none mutable.
	 */
	@Test
	fun `PersonalityVector properties are all val`() {
		Konsist
			.scopeFromProject()
			.classes()
			.filter { it.name == "PersonalityVector" }
			.flatMap { it.properties() }
			.assertFalse { prop -> prop.hasVarModifier }
	}

	/**
	 * Plan 4a spec §11 #5 — PersonalityVector has exactly 16 properties
	 * (schemaVersion + 15 dimensions). Adding a 17th is a conscious change
	 * that bumps this count.
	 */
	@Test
	fun `PersonalityVector has exactly 16 properties`() {
		val klass = Konsist
			.scopeFromProject()
			.classes()
			.first { it.name == "PersonalityVector" }
		val count = klass.properties().size
		val expected = 16
		if (count != expected) {
			throw AssertionError("PersonalityVector property count is $count, expected $expected — " +
				"adding/removing a dimension requires bumping this test and PersonalityVector.schemaVersion")
		}
	}

	/**
	 * Plan 4a spec §11 #6 — PersonalityVector property declaration order matches
	 * PersonalityGenerator.generate call order. Reordering silently changes every
	 * existing persisted personality.
	 *
	 * Enforcement: extract the property names in the order they appear in
	 * PersonalityVector, then compare with the assignment order parsed from
	 * PersonalityGenerator. Using file text scan because Konsist's call-expr
	 * inspection is heavy.
	 */
	@Test
	fun `PersonalityGenerator draw order matches PersonalityVector field order`() {
		val vectorClass = Konsist.scopeFromProject().classes()
			.first { it.name == "PersonalityVector" }
		val fieldOrder = vectorClass.properties()
			.map { it.name }
			.filter { it != "schemaVersion" }   // schemaVersion is not drawn

		val generatorFile = Konsist.scopeFromProject().files
			.first { it.name == "PersonalityGenerator" }
		val genBody = generatorFile.text
		// Extract assignments like "mouseSpeedCenter = rng..." in order
		val pattern = Regex("""(\w+)\s*=\s*(?:rng|BreakBias)""")
		val generatorOrder = pattern.findAll(genBody).map { it.groupValues[1] }.toList()

		if (fieldOrder != generatorOrder) {
			throw AssertionError(
				"PersonalityVector field order and PersonalityGenerator draw order must match.\n" +
				"Vector:    $fieldOrder\n" +
				"Generator: $generatorOrder"
			)
		}
	}

	/**
	 * Plan 4a spec §11 #7 — Mouse/Keyboard/Camera expose no public mutable state.
	 * All `var` fields must be `internal var` (visible to AntibanBootstrap + tests,
	 * invisible to consumers).
	 */
	@Test
	fun `Mouse Keyboard Camera have no public var fields`() {
		val targets = setOf("Mouse", "Keyboard", "Camera")
		Konsist
			.scopeFromProject()
			.objects()
			.filter { it.name in targets && it.resideInPackage("net.vital.plugins.buildcore.core.antiban.input..") }
			.flatMap { it.properties() }
			.filter { it.hasVarModifier }
			.assertTrue { prop -> prop.hasInternalModifier }
	}

	/**
	 * Plan 4a spec §11 #8 — all primitives are `suspend fun` because reaction
	 * delays are non-optional. A non-suspend primitive would bypass timing.
	 */
	@Test
	fun `Mouse Keyboard Camera primitive functions are all suspend`() {
		val targets = setOf("Mouse", "Keyboard", "Camera")
		Konsist
			.scopeFromProject()
			.objects()
			.filter { it.name in targets && it.resideInPackage("net.vital.plugins.buildcore.core.antiban.input..") }
			.flatMap { it.functions() }
			.filter { it.hasPublicOrDefaultModifier && !it.name.startsWith("emit") && !it.name.startsWith("reactionDelay") }
			.assertTrue { fn -> fn.hasSuspendModifier }
	}
}
