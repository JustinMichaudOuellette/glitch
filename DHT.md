# DHT bit flips - what every bit does

Reference for the flip UI: the JPEG **Huffman tables (DHT)** of the images
this batch was swept from, what each bit changes, which bits are usable, and
which ones have no visible effect.

Built from `b_x4/dht/` - 2698 composites produced by
`python jpg_batch_glitch.py b.jpg e.jpg f.jpg g.jpg` (four panels: b.jpg,
e.jpg, f.jpg, g.jpg; each resized to 1080 px wide, re-encoded at q92, then
one DHT bit flipped in all four and the results laid out side by side).
Every claim here comes from those files, from the folder names for the effect
families, and from re-running all 3328 flips through the same
encoder/decoder and measuring the result against the unflipped frame.

Two notes on the measurements, because they are what the rest of this file
rests on:

* The unflipped frame is exactly recoverable. Every composite was written by
  the same encoder with the same settings, so a bit that changes nothing
  produces a *pixel-identical* file: 1652 of the 1856 files in `nop/` decode
  to exactly the same pixels as `nop/b_x4_DHT_1080w_bit00000.jpg`, and every
  other `nop/` file measures below 2.0 (mean absolute pixel change) except
  a handful, listed in section 6. "Mean change" below is the mean absolute
  luma difference against that baseline, averaged over the panel; **2.0 is
  the cut** - everything above it is a real change, everything below it is
  noise.
* The four panels are b, e, f, g left to right, in the order the files were
  given on the command line. Panel 3 (f.jpg) is the busiest of the four, so
  it is the panel that shows a flip most often.

## 0. What the UI needs to know

* The payload is **3328 bits** = 416 bytes = **four Huffman tables**, one per
  (class, id): DC luma, AC luma, DC chroma, AC chroma.
* A table is `1 information byte + 16 code-length counts + N symbols`. The
  two DC tables carry 12 symbols each, the two AC tables 162 each:
  `4 x (1 + 16) + 2 x 12 + 2 x 162 = 416` bytes.
* **2698 bits have a sample image. 630 bits do not**, because flipping them
  makes the file undecodable - decoder and all. See section 3. Do not show
  them as flips that produce a black frame; there is nothing to show.
* **848 of the 2698 bits change the picture** on at least one of the four
  photos; the other 1850 decode to something pixel-identical.
* **The bit's strength is decided by which symbol it belongs to, not by its
  bit weight.** The symbol list is stored in code order, so symbol #0 owns
  the shortest code and therefore appears most often in the bitstream.
  Symbol #2 of AC luma changes the picture by 144 on average over its eight
  bits; symbol #150 changes nothing at all, at any bit weight. Section 5 has
  the numbers - it is the single most useful fact for the UI.
* The folder names in `b_x4/dht/` say how many of the four sample photos the
  flip visibly changes (25% = one of four, 100% = all four) and `bad` means
  the change wrecks the picture rather than merely altering it. Section 4
  maps them onto the measurements.
* A usable UI is therefore **four tables, each one information byte, one
  row of 16 code-length counts, and a symbol list** - but almost all of the
  interesting range is in the two AC symbol lists: 767 of the 848 live bits
  are there.

## 1. Where the bits are

Pillow writes **four DHT segments**, one table each, so the payload the
sweep walks is their concatenation:

```
payload byte   0        1-16        17-28          29      30-45
content        info0    BITS[1..16] HUFFVAL 0-11   info1   BITS[1..16]
               DC luma  code counts 12 DC symbols  AC luma code counts

payload byte   46-207          208      209-224     225-236       237
content        HUFFVAL 0-161   info2    BITS[1..16] HUFFVAL 0-11 info3
               162 AC symbols  DC chroma code counts 12 symbols   AC chroma

payload byte   238-253      254-415
content        BITS[1..16]  HUFFVAL 0-161
               code counts   162 AC symbols
```

Bit 0 of a byte is the least significant bit, exactly as
`jpg_batch_glitch.py` counts it:

```python
byte  = bit // 8                        # 0-415, index into the payload
k     = bit %  8                        # 0 = 1s bit, 7 = 128s bit
# then find which table byte falls in, from the map above
info  = payload[0], payload[29], payload[208], payload[237]
```

The four tables in these files, as the byte values stand before any flip:

| table | class/id | info byte | BITS[1..16] | symbols |
|---|---|---|---|---|
| DC luma | Tc=0, Th=0 | `0x00` | `0 1 5 1 1 1 1 1 1 0 0 0 0 0 0 0` | 0,1,2,...,11 |
| AC luma | Tc=1, Th=0 | `0x10` | `0 2 1 3 3 2 4 3 5 5 4 4 0 0 1 125` | symbol list of 162 |
| DC chroma | Tc=0, Th=1 | `0x01` | `0 3 1 1 1 1 1 1 1 1 1 0 0 0 0 0` | 0,1,2,...,11 |
| AC chroma | Tc=1, Th=1 | `0x11` | `0 2 1 2 4 4 3 4 7 5 4 4 0 1 2 119` | symbol list of 162 |

Read a BITS row as: `BITS[n]` is how many codes of length `n` the table has.
DC luma has no 1-bit code, one 2-bit code, five 3-bit codes, one each of
length 4 to 9, and nothing longer. AC luma's 125 sixteen-bit codes are
almost all dead weight on these photos (section 6).

A DC symbol is the number of extra bits a DC difference needs, so 0-11 for
8-bit samples. An AC symbol is one byte holding `run << 4 | size`: how many
zero coefficients to skip, and how many bits the non-zero one needs.

**These are the JPEG Annex K standard tables**, because Pillow does not
optimise Huffman coding when it saves. That is not a detail - it is why a
whole class of flips in the information byte does nothing at all (section 2).

## 2. What one flipped bit does

Three field classes, three completely different behaviours.

**Information byte** (bytes 0, 29, 208, 237): holds Tc in the high nibble
and Th in the low nibble. Only 10 of its 32 bits leave a file that opens,
and they do one of two things:

* *remove a table* - bit 0, 1 and 4 of byte 0, bit 0 and 1 of byte 29, bit 1
  and 4 of byte 208, and bit 1 of byte 237 all give the table a different id
  or the other class, so the id the scan actually asks for is left
  undefined. The decoder then substitutes the standard table for that
  class/id - which is byte-for-byte the table that was just removed - and
  the picture comes out **pixel-identical**. Eight of the ten are this.
* *reclassify a table the scan does use* - bit 1664 turns chroma's DC table
  into DC table 0, overwriting luma's; bit 1896 turns chroma's AC table into
  AC table 0, overwriting luma's. Those two are the loudest information-byte
  flips there are: mean change 145 and 103, all four photos wrecked, filed
  as `bad`.

The remaining 22 information-byte bits kill the file: 20 of them ask for an
index the format does not have (Th 4-15, Tc 2-15), and 2 hand a table to the
other class at a point where the scan is using it.

