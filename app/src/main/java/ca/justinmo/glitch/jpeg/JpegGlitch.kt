package ca.justinmo.glitch.jpeg

import kotlin.random.Random

/**
 * Pure-Kotlin port of the JPEG region parsing + bit-flip logic from
 * `jpg_glitch.py` (no Android dependencies, so it can be unit-tested on a JVM).
 *
 * The JPEG is walked segment by segment and split into corruptable byte
 * regions, mirroring the Python tool:
 *
 *  - DQT  quantization tables (payload bytes only)
 *  - DHT  Huffman tables (payload bytes only)
 *  - SCAN scan / entropy data (default, the classic glitch region)
 *  - RST  restart markers (FF D0-FF D7 inside scan data)
 *
 * Ranges never include marker/length bytes for DQT/DHT and never include the
 * EOI marker. Byte offsets returned by the parser refer to the *original*
 * source byte array; since bit flips never change the file length, the
 * regions stay valid while the working buffer is mutated.
 */

/** Half-open byte span [start, endExclusive), like the Python ranges. */
class ByteSpan(val start: Int, val endExclusive: Int) {
  val size: Int get() = endExclusive - start
  override fun toString(): String = "[$start, $endExclusive)"
}

/**
 * The four corruptable regions, mirroring jpg_glitch.py's number keys.
 * Display labels live in string resources (see regionCodeRes / regionDescriptionRes).
 */
enum class GlitchRegion { DQT, DHT, SCAN }

/** Parsed corruption regions of one JPEG. */
class JpegStructure(
  val dqt: List<ByteSpan>,
  val dht: List<ByteSpan>,
  val scan: List<ByteSpan>,
  val rst: List<ByteSpan>,
) {
  fun spansOf(region: GlitchRegion): List<ByteSpan> = when (region) {
    GlitchRegion.DQT -> dqt
    GlitchRegion.DHT -> dht
    GlitchRegion.SCAN -> scan
  }

  fun byteCount(region: GlitchRegion): Int = spansOf(region).sumOf { it.size }
}

object JpegParser {
  // JPEG marker codes.
  private const val EOI = 0xD9 // end of image
  private const val SOS = 0xDA // start of scan (entropy-coded data follows)
  private const val DQT = 0xDB // define quantization table(s)
  private const val DHT = 0xC4 // define Huffman table(s)

  /**
   * Walk the JPEG structure and return byte ranges per corruptable region.
   * Mirrors `parse_jpeg` in jpg_glitch.py.
   */
  fun parse(data: ByteArray): JpegStructure {
    val dqt = mutableListOf<ByteSpan>()
    val dht = mutableListOf<ByteSpan>()
    val scan = mutableListOf<ByteSpan>()
    val rst = mutableListOf<ByteSpan>()
    val n = data.size
    var pos = 2 // just past SOI (FF D8)

    while (pos + 1 < n) {
      if ((data[pos].toInt() and 0xFF) != 0xFF) break // lost sync - stop cleanly
      val code = data[pos + 1].toInt() and 0xFF
      if (code == 0xFF) { // fill bytes before a marker code
        pos += 1
        continue
      }
      if (code == EOI) break
      // standalone markers with no length field (TEM/RST/SOI/EOI)
      if (code == 0x01 || code in 0xD0..0xD8) {
        pos += 2
        continue
      }
      if (pos + 3 >= n) break // not enough bytes for a segment length
      val segLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
      if (segLen < 2 || pos + 2 + segLen > n) break // bogus length - refuse to walk further
      val segEnd = pos + 2 + segLen

      when (code) {
        SOS -> {
          pos = consumeScan(data, segEnd, scan, rst)
          continue
        }
        DQT -> dqt.add(ByteSpan(pos + 4, segEnd)) // payload only
        DHT -> dht.add(ByteSpan(pos + 4, segEnd)) // payload only
      }
      pos = segEnd
    }
    return JpegStructure(dqt, dht, scan, rst)
  }

  /**
   * Classify entropy-coded data of one scan starting at offset [p0].
   * Handles 0xFF-stuffing (FF 00 = a data byte 0xFF) and standalone restart
   * markers (FF D0-FF D7). Returns the offset of the marker that terminates
   * the scan (or data.size at end of file). Mirrors `_consume_scan`.
   */
  private fun consumeScan(
    data: ByteArray,
    p0: Int,
    scan: MutableList<ByteSpan>,
    rst: MutableList<ByteSpan>,
  ): Int {
    val n = data.size
    var p = p0
    var runStart = p0
    while (p < n) {
      val b = data[p].toInt() and 0xFF
      if (b != 0xFF) {
        p += 1
        continue
      }
      val nb = if (p + 1 < n) data[p + 1].toInt() and 0xFF else null
      if (nb == 0) { // stuffed 0xFF data byte - part of the scan
        p += 2
        continue
      }
      if (nb == null) { // lone 0xFF at end of file: treat as scan byte
        p += 1
        continue
      }
      if (nb in 0xD0..0xD7) { // restart marker: its own region
        if (p > runStart) scan.add(ByteSpan(runStart, p))
        rst.add(ByteSpan(p, p + 2))
        p += 2
        runStart = p
        continue
      }
      // any other marker (EOI, next SOS, ...) ends this scan
      if (p > runStart) scan.add(ByteSpan(runStart, p))
      return p
    }
    if (p > runStart) scan.add(ByteSpan(runStart, p))
    return n
  }
}

object JpegGlitch {
  /** Attempts per flip press (roll back undecodable / no-op flips), as in the Python tool. */
  const val MAX_FLIP_TRIES = 50

  /**
   * Flip one random bit inside the union of [spans]. Mutates [data].
   * Returns (byteOffset, bitIndex) or null if the region has no bytes.
   */
  fun flipRandomBit(data: ByteArray, spans: List<ByteSpan>, rng: Random = Random.Default): Pair<Int, Int>? {
    val total = spans.sumOf { it.size }
    if (total <= 0) return null
    var k = rng.nextInt(total) // uniform over all region bytes
    for (span in spans) {
      if (k < span.size) {
        val pos = span.start + k
        val bit = rng.nextInt(8)
        data[pos] = (data[pos].toInt() xor (1 shl bit)).toByte()
        return pos to bit
      }
      k -= span.size
    }
    throw IllegalStateException("unreachable")
  }
}
