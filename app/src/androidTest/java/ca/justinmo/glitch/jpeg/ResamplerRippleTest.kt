package ca.justinmo.glitch.jpeg

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Does the resampler add anything to an image that has nothing to add to?
 *
 * A linear gradient contains one low frequency and no detail at all, so after a
 * faithful resample it is still a straight line: any departure from that line is
 * the resampler's own, it cannot be the picture's. A ripple that repeats with a
 * period tied to the ratio is what a phase-dependent error looks like, and the
 * reported grid moved with the target width - so this measures the departure and
 * the period it repeats at, for the sizes that showed it, and for one below the
 * size at which the passes hand rows to other threads.
 */
@RunWith(AndroidJUnit4::class)
class ResamplerRippleTest {

  private fun gradient(w: Int, h: Int, horizontal: Boolean): IntArray {
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = if (horizontal) x * 255 / (w - 1) else y * 255 / (h - 1)
        pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
      }
    }
    return pixels
  }

  /** Largest departure from a straight line through the profile, and its period. */
  private fun ripple(prof: DoubleArray): Triple<Double, Int, Double> {
    val n = prof.size
    val mean = prof.average()
    var sxy = 0.0
    var sxx = 0.0
    val mid = (n - 1) / 2.0
    for (i in 0 until n) {
      sxy += (i - mid) * (prof[i] - mean)
      sxx += (i - mid) * (i - mid)
    }
    val slope = sxy / sxx
    val dev = DoubleArray(n) { i -> prof[i] - (mean + slope * (i - mid)) }
    val worst = dev.maxOf { abs(it) }
    val z = dev.sumOf { it * it }
    var bestCorr = 0.0
    var bestLag = 0
    for (lag in 4..minOf(160, n / 3)) {
      var s = 0.0
      for (i in 0 until n - lag) s += dev[i] * dev[i + lag]
      val c = s / z
      if (c > bestCorr) {
        bestCorr = c
        bestLag = lag
      }
    }
    return Triple(worst, bestLag, bestCorr)
  }

  private fun check(w: Int, h: Int, horizontal: Boolean, targets: List<Int>) {
    val source = gradient(w, h, horizontal)
    for (target in targets) {
      val size = IntArray(2)
      val out = JpegNative.scalePixelsArgb(source, w, h, target, 0f, size)
      assertNotNull("a resample at $target", out)
      val ow = size[0]
      val oh = size[1]
      // The row (or column) profile of the middle of the image.
      val prof = DoubleArray(if (horizontal) ow else oh) { i ->
        val p = if (horizontal) out!![(oh / 2) * ow + i] else out!![i * ow + ow / 2]
        ((p shr 16) and 0xFF).toDouble()
      }
      val (worst, lag, corr) = ripple(prof)
      Log.i(
        "JpegRipple",
        "source ${w}x$h ${if (horizontal) "h" else "v"}-gradient -> $target: " +
          "${ow}x$oh, departure ${"%.2f".format(worst)} levels, " +
          "period $lag corr ${"%.2f".format(corr)}",
      )
      // A gradient resampled faithfully is still a straight line, to rounding.
      assertTrue("a gradient stays linear at $target (worst ${"%.2f".format(worst)})", worst <= 1.0)
    }
  }

  @Test
  fun aGradientStaysLinear() {
    // Sizes that showed the grid on screen, plus one on each side.
    val big = listOf(1080, 1144, 1208, 1272, 1336)
    // 1300 wide by 2312 tall is the previewed strip of the photo in question, and
    // is well above the size at which rows go to other threads.
    check(1300, 2312, horizontal = true, targets = big)
    check(1300, 2312, horizontal = false, targets = big)
    // Small enough to stay on one thread, to tell a ratio effect from a threading one.
    check(400, 700, horizontal = true, targets = listOf(352, 372, 400))
    check(400, 700, horizontal = false, targets = listOf(352, 372, 400))
  }
}
