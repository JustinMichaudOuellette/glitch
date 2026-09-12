package ca.justinmo.glitch.scan

import ca.justinmo.glitch.jpeg.ByteSpans
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.jpeg.JpegStructure

/**
 * Which bits the scan screen flips, worked out from the controls alone.
 *
 * The point of this screen is that the same settings always write the same file.
 * The editor flips a *random* bit and keeps it if the picture changed, which is
 * how you explore; here the damage is chosen - a band of the picture, and how hard
 * to hit it - and it is read out of the band's own bytes, so a setting can be
 * dialled, compared and come back to. Nothing here is random, and the plan depends
 * only on the file's spans, so it can be tested without a device.
 */
internal object ScanSweep {

  /** One bit to flip: the byte's offset in the file, and which bit (0 = LSB). */
  data class Flip(val offset: Int, val bit: Int)

  /** The bytes a band of the picture owns: restart markers punched out already. */
  fun bytesFor(structure: JpegStructure, centre: Float, bandWidth: Float): IntRange? {
    val geometry = structure.geometry.firstOrNull() ?: return null
    if (geometry.unitRows <= 0 || geometry.unitsPerRow <= 0) return null
    val half = bandWidth.coerceIn(0.01f, 1f) / 2f
    val from = (centre - half).coerceIn(0f, 1f)
    val to = (centre + half).coerceIn(0f, 1f)
    if (to <= from) return null
    val firstUnit = (from * geometry.totalUnits).toInt().coerceIn(0, geometry.totalUnits - 1)
    val lastUnit = (to * geometry.totalUnits).toInt().coerceIn(firstUnit + 1, geometry.totalUnits)
    val start = geometry.offsetForUnit(firstUnit)
    val end = (geometry.offsetForUnit(lastUnit - 1) + 1)
      .coerceAtLeast(start + 1)
      .coerceAtMost(structure.scan.lastOrNull()?.endExclusive ?: (start + 1))
    return start until end
  }

  /**
   * The flips for a band covering [bandWidth] of the picture's height, centred on
   * [centre], hitting each selected block about [bitsPerUnit] times.
   *
   * The bits are spread evenly through the band's bytes rather than clustered, and
   * step through the bit positions as they go, so a handful of flips touches as
   * much of the entropy-coded stream as the strength asks for rather than the same
   * bit of neighbouring bytes. The spans exclude the markers and the tables - a
   * flip there does not decode at all - and the restart markers are punched out
   * here as well, since they sit inside the scan but carry a sync code the decoder
   * checks.
   */
  fun plan(
    structure: JpegStructure,
    centre: Float,
    bandWidth: Float,
    bitsPerUnit: Int,
  ): List<Flip> {
    val geometry = structure.geometry.firstOrNull() ?: return emptyList()
    val range = bytesFor(structure, centre, bandWidth) ?: return emptyList()
    val half = bandWidth.coerceIn(0.01f, 1f) / 2f
    val from = (centre - half).coerceIn(0f, 1f)
    val firstUnit = (from * geometry.totalUnits).toInt().coerceIn(0, geometry.totalUnits - 1)
    val lastUnit = ((centre + half).coerceIn(0f, 1f) * geometry.totalUnits)
      .toInt().coerceIn(firstUnit + 1, geometry.totalUnits)

    val band = ByteSpans
      .subtract(structure.flippableSpans(GlitchRegion.SCAN), structure.spansOf(GlitchRegion.RST))
      .filter { it.endExclusive > range.first && it.start <= range.last }

    var bytes = 0
    for (span in band) {
      bytes += (minOf(span.endExclusive, range.last + 1) - maxOf(span.start, range.first)).coerceAtLeast(0)
    }
    if (bytes <= 0) return emptyList()

    val wanted = (lastUnit - firstUnit).coerceAtLeast(1) * bitsPerUnit.coerceIn(1, 8)
    val stride = (bytes / wanted).coerceAtLeast(1)

    val flips = ArrayList<Flip>(wanted)
    var seen = 0
    var step = 0
    for (span in band) {
      for (offset in maxOf(span.start, range.first) until minOf(span.endExclusive, range.last + 1)) {
        if (seen % stride == 0) {
          flips += Flip(offset, step % 8)
          step++
        }
        seen++
      }
    }
    return flips
  }

