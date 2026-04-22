package net.vital.plugins.buildcore.gui

import net.vital.plugins.buildcore.gui.theme.FlatLafTheme
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * BuildCore's main window — minimal shell for Plan 1.
 *
 * Plan 10 replaces the body of this class with the full 9-tab GUI from
 * spec §15. Plan 1 only proves that:
 *   - FlatLaf installs cleanly
 *   - A JFrame opens at the target size and can be closed
 *   - No AWT-thread violations occur during startup/shutdown
 *
 * Construct on the Swing EDT via [openOnEdt]. Never call the constructor
 * from the plugin's main thread.
 */
class BuildCoreWindow : JFrame("BuildCore") {

	init {
		preferredSize = Dimension(1440, 900)
		minimumSize = Dimension(1280, 800)
		defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		contentPane.add(JLabel("BuildCore foundation bootstrap — Plan 1 shell", JLabel.CENTER))
		pack()
		setLocationRelativeTo(null)
	}

	companion object {
		/**
		 * Install theme + construct + show the window on the Swing EDT.
		 * Returns the window reference on the calling thread once
		 * initialization completes (blocks briefly on invokeAndWait).
		 */
		fun openOnEdt(): BuildCoreWindow {
			var ref: BuildCoreWindow? = null
			SwingUtilities.invokeAndWait {
				FlatLafTheme.install()
				ref = BuildCoreWindow().apply { isVisible = true }
			}
			return ref ?: error("BuildCoreWindow failed to initialize on EDT")
		}
	}
}
