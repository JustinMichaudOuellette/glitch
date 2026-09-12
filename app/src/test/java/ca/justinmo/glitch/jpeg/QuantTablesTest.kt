package ca.justinmo.glitch.jpeg

import ca.justinmo.glitch.dqt.QuantAxis
import ca.justinmo.glitch.dqt.QuantTables
import ca.justinmo.glitch.dqt.flipQuantizerBit
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Covers the quantization-table model the DQT screen is built on: the byte
 * layout it reads, the families it sorts quantizers into, and the write-back it
 * uses when a bit is toggled.
 *
 * The expected values for `a.jpg` were produced by the Python reference
 * (`jpg_glitch.py` walks the same payload), so a drift in either parser shows up
 * here. A missing fixture skips that case, matching [JpegParserTest].
 */
class QuantTablesTest {

  // a.jpg holds both tables in one DQT segment: spec 0x00 (luma, 8-bit) then 64
  // values, then spec 0x01 (chroma, 8-bit) then 64 values.
  private val aJpegLumaHead = intArrayOf(5, 6, 6, 11, 8, 11, 11, 11, 11, 11, 13, 11)
  private val aJpegLumaTail = intArrayOf(25, 22, 22, 18)
  private val aJpegChromaHead = intArrayOf(5, 5, 5, 10, 7, 10, 8, 9, 9, 8, 11, 8)

  private fun structureOf(
    spans: List<ByteSpan>,
    components: List<ComponentSampling>? = null,
    scans: List<ScanInfo> = emptyList(),
  ): JpegStructure = JpegStructure(
    dqt = spans,
    dht = emptyList(),
    scan = emptyList(),
    rst = emptyList(),
    scans = scans,
    geometry = emptyList(),
    frame = components?.let { JpegFrame(8, 8, 8, progressive = false, components = it) },
    fatal = emptyList(),
    metadata = emptyList(),
  )

  /** One 8-bit table per pair, spec byte + 64 quantizers, 65 bytes apiece. */
  private fun payloadOfTables(vararg tables: Pair<Int, Int>): ByteArray {
    val data = ByteArray(tables.size * 65)
    tables.forEachIndexed { index, (id, value) ->
      val at = index * 65
      data[at] = id.toByte()
      for (i in 0 until 64) data[at + 1 + i] = value.toByte()
    }
    return data
  }

  private fun spansOf(count: Int): List<ByteSpan> =
    (0 until count).map { ByteSpan(it * 65, (it + 1) * 65) }

  private fun scan(index: Int, markerOffset: Int, componentIds: List<Int>): ScanInfo = ScanInfo(
    index = index,
    markerOffset = markerOffset,
    start = markerOffset + 4,
    endExclusive = markerOffset + 5,
    components = componentIds.map { ScanComponent(it, 0, 0) },
    ss = 0,
    se = 63,
    ah = 0,
    al = 0,
  )

