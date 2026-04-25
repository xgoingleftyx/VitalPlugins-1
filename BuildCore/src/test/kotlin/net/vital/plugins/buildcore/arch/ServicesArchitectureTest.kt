package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test

/**
 * Architecture invariants for the L5 service layer.
 *
 * Plan 5a spec §7.
 */
class ServicesArchitectureTest
{
	private fun servicePackageFiles() = Konsist.scopeFromProduction()
		.files
		.filter { it.path.contains("/core/services/") }

	@Test
	fun `every Service file declares an object`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("object "))
				{ "${file.path}: a *Service.kt file must declare an object" }
			}
	}

	@Test
	fun `every Service file declares Volatile internal var backend`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("@Volatile internal var backend"))
				{ "${file.path}: must declare `@Volatile internal var backend`" }
			}
	}

	@Test
	fun `every Service file body uses withServiceCall`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") && it.name != "ServiceBootstrap.kt" }
			.forEach { file ->
				assert(file.text.contains("withServiceCall("))
				{ "${file.path}: must invoke withServiceCall" }
			}
	}

	@Test
	fun `Service files do not import vital_api directly`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") }
			.forEach { file ->
				assert(!file.text.contains("import vital.api"))
				{ "${file.path}: only VitalApi*Backend.kt files may import vital.api.*" }
			}
	}

	@Test
	fun `Service files do not import vital_api_input`()
	{
		servicePackageFiles()
			.filter { it.name.endsWith("Service.kt") }
			.forEach { file ->
				assert(!file.text.contains("import vital.api.input"))
				{ "${file.path}: must not import vital.api.input.* (route input through Plan 4a primitives)" }
			}
	}
}
