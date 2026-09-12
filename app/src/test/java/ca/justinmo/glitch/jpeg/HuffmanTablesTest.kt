package ca.justinmo.glitch.jpeg

import ca.justinmo.glitch.dht.HuffmanTables
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Covers the Huffman-table model the DHT screen is built on: the byte layout it
 * reads, the code lengths it derives from the counts, and the write-back it uses
 * when a symbol is edited.
 *
 * The facts asserted here are the ones `DHT.md` documents - four tables, 12
 * symbols in each DC table and 162 in each AC table, the spec byte and the 16
 * counts sitting in [JpegStructure.fatal], and symbol #0 owning the shortest
 * code. A missing fixture skips that case, matching [JpegParserTest].
 */
class HuffmanTablesTest {

  private fun fixture(rel: String): ByteArray {
    val file = File(rel)
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  /** The standard AC luminance code lengths, in symbol order. */
  private val acLumaCounts =
    intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125)

  @Test
  fun readsTheFourTablesOfABaselineJpeg() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)

    assertEquals("four tables", 4, tables.size)
    // b.jpg is baseline 4:2:0, so it carries DC and AC for luma and for chroma.
    assertEquals(
      "classes and ids",
      listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
      tables.map { it.tableClass to it.tableId },
    )
    assertEquals("DC table symbols", 12, tables[0].symbolCount)
    assertEquals("AC table symbols", 162, tables[1].symbolCount)
    assertEquals("DC chroma symbols", 12, tables[2].symbolCount)
    assertEquals("AC chroma symbols", 162, tables[3].symbolCount)

    assertFalse("first table is luma", tables[0].isChroma)
    assertTrue("table 1 is chroma", tables[2].isChroma)
    assertFalse("AC luma is luma", tables[1].isChroma)

    val acLuma = tables[1]
    assertArrayEquals("standard AC luminance counts", acLumaCounts, acLuma.counts)
    assertEquals("longest code", 16, acLuma.maxCodeLength)
  }

  @Test
  fun codeLengthsFollowTheCounts() {
    val data = fixture("../b.jpg")
    val tables = HuffmanTables.parse(data, JpegParser.parse(data))
    val acLuma = tables[1]

    // The counts are cumulative code lengths: two 2-bit codes, then one 3-bit,
    // then three 4-bit ones, and the list is stored in exactly that order.
    assertEquals("symbol 0 is a 2-bit code", 2, acLuma.entries[0].codeLength)
    assertEquals("symbol 1 is a 2-bit code", 2, acLuma.entries[1].codeLength)
    assertEquals("symbol 2 is a 3-bit code", 3, acLuma.entries[2].codeLength)
    assertEquals("symbol 3 is a 4-bit code", 4, acLuma.entries[3].codeLength)
    assertEquals("last symbol is a 16-bit code", 16, acLuma.entries[161].codeLength)
    assertEquals(
      "code lengths are grouped short-first",
      listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16),
      acLuma.entriesByLength().map { it.first },
    )
  }

  @Test
  fun symbolBytesSitInsideTheFatalFreePartOfTheSpan() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)

    for (table in tables) {
      // The 17 header bytes are the ones every bit of which breaks the file.
      assertTrue(
        "spec byte is fatal (${table})",
        structure.isFatal(table.specOffset),
      )
      val symbolStart = table.entries.first().offset
      assertEquals("symbols start after the counts", table.specOffset + 17, symbolStart)
      for (entry in table.entries) {
        assertFalse("symbol byte is flippable (${entry.offset})", structure.isFatal(entry.offset))
        assertTrue(
          "symbol byte is inside a DHT span (${entry.offset})",
          ByteSpans.contains(structure.dht, entry.offset),
        )
      }
    }
  }

  @Test
  fun readsTheSymbolsOfTheStandardTable() {
    val data = fixture("../b.jpg")
    val tables = HuffmanTables.parse(data, JpegParser.parse(data))
    val acLuma = tables[1]

    // The first few symbols of the standard AC luminance table. Symbol #3 is the
    // end-of-block symbol and #5 is the classic "skip 1, 1-bit value" pair.
    assertEquals("symbol 0", 0x01, acLuma.entries[0].value)
    assertEquals("symbol 1", 0x02, acLuma.entries[1].value)
    assertEquals("symbol 2", 0x03, acLuma.entries[2].value)
    assertEquals("symbol 3 is end of block", 0x00, acLuma.entries[3].value)
    assertTrue("symbol 3 ends the block", acLuma.entries[3].isEndOfBlock)
    assertEquals("symbol 5 skips one", 1, acLuma.entries[5].run)
    assertEquals("symbol 5 is a 1-bit value", 1, acLuma.entries[5].size)

    // Every DC symbol of the standard table is a plain category 0-11.
    val dcLuma = tables[0]
    assertEquals("DC categories", (0..11).toList(), dcLuma.entries.map { it.value })
    assertTrue("DC symbols are categories", dcLuma.entries.all { it.isDc })
    assertEquals("a DC symbol caps at 15", 15, dcLuma.entries[0].maxValue)
    assertEquals("an AC symbol caps at 255", 255, acLuma.entries[0].maxValue)
  }

  @Test
  fun applyValuesWritesOnlyTheSymbolsItIsGiven() {
    val source = fixture("../b.jpg")
    val structure = JpegParser.parse(source)
    val tables = HuffmanTables.parse(source, structure)
    val acLuma = tables[1]

    val edited = source.copyOf()
    val target = acLuma.entries[5]
    HuffmanTables.applyValues(edited, mapOf(target.offset to 0x99))

    assertEquals("the file keeps its length", source.size, edited.size)
    assertEquals("the edited byte took the value", 0x99, edited[target.offset].toInt() and 0xFF)
    // Nothing else moved: re-parsing the edited file reports one changed symbol.
    val reparsed = HuffmanTables.parse(edited, structure)
    assertEquals("symbol value", 0x99, reparsed[1].entries[5].value)
    assertEquals("other symbols unchanged", acLuma.entries[6].value, reparsed[1].entries[6].value)
    assertEquals("DC table untouched", tables[0].entries[3].value, reparsed[0].entries[3].value)
  }

  @Test
  fun anEditedSymbolStillDecodes() {
    // A symbol change is an ordinary value change: only the 17 header bytes and
    // the DC categories above 15 can stop a file opening (DHT.md section 3).
    val source = fixture("../b.jpg")
    val structure = JpegParser.parse(source)
    val tables = HuffmanTables.parse(source, structure)
    val edited = source.copyOf()
    val target = tables[1].entries[5]
    HuffmanTables.applyValues(edited, mapOf(target.offset to 0xE0))

    val reparsed = JpegParser.parse(edited)
    assertEquals(
      "the DHT payload is unchanged in length",
      structure.byteCount(GlitchRegion.DHT),
      reparsed.byteCount(GlitchRegion.DHT),
    )
    assertNotNull("the edit stays inside the tables", HuffmanTables.tableOf(
      HuffmanTables.parse(edited, reparsed), target.offset,
    ))
  }
}
