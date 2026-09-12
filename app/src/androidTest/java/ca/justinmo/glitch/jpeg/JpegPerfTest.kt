package ca.justinmo.glitch.jpeg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/**
 * What the app's codec costs, next to the platform's, on the device in hand.
 *
 * This is a benchmark rather than a test: it prints a table and only asserts that
 * every path produced something sane. It exists because the native library is
 * built by our own CMake, and the one failure mode that produces no wrong pixels
 * and no error message is building it unoptimised - the app works, and is simply
 * several times slower than the platform decoder it replaced. Numbers in the
 * logcat are how that gets noticed.
 *
 * ```
 * adb logcat -d -s JpegPerf:I
 * ```
 *
 * The image is generated here instead of shipped as an asset: a camera-sized JPEG
 * is ~2 MB and belongs in no repository. It is deterministic, so runs are
 * comparable, and it is built with the *platform* encoder so both decoders are
 * handed the same file. 2560x1920 is a small phone photo rather than a 12 MP one,
 * to stay inside an emulator's heap.
 */
@RunWith(AndroidJUnit4::class)
class JpegPerfTest {

  private val width = 2560
  private val height = 1920

  /** The same picture every run: a gradient crossed by detail the DCT has to work for. */
  private fun generatePixels(): IntArray {
    val pixels = IntArray(width * height)
    var seed = 0x2F6E2B1
    for (y in 0 until height) {
      for (x in 0 until width) {
        // Cheap deterministic noise on top of smooth bands, so the file is not
        // trivially compressible - a flat image would flatter the encoder.
        seed = seed * 1103515245 + 12345
        val noise = (seed ushr 16) and 0x1F
        val band = (sin(x / 37.0) * 60 + sin(y / 23.0) * 40).toInt()
        val r = (x * 255 / width + noise).coerceIn(0, 255)
        val g = (y * 255 / height + band + noise).coerceIn(0, 255)
        val b = ((x + y) * 255 / (width + height) + noise).coerceIn(0, 255)
        pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return pixels
  }

  /** The benchmark's source file, written by the platform encoder. */
  private fun sourceJpeg(pixels: IntArray): ByteArray {
    val bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    assertTrue("the platform encoder wrote a JPEG", bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
    bitmap.recycle()
    return out.toByteArray()
  }

  private fun timeMs(runs: Int = 3, block: () -> Unit): Double {
    block() // warm up: first touch of a page or a code path is not what is being measured
    val times = DoubleArray(runs)
    for (i in 0 until runs) {
      val start = System.nanoTime()
      block()
      times[i] = (System.nanoTime() - start) / 1_000_000.0
    }
    return times.min() // the best run, not the average: the noise is all upward
  }

  private fun report(name: String, ms: Double) {
    Log.i(TAG, String.format("%-34s %9.1f ms", name, ms))
  }

  @Test
  fun whatEachPathCosts() {
    assertTrue("the native library loaded", JpegNative.isAvailable)
    val pixels = generatePixels()
    val jpeg = sourceJpeg(pixels)
    Log.i(TAG, "source: ${width}x$height, ${jpeg.size / 1024} KiB, libjpeg-turbo ${JpegNative.version()}")

    // --- decode, full size -----------------------------------------------------
    val platformFull = timeMs {
      val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
      assertNotNull("the platform decoder produced a bitmap", bitmap)
      assertEquals(width, bitmap.width)
      bitmap.recycle()
    }
    report("platform  decode full", platformFull)

    val size = IntArray(2)
    val oursFull = timeMs {
      val decoded = JpegNative.decodeArgb(jpeg, 0, size)
      assertNotNull("our decoder produced pixels", decoded)
      assertEquals(width * height, decoded!!.size)
    }
    report("ours      decode full", oursFull)
    val oursFullBitmap = timeMs {
      val bitmap = JpegNative.decodeBitmap(jpeg, 0)
      assertNotNull(bitmap)
      bitmap!!.recycle()
    }
    report("ours      decode full + Bitmap", oursFullBitmap)

    // --- decode, preview size --------------------------------------------------
    // The platform row is what this app did before it had its own codec: a
    // subsampled decode, then Skia's scaler to the size on screen. Comparing
    // against a bare subsampled decode would flatter us - ours also resamples.
    val cap = 1400
    val platformPreview = timeMs {
      val half = BitmapFactory.decodeByteArray(
        jpeg, 0, jpeg.size,
        BitmapFactory.Options().apply { inSampleSize = 2 },
      )
      assertNotNull(half)
      val scaled = Bitmap.createScaledBitmap(half, cap, cap * half.height / half.width, true)
      scaled.recycle()
      half.recycle()
    }
    report("platform  decode 1/2 + scale to $cap", platformPreview)

    val oursPreview = timeMs {
      val bitmap = JpegNative.decodePreviewBitmap(jpeg, cap)
      assertNotNull(bitmap)
      assertEquals(cap, maxOf(bitmap!!.width, bitmap.height))
      bitmap.recycle()
    }
    report("ours      decode preview ($cap)", oursPreview)

    // --- the resize screen's two calls -----------------------------------------
    val ourScale = timeMs {
      val bitmap = JpegNative.decodePreviewBitmap(jpeg, cap)!!
      val scaled = JpegNative.scaleBitmap(bitmap, cap / 2, null)
      assertNotNull(scaled)
      scaled!!.recycle()
      bitmap.recycle()
    }
    report("ours      decode + Lanczos to ${cap / 2}", ourScale)

    val ourResize = timeMs {
      val resized = JpegNative.resizeReference(jpeg, cap / 2, null)
      assertNotNull(resized)
    }
    report("ours      resize (decode+Lanczos+q92)", ourResize)

    // --- encode ----------------------------------------------------------------
    val source = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888)
    val platformEncode = timeMs {
      val out = ByteArrayOutputStream()
      assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 92, out))
    }
    report("platform  encode q92", platformEncode)

    val argb = IntArray(width * height)
    source.getPixels(argb, 0, width, 0, 0, width, height)
    val oursEncode = timeMs {
      val encoded = JpegNative.encodeArgb(argb, width, height, 92, JpegNative.SUBSAMPLING_420)
      assertNotNull(encoded)
    }
    report("ours      encode q92 (from ints)", oursEncode)

    val withCopy = timeMs {
      val fresh = IntArray(width * height)
      source.getPixels(fresh, 0, width, 0, 0, width, height)
      val encoded = JpegNative.encodeArgb(fresh, width, height, 92, JpegNative.SUBSAMPLING_420)
      assertNotNull(encoded)
    }
    report("ours      encode q92 + getPixels", withCopy)

    source.recycle()

    // A readout a human compares at a glance; the numbers above are the point.
    Log.i(
      TAG,
      String.format(
        "ratios ours/platform - decode %.2fx, preview %.2fx, encode %.2fx, encode+copy %.2fx",
        oursFull / platformFull,
        oursPreview / platformPreview,
        oursEncode / platformEncode,
        withCopy / platformEncode,
      ),
    )
  }

  private companion object {
    const val TAG = "JpegPerf"
  }
}
