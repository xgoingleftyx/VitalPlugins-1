package net.vital.plugins.buildcore.core.logging

/**
 * Single source of truth for the BuildCore version string.
 *
 * Plan 3 uses this in [SessionStart] and [session.meta.json]. A later
 * plan may wire this from the gradle `version` property via a
 * generated resource; for now it is a hand-edited constant and
 * version bumps bump both places.
 */
object BuildCoreVersion {
	const val CURRENT: String = "0.1.0"
}
