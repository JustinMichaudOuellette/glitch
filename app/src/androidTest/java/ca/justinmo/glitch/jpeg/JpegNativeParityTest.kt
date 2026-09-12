package ca.justinmo.glitch.jpeg

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app's own codec against Pillow, on a device.
 *
 * The fixtures in `androidTest/assets/parity/` were written by Pillow - the same
 * libjpeg-turbo the `b_x4` sweep was measured with - so these tests answer the
 * question the whole native pipeline exists for: does the app decode, resize and
 * encode the same thing the sweep and `DHT.md` describe, on a phone?
 *
 * They cannot run as JVM tests: the codec is an `.so`, so this is the only place
 * parity can be checked, and it is why the emulator exists in this workflow.
 */
@RunWith(AndroidJUnit4::class)
class JpegNativeParityTest {

  private fun asset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets
      .open("parity/$name")
      .use { it.readBytes() }

  /** The reference decode, as 3 bytes per pixel. */
  private fun referenceRgb(): ByteArray = asset("source_rgb.bin")

  /** The reference decode as 0xAARRGGBB ints, which is what the codec takes. */
  private fun referenceArgb(rgb: ByteArray): IntArray =
    IntArray(rgb.size / 3) { i ->
      (0xFF shl 24) or
        ((rgb[i * 3].toInt() and 0xFF) shl 16) or
        ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or
        (rgb[i * 3 + 2].toInt() and 0xFF)
    }

  private fun channels(pixels: IntArray, i: Int): Triple<Int, Int, Int> =
    Triple((pixels[i] shr 16) and 0xFF, (pixels[i] shr 8) and 0xFF, pixels[i] and 0xFF)

  @Test
  fun theBundledLibraryIsTheOneTheSweepUsed() {
    assertTrue("the native library loaded", JpegNative.isAvailable)
    val version = JpegNative.version()
    println("libjpeg-turbo: $version")
    assertTrue("a version is reported, was '$version'", version.isNotBlank())
    assertTrue(
      "libjpeg-turbo 3.x, the series the sweep's files came from, was '$version'",
      version.startsWith("3."),
    )
  }

  @Test
  fun decodesExactlyLikePillow() {
    val jpeg = asset("source.jpg")
    val expected = referenceRgb()
    val size = IntArray(2)
    val pixels = JpegNative.decodeArgb(jpeg, 0, size)
    assertNotNull("the fixture decodes", pixels)
    val width = size[0]
    val height = size[1]
    assertEquals("width", 192, width)
    assertEquals("height", 144, height)
    assertEquals("one int per pixel", width * height, pixels!!.size)

    var worst = 0
    var differing = 0
    for (i in 0 until width * height) {
      val (r, g, b) = channels(pixels, i)
      val er = expected[i * 3].toInt() and 0xFF
      val eg = expected[i * 3 + 1].toInt() and 0xFF
      val eb = expected[i * 3 + 2].toInt() and 0xFF
      val diff = maxOf(kotlin.math.abs(r - er), kotlin.math.abs(g - eg), kotlin.math.abs(b - eb))
      if (diff != 0) differing++
      if (diff > worst) worst = diff
    }
    println("decode vs Pillow: $differing of ${width * height} pixels differ, worst $worst")
    // Same library, same settings, same file: the pixels have to be identical.
    assertEquals("every channel matches Pillow's decode", 0, worst)
  }

  @Test
  fun encodesExactlyLikePillow() {
    val pixels = referenceArgb(referenceRgb())

    val encoded = JpegNative.encodeArgb(
      argb = pixels,
      width = 192,
      height = 144,
      quality = JpegNative.REFERENCE_QUALITY,
      subsampling = JpegNative.SUBSAMPLING_420,
    )
    assertNotNull("the encoder produced bytes", encoded)
    val expected = asset("encoded_q92.jpg")

    assertEquals("same length as Pillow's file", expected.size, encoded!!.size)
    var firstDiff = -1
    for (i in expected.indices) {
      if (expected[i] != encoded[i]) {
        firstDiff = i
        break
      }
    }
    if (firstDiff >= 0) {
      val from = maxOf(0, firstDiff - 8)
      val to = minOf(expected.size, firstDiff + 8)
      println(
        "first difference at byte $firstDiff / ${expected.size}\n" +
          "  pillow : ${expected.copyOfRange(from, to).joinToString(" ") { "%02x".format(it) }}\n" +
          "  ours   : ${encoded.copyOfRange(from, to).joinToString(" ") { "%02x".format(it) }}",
      )
    }
    assertEquals("byte-for-byte with Pillow's encoder", -1, firstDiff)
  }

