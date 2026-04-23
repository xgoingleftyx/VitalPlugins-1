package net.vital.plugins.buildcore.core.task

/**
 * Schema describing a task's or method's configurable fields.
 *
 * Rendered automatically by Plan 10's GUI. Task authors never write
 * Swing — they declare their config fields here and the single GUI
 * renderer produces the form.
 *
 * Spec §7.
 */
data class ConfigSchema(val fields: List<ConfigField>) {
	companion object {
		/** Empty schema for tasks/methods with no configurable fields. */
		val EMPTY: ConfigSchema = ConfigSchema(emptyList())
	}
}

sealed class ConfigField {
	abstract val key: String
	abstract val label: String

	data class IntRange(
		override val key: String,
		override val label: String,
		val min: Int,
		val max: Int,
		val default: Int
	) : ConfigField() {
		init {
			require(max >= min) { "IntRange '$key': max must be >= min" }
			require(default in min..max) { "IntRange '$key': default $default not in [$min, $max]" }
		}
	}

	data class Toggle(
		override val key: String,
		override val label: String,
		val default: Boolean
	) : ConfigField()

	data class ItemPicker(
		override val key: String,
		override val label: String,
		val filter: ItemFilter = ItemFilter.ANY
	) : ConfigField()

	data class LocationPicker(
		override val key: String,
		override val label: String,
		val presets: List<LocationPreset> = emptyList()
	) : ConfigField()

	data class Enum(
		override val key: String,
		override val label: String,
		val options: List<String>,
		val default: String
	) : ConfigField() {
		init {
			require(options.isNotEmpty()) { "Enum '$key': options must not be empty" }
			require(default in options) { "Enum '$key': default '$default' not in $options" }
		}
	}

	data class CompletionPredicate(
		override val key: String,
		override val label: String,
		val defaults: List<PredicateTemplate> = emptyList()
	) : ConfigField()

	/**
	 * Wrapper that reveals [inner] only when the profile's current config
	 * satisfies [conditionKey] [conditionValue]. Used to hide dependent
	 * fields until their parent field is set.
	 */
	data class VisibleWhen(
		val conditionKey: String,
		val conditionValue: Any,
		val inner: ConfigField
	) : ConfigField() {
		override val key: String get() = inner.key
		override val label: String get() = inner.label
	}
}

/** What kind of items an ItemPicker presents. */
enum class ItemFilter { ANY, FOOD, POTION, WEAPON, ARMOR, TOOL, RESOURCE }

data class LocationPreset(val label: String, val worldX: Int, val worldY: Int, val plane: Int)

/** Predicate templates populated by Plan 2 with common options; expanded later. */
enum class PredicateTemplate {
	UNTIL_LEVEL,
	UNTIL_QUEST_COMPLETE,
	UNTIL_ITEM_QUANTITY,
	UNTIL_TIME_ELAPSED,
	UNTIL_XP_GAINED
}
