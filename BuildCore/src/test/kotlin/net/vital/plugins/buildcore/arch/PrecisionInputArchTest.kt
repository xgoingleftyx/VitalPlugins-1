package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures that any function which calls [withPrecision] or [withSurvival]
 * is annotated `@UsesPrecisionInput`, and that `PrecisionGate.enter` is
 * only invoked from within Mouse/Keyboard/Camera or AntibanBootstrap.
 *
 * Text-based scan (not full call-graph) — comments containing the trigger
 * strings are a known false-positive risk but acceptable given the small
 * surface area. Plan 4b spec §4.4.
 */
class PrecisionInputArchTest
{
	/**
	 * Any function whose body contains a call to `withPrecision(` or
	 * `withSurvival(` must be annotated `@UsesPrecisionInput`.
	 *
	 * Builders (`withPrecision`, `withSurvival`, `runScope`) are exempt
	 * because they *are* the implementation, not callers.
	 */
	@Test
	fun `non-NORMAL precision callers must be annotated UsesPrecisionInput`()
	{
		val triggers = setOf("withPrecision", "withSurvival")

		Konsist.scopeFromProduction()
			.functions(includeNested = true, includeLocal = true)
			.filter { f ->
				f.text.let { src -> triggers.any { src.contains("$it(") } }
			}
			.filterNot { f ->
				f.name == "withPrecision" || f.name == "withSurvival" || f.name == "runScope"
			}
			.assertTrue(
				testName = "Function calling withPrecision/withSurvival must be @UsesPrecisionInput"
			) { f ->
				f.hasAnnotation { it.name == "UsesPrecisionInput" }
			}
	}

	/**
	 * `PrecisionGate.enter` may only be called from files inside
	 * the `precision/` or `input/` packages, or from `AntibanBootstrap`.
	 *
	 * Simple file-text scan; path segments matched against the source
	 * file path so Windows and Unix separators both work.
	 */
	@Test
	fun `PrecisionGate is the only direct caller of mode-dispatch logic outside its own package`()
	{
		Konsist.scopeFromProduction()
			.files
			.filter { it.path.contains("/main/") || it.path.contains("\\main\\") }
			.filterNot { f ->
				f.path.contains("precision") ||
				f.path.contains("input") ||
				f.name.contains("AntibanBootstrap")
			}
			.forEach { file ->
				assert(!file.text.contains("PrecisionGate.enter"))
				{
					"${file.path} calls PrecisionGate.enter directly — " +
					"only Mouse/Keyboard/Camera/AntibanBootstrap may do so"
				}
			}
	}
}
