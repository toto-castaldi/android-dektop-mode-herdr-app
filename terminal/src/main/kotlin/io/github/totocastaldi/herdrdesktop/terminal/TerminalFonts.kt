package io.github.totocastaldi.herdrdesktop.terminal

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily

/**
 * The terminal font, JetBrains Mono (OFL-1.1, `assets/terminal-font/`), with its real bold, italic and
 * bold italic faces; glyphs it lacks (CJK, emoji) come from the system's monospace fallback. If the assets
 * cannot be read the system monospace font is used for every face.
 */
class TerminalFonts private constructor(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface
) {
    fun face(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        private const val DIR = "terminal-font"
        private const val FAMILY = "JetBrainsMono"
        private const val SYSTEM_FALLBACK = "monospace"

        @Volatile
        private var loaded: TerminalFonts? = null

        /** The fonts, read once from [assets]. */
        fun get(assets: AssetManager): TerminalFonts = loaded ?: synchronized(this) {
            loaded ?: load(assets).also { loaded = it }
        }

        /** The system monospace font for every face, without assets (tests, previews). */
        val SYSTEM = TerminalFonts(Typeface.MONOSPACE, Typeface.MONOSPACE, Typeface.MONOSPACE, Typeface.MONOSPACE)

        private fun load(assets: AssetManager): TerminalFonts = runCatching {
            fun face(name: String): Typeface {
                val font = Font.Builder(assets, "$DIR/$FAMILY-$name.ttf").build()
                return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                    .setSystemFallback(SYSTEM_FALLBACK)
                    .build()
            }
            TerminalFonts(face("Regular"), face("Bold"), face("Italic"), face("BoldItalic"))
        }.getOrDefault(SYSTEM)
    }
}
