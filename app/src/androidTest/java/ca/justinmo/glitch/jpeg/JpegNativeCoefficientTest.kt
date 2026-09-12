package ca.justinmo.glitch.jpeg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the coefficient census the DQT screen hides rows with.
 *
 * The screen may only drop a slider when its coefficient decodes to zero in
 * every block - otherwise the user loses a control that would have worked. So
 * the walk has to see the coefficients libjpeg built, in the same zigzag order
 * the table stores them, and it has to stay silent for a picture with no detail.
 *
 * These cannot run as JVM tests: the walk is in the `.so`.
 */
@RunWith(AndroidJUnit4::class)
class JpegNativeCoefficientTest {

  private fun asset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets
      .open("parity/$name")
      .use { it.readBytes() }

  @Test
  fun aPhotoUsesItsDcAndSomeAcCoefficients() {
    assertTrue("the native library loaded", JpegNative.isAvailable)
    val masks = JpegNative.liveCoefficientMasks(asset("source.jpg"))
    assertNotNull("the fixture decodes", masks)

    val luma = masks!![0]
    assertTrue("luma DC is non-zero somewhere", (luma and 1L) != 0L)
    assertTrue("at least one luma AC coefficient survives", (luma ushr 1) != 0L)
    assertEquals("one mask per possible table id", 4, masks.size)
    assertEquals("no component selects table 2", 0L, masks[2])
    assertEquals("no component selects table 3", 0L, masks[3])
  }

  @Test
  fun aFlatImageOnlyUsesItsDcCoefficient() {
    // Every 8x8 block is the same solid colour, so every AC coefficient is zero
    // and no AC quantizer can change this picture - the case that motivated the
    // census.
    val width = 16
    val height = 16
    val pixels = IntArray(width * height) { 0xFF336699.toInt() }
    val jpeg = JpegNative.encodeArgb(
      argb = pixels,
      width = width,
      height = height,
      quality = JpegNative.REFERENCE_QUALITY,
      subsampling = JpegNative.SUBSAMPLING_444,
    )
    assertNotNull("the encoder produced a file", jpeg)

    val masks = JpegNative.liveCoefficientMasks(jpeg!!)
    assertNotNull("the file decodes", masks)
    assertEquals("luma block average only", 1L, masks!![0])
    assertEquals("chroma block average only", 1L, masks[1])
  }
}
