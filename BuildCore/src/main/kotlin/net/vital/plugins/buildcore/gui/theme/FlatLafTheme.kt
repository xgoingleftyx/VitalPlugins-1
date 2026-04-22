package net.vital.plugins.buildcore.gui.theme

import com.formdev.flatlaf.FlatDarkLaf
import javax.swing.UIManager

/**
 * BuildCore GUI theme.
 *
 * FlatLaf Dark as the base (spec §15 calls for IntelliJ Darcula;
 * FlatDarculaIJTheme was removed in flatlaf-intellij-themes 3.x —
 * FlatDarkLaf from the core module is the maintained equivalent with
 * the same dark palette). Full accent support lands in Plan 10's GUI
 * shell work. This bootstrap task just gets the base theme installed.
 *
 * Call [install] once at GUI startup, before any Swing component is
 * constructed.
 */
object FlatLafTheme {

	/** Default accent color (BuildCore purple). Used by Plan 10. */
	const val DEFAULT_ACCENT_HEX = "#8A6BFF"

	/**
	 * Install the theme on the current thread's UIManager.
	 *
	 * Must be called before any JFrame/JPanel construction, or the
	 * components will render with the default Metal LaF.
	 */
	fun install() {
		try {
			UIManager.setLookAndFeel(FlatDarkLaf())
		} catch (ex: Exception) {
			System.err.println("BuildCore: failed to install FlatLaf theme: ${ex.message}")
			// Fall back to system LaF so the GUI still renders something.
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
		}
	}
}