  @Test
  fun resizesLikePillow() {
    val jpeg = asset("source.jpg")
    val reference = referenceRgb()
    val scaled = asset("scaled_96_rgb.bin")
    val height = 72
    val width = 96
    assertEquals("fixture is the right size", width * height * 3, scaled.size)

    val size = IntArray(2)
    val pixels = JpegNative.scaleArgb(jpeg, width, 0f, size)
    assertNotNull("scaling produced pixels", pixels)
    assertEquals("scaled width", width, size[0])
    assertEquals("scaled height", height, size[1])

    var worst = 0
    var over2 = 0
    for (i in 0 until width * height) {
      val (r, g, b) = channels(pixels!!, i)
      val er = scaled[i * 3].toInt() and 0xFF
      val eg = scaled[i * 3 + 1].toInt() and 0xFF
      val eb = scaled[i * 3 + 2].toInt() and 0xFF
      val diff = maxOf(kotlin.math.abs(r - er), kotlin.math.abs(g - eg), kotlin.math.abs(b - eb))
      if (diff > 2) over2++
      if (diff > worst) worst = diff
    }
    println("resize vs Pillow LANCZOS: worst $worst, $over2 of ${width * height} over 2 levels")
    // Also on logcat, so the number is readable when the test passes - the
    // resampler's agreement with Pillow is a measurement, not just a pass.
    Log.i("JpegParity", "resize vs Pillow LANCZOS: worst $worst, $over2 of ${width * height} over 2 levels")
    // Pillow uses fixed-point coefficients and this sums in the same fixed point,
    // so a level of rounding is expected; a shift of half a pixel would not be.
    assertTrue("no pixel is off by more than 2 levels (worst $worst)", worst <= 2)
  }

  @Test
  fun refusesTheBitsTheSweepHasNoSampleFor() {
    // The editor greys out strip cells the sweep has no picture for. That is only
    // honest if flipping one really does stop our decoder - so flip one and see.
    val source = asset("source.jpg")
    val structure = JpegParser.parse(source)
    val fatal = structure.fatal.firstOrNull()
    assertNotNull("the fixture has a fatal byte", fatal)

    val damaged = source.copyOf()
    val offset = fatal!!.start
    damaged[offset] = (damaged[offset].toInt() xor 0x01).toByte()

    val size = IntArray(2)
    assertNull(
      "a flip the classification calls fatal must not decode",
      JpegNative.decodeArgb(damaged, 0, size),
    )
  }

  @Test
  fun theScaledPreviewDecodeIsTheRightShapeAndCloseToTheFullOne() {
    // Sanity on the path the preview uses: a scaled decode must give a smaller
    // image that still looks like the picture. libjpeg's N/8 decode uses a
    // reduced-size IDCT, so it is not the same pixels as a full decode - hence a
    // mean difference rather than an exact match.
    val jpeg = asset("source.jpg")
    val size = IntArray(2)
    val scaled = JpegNative.decodeArgb(jpeg, 96, size)
    assertNotNull("the scaled decode produced pixels", scaled)
    assertEquals("scaled to the cap", 96, size[0])
    assertEquals("keeping the aspect", 72, size[1])

    val fullSize = IntArray(2)
    val full = JpegNative.decodeArgb(jpeg, 0, fullSize)!!
    assertEquals(192, fullSize[0])
    assertEquals(144, fullSize[1])

    val step = fullSize[0] / size[0]
    var total = 0L
    var counted = 0
    for (y in 0 until size[1]) {
      for (x in 0 until size[0]) {
        val a = channels(scaled!!, y * size[0] + x)
        val b = channels(
          full,
          (y * step).coerceAtMost(fullSize[1] - 1) * fullSize[0] +
            (x * step).coerceAtMost(fullSize[0] - 1),
        )
        total += kotlin.math.abs(a.first - b.first)
        counted++
      }
    }
    val mean = total.toDouble() / counted
    println("scaled vs full decode: mean red difference $mean over $counted pixels")
    assertTrue("the scaled decode is the same picture (mean $mean)", mean < 16.0)
  }
}
