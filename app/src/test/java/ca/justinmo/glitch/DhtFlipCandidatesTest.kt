package ca.justinmo.glitch

import ca.justinmo.glitch.dht.DhtSweep
import ca.justinmo.glitch.dht.HuffmanCensus
import ca.justinmo.glitch.dht.HuffmanSymbol
import ca.justinmo.glitch.dht.HuffmanTable
import ca.justinmo.glitch.dht.SweepEdit
import ca.justinmo.glitch.dht.SweepOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DHT screen's random flip must land on a symbol this photo actually emits and
 * on a bit that can do more than kill the file or leave the picture identical.
 * Those two conditions are pure, so they are checked here rather than by pressing
 * Flip on a phone.
 */
class DhtFlipCandidatesTest {

  private fun smallTable(): HuffmanTable {
    val values = intArrayOf(1, 2, 3, 0, 17, 33, 65, 200)
    val lengths = intArrayOf(2, 2, 3, 4, 4, 4, 9, 16)
    val data = ByteArray(17 + values.size) { if (it >= 17) values[it - 17].toByte() else 0 }
    val entries = values.indices.map { i ->
      HuffmanSymbol(1, 0, i, 17 + i, lengths[i], data)
    }
    return HuffmanTable(
      tableClass = 1,
      tableId = 0,
      specOffset = 0,
      payloadIndex = 29,
      counts = intArrayOf(0, 2, 1, 3, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1),
      entries = entries,
      isChroma = false,
    )
  }

  private fun edit(symbol: HuffmanSymbol, bit: Int, outcome: SweepOutcome): SweepEdit =
    SweepEdit(
      tableClass = symbol.tableClass,
      tableId = symbol.tableId,
      chroma = false,
      symbolIndex = symbol.index,
      codeLength = symbol.codeLength,
      offset = symbol.offset,
      from = symbol.value,
      to = symbol.value xor (1 shl bit),
      bit = bit,
      outcome = outcome,
    )

  /** One edit per bit, with [outcomes] giving each symbol's eight results. */
  private fun flips(
    table: HuffmanTable,
    outcomes: Map<Int, List<SweepOutcome>>,
  ): Map<Int, List<SweepEdit>> = table.entries.associate { symbol ->
    val bits = outcomes[symbol.index] ?: List(8) { SweepOutcome.FATAL }
    symbol.offset to bits.mapIndexed { bit, outcome -> edit(symbol, bit, outcome) }
  }

  /** A census that says exactly [used] codes occurred once each. */
  private fun census(table: HuffmanTable, used: Set<Int>): HuffmanCensus {
    val key = (table.tableClass shl 8) or table.tableId
    val counts = IntArray(table.symbolCount) { if (it in used) 1 else 0 }
    return HuffmanCensus(
      uses = mapOf(key to counts),
      symbolsRead = used.size,
      blocks = 1,
      bytesRead = 0,
    )
  }

  @Test
  fun aSymbolIsOfferedOnlyWhenThePhotoUsesItAndItHasAnEffectiveBit() {
    val table = smallTable()
    val fatal = List(8) { SweepOutcome.FATAL }
    val outcomes = mapOf(
      // Used, and one bit is a real change.
      0 to listOf(
        SweepOutcome.ONE, SweepOutcome.FATAL, SweepOutcome.NOTHING, SweepOutcome.FATAL,
        SweepOutcome.FATAL, SweepOutcome.FATAL, SweepOutcome.FATAL, SweepOutcome.FATAL,
      ),
      // Would work, but this photo never emits the code.
      1 to List(8) { SweepOutcome.ONE },
      // Used, but every bit kills the file.
      2 to fatal,
      // Used, but every bit reproduces the picture.
      3 to List(8) { SweepOutcome.NOTHING },
      // A wreck is a change: it belongs in the draw, rarely.
      4 to List(8) { SweepOutcome.WRECK_ALL },
    )
    val candidates = DhtSweep.randomFlipCandidates(
      tables = listOf(table),
      flips = flips(table, outcomes),
      census = census(table, used = setOf(0, 2, 3, 4)),
    )

    assertEquals("symbols 0 and 4", listOf(0, 4), candidates.map { it.index })
  }

  @Test
  fun withoutACensusUsageCannotBeJudged() {
    val table = smallTable()
    val outcomes = mapOf(
      0 to List(8) { SweepOutcome.ONE },
      1 to List(8) { SweepOutcome.ONE },
      2 to List(8) { SweepOutcome.FATAL },
      3 to List(8) { SweepOutcome.NOTHING },
    )
    val candidates = DhtSweep.randomFlipCandidates(
      tables = listOf(table),
      flips = flips(table, outcomes),
      census = null,
    )

    // Every symbol with a usable bit, used or not.
    assertEquals(listOf(0, 1), candidates.map { it.index })
  }

  @Test
  fun anUnclassifiedPayloadOffersNothing() {
    val table = smallTable()
    val candidates = DhtSweep.randomFlipCandidates(tableList(table), emptyMap(), census(table, emptySet()))
    assertEquals(emptyList<HuffmanSymbol>(), candidates)
  }

  private fun tableList(table: HuffmanTable): List<HuffmanTable> = listOf(table)
}