  private fun aJpeg(): ByteArray {
    val file = File("../a.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  @Test
  fun readsBothTablesOfARealJpeg() {
    val data = aJpeg()
    val structure = JpegParser.parse(data)
    val tables = QuantTables.parse(data, structure)

    assertEquals("two tables", 2, tables.size)
    assertEquals("luma table id", 0, tables[0].id)
    assertEquals("chroma table id", 1, tables[1].id)
    assertFalse("first table is luma", tables[0].isChroma)
    assertTrue("second table is chroma", tables[1].isChroma)
    assertEquals("8-bit precision", 0, tables[0].precision)

    val luma = tables[0].entries
    val chroma = tables[1].entries
    assertEquals("64 quantizers per table", 64, luma.size)
    assertEquals(64, chroma.size)

    // Values, in zigzag order, exactly as jpg_glitch.py reads them.
    for (i in aJpegLumaHead.indices) {
      assertEquals("luma[$i]", aJpegLumaHead[i], luma[i].value)
    }
    for (i in aJpegLumaTail.indices) {
      assertEquals("luma tail", aJpegLumaTail[i], luma[60 + i].value)
    }
    for (i in aJpegChromaHead.indices) {
      assertEquals("chroma[$i]", aJpegChromaHead[i], chroma[i].value)
    }

    // One byte per quantizer, packed straight after the table's spec byte.
    val span = structure.dqt.first()
    assertEquals("payload is spec + 128", 129, span.size - 1)
    assertEquals("first quantizer follows the spec byte", span.start + 1, luma[0].offset)
    for (i in 0 until 64) {
      assertEquals("luma offset $i", span.start + 1 + i, luma[i].offset)
      assertEquals("chroma offset $i", span.start + 66 + i, chroma[i].offset)
      assertEquals("one byte each", 1, luma[i].byteCount)
    }

    // The spec bytes are the ones no flip can survive, so they are never rows.
    val specOffsets = setOf(span.start, span.start + 65)
    assertTrue(
      "spec bytes are fatal",
      specOffsets.all { structure.isFatal(it) },
    )
    assertTrue(
      "no quantizer is fatal",
      tables.flatMap { it.entries }.none { structure.isFatal(it.offset) },
    )
  }

  @Test
  fun splitsTablesAcrossSegmentsToo() {
    // Pillow writes one DQT segment per table; the screen must not care.
    val payload = ByteArray(130)
    payload[0] = 0x00
    for (i in 0 until 64) payload[1 + i] = aJpegLumaHead[i % aJpegLumaHead.size].toByte()
    payload[65] = 0x01
    for (i in 0 until 64) payload[66 + i] = aJpegChromaHead[i % aJpegChromaHead.size].toByte()
    val structure = structureOf(listOf(ByteSpan(0, 65), ByteSpan(65, 130)))

    val tables = QuantTables.parse(payload, structure)
    assertEquals(2, tables.size)
    assertFalse(tables[0].isChroma)
    assertTrue(tables[1].isChroma)
    assertEquals(64, tables[0].entries.size)
    assertEquals(span0FirstValues(), tables[0].entries.take(12).map { it.value })
    assertEquals(66, tables[1].entries[0].offset)
  }

  private fun span0FirstValues(): List<Int> =
    (0 until 12).map { aJpegLumaHead[it % aJpegLumaHead.size] }

  @Test
  fun keepsBothTablesOfARealThreeComponentJpeg() {
    // The other direction: both ids the frame selects survive the filter, and the
    // scans are placed so neither definition is mistaken for a dead one.
    val file = File("src/androidTest/assets/parity/encoded_q92.jpg")
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    val data = file.readBytes()
    val structure = JpegParser.parse(data)
    assumeTrue("fixture has no frame/scans", structure.frame != null && structure.scans.isNotEmpty())

    val tables = QuantTables.parse(data, structure)

    assertEquals(listOf(0, 1), tables.map { it.id })
    assertEquals(64, tables[0].entries.size)
    assertEquals(64, tables[1].entries.size)
  }

  @Test
  fun randomFlipsOnlyTouchBitsBigEnoughToShow() {
    // A flip has to change the decoded quantizer enough that the coefficients
    // this picture has move pixels. The low bits cannot promise that (and 0 <-> 1
    // both decode as 1), so every draw must land on bit 4 or above.
    val random = Random(7)
    fun check(value: Int, bitCount: Int) {
      val flipped = flipQuantizerBit(value, bitCount, random)
      val moved = value xor flipped
      assertTrue("a bit moved for $value/$bitCount", moved != 0)
      assertTrue(
        "bit ${Integer.numberOfTrailingZeros(moved)} is below bit 4 for $value",
        Integer.numberOfTrailingZeros(moved) >= 4,
      )
      assertTrue("stays inside $bitCount bits", flipped in 0..((1 shl bitCount) - 1))
      // A zero quantizer is decoded as one; measure the change the decoder sees.
      val before = if (value == 0) 1 else value
      val after = if (flipped == 0) 1 else flipped
      assertTrue(
        "decoded quantizer moved only ${kotlin.math.abs(after - before)} ($before -> $after)",
        kotlin.math.abs(after - before) >= 15,
      )
    }
    repeat(100) {
      for (value in intArrayOf(0, 1, 5, 25, 127, 255)) check(value, 8)
      for (value in intArrayOf(0, 1, 300, 65535)) check(value, 16)
    }
  }

  @Test
  fun marksQuantizersWhoseCoefficientNeverOccursAsDead() {
    // Coefficient masks from the decoder: table 0 has a non-zero DC and (1,1);
    // table 1 only a non-zero DC. Everything else decodes to zero, so no
    // quantizer of those coefficients can move a pixel.
    val data = payloadOfTables(0 to 5, 1 to 7)
    val structure = structureOf(
      spansOf(2),
      components = listOf(
        ComponentSampling(1, 2, 2, 0),
        ComponentSampling(2, 1, 1, 1),
      ),
    )
    val masks = longArrayOf(1L or (1L shl 4), 1L)

    val tables = QuantTables.parse(data, structure, masks)
    val luma = tables[0]
    val chroma = tables[1]

    assertEquals("all 64 entries stay available to the writer", 64, luma.entries.size)
    assertEquals(
      "only DC and coefficient 4 are live",
      listOf(0, 4),
      luma.entries.filter { it.live }.map { it.coefficient },
    )
    assertEquals("one live DC row", 1, luma.entriesFor(QuantAxis.DC).size)
    assertEquals("one live checkerboard row", 1, luma.entriesFor(QuantAxis.CHECKERED).size)
    assertEquals(4, luma.entriesFor(QuantAxis.CHECKERED).single().coefficient)
    assertEquals("the dead bands are not offered", 0, luma.entriesFor(QuantAxis.VERTICAL).size)

    assertEquals(1, chroma.entriesFor(QuantAxis.DC).size)
    assertEquals("chroma has no live AC rows", 0, chroma.liveEntries.count { it.axis != QuantAxis.DC })
    assertEquals("but its dead entries are still written back", 64, chroma.entries.size)
  }

  @Test
  fun withoutMasksEveryQuantizerIsLive() {
    val data = payloadOfTables(0 to 5, 1 to 7)
    val structure = structureOf(
      spansOf(2),
      components = listOf(
        ComponentSampling(1, 1, 1, 0),
        ComponentSampling(2, 1, 1, 1),
      ),
    )

    val tables = QuantTables.parse(data, structure)

    assertEquals("no census means no hiding", 64, tables[0].liveEntries.size)
    assertEquals(64, tables[1].liveEntries.size)
  }

  @Test
  fun ignoresTablesNoComponentSelects() {
    // A grayscale frame that still carries a chroma table (or any file with a
    // leftover definition): nothing selects table 1, so no slider may edit it.
    val data = payloadOfTables(0 to 5, 1 to 7)
    val structure = structureOf(
      spansOf(2),
      components = listOf(ComponentSampling(1, 1, 1, 0)),
    )

    val tables = QuantTables.parse(data, structure)

    assertEquals("only the referenced table", listOf(0), tables.map { it.id })
    assertFalse(tables[0].isChroma)
  }

  @Test
  fun keepsEveryTableAComponentSelects() {
    val data = payloadOfTables(0 to 5, 1 to 7)
    val structure = structureOf(
      spansOf(2),
      components = listOf(
        ComponentSampling(1, 2, 2, 0),
        ComponentSampling(2, 1, 1, 1),
      ),
    )

    val tables = QuantTables.parse(data, structure)

    assertEquals(listOf(0, 1), tables.map { it.id })
    assertEquals(7, tables[1].entries[0].value)
  }

  @Test
  fun aDuplicateDefinitionBeforeTheFirstScanKeepsOnlyTheLast() {
    // Table 0 defined twice before anything decodes it. The decoder ends up with
    // the second definition, so that is the one worth showing (and editing).
    val data = payloadOfTables(0 to 11, 0 to 22)
    val structure = structureOf(
      listOf(ByteSpan(0, 130)),
      components = listOf(ComponentSampling(1, 1, 1, 0)),
      scans = listOf(scan(index = 0, markerOffset = 200, componentIds = listOf(1))),
    )

    val tables = QuantTables.parse(data, structure)

    assertEquals(1, tables.size)
    assertEquals("the last definition is the live one", 22, tables[0].entries[0].value)
    assertEquals("first quantizer of the surviving definition", 66, tables[0].entries[0].offset)
  }

  @Test
  fun aRedefinitionBetweenScansKeepsBothDefinitions() {
    // A scan between the two definitions decoded with the first, a later one with
    // the second, so both definitions really do reach this picture.
    val data = payloadOfTables(0 to 11, 0 to 22)
    val structure = structureOf(
      listOf(ByteSpan(0, 130)),
      components = listOf(ComponentSampling(1, 1, 1, 0)),
      scans = listOf(
        scan(index = 0, markerOffset = 65, componentIds = listOf(1)),
        scan(index = 1, markerOffset = 200, componentIds = listOf(1)),
      ),
    )

    val tables = QuantTables.parse(data, structure)

    assertEquals("both definitions reached a scan", 2, tables.size)
    assertEquals(listOf(11, 22), tables.map { it.entries[0].value })
  }

  @Test
  fun readsSixteenBitTables() {
    // Pq = 1: two bytes per quantizer, big-endian. Values above 255 are only
    // representable here, so the fixture uses 300..363.
    val data = ByteArray(1 + 64 * 2)
    data[0] = 0x11 // precision 1, table id 1
    for (i in 0 until 64) {
      val value = 300 + i
      data[1 + i * 2] = ((value shr 8) and 0xFF).toByte()
      data[2 + i * 2] = (value and 0xFF).toByte()
    }
    val tables = QuantTables.parse(data, structureOf(listOf(ByteSpan(0, data.size))))

    assertEquals(1, tables.size)
    assertEquals(1, tables[0].precision)
    assertEquals(64, tables[0].entries.size)
    assertEquals("16 bits per quantizer", 16, tables[0].entries[0].bitCount)
    assertEquals("two bytes per quantizer", 2, tables[0].entries[1].byteCount)
    assertEquals(300, tables[0].entries[0].value)
    assertEquals(301, tables[0].entries[1].value)
    assertEquals(363, tables[0].entries[63].value)

    // Writing one back has to keep the two-byte, big-endian layout. 512 changes
    // both bytes of the first quantizer (300 = 0x012C, 512 = 0x0200).
    val edited = data.copyOf()
    QuantTables.applyValues(edited, tables, mapOf(tables[0].entries[0].offset to 512))
    assertEquals(
      512,
      QuantTables.parse(edited, structureOf(listOf(ByteSpan(0, edited.size))))
        .first().entries[0].value,
    )
    assertEquals("only that quantizer's two bytes moved", 2, data.indices.count { data[it] != edited[it] })
    assertEquals("neighbours untouched", data[3], edited[3])
  }

  @Test
  fun sortsQuantizersIntoTheFlipFamilies() {
    val data = aJpeg()
    val tables = QuantTables.parse(data, JpegParser.parse(data))
    val luma = tables[0]
    val chroma = tables[1]

    assertEquals("one DC quantizer", 1, luma.entriesFor(QuantAxis.DC).size)
    assertEquals(0, luma.entriesFor(QuantAxis.DC).first().coefficient)
    assertEquals("seven vertical bands", 7, luma.entriesFor(QuantAxis.VERTICAL).size)
    assertEquals("seven horizontal bands", 7, luma.entriesFor(QuantAxis.HORIZONTAL).size)
    assertEquals("49 two-directional cells", 49, luma.entriesFor(QuantAxis.CHECKERED).size)

    // Zigzag order: coeff 1 is (1,0), coeff 2 is (0,1), coeff 4 is (1,1).
    val vertical = luma.entriesFor(QuantAxis.VERTICAL).sortedBy { it.coefficient }
    assertEquals(listOf(1, 5, 6, 14, 15), vertical.take(5).map { it.coefficient })
    assertEquals(1 to 0, vertical.first().u to vertical.first().v)

    val horizontal = luma.entriesFor(QuantAxis.HORIZONTAL).sortedBy { it.coefficient }
    assertEquals(0 to 1, horizontal.first().u to horizontal.first().v)

    val checkered = luma.entriesFor(QuantAxis.CHECKERED).sortedBy { it.coefficient }
    assertEquals(4, checkered.first().coefficient)
    assertEquals(1 to 1, checkered.first().u to checkered.first().v)
    assertEquals("checkerboard cell is 4 px at (1,1)", 4f, checkered.first().bandPx, 0.01f)

    // The chroma table holds the same families; the screen keys off isChroma.
    assertEquals(49, chroma.entriesFor(QuantAxis.CHECKERED).size)
  }

  @Test
  fun togglingABitWritesOnlyThatQuantizer() {
    val original = aJpeg()
    val structure = JpegParser.parse(original)
    val tables = QuantTables.parse(original, structure)
    val entry = tables[0].entries[0]
    val before = entry.value
    val flipped = before xor (1 shl 2) // e.g. 5 -> 1

    val data = original.copyOf()
    QuantTables.applyValues(data, tables, mapOf(entry.offset to flipped))

    val after = QuantTables.parse(data, JpegParser.parse(data))[0].entries[0]
    assertEquals(flipped, after.value)
    assertNotEquals(before, after.value)

    val differing = original.indices.filter { original[it] != data[it] }
    assertEquals("exactly one byte changes", listOf(entry.offset), differing)
    assertEquals("file size is untouched", original.size, data.size)
  }

  @Test
  fun appliesEveryEditedQuantizerAtOnce() {
    val original = aJpeg()
    val structure = JpegParser.parse(original)
    val tables = QuantTables.parse(original, structure)
    val lumaDc = tables[0].entries[0]
    val chromaAc = tables[1].entries[10]

    val data = original.copyOf()
    QuantTables.applyValues(
      data,
      tables,
      mapOf(
        lumaDc.offset to (lumaDc.value xor 0x80),
        chromaAc.offset to (chromaAc.value xor 0x10),
      ),
    )

    val reloaded = QuantTables.parse(data, JpegParser.parse(data))
    assertEquals(lumaDc.value xor 0x80, reloaded[0].entries[0].value)
    assertEquals(chromaAc.value xor 0x10, reloaded[1].entries[10].value)
    assertEquals(2, original.indices.count { original[it] != data[it] })
  }

  @Test
  fun emptyEditSetLeavesTheFileAlone() {
    val original = aJpeg()
    val data = original.copyOf()
    QuantTables.applyValues(data, QuantTables.parse(original, JpegParser.parse(original)), emptyMap())
    assertTrue("nothing changes", original.contentEquals(data))
  }
}
