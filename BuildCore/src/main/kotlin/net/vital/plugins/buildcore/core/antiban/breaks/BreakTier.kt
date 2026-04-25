package net.vital.plugins.buildcore.core.antiban.breaks

/**
 * Re-export of the canonical [net.vital.plugins.buildcore.core.events.BreakTier]
 * declared in `BusEvent.kt`. Lives here so `breaks` package references read
 * naturally without an `events` import.
 *
 * Plan 4b spec §5.1.
 */
typealias BreakTier = net.vital.plugins.buildcore.core.events.BreakTier
