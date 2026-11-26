package io.github.totocastaldi.herdrdesktop.terminal

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

/** The terminal font comes from the packaged assets, with real faces of the same advance. */
@RunWith(AndroidJUnit4::class)
class TerminalFontsTest {
    private val fonts = TerminalFonts.get(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    @Test
    fun facesAreLoadedFromTheAssets() {
        assertNotSame(TerminalFonts.SYSTEM, fonts)
        assertNotSame(fonts.regular, fonts.bold)
        assertNotSame(fonts.regular, fonts.italic)
        assertNotSame(fonts.bold, fonts.boldItalic)
    }

    @Test
    fun everyFaceHasTheSameAdvance() {
        val widths = listOf(fonts.regular, fonts.bold, fonts.italic, fonts.boldItalic).map { face ->
            Paint().apply {
                typeface = face
                textSize = TEXT_SIZE
            }.measureText("M")
        }

        widths.forEach { assertEquals(widths[0], it, 0.01f) }
    }

    @Test
    fun missingGlyphsFallBackToTheSystem() {
        val paint = Paint().apply {
            typeface = fonts.regular
            textSize = TEXT_SIZE
        }

        assertEquals(true, paint.hasGlyph("漢"))
    }

    private companion object {
        const val TEXT_SIZE = 20f
    }
}
