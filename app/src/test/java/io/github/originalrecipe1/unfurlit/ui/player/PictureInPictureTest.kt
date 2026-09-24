package io.github.originalrecipe1.unfurlit.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureTest {
    @Test
    fun usesTheVideoDisplayAspectRatio() {
        assertEquals(17_778 to 10_000, pictureInPictureAspectRatio(1920, 1080, 1f))
        assertEquals(5_625 to 10_000, pictureInPictureAspectRatio(1080, 1920, 1f))
        assertEquals(13_333 to 10_000, pictureInPictureAspectRatio(1440, 1440, 4f / 3f))
    }

    @Test
    fun clampsToTheRangeAndroidAccepts() {
        val (tallWidth, tallHeight) = pictureInPictureAspectRatio(100, 1000, 1f)!!
        assertEquals(4_185 to 10_000, tallWidth to tallHeight)
        assertTrue("Must not be narrower than 100:239", tallWidth * 239 >= tallHeight * 100)
        val (wideWidth, wideHeight) = pictureInPictureAspectRatio(3000, 1000, 1f)!!
        assertEquals(23_890 to 10_000, wideWidth to wideHeight)
        assertTrue("Must not be wider than 239:100", wideWidth * 100 <= wideHeight * 239)
    }

    @Test
    fun unknownVideoSizeHasNoRatio() {
        assertNull(pictureInPictureAspectRatio(0, 0, 1f))
    }
}
