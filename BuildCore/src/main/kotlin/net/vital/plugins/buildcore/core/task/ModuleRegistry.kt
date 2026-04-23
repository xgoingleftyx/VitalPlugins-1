package net.vital.plugins.buildcore.core.task

/**
 * Plan 2's simplest possible module registry.
 *
 * Holds all currently-registered [Task] implementations in memory.
 * Plan 7's plan-loading validator consults this registry to resolve
 * taskId strings in Plans.
 *
 * No hot-loading, no classpath scanning — explicit register() calls
 * only. Plan 7 may extend this with an annotation-processor-generated
 * auto-register hook.
 *
 * Thread-safety: intended to be populated at startup before the Runner
 * begins. Once registered, tasks are read-only.
 */
class ModuleRegistry {

	private val tasks = mutableMapOf<TaskId, Task>()

	fun register(task: Task): ModuleRegistry {
		val validation = task.validateStructure()
		require(validation is ValidationResult.Pass) {
			"Cannot register task '${task.id}': $validation"
		}
		require(task.id !in tasks) {
			"Task '${task.id}' is already registered"
		}
		tasks[task.id] = task
		return this
	}

	fun unregisterAll() {
		tasks.clear()
	}

	fun findById(id: TaskId): Task? = tasks[id]

	fun all(): List<Task> = tasks.values.toList()

	fun size(): Int = tasks.size
}
