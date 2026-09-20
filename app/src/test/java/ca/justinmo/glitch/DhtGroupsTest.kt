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
  fun tiersFollowTheShareOfBlocksAnEditReaches() {
    assertEquals("nothing at all", DhtTier.TouchUp, tierOf(0f))
    assertEquals("a speck", DhtTier.TouchUp, tierOf(0.0001f))
    assertEquals("just under one percent", DhtTier.TouchUp, tierOf(0.0099f))
    assertEquals("one percent", DhtTier.Texture, tierOf(TOUCH_UP_MAX_IMPACT))
    assertEquals("a fifth", DhtTier.Texture, tierOf(TEXTURE_MAX_IMPACT))
    assertEquals("past a fifth", DhtTier.Rewrite, tierOf(0.2001f))
    assertEquals("the end-of-block code", DhtTier.Rewrite, tierOf(1f))
    assertEquals("never seen", DhtTier.TouchUp, tierOf(0f))
  }

  @Test
  fun theRealTableIsGroupedByHowFarEachEditReaches() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)!!
    val acLuma = tables.first { it.isAc && !it.isChroma }

    val groups = dhtGroups(acLuma, census, onlyUsed = true)
    assertTrue("the census produces tiers", groups.all { it is DhtGroup.Tier })

    // Gentlest first: the tier list has to open on the touch-ups, because that
    // is the whole point of the redesign (the old screen opened on the codes
    // that rewrite the picture).
    val tiers = groups.map { (it as DhtGroup.Tier).tier }
    assertEquals("tiers in order", tiers, tiers.sortedBy { it.ordinal })

    val used = acLuma.entries.filter { census.uses(it) > 0 }
    assertEquals("every used symbol is listed", used.size, groups.sumOf { it.entries.size })
    assertEquals("nothing unused leaks in", used.size, groups.flatMap { it.entries }.distinct().size)

    // Inside a tier, the code that reaches furthest comes first.
    for (group in groups.map { it as DhtGroup.Tier }) {
      val impacts = group.entries.map { census.impact(it) }
      assertEquals("reach descends down a tier", impacts.sortedDescending(), impacts)
      val expected = when (group.tier) {
        DhtTier.TouchUp -> impacts.all { it < TOUCH_UP_MAX_IMPACT }
        DhtTier.Texture -> impacts.all { it in TOUCH_UP_MAX_IMPACT..TEXTURE_MAX_IMPACT }
        DhtTier.Rewrite -> impacts.all { it > TEXTURE_MAX_IMPACT }
      }
      assertTrue("${group.tier} holds the right reaches", expected)
    }

    val all = dhtGroups(acLuma, census, onlyUsed = false)
    assertEquals("one more section with the filter off", groups.size + 1, all.size)
    assertTrue("the last one is the unused tail", all.last() is DhtGroup.Unused)
    assertTrue("the unused tail is genuinely unused", all.last().entries.all { census.uses(it) == 0 })
  }

  @Test
  fun theDestructiveCodesAreKeptOutOfTheOpeningSection() {
    // The measured failure of the first version: its list opened on AC luma's
    // first 32 symbols, the ones every block uses, which the sample batch
    // measured as 96% destructive. Those must not be in the first section now.
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)!!
    val acLuma = tables.first { it.isAc && !it.isChroma }

    val opening = dhtGroups(acLuma, census, onlyUsed = true).first() as DhtGroup.Tier
    assertEquals("the list opens on touch-ups", DhtTier.TouchUp, opening.tier)
    assertTrue(
      "and nothing in it reaches a hundredth of the picture",
      opening.entries.all { census.impact(it) < TOUCH_UP_MAX_IMPACT },
    )
    val rewrite = dhtGroups(acLuma, census, onlyUsed = true)
      .filterIsInstance<DhtGroup.Tier>()
      .firstOrNull { it.tier == DhtTier.Rewrite }
    // The end-of-block code is in every luma block, so it has to be behind the
    // rewrite heading rather than mixed in with the harmless ones.
    assertTrue("the every-block codes are in their own section", rewrite != null)
    assertTrue(
      "the end of block symbol is one of them",
      rewrite!!.entries.any { it.isEndOfBlock && it.run == 0 },
    )
  }

  @Test
  fun theTierCountsAddUp() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)!!
    val acLuma = tables.first { it.isAc && !it.isChroma }

    val total = DhtTier.entries.sumOf { dhtTierCount(acLuma, census, it) }
    assertEquals(
      "every used symbol is in exactly one tier",
      census.usedSymbolCount(acLuma),
      total,
    )
    assertEquals("the end of block code reaches every luma block", 1f,
      census.impact(acLuma, 3), 0.0001f)
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
