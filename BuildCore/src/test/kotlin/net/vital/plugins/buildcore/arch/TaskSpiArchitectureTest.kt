package net.vital.plugins.buildcore.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import net.vital.plugins.buildcore.core.task.Method
import net.vital.plugins.buildcore.core.task.ModuleRegistry
import net.vital.plugins.buildcore.core.task.NoOpTask
import net.vital.plugins.buildcore.core.task.Task
import net.vital.plugins.buildcore.core.task.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Architecture tests for the Task SPI.
 *
 * Spec §16. Guardrails for future edits.
 */
class TaskSpiArchitectureTest {

	/**
	 * Spec §7: every concrete Method must have exactly one IRONMAN path
	 * with no gatingRestrictions. ModuleRegistry enforces this at
	 * register time; this test asserts the enforcement machinery works
	 * for the known-good NoOpTask.
	 */
	@Test
	fun `NoOpTask passes structural validation`() {
		val task = NoOpTask()
		val result = task.validateStructure()
		assertEquals(ValidationResult.Pass, result)
	}

	/**
	 * Spec §16: ModuleRegistry refuses to register a task whose
	 * structural validation fails.
	 */
	@Test
	fun `ModuleRegistry rejects task with zero methods`() {
		val badTask = object : Task {
			override val id = net.vital.plugins.buildcore.core.task.TaskId("bad")
			override val displayName = "bad"
			override val version = net.vital.plugins.buildcore.core.task.SemVer(0, 0, 1)
			override val moduleId = net.vital.plugins.buildcore.core.task.ModuleId("test")
			override val config = net.vital.plugins.buildcore.core.task.ConfigSchema.EMPTY
			override val methods: List<Method> = emptyList()
			override fun validate(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = ValidationResult.Pass
			override fun onStart(ctx: net.vital.plugins.buildcore.core.task.TaskContext) {}
			override fun step(ctx: net.vital.plugins.buildcore.core.task.TaskContext) =
				net.vital.plugins.buildcore.core.task.StepResult.Complete
			override fun isComplete(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = true
			override fun safeStop(ctx: net.vital.plugins.buildcore.core.task.TaskContext) {}
			override fun progressSignal(ctx: net.vital.plugins.buildcore.core.task.TaskContext) =
				net.vital.plugins.buildcore.core.task.ProgressFingerprint.EMPTY
			override fun canStopNow(ctx: net.vital.plugins.buildcore.core.task.TaskContext) = true
		}
		val registry = ModuleRegistry()
		val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
			registry.register(badTask)
		}
		assertEquals(true, ex.message!!.contains("at least one method"))
	}

	/**
	 * Spec §16: Task sealed-interface-ish discipline. Concrete Task
	 * implementations must not be `object` singletons (would imply
	 * shared mutable state across sessions) and must not expose
	 * public `var` properties (mutable state outside step() is a smell).
	 *
	 * For Plan 2 we only assert the NoOpTask's properties are declared
	 * using `val` — future plan additions extend this rule.
	 */
	@Test
	fun `Task implementations do not expose public var properties`() {
		Konsist
			.scopeFromProject()
			.classes()
			.filter { it.resideInPackage("net.vital.plugins.buildcore.core.task..") }
			.filter { klass -> klass.parents().any { it.name == "Task" } }
			.assertTrue { klass ->
				klass.properties().none { prop ->
					prop.hasPublicOrDefaultModifier && !prop.hasValModifier
				}
			}
	}

	/**
	 * Spec §16: no class outside `core.task` may import `Runner` directly.
	 * The Runner is an internal driver; other code interacts with tasks
	 * via the Task interface, not the Runner.
	 *
	 * Plan 7 will tighten this further; Plan 2 only enforces the
	 * package boundary.
	 */
	@Test
	fun `Runner is only used inside core-task package`() {
		Konsist
			.scopeFromProject()
			.files
			.filter { file ->
				file.imports.any { imp ->
					imp.name == "net.vital.plugins.buildcore.core.task.Runner"
				}
			}
			.assertTrue { file ->
				file.packagee?.name?.startsWith("net.vital.plugins.buildcore.core.task") == true
					|| file.packagee?.name?.startsWith("net.vital.plugins.buildcore.arch") == true
			}
	}
}
