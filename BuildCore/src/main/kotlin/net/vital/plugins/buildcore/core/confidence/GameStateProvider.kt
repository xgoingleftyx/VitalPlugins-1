package net.vital.plugins.buildcore.core.confidence

/**
 * Abstraction over VitalAPI reads needed by [ConfidenceTracker]. Default
 * impl ([VitalApiGameStateProvider]) calls `vital.api.*` directly. Tests
 * swap to a fake. Plan 6a spec §4.5.
 */
interface GameStateProvider
{
	/** Returns the open widget id, or null if none. */
	fun openWidgetId(): Int?

	/** True if any dialog widget is currently visible. */
	fun isDialogVisible(): Boolean

	/** Current HP / Max HP ratio in [0.0, 1.0]; null if unavailable. */
	fun hpRatio(): Double?

	/** Player tile X (world coordinates); null if unavailable. */
	fun playerTileX(): Int?

	/** Player tile Y; null if unavailable. */
	fun playerTileY(): Int?

	/** Count of NPCs in scene matching the given name; 0 if none. */
	fun npcCountByName(name: String): Int

	/** Count of TileObjects in scene matching the given name; 0 if none. */
	fun objectCountByName(name: String): Int

	/** Inventory count for the given itemId. */
	fun inventoryCountById(itemId: Int): Int
}

/**
 * Default impl. Reads VitalAPI statics. Returns null/0 when VitalAPI is
 * unavailable (e.g. in unit tests when not swapped — but tests should swap).
 */
object VitalApiGameStateProvider : GameStateProvider
{
	// TODO 6b: wire when VitalAPI exposes Widgets.getOpenWidgetId()
	override fun openWidgetId(): Int? = null

	// TODO 6b: wire when VitalAPI exposes Widgets.isDialogVisible()
	override fun isDialogVisible(): Boolean = false

	// TODO 6b: wire when VitalAPI exposes Player health/maxHealth
	override fun hpRatio(): Double? = null

	override fun playerTileX(): Int? = runCatchingOrNull { vital.api.entities.Players.getLocal()?.getTileX() }

	override fun playerTileY(): Int? = runCatchingOrNull { vital.api.entities.Players.getLocal()?.getTileY() }

	// TODO 6b: wire when VitalAPI exposes Npcs.getAll(name)
	override fun npcCountByName(name: String): Int = 0

	override fun objectCountByName(name: String): Int = runCatchingOrNull {
		vital.api.entities.TileObjects.getAll()
			.count { runCatchingOrNull { it.toString().contains(name) } == true }
	} ?: 0

	override fun inventoryCountById(itemId: Int): Int = runCatchingOrNull {
		vital.api.containers.Inventory.getCount(itemId)
	} ?: 0

	private inline fun <T> runCatchingOrNull(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }
}
