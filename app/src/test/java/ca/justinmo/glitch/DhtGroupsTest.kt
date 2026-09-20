package ca.justinmo.glitch

import ca.justinmo.glitch.jpeg.HuffmanCensus
import ca.justinmo.glitch.jpeg.HuffmanCensusReader
import ca.justinmo.glitch.jpeg.HuffmanSymbol
import ca.justinmo.glitch.jpeg.HuffmanTable
import ca.justinmo.glitch.jpeg.HuffmanTables
import ca.justinmo.glitch.jpeg.JpegParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Covers the two pure pieces of the DHT screen's presentation: which table a
 * tile stands for, and how the symbol list is grouped.
 *
 * The list ordering is the screen's whole point - the loudest edit has to be the
 * first row - so it is checked against a real census of `b.jpg` as well as
 * against a hand-built table with no census at all.
 */
class DhtGroupsTest {

  private fun fixture(rel: String): ByteArray {
    val file = File(rel)
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  /** A stand-in table: 8 symbols with code lengths 2,2,3,4,4,4,9,16. */
  private fun smallTable(values: IntArray = intArrayOf(1, 2, 3, 0, 17, 33, 65, 200)): HuffmanTable {
    val lengths = intArrayOf(2, 2, 3, 4, 4, 4, 9, 16)
    val data = ByteArray(17 + values.size) { if (it >= 17) values[it - 17].toByte() else 0 }
    val entries = values.indices.map { i ->
      HuffmanSymbol(1, 0, i, 17 + i, lengths[i], data)
    }
    return HuffmanTable(1, 0, 0, intArrayOf(0, 2, 1, 3, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1), entries, false)
  }

  @Test
  fun withoutACensusSectionsFollowTheCodeLengths() {
    val table = smallTable()
    val groups = dhtGroups(table, census = null, onlyUsed = true)
    assertEquals(
      "one section per code length, shortest first",
      listOf(2, 3, 4, 9, 16),
      groups.map { (it as DhtGroup.Length).length },
    )
    assertEquals("every symbol is listed", 8, groups.sumOf { it.entries.size })
    assertEquals("shortest first", 2, groups.first().entries.first().codeLength)
  }

  @Test
  fun theRealTableIsGroupedByUsageWhenThereIsACensus() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)!!
    val acLuma = tables.first { it.isAc && !it.isChroma }

    val used = dhtGroups(acLuma, census, onlyUsed = true)
    assertEquals("one section while the filter is on", 1, used.size)
    val listed = used.single()
    assertTrue("it is the used section", listed is DhtGroup.Used)
    assertTrue("it is not the whole table", listed.entries.size < acLuma.symbolCount)
    assertTrue("it is a meaningful slice", listed.entries.size > 10)

    // Loudest first, which is the ordering the screen promises.
    val uses = listed.entries.map { census.uses(it) }
    assertEquals("sorted by usage, descending", uses.sortedDescending(), uses)

    val all = dhtGroups(acLuma, census, onlyUsed = false)
    assertEquals("two sections with the filter off", 2, all.size)
    assertEquals("and every symbol is listed", acLuma.symbolCount, all.sumOf { it.entries.size })
    assertTrue("the second section is the unused tail", all[1] is DhtGroup.Unused)
    assertTrue("the unused tail is genuinely unused", all[1].entries.all { census.uses(it) == 0 })
  }

  @Test
  fun aTableThePhotoNeverUsesStillListsItsSymbols() {
    val table = smallTable(values = IntArray(8) { 0x55 })
    // No census at all is the same path a file with an unreadable scan takes.
    val groups = dhtGroups(table, census = null, onlyUsed = true)
    assertEquals("symbols are still listed", 8, groups.sumOf { it.entries.size })
  }

  @Test
  fun tilesResolveToTheirOwnTable() {
    val data = fixture("../b.jpg")
    val tables = HuffmanTables.parse(data, JpegParser.parse(data))

    // Both luma tables carry id 0; their class (AC vs DC) is what separates them.
    val acLuma = tableFor(tables, DhtFamily(1, false))!!
    val dcLuma = tableFor(tables, DhtFamily(0, false))!!
    assertEquals("AC luma is an AC table", 1, acLuma.tableClass)
    assertEquals("DC luma is a DC table", 0, dcLuma.tableClass)
    assertEquals("both are id 0", 0, acLuma.tableId)
    assertEquals("both are luma", false, acLuma.isChroma)
    assertTrue("AC chroma is chroma", tableFor(tables, DhtFamily(1, true))!!.isChroma)
    assertTrue("DC chroma is chroma", tableFor(tables, DhtFamily(0, true))!!.isChroma)

    // The four tiles are four different tables.
    assertEquals(
      "four distinct tables",
      4,
      DHT_FAMILIES.map { tableFor(tables, it) }.toSet().size,
    )
    assertEquals("a 162-symbol table", 162, acLuma.symbolCount)
    assertEquals("a 12-symbol table", 12, dcLuma.symbolCount)
    // A greyscale file has no chroma tables at all.
    val lumaOnly = tables.filter { !it.isChroma }
    assertNull(tableFor(lumaOnly, DhtFamily(1, true)))
    assertNull(tableFor(lumaOnly, DhtFamily(0, true)))
  }
}
