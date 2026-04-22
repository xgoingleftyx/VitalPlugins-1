package net.vital.plugins.buildcore

import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.vital.plugins.buildcore.core.events.EventBus

/**
 * BuildCore RuneLite plugin entry point.
 *
 * Lifecycle is owned by VitalShell's plugin manager. Plan 1 only
 * establishes startup/shutdown paths — Plan 2 wires the task runner,
 * Plan 10 wires the full GUI launch.
 *
 * The [eventBus] field is exposed as package-visible so early
 * subsystems can share a single bus instance for their integration
 * tests. Plan 2 replaces this with a Guice-provided binding.
 */
@PluginDescriptor(
	name = "BuildCore",
	description = "All-inclusive OSRS account builder foundation",
	tags = ["buildcore", "builder", "account", "framework"]
)
class BuildCorePlugin : Plugin() {

	internal val eventBus: EventBus = EventBus()

	override fun startUp() {
		log("BuildCore plugin starting — v${version()}")
	}

	override fun shutDown() {
		log("BuildCore plugin shutting down")
	}

	private fun version(): String = javaClass.`package`?.implementationVersion ?: "dev"

	private fun log(message: String) {
		// Plan 3 replaces with structured event emission. For now, stderr
		// so we can see it in the VitalClient console on first load.
		System.err.println("[BuildCore] $message")
	}
}
