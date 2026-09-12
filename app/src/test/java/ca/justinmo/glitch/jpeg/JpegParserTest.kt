package ca.justinmo.glitch.jpeg

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifies the Kotlin JPEG parser/flipper against the reference behaviour of
 * `jpg_glitch.py`. The region byte counts for the checked-in sample files
 * below were produced by running the Python parser; a missing fixture simply
 * skips that case so the tests stay runnable in any checkout.
 */
class JpegParserTest {

  // Expected (DQT, DHT, scan, RST) payload byte counts from jpg_glitch.py.
  private val fixtures = listOf(
    Triple("../a.jpg", intArrayOf(130, 307, 159_208, 0), intArrayOf(1, 3, 10, 0)),
    Triple("../b.jpg", intArrayOf(130, 416, 1_637_257, 0), intArrayOf(2, 4, 1, 0)),
  )

  @Test
  fun realJpegRegionsMatchPythonReference() {
    for ((rel, counts, spanCounts) in fixtures) {
      val file = File(rel)
      assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
      val data = file.readBytes()
      val s = JpegParser.parse(data)
      assertTrue("expected a JPEG", data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte())

      assertEquals("DQT bytes ($rel)", counts[0], s.byteCount(GlitchRegion.DQT))
      assertEquals("DHT bytes ($rel)", counts[1], s.byteCount(GlitchRegion.DHT))
      assertEquals("scan bytes ($rel)", counts[2], s.byteCount(GlitchRegion.SCAN))
      assertEquals("RST bytes ($rel)", counts[3], s.byteCount(GlitchRegion.RST))

      assertEquals("DQT spans ($rel)", spanCounts[0], s.dqt.size)
      assertEquals("DHT spans ($rel)", spanCounts[1], s.dht.size)
      assertEquals("scan spans ($rel)", spanCounts[2], s.scan.size)
      assertEquals("RST spans ($rel)", spanCounts[3], s.rst.size)
    }
  }

  @Test
  fun bitFlipsStayInsideRegionAndNeverChangeFileSize() {
    val file = File("../a.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val original = file.readBytes()
    val s = JpegParser.parse(original)
    val rng = Random(12345)
    val region = GlitchRegion.SCAN

    for (i in 0 until 200) {
      val working = original.copyOf()
      val flipped = JpegGlitch.flipRandomBit(working, s.spansOf(region), rng)
      assertNotNull(flipped)
      val (pos, bit) = flipped!!
      assertEquals("file length must not change", original.size, working.size)

      // exactly one bit toggled inside the region
      var diffs = 0
      for (j in original.indices) {
        if (original[j] != working[j]) {
          diffs++
          assertEquals(j, pos)
          // Implementation flips in unsigned byte space, so compare bytes.
          assertEquals(
            (original[j].toInt() xor (1 shl bit)).toByte(),
            working[j],
          )
        }
      }
      assertEquals(1, diffs)
      val inside = s.spansOf(region).any { pos >= it.start && pos < it.endExclusive }
      assertTrue("flipped byte 0x${pos.toString(16)} must lie in $region", inside)
    }
  }

  @Test
  fun flipRandomBitReturnsNullForEmptyRegion() {
    val working = ByteArray(0)
    assertNull(JpegGlitch.flipRandomBit(working, emptyList(), Random(1)))
  }

  /** A tiny hand-built JPEG: SOI, DQT, DHT, SOF0, SOS + stuffed scan bytes + RST, EOI. */
  private fun syntheticJpeg(): ByteArray {
    val out = ArrayList<Byte>()
    fun seg(marker: Int, payload: ByteArray) {
      out.add(0xFF.toByte()); out.add(marker.toByte())
      val len = payload.size + 2
      out.add((len shr 8).toByte()); out.add(len.toByte())
      payload.forEach { out.add(it) }
    }
    out.add(0xFF.toByte()); out.add(0xD8.toByte()) // SOI

    val quant = ByteArray(65) { if (it == 0) 0 else 8 } // 0x00 id + 64 values
    quant[0] = 0
    seg(0xDB, quant) // DQT

    // DHT payload: 1 table, counts then symbols (make it small but plausible)
    val huff = byteArrayOf(0x00, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x00)
    seg(0xC4, huff) // DHT

    // SOF0: precision 8, 8x8, 1 component
    seg(0xC0, byteArrayOf(8, 0, 8, 0, 8, 1, 0x11, 0x00))

    // SOS: 1 component, then entropy bytes below
    seg(0xDA, byteArrayOf(1, 1, 0x00, 0, 63, 0))

    // entropy-coded data: plain byte, stuffed FF 00, plain byte, RST0, more bytes, then EOI
    byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56, 0x78)
      .forEach { out.add(it) }
    out.add(0xFF.toByte()); out.add(0xD9.toByte()) // EOI
    return ByteArray(out.size) { out[it] }
  }

  @Test
  fun syntheticJpegParsesSpansAndStuffing() {
    val data = syntheticJpeg()
    val s = JpegParser.parse(data)
    assertEquals(65, s.byteCount(GlitchRegion.DQT))
    assertEquals(18, s.byteCount(GlitchRegion.DHT))

    // Entropy data before the RST: 0x12, (0xFF 0x00 stuffed), 0x34 -> 4 bytes.
    // After the RST: 0x56 0x78 -> 2 bytes. The RST marker is its own span.
    val scanBytes = s.scan.sumOf { it.size }
    assertEquals(6, scanBytes)
    assertEquals(1, s.rst.size)
    assertEquals(2, s.rst[0].size)
  }

  @Test
  fun flippingEveryRegionKeepsFileSizeAndDoesNotCrashTheParser() {
    val file = File("../b.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val original = file.readBytes()
    val rng = Random(7)
    for (region in GlitchRegion.entries) {
      val spans = JpegParser.parse(original).spansOf(region)
      val working = original.copyOf()
      var flippedBits = 0
      repeat(30) {
        val before = working.copyOf()
        val flipped = JpegGlitch.flipRandomBit(working, spans, rng)
        if (flipped != null) {
          flippedBits++
          val (pos, bit) = flipped
          assertTrue(
            "flip must stay inside $region",
            spans.any { pos >= it.start && pos < it.endExclusive },
          )
          assertEquals(
            "byte at $pos toggled bit $bit",
            (before[pos].toInt() xor (1 shl bit)).toByte(),
            working[pos],
          )
        }
        assertEquals("file size must never change", original.size, working.size)
      }
      // The mutated file must still be walkable by the parser.
      assertTrue(JpegParser.parse(working).scan.isNotEmpty() || flippedBits == 0)
    }
  }
}
