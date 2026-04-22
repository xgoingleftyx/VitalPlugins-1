package net.vital.plugins.buildcore.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

class BuildCoreWindowSmokeTest {

	@Test
	fun `window constructs with expected title and dimensions`() {
		// JVM on CI might be headless — skip if so.
		assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display")

		var window: BuildCoreWindow? = null
		SwingUtilities.invokeAndWait {
			window = BuildCoreWindow()
		}
		val w = window ?: error("window not created")

		try {
			assertEquals("BuildCore", w.title)
			assertEquals(1280, w.minimumSize.width)
			assertEquals(800, w.minimumSize.height)
			assertEquals(WindowConstants.DISPOSE_ON_CLOSE, w.defaultCloseOperation)
			assertFalse(w.isVisible, "window must not auto-show from constructor")
		} finally {
			SwingUtilities.invokeAndWait { w.dispose() }
		}
	}
}
