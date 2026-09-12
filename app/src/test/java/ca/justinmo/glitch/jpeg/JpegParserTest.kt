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

  // --- Fatal-byte exclusion -------------------------------------------------
  //
  // Exhaustive single-bit sweeps (every bit of every DQT/DHT byte, full decode)
  // found bytes where all 8 bits make the file undecodable:
  //   - DQT: the table spec byte before each 64/128-entry table
  //   - DHT: the table spec byte plus the 16 code-length counts (BITS[1..16])
  // jpeg_tables_count.py counts 2 DQT tables and 8 DHT tables in a.jpg,
  // 2 and 4 in b.jpg, i.e. 2 and 4*(17)=68 fatal bytes in b.jpg.

  @Test
  fun fatalBytesAreExcludedFromFlippableRegions() {
    for ((rel, dhtTables) in listOf("../a.jpg" to 8, "../b.jpg" to 4)) {
      val file = File(rel)
      assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
      val s = JpegParser.parse(file.readBytes())

      assertEquals("$rel DQT fatal bytes (2 spec bytes)", 2, s.fatalByteCount(GlitchRegion.DQT))
      assertEquals("$rel DQT usable bytes", 128, s.flippableByteCount(GlitchRegion.DQT))
      assertEquals(
        "$rel DHT fatal bytes (spec + 16 counts per table)",
        17 * dhtTables,
        s.fatalByteCount(GlitchRegion.DHT),
      )
      assertEquals(
        "$rel DHT usable bytes",
        s.byteCount(GlitchRegion.DHT) - 17 * dhtTables,
        s.flippableByteCount(GlitchRegion.DHT),
      )
      // Scan data holds no fatal bytes: every scan byte is a legitimate target.
      assertEquals(
        "$rel SCAN must be fully flippable",
        s.byteCount(GlitchRegion.SCAN),
        s.flippableByteCount(GlitchRegion.SCAN),
      )

      // Usable + fatal must reconstruct each region exactly, and no byte in the
      // usable set may be fatal.
      for (region in listOf(GlitchRegion.DQT, GlitchRegion.DHT, GlitchRegion.SCAN)) {
        assertEquals(
          "$rel $region usable + fatal == total",
          s.byteCount(region),
          s.flippableByteCount(region) + s.fatalByteCount(region),
        )
        for (span in s.flippableSpans(region)) {
          for (offset in span.start until span.endExclusive) {
            assertTrue("$rel $region byte $offset must not be fatal", !s.isFatal(offset))
          }
        }
      }
    }
  }

  @Test
  fun flipRandomSafeBitNeverLandsOnAFatalByte() {
    val file = File("../b.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val original = file.readBytes()
    val s = JpegParser.parse(original)
    val rng = Random(4242)
    for (region in listOf(GlitchRegion.DQT, GlitchRegion.DHT)) {
      val usable = s.flippableSpans(region)
      repeat(300) {
        val working = original.copyOf()
        val flipped = JpegGlitch.flipRandomSafeBit(working, s.spansOf(region), s.fatal, rng)
        assertNotNull("$region should have usable bytes", flipped)
        assertTrue(
          "$region flip landed on a fatal byte at ${flipped!!.first}",
          !s.isFatal(flipped.first),
        )
        assertTrue(
          "$region flip must stay in the usable set",
          usable.any { flipped.first >= it.start && flipped.first < it.endExclusive },
        )
      }
    }
  }

  // --- Progressive per-scan metadata ---------------------------------------
  //
  // a.jpg is progressive with 10 scans. Reference values produced directly from
  // the SOS headers (jpeg_tables_count.py); corrupting scan 0 measured 47.7% of
  // pixels changed on average versus 0.0% for the scan 6 DC refinement, which is
  // why these need to be separately targetable instead of one "Scan" region.

  @Test
  fun progressiveScanHeadersMatchReference() {
    val file = File("../a.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val s = JpegParser.parse(file.readBytes())
    assertTrue("a.jpg must parse as progressive", s.isProgressive)
    assertEquals(10, s.scans.size)

    // bytes, componentIds, Ss, Se, Ah, Al
    val expected = listOf(
      listOf(35_207, listOf(1, 2, 3), 0, 0, 0, 1),
      listOf(17_686, listOf(1), 1, 5, 0, 2),
      listOf(3_702, listOf(3), 1, 63, 0, 1),
      listOf(3_115, listOf(2), 1, 63, 0, 1),
      listOf(3_114, listOf(1), 6, 63, 0, 2),
      listOf(23_043, listOf(1), 1, 63, 2, 1),
      listOf(9_931, listOf(1, 2, 3), 0, 0, 1, 0),
      listOf(6_224, listOf(3), 1, 63, 1, 0),
      listOf(6_007, listOf(2), 1, 63, 1, 0),
      listOf(51_179, listOf(1), 1, 63, 1, 0),
    )
    s.scans.forEachIndexed { i, scan ->
      assertEquals("scan $i bytes", expected[i][0], scan.byteCount)
      assertEquals("scan $i components", expected[i][1], scan.componentIds)
      assertEquals("scan $i Ss", expected[i][2], scan.ss)
      assertEquals("scan $i Se", expected[i][3], scan.se)
      assertEquals("scan $i Ah", expected[i][4], scan.ah)
      assertEquals("scan $i Al", expected[i][5], scan.al)
    }
    // Scan 0 is the DC scan and scan 6 a DC refinement - the two ends of the
    // usefulness range the UI now labels.
    assertTrue("scan 0 must be the DC scan", s.scans[0].isDcOnly)
    assertTrue("scan 0 is not a refinement", !s.scans[0].isRefinement)
    assertTrue("scan 6 must be a DC refinement", s.scans[6].isRefinement && s.scans[6].isDcOnly)
    // Every scan must have its own geometry for the area slider.
    assertEquals(10, s.geometry.size)
  }

  @Test
  fun baselineFileHasOneFullScan() {
    val file = File("../b.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val s = JpegParser.parse(file.readBytes())
    assertTrue("b.jpg must parse as baseline", !s.isProgressive)
    assertEquals(1, s.scans.size)
    assertTrue("b.jpg scan must be the full baseline scan", s.scans[0].isBaselineFull)
    assertEquals(3, s.scans[0].componentCount)
  }

  // --- Area control: the offset <-> image-row mapping -----------------------

  @Test
  fun geometryAimsDamageWithinOneUnitRow() {
    for (rel in listOf("../a.jpg", "../b.jpg")) {
      val file = File(rel)
      assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
      val s = JpegParser.parse(file.readBytes())
      for (geom in s.geometry) {
        val rowHeight = geom.rowsPerUnitRow.toFloat() / geom.imageHeight
        for (step in 1..9) {
          val aim = step / 10f
          val band = geom.byteSpanForRowFraction(aim)
          assertTrue(
            "$rel scan#${geom.scan.index} band at $aim must be non-empty",
            band.size >= 1,
          )
          // The band must lie inside the target scan.
          assertTrue(
            "$rel scan#${geom.scan.index} band must stay inside the scan",
            band.start >= geom.scan.start && band.endExclusive <= geom.scan.endExclusive,
          )
          val start = geom.damageStartFraction(band.start)
          val end = geom.damageStartFraction((band.endExclusive - 1).coerceAtLeast(band.start))
          assertEquals(
            "$rel scan#${geom.scan.index} aim $aim within one unit row",
            aim,
            start,
            rowHeight + 1e-6f,
          )
          // A band may straddle one row boundary, never more.
          assertTrue(
            "$rel scan#${geom.scan.index} band spans more than one unit row at $aim",
            kotlin.math.abs(end - start) <= rowHeight + 1e-6f,
          )
        }
      }
    }
  }

  @Test
  fun geometryMatchesFrameAndMcuCounts() {
    val file = File("../b.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val s = JpegParser.parse(file.readBytes())
    val frame = s.frame
    assertNotNull("b.jpg must expose a frame header", frame)
    assertEquals(3472, frame!!.width)
    assertEquals(4624, frame.height)
    assertEquals(16, frame.mcuWidth)
    assertEquals(16, frame.mcuHeight)
    assertEquals(217, frame.mcusPerRow)
    assertEquals(289, frame.mcuRows)
    val geom = s.geometryFor(0)
    assertNotNull("b.jpg scan 0 must have geometry", geom)
    assertEquals("interleaved MCU grid", 217 * 289, geom!!.totalUnits)
    assertEquals(217, geom.unitsPerRow)
    assertEquals(frame.mcuHeight, geom.rowsPerUnitRow)
  }
}