**BITS bytes** (the 64 bytes of code-length counts, 512 bits): **every one
of them is fatal.** Not one has a sample image. Changing a count changes how
many symbol bytes the decoder reads after the counts, which shifts the parse
of the whole segment; the file no longer decodes. Section 3.

**HUFFVAL bytes** (the symbols, 2592 bits in the two AC tables, 192 in the
two DC tables): this is where the flips live. Changing a symbol changes the
value the decoder assigns to one code, and nothing else - the code keeps its
length, the table stays legal, the file opens. What you get depends on which
table:

* **AC tables - 2592 bits, all of them open the file.** The symbol is a
  (run, size) pair, so a flip makes a block put a different coefficient
  somewhere else, or with a different magnitude. No desynchronisation: the
  decoder still consumes exactly one code per symbol, so the damage is
  confined to the blocks that used that code. It shows across the whole
  panel rather than in a band: the fraction of pixels moved, top band to
  bottom band, is 0.60 0.69 0.80 0.89 0.94 0.96 0.97 0.98 for AC luma and
  0.64 0.76 0.79 0.84 0.86 0.93 0.97 0.99 for AC chroma.
* **DC tables - only 96 of the 192 bits open the file.** The DC symbol is a
  *bit count*: the decoder reads that many raw bits after the code. Change
  it and every block that used the code is read wrong from that point on -
  a full desynchronisation. That shows in the measurement: DC flips move
  essentially every pixel of the panel (band means 0.92-1.00 all the way
  down) and average 45-56 mean change against the AC tables' 18-35.

The two DC tables are also by far the densest field: 81 of their 96 usable
bits change the picture, against 767 of 2592 in the AC tables. Every DC
symbol's low nibble is flippable (4 bits x 12 symbols x 2 tables) and DC
symbols are the shortest codes there are, so almost all of them are violent.
The exceptions are the last symbol of each list - #11 of DC luma and #9 of
DC chroma - which these photos never use, and every one of their eight bits
is a `nop`.

Luma-table flips show up as brightness, chroma-table flips as colour, and
the green channel moves most in both cases (green carries about 0.59 of the
luma signal). Mean channel shift, over every live bit of that field:

| field | R | G | B |
|---|---|---|---|
| DC luma HUFFVAL | -4.5 | +58.7 | +26.4 |
| DC chroma HUFFVAL | +13.0 | +51.1 | +32.0 |
| AC luma HUFFVAL | +1.4 | +36.8 | +9.3 |
| AC chroma HUFFVAL | +14.6 | +43.0 | +29.1 |

## 3. Bits that make the file undecodable

```
fatal    bits 2,3,5,6,7          byte 0        DC luma   information byte
fatal    bits 8-135              bytes 1-16    DC luma   BITS[1..16]   (128 bits)
fatal    every bit k=4..7 of     bytes 17-28   DC luma   HUFFVAL      (48 bits)
fatal    bits 234-239            byte 29       AC luma   information byte
fatal    bits 240-367            bytes 30-45   AC luma   BITS[1..16]   (128 bits)
fatal    bits 1666,1667,1669-1671 byte 208     DC chroma information byte
fatal    bits 1672-1807          bytes 209-224 DC chroma BITS[1..16]   (128 bits)
fatal    every bit k=4..7 of     bytes 225-236 DC chroma HUFFVAL      (48 bits)
fatal    bits 1898-1903          byte 237      AC chroma information byte
fatal    bits 1904-2031          bytes 238-253 AC chroma BITS[1..16]   (128 bits)
```

630 bits in total: **512** in the four BITS arrays, **96** in the high
nibbles of the two DC symbol lists, **22** in the information bytes. These
are exactly the 630 bits with no file in `b_x4/dht/`, because
`jpg_batch_glitch.py` writes no frame when every panel fails to decode.

This is not an inference from the missing files: re-running the flips
confirms it. All 512 BITS bits and all 96 DC high-nibble bits fail on all
four photos, and so do those 22 information-byte bits. The decoder reports
`broken data stream when reading image file` in every case.

The three rules behind those failures:

1. **Changing a code count desynchronises the table.** After the 16 counts
   the decoder reads `sum(BITS)` symbol bytes. The segment physically holds
   a fixed number, so any change either overruns it (count too high) or
   leaves bytes over that are then read as the next table's header. A flip
   in a BITS byte is therefore fatal in both directions, at every bit
   weight.
2. **A DC symbol of 16 or more is not a category the decoder can use.** The
   DC symbol is a number of bits to read, and the low nibble (values 0-15)
   leaves a file that opens while the high nibble (16-255) does not. Within
   the low nibble the decoder accepts everything, including 12-15, which
   are outside what an 8-bit sample should need.
3. **The information byte can ask for something that is not there.** 20 of
   the 22 bits ask for an index the format does not have - `Th` above 3 or
   `Tc` above 1. The other 2 give a table the other class, and whether that
   is fatal depends on where the table sits: making AC luma's byte say `DC`
   (bit 236) hands 162 AC symbols to a DC table and destroys the file, while
   making DC luma's byte say `AC` (bit 4) merely leaves DC table 0
   undefined, which the decoder fills in from the standard table - a no-op.

## 4. Effect families - the folders

`b_x4/dht/` sorts the 2698 composites into nine folders. The number is how
many of the four sample photos the flip visibly changes; `bad` marks a flip
whose result is a wreck rather than a visible alteration. Measured against
the unflipped frame:

| folder | files | panels changed (mean diff > 2) | mean of the strongest panel | median strongest |
|---|---|---|---|---|
| `nop` | 1856 | 0.00 | 0.05 | 0.00 |
| `25pct` | 135 | 1.19 | 36.1 | 29.7 |
| `50pct` | 58 | 2.09 | 56.0 | 49.3 |
| `75pct` | 16 | 3.06 | 68.1 | 68.1 |
| `100pct` | 2 | 4.00 | 71.5 | 77.6 |
| `25pct bad` | 132 | 2.21 | 78.7 | 75.9 |
| `50pct bad` | 118 | 3.08 | 96.8 | 90.8 |
| `75pct bad` | 140 | 3.62 | 121.1 | 117.1 |
| `bad` | 241 | 4.00 | 122.8 | 120.4 |

The panel count tracks the folder number; the strength tracks the `bad`
suffix. The `bad` folders measure about half a panel higher than their plain
twins because a flip violent enough to wreck one photo usually disturbs the
others past the noise floor too, even when the change there is not the one
worth counting.

Counted by folder, the live bits are: 241 `bad`, 140 `75pct bad`, 135
`25pct`, 132 `25pct bad`, 118 `50pct bad`, 58 `50pct`, 16 `75pct`, 2
`100pct` - and 6 of the 1856 `nop` files, which are the outliers of section
6. None of the 630 fatal bits appears here: they are not a tenth family of
"no effect", they are the case where there is no picture to file.

