package ca.justinmo.glitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.glitch.dht.HuffmanTables
import ca.justinmo.glitch.dqt.QuantTables
import ca.justinmo.glitch.jpeg.JpegNative
import ca.justinmo.glitch.jpeg.JpegParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Checks the app's own image helpers now go through the bundled codec, on a device.
 *
 * The codec has its own parity tests against Pillow; these are about the wiring -
 * that `decodeSampled` and `resizeJpeg` really produce what the native library
 * produces, and not what the platform decoder happened to do on this OEM build.
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {

  private fun asset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets
      .open("parity/$name")
      .use { it.readBytes() }

  @Test
  fun thePreviewDecodeLandsOnTheRequestedSize() {
    val jpeg = asset("source.jpg")
    // A cap below the source size, so the native scaler has to do the work: the
    // libjpeg N/8 decode alone would give 96 anyway here, but the resize path is
    // what keeps a 1400 cap from silently becoming 1008 on a big photo.
    val bitmap = decodeSampled(jpeg, 96)
    assertNotNull("the preview decodes", bitmap)
    assertEquals("exactly the cap, not a rounded N/8 fraction", 96, bitmap!!.width)
    assertEquals("aspect kept", 72, bitmap.height)
    bitmap.recycle()
  }

  @Test
  fun aSmallImageIsDecodedWhole() {
    val jpeg = asset("source.jpg")
    val bitmap = decodeSampled(jpeg, 1000)
    assertNotNull(bitmap)
    assertEquals("nothing to scale", 192, bitmap!!.width)
    assertEquals(144, bitmap.height)
    bitmap.recycle()
  }

  @Test
  fun theResizePathReproducesTheReferencePipeline() {
    // resizeJpeg is decode + Lanczos + q92/4:2:0 in one native call. Pillow's
    // LANCZOS pixels are the reference, so decoding our file back and comparing
    // is the end-to-end check: it covers the decode, the resampler and the
    // encoder settings in one assertion.
    val jpeg = asset("source.jpg")
    val resized = resizeJpeg(jpeg, 96)
    assertNotNull("resize produced bytes", resized)

    val size = IntArray(2)
    val pixels = JpegNative.decodeArgb(resized!!, 0, size)
    assertNotNull("the resized file decodes", pixels)
    assertEquals("resized width", 96, size[0])
    assertEquals("resized height", 72, size[1])

    val reference = asset("scaled_96_rgb.bin")
    var total = 0L
    var worst = 0
    var counted = 0
    for (i in 0 until size[0] * size[1]) {
      val r = (pixels!![i] shr 16) and 0xFF
      val g = (pixels[i] shr 8) and 0xFF
      val b = pixels[i] and 0xFF
      val er = reference[i * 3].toInt() and 0xFF
      val eg = reference[i * 3 + 1].toInt() and 0xFF
      val eb = reference[i * 3 + 2].toInt() and 0xFF
      val d = maxOf(kotlin.math.abs(r - er), kotlin.math.abs(g - eg), kotlin.math.abs(b - eb))
      total += d
      if (d > worst) worst = d
      counted++
    }
    val mean = total.toDouble() / counted
    println("wired pipeline vs Pillow LANCZOS: mean $mean, worst $worst over $counted pixels")
    Log.i("JpegParity", "wired pipeline vs Pillow LANCZOS: mean $mean, worst $worst over $counted pixels")
    // The resampler agrees within a level or two and the q92 round trip adds a
    // little, so this is a band rather than an equality.
    assertTrue("mean difference is small (was $mean)", mean < 4.0)
    assertTrue("no pixel is wildly out (worst $worst)", worst < 48)
  }

  @Test
  fun aDamagedStreamStillReachesTheCaller() {
    // The native codec refuses a damaged stream; decodeSampled must not crash on
    // that - it falls back to the platform decoder, which is more forgiving, and
    // either way the caller gets a bitmap or a null rather than an exception.
    val jpeg = asset("source.jpg")
    val damaged = jpeg.copyOf()
    // Corrupt a byte inside the entropy-coded data.
    val offset = (jpeg.size * 3) / 4
    damaged[offset] = (damaged[offset].toInt() xor 0xFF).toByte()
    val bitmap = decodeSampled(damaged, 96)
    println("damaged stream decoded to: ${bitmap?.width}x${bitmap?.height}")
    bitmap?.recycle()
    // Nothing to assert beyond "no crash": whether a corrupt stream renders is
    // the decoders' business, and this only has to survive it.
  }

  @Test
  fun theResizePreviewUsesTheSameResamplerAsTheExport() {
    // previewAtTarget is what the resize screen shows before you commit it. If it
    // used the platform scaler, the picture on screen would not be the picture in
    // the file - and there is no JPEG in between here, so the resampler can be
    // checked on its own, tightly.
    val jpeg = asset("source.jpg")
    val full = decodeSampled(jpeg, 0)
    assertNotNull("the fixture decodes", full)
    val preview = previewAtTarget(full!!, 96, null)
    assertNotNull("the preview scaled", preview)
    assertEquals("preview width", 96, preview!!.width)
    assertEquals("preview height", 72, preview.height)

    val reference = asset("scaled_96_rgb.bin")
    val pixels = IntArray(preview.width * preview.height)
    preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
    var worst = 0
    var over2 = 0
    for (i in pixels.indices) {
      val r = (pixels[i] shr 16) and 0xFF
      val g = (pixels[i] shr 8) and 0xFF
      val b = pixels[i] and 0xFF
      val d = maxOf(
        kotlin.math.abs(r - (reference[i * 3].toInt() and 0xFF)),
        kotlin.math.abs(g - (reference[i * 3 + 1].toInt() and 0xFF)),
        kotlin.math.abs(b - (reference[i * 3 + 2].toInt() and 0xFF)),
      )
      if (d > 2) over2++
      if (d > worst) worst = d
    }
    println("resize preview vs Pillow LANCZOS: worst $worst, $over2 over 2 levels")
    assertTrue("the preview matches Pillow's resampler (worst $worst)", worst <= 2)
    full.recycle()
    preview.recycle()
  }

  @Test
  fun aNonJpegInputIsEncodedByOurEncoder() {
    // Picking a PNG means the app has to write a JPEG itself, and that file has
    // to be the app's own encoder rather than Skia's - otherwise a screenshot
    // import lands in a different kind of file from the photos the sweep used.
    val png = asset("source.png")
    val jpeg = toJpegBytes(png)
    assertNotNull("the PNG converted", jpeg)
    assertEquals("it is a JPEG", 0xFF, jpeg!![0].toInt() and 0xFF)
    assertEquals(0xD8, jpeg[1].toInt() and 0xFF)

    val structure = ca.justinmo.glitch.jpeg.JpegParser.parse(jpeg)
    val tables = HuffmanTables.parse(jpeg, structure)
    val frame = structure.frame
    assertNotNull("the frame parses", frame)
    val luma = frame!!.components.first()
    assertEquals("4:2:0, as the reference pipeline writes", 2, luma.h)
    assertEquals("4:2:0", 2, luma.v)
    val lumaDc = tables.first { !it.isAc && !it.isChroma }
    assertEquals(
      "the Annex K luma table, which is what an optimised encoder would not write",
      listOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
      lumaDc.counts.toList(),
    )

    // And it is the picture that went in, to within a q92 round trip.
    val size = IntArray(2)
    val decoded = JpegNative.decodeArgb(jpeg, 0, size)
    assertNotNull(decoded)
    assertEquals(192, size[0])
    assertEquals(144, size[1])
    val expected = asset("source_rgb.bin")
    var total = 0L
    for (i in 0 until size[0] * size[1]) {
      total += kotlin.math.abs(((decoded!![i] shr 16) and 0xFF) -
        (expected[i * 3].toInt() and 0xFF))
    }
    val mean = total.toDouble() / (size[0] * size[1])
    println("PNG -> our JPEG: mean red difference $mean")
    assertTrue("the picture survived the conversion (mean $mean)", mean < 4.0)
  }

  @Test
  fun theStoriesPreviewIsTheFileTheExportWrites() {
    // The crop is the case the first version of this got wrong: cropping to 9:16
    // changes *both* dimensions, and on a target wider than the cropped strip
    // there is no scaling at all - the preview is the strip. A preview that
    // works the size out from the uncropped bitmap reads rows at the wrong
    // stride, and at some widths gives up and shows Skia's scaler instead of the
    // resampler the file will be written with. So this checks the thing that
    // matters: same pixels as the export, at every target width.
    val jpeg = asset("source.jpg")
    val decoded = decodeSampled(jpeg, 0)
    assertNotNull("the fixture decodes", decoded)
    val source = decoded!!

    // 192x144 is 4:3, so the 9:16 crop is an 81x144 centre strip: the first
    // target is inside it (a real scale), the second is past it (the strip
    // itself), and the third is far past it.
    for (target in listOf(48, 108, 1464)) {
      val preview = previewAtTarget(source, target, STORIES_ASPECT)
      assertNotNull("a preview at target $target", preview)
      val shown = preview!!
      assertTrue(
        "the preview at $target is 9:16, was ${shown.width}x${shown.height}",
        kotlin.math.abs(shown.width.toFloat() / shown.height - STORIES_ASPECT) < 0.02f,
      )

      val exported = resizeJpeg(jpeg, target, STORIES_ASPECT)
      assertNotNull("an export at target $target", exported)
      val size = IntArray(2)
      val file = JpegNative.decodeArgb(exported!!, 0, size)
      assertNotNull("the exported file at target $target decodes", file)
      val written = file!!
      assertEquals("preview width at $target", size[0], shown.width)
      assertEquals("preview height at $target", size[1], shown.height)

      val pixels = IntArray(shown.width * shown.height)
      shown.getPixels(pixels, 0, shown.width, 0, 0, shown.width, shown.height)
      var total = 0L
      var worst = 0
      for (i in pixels.indices) {
        val d = maxOf(
          kotlin.math.abs(((pixels[i] shr 16) and 0xFF) - ((written[i] shr 16) and 0xFF)),
          kotlin.math.abs(((pixels[i] shr 8) and 0xFF) - ((written[i] shr 8) and 0xFF)),
          kotlin.math.abs((pixels[i] and 0xFF) - (written[i] and 0xFF)),
        )
        total += d
        if (d > worst) worst = d
      }
      val mean = total.toDouble() / pixels.size
      Log.i(
        "JpegParity",
        "stories preview at $target: ${shown.width}x${shown.height}, " +
          "vs the exported file mean $mean worst $worst",
      )
      // Only the q92 round trip should separate them: any other resampler, or a
      // stride mistake, shows up as tens of levels.
      assertTrue("preview at $target is the exported picture (mean $mean)", mean < 4.0)
      assertTrue("preview at $target is not sheared (worst $worst)", worst < 48)
      shown.recycle()
    }
    source.recycle()
  }

  @Test
  fun theCroppedPreviewIsTheCropThenScaleTheResamplerWouldDo() {
    // The resize screen asks the codec for "crop to 9:16 and scale to N" in one
    // call, while the export crop is applied inside the same call. Feeding the
    // resampler the crop instead - cropping the bitmap here, then scaling with
    // no crop - has to give the same pixels, or the picture on screen is not the
    // picture in the file. It is also the tightest check available on the vector
    // kernels' tails: a block of eight taps handled wrongly shows up as a pattern
    // every eight pixels, which the 1-pixel checkerboard in the source and an
    // exact equality both catch immediately.
    for ((w, h) in listOf(1200 to 1600, 1600 to 1200)) {
      val source = syntheticSource(w, h)
      for (target in listOf(344, 600, 900, 1272, 1464)) {
        val cropped = centeredCropBitmap(source, STORIES_ASPECT)
        val expected = JpegNative.scaleBitmap(cropped, target, null)
        val actual = JpegNative.scaleBitmap(source, target, STORIES_ASPECT)
        assertNotNull("crop-then-scale produced a bitmap at $target", expected)
        assertNotNull("the cropped preview produced a bitmap at $target", actual)
        assertEquals(
          "width from ${w}x$h at target $target",
          expected!!.width,
          actual!!.width,
        )
        assertEquals("height from ${w}x$h at target $target", expected.height, actual.height)

        val a = IntArray(actual.width * actual.height)
        val b = IntArray(expected.width * expected.height)
        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
        expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
        var worst = 0
        for (i in a.indices) {
          val d = maxOf(
            kotlin.math.abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)),
            kotlin.math.abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)),
            kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF)),
          )
          if (d > worst) worst = d
        }
        Log.i(
          "JpegParity",
          "crop path ${w}x$h -> $target: ${actual.width}x${actual.height}, " +
            "vs crop-then-scale worst $worst",
        )
        assertTrue(
          "the crop path at ${w}x$h -> $target is the same picture (worst $worst)",
          worst == 0,
        )
        actual.recycle()
        expected.recycle()
        if (cropped !== source) cropped.recycle()
      }
      source.recycle()
    }
  }

  @Test
  fun aCameraPhotoResizesUpright() {
    // A phone camera saves a portrait shot as a landscape grid plus an EXIF orientation
    // tag. libjpeg does not read that tag -- it hands back the stored grid -- and this
    // pipeline writes a file with no EXIF of its own, so unless the rotation happens in
    // here the file handed on is sideways *and* the tag that explained it is gone. That
    // is the picture that then spins the first time something decodes it again, which is
    // what flipping a quantization-table bit does.
    val jpeg = withOrientation(asset("source.jpg"), ExifInterface.ORIENTATION_ROTATE_90)
    assertEquals(
      "the tag is really in the file",
      ExifInterface.ORIENTATION_ROTATE_90,
      exifOrientation(jpeg),
    )

    // Displayed, the 192x144 fixture is a 144x192 portrait.
    val upright = decodeSampled(jpeg, 0)
    assertNotNull("the tagged file decodes", upright)
    assertEquals("shown width", 144, upright!!.width)
    assertEquals("shown height", 192, upright.height)

    val resized = resizeJpeg(jpeg, 96)
    assertNotNull("resize produced bytes", resized)
    val size = IntArray(2)
    val pixels = JpegNative.decodeArgb(resized, 0, size)
    assertNotNull("the resized file decodes", pixels)
    assertEquals("resized onto the upright width", 96, size[0])
    assertEquals("and the upright height follows it", 128, size[1])
    // Nothing is left to say "turn this", which is the point: the pixels are the way
    // up already. Absent reads back as 1 (normal) or 0 (undefined) depending on the
    // platform's ExifInterface, and both mean the same thing here - the app applies
    // neither.
    val tag = exifOrientation(resized)
    assertTrue(
      "the output needs no orientation tag, was $tag",
      tag == ExifInterface.ORIENTATION_NORMAL || tag == ExifInterface.ORIENTATION_UNDEFINED,
    )

    // And it is the picture the resize screen showed: the same cropped-and-resampled
    // frame previewAtTarget produces, not a rotation of it.
    val expected = previewAtTarget(upright!!, 96, null)
    assertNotNull("the preview scales", expected)
    assertEquals("preview width", 96, expected.width)
    assertEquals("preview height", 128, expected.height)
    assertSamePicture("the file is the preview", expected, pixels!!, size)
    expected.recycle()
    upright.recycle()
  }

  @Test
  fun aCameraPhotoCropsTheWayItIsShown() {
    // The crop has to be measured on the upright picture too: a 9:16 trim of a portrait
    // takes the sides off it, while the same trim of the sideways grid would keep a
    // narrow slice of the middle. Which one it took is invisible in the dimensions and
    // obvious in the picture, so this compares pixels against the resize screen's own
    // preview.
    val jpeg = withOrientation(asset("source.jpg"), ExifInterface.ORIENTATION_ROTATE_90)
    val upright = decodeSampled(jpeg, 0)
    assertNotNull("the tagged file decodes", upright)

    val exported = resizeJpeg(jpeg, 96, STORIES_ASPECT)
    assertNotNull("an export at 96", exported)
    val size = IntArray(2)
    val pixels = JpegNative.decodeArgb(exported, 0, size)
    assertNotNull("the exported file decodes", pixels)

    val expected = previewAtTarget(upright!!, 96, STORIES_ASPECT)
    assertNotNull("the preview scales", expected)
    assertEquals("preview width", expected.width, size[0])
    assertEquals("preview height", expected.height, size[1])
    assertSamePicture("the cropped file is the preview", expected, pixels!!, size)
    expected.recycle()
    upright.recycle()
  }

  @Test
  fun aReEncodedExportIsUpright() {
    // The other place pixels are written without the tag they arrived with: every output
    // format but RawJpeg decodes the file and encodes it again, and the frame that comes
    // back out of BitmapFactory is the stored one. Exported as a PNG, a camera photo used
    // to lie on its side.
    val jpeg = withOrientation(asset("source.jpg"), ExifInterface.ORIENTATION_ROTATE_90)
    val png = encodedFor(jpeg, OutputFormat.Png)
    assertNotNull("the PNG was written", png)
    val picture = BitmapFactory.decodeByteArray(png, 0, png!!.size)
    assertNotNull("the PNG decodes", picture)
    assertEquals("upright width", 144, picture!!.width)
    assertEquals("upright height", 192, picture.height)
    picture.recycle()
  }

  @Test
  fun flippingAQuantizerBitDoesNotTurnThePicture() {
    // The reported bug, walked through the way it happens: a photo from the camera, the
    // resize step's file, then a DQT bit flipped on it. The flip was never the cause -
    // it is simply the first fresh decode of the file the resize step made, and that
    // file used to be sideways with the tag that explained it thrown away. So this
    // checks the file is upright when it reaches the table screen, and that flipping a
    // quantizer in it leaves the picture the same way up.
    val camera = withOrientation(asset("source.jpg"), ExifInterface.ORIENTATION_ROTATE_90)
    val resized = resizeJpeg(camera, 96)

    val before = decodeSampled(resized, 0)
    assertNotNull("the resized file decodes", before)
    assertEquals("the table screen opens on the upright picture", 96, before!!.width)
    assertEquals(128, before.height)

    val structure = JpegParser.parse(resized)
    val tables = QuantTables.parse(resized, structure)
    val entry = tables.flatMap { it.entries }.first { it.value != 0 }
    // Bit 4, the lowest bit the DQT screen's own flip will move (see QUANT_FLIP_MIN_BIT).
    val flipped = resized.copyOf().also {
      QuantTables.applyValues(it, tables, mapOf(entry.offset to (entry.value xor 0x10)))
    }
    val after = decodeSampled(flipped, 0)
    assertNotNull("the flipped file still decodes", after)
    assertEquals("the flip did not turn the picture", before.width, after!!.width)
    assertEquals(before.height, after.height)
    before.recycle()
    after.recycle()
  }

  /** [jpeg] with its EXIF orientation tag set to [orientation], written to the cache. */
  private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "oriented_$orientation.jpg")
    file.writeBytes(jpeg)
    val exif = ExifInterface(file)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
    exif.saveAttributes()
    return file.readBytes()
  }

  /** Assert [pixels] (as decoded, [size] wide and high) is [expected] up to a q92 round trip. */
  private fun assertSamePicture(
    what: String,
    expected: Bitmap,
    pixels: IntArray,
    size: IntArray,
  ) {
    assertEquals("$what: width", expected.width, size[0])
    assertEquals("$what: height", expected.height, size[1])
    val shown = IntArray(expected.width * expected.height)
    expected.getPixels(shown, 0, expected.width, 0, 0, expected.width, expected.height)
    var total = 0L
    var worst = 0
    for (i in shown.indices) {
      val d = maxOf(
        kotlin.math.abs(((shown[i] shr 16) and 0xFF) - ((pixels[i] shr 16) and 0xFF)),
        kotlin.math.abs(((shown[i] shr 8) and 0xFF) - ((pixels[i] shr 8) and 0xFF)),
        kotlin.math.abs((shown[i] and 0xFF) - (pixels[i] and 0xFF)),
      )
      total += d
      if (d > worst) worst = d
    }
    val mean = total.toDouble() / shown.size
    Log.i("JpegParity", "$what: mean $mean worst $worst over ${shown.size} pixels")
    assertTrue("$what: mean difference is small (was $mean)", mean < 4.0)
    assertTrue("$what: nothing is wildly out (worst $worst)", worst < 48)
  }

  /** A gradient with one-pixel detail on top: worst case for any resampler. */
  private fun syntheticSource(w: Int, h: Int): Bitmap {
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val r = x * 255 / w
        val g = y * 255 / h
        val b = if (((x + y) and 1) == 0) 255 else 0
        pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Bitmap.createBitmap(pixels, 0, w, w, h, Bitmap.Config.ARGB_8888)
  }
}
