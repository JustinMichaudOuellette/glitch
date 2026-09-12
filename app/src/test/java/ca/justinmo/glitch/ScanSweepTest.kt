package ca.justinmo.glitch

import ca.justinmo.glitch.jpeg.ByteSpan
import ca.justinmo.glitch.jpeg.ByteSpans
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.JpegStructure
import ca.justinmo.glitch.jpeg.ScanGeometry
import ca.justinmo.glitch.jpeg.ScanInfo
import ca.justinmo.glitch.scan.ScanSweep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The scan screen's whole promise is that the same settings write the same file,
 * and that a band's bits are inside the part of the scan the decoder can survive
 * losing. Both are properties of [ScanSweep.plan] alone, so they are checked here
 * against a real file rather than by looking at a preview.
 */
class ScanSweepTest {

  private fun fixture(rel: String): ByteArray {
    val file = File(rel)
    assumeTrue("fixture missing: ${file.absolutePath}", file.exists())
    return file.readBytes()
  }

  @Test
  fun thePlanHitsTheScansDataAndNothingFragile() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val scan = structure.flippableSpans(GlitchRegion.SCAN)
    val markers = structure.spansOf(GlitchRegion.RST)

    val plan = ScanSweep.plan(structure, centre = 0.5f, bandWidth = 0.25f, bitsPerUnit = 2)
    assertTrue("a band in the middle of the picture plans bits", plan.isNotEmpty())

    for (flip in plan) {
      assertTrue("offset ${flip.offset} is inside the scan", ByteSpans.contains(scan, flip.offset))
      assertFalse("offset ${flip.offset} is not a restart marker", ByteSpans.contains(markers, flip.offset))
      assertFalse("offset ${flip.offset} is not a fatal byte", structure.isFatal(flip.offset))
      assertTrue("bit ${flip.bit} exists", flip.bit in 0..7)
    }
    assertEquals("a byte is never flipped twice", plan.size, plan.map { it.offset }.toSet().size)
  }

  @Test
  fun theSameSettingsAlwaysWriteTheSameFile() {
    val data = fixture("../b.jpg")
    val structure = JpegParser.parse(data)
    val first = ScanSweep.plan(structure, 0.4f, 0.3f, 3)
    val again = ScanSweep.plan(structure, 0.4f, 0.3f, 3)
    assertEquals("the plan is not random", first, again)

    val applied = ScanSweep.apply(data, first)
    assertTrue("the bytes changed", applied.indices.any { applied[it] != data[it] })
    assertTrue("the original is left alone", data.indices.none { data[it] != fixture("../b.jpg")[it] })
  }

  @Test
  fun strengthAndHeightWidenTheDamage() {
    val structure = JpegParser.parse(fixture("../b.jpg"))
    val gentle = ScanSweep.plan(structure, 0.5f, 0.2f, 1)
    val harder = ScanSweep.plan(structure, 0.5f, 0.2f, 8)
    val wider = ScanSweep.plan(structure, 0.5f, 1f, 1)
    assertTrue("more strength flips more bits", harder.size > gentle.size)
    assertTrue("a taller band flips more bits", wider.size > gentle.size)
    assertTrue("a sliver of the picture still flips something", ScanSweep.plan(structure, 0.5f, 0.02f, 1).isNotEmpty())
  }

  /** A file-shaped stand-in: one 128-byte scan, a 4x8 unit grid, 64 rows tall. */
  private fun syntheticScan(): Pair<ByteArray, JpegStructure> {
    val data = ByteArray(128) { (it and 0xFF).toByte() }
    val scan = ScanInfo(
      index = 0,
      markerOffset = 0,
      start = 0,
      endExclusive = data.size,
      components = emptyList(),
      ss = 0,
      se = 63,
      ah = 0,
      al = 0,
    )
    val geometry = ScanGeometry(
      scan = scan,
      unitsPerRow = 4,
      unitRows = 8,
      rowsPerUnitRow = 8,
      imageHeight = 64,
    )
    val structure = JpegStructure(
      dqt = emptyList(),
      dht = emptyList(),
      scan = listOf(ByteSpan(0, data.size)),
      rst = emptyList(),
      scans = listOf(scan),
      geometry = listOf(geometry),
      frame = null,
      fatal = emptyList(),
      metadata = emptyList(),
    )
    return data to structure
  }

  @Test
  fun everySliderPositionIsOneSectionAndMapsToIt() {
    val (_, structure) = syntheticScan()
    val geometry = structure.geometry.first()
    val sections = geometry.unitRows
    assertTrue("more than one section to step through", sections > 1)

    // The discrete positions the slider offers: one per section, endpoints included.
    val positions = (0 until sections).map { it.toFloat() / (sections - 1) }
    assertEquals(
      "each position lands on its own unit row",
      (0 until sections).toList(),
      positions.map { geometry.unitRowForFraction(it) },
    )
    assertEquals("the last position is the last row", sections - 1, geometry.unitRowForFraction(1f))
  }

  @Test
  fun aFlipSkipsCandidatesTheTestRejects() = runBlocking {
    val (data, structure) = syntheticScan()
    var tested = 0
    val found = ScanSweep.findEffectiveFlip(
      structure = structure,
      areaFraction = 0.5f,
      startStep = 0,
      maxAttempts = 8,
      base = data,
    ) { _, _ ->
      tested++
      tested == 3
    }

    assertNotNull("a candidate was accepted", found)
    assertEquals("the first two were skipped", 3, tested)
    assertEquals("it is the third candidate", ScanSweep.flipAt(structure, 0.5f, 2), found!!.flip)
    assertEquals("the cursor is past it", 3, found.nextStep)
  }

  @Test
  fun aFlipStartsWhereTheWalkLeftOff() = runBlocking {
    val (data, structure) = syntheticScan()
    val found = ScanSweep.findEffectiveFlip(structure, 0.5f, 5, 8, data) { _, _ -> true }

    assertEquals(ScanSweep.flipAt(structure, 0.5f, 5), found!!.flip)
    assertEquals(6, found.nextStep)
  }

  @Test
  fun theSearchGivesUpAfterTheAttemptLimit() = runBlocking {
    val (data, structure) = syntheticScan()
    var tested = 0
    val found = ScanSweep.findEffectiveFlip(structure, 0.5f, 0, 4, data) { _, _ ->
      tested++
      false
    }

    assertNull("nothing was accepted", found)
    assertEquals("it tried exactly the limit", 4, tested)
  }
}
