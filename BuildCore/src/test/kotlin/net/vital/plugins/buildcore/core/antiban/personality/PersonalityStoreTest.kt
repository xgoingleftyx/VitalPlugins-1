package net.vital.plugins.buildcore.core.antiban.personality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PersonalityStoreTest {

	private fun sample() = PersonalityVector(
		mouseSpeedCenter = 1.0, mouseCurveGravity = 10.0, mouseCurveWind = 5.0,
		overshootTendency = 0.05, reactionLogMean = 6.0, reactionLogStddev = 0.4,
		hotkeyPreference = 0.7, foodEatDelayCenterMs = 600, cameraFidgetRatePerMin = 2.0,
		bankWithdrawalPrecision = 0.95, breakBias = BreakBias.DAY_REGULAR,
		misclickRate = 0.01, menuTopSelectionRate = 0.97, idleExamineRatePerMin = 1.5,
		tabSwapRatePerMin = 1.0
	)

	@Test
	fun `save then load returns equal PersonalityVector`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		val original = sample()
		store.save("abc123def456", original)
		val loaded = store.load("abc123def456")
		assertEquals(original, loaded)
	}

	@Test
	fun `load returns null for missing key`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		assertNull(store.load("deadbeef0000"))
	}

	@Test
	fun `load returns null and logs warning on corrupt file`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		Files.writeString(tmp.resolve("abc123def456.json"), "not valid json")
		assertNull(store.load("abc123def456"))
	}

	@Test
	fun `save writes atomically via tmp then move`(@TempDir tmp: Path) {
		val store = PersonalityStore(tmp)
		store.save("abc123def456", sample())
		val final = tmp.resolve("abc123def456.json")
		val temp = tmp.resolve("abc123def456.json.tmp")
		assertTrue(Files.exists(final))
		assertTrue(!Files.exists(temp))   // tmp has been moved, not left behind
	}
}
