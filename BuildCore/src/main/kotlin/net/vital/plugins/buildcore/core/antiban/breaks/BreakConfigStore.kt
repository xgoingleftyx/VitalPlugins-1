package net.vital.plugins.buildcore.core.antiban.breaks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import net.vital.plugins.buildcore.core.events.BreakTier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and persists [BreakConfig] as JSON. Atomic write via temp-file +
 * `Files.move(REPLACE_EXISTING)`. Missing tiers in the loaded file fall back
 * to [BreakConfig.defaults] for forward compatibility.
 *
 * Plan 4b spec §5.2.
 */
class BreakConfigStore(private val dir: Path)
{
	private val mapper = ObjectMapper().registerModule(kotlinModule())

	private val file: Path get() = dir.resolve("breaks.json")

	fun load(): BreakConfig
	{
		if (!Files.exists(file)) return BreakConfig.defaults()
		val raw = mapper.readValue<BreakConfigDto>(file.toFile())
		val merged = BreakTier.values().associateWith { tier ->
			val dto = raw.tiers[tier.name]
			if (dto != null)
				TierConfig(
					enabled          = dto.enabled,
					durationRangeMs  = dto.durStart..dto.durEnd,
					intervalRangeMs  = dto.ivStart..dto.ivEnd,
					maxDeferMs       = dto.maxDeferMs,
					onDeferTimeout   = dto.onDeferTimeout
				)
			else
				BreakConfig.defaults().tiers[tier]!!
		}
		return BreakConfig(tiers = merged)
	}

	fun save(cfg: BreakConfig)
	{
		Files.createDirectories(dir)
		val dtoMap = cfg.tiers.entries.associate { (tier, tc) ->
			tier.name to TierConfigDto(
				enabled         = tc.enabled,
				durStart        = tc.durationRangeMs.first,
				durEnd          = tc.durationRangeMs.last,
				ivStart         = tc.intervalRangeMs.first,
				ivEnd           = tc.intervalRangeMs.last,
				maxDeferMs      = tc.maxDeferMs,
				onDeferTimeout  = tc.onDeferTimeout
			)
		}
		val tmp = Files.createTempFile(dir, "breaks", ".json.tmp")
		mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), BreakConfigDto(dtoMap))
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
	}

	private data class TierConfigDto(
		val enabled: Boolean,
		val durStart: Long,
		val durEnd: Long,
		val ivStart: Long,
		val ivEnd: Long,
		val maxDeferMs: Long,
		val onDeferTimeout: DeferAction
	)

	private data class BreakConfigDto(val tiers: Map<String, TierConfigDto> = emptyMap())
}
