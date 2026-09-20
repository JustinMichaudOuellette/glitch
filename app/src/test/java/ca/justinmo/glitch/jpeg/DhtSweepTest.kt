package ca.justinmo.glitch.jpeg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Covers the classification the DHT screen is built on.
 *
 * The screen offers an edit only when the sweep filed its sample as something
 * other than a wreck, so these tests pin the two things that could quietly go
 * wrong: the bit indices lining up with the payload the sweep walked, and the
 * tally still matching the folders the sweep was sorted into.
 */
class DhtSweepTest {

  private fun fixture(rel: String): ByteArray {
    val file = File(rel)
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  @Test
  fun theTallyMatchesTheFoldersTheSweepWasSortedInto() {
    val tally = DhtSweep.tally()
    assertEquals("payload bits", 3328, DhtSweep.BIT_COUNT)
    assertEquals("every bit is classified", 3328, tally.values.sum())
    assertEquals("nop", 1856, tally[SweepOutcome.NOTHING])
    assertEquals("bad", 241, tally[SweepOutcome.WRECK_ALL])
    assertEquals("75pct bad", 140, tally[SweepOutcome.WRECK_THREE])
    assertEquals("50pct bad", 118, tally[SweepOutcome.WRECK_TWO])
    assertEquals("25pct bad", 132, tally[SweepOutcome.WRECK_ONE])
    assertEquals("75pct", 16, tally[SweepOutcome.THREE])
    assertEquals("50pct", 58, tally[SweepOutcome.TWO])
    assertEquals("25pct", 135, tally[SweepOutcome.ONE])
    assertEquals("100pct", 2, tally[SweepOutcome.FOUR])
    assertEquals("no sample", 630, tally[SweepOutcome.FATAL])

    // The headline the screen quotes: 211 edits were worth keeping.
    val usable = tally.filterKeys { it.isUsable }.values.sum()
    assertEquals("edits the screen offers by default", 211, usable)
    val wrecked = tally.filterKeys { it.isWreck }.values.sum()
    assertEquals("edits filed as wrecks", 631, wrecked)
  }

  @Test
  fun theBaselineIsThePayloadTheSweepWalked() {
    assertEquals("416 payload bytes", 416, DhtSweep.BASELINE.size)
    // The four information bytes of the swept files, and the first table's
    // counts, which DHT.md records.
    assertEquals(0x00, DhtSweep.BASELINE[0].toInt() and 0xFF)
    assertEquals(0x10, DhtSweep.BASELINE[29].toInt() and 0xFF)
    assertEquals(0x01, DhtSweep.BASELINE[208].toInt() and 0xFF)
    assertEquals(0x11, DhtSweep.BASELINE[237].toInt() and 0xFF)
    assertArrayEquals(
      intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
      IntArray(16) { DhtSweep.BASELINE[1 + it].toInt() and 0xFF },
    )
    // And the standard symbol list of the luma detail table.
    assertEquals(1, DhtSweep.BASELINE[46].toInt() and 0xFF)
    assertEquals(0, DhtSweep.BASELINE[49].toInt() and 0xFF)
    assertEquals(240, DhtSweep.BASELINE[46 + 31].toInt() and 0xFF)
  }

  private fun assertArrayEquals(expected: IntArray, actual: IntArray) =
    assertEquals(expected.toList(), actual.toList())

  @Test
  fun aRealFileMatchesTheSweepLayout() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val payload = HuffmanTables.payloadOf(data, structure)
    assertEquals("the DHT payload of b.jpg", 416, payload.size)
    assertTrue(
      "b.jpg carries the tables the sweep measured",
      DhtSweep.appliesTo(payload),
    )
  }

  @Test
  fun aFileWithDifferentTablesIsRefused() {
    val data = fixture("../b.jpg").copyOf()
    val structure = JpegParser.parse(data)
    // One byte of one symbol changed: the sweep's bit indices no longer describe
    // this file, so the classification has to be refused rather than guessed at.
    val tables = HuffmanTables.parse(data, structure)
    val target = tables.first { it.isAc && !it.isChroma }.entries[5]
    HuffmanTables.applyValues(data, mapOf(target.offset to 0x77))
    val payload = HuffmanTables.payloadOf(data, structure)
    assertFalse(DhtSweep.appliesTo(payload))

    // A progressive file's payload is a different set of tables entirely.
    val a = fixture("../a.jpg")
    val aStructure = JpegParser.parse(a)
    assertFalse(DhtSweep.appliesTo(HuffmanTables.payloadOf(a, aStructure)))
  }