Only two bits manage to change all four photos without wrecking them, and
both are ordinary AC symbol changes: bit 770 (AC luma symbol #50, value 52
-> 48, i.e. `run 3, size 4` -> `run 3, size 0`) and bit 2274 (AC chroma
symbol #30, value 82 -> 86, `run 8, size 2` -> `run 8, size 6`). Their
per-panel changes are 3, 48, 78, 43 and 36, 21, 57, 65 - strong on every
photo but not total, which is why they are the only `100pct` entries and
everything stronger is a `bad`.

## 5. How strong - the code length is the dial

The symbol list is stored in code order. Symbol #0 owns the shortest code,
and short codes are the ones the encoder hands out to the most frequent
(run, size) pairs, so a symbol's position in the list is a direct measure of
how often a flip of it will fire. Measured on AC luma, mean over the eight
bit weights of the strongest panel's change:

| code length | symbols | mean change | photos spoilt per bit |
|---|---|---|---|
| 2 | 2 | 166.9 | 4.00 |
| 3 | 1 | 194.1 | 4.00 |
| 4 | 3 | 139.2 | 3.25 |
| 5 | 3 | 143.0 | 3.75 |
| 6 | 2 | 162.9 | 3.50 |
| 7 | 4 | 148.9 | 3.53 |
| 8 | 3 | 154.4 | 3.58 |
| 9 | 5 | 151.7 | 3.30 |
| 10 | 5 | 145.6 | 3.15 |
| 11 | 4 | 155.9 | 3.25 |
| 12 | 4 | 110.3 | 3.00 |
| 15 | 1 | 118.2 | 2.50 |
| 16 | 125 | 23.6 | 0.40 |

The first 16 symbols of AC luma average 154.5; the remaining 146 average
40.4. AC chroma is the same shape with a shorter tail: the 119 sixteen-bit
symbols there average 1.5, i.e. nothing.

So the UI's strength control is the symbol index, and its bit weight is a
much weaker second control. Within one symbol the eight bit weights are
*not* ordered - flipping bit 7 of a symbol is not "more" than flipping bit
0 - because the symbol is a packed byte: bits 0-3 are `size`, how many bits
the coefficient needs, and bits 4-7 are `run`, how far along the block it
lands. The two halves are different kinds of damage at similar strength, not
two ends of one scale.

## 6. Why so much of the table does nothing

1850 of the 2698 bits change the picture by less than the re-encode floor,
and 1031 of those are in AC chroma. Two different reasons, and it is worth
keeping them apart:

* **The code never appears in the bitstream.** A symbol can only change
  something if its code is actually used. 85 of the 162 AC luma symbols and
  117 of the 162 AC chroma symbols are inert at every bit weight. They are
  not a clean tail: the first dead AC luma symbol is #38, and from #135 up
  everything is dead, with the rest scattered in between. This is largely a
  property of the standard tables, not of the photos: the same single-panel
  sweep on a uniform-noise image of the same size, encoded the same way,
  wakes up only 270 of the 1296 AC luma bits against b.jpg's 268, and the
  long codes stay dead on both. The standard tables reserve their 16-bit
  codes for (run, size) combinations that essentially no image produces.
* **The code appears, but the value it changes to does not show.** A symbol
  whose run/size is only slightly different produces a coefficient a few
  levels off, which the composite's own q95 re-encode smooths away. That is
  the bulk of the 1850.

For the UI this means a DHT bit's effect is **content dependent**, like the
DQT bits but for a different reason: not "does this frequency have energy"
but "does this code occur". Two honest options, the same two as for DQT:

1. **Per-image availability** - decode the image, tally which (run, size)
  pairs actually occur, and grey out the symbols whose code never appears.
  This is exact and cheap: one entropy-decode pass gives the histogram.
  *This is what the app now does*: the Huffman Table screen (`DhtScreen.kt`,
  between the quantization screen and the bit editor) walks the scan once,
  lists each table's symbols by how often the photo uses them, and hides the
  codes it never emits. The walk is checked against `b.jpg`, where it
  consumes the scan byte for byte (1,637,257 of 1,637,257) and decodes the
  end-of-block code exactly once per 8x8 block.
2. **A showcase image** - there is no source that wakes up all 162 symbols,
  so unlike DQT this does not fully solve it; a detail-rich image moves the
  line a little (268 -> 270) but not the tail. Option 1 is the only one that
  gives a complete picture.

Six files are filed under `nop/` but do change a photo: bits 652 and 653
(panel 3 by 20.5 and 20.3), 738 (9.6 on panel 3), 663 (5.7 on panel 3),
2279 (11.9 on panel 4) and 2165 (3.7 and 4.9 on panels 3 and 4). Three more
- 1588, 1589, 1591 - sit at 1.6 on panel 3, right on the floor. Every other
`nop/` file measures under 2.0, so the folder is right about the other 1850,
and nothing outside `nop/` measures as a no-op.

## 7. Appendix - examples to link to

| what | file | what it is | per-panel change |
|---|---|---|---|
| the unflipped frame | `b_x4/dht/nop/b_x4_DHT_1080w_bit00000.jpg` | no flip | 1652 files decode to exactly these pixels |
| a `25pct` change | `b_x4/dht/25pct/b_x4_DHT_1080w_bit00217.jpg` | DC luma symbol #10, 10 -> 8 | 0, 79, 0, 0 |
| a `50pct` change | `b_x4/dht/50pct/b_x4_DHT_1080w_bit00580.jpg` | AC luma symbol #26, 177 -> 161 | 0, 31, 36, 0 |
| a `75pct` change | `b_x4/dht/75pct/b_x4_DHT_1080w_bit00888.jpg` | AC luma symbol #65, 83 -> 82 | 1, 43, 54, 10 |
| a `100pct` change | `b_x4/dht/100pct/b_x4_DHT_1080w_bit00770.jpg` | AC luma symbol #50, 52 -> 48 | 3, 48, 78, 43 |
| the heaviest bit | `b_x4/dht/bad/b_x4_DHT_1080w_bit00555.jpg` | AC luma symbol #23, 8 -> 0 | 88, 95, 103, 200 |
| an information-byte reclassification | `b_x4/dht/bad/b_x4_DHT_1080w_bit01664.jpg` | chroma DC table overwrites luma's | 83, 90, 96, 145 |
| a fatal bit | *(no file)* | bit 8, DC luma BITS[1]: every panel stops decoding | - |

## 8. Appendix - the payload byte map

| payload bytes | bits | table | field | what a flip there does |
|---|---|---|---|---|
| 0 | 0-7 | DC luma | information byte | 3 bits are no-ops, 5 are fatal (section 2) |
| 1-16 | 8-135 | DC luma | BITS[1..16] | **all 128 fatal** - no sample, no picture |
| 17-28 | 136-231 | DC luma | HUFFVAL symbols 0-11 | bit 0-3 change the DC size, bit 4-7 are fatal |
| 29 | 232-239 | AC luma | information byte | 2 bits are no-ops, 6 are fatal |
| 30-45 | 240-367 | AC luma | BITS[1..16] | **all 128 fatal** |
| 46-207 | 368-1663 | AC luma | HUFFVAL symbols 0-161 | ordinary value changes; strength follows the code length |
| 208 | 1664-1671 | DC chroma | information byte | bit 1664 reclassifies DC table 0 and wrecks all four photos; 2 no-ops, 5 fatal |
| 209-224 | 1672-1807 | DC chroma | BITS[1..16] | **all 128 fatal** |
| 225-236 | 1800-1895 | DC chroma | HUFFVAL symbols 0-11 | bit 0-3 change the DC size, bit 4-7 are fatal |
| 237 | 1896-1903 | AC chroma | information byte | bit 1896 reclassifies AC table 0 and wrecks all four photos; 2 no-ops, 5 fatal |
| 238-253 | 1904-2031 | AC chroma | BITS[1..16] | **all 128 fatal** |
| 254-415 | 2032-3327 | AC chroma | HUFFVAL symbols 0-161 | ordinary value changes |

## 9. Appendix - every bit, by field

Every bit of the 3328, grouped the way the tables are. `k0`-`k7` are the
eight bit weights, each cell holding the folder that bit was filed in:

```
---  no sample image: the flip stops the file decoding
nop  no visible change     25 / 25b    one of the four photos, plain / wrecked
50 / 50b   two of the four 75 / 75b    three of the four
100  all four              bad         all four, wrecked
```

`strongest` is the largest mean pixel change any of that byte's eight bits
makes on the four photos (0.0 means inert at every bit weight; 2.0 is the
noise floor). `run,size` unpacks an AC symbol; `len` is the code length the
symbol's position in the list gives it, which is why the early rows are the
loud ones.

### The four information bytes (32 bits)

`class` is Tc (0 = DC table, 1 = AC table), `id` is Th (0-3). A flip is
fatal when it makes the byte an index no DHT segment can legitimately
carry, or when it turns a table into a table of the other class.

| bit | byte | table | value before | value after | class/id after | file opens | effect on the picture | folder |
|---|---|---|---|---|---|---|---|---|
| 0 | 0 | DC luma | 0 | 1 | 0,1 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 1 | 0 | DC luma | 0 | 2 | 0,2 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 2 | 0 | DC luma | 0 | 4 | 0,4 | 0/4 | file is rejected | --- |
| 3 | 0 | DC luma | 0 | 8 | 0,8 | 0/4 | file is rejected | --- |
| 4 | 0 | DC luma | 0 | 16 | 1,0 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 5 | 0 | DC luma | 0 | 32 | 2,0 | 0/4 | file is rejected | --- |
| 6 | 0 | DC luma | 0 | 64 | 4,0 | 0/4 | file is rejected | --- |
| 7 | 0 | DC luma | 0 | 128 | 8,0 | 0/4 | file is rejected | --- |
| 232 | 29 | AC luma | 16 | 17 | 1,1 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 233 | 29 | AC luma | 16 | 18 | 1,2 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 234 | 29 | AC luma | 16 | 20 | 1,4 | 0/4 | file is rejected | --- |
| 235 | 29 | AC luma | 16 | 24 | 1,8 | 0/4 | file is rejected | --- |
| 236 | 29 | AC luma | 16 | 0 | 0,0 | 0/4 | file is rejected | --- |
| 237 | 29 | AC luma | 16 | 48 | 3,0 | 0/4 | file is rejected | --- |
| 238 | 29 | AC luma | 16 | 80 | 5,0 | 0/4 | file is rejected | --- |
| 239 | 29 | AC luma | 16 | 144 | 9,0 | 0/4 | file is rejected | --- |
| 1664 | 208 | DC chroma | 1 | 0 | 0,0 | 4/4 | reclassifies the table, max change 146 | bad |
| 1665 | 208 | DC chroma | 1 | 3 | 0,3 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 1666 | 208 | DC chroma | 1 | 5 | 0,5 | 0/4 | file is rejected | --- |
| 1667 | 208 | DC chroma | 1 | 9 | 0,9 | 0/4 | file is rejected | --- |
| 1668 | 208 | DC chroma | 1 | 17 | 1,1 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 1669 | 208 | DC chroma | 1 | 33 | 2,1 | 0/4 | file is rejected | --- |
| 1670 | 208 | DC chroma | 1 | 65 | 4,1 | 0/4 | file is rejected | --- |
| 1671 | 208 | DC chroma | 1 | 129 | 8,1 | 0/4 | file is rejected | --- |
| 1896 | 237 | AC chroma | 17 | 16 | 1,0 | 4/4 | reclassifies the table, max change 106 | bad |
| 1897 | 237 | AC chroma | 17 | 19 | 1,3 | 4/4 | nothing (decoder falls back to the same standard table) | nop |
| 1898 | 237 | AC chroma | 17 | 21 | 1,5 | 0/4 | file is rejected | --- |
| 1899 | 237 | AC chroma | 17 | 25 | 1,9 | 0/4 | file is rejected | --- |
| 1900 | 237 | AC chroma | 17 | 1 | 0,1 | 0/4 | file is rejected | --- |
| 1901 | 237 | AC chroma | 17 | 49 | 3,1 | 0/4 | file is rejected | --- |
| 1902 | 237 | AC chroma | 17 | 81 | 5,1 | 0/4 | file is rejected | --- |
| 1903 | 237 | AC chroma | 17 | 145 | 9,1 | 0/4 | file is rejected | --- |

### The 64 BITS bytes (512 bits)

Every one of these is fatal on all four sample images: there is no
sample image and no folder for any of them. The 16 code-length counts
of each table:

| table | bytes | counts BITS[1..16] | flippable bits | sample images |
|---|---|---|---|---|
| DC luma | 1-16 | `0 1 5 1 1 1 1 1 1 0 0 0 0 0 0 0` | 128 | none |
| AC luma | 30-45 | `0 2 1 3 3 2 4 3 5 5 4 4 0 0 1 125` | 128 | none |
| DC chroma | 209-224 | `0 3 1 1 1 1 1 1 1 1 1 0 0 0 0 0` | 128 | none |
| AC chroma | 238-253 | `0 2 1 2 4 4 3 4 7 5 4 4 0 1 2 119` | 128 | none |

### DC luma HUFFVAL - 12 symbols, 96 bits

Payload bytes 17-28. Each byte is one symbol: the number of extra bits a DC difference of that size needs.

| sym | byte | value | k0 | k1 | k2 | k3 | k4 | k5 | k6 | k7 | strongest |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 17 | 0 | bad | bad | bad | bad | --- | --- | --- | --- | 137 |
| 1 | 18 | 1 | bad | bad | bad | bad | --- | --- | --- | --- | 143 |
| 2 | 19 | 2 | bad | bad | bad | bad | --- | --- | --- | --- | 152 |
| 3 | 20 | 3 | bad | bad | bad | bad | --- | --- | --- | --- | 147 |
| 4 | 21 | 4 | bad | bad | bad | bad | --- | --- | --- | --- | 161 |
| 5 | 22 | 5 | bad | bad | bad | bad | --- | --- | --- | --- | 132 |
| 6 | 23 | 6 | bad | bad | bad | bad | --- | --- | --- | --- | 141 |
| 7 | 24 | 7 | bad | bad | bad | bad | --- | --- | --- | --- | 116 |
| 8 | 25 | 8 | bad | bad | bad | bad | --- | --- | --- | --- | 132 |
| 9 | 26 | 9 | 75b | 75b | 75b | 75b | --- | --- | --- | --- | 118 |
| 10 | 27 | 10 | 25b | 25 | 25 | 25 | --- | --- | --- | --- | 120 |
| 11 | 28 | 11 | nop | nop | nop | nop | --- | --- | --- | --- | 0 |

### DC chroma HUFFVAL - 12 symbols, 96 bits

Payload bytes 225-236. Each byte is one symbol: the number of extra bits a DC difference of that size needs.

| sym | byte | value | k0 | k1 | k2 | k3 | k4 | k5 | k6 | k7 | strongest |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 225 | 0 | bad | bad | bad | bad | --- | --- | --- | --- | 129 |
| 1 | 226 | 1 | bad | bad | bad | bad | --- | --- | --- | --- | 146 |
| 2 | 227 | 2 | bad | bad | bad | bad | --- | --- | --- | --- | 142 |
| 3 | 228 | 3 | bad | bad | bad | bad | --- | --- | --- | --- | 144 |
| 4 | 229 | 4 | bad | bad | bad | bad | --- | --- | --- | --- | 144 |
| 5 | 230 | 5 | 75b | 75b | bad | bad | --- | --- | --- | --- | 130 |
| 6 | 231 | 6 | 75b | 75b | 75b | 75b | --- | --- | --- | --- | 119 |
| 7 | 232 | 7 | 75b | 75b | 75b | 75b | --- | --- | --- | --- | 148 |
| 8 | 233 | 8 | 50b | 50b | 50b | 75b | --- | --- | --- | --- | 123 |
| 9 | 234 | 9 | nop | nop | nop | nop | --- | --- | --- | --- | 0 |
| 10 | 235 | 10 | nop | nop | nop | nop | --- | --- | --- | --- | 0 |
| 11 | 236 | 11 | nop | nop | nop | nop | --- | --- | --- | --- | 0 |

### AC luma HUFFVAL - 162 symbols, 1296 bits

Payload bytes 46-207. Symbols are stored in code order, so symbol #0 owns the shortest code.

| sym | byte | value | run,size | len | k0 | k1 | k2 | k3 | k4 | k5 | k6 | k7 | strongest |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 46 | 1 | 0,1 | 2 | bad | bad | bad | bad | 75b | bad | bad | bad | 155 |
| 1 | 47 | 2 | 0,2 | 2 | bad | bad | bad | bad | 75b | bad | bad | bad | 178 |
| 2 | 48 | 3 | 0,3 | 3 | bad | bad | bad | bad | 25b | 25b | bad | bad | 194 |
| 3 | 49 | 0 | 0,0 | 4 | bad | bad | bad | bad | nop | nop | nop | nop | 99 |
| 4 | 50 | 4 | 0,4 | 4 | bad | bad | bad | bad | 50b | 25b | bad | bad | 171 |
| 5 | 51 | 17 | 1,1 | 4 | bad | bad | bad | bad | 50b | 50b | bad | bad | 148 |
| 6 | 52 | 5 | 0,5 | 5 | bad | bad | bad | bad | 50b | 50b | 75b | bad | 138 |
| 7 | 53 | 18 | 1,2 | 5 | bad | bad | bad | bad | 50b | 50b | 50b | 75b | 157 |
| 8 | 54 | 33 | 2,1 | 5 | bad | bad | bad | bad | 50b | 50b | 50b | 50b | 134 |
| 9 | 55 | 49 | 3,1 | 6 | bad | bad | bad | bad | 50b | 50b | 50b | 75b | 149 |
| 10 | 56 | 65 | 4,1 | 6 | bad | bad | bad | bad | 50b | 50b | 50b | 75b | 177 |
| 11 | 57 | 6 | 0,6 | 7 | bad | bad | bad | bad | 50b | 50b | bad | bad | 168 |
| 12 | 58 | 19 | 1,3 | 7 | bad | bad | bad | bad | 50b | 50b | 50b | 75b | 160 |
| 13 | 59 | 81 | 5,1 | 7 | bad | bad | bad | bad | 50b | 50b | 50b | 50b | 138 |
| 14 | 60 | 97 | 6,1 | 7 | bad | bad | bad | bad | 50b | 25b | 50b | 50b | 130 |
| 15 | 61 | 7 | 0,7 | 8 | bad | bad | bad | bad | 50b | 50b | bad | bad | 176 |
| 16 | 62 | 34 | 2,2 | 8 | bad | bad | bad | bad | 50b | 50b | 50b | 50b | 154 |
| 17 | 63 | 113 | 7,1 | 8 | bad | bad | bad | bad | 50b | 25b | 25b | 50b | 133 |
| 18 | 64 | 20 | 1,4 | 9 | bad | bad | bad | 75b | 50b | 50b | 50b | bad | 177 |
| 19 | 65 | 50 | 3,2 | 9 | bad | bad | bad | bad | 50b | 50b | 50b | 50b | 143 |
| 20 | 66 | 129 | 8,1 | 9 | bad | bad | bad | bad | 25b | 50b | 50b | 25b | 161 |
| 21 | 67 | 145 | 9,1 | 9 | bad | bad | bad | bad | 25b | 25b | 50b | 25b | 149 |
| 22 | 68 | 161 | 10,1 | 9 | bad | bad | bad | bad | 50b | 25 | 50b | 25 | 129 |
| 23 | 69 | 8 | 0,8 | 10 | bad | 75b | bad | bad | 50b | 50b | 50b | 50b | 200 |
| 24 | 70 | 35 | 2,3 | 10 | bad | bad | 50b | bad | 50b | 50b | 50b | 50b | 115 |
| 25 | 71 | 66 | 4,2 | 10 | 75b | bad | 75b | 75b | 50b | 50b | 25b | 50b | 139 |
| 26 | 72 | 177 | 11,1 | 10 | bad | bad | 75b | bad | 50 | 50 | 50 | 25 | 134 |
| 27 | 73 | 193 | 12,1 | 10 | bad | bad | bad | bad | 25 | 25b | 25 | 25 | 140 |
| 28 | 74 | 21 | 1,5 | 11 | 75b | bad | bad | 75b | 50b | 50b | 50b | 50b | 182 |
| 29 | 75 | 82 | 5,2 | 11 | bad | bad | 75b | 75b | 25b | 50b | 25b | 50b | 155 |
| 30 | 76 | 209 | 13,1 | 11 | 75b | 75b | 75b | 75b | nop | 25b | nop | nop | 173 |
| 31 | 77 | 240 | 15,0 | 11 | 75b | 75b | bad | 75b | bad | bad | bad | bad | 114 |
| 32 | 78 | 36 | 2,4 | 12 | 75b | 75b | 75b | 50b | 25b | 25b | 50b | 50b | 112 |
| 33 | 79 | 51 | 3,3 | 12 | 50b | 50b | 50b | 50b | 25b | 25b | 50b | 50b | 90 |
| 34 | 80 | 98 | 6,2 | 12 | 75b | bad | 75b | 75b | 50b | 50 | 50 | 50b | 116 |
| 35 | 81 | 114 | 7,2 | 12 | 75b | 75b | 75b | 75b | nop | nop | 25 | 25b | 124 |
| 36 | 82 | 130 | 8,2 | 15 | 50b | 25b | 50b | 50b | 25 | 25 | 25 | nop | 118 |
| 37 | 83 | 9 | 0,9 | 16 | 50b | 50b | 50b | 25b | 25b | 25b | 25b | 50b | 102 |
| 38 | 84 | 10 | 0,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 39 | 85 | 22 | 1,6 | 16 | 50b | 50b | 50b | 50b | 50b | 50b | 50b | 50b | 122 |
| 40 | 86 | 23 | 1,7 | 16 | 25b | 50b | 50b | 25b | 25b | 50b | 25b | 25b | 106 |
| 41 | 87 | 24 | 1,8 | 16 | 25b | 25b | 25b | 25b | 25 | 25b | 25b | 25b | 89 |
| 42 | 88 | 25 | 1,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 43 | 89 | 26 | 1,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 44 | 90 | 37 | 2,5 | 16 | 25b | 25b | 25b | 25b | 25b | 25 | 25b | 25b | 78 |
| 45 | 91 | 38 | 2,6 | 16 | 25 | 25 | 25 | 50 | 25 | nop | 50 | 50 | 92 |
| 46 | 92 | 39 | 2,7 | 16 | 25 | 25b | nop | 25b | nop | nop | 25 | 25 | 45 |
| 47 | 93 | 40 | 2,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 48 | 94 | 41 | 2,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 49 | 95 | 42 | 2,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 50 | 96 | 52 | 3,4 | 16 | 25b | 50 | 100 | 50b | 25 | 25 | 50 | 25b | 131 |
| 51 | 97 | 53 | 3,5 | 16 | 25b | 25 | 25 | 25 | nop | nop | 25 | 25 | 57 |
| 52 | 98 | 54 | 3,6 | 16 | 25 | nop | nop | nop | nop | nop | nop | nop | 76 |
| 53 | 99 | 55 | 3,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 54 | 100 | 56 | 3,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 55 | 101 | 57 | 3,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 56 | 102 | 58 | 3,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 57 | 103 | 67 | 4,3 | 16 | 25b | 25b | 25b | 25b | 50 | 50 | 50 | 25b | 89 |
| 58 | 104 | 68 | 4,4 | 16 | 25b | 25 | 25b | 25b | 25 | 25 | nop | 50 | 93 |
| 59 | 105 | 69 | 4,5 | 16 | 25b | 25b | 25b | nop | nop | nop | nop | nop | 43 |
| 60 | 106 | 70 | 4,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 61 | 107 | 71 | 4,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 62 | 108 | 72 | 4,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 63 | 109 | 73 | 4,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 64 | 110 | 74 | 4,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 65 | 111 | 83 | 5,3 | 16 | 75 | 75 | 25b | 25b | 50 | 50 | 50 | 25b | 115 |
| 66 | 112 | 84 | 5,4 | 16 | 25 | 25b | 25b | 25b | 25 | 25 | 25 | 25 | 94 |
| 67 | 113 | 85 | 5,5 | 16 | 25 | 25 | 25 | nop | nop | nop | nop | nop | 29 |
| 68 | 114 | 86 | 5,6 | 16 | nop | 25b | 25b | nop | nop | nop | nop | nop | 44 |
| 69 | 115 | 87 | 5,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 70 | 116 | 88 | 5,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 71 | 117 | 89 | 5,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 72 | 118 | 90 | 5,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 73 | 119 | 99 | 6,3 | 16 | 50 | 25b | 50 | 25b | 50 | 25 | 25 | 50 | 77 |
| 74 | 120 | 100 | 6,4 | 16 | 25 | 25 | 25 | 25b | nop | nop | nop | nop | 54 |
| 75 | 121 | 101 | 6,5 | 16 | 25 | nop | 25 | 25 | nop | nop | nop | nop | 45 |
| 76 | 122 | 102 | 6,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 77 | 123 | 103 | 6,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 78 | 124 | 104 | 6,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 79 | 125 | 105 | 6,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 80 | 126 | 106 | 6,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 81 | 127 | 115 | 7,3 | 16 | 50 | 25b | 25b | 25b | nop | nop | nop | 25 | 88 |
| 82 | 128 | 116 | 7,4 | 16 | 25 | 25b | 25b | 25b | nop | nop | nop | nop | 46 |
| 83 | 129 | 117 | 7,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 84 | 130 | 118 | 7,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 85 | 131 | 119 | 7,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 86 | 132 | 120 | 7,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 87 | 133 | 121 | 7,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 88 | 134 | 122 | 7,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 89 | 135 | 131 | 8,3 | 16 | 75 | 75 | 25 | 50 | nop | nop | nop | nop | 77 |
| 90 | 136 | 132 | 8,4 | 16 | 25 | 25 | 25 | 25 | nop | nop | nop | nop | 65 |
| 91 | 137 | 133 | 8,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 92 | 138 | 134 | 8,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 93 | 139 | 135 | 8,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 94 | 140 | 136 | 8,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 95 | 141 | 137 | 8,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 96 | 142 | 138 | 8,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 97 | 143 | 146 | 9,2 | 16 | 25b | 25b | 25b | 25b | nop | 25b | 25b | nop | 116 |
| 98 | 144 | 147 | 9,3 | 16 | 25b | 50 | 25 | 50 | nop | nop | nop | nop | 49 |
| 99 | 145 | 148 | 9,4 | 16 | 25b | 25b | 25b | 25b | nop | nop | nop | nop | 59 |
| 100 | 146 | 149 | 9,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 101 | 147 | 150 | 9,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 102 | 148 | 151 | 9,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 103 | 149 | 152 | 9,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 104 | 150 | 153 | 9,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 105 | 151 | 154 | 9,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 106 | 152 | 162 | 10,2 | 16 | 25b | 50b | 25b | 25b | nop | nop | 25 | nop | 72 |
| 107 | 153 | 163 | 10,3 | 16 | 25b | 25 | 25 | 25 | nop | nop | nop | nop | 84 |
| 108 | 154 | 164 | 10,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 109 | 155 | 165 | 10,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 110 | 156 | 166 | 10,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 111 | 157 | 167 | 10,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 112 | 158 | 168 | 10,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 113 | 159 | 169 | 10,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 114 | 160 | 170 | 10,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 115 | 161 | 178 | 11,2 | 16 | 25b | 25b | 50 | 25b | nop | nop | 25b | nop | 116 |
| 116 | 162 | 179 | 11,3 | 16 | 25 | 25 | 25 | 25b | nop | nop | nop | nop | 51 |
| 117 | 163 | 180 | 11,4 | 16 | 25 | 25 | 25 | 25 | nop | nop | nop | nop | 19 |
| 118 | 164 | 181 | 11,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 119 | 165 | 182 | 11,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 120 | 166 | 183 | 11,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 121 | 167 | 184 | 11,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 122 | 168 | 185 | 11,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 123 | 169 | 186 | 11,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 124 | 170 | 194 | 12,2 | 16 | 25b | 25b | 25b | 25b | nop | nop | nop | nop | 68 |
| 125 | 171 | 195 | 12,3 | 16 | 25b | 25 | 25 | 25 | nop | nop | nop | nop | 57 |
| 126 | 172 | 196 | 12,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 127 | 173 | 197 | 12,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 128 | 174 | 198 | 12,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 129 | 175 | 199 | 12,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 130 | 176 | 200 | 12,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 131 | 177 | 201 | 12,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 132 | 178 | 202 | 12,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 133 | 179 | 210 | 13,2 | 16 | 25b | 50b | 25b | 25b | nop | nop | nop | nop | 70 |
| 134 | 180 | 211 | 13,3 | 16 | 25b | 25b | 25b | 25b | nop | nop | nop | nop | 56 |
| 135 | 181 | 212 | 13,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 136 | 182 | 213 | 13,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 137 | 183 | 214 | 13,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 138 | 184 | 215 | 13,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 139 | 185 | 216 | 13,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 140 | 186 | 217 | 13,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 141 | 187 | 218 | 13,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 142 | 188 | 225 | 14,1 | 16 | 75b | 75b | 75b | 75b | 50 | 25 | 25 | 25 | 133 |
| 143 | 189 | 226 | 14,2 | 16 | 25 | 25 | 25 | 25 | nop | nop | nop | nop | 25 |
| 144 | 190 | 227 | 14,3 | 16 | 25 | 25 | nop | 25 | nop | nop | nop | nop | 18 |
| 145 | 191 | 228 | 14,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 146 | 192 | 229 | 14,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 147 | 193 | 230 | 14,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 148 | 194 | 231 | 14,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 149 | 195 | 232 | 14,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 150 | 196 | 233 | 14,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 151 | 197 | 234 | 14,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 152 | 198 | 241 | 15,1 | 16 | 25b | 50b | 50b | 50b | nop | nop | 25 | nop | 93 |
| 153 | 199 | 242 | 15,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 154 | 200 | 243 | 15,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 155 | 201 | 244 | 15,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 156 | 202 | 245 | 15,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 157 | 203 | 246 | 15,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 158 | 204 | 247 | 15,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 159 | 205 | 248 | 15,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 160 | 206 | 249 | 15,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 161 | 207 | 250 | 15,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |

### AC chroma HUFFVAL - 162 symbols, 1296 bits

Payload bytes 254-415. Symbols are stored in code order, so symbol #0 owns the shortest code.

| sym | byte | value | run,size | len | k0 | k1 | k2 | k3 | k4 | k5 | k6 | k7 | strongest |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 254 | 0 | 0,0 | 2 | bad | bad | bad | bad | nop | nop | nop | nop | 112 |
| 1 | 255 | 1 | 0,1 | 2 | bad | bad | bad | bad | 75b | 75b | 75b | 75b | 153 |
| 2 | 256 | 2 | 0,2 | 3 | bad | bad | bad | bad | 75 | 75b | 75b | 75b | 128 |
| 3 | 257 | 3 | 0,3 | 4 | bad | bad | bad | bad | 25 | 75b | 75b | 75b | 136 |
| 4 | 258 | 17 | 1,1 | 4 | bad | bad | bad | bad | 50 | 50b | 75b | 75b | 195 |
| 5 | 259 | 4 | 0,4 | 5 | bad | bad | bad | 75b | 50 | 25b | 75b | 75b | 131 |
| 6 | 260 | 5 | 0,5 | 5 | 75b | 75b | 75b | 75b | 25 | 25 | 75b | 75b | 131 |
| 7 | 261 | 33 | 2,1 | 5 | bad | bad | bad | bad | 50 | 75 | 75b | 75b | 192 |
| 8 | 262 | 49 | 3,1 | 5 | bad | bad | bad | bad | 25 | 25 | 50b | 75b | 188 |
| 9 | 263 | 6 | 0,6 | 6 | 75b | 75b | 75b | 75b | nop | nop | 50 | 25b | 149 |
| 10 | 264 | 18 | 1,2 | 6 | bad | bad | bad | bad | 25 | 75 | 75 | 50b | 137 |
| 11 | 265 | 65 | 4,1 | 6 | 75b | bad | bad | bad | 50 | 50 | 25 | 50b | 137 |
| 12 | 266 | 81 | 5,1 | 6 | 75b | bad | 75b | 75b | 50 | 75 | 50 | 75b | 124 |
| 13 | 267 | 7 | 0,7 | 7 | 25 | 25b | 50 | 25 | nop | nop | nop | nop | 104 |
| 14 | 268 | 97 | 6,1 | 7 | 75b | bad | bad | bad | nop | 25 | 25 | 25b | 135 |
| 15 | 269 | 113 | 7,1 | 7 | 75b | 75b | 75b | bad | 50 | 50 | 50 | 25b | 177 |
| 16 | 270 | 19 | 1,3 | 8 | 75b | 75b | 75b | bad | 25 | nop | 50 | 25b | 189 |
| 17 | 271 | 34 | 2,2 | 8 | bad | bad | 75b | bad | 25 | nop | 25 | 50 | 122 |
| 18 | 272 | 50 | 3,2 | 8 | 75b | bad | 75b | 75b | nop | nop | 50 | 50 | 116 |
| 19 | 273 | 129 | 8,1 | 8 | 75b | 75b | 75b | 75b | nop | 50 | 25b | 25 | 134 |
| 20 | 274 | 8 | 0,8 | 9 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 21 | 275 | 20 | 1,4 | 9 | 75b | 75b | 75b | 75b | 25 | nop | 25 | 50 | 166 |
| 22 | 276 | 66 | 4,2 | 9 | 75b | 50b | 75b | 50b | nop | nop | nop | 50 | 118 |
| 23 | 277 | 145 | 9,1 | 9 | 75b | 75b | 75b | 75b | 50 | 25b | 25b | 25 | 142 |
| 24 | 278 | 161 | 10,1 | 9 | 75b | 75b | 75b | 75b | 25 | nop | 25 | nop | 128 |
| 25 | 279 | 177 | 11,1 | 9 | 75b | 75b | 75b | 75b | 25 | nop | 25 | nop | 131 |
| 26 | 280 | 193 | 12,1 | 9 | 75b | 75b | 75b | 75b | nop | nop | 25 | 25 | 185 |
| 27 | 281 | 9 | 0,9 | 10 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 28 | 282 | 35 | 2,3 | 10 | 75b | 75b | 75b | 75b | nop | nop | 25 | 25 | 107 |
| 29 | 283 | 51 | 3,3 | 10 | 75 | 25b | 75 | 75 | nop | nop | nop | nop | 83 |
| 30 | 284 | 82 | 5,2 | 10 | 25b | 25b | 100 | 25b | nop | nop | nop | nop | 144 |
| 31 | 285 | 240 | 15,0 | 10 | 75b | 75b | 75b | 75b | 75b | 75b | 75b | 75b | 164 |
| 32 | 286 | 21 | 1,5 | 11 | 50b | 50b | 25b | 25 | nop | nop | nop | nop | 125 |
| 33 | 287 | 98 | 6,2 | 11 | 25 | 75 | 25 | 25 | nop | nop | nop | 25 | 86 |
| 34 | 288 | 114 | 7,2 | 11 | 50 | 25b | 25 | 25 | nop | nop | nop | nop | 69 |
| 35 | 289 | 209 | 13,1 | 11 | 75b | 75b | 75b | 75b | 25 | 25 | 25 | 25 | 144 |
| 36 | 290 | 10 | 0,10 | 12 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 37 | 291 | 22 | 1,6 | 12 | 50 | 50 | 50 | 50 | nop | nop | nop | nop | 98 |
| 38 | 292 | 36 | 2,4 | 12 | 75 | 25b | 75 | 75 | nop | nop | nop | nop | 132 |
| 39 | 293 | 52 | 3,4 | 12 | 50 | 50 | 50 | 25 | nop | nop | nop | nop | 109 |
| 40 | 294 | 225 | 14,1 | 14 | 50b | 75b | 50b | 50b | nop | nop | nop | nop | 152 |
| 41 | 295 | 37 | 2,5 | 15 | 25 | 25 | 25 | 25 | nop | nop | nop | nop | 80 |
| 42 | 296 | 241 | 15,1 | 15 | 25b | 25b | 50b | 25b | nop | nop | nop | nop | 94 |
| 43 | 297 | 23 | 1,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 44 | 298 | 24 | 1,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 45 | 299 | 25 | 1,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 46 | 300 | 26 | 1,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 47 | 301 | 38 | 2,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 48 | 302 | 39 | 2,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 49 | 303 | 40 | 2,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 50 | 304 | 41 | 2,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 51 | 305 | 42 | 2,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 52 | 306 | 53 | 3,5 | 16 | 25 | 25 | 25b | nop | nop | nop | nop | nop | 84 |
| 53 | 307 | 54 | 3,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 54 | 308 | 55 | 3,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 55 | 309 | 56 | 3,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 56 | 310 | 57 | 3,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 57 | 311 | 58 | 3,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 58 | 312 | 67 | 4,3 | 16 | nop | 50 | 50 | 50 | nop | nop | nop | nop | 28 |
| 59 | 313 | 68 | 4,4 | 16 | 25 | 25 | 25 | nop | nop | nop | nop | nop | 13 |
| 60 | 314 | 69 | 4,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 61 | 315 | 70 | 4,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 62 | 316 | 71 | 4,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 63 | 317 | 72 | 4,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 64 | 318 | 73 | 4,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 65 | 319 | 74 | 4,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 66 | 320 | 83 | 5,3 | 16 | nop | 25 | 25 | 25 | nop | nop | nop | nop | 30 |
| 67 | 321 | 84 | 5,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 68 | 322 | 85 | 5,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 69 | 323 | 86 | 5,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 70 | 324 | 87 | 5,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 71 | 325 | 88 | 5,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 72 | 326 | 89 | 5,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 73 | 327 | 90 | 5,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 74 | 328 | 99 | 6,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 75 | 329 | 100 | 6,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 76 | 330 | 101 | 6,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 77 | 331 | 102 | 6,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 78 | 332 | 103 | 6,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 79 | 333 | 104 | 6,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 80 | 334 | 105 | 6,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 81 | 335 | 106 | 6,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 82 | 336 | 115 | 7,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 83 | 337 | 116 | 7,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 84 | 338 | 117 | 7,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 85 | 339 | 118 | 7,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 86 | 340 | 119 | 7,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 87 | 341 | 120 | 7,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 88 | 342 | 121 | 7,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 89 | 343 | 122 | 7,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 90 | 344 | 130 | 8,2 | 16 | 25 | 25 | nop | 25 | nop | nop | nop | nop | 24 |
| 91 | 345 | 131 | 8,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 92 | 346 | 132 | 8,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 93 | 347 | 133 | 8,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 94 | 348 | 134 | 8,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 95 | 349 | 135 | 8,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 96 | 350 | 136 | 8,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 97 | 351 | 137 | 8,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 98 | 352 | 138 | 8,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 99 | 353 | 146 | 9,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 100 | 354 | 147 | 9,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 101 | 355 | 148 | 9,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 102 | 356 | 149 | 9,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 103 | 357 | 150 | 9,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 104 | 358 | 151 | 9,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 105 | 359 | 152 | 9,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 106 | 360 | 153 | 9,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 107 | 361 | 154 | 9,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 108 | 362 | 162 | 10,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 109 | 363 | 163 | 10,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 110 | 364 | 164 | 10,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 111 | 365 | 165 | 10,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 112 | 366 | 166 | 10,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 113 | 367 | 167 | 10,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 114 | 368 | 168 | 10,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 115 | 369 | 169 | 10,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 116 | 370 | 170 | 10,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 117 | 371 | 178 | 11,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 118 | 372 | 179 | 11,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 119 | 373 | 180 | 11,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 120 | 374 | 181 | 11,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 121 | 375 | 182 | 11,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 122 | 376 | 183 | 11,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 123 | 377 | 184 | 11,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 124 | 378 | 185 | 11,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 125 | 379 | 186 | 11,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 126 | 380 | 194 | 12,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 127 | 381 | 195 | 12,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 128 | 382 | 196 | 12,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 129 | 383 | 197 | 12,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 130 | 384 | 198 | 12,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 131 | 385 | 199 | 12,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 132 | 386 | 200 | 12,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 133 | 387 | 201 | 12,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 134 | 388 | 202 | 12,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 135 | 389 | 210 | 13,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 136 | 390 | 211 | 13,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 137 | 391 | 212 | 13,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 138 | 392 | 213 | 13,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 139 | 393 | 214 | 13,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 140 | 394 | 215 | 13,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 141 | 395 | 216 | 13,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 142 | 396 | 217 | 13,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 143 | 397 | 218 | 13,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 144 | 398 | 226 | 14,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 145 | 399 | 227 | 14,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 146 | 400 | 228 | 14,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 147 | 401 | 229 | 14,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 148 | 402 | 230 | 14,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 149 | 403 | 231 | 14,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 150 | 404 | 232 | 14,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 151 | 405 | 233 | 14,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 152 | 406 | 234 | 14,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 153 | 407 | 242 | 15,2 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 154 | 408 | 243 | 15,3 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 155 | 409 | 244 | 15,4 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 156 | 410 | 245 | 15,5 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 157 | 411 | 246 | 15,6 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 158 | 412 | 247 | 15,7 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 159 | 413 | 248 | 15,8 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 160 | 414 | 249 | 15,9 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |
| 161 | 415 | 250 | 15,10 | 16 | nop | nop | nop | nop | nop | nop | nop | nop | 0 |

