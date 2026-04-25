package net.vital.plugins.buildcore.core.services.magic

import net.vital.plugins.buildcore.core.services.combat.Targetable
import vital.api.ui.Spell

interface MagicBackend
{
	suspend fun cast(spell: Spell): Boolean
	suspend fun castOn(spell: Spell, target: Targetable): Boolean
}
