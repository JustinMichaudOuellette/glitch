package ca.justinmo.glitch.jpeg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Covers the per-image code census the DHT screen hides dead codes with.
 *
 * The walk is checked by arithmetic that only holds if it is exact, rather than
 * against expected pixel values:
 *
 *  * it must consume **every byte** of the scan - a lost bit anywhere would leave
 *    the reader short and the walk padding zeros off the end of the buffer;
 *  * the end-of-block symbol must occur **exactly once per 8x8 block**, because
 *    that is the only way a block can end, and its code appears nowhere else.
 *
 * Measured on `b.jpg` (3472x4624 baseline, 376278 blocks over 1,637,257 scan
 * bytes): the walk reads all 1,637,257 bytes, and the end-of-block code is
 * decoded 250,852 times for the four luma blocks per MCU and 125,426 times for
 * the two chroma blocks.
 */
class HuffmanCensusTest {

  private fun fixture(rel: String): ByteArray {
    val file = File(rel)
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  private class Counted(
    val structure: JpegStructure,
    val tables: List<HuffmanTable>,
    val census: HuffmanCensus,
  ) {
    val frame: JpegFrame get() = structure.frame!!
    val scan: ScanInfo get() = structure.scans.first()
    fun table(isAc: Boolean, chroma: Boolean): HuffmanTable =
      tables.first { it.isAc == isAc && it.isChroma == chroma }
    /** Blocks per MCU coded with one table, summed over the components using it. */
    fun blocksUsing(table: HuffmanTable): Int =
      scan.components.filter {
        (if (table.isAc) it.acTable else it.dcTable) == table.tableId
      }.sumOf { component ->
        val sampling = frame.components.first { it.id == component.id }
        sampling.h * sampling.v
      } * frame.mcusPerRow * frame.mcuRows
  }

  private fun counted(rel: String = "../b.jpg"): Counted {
    val data = fixture(rel)
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)
    assertNotNull("census of $rel", census)
    return Counted(structure, tables, census!!)
  }

  @Test
  fun theWalkConsumesTheWholeScan() {
    val counted = counted()
    assertEquals(
      "the walk read every scan byte",
      counted.scan.byteCount,
      counted.census.bytesRead,
    )
    val expectedBlocks = counted.frame.mcusPerRow * counted.frame.mcuRows *
      counted.scan.components.sumOf { component ->
        val sampling = counted.frame.components.first { it.id == component.id }
        sampling.h * sampling.v
      }
    assertEquals("blocks walked", expectedBlocks, counted.census.blocks)
    assertEquals("376278 blocks in b.jpg", 376278, counted.census.blocks)
  }

  @Test
  fun endOfBlockOccursExactlyOncePerBlock() {
    val counted = counted()
    for (table in counted.tables.filter { it.isAc }) {
      val eob = table.entries.firstOrNull { it.isEndOfBlock && it.run == 0 }
      assertNotNull("an end-of-block symbol in ${table}", eob)
      assertEquals(
        "one end of block per block (${if (table.isChroma) "chroma" else "luma"})",
        counted.blocksUsing(table),
        counted.census.uses(eob!!),
      )
    }
  }

  @Test
  fun theBusiestCodesAreTheShortestOnes() {
    val counted = counted()
    val acLuma = counted.table(isAc = true, chroma = false)
    val top = acLuma.entries.sortedByDescending { counted.census.uses(it) }.take(6)
    assertTrue(
      "the six busiest codes are short ones ($top)",
      top.all { it.codeLength <= 5 },
    )

    // Usage falls off with code length, which is the ordering the screen relies
    // on and the strength dial DHT.md measured.
    val short = acLuma.entries.filter { it.codeLength <= 4 }.sumOf { counted.census.uses(it) }
    val long = acLuma.entries.filter { it.codeLength >= 12 }.sumOf { counted.census.uses(it) }
    assertTrue("short codes dominate", short > long * 10)
  }

  @Test
  fun mostOfATableIsUsuallyUnused() {
    val counted = counted()
    val acLuma = counted.table(isAc = true, chroma = false)
    val acChroma = counted.table(isAc = true, chroma = true)

    // On this photo the chroma table is almost entirely dead weight, and its 119
    // sixteen-bit codes are never emitted at all.
    val chromaTail = acChroma.entries.filter { it.codeLength == acChroma.maxCodeLength }
    assertEquals("119 sixteen-bit chroma codes", 119, chromaTail.size)
    assertEquals("none of them occur", 0, chromaTail.count { counted.census.uses(it) > 0 })
    assertTrue(
      "a quarter of the chroma table at most (${counted.census.usedSymbolCount(acChroma)})",
      counted.census.usedSymbolCount(acChroma) < acChroma.symbolCount / 4,
    )
    assertTrue(
      "the luma table drops its long tail too",
      counted.census.usedSymbolCount(acLuma) < acLuma.symbolCount / 2,
    )
  }

  @Test
  fun countsAreKeyedBySymbolIndexNotByValue() {
    // The screen counts the image once, as it arrived, and then lets the user
    // rewrite what the codes mean. That only works because the counts are held
    // per symbol *position*: the encoder emitted the same codes either way, so
    // "how often does #5 occur" survives any edit to #5's value.
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val census = HuffmanCensusReader.read(data, structure, tables)!!

    val edited = data.copyOf()
    val target = tables.first { it.isAc && !it.isChroma }.entries[5]
    HuffmanTables.applyValues(edited, mapOf(target.offset to 0xE0))
    val afterTables = HuffmanTables.parse(edited, structure)

    assertEquals("the edited value is in the file", 0xE0, afterTables[1].entries[5].value)
    for (entry in afterTables[1].entries) {
      assertEquals(
        "uses of #${entry.index} are unchanged by the edit",
        census.uses(tables[1], entry.index),
        census.uses(entry),
      )
    }
  }

  @Test
  fun anEditThatChangesABlocksLengthLeavesTheWalkBehind() {
    // Changing symbol #5 (skip 1, one-bit value) to a symbol that ends the block
    // early makes every block that used that code consume fewer bits than the
    // encoder spent. The decoder then reads every later block from the wrong bit
    // position, which is exactly the desynchronisation that makes these edits
    // glitch the picture - and it is why the census must be taken from the bytes
    // as they arrived rather than recomputed after an edit.
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)

    val edited = data.copyOf()
    HuffmanTables.applyValues(edited, mapOf(tables[1].entries[5].offset to 0xE0))
    val afterTables = HuffmanTables.parse(edited, structure)

    assertNull(
      "the walk no longer reaches the end of the scan",
      HuffmanCensusReader.read(edited, structure, afterTables),
    )
  }

  @Test
  fun censusIsRefusedForAProgressiveFile() {
    val data = fixture("../a.jpg")
    val structure = JpegParser.parse(data)
    assertTrue("a.jpg really is multi-scan", structure.scans.size > 1)
    assertEquals(CensusBlock.NOT_SINGLE_SCAN, HuffmanCensusReader.blockFor(structure))
    assertNull(
      "no census without a single full scan",
      HuffmanCensusReader.read(data, structure, HuffmanTables.parse(data, structure)),
    )
  }

  @Test
  fun censusIsRefusedWhenTheFrameIsUnknown() {
    val structure = JpegStructure(
      dqt = emptyList(),
      dht = emptyList(),
      scan = emptyList(),
      rst = emptyList(),
      scans = emptyList(),
      geometry = emptyList(),
      frame = null,
      fatal = emptyList(),
      metadata = emptyList(),
    )
    assertEquals(CensusBlock.NO_FRAME, HuffmanCensusReader.blockFor(structure))
  }
}
