package ca.justinmo.glitch

import android.graphics.Bitmap

/**
 * The decoded previews the flow is carrying, kept so that moving between steps does not have to
 * decode the same file again - and, more to the point, so that a screen arriving does not spend
 * its first frames with nothing to show.
 *
 * Every screen decodes the file itself, into its own `remember`, and the flow swaps whole
 * screens in and out of the composition on every step: so the picture was decoded again on every
 * arrival. The layout no longer jumps (screens take the photo's shape out of the file's header
 * before they have decoded anything - see `previewFrame`), but the *picture* still vanished for
 * a hundred milliseconds and then came back, on both ends of every transition that touched the
 * same file.
 *
 * What makes a held frame safe to hand out is [showable]: it is only reused for a file it was
 * decoded from, or for one of the same shape. Both halves matter. The first ties the picture to
 * the bytes, which is what keeps a reused frame from showing the previous edit; the second is
 * what makes the reuse *useful*, because a step's edit produces a new array with the same
 * proportions - a flip here, a quantizer there - and the picture the user is about to see is the
 * one on screen with one thing changed.
 */
internal class PreviewImage {
  /** One decoded frame: the file it came from, the detail it carries, and the pixels. */
  private class Held(val bytes: ByteArray, val cap: Int, val image: Bitmap)

  /**
   * The frames being held, most recently put first.
   *
   * Two, rather than one, and the reason is the resize screen: it is the only step that shows
   * the file the flow was *handed* - it derives the crop from it - while every other step shows
   * the file the steps have *made*. Walking back and forth therefore alternates between two
   * files, and with a single slot the entry the arriving step wants is always the entry the
   * step before it replaced. Holding both is what makes the walk seamless in either direction,
   * and two is also the bound: a third distinct file evicts the oldest.
   */
  private val held = ArrayDeque<Held>()

  /**
   * What a screen showing [bytes] may draw on its first frame, or null when there is nothing
   * that would not be a lie.
   *
   * [shape] is the size the photo on *that* screen measures in file pixels, which every screen
   * can read from the header. Null means the caller does not know it yet, and then only an
   * exact match counts.
   */
  fun showable(bytes: ByteArray, shape: Pair<Int, Int>?): Bitmap? =
    (held.firstOrNull { it.bytes === bytes }
      ?: held.firstOrNull { sameShape(it.image.width, it.image.height, shape) })
      ?.image

  /**
   * Whether a frame held for these very bytes carries at least as much detail as [cap] wants.
   *
   * A screen whose decode is capped higher than the held one still shows the held picture
   * while it decodes - it is the same file, and a slightly softer frame beats a blank one -
   * but it does have to decode, so this is the question it asks first.
   */
  fun covers(bytes: ByteArray, cap: Int): Boolean =
    held.any { it.bytes === bytes && it.cap >= cap }

  /** Keep [bitmap] as the picture for [bytes], as decoded at [cap]. */
  fun put(bytes: ByteArray, cap: Int, bitmap: Bitmap) {
    held.removeAll { it.bytes === bytes }
    held.addFirst(Held(bytes, cap, bitmap))
    while (held.size > SLOTS) held.removeLast()
  }

  /** Forget everything held. */
  fun clear() {
    held.clear()
  }

  private companion object {
    /** How many files are worth remembering: see [held]. */
    const val SLOTS = 2
  }
}

/**
 * Whether a frame [heldWidth] x [heldHeight] is the shape [shape] describes: the two
 * proportions agree, or the caller has not said what to expect.
 *
 * A frame that was decoded from a *derived* file - the scan screen's flips, the DQT screen's
 * quantizers - is the same shape as the file it came from, so this is what lets one step show
 * the picture the last one was showing. A crop is the case it refuses: the resize screen's
 * 9:16 frame is not the 4:3 photo it was cut from, and showing one laid out as the other would
 * put the furniture beside a photo that is not where the frame says.
 *
 * On the numbers rather than on the bitmap so that `PreviewImageTest` can check the rule: the
 * holder needs a real decoded image to hold, and this is the part of it that could be wrong.
 */
internal fun sameShape(heldWidth: Int, heldHeight: Int, shape: Pair<Int, Int>?): Boolean {
  if (shape == null) return false
  val (width, height) = shape
  if (width <= 0 || height <= 0 || heldWidth <= 0 || heldHeight <= 0) return false
  // Cross-multiplied rather than divided, so the comparison is exact arithmetic on the
  // proportions and cannot be knocked off by rounding on either side.
  return heldWidth.toLong() * height == heldHeight.toLong() * width
}
