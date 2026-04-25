package net.vital.plugins.buildcore.core.services.magic

import net.vital.plugins.buildcore.core.services.combat.Targetable
import vital.api.ui.Magic
import vital.api.ui.Spell

/**
 * Default [MagicBackend] delegating to VitalAPI.
 * Plan 5a spec §5.
 */
object VitalApiMagicBackend : MagicBackend
{
	override suspend fun cast(spell: Spell): Boolean = Magic.cast(spell)

	override suspend fun castOn(spell: Spell, target: Targetable): Boolean =
		error("not implemented in 5a; needs targetParam/targetAction derivation from Targetable")
}