  /**
   * The [step]-th flip in the block row the area slider points at.
   *
   * A press of Flip walks the row rather than sampling it: the first step hits the
   * row's first byte, the next its second, and once the row is walked the bit
   * position moves on and it starts again. [findEffectiveFlip] may skip steps whose
   * result changes nothing or does not decode, which keeps the walk deterministic -
   * the same row and the same presses always reach the same bit.
   */
  fun flipAt(structure: JpegStructure, areaFraction: Float, step: Int): Flip? {
    val geometry = structure.geometry.firstOrNull() ?: return null
    if (geometry.unitRows <= 0 || geometry.unitsPerRow <= 0) return null
    val rowsPerUnit = geometry.rowsPerUnitRow.coerceAtLeast(1)
    val imageRow = (areaFraction.coerceIn(0f, 1f) * geometry.imageHeight).toInt()
    val unitRow = (imageRow / rowsPerUnit).coerceIn(0, geometry.unitRows - 1)
    val firstUnit = unitRow * geometry.unitsPerRow
    val lastUnit = (firstUnit + geometry.unitsPerRow - 1).coerceAtMost(geometry.totalUnits - 1)
    val start = geometry.offsetForUnit(firstUnit)
    val end = geometry.offsetForUnit(lastUnit) + 1
    val band = ByteSpans
      .subtract(structure.flippableSpans(GlitchRegion.SCAN), structure.spansOf(GlitchRegion.RST))
      .filter { it.endExclusive > start && it.start < end }

    var bytes = 0
    for (span in band) {
      bytes += (minOf(span.endExclusive, end) - maxOf(span.start, start)).coerceAtLeast(0)
    }
    if (bytes <= 0 || step < 0) return null

    val wanted = step % bytes
    val bit = (step / bytes) % 8
    var seen = 0
    for (span in band) {
      for (offset in maxOf(span.start, start) until minOf(span.endExclusive, end)) {
        if (seen == wanted) return Flip(offset, bit)
        seen++
      }
    }
    return null
  }

  /** [data] with [flips] applied, as a copy. */
  fun apply(data: ByteArray, flips: List<Flip>): ByteArray {
    val out = data.copyOf()
    for (flip in flips) {
      if (flip.offset in out.indices) {
        out[flip.offset] = (out[flip.offset].toInt() xor (1 shl flip.bit)).toByte()
      }
    }
    return out
  }

  /** A candidate that passed the effect test, with the file it made and the cursor after it. */
  data class EffectiveFlip(val flip: Flip, val bytes: ByteArray, val nextStep: Int)

  /**
   * Walk the row from [startStep] until [isEffective] accepts a candidate, giving up
   * after [maxAttempts].
   *
   * A press of Flip should not spend itself on a bit that leaves the picture
   * identical or makes the file stop decoding, so the screen tests candidates in
   * the same deterministic order [flipAt] walks and takes the first that passes.
   * The cursor is returned with the result - and is the caller's cue to move past
   * the candidates that were skipped - so the same row and the same presses still
   * write the same file.
   */
  suspend fun findEffectiveFlip(
    structure: JpegStructure,
    areaFraction: Float,
    startStep: Int,
    maxAttempts: Int,
    base: ByteArray,
    isEffective: suspend (Flip, ByteArray) -> Boolean,
  ): EffectiveFlip? {
    var step = startStep
    var tried = 0
    while (tried < maxAttempts) {
      val flip = flipAt(structure, areaFraction, step) ?: return null
      step++
      tried++
      val candidate = apply(base, listOf(flip))
      if (isEffective(flip, candidate)) return EffectiveFlip(flip, candidate, step)
    }
    return null
  }
}