  @Test
  fun anEditIsTheBitFlipTheSweepMeasured() {
    // Luma detail symbol #10 is value 65 at payload byte 56. Its bit 5 flip is
    // 65 -> 97, which the sweep filed as 50pct bad - the edit someone found and
    // liked, and the reason a wreck is one chip away rather than forbidden.
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val acLuma = tables.first { it.isAc && !it.isChroma }
    val symbol = acLuma.entries[10]
    assertEquals("payload byte", 56, acLuma.payloadIndex + 17 + symbol.index)
    assertEquals("its value in the file", 65, symbol.value)

    val edits = DhtSweep.editsFor(symbol, 56)
    assertEquals("one edit per bit", 8, edits.size)
    val bit5 = edits.single { it.bit == 5 }
    assertEquals("65 xor 32", 97, bit5.to)
    assertEquals("which is skip 6, 1-bit value", 6, bit5.to shr 4)
    assertEquals(1, bit5.to and 0x0F)
    assertEquals("and is a single bit away", 65 xor 97, 1 shl 5)
    assertEquals(SweepOutcome.WRECK_TWO, bit5.outcome)

    // Bit 0 of the same symbol was filed as a wreck of all four.
    assertEquals(SweepOutcome.WRECK_ALL, edits.single { it.bit == 0 }.outcome)
  }

  @Test
  fun everyEditChangesExactlyOneBit() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    for (edit in DhtSweep.editsFor(tables)) {
      val difference = edit.from xor edit.to
      assertEquals("one bit differs", 1 shl edit.bit, difference)
      assertTrue("the result is a byte", edit.to in 0..255)
      assertTrue("and the source was too", edit.from in 0..255)
    }
  }

  @Test
  fun theSweptFileOffersTheEditsItsFolderHolds() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    val edits = DhtSweep.editsFor(tables)
    // One edit per bit of every *symbol* byte: 2 x 12 + 2 x 162 symbols, eight
    // bits each. The information byte and the sixteen code-length counts of each
    // table are not symbol bytes, and every bit of them is either a no-op or
    // fatal, so the screen never offers them.
    assertEquals("one edit per symbol bit", 2784, edits.size)

    val usable = edits.filter { it.outcome.isUsable }
    assertEquals("211 of them are worth offering", 211, usable.size)
    // The screen's default list is those 211 minus the ones whose code never
    // occurs, and the luma detail table has to be a large part of it.
    assertTrue(
      "most of the good edits are detail-table ones",
      usable.count { !it.isDc } > usable.count { it.isDc } * 4,
    )
    // A code that ends the block on every luma block, and a rare one, both
    // appear - the list is not only one or the other.
    assertTrue("a code that fires constantly is in there", usable.any { it.symbolIndex == 3 })
    assertTrue("and so is a rare one", usable.any { it.symbolIndex > 100 })
  }

  @Test
  fun theTableAnEditBelongsToIsResolved() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val tables = HuffmanTables.parse(data, structure)
    for (edit in DhtSweep.editsFor(tables)) {
      val table = tables.first {
        it.tableClass == edit.tableClass && it.tableId == edit.tableId && it.isChroma == edit.chroma
      }
      assertNotNull("table ${edit.tableClass}/${edit.tableId}", table)
      val symbol = table.entries[edit.symbolIndex]
      assertEquals("offset lines up", symbol.offset, edit.offset)
      assertEquals("value lines up", symbol.value, edit.from)
    }
  }

  @Test
  fun theOutcomeCodesRoundTrip() {
    for (code in DhtSweep.ENCODED) {
      assertNotNull("every character is an outcome ($code)", SweepOutcome.of(code))
    }
    assertNull("and nothing else is", SweepOutcome.of('?'))
    assertTrue("wrecks are wrecks", SweepOutcome.WRECK_ALL.isWreck)
    assertFalse("and the kept ones are not", SweepOutcome.ONE.isWreck)
    assertFalse("nothing is not usable", SweepOutcome.NOTHING.isUsable)
    assertFalse("and neither is a file that will not open", SweepOutcome.FATAL.isUsable)
  }
}
