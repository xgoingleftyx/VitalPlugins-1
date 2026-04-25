package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the Plan 6a confidence + watchdog layer.
 */
class ConfidenceArchitectureTest
{
	private fun servicePackageFiles() = Konsist.scopeFromProduction()
		.files
		.filter { it.path.contains("/core/services/") }

	@Test
	fun `every Service action method passes a stakes argument to withServiceCall`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("stakes ="))
				{ "${file.path}: every withServiceCall site must include `stakes = ActionStakes.X`" }
			}
	}

	@Test
	fun `vital_api imports outside the allowed list are forbidden`()
	{
		Konsist.scopeFromProduction()
			.files
			.filter { it.path.contains("/main/kotlin/") }
			.filter { it.text.contains("import vital.api") }
			.forEach { file ->
				val isAllowed = file.path.contains("/services/") && file.name.startsWith("VitalApi")
					|| file.name == "VitalApiGameStateProvider.kt"
				assert(isAllowed)
				{ "${file.path}: only VitalApi*Backend.kt or VitalApiGameStateProvider.kt may import vital.api.*" }
			}
	}
}
