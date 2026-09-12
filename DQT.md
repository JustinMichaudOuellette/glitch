# DQT bit flips - what every bit does

Reference for the flip UI: the JPEG **quantization tables (DQT)** of the
images this batch was swept from, what each bit changes, which bits are
usable, and which ones have no visible effect.

Built from `b_x4/dqt/` - 1024 composites produced by
`python jpg_batch_glitch.py b.jpg e.jpg f.jpg g.jpg` (four panels: b.jpg, e.jpg, f.jpg, g.jpg; each resized to 1080 px wide, re-encoded at q92,
then one DQT bit flipped in all four and the results laid out side by
side). Every claim here comes from those files: their bytes for the
layout, their folder names for the effect families, and pixel
measurements against the unflipped composite for the strengths.

## 0. What the UI needs to know

* The payload is **1040 bits** = 130 bytes = two tables of (1 information
  byte + 64 quantizers).
* **1024 bits are usable** and every one of them has a sample
  image in `b_x4/dqt/`. **16 bits are unusable** - bits 0-7 and
  520-527 - because they corrupt the table information byte and no decoder
  will open the result. See section 3.
* Bits group as **128 coefficients x 8 bit weights**. The coefficient picks
  the *pattern*; the bit weight picks the *strength* (bit 0 adds or
  subtracts 1, bit 7 adds or subtracts 128). So the natural UI is 128
  coefficient choices each with an 8-step strength dial - and within one
  coefficient the eight samples are the eight dial positions.
* **On these photos, 763 of the 1024 bits change nothing measurable**, and 74 of the 128 coefficients are completely inert.
  That is a property of the *input image* (smooth photos have no energy at
  high frequencies, so those quantizers multiply nothing), not of the bit.
  Section 6 has the measurements and how to make those bits work.
* If the UI wants every bit to do something, sweep a source with detail at
  every frequency - a noise image lights up even coefficient 63 (7,7),
  which is inert on all four photos here.

## 1. Where the bits are

Pillow writes **two DQT segments**, one per table, so the payload the
sweep walks is their concatenation:

```
bit       0-7     8-519                    520-527   528-1039
byte      0       1-64                     65        66-129
content   info0   luma quantizers 0-63     info1     chroma quantizers 0-63
          Pq/Tq   (zigzag order)           Pq/Tq     (zigzag order)
```

Bit 0 is the least significant bit of its byte:
```python
byte  = bit // 8
table = 0 if byte <= 64 else 1          # 0 = luma, 1 = chroma
pos   = (bit - 8) if table == 0 else (bit - 528)
coeff = pos // 8                        # 0-63, zigzag index of the quantizer
k     = pos % 8                         # bit inside that byte
q_new = q_old ^ (1 << k)                # +/- 2**k
```

The `(u, v)` of each zigzag index, where `u` counts horizontal and `v`
vertical half-cycles per 8x8 block:

```
   0:(0,0)   1:(1,0)   2:(0,1)   3:(0,2)   4:(1,1)   5:(2,0)   6:(3,0)   7:(2,1)
   8:(1,2)   9:(0,3)  10:(0,4)  11:(1,3)  12:(2,2)  13:(3,1)  14:(4,0)  15:(5,0)
  16:(4,1)  17:(3,2)  18:(2,3)  19:(1,4)  20:(0,5)  21:(0,6)  22:(1,5)  23:(2,4)
  24:(3,3)  25:(4,2)  26:(5,1)  27:(6,0)  28:(7,0)  29:(6,1)  30:(5,2)  31:(4,3)
  32:(3,4)  33:(2,5)  34:(1,6)  35:(0,7)  36:(1,7)  37:(2,6)  38:(3,5)  39:(4,4)
  40:(5,3)  41:(6,2)  42:(7,1)  43:(7,2)  44:(6,3)  45:(5,4)  46:(4,5)  47:(3,6)
  48:(2,7)  49:(3,7)  50:(4,6)  51:(5,5)  52:(6,4)  53:(7,3)  54:(7,4)  55:(6,5)
  56:(5,6)  57:(4,7)  58:(5,7)  59:(6,6)  60:(7,5)  61:(7,6)  62:(6,7)  63:(7,7)
```

Read the sizes like this: one 8x8 basis holds `2u` bands across the block,
so each band is `8 / (2u) = 4 / u` px wide and the pattern repeats every
`8 / u` px. `u = 1` is therefore 4 px bands, `u = 4` is 1 px bands, and
`u >= 5` is narrower than a pixel - it reads as fine texture or moire rather
than distinct stripes. `coeff 4 (1,1)` is the classic checkerboard, 4 px
cells; `coeff 63 (7,7)` is the block Nyquist, the harshest texture of all.

Rendered DCT bases, 16x16 px = four blocks, `@` brightest and ` ` darkest.
This is the shape of the error a flip injects into *every* block, scaled by
that block's own coefficient:

`coeff 0 (0,0) - flat / DC`
```
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
```
`coeff 1 (1,0) - coarse vertical`
```
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
@@#+=:  @@#+=:  
```
`coeff 2 (0,1) - coarse horizontal`
```
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
################
++++++++++++++++
================
::::::::::::::::
                
                
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
################
++++++++++++++++
================
::::::::::::::::
                
                
```
`coeff 3 (0,2) - horizontal x2`
```
@@@@@@@@@@@@@@@@
****************
----------------
                
                
----------------
****************
@@@@@@@@@@@@@@@@
@@@@@@@@@@@@@@@@
****************
----------------
                
                
----------------
****************
@@@@@@@@@@@@@@@@
```
`coeff 4 (1,1) - checkered x1`
```
@@#+=:  @@#+=:  
@%#+=:. @%#+=:. 
##*+=-::##*+=-::
++++====++++====
====++++====++++
::-=+*##::-=+*##
 .:=+#%@ .:=+#%@
  :=+#@@  :=+#@@
@@#+=:  @@#+=:  
@%#+=:. @%#+=:. 
##*+=-::##*+=-::
++++====++++====
====++++====++++
::-=+*##::-=+*##
 .:=+#%@ .:=+#%@
  :=+#@@  :=+#@@
```
`coeff 5 (2,0) - vertical x2`
```
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
@*-  -*@@*-  -*@
```
`coeff 24 (3,3) - checkered x3`
```
%= :#@+.%= :#@+.
=+++===+=+++===+
 +@#: =@ +@#: =@
:+#*-:=#:+#*-:=#
#=:-*#+:#=:-*#+:
@= :#@+ @= :#@+ 
+===+++=+===+++=
.+@#: =%.+@#: =%
%= :#@+.%= :#@+.
=+++===+=+++===+
 +@#: =@ +@#: =@
:+#*-:=#:+#*-:=#
#=:-*#+:#=:-*#+:
@= :#@+ @= :#@+ 
+===+++=+===+++=
.+@#: =%.+@#: =%
```
`coeff 63 (7,7) - finest checkered`
```
+=+=+=+=+=+=+=+=
=*:#:#-+=*:#:#-+
+:% @.#=+:% @.#=
=# @ @:+=# @ @:+
+:@ @ #=+:@ @ #=
=#.@ %:+=#.@ %:+
+-#:#:*=+-#:#:*=
=+=+=+=+=+=+=+=+
+=+=+=+=+=+=+=+=
=*:#:#-+=*:#:#-+
+:% @.#=+:% @.#=
=# @ @:+=# @ @:+
+:@ @ #=+:@ @ #=
=#.@ %:+=#.@ %:+
+-#:#:*=+-#:#:*=
=+=+=+=+=+=+=+=+
```

## 2. What one flipped bit does

The decoder multiplies every coefficient of that frequency by the stored
quantizer, so changing the byte by `+/- 2**k` re-scales that frequency
everywhere in the picture. With the tables in this batch a flip is one of:

* **coarser** (bit was 0): that frequency is quantized harder, so whatever
  error the encoder left there is amplified - banding, ringing or a grid
  at exactly that frequency, strongest where the picture has detail there.
* **finer** (bit was 1): the quantizer drops, the picture usually looks
  smoother there - the flip partly repairs the original compression.
* **zero** (bit was the value's only set bit): the quantizer becomes 0 and
  that frequency is deleted outright. 81 bits do this, e.g.
  `17, 25, 33, 41, 49, 65, 73, 81, 122, 138, 146, 154, 162, 170, 179, 227` ...

The two DC coefficients (`coeff 0` of each table) are different: they set
the average of each block, so their bits move overall brightness (luma
table) or color saturation (chroma table) rather than adding a texture.
Measured on this batch, the luma DC bits alone account for the loudest
changes of all 1024 (strength 14-97 against a 0.25 noise floor).

Measured strength by bit weight, averaged over all coefficients (mean
absolute pixel change, 0.25 = nothing visible):

| bit weight | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| mean strength | 0.40 | 0.56 | 0.74 | 1.03 | 1.43 | 2.03 | 2.89 | 3.98 |

So the bit weight is a usable strength dial: each step roughly doubles the
quantizer change, and the measured change grows with it.

## 3. Bits that make the file undecodable

```
bits 0-7       byte 0   luma table information byte   (Pq, Tq)
bits 520-527   byte 65  chroma table information byte (Pq, Tq)
```

These are the `Pq` (quantizer precision) and `Tq` (destination table id)
fields, not quantizers. Setting a bit there asks for a table precision the
rest of the file contradicts, or a table id no scan references, and the
decoder rejects the whole file. That is why the batch has **no images for
them**: the sweep only writes a frame when at least one panel still
decodes, and for these 16 bits every panel fails.

Two independent confirmations: the batch's own file inventory is 1024 files
for a 1040-bit payload, and the sibling `b_x3` run (same pipeline, three
panels) logged exactly 16 `skipped-all-broken` DQT bits, at exactly these
two ranges.

For the UI: show bits 0-7 and 520-527 as unavailable/disabled rather than
as flips that produce a black frame - there is nothing to show.

No quantizer bit is undecodable: all 1024 remaining bits decode on every
panel, which is why the sample set is complete.

## 4. Effect families

The sections below call the families by geometry, with the folder names the
batch was sorted into as the alias. `color` in a folder name means table 1
(chroma): the same pattern, but tinted instead of adding luma texture. The
numbers in those folder names are just the order folders were created, not
a strength tier.

```
geometry                    folder names                  canonical name
(0,0) table 0               darken, heavy                 brightness
(0,0) table 1               increase saturation,          saturation
                            lower saturation
u=1, v=0                    vertical                      vertical grating 1
u>1, v=0                    vertical square 1-8           vertical grating u
u=0, v=1                    horizontal                    horizontal grating 1
u=0, v>1                    horizontal square 1-5         horizontal grating v
u>0, v>0                    square x 1-45,                checkered
                            square x color 1-10
any, table 1                * color *                     same family, chroma
```

**`square x` is now called `checkered`** as requested; that family is every
coefficient whose pattern runs in both directions at once - a true
checkerboard when `u == v`, a plaid/grid when they differ. The per-
coefficient table in section 5 says which of the two each one is.

Two families are worth reading carefully because one coefficient got split
across two folders, by strength:

* luma DC (`coeff 0`, q = 3): bits 10-12 become 7, 11, 19 -> `darken`; bits
  13-15 become 35, 67, 131 -> `heavy`. Bits 8-9 make it *finer* (3 -> 2, 1),
  brightening slightly and reading as washed-out, so they were filed under
  `lower saturation`.
* chroma DC (`coeff 0`, q = 3): bits 530-532 (7, 11, 19) and 533-535 (35,
  67, 131) all push color apart -> `increase saturation`; bits 528-529
  (3 -> 2, 1) pull color together -> `lower saturation`. Measurements
  confirm the direction: chroma separation +26 to +107 for the first group,
  -6 and -13 for the second.

## 5. Coefficient reference

One row per quantizer. `q` is its value in this batch's files, and the
eight numbers are what bits 0-7 turn that value into (`*` = zero, the
frequency disappears). `visible` is the largest measured change any of its
eight bits makes on these photos; 0.25 and below is the re-encode noise
floor, i.e. nothing you can see.

### table 0 - luma quantizers (bits 8-519)

| coeff | u,v | pattern | q | bits 0-7 become | visible | folder | sample |
|---|---|---|---|---|---|---|---|
| 0 | 0,0 | flat (DC) | 3 | 2 1 7 11 19 35 67 131 | 97.4 | lower saturation | `b_x4/dqt/lower saturation/b_x4_DQT_1080w_bit00008.jpg` |
| 1 | 1,0 | vertical bands 4.0px | 2 | 3 0* 6 10 18 34 66 130 | 36.4 | vertical | `b_x4/dqt/vertical/b_x4_DQT_1080w_bit00016.jpg` |
| 2 | 0,1 | horizontal bands 4.0px | 2 | 3 0* 6 10 18 34 66 130 | 37.7 | horizontal | `b_x4/dqt/horizontal/b_x4_DQT_1080w_bit00024.jpg` |
| 3 | 0,2 | horizontal bands 2.0px | 2 | 3 0* 6 10 18 34 66 130 | 20.7 | horizontal square | `b_x4/dqt/horizontal square/b_x4_DQT_1080w_bit00032.jpg` |
| 4 | 1,1 | checkerboard 4.0px cells | 2 | 3 0* 6 10 18 34 66 130 | 19.5 | square x | `b_x4/dqt/square x/b_x4_DQT_1080w_bit00040.jpg` |
| 5 | 2,0 | vertical bands 2.0px | 2 | 3 0* 6 10 18 34 66 130 | 19.1 | vertical square | `b_x4/dqt/vertical square/b_x4_DQT_1080w_bit00048.jpg` |
| 6 | 3,0 | vertical bands 1.3px | 3 | 2 1 7 11 19 35 67 131 | 10.1 | vertical square 2 | `b_x4/dqt/vertical square 2/b_x4_DQT_1080w_bit00056.jpg` |
| 7 | 2,1 | plaid 2.0pxx4.0px cells | 2 | 3 0* 6 10 18 34 66 130 | 14.8 | square x 2 | `b_x4/dqt/square x 2/b_x4_DQT_1080w_bit00064.jpg` |
| 8 | 1,2 | plaid 4.0pxx2.0px cells | 2 | 3 0* 6 10 18 34 66 130 | 15.0 | square x 3 | `b_x4/dqt/square x 3/b_x4_DQT_1080w_bit00072.jpg` |
| 9 | 0,3 | horizontal bands 1.3px | 2 | 3 0* 6 10 18 34 66 130 | 16.1 | horizontal square 2 | `b_x4/dqt/horizontal square 2/b_x4_DQT_1080w_bit00080.jpg` |
| 10 | 0,4 | horizontal bands 1.0px | 3 | 2 1 7 11 19 35 67 131 | 8.8 | horizontal square 3 | `b_x4/dqt/horizontal square 3/b_x4_DQT_1080w_bit00088.jpg` |
| 11 | 1,3 | plaid 4.0pxx1.3px cells | 3 | 2 1 7 11 19 35 67 131 | 7.8 | square x 4 | `b_x4/dqt/square x 4/b_x4_DQT_1080w_bit00096.jpg` |
| 12 | 2,2 | checkerboard 2.0px cells | 3 | 2 1 7 11 19 35 67 131 | 8.6 | square x 5 | `b_x4/dqt/square x 5/b_x4_DQT_1080w_bit00104.jpg` |
| 13 | 3,1 | plaid 1.3pxx4.0px cells | 3 | 2 1 7 11 19 35 67 131 | 7.4 | vertical square 3 | `b_x4/dqt/vertical square 3/b_x4_DQT_1080w_bit00112.jpg` |
| 14 | 4,0 | vertical bands 1.0px | 4 | 5 6 0* 12 20 36 68 132 | 5.5 | vertical square 4 | `b_x4/dqt/vertical square 4/b_x4_DQT_1080w_bit00120.jpg` |
| 15 | 5,0 | vertical bands 0.80px | 6 | 7 4 2 14 22 38 70 134 | 1.8 | vertical square 5 | `b_x4/dqt/vertical square 5/b_x4_DQT_1080w_bit00128.jpg` |
| 16 | 4,1 | plaid 1.0pxx4.0px cells | 4 | 5 6 0* 12 20 36 68 132 | 4.3 | square x 6 | `b_x4/dqt/square x 6/b_x4_DQT_1080w_bit00136.jpg` |
| 17 | 3,2 | plaid 1.3pxx2.0px cells | 4 | 5 6 0* 12 20 36 68 132 | 4.6 | square x 7 | `b_x4/dqt/square x 7/b_x4_DQT_1080w_bit00144.jpg` |
| 18 | 2,3 | plaid 2.0pxx1.3px cells | 4 | 5 6 0* 12 20 36 68 132 | 4.7 | square x 8 | `b_x4/dqt/square x 8/b_x4_DQT_1080w_bit00152.jpg` |
| 19 | 1,4 | plaid 4.0pxx1.0px cells | 4 | 5 6 0* 12 20 36 68 132 | 4.7 | square x 9 | `b_x4/dqt/square x 9/b_x4_DQT_1080w_bit00160.jpg` |
| 20 | 0,5 | horizontal bands 0.80px | 4 | 5 6 0* 12 20 36 68 132 | 4.2 | horizontal square 4 | `b_x4/dqt/horizontal square 4/b_x4_DQT_1080w_bit00168.jpg` |
| 21 | 0,6 | horizontal bands 0.67px | 8 | 9 10 12 0* 24 40 72 136 | 0.7 | horizontal square 5 | `b_x4/dqt/horizontal square 5/b_x4_DQT_1080w_bit00176.jpg` |
| 22 | 1,5 | plaid 4.0pxx0.80px cells | 6 | 7 4 2 14 22 38 70 134 | 1.5 | square x 10 | `b_x4/dqt/square x 10/b_x4_DQT_1080w_bit00184.jpg` |
| 23 | 2,4 | plaid 2.0pxx1.0px cells | 6 | 7 4 2 14 22 38 70 134 | 2.1 | square x 11 | `b_x4/dqt/square x 11/b_x4_DQT_1080w_bit00192.jpg` |
| 24 | 3,3 | checkerboard 1.3px cells | 5 | 4 7 1 13 21 37 69 133 | 2.6 | square x 12 | `b_x4/dqt/square x 12/b_x4_DQT_1080w_bit00200.jpg` |
| 25 | 4,2 | plaid 1.0pxx2.0px cells | 6 | 7 4 2 14 22 38 70 134 | 2.0 | square x 13 | `b_x4/dqt/square x 13/b_x4_DQT_1080w_bit00208.jpg` |
| 26 | 5,1 | plaid 0.80pxx4.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.7 | square x 14 | `b_x4/dqt/square x 14/b_x4_DQT_1080w_bit00216.jpg` |
| 27 | 6,0 | vertical bands 0.67px | 8 | 9 10 12 0* 24 40 72 136 | 0.7 | vertical square 6 | `b_x4/dqt/vertical square 6/b_x4_DQT_1080w_bit00224.jpg` |
| 28 | 7,0 | vertical bands 0.57px | 10 | 11 8 14 2 26 42 74 138 | 0.3 | vertical square 7 | `b_x4/dqt/vertical square 7/b_x4_DQT_1080w_bit00232.jpg` |
| 29 | 6,1 | plaid 0.67pxx4.0px cells | 10 | 11 8 14 2 26 42 74 138 | 0.4 | square x 15 | `b_x4/dqt/square x 15/b_x4_DQT_1080w_bit00240.jpg` |
| 30 | 5,2 | plaid 0.80pxx2.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.7 | square x 16 | `b_x4/dqt/square x 16/b_x4_DQT_1080w_bit00248.jpg` |
| 31 | 4,3 | plaid 1.0pxx1.3px cells | 8 | 9 10 12 0* 24 40 72 136 | 1.0 | square x 17 | `b_x4/dqt/square x 17/b_x4_DQT_1080w_bit00256.jpg` |
| 32 | 3,4 | plaid 1.3pxx1.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.9 | square x 18 | `b_x4/dqt/square x 18/b_x4_DQT_1080w_bit00264.jpg` |
| 33 | 2,5 | plaid 2.0pxx0.80px cells | 9 | 8 11 13 1 25 41 73 137 | 0.7 | square x 19 | `b_x4/dqt/square x 19/b_x4_DQT_1080w_bit00272.jpg` |
| 34 | 1,6 | plaid 4.0pxx0.67px cells | 10 | 11 8 14 2 26 42 74 138 | 0.5 | horizontal square 6 | `b_x4/dqt/horizontal square 6/b_x4_DQT_1080w_bit00280.jpg` |
| 35 | 0,7 | horizontal bands 0.57px | 12 | 13 14 8 4 28 44 76 140 | no effect | horizontal square 7 | `b_x4/dqt/horizontal square 7/b_x4_DQT_1080w_bit00288.jpg` |
| 36 | 1,7 | plaid 4.0pxx0.57px cells | 15 | 14 13 11 7 31 47 79 143 | no effect | horizontal square 8 | `b_x4/dqt/horizontal square 8/b_x4_DQT_1080w_bit00296.jpg` |
| 37 | 2,6 | plaid 2.0pxx0.67px cells | 12 | 13 14 8 4 28 44 76 140 | 0.3 | square x 20 | `b_x4/dqt/square x 20/b_x4_DQT_1080w_bit00304.jpg` |
| 38 | 3,5 | plaid 1.3pxx0.80px cells | 10 | 11 8 14 2 26 42 74 138 | 0.5 | square x 21 | `b_x4/dqt/square x 21/b_x4_DQT_1080w_bit00312.jpg` |
| 39 | 4,4 | checkerboard 1.0px cells | 11 | 10 9 15 3 27 43 75 139 | 0.5 | square x 22 | `b_x4/dqt/square x 22/b_x4_DQT_1080w_bit00320.jpg` |
| 40 | 5,3 | plaid 0.80pxx1.3px cells | 14 | 15 12 10 6 30 46 78 142 | 0.4 | square x 23 | `b_x4/dqt/square x 23/b_x4_DQT_1080w_bit00328.jpg` |
| 41 | 6,2 | plaid 0.67pxx2.0px cells | 11 | 10 9 15 3 27 43 75 139 | 0.4 | square x 24 | `b_x4/dqt/square x 24/b_x4_DQT_1080w_bit00336.jpg` |
| 42 | 7,1 | plaid 0.57pxx4.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.3 | vertical square 8 | `b_x4/dqt/vertical square 8/b_x4_DQT_1080w_bit00344.jpg` |
| 43 | 7,2 | plaid 0.57pxx2.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.3 | square x 25 | `b_x4/dqt/square x 25/b_x4_DQT_1080w_bit00352.jpg` |
| 44 | 6,3 | plaid 0.67pxx1.3px cells | 13 | 12 15 9 5 29 45 77 141 | no effect | square x 26 | `b_x4/dqt/square x 26/b_x4_DQT_1080w_bit00360.jpg` |
| 45 | 5,4 | plaid 0.80pxx1.0px cells | 17 | 16 19 21 25 1 49 81 145 | no effect | square x 27 | `b_x4/dqt/square x 27/b_x4_DQT_1080w_bit00368.jpg` |
| 46 | 4,5 | plaid 1.0pxx0.80px cells | 13 | 12 15 9 5 29 45 77 141 | 0.3 | square x 28 | `b_x4/dqt/square x 28/b_x4_DQT_1080w_bit00376.jpg` |
| 47 | 3,6 | plaid 1.3pxx0.67px cells | 14 | 15 12 10 6 30 46 78 142 | no effect | square x 29 | `b_x4/dqt/square x 29/b_x4_DQT_1080w_bit00384.jpg` |
| 48 | 2,7 | plaid 2.0pxx0.57px cells | 15 | 14 13 11 7 31 47 79 143 | no effect | square x 30 | `b_x4/dqt/square x 30/b_x4_DQT_1080w_bit00392.jpg` |
| 49 | 3,7 | plaid 1.3pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 31 | `b_x4/dqt/square x 31/b_x4_DQT_1080w_bit00400.jpg` |
| 50 | 4,6 | plaid 1.0pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 32 | `b_x4/dqt/square x 32/b_x4_DQT_1080w_bit00408.jpg` |
| 51 | 5,5 | checkerboard 0.80px cells | 17 | 16 19 21 25 1 49 81 145 | no effect | square x 33 | `b_x4/dqt/square x 33/b_x4_DQT_1080w_bit00416.jpg` |
| 52 | 6,4 | plaid 0.67pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 34 | `b_x4/dqt/square x 34/b_x4_DQT_1080w_bit00424.jpg` |
| 53 | 7,3 | plaid 0.57pxx1.3px cells | 10 | 11 8 14 2 26 42 74 138 | no effect | square x 35 | `b_x4/dqt/square x 35/b_x4_DQT_1080w_bit00432.jpg` |
| 54 | 7,4 | plaid 0.57pxx1.0px cells | 12 | 13 14 8 4 28 44 76 140 | no effect | square x 36 | `b_x4/dqt/square x 36/b_x4_DQT_1080w_bit00440.jpg` |
| 55 | 6,5 | plaid 0.67pxx0.80px cells | 18 | 19 16 22 26 2 50 82 146 | no effect | square x 37 | `b_x4/dqt/square x 37/b_x4_DQT_1080w_bit00448.jpg` |
| 56 | 5,6 | plaid 0.80pxx0.67px cells | 19 | 18 17 23 27 3 51 83 147 | no effect | square x 38 | `b_x4/dqt/square x 38/b_x4_DQT_1080w_bit00456.jpg` |
| 57 | 4,7 | plaid 1.0pxx0.57px cells | 18 | 19 16 22 26 2 50 82 146 | no effect | square x 39 | `b_x4/dqt/square x 39/b_x4_DQT_1080w_bit00464.jpg` |
| 58 | 5,7 | plaid 0.80pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 40 | `b_x4/dqt/square x 40/b_x4_DQT_1080w_bit00472.jpg` |
| 59 | 6,6 | checkerboard 0.67px cells | 19 | 18 17 23 27 3 51 83 147 | no effect | square x 41 | `b_x4/dqt/square x 41/b_x4_DQT_1080w_bit00480.jpg` |
| 60 | 7,5 | plaid 0.57pxx0.80px cells | 15 | 14 13 11 7 31 47 79 143 | no effect | square x 42 | `b_x4/dqt/square x 42/b_x4_DQT_1080w_bit00488.jpg` |
| 61 | 7,6 | plaid 0.57pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 43 | `b_x4/dqt/square x 43/b_x4_DQT_1080w_bit00496.jpg` |
| 62 | 6,7 | plaid 0.67pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 44 | `b_x4/dqt/square x 44/b_x4_DQT_1080w_bit00504.jpg` |
| 63 | 7,7 | checkerboard 0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x 45 | `b_x4/dqt/square x 45/b_x4_DQT_1080w_bit00512.jpg` |

### table 1 - chroma quantizers (bits 528-1039)

| coeff | u,v | pattern | q | bits 0-7 become | visible | folder | sample |
|---|---|---|---|---|---|---|---|
| 0 | 0,0 | flat (DC) | 3 | 2 1 7 11 19 35 67 131 | 91.4 | lower saturation | `b_x4/dqt/lower saturation/b_x4_DQT_1080w_bit00528.jpg` |
| 1 | 1,0 | vertical bands 4.0px | 3 | 2 1 7 11 19 35 67 131 | 9.5 | vertical color | `b_x4/dqt/vertical color/b_x4_DQT_1080w_bit00536.jpg` |
| 2 | 0,1 | horizontal bands 4.0px | 3 | 2 1 7 11 19 35 67 131 | 9.6 | horizontal color | `b_x4/dqt/horizontal color/b_x4_DQT_1080w_bit00544.jpg` |
| 3 | 0,2 | horizontal bands 2.0px | 4 | 5 6 0* 12 20 36 68 132 | 2.3 | horizontal color 2 | `b_x4/dqt/horizontal color 2/b_x4_DQT_1080w_bit00552.jpg` |
| 4 | 1,1 | checkerboard 4.0px cells | 3 | 2 1 7 11 19 35 67 131 | 4.4 | square x color | `b_x4/dqt/square x color/b_x4_DQT_1080w_bit00560.jpg` |
| 5 | 2,0 | vertical bands 2.0px | 4 | 5 6 0* 12 20 36 68 132 | 2.3 | vertical color 2 | `b_x4/dqt/vertical color 2/b_x4_DQT_1080w_bit00568.jpg` |
| 6 | 3,0 | vertical bands 1.3px | 8 | 9 10 12 0* 24 40 72 136 | 0.4 | vertical color 3 | `b_x4/dqt/vertical color 3/b_x4_DQT_1080w_bit00576.jpg` |
| 7 | 2,1 | plaid 2.0pxx4.0px cells | 4 | 5 6 0* 12 20 36 68 132 | 1.4 | square x color 2 | `b_x4/dqt/square x color 2/b_x4_DQT_1080w_bit00584.jpg` |
| 8 | 1,2 | plaid 4.0pxx2.0px cells | 4 | 5 6 0* 12 20 36 68 132 | 1.4 | square x color 3 | `b_x4/dqt/square x color 3/b_x4_DQT_1080w_bit00592.jpg` |
| 9 | 0,3 | horizontal bands 1.3px | 8 | 9 10 12 0* 24 40 72 136 | 0.4 | horizontal color 3 | `b_x4/dqt/horizontal color 3/b_x4_DQT_1080w_bit00600.jpg` |
| 10 | 0,4 | horizontal bands 1.0px | 16 | 17 18 20 24 0* 48 80 144 | no effect | vertical color 4 | `b_x4/dqt/vertical color 4/b_x4_DQT_1080w_bit00608.jpg` |
| 11 | 1,3 | plaid 4.0pxx1.3px cells | 11 | 10 9 15 3 27 43 75 139 | no effect | square x color 4 | `b_x4/dqt/square x color 4/b_x4_DQT_1080w_bit00616.jpg` |
| 12 | 2,2 | checkerboard 2.0px cells | 9 | 8 11 13 1 25 41 73 137 | 0.3 | square x color 5 | `b_x4/dqt/square x color 5/b_x4_DQT_1080w_bit00624.jpg` |
| 13 | 3,1 | plaid 1.3pxx4.0px cells | 11 | 10 9 15 3 27 43 75 139 | no effect | square x color 6 | `b_x4/dqt/square x color 6/b_x4_DQT_1080w_bit00632.jpg` |
| 14 | 4,0 | vertical bands 1.0px | 16 | 17 18 20 24 0* 48 80 144 | no effect | vertical color 5 | `b_x4/dqt/vertical color 5/b_x4_DQT_1080w_bit00640.jpg` |
| 15 | 5,0 | vertical bands 0.80px | 16 | 17 18 20 24 0* 48 80 144 | no effect | vertical color 6 | `b_x4/dqt/vertical color 6/b_x4_DQT_1080w_bit00648.jpg` |
| 16 | 4,1 | plaid 1.0pxx4.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x color 7 | `b_x4/dqt/square x color 7/b_x4_DQT_1080w_bit00656.jpg` |
| 17 | 3,2 | plaid 1.3pxx2.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x color 8 | `b_x4/dqt/square x color 8/b_x4_DQT_1080w_bit00664.jpg` |
| 18 | 2,3 | plaid 2.0pxx1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x color 9 | `b_x4/dqt/square x color 9/b_x4_DQT_1080w_bit00672.jpg` |
| 19 | 1,4 | plaid 4.0pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | square x color 10 | `b_x4/dqt/square x color 10/b_x4_DQT_1080w_bit00680.jpg` |
| 20 | 0,5 | horizontal bands 0.80px | 16 | 17 18 20 24 0* 48 80 144 | no effect | horizontal color 4 | `b_x4/dqt/horizontal color 4/b_x4_DQT_1080w_bit00688.jpg` |
| 21 | 0,6 | horizontal bands 0.67px | 16 | 17 18 20 24 0* 48 80 144 | no effect | horizontal color 5 | `b_x4/dqt/horizontal color 5/b_x4_DQT_1080w_bit00696.jpg` |
| 22 | 1,5 | plaid 4.0pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00704.jpg` |
| 23 | 2,4 | plaid 2.0pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00712.jpg` |
| 24 | 3,3 | checkerboard 1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00720.jpg` |
| 25 | 4,2 | plaid 1.0pxx2.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00728.jpg` |
| 26 | 5,1 | plaid 0.80pxx4.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00736.jpg` |
| 27 | 6,0 | vertical bands 0.67px | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00744.jpg` |
| 28 | 7,0 | vertical bands 0.57px | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00752.jpg` |
| 29 | 6,1 | plaid 0.67pxx4.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00760.jpg` |
| 30 | 5,2 | plaid 0.80pxx2.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00768.jpg` |
| 31 | 4,3 | plaid 1.0pxx1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00776.jpg` |
| 32 | 3,4 | plaid 1.3pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00784.jpg` |
| 33 | 2,5 | plaid 2.0pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00792.jpg` |
| 34 | 1,6 | plaid 4.0pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00800.jpg` |
| 35 | 0,7 | horizontal bands 0.57px | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00808.jpg` |
| 36 | 1,7 | plaid 4.0pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00816.jpg` |
| 37 | 2,6 | plaid 2.0pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00824.jpg` |
| 38 | 3,5 | plaid 1.3pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00832.jpg` |
| 39 | 4,4 | checkerboard 1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00840.jpg` |
| 40 | 5,3 | plaid 0.80pxx1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00848.jpg` |
| 41 | 6,2 | plaid 0.67pxx2.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00856.jpg` |
| 42 | 7,1 | plaid 0.57pxx4.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00864.jpg` |
| 43 | 7,2 | plaid 0.57pxx2.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00872.jpg` |
| 44 | 6,3 | plaid 0.67pxx1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00880.jpg` |
| 45 | 5,4 | plaid 0.80pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00888.jpg` |
| 46 | 4,5 | plaid 1.0pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00896.jpg` |
| 47 | 3,6 | plaid 1.3pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00904.jpg` |
| 48 | 2,7 | plaid 2.0pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00912.jpg` |
| 49 | 3,7 | plaid 1.3pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00920.jpg` |
| 50 | 4,6 | plaid 1.0pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00928.jpg` |
| 51 | 5,5 | checkerboard 0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00936.jpg` |
| 52 | 6,4 | plaid 0.67pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00944.jpg` |
| 53 | 7,3 | plaid 0.57pxx1.3px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00952.jpg` |
| 54 | 7,4 | plaid 0.57pxx1.0px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00960.jpg` |
| 55 | 6,5 | plaid 0.67pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00968.jpg` |
| 56 | 5,6 | plaid 0.80pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00976.jpg` |
| 57 | 4,7 | plaid 1.0pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00984.jpg` |
| 58 | 5,7 | plaid 0.80pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit00992.jpg` |
| 59 | 6,6 | checkerboard 0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit01000.jpg` |
| 60 | 7,5 | plaid 0.57pxx0.80px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit01008.jpg` |
| 61 | 7,6 | plaid 0.57pxx0.67px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit01016.jpg` |
| 62 | 6,7 | plaid 0.67pxx0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit01024.jpg` |
| 63 | 7,7 | checkerboard 0.57px cells | 16 | 17 18 20 24 0* 48 80 144 | no effect | rest of bits not yet categorized | `b_x4/dqt/rest of bits not yet categorized/b_x4_DQT_1080w_bit01032.jpg` |

## 6. Why most bits look like nothing (and how to fix that)

Measured against the unflipped composite, **763 of the 1024 bits**
change the picture by less than the re-encode noise floor. They are not
broken - their coefficient is simply unused by these photos:

* the four sources are already JPEG-compressed photos, so at 1080 px the
  high-frequency coefficients are quantized to zero in every block. A
  quantizer that multiplies zeros cannot change anything, however large the
  flip is.
* the whole `rest of bits not yet categorized` folder (336 files) is exactly this:
  every bit in it measures at the floor, which is why they could not be
  sorted into an effect family.
* 74 of the 128 coefficients are inert at every bit weight.

Proof that this is about the image and not the measurement: the same bits
were flipped in a noise image re-encoded with the identical pipeline. Mean
pixel change, photos versus noise:

| bit | coefficient | on the photos | on a noise source |
|---|---|---|---|
| 519 | luma 63 (7,7) bit 7 | 0.25 (nothing) | 28.2 |
| 207 | luma 24 (3,3) bit 7 | 2.6 | 58.9 |
| 183 | luma 21 (0,6) bit 7 | 0.7 | 54.2 |
| 727 | chroma 24 (3,3) bit 7 | 0.25 (nothing) | 13.9 |
| 615 | chroma 10 (0,4) bit 7 | 0.25 (nothing) | 17.4 |
| 1039 | chroma 63 (7,7) bit 7 | 0.25 (nothing) | 5.3 |

For the UI this means: a DQT bit's effect is *content dependent*, so the
same bit can be dramatic on one image and invisible on another. Two honest
options:

1. **Per-image availability** - when the user loads an image, measure
   whether each coefficient has any energy (decode, take an 8x8 DCT of each
   block, check whether the quantized coefficient is ever non-zero) and grey
   out the bits that cannot do anything. This is the most truthful UI.
2. **A showcase image** - pick a source with broad-band detail (noise,
   film grain, or a PNG-heavy image) so every coefficient is live, and the
   sample set then covers all 1024 usable bits visibly.

The existing samples remain useful either way: they document what the
flipped bit does to *these* photos, and they show the family (pattern)
correctly whenever the coefficient is live.

## 7. Appendix - every usable bit

All 1024 files in the batch, in bit order. `dir` is raise (`coarser`),
lower (`finer`) or `zero`; `family` is the canonical name from section 4;
`strength` is the measured mean pixel change on this batch.

| bit | byte | table | coeff | u,v | pattern | k | q -> q' | dir | family | folder | strength |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 8 | 1 | 0 | 0 | 0,0 | flat (DC) | 0 | 3 -> 2 | finer | brightness | lower saturation | 14.56 |
| 9 | 1 | 0 | 0 | 0,0 | flat (DC) | 1 | 3 -> 1 | finer | brightness | lower saturation | 29.11 |
| 10 | 1 | 0 | 0 | 0,0 | flat (DC) | 2 | 3 -> 7 | coarser | brightness | darken | 41.46 |
| 11 | 1 | 0 | 0 | 0,0 | flat (DC) | 3 | 3 -> 11 | coarser | brightness | darken | 56.78 |
| 12 | 1 | 0 | 0 | 0,0 | flat (DC) | 4 | 3 -> 19 | coarser | brightness | darken | 68.10 |
| 13 | 1 | 0 | 0 | 0,0 | flat (DC) | 5 | 3 -> 35 | coarser | brightness | heavy | 76.74 |
| 14 | 1 | 0 | 0 | 0,0 | flat (DC) | 6 | 3 -> 67 | coarser | brightness | heavy | 91.53 |
| 15 | 1 | 0 | 0 | 0,0 | flat (DC) | 7 | 3 -> 131 | coarser | brightness | heavy | 97.39 |
| 16 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 0 | 2 -> 3 | coarser | vertical grating | vertical | 0.87 |
| 17 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 1 | 2 -> 0 | zero | vertical grating | vertical | 1.61 |
| 18 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 2 | 2 -> 6 | coarser | vertical grating | vertical | 3.00 |
| 19 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 3 | 2 -> 10 | coarser | vertical grating | vertical | 5.50 |
| 20 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 4 | 2 -> 18 | coarser | vertical grating | vertical | 9.63 |
| 21 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 5 | 2 -> 34 | coarser | vertical grating | vertical | 15.91 |
| 22 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 6 | 2 -> 66 | coarser | vertical grating | vertical | 24.79 |
| 23 | 2 | 0 | 1 | 1,0 | vertical bands 4.0px | 7 | 2 -> 130 | coarser | vertical grating | vertical | 36.38 |
| 24 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 0 | 2 -> 3 | coarser | horizontal grating | horizontal | 0.86 |
| 25 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 1 | 2 -> 0 | zero | horizontal grating | horizontal | 1.59 |
| 26 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 2 | 2 -> 6 | coarser | horizontal grating | horizontal | 2.99 |
| 27 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 3 | 2 -> 10 | coarser | horizontal grating | horizontal | 5.56 |
| 28 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 4 | 2 -> 18 | coarser | horizontal grating | horizontal | 9.85 |
| 29 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 5 | 2 -> 34 | coarser | horizontal grating | horizontal | 16.37 |
| 30 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 6 | 2 -> 66 | coarser | horizontal grating | horizontal | 25.59 |
| 31 | 3 | 0 | 2 | 0,1 | horizontal bands 4.0px | 7 | 2 -> 130 | coarser | horizontal grating | horizontal | 37.72 |
| 32 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 0 | 2 -> 3 | coarser | horizontal grating | horizontal square | 0.41 |
| 33 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 1 | 2 -> 0 | zero | horizontal grating | horizontal square | 0.63 |
| 34 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 2 | 2 -> 6 | coarser | horizontal grating | horizontal square | 1.10 |
| 35 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 3 | 2 -> 10 | coarser | horizontal grating | horizontal square | 2.05 |
| 36 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 4 | 2 -> 18 | coarser | horizontal grating | horizontal square | 3.86 |
| 37 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 5 | 2 -> 34 | coarser | horizontal grating | horizontal square | 7.06 |
| 38 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 6 | 2 -> 66 | coarser | horizontal grating | horizontal square | 12.35 |
| 39 | 4 | 0 | 3 | 0,2 | horizontal bands 2.0px | 7 | 2 -> 130 | coarser | horizontal grating | horizontal square | 20.69 |
| 40 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 0 | 2 -> 3 | coarser | checkered | square x | 0.44 |
| 41 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 1 | 2 -> 0 | zero | checkered | square x | 0.68 |
| 42 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 2 | 2 -> 6 | coarser | checkered | square x | 1.19 |
| 43 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 3 | 2 -> 10 | coarser | checkered | square x | 2.21 |
| 44 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 4 | 2 -> 18 | coarser | checkered | square x | 4.07 |
| 45 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 5 | 2 -> 34 | coarser | checkered | square x | 7.21 |
| 46 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 6 | 2 -> 66 | coarser | checkered | square x | 12.11 |
| 47 | 5 | 0 | 4 | 1,1 | checkerboard 4.0px cells | 7 | 2 -> 130 | coarser | checkered | square x | 19.49 |
| 48 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 0 | 2 -> 3 | coarser | vertical grating | vertical square | 0.40 |
| 49 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 1 | 2 -> 0 | zero | vertical grating | vertical square | 0.61 |
| 50 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 2 | 2 -> 6 | coarser | vertical grating | vertical square | 1.06 |
| 51 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 3 | 2 -> 10 | coarser | vertical grating | vertical square | 1.98 |
| 52 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 4 | 2 -> 18 | coarser | vertical grating | vertical square | 3.73 |
| 53 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 5 | 2 -> 34 | coarser | vertical grating | vertical square | 6.76 |
| 54 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 6 | 2 -> 66 | coarser | vertical grating | vertical square | 11.63 |
| 55 | 6 | 0 | 5 | 2,0 | vertical bands 2.0px | 7 | 2 -> 130 | coarser | vertical grating | vertical square | 19.10 |
| 56 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 0 | 3 -> 2 | finer | vertical grating | vertical square 2 | 0.29 |
| 57 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 1 | 3 -> 1 | finer | vertical grating | vertical square 2 | 0.36 |
| 58 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 2 | 3 -> 7 | coarser | vertical grating | vertical square 2 | 0.53 |
| 59 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 3 | 3 -> 11 | coarser | vertical grating | vertical square 2 | 0.88 |
| 60 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 4 | 3 -> 19 | coarser | vertical grating | vertical square 2 | 1.61 |
| 61 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 5 | 3 -> 35 | coarser | vertical grating | vertical square 2 | 3.04 |
| 62 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 6 | 3 -> 67 | coarser | vertical grating | vertical square 2 | 5.65 |
| 63 | 7 | 0 | 6 | 3,0 | vertical bands 1.3px | 7 | 3 -> 131 | coarser | vertical grating | vertical square 2 | 10.13 |
| 64 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 0 | 2 -> 3 | coarser | checkered | square x 2 | 0.34 |
| 65 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 1 | 2 -> 0 | zero | checkered | square x 2 | 0.47 |
| 66 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 2 | 2 -> 6 | coarser | checkered | square x 2 | 0.77 |
| 67 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 3 | 2 -> 10 | coarser | checkered | square x 2 | 1.38 |
| 68 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 4 | 2 -> 18 | coarser | checkered | square x 2 | 2.60 |
| 69 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 5 | 2 -> 34 | coarser | checkered | square x 2 | 4.83 |
| 70 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 6 | 2 -> 66 | coarser | checkered | square x 2 | 8.66 |
| 71 | 8 | 0 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 7 | 2 -> 130 | coarser | checkered | square x 2 | 14.79 |
| 72 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 0 | 2 -> 3 | coarser | checkered | square x 3 | 0.34 |
| 73 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 1 | 2 -> 0 | zero | checkered | square x 3 | 0.47 |
| 74 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 2 | 2 -> 6 | coarser | checkered | square x 3 | 0.76 |
| 75 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 3 | 2 -> 10 | coarser | checkered | square x 3 | 1.36 |
| 76 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 4 | 2 -> 18 | coarser | checkered | square x 3 | 2.55 |
| 77 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 5 | 2 -> 34 | coarser | checkered | square x 3 | 4.77 |
| 78 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 6 | 2 -> 66 | coarser | checkered | square x 3 | 8.64 |
| 79 | 9 | 0 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 7 | 2 -> 130 | coarser | checkered | square x 3 | 14.97 |
| 80 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 0 | 2 -> 3 | coarser | horizontal grating | horizontal square 2 | 0.34 |
| 81 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 1 | 2 -> 0 | zero | horizontal grating | horizontal square 2 | 0.46 |
| 82 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 2 | 2 -> 6 | coarser | horizontal grating | horizontal square 2 | 0.73 |
| 83 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 3 | 2 -> 10 | coarser | horizontal grating | horizontal square 2 | 1.32 |
| 84 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 4 | 2 -> 18 | coarser | horizontal grating | horizontal square 2 | 2.51 |
| 85 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 5 | 2 -> 34 | coarser | horizontal grating | horizontal square 2 | 4.79 |
| 86 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 6 | 2 -> 66 | coarser | horizontal grating | horizontal square 2 | 8.94 |
| 87 | 10 | 0 | 9 | 0,3 | horizontal bands 1.3px | 7 | 2 -> 130 | coarser | horizontal grating | horizontal square 2 | 16.11 |
| 88 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 0 | 3 -> 2 | finer | horizontal grating | horizontal square 3 | 0.28 |
| 89 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 1 | 3 -> 1 | finer | horizontal grating | horizontal square 3 | 0.33 |
| 90 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 2 | 3 -> 7 | coarser | horizontal grating | horizontal square 3 | 0.45 |
| 91 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 3 | 3 -> 11 | coarser | horizontal grating | horizontal square 3 | 0.72 |
| 92 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 4 | 3 -> 19 | coarser | horizontal grating | horizontal square 3 | 1.29 |
| 93 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 5 | 3 -> 35 | coarser | horizontal grating | horizontal square 3 | 2.45 |
| 94 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 6 | 3 -> 67 | coarser | horizontal grating | horizontal square 3 | 4.68 |
| 95 | 11 | 0 | 10 | 0,4 | horizontal bands 1.0px | 7 | 3 -> 131 | coarser | horizontal grating | horizontal square 3 | 8.81 |
| 96 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 0 | 3 -> 2 | finer | checkered | square x 4 | 0.28 |
| 97 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 1 | 3 -> 1 | finer | checkered | square x 4 | 0.32 |
| 98 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 2 | 3 -> 7 | coarser | checkered | square x 4 | 0.44 |
| 99 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 3 | 3 -> 11 | coarser | checkered | square x 4 | 0.69 |
| 100 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 4 | 3 -> 19 | coarser | checkered | square x 4 | 1.21 |
| 101 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 5 | 3 -> 35 | coarser | checkered | square x 4 | 2.26 |
| 102 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 6 | 3 -> 67 | coarser | checkered | square x 4 | 4.24 |
| 103 | 12 | 0 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 7 | 3 -> 131 | coarser | checkered | square x 4 | 7.80 |
| 104 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 0 | 3 -> 2 | finer | checkered | square x 5 | 0.28 |
| 105 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 1 | 3 -> 1 | finer | checkered | square x 5 | 0.34 |
| 106 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 2 | 3 -> 7 | coarser | checkered | square x 5 | 0.47 |
| 107 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 3 | 3 -> 11 | coarser | checkered | square x 5 | 0.76 |
| 108 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 4 | 3 -> 19 | coarser | checkered | square x 5 | 1.36 |
| 109 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 5 | 3 -> 35 | coarser | checkered | square x 5 | 2.54 |
| 110 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 6 | 3 -> 67 | coarser | checkered | square x 5 | 4.73 |
| 111 | 13 | 0 | 12 | 2,2 | checkerboard 2.0px cells | 7 | 3 -> 131 | coarser | checkered | square x 5 | 8.55 |
| 112 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 0 | 3 -> 2 | finer | checkered | vertical square 3 | 0.28 |
| 113 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 1 | 3 -> 1 | finer | checkered | vertical square 3 | 0.32 |
| 114 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 2 | 3 -> 7 | coarser | checkered | vertical square 3 | 0.43 |
| 115 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 3 | 3 -> 11 | coarser | checkered | vertical square 3 | 0.67 |
| 116 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 4 | 3 -> 19 | coarser | checkered | vertical square 3 | 1.18 |
| 117 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 5 | 3 -> 35 | coarser | checkered | vertical square 3 | 2.18 |
| 118 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 6 | 3 -> 67 | coarser | checkered | vertical square 3 | 4.07 |
| 119 | 14 | 0 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 7 | 3 -> 131 | coarser | checkered | vertical square 3 | 7.38 |
| 120 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 0 | 4 -> 5 | coarser | vertical grating | vertical square 4 | 0.27 |
| 121 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 1 | 4 -> 6 | coarser | vertical grating | vertical square 4 | 0.29 |
| 122 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 2 | 4 -> 0 | zero | vertical grating | vertical square 4 | 0.36 |
| 123 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 3 | 4 -> 12 | coarser | vertical grating | vertical square 4 | 0.52 |
| 124 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 4 | 4 -> 20 | coarser | vertical grating | vertical square 4 | 0.86 |
| 125 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 5 | 4 -> 36 | coarser | vertical grating | vertical square 4 | 1.57 |
| 126 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 6 | 4 -> 68 | coarser | vertical grating | vertical square 4 | 2.94 |
| 127 | 15 | 0 | 14 | 4,0 | vertical bands 1.0px | 7 | 4 -> 132 | coarser | vertical grating | vertical square 4 | 5.48 |
| 128 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 0 | 6 -> 7 | coarser | vertical grating | vertical square 5 | 0.26 |
| 129 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 1 | 6 -> 4 | finer | vertical grating | vertical square 5 | 0.26 |
| 130 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 2 | 6 -> 2 | finer | vertical grating | vertical square 5 | 0.27 |
| 131 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 3 | 6 -> 14 | coarser | vertical grating | vertical square 5 | 0.32 |
| 132 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 4 | 6 -> 22 | coarser | vertical grating | vertical square 5 | 0.42 |
| 133 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 5 | 6 -> 38 | coarser | vertical grating | vertical square 5 | 0.62 |
| 134 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 6 | 6 -> 70 | coarser | vertical grating | vertical square 5 | 1.03 |
| 135 | 16 | 0 | 15 | 5,0 | vertical bands 0.80px | 7 | 6 -> 134 | coarser | vertical grating | vertical square 5 | 1.84 |
| 136 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 0 | 4 -> 5 | coarser | checkered | square x 6 | 0.27 |
| 137 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 1 | 4 -> 6 | coarser | checkered | square x 6 | 0.28 |
| 138 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 2 | 4 -> 0 | zero | checkered | square x 6 | 0.33 |
| 139 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 3 | 4 -> 12 | coarser | checkered | square x 6 | 0.45 |
| 140 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 4 | 4 -> 20 | coarser | checkered | square x 6 | 0.73 |
| 141 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 5 | 4 -> 36 | coarser | checkered | square x 6 | 1.25 |
| 142 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 6 | 4 -> 68 | coarser | checkered | square x 6 | 2.34 |
| 143 | 17 | 0 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 7 | 4 -> 132 | coarser | checkered | square x 6 | 4.35 |
| 144 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 0 | 4 -> 5 | coarser | checkered | square x 7 | 0.27 |
| 145 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 1 | 4 -> 6 | coarser | checkered | square x 7 | 0.29 |
| 146 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 2 | 4 -> 0 | zero | checkered | square x 7 | 0.34 |
| 147 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 3 | 4 -> 12 | coarser | checkered | square x 7 | 0.47 |
| 148 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 4 | 4 -> 20 | coarser | checkered | square x 7 | 0.76 |
| 149 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 5 | 4 -> 36 | coarser | checkered | square x 7 | 1.33 |
| 150 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 6 | 4 -> 68 | coarser | checkered | square x 7 | 2.47 |
| 151 | 18 | 0 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 7 | 4 -> 132 | coarser | checkered | square x 7 | 4.57 |
| 152 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 0 | 4 -> 5 | coarser | checkered | square x 8 | 0.27 |
| 153 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 1 | 4 -> 6 | coarser | checkered | square x 8 | 0.29 |
| 154 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 2 | 4 -> 0 | zero | checkered | square x 8 | 0.35 |
| 155 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 3 | 4 -> 12 | coarser | checkered | square x 8 | 0.48 |
| 156 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 4 | 4 -> 20 | coarser | checkered | square x 8 | 0.77 |
| 157 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 5 | 4 -> 36 | coarser | checkered | square x 8 | 1.37 |
| 158 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 6 | 4 -> 68 | coarser | checkered | square x 8 | 2.54 |
| 159 | 19 | 0 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 7 | 4 -> 132 | coarser | checkered | square x 8 | 4.73 |
| 160 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 0 | 4 -> 5 | coarser | checkered | square x 9 | 0.27 |
| 161 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 1 | 4 -> 6 | coarser | checkered | square x 9 | 0.29 |
| 162 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 2 | 4 -> 0 | zero | checkered | square x 9 | 0.34 |
| 163 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 3 | 4 -> 12 | coarser | checkered | square x 9 | 0.47 |
| 164 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 4 | 4 -> 20 | coarser | checkered | square x 9 | 0.76 |
| 165 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 5 | 4 -> 36 | coarser | checkered | square x 9 | 1.35 |
| 166 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 6 | 4 -> 68 | coarser | checkered | square x 9 | 2.53 |
| 167 | 20 | 0 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 7 | 4 -> 132 | coarser | checkered | square x 9 | 4.74 |
| 168 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 0 | 4 -> 5 | coarser | horizontal grating | horizontal square 4 | 0.27 |
| 169 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 1 | 4 -> 6 | coarser | horizontal grating | horizontal square 4 | 0.28 |
| 170 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 2 | 4 -> 0 | zero | horizontal grating | horizontal square 4 | 0.33 |
| 171 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 3 | 4 -> 12 | coarser | horizontal grating | horizontal square 4 | 0.44 |
| 172 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 4 | 4 -> 20 | coarser | horizontal grating | horizontal square 4 | 0.69 |
| 173 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 5 | 4 -> 36 | coarser | horizontal grating | horizontal square 4 | 1.20 |
| 174 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 6 | 4 -> 68 | coarser | horizontal grating | horizontal square 4 | 2.21 |
| 175 | 21 | 0 | 20 | 0,5 | horizontal bands 0.80px | 7 | 4 -> 132 | coarser | horizontal grating | horizontal square 4 | 4.18 |
| 176 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 0 | 8 -> 9 | coarser | horizontal grating | horizontal square 5 | 0.25 |
| 177 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 1 | 8 -> 10 | coarser | horizontal grating | horizontal square 5 | 0.25 |
| 178 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 2 | 8 -> 12 | coarser | horizontal grating | horizontal square 5 | 0.26 |
| 179 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 3 | 8 -> 0 | zero | horizontal grating | horizontal square 5 | 0.27 |
| 180 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 4 | 8 -> 24 | coarser | horizontal grating | horizontal square 5 | 0.30 |
| 181 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 5 | 8 -> 40 | coarser | horizontal grating | horizontal square 5 | 0.36 |
| 182 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 6 | 8 -> 72 | coarser | horizontal grating | horizontal square 5 | 0.47 |
| 183 | 22 | 0 | 21 | 0,6 | horizontal bands 0.67px | 7 | 8 -> 136 | coarser | horizontal grating | horizontal square 5 | 0.72 |
| 184 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 0 | 6 -> 7 | coarser | checkered | square x 10 | 0.25 |
| 185 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 1 | 6 -> 4 | finer | checkered | square x 10 | 0.26 |
| 186 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 2 | 6 -> 2 | finer | checkered | square x 10 | 0.27 |
| 187 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 3 | 6 -> 14 | coarser | checkered | square x 10 | 0.31 |
| 188 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 4 | 6 -> 22 | coarser | checkered | square x 10 | 0.39 |
| 189 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 5 | 6 -> 38 | coarser | checkered | square x 10 | 0.55 |
| 190 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 6 | 6 -> 70 | coarser | checkered | square x 10 | 0.89 |
| 191 | 23 | 0 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 7 | 6 -> 134 | coarser | checkered | square x 10 | 1.55 |
| 192 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 0 | 6 -> 7 | coarser | checkered | square x 11 | 0.26 |
| 193 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 1 | 6 -> 4 | finer | checkered | square x 11 | 0.26 |
| 194 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 2 | 6 -> 2 | finer | checkered | square x 11 | 0.28 |
| 195 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 3 | 6 -> 14 | coarser | checkered | square x 11 | 0.33 |
| 196 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 4 | 6 -> 22 | coarser | checkered | square x 11 | 0.45 |
| 197 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 5 | 6 -> 38 | coarser | checkered | square x 11 | 0.69 |
| 198 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 6 | 6 -> 70 | coarser | checkered | square x 11 | 1.19 |
| 199 | 24 | 0 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 7 | 6 -> 134 | coarser | checkered | square x 11 | 2.15 |
| 200 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 0 | 5 -> 4 | finer | checkered | square x 12 | 0.26 |
| 201 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 1 | 5 -> 7 | coarser | checkered | square x 12 | 0.26 |
| 202 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 2 | 5 -> 1 | finer | checkered | square x 12 | 0.30 |
| 203 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 3 | 5 -> 13 | coarser | checkered | square x 12 | 0.35 |
| 204 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 4 | 5 -> 21 | coarser | checkered | square x 12 | 0.51 |
| 205 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 5 | 5 -> 37 | coarser | checkered | square x 12 | 0.80 |
| 206 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 6 | 5 -> 69 | coarser | checkered | square x 12 | 1.42 |
| 207 | 25 | 0 | 24 | 3,3 | checkerboard 1.3px cells | 7 | 5 -> 133 | coarser | checkered | square x 12 | 2.59 |
| 208 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 0 | 6 -> 7 | coarser | checkered | square x 13 | 0.26 |
| 209 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 1 | 6 -> 4 | finer | checkered | square x 13 | 0.26 |
| 210 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 2 | 6 -> 2 | finer | checkered | square x 13 | 0.28 |
| 211 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 3 | 6 -> 14 | coarser | checkered | square x 13 | 0.33 |
| 212 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 4 | 6 -> 22 | coarser | checkered | square x 13 | 0.43 |
| 213 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 5 | 6 -> 38 | coarser | checkered | square x 13 | 0.66 |
| 214 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 6 | 6 -> 70 | coarser | checkered | square x 13 | 1.12 |
| 215 | 26 | 0 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 7 | 6 -> 134 | coarser | checkered | square x 13 | 2.00 |
| 216 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 0 | 9 -> 8 | finer | checkered | square x 14 | 0.25 |
| 217 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 1 | 9 -> 11 | coarser | checkered | square x 14 | 0.25 |
| 218 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 2 | 9 -> 13 | coarser | checkered | square x 14 | 0.25 |
| 219 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 3 | 9 -> 1 | finer | checkered | square x 14 | 0.27 |
| 220 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 4 | 9 -> 25 | coarser | checkered | square x 14 | 0.29 |
| 221 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 5 | 9 -> 41 | coarser | checkered | square x 14 | 0.36 |
| 222 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 6 | 9 -> 73 | coarser | checkered | square x 14 | 0.48 |
| 223 | 27 | 0 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 7 | 9 -> 137 | coarser | checkered | square x 14 | 0.73 |
| 224 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 0 | 8 -> 9 | coarser | vertical grating | vertical square 6 | 0.25 |
| 225 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 1 | 8 -> 10 | coarser | vertical grating | vertical square 6 | 0.25 |
| 226 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 2 | 8 -> 12 | coarser | vertical grating | vertical square 6 | 0.25 |
| 227 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 3 | 8 -> 0 | zero | vertical grating | vertical square 6 | 0.27 |
| 228 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 4 | 8 -> 24 | coarser | vertical grating | vertical square 6 | 0.29 |
| 229 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 5 | 8 -> 40 | coarser | vertical grating | vertical square 6 | 0.34 |
| 230 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 6 | 8 -> 72 | coarser | vertical grating | vertical square 6 | 0.45 |
| 231 | 28 | 0 | 27 | 6,0 | vertical bands 0.67px | 7 | 8 -> 136 | coarser | vertical grating | vertical square 6 | 0.66 |
| 232 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 0 | 10 -> 11 | coarser | vertical grating | vertical square 7 | 0.25 |
| 233 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 1 | 10 -> 8 | finer | vertical grating | vertical square 7 | 0.25 |
| 234 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 2 | 10 -> 14 | coarser | vertical grating | vertical square 7 | 0.25 |
| 235 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 3 | 10 -> 2 | finer | vertical grating | vertical square 7 | 0.25 |
| 236 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 4 | 10 -> 26 | coarser | vertical grating | vertical square 7 | 0.26 |
| 237 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 5 | 10 -> 42 | coarser | vertical grating | vertical square 7 | 0.27 |
| 238 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 6 | 10 -> 74 | coarser | vertical grating | vertical square 7 | 0.29 |
| 239 | 29 | 0 | 28 | 7,0 | vertical bands 0.57px | 7 | 10 -> 138 | coarser | vertical grating | vertical square 7 | 0.33 |
| 240 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 0 | 10 -> 11 | coarser | checkered | square x 15 | 0.25 |
| 241 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 1 | 10 -> 8 | finer | checkered | square x 15 | 0.25 |
| 242 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 2 | 10 -> 14 | coarser | checkered | square x 15 | 0.25 |
| 243 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 3 | 10 -> 2 | finer | checkered | square x 15 | 0.26 |
| 244 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 4 | 10 -> 26 | coarser | checkered | square x 15 | 0.26 |
| 245 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 5 | 10 -> 42 | coarser | checkered | square x 15 | 0.29 |
| 246 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 6 | 10 -> 74 | coarser | checkered | square x 15 | 0.33 |
| 247 | 30 | 0 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 7 | 10 -> 138 | coarser | checkered | square x 15 | 0.42 |
| 248 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 0 | 9 -> 8 | finer | checkered | square x 16 | 0.25 |
| 249 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 1 | 9 -> 11 | coarser | checkered | square x 16 | 0.25 |
| 250 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 2 | 9 -> 13 | coarser | checkered | square x 16 | 0.25 |
| 251 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 3 | 9 -> 1 | finer | checkered | square x 16 | 0.27 |
| 252 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 4 | 9 -> 25 | coarser | checkered | square x 16 | 0.28 |
| 253 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 5 | 9 -> 41 | coarser | checkered | square x 16 | 0.34 |
| 254 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 6 | 9 -> 73 | coarser | checkered | square x 16 | 0.45 |
| 255 | 31 | 0 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 7 | 9 -> 137 | coarser | checkered | square x 16 | 0.67 |
| 256 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 0 | 8 -> 9 | coarser | checkered | square x 17 | 0.25 |
| 257 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 1 | 8 -> 10 | coarser | checkered | square x 17 | 0.25 |
| 258 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 2 | 8 -> 12 | coarser | checkered | square x 17 | 0.26 |
| 259 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 3 | 8 -> 0 | zero | checkered | square x 17 | 0.28 |
| 260 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 4 | 8 -> 24 | coarser | checkered | square x 17 | 0.33 |
| 261 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 5 | 8 -> 40 | coarser | checkered | square x 17 | 0.42 |
| 262 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 6 | 8 -> 72 | coarser | checkered | square x 17 | 0.61 |
| 263 | 32 | 0 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 7 | 8 -> 136 | coarser | checkered | square x 17 | 1.01 |
| 264 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 0 | 9 -> 8 | finer | checkered | square x 18 | 0.25 |
| 265 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 1 | 9 -> 11 | coarser | checkered | square x 18 | 0.25 |
| 266 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 2 | 9 -> 13 | coarser | checkered | square x 18 | 0.25 |
| 267 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 3 | 9 -> 1 | finer | checkered | square x 18 | 0.27 |
| 268 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 4 | 9 -> 25 | coarser | checkered | square x 18 | 0.30 |
| 269 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 5 | 9 -> 41 | coarser | checkered | square x 18 | 0.39 |
| 270 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 6 | 9 -> 73 | coarser | checkered | square x 18 | 0.53 |
| 271 | 33 | 0 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 7 | 9 -> 137 | coarser | checkered | square x 18 | 0.85 |
| 272 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 0 | 9 -> 8 | finer | checkered | square x 19 | 0.25 |
| 273 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 1 | 9 -> 11 | coarser | checkered | square x 19 | 0.25 |
| 274 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 2 | 9 -> 13 | coarser | checkered | square x 19 | 0.25 |
| 275 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 3 | 9 -> 1 | finer | checkered | square x 19 | 0.27 |
| 276 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 4 | 9 -> 25 | coarser | checkered | square x 19 | 0.29 |
| 277 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 5 | 9 -> 41 | coarser | checkered | square x 19 | 0.35 |
| 278 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 6 | 9 -> 73 | coarser | checkered | square x 19 | 0.47 |
| 279 | 34 | 0 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 7 | 9 -> 137 | coarser | checkered | square x 19 | 0.71 |
| 280 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 0 | 10 -> 11 | coarser | checkered | horizontal square 6 | 0.25 |
| 281 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 1 | 10 -> 8 | finer | checkered | horizontal square 6 | 0.25 |
| 282 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 2 | 10 -> 14 | coarser | checkered | horizontal square 6 | 0.25 |
| 283 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 3 | 10 -> 2 | finer | checkered | horizontal square 6 | 0.26 |
| 284 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 4 | 10 -> 26 | coarser | checkered | horizontal square 6 | 0.27 |
| 285 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 5 | 10 -> 42 | coarser | checkered | horizontal square 6 | 0.29 |
| 286 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 6 | 10 -> 74 | coarser | checkered | horizontal square 6 | 0.34 |
| 287 | 35 | 0 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 7 | 10 -> 138 | coarser | checkered | horizontal square 6 | 0.45 |
| 288 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 0 | 12 -> 13 | coarser | horizontal grating | horizontal square 7 | 0.25 |
| 289 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 1 | 12 -> 14 | coarser | horizontal grating | horizontal square 7 | 0.25 |
| 290 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 2 | 12 -> 8 | finer | horizontal grating | horizontal square 7 | 0.25 |
| 291 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 3 | 12 -> 4 | finer | horizontal grating | horizontal square 7 | 0.25 |
| 292 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 4 | 12 -> 28 | coarser | horizontal grating | horizontal square 7 | 0.26 |
| 293 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 5 | 12 -> 44 | coarser | horizontal grating | horizontal square 7 | 0.26 |
| 294 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 6 | 12 -> 76 | coarser | horizontal grating | horizontal square 7 | 0.27 |
| 295 | 36 | 0 | 35 | 0,7 | horizontal bands 0.57px | 7 | 12 -> 140 | coarser | horizontal grating | horizontal square 7 | 0.30 |
| 296 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 0 | 15 -> 14 | finer | checkered | horizontal square 8 | 0.25 |
| 297 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 1 | 15 -> 13 | finer | checkered | horizontal square 8 | 0.25 |
| 298 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 2 | 15 -> 11 | finer | checkered | horizontal square 8 | 0.25 |
| 299 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 3 | 15 -> 7 | finer | checkered | horizontal square 8 | 0.25 |
| 300 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 4 | 15 -> 31 | coarser | checkered | horizontal square 8 | 0.25 |
| 301 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 5 | 15 -> 47 | coarser | checkered | horizontal square 8 | 0.25 |
| 302 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 6 | 15 -> 79 | coarser | checkered | horizontal square 8 | 0.26 |
| 303 | 37 | 0 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 7 | 15 -> 143 | coarser | checkered | horizontal square 8 | 0.27 |
| 304 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 0 | 12 -> 13 | coarser | checkered | square x 20 | 0.25 |
| 305 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 1 | 12 -> 14 | coarser | checkered | square x 20 | 0.25 |
| 306 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 2 | 12 -> 8 | finer | checkered | square x 20 | 0.25 |
| 307 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 3 | 12 -> 4 | finer | checkered | square x 20 | 0.25 |
| 308 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 4 | 12 -> 28 | coarser | checkered | square x 20 | 0.26 |
| 309 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 5 | 12 -> 44 | coarser | checkered | square x 20 | 0.27 |
| 310 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 6 | 12 -> 76 | coarser | checkered | square x 20 | 0.29 |
| 311 | 38 | 0 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 7 | 12 -> 140 | coarser | checkered | square x 20 | 0.34 |
| 312 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 0 | 10 -> 11 | coarser | checkered | square x 21 | 0.25 |
| 313 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 1 | 10 -> 8 | finer | checkered | square x 21 | 0.25 |
| 314 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 2 | 10 -> 14 | coarser | checkered | square x 21 | 0.25 |
| 315 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 3 | 10 -> 2 | finer | checkered | square x 21 | 0.26 |
| 316 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 4 | 10 -> 26 | coarser | checkered | square x 21 | 0.27 |
| 317 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 5 | 10 -> 42 | coarser | checkered | square x 21 | 0.30 |
| 318 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 6 | 10 -> 74 | coarser | checkered | square x 21 | 0.36 |
| 319 | 39 | 0 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 7 | 10 -> 138 | coarser | checkered | square x 21 | 0.49 |
| 320 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 0 | 11 -> 10 | finer | checkered | square x 22 | 0.25 |
| 321 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 1 | 11 -> 9 | finer | checkered | square x 22 | 0.25 |
| 322 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 2 | 11 -> 15 | coarser | checkered | square x 22 | 0.25 |
| 323 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 3 | 11 -> 3 | finer | checkered | square x 22 | 0.26 |
| 324 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 4 | 11 -> 27 | coarser | checkered | square x 22 | 0.27 |
| 325 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 5 | 11 -> 43 | coarser | checkered | square x 22 | 0.30 |
| 326 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 6 | 11 -> 75 | coarser | checkered | square x 22 | 0.37 |
| 327 | 40 | 0 | 39 | 4,4 | checkerboard 1.0px cells | 7 | 11 -> 139 | coarser | checkered | square x 22 | 0.50 |
| 328 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 0 | 14 -> 15 | coarser | checkered | square x 23 | 0.25 |
| 329 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 1 | 14 -> 12 | finer | checkered | square x 23 | 0.25 |
| 330 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 2 | 14 -> 10 | finer | checkered | square x 23 | 0.25 |
| 331 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 3 | 14 -> 6 | finer | checkered | square x 23 | 0.25 |
| 332 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 4 | 14 -> 30 | coarser | checkered | square x 23 | 0.26 |
| 333 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 5 | 14 -> 46 | coarser | checkered | square x 23 | 0.28 |
| 334 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 6 | 14 -> 78 | coarser | checkered | square x 23 | 0.31 |
| 335 | 41 | 0 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 7 | 14 -> 142 | coarser | checkered | square x 23 | 0.38 |
| 336 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 0 | 11 -> 10 | finer | checkered | square x 24 | 0.25 |
| 337 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 1 | 11 -> 9 | finer | checkered | square x 24 | 0.25 |
| 338 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 2 | 11 -> 15 | coarser | checkered | square x 24 | 0.25 |
| 339 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 3 | 11 -> 3 | finer | checkered | square x 24 | 0.26 |
| 340 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 4 | 11 -> 27 | coarser | checkered | square x 24 | 0.26 |
| 341 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 5 | 11 -> 43 | coarser | checkered | square x 24 | 0.27 |
| 342 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 6 | 11 -> 75 | coarser | checkered | square x 24 | 0.30 |
| 343 | 42 | 0 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 7 | 11 -> 139 | coarser | checkered | square x 24 | 0.36 |
| 344 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 0 | 9 -> 8 | finer | checkered | vertical square 8 | 0.25 |
| 345 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 1 | 9 -> 11 | coarser | checkered | vertical square 8 | 0.25 |
| 346 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 2 | 9 -> 13 | coarser | checkered | vertical square 8 | 0.25 |
| 347 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 3 | 9 -> 1 | finer | checkered | vertical square 8 | 0.25 |
| 348 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 4 | 9 -> 25 | coarser | checkered | vertical square 8 | 0.26 |
| 349 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 5 | 9 -> 41 | coarser | checkered | vertical square 8 | 0.27 |
| 350 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 6 | 9 -> 73 | coarser | checkered | vertical square 8 | 0.29 |
| 351 | 43 | 0 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 7 | 9 -> 137 | coarser | checkered | vertical square 8 | 0.33 |
| 352 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 0 | 9 -> 8 | finer | checkered | square x 25 | 0.25 |
| 353 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 1 | 9 -> 11 | coarser | checkered | square x 25 | 0.25 |
| 354 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 2 | 9 -> 13 | coarser | checkered | square x 25 | 0.25 |
| 355 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 3 | 9 -> 1 | finer | checkered | square x 25 | 0.25 |
| 356 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 4 | 9 -> 25 | coarser | checkered | square x 25 | 0.25 |
| 357 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 5 | 9 -> 41 | coarser | checkered | square x 25 | 0.26 |
| 358 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 6 | 9 -> 73 | coarser | checkered | square x 25 | 0.27 |
| 359 | 44 | 0 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 7 | 9 -> 137 | coarser | checkered | square x 25 | 0.30 |
| 360 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 0 | 13 -> 12 | finer | checkered | square x 26 | 0.25 |
| 361 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 1 | 13 -> 15 | coarser | checkered | square x 26 | 0.25 |
| 362 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 2 | 13 -> 9 | finer | checkered | square x 26 | 0.25 |
| 363 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 3 | 13 -> 5 | finer | checkered | square x 26 | 0.25 |
| 364 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 4 | 13 -> 29 | coarser | checkered | square x 26 | 0.26 |
| 365 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 5 | 13 -> 45 | coarser | checkered | square x 26 | 0.26 |
| 366 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 6 | 13 -> 77 | coarser | checkered | square x 26 | 0.27 |
| 367 | 45 | 0 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 7 | 13 -> 141 | coarser | checkered | square x 26 | 0.30 |
| 368 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 0 | 17 -> 16 | finer | checkered | square x 27 | 0.25 |
| 369 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 1 | 17 -> 19 | coarser | checkered | square x 27 | 0.25 |
| 370 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 2 | 17 -> 21 | coarser | checkered | square x 27 | 0.25 |
| 371 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 3 | 17 -> 25 | coarser | checkered | square x 27 | 0.25 |
| 372 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 4 | 17 -> 1 | finer | checkered | square x 27 | 0.25 |
| 373 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 5 | 17 -> 49 | coarser | checkered | square x 27 | 0.26 |
| 374 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 6 | 17 -> 81 | coarser | checkered | square x 27 | 0.27 |
| 375 | 46 | 0 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 7 | 17 -> 145 | coarser | checkered | square x 27 | 0.29 |
| 376 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 0 | 13 -> 12 | finer | checkered | square x 28 | 0.25 |
| 377 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 1 | 13 -> 15 | coarser | checkered | square x 28 | 0.25 |
| 378 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 2 | 13 -> 9 | finer | checkered | square x 28 | 0.25 |
| 379 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 3 | 13 -> 5 | finer | checkered | square x 28 | 0.25 |
| 380 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 4 | 13 -> 29 | coarser | checkered | square x 28 | 0.26 |
| 381 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 5 | 13 -> 45 | coarser | checkered | square x 28 | 0.27 |
| 382 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 6 | 13 -> 77 | coarser | checkered | square x 28 | 0.28 |
| 383 | 47 | 0 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 7 | 13 -> 141 | coarser | checkered | square x 28 | 0.32 |
| 384 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 0 | 14 -> 15 | coarser | checkered | square x 29 | 0.25 |
| 385 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 1 | 14 -> 12 | finer | checkered | square x 29 | 0.25 |
| 386 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 2 | 14 -> 10 | finer | checkered | square x 29 | 0.25 |
| 387 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 3 | 14 -> 6 | finer | checkered | square x 29 | 0.25 |
| 388 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 4 | 14 -> 30 | coarser | checkered | square x 29 | 0.25 |
| 389 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 5 | 14 -> 46 | coarser | checkered | square x 29 | 0.26 |
| 390 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 6 | 14 -> 78 | coarser | checkered | square x 29 | 0.27 |
| 391 | 48 | 0 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 7 | 14 -> 142 | coarser | checkered | square x 29 | 0.28 |
| 392 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 0 | 15 -> 14 | finer | checkered | square x 30 | 0.25 |
| 393 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 1 | 15 -> 13 | finer | checkered | square x 30 | 0.25 |
| 394 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 2 | 15 -> 11 | finer | checkered | square x 30 | 0.25 |
| 395 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 3 | 15 -> 7 | finer | checkered | square x 30 | 0.25 |
| 396 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 4 | 15 -> 31 | coarser | checkered | square x 30 | 0.25 |
| 397 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 5 | 15 -> 47 | coarser | checkered | square x 30 | 0.25 |
| 398 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 6 | 15 -> 79 | coarser | checkered | square x 30 | 0.26 |
| 399 | 49 | 0 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 7 | 15 -> 143 | coarser | checkered | square x 30 | 0.26 |
| 400 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered | square x 31 | 0.25 |
| 401 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered | square x 31 | 0.25 |
| 402 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered | square x 31 | 0.25 |
| 403 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered | square x 31 | 0.25 |
| 404 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 4 | 16 -> 0 | zero | checkered | square x 31 | 0.25 |
| 405 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered | square x 31 | 0.25 |
| 406 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered | square x 31 | 0.25 |
| 407 | 50 | 0 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered | square x 31 | 0.26 |
| 408 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered | square x 32 | 0.25 |
| 409 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered | square x 32 | 0.25 |
| 410 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered | square x 32 | 0.25 |
| 411 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered | square x 32 | 0.25 |
| 412 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 4 | 16 -> 0 | zero | checkered | square x 32 | 0.25 |
| 413 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered | square x 32 | 0.25 |
| 414 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered | square x 32 | 0.26 |
| 415 | 51 | 0 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered | square x 32 | 0.27 |
| 416 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 0 | 17 -> 16 | finer | checkered | square x 33 | 0.25 |
| 417 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 1 | 17 -> 19 | coarser | checkered | square x 33 | 0.25 |
| 418 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 2 | 17 -> 21 | coarser | checkered | square x 33 | 0.25 |
| 419 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 3 | 17 -> 25 | coarser | checkered | square x 33 | 0.25 |
| 420 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 4 | 17 -> 1 | finer | checkered | square x 33 | 0.25 |
| 421 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 5 | 17 -> 49 | coarser | checkered | square x 33 | 0.25 |
| 422 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 6 | 17 -> 81 | coarser | checkered | square x 33 | 0.26 |
| 423 | 52 | 0 | 51 | 5,5 | checkerboard 0.80px cells | 7 | 17 -> 145 | coarser | checkered | square x 33 | 0.26 |
| 424 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered | square x 34 | 0.25 |
| 425 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered | square x 34 | 0.25 |
| 426 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered | square x 34 | 0.25 |
| 427 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered | square x 34 | 0.25 |
| 428 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 4 | 16 -> 0 | zero | checkered | square x 34 | 0.25 |
| 429 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered | square x 34 | 0.25 |
| 430 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered | square x 34 | 0.26 |
| 431 | 53 | 0 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered | square x 34 | 0.27 |
| 432 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 0 | 10 -> 11 | coarser | checkered | square x 35 | 0.25 |
| 433 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 1 | 10 -> 8 | finer | checkered | square x 35 | 0.25 |
| 434 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 2 | 10 -> 14 | coarser | checkered | square x 35 | 0.25 |
| 435 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 3 | 10 -> 2 | finer | checkered | square x 35 | 0.25 |
| 436 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 4 | 10 -> 26 | coarser | checkered | square x 35 | 0.25 |
| 437 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 5 | 10 -> 42 | coarser | checkered | square x 35 | 0.26 |
| 438 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 6 | 10 -> 74 | coarser | checkered | square x 35 | 0.26 |
| 439 | 54 | 0 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 7 | 10 -> 138 | coarser | checkered | square x 35 | 0.28 |
| 440 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 0 | 12 -> 13 | coarser | checkered | square x 36 | 0.25 |
| 441 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 1 | 12 -> 14 | coarser | checkered | square x 36 | 0.25 |
| 442 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 2 | 12 -> 8 | finer | checkered | square x 36 | 0.25 |
| 443 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 3 | 12 -> 4 | finer | checkered | square x 36 | 0.25 |
| 444 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 4 | 12 -> 28 | coarser | checkered | square x 36 | 0.25 |
| 445 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 5 | 12 -> 44 | coarser | checkered | square x 36 | 0.25 |
| 446 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 6 | 12 -> 76 | coarser | checkered | square x 36 | 0.26 |
| 447 | 55 | 0 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 7 | 12 -> 140 | coarser | checkered | square x 36 | 0.26 |
| 448 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 0 | 18 -> 19 | coarser | checkered | square x 37 | 0.25 |
| 449 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 1 | 18 -> 16 | finer | checkered | square x 37 | 0.25 |
| 450 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 2 | 18 -> 22 | coarser | checkered | square x 37 | 0.25 |
| 451 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 3 | 18 -> 26 | coarser | checkered | square x 37 | 0.25 |
| 452 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 4 | 18 -> 2 | finer | checkered | square x 37 | 0.25 |
| 453 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 5 | 18 -> 50 | coarser | checkered | square x 37 | 0.25 |
| 454 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 6 | 18 -> 82 | coarser | checkered | square x 37 | 0.25 |
| 455 | 56 | 0 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 7 | 18 -> 146 | coarser | checkered | square x 37 | 0.25 |
| 456 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 0 | 19 -> 18 | finer | checkered | square x 38 | 0.25 |
| 457 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 1 | 19 -> 17 | finer | checkered | square x 38 | 0.25 |
| 458 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 2 | 19 -> 23 | coarser | checkered | square x 38 | 0.25 |
| 459 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 3 | 19 -> 27 | coarser | checkered | square x 38 | 0.25 |
| 460 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 4 | 19 -> 3 | finer | checkered | square x 38 | 0.25 |
| 461 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 5 | 19 -> 51 | coarser | checkered | square x 38 | 0.25 |
| 462 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 6 | 19 -> 83 | coarser | checkered | square x 38 | 0.25 |
| 463 | 57 | 0 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 7 | 19 -> 147 | coarser | checkered | square x 38 | 0.25 |
| 464 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 0 | 18 -> 19 | coarser | checkered | square x 39 | 0.25 |
| 465 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 1 | 18 -> 16 | finer | checkered | square x 39 | 0.25 |
| 466 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 2 | 18 -> 22 | coarser | checkered | square x 39 | 0.25 |
| 467 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 3 | 18 -> 26 | coarser | checkered | square x 39 | 0.25 |
| 468 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 4 | 18 -> 2 | finer | checkered | square x 39 | 0.25 |
| 469 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 5 | 18 -> 50 | coarser | checkered | square x 39 | 0.25 |
| 470 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 6 | 18 -> 82 | coarser | checkered | square x 39 | 0.25 |
| 471 | 58 | 0 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 7 | 18 -> 146 | coarser | checkered | square x 39 | 0.25 |
| 472 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered | square x 40 | 0.25 |
| 473 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered | square x 40 | 0.25 |
| 474 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered | square x 40 | 0.25 |
| 475 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered | square x 40 | 0.25 |
| 476 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 4 | 16 -> 0 | zero | checkered | square x 40 | 0.25 |
| 477 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered | square x 40 | 0.25 |
| 478 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered | square x 40 | 0.25 |
| 479 | 59 | 0 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered | square x 40 | 0.25 |
| 480 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 0 | 19 -> 18 | finer | checkered | square x 41 | 0.25 |
| 481 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 1 | 19 -> 17 | finer | checkered | square x 41 | 0.25 |
| 482 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 2 | 19 -> 23 | coarser | checkered | square x 41 | 0.25 |
| 483 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 3 | 19 -> 27 | coarser | checkered | square x 41 | 0.25 |
| 484 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 4 | 19 -> 3 | finer | checkered | square x 41 | 0.25 |
| 485 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 5 | 19 -> 51 | coarser | checkered | square x 41 | 0.25 |
| 486 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 6 | 19 -> 83 | coarser | checkered | square x 41 | 0.25 |
| 487 | 60 | 0 | 59 | 6,6 | checkerboard 0.67px cells | 7 | 19 -> 147 | coarser | checkered | square x 41 | 0.25 |
| 488 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 0 | 15 -> 14 | finer | checkered | square x 42 | 0.25 |
| 489 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 1 | 15 -> 13 | finer | checkered | square x 42 | 0.25 |
| 490 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 2 | 15 -> 11 | finer | checkered | square x 42 | 0.25 |
| 491 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 3 | 15 -> 7 | finer | checkered | square x 42 | 0.25 |
| 492 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 4 | 15 -> 31 | coarser | checkered | square x 42 | 0.25 |
| 493 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 5 | 15 -> 47 | coarser | checkered | square x 42 | 0.25 |
| 494 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 6 | 15 -> 79 | coarser | checkered | square x 42 | 0.25 |
| 495 | 61 | 0 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 7 | 15 -> 143 | coarser | checkered | square x 42 | 0.25 |
| 496 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered | square x 43 | 0.25 |
| 497 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered | square x 43 | 0.25 |
| 498 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered | square x 43 | 0.25 |
| 499 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered | square x 43 | 0.25 |
| 500 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 4 | 16 -> 0 | zero | checkered | square x 43 | 0.25 |
| 501 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered | square x 43 | 0.25 |
| 502 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered | square x 43 | 0.25 |
| 503 | 62 | 0 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered | square x 43 | 0.25 |
| 504 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered | square x 44 | 0.25 |
| 505 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered | square x 44 | 0.25 |
| 506 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered | square x 44 | 0.25 |
| 507 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered | square x 44 | 0.25 |
| 508 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 4 | 16 -> 0 | zero | checkered | square x 44 | 0.25 |
| 509 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered | square x 44 | 0.25 |
| 510 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered | square x 44 | 0.25 |
| 511 | 63 | 0 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered | square x 44 | 0.25 |
| 512 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 0 | 16 -> 17 | coarser | checkered | square x 45 | 0.25 |
| 513 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 1 | 16 -> 18 | coarser | checkered | square x 45 | 0.25 |
| 514 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 2 | 16 -> 20 | coarser | checkered | square x 45 | 0.25 |
| 515 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 3 | 16 -> 24 | coarser | checkered | square x 45 | 0.25 |
| 516 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 4 | 16 -> 0 | zero | checkered | square x 45 | 0.25 |
| 517 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 5 | 16 -> 48 | coarser | checkered | square x 45 | 0.25 |
| 518 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 6 | 16 -> 80 | coarser | checkered | square x 45 | 0.25 |
| 519 | 64 | 0 | 63 | 7,7 | checkerboard 0.57px cells | 7 | 16 -> 144 | coarser | checkered | square x 45 | 0.25 |
| 528 | 66 | 1 | 0 | 0,0 | flat (DC) | 0 | 3 -> 2 | finer | saturation chroma | lower saturation | 2.32 |
| 529 | 66 | 1 | 0 | 0,0 | flat (DC) | 1 | 3 -> 1 | finer | saturation chroma | lower saturation | 4.86 |
| 530 | 66 | 1 | 0 | 0,0 | flat (DC) | 2 | 3 -> 7 | coarser | saturation chroma | increase saturation | 9.51 |
| 531 | 66 | 1 | 0 | 0,0 | flat (DC) | 3 | 3 -> 11 | coarser | saturation chroma | increase saturation | 18.16 |
| 532 | 66 | 1 | 0 | 0,0 | flat (DC) | 4 | 3 -> 19 | coarser | saturation chroma | increase saturation | 33.77 |
| 533 | 66 | 1 | 0 | 0,0 | flat (DC) | 5 | 3 -> 35 | coarser | saturation chroma | increase saturation | 56.90 |
| 534 | 66 | 1 | 0 | 0,0 | flat (DC) | 6 | 3 -> 67 | coarser | saturation chroma | increase saturation | 78.60 |
| 535 | 66 | 1 | 0 | 0,0 | flat (DC) | 7 | 3 -> 131 | coarser | saturation chroma | increase saturation | 91.36 |
| 536 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 0 | 3 -> 2 | finer | vertical grating chroma | vertical color | 0.29 |
| 537 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 1 | 3 -> 1 | finer | vertical grating chroma | vertical color | 0.36 |
| 538 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 2 | 3 -> 7 | coarser | vertical grating chroma | vertical color | 0.50 |
| 539 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 3 | 3 -> 11 | coarser | vertical grating chroma | vertical color | 0.80 |
| 540 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 4 | 3 -> 19 | coarser | vertical grating chroma | vertical color | 1.45 |
| 541 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 5 | 3 -> 35 | coarser | vertical grating chroma | vertical color | 2.74 |
| 542 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 6 | 3 -> 67 | coarser | vertical grating chroma | vertical color | 5.18 |
| 543 | 67 | 1 | 1 | 1,0 | vertical bands 4.0px | 7 | 3 -> 131 | coarser | vertical grating chroma | vertical color | 9.46 |
| 544 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 0 | 3 -> 2 | finer | horizontal grating chroma | horizontal color | 0.29 |
| 545 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 1 | 3 -> 1 | finer | horizontal grating chroma | horizontal color | 0.35 |
| 546 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 2 | 3 -> 7 | coarser | horizontal grating chroma | horizontal color | 0.50 |
| 547 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 3 | 3 -> 11 | coarser | horizontal grating chroma | horizontal color | 0.78 |
| 548 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 4 | 3 -> 19 | coarser | horizontal grating chroma | horizontal color | 1.43 |
| 549 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 5 | 3 -> 35 | coarser | horizontal grating chroma | horizontal color | 2.72 |
| 550 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 6 | 3 -> 67 | coarser | horizontal grating chroma | horizontal color | 5.18 |
| 551 | 68 | 1 | 2 | 0,1 | horizontal bands 4.0px | 7 | 3 -> 131 | coarser | horizontal grating chroma | horizontal color | 9.56 |
| 552 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 0 | 4 -> 5 | coarser | horizontal grating chroma | horizontal color 2 | 0.26 |
| 553 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 1 | 4 -> 6 | coarser | horizontal grating chroma | horizontal color 2 | 0.27 |
| 554 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 2 | 4 -> 0 | zero | horizontal grating chroma | horizontal color 2 | 0.30 |
| 555 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 3 | 4 -> 12 | coarser | horizontal grating chroma | horizontal color 2 | 0.35 |
| 556 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 4 | 4 -> 20 | coarser | horizontal grating chroma | horizontal color 2 | 0.46 |
| 557 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 5 | 4 -> 36 | coarser | horizontal grating chroma | horizontal color 2 | 0.73 |
| 558 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 6 | 4 -> 68 | coarser | horizontal grating chroma | horizontal color 2 | 1.27 |
| 559 | 69 | 1 | 3 | 0,2 | horizontal bands 2.0px | 7 | 4 -> 132 | coarser | horizontal grating chroma | horizontal color 2 | 2.34 |
| 560 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 0 | 3 -> 2 | finer | checkered chroma | square x color | 0.26 |
| 561 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 1 | 3 -> 1 | finer | checkered chroma | square x color | 0.29 |
| 562 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 2 | 3 -> 7 | coarser | checkered chroma | square x color | 0.33 |
| 563 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 3 | 3 -> 11 | coarser | checkered chroma | square x color | 0.45 |
| 564 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 4 | 3 -> 19 | coarser | checkered chroma | square x color | 0.70 |
| 565 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 5 | 3 -> 35 | coarser | checkered chroma | square x color | 1.24 |
| 566 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 6 | 3 -> 67 | coarser | checkered chroma | square x color | 2.35 |
| 567 | 70 | 1 | 4 | 1,1 | checkerboard 4.0px cells | 7 | 3 -> 131 | coarser | checkered chroma | square x color | 4.37 |
| 568 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 0 | 4 -> 5 | coarser | vertical grating chroma | vertical color 2 | 0.26 |
| 569 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 1 | 4 -> 6 | coarser | vertical grating chroma | vertical color 2 | 0.27 |
| 570 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 2 | 4 -> 0 | zero | vertical grating chroma | vertical color 2 | 0.30 |
| 571 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 3 | 4 -> 12 | coarser | vertical grating chroma | vertical color 2 | 0.35 |
| 572 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 4 | 4 -> 20 | coarser | vertical grating chroma | vertical color 2 | 0.46 |
| 573 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 5 | 4 -> 36 | coarser | vertical grating chroma | vertical color 2 | 0.72 |
| 574 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 6 | 4 -> 68 | coarser | vertical grating chroma | vertical color 2 | 1.25 |
| 575 | 71 | 1 | 5 | 2,0 | vertical bands 2.0px | 7 | 4 -> 132 | coarser | vertical grating chroma | vertical color 2 | 2.33 |
| 576 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 0 | 8 -> 9 | coarser | vertical grating chroma | vertical color 3 | 0.25 |
| 577 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 1 | 8 -> 10 | coarser | vertical grating chroma | vertical color 3 | 0.25 |
| 578 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 2 | 8 -> 12 | coarser | vertical grating chroma | vertical color 3 | 0.25 |
| 579 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 3 | 8 -> 0 | zero | vertical grating chroma | vertical color 3 | 0.26 |
| 580 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 4 | 8 -> 24 | coarser | vertical grating chroma | vertical color 3 | 0.26 |
| 581 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 5 | 8 -> 40 | coarser | vertical grating chroma | vertical color 3 | 0.27 |
| 582 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 6 | 8 -> 72 | coarser | vertical grating chroma | vertical color 3 | 0.31 |
| 583 | 72 | 1 | 6 | 3,0 | vertical bands 1.3px | 7 | 8 -> 136 | coarser | vertical grating chroma | vertical color 3 | 0.38 |
| 584 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 0 | 4 -> 5 | coarser | checkered chroma | square x color 2 | 0.25 |
| 585 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 1 | 4 -> 6 | coarser | checkered chroma | square x color 2 | 0.26 |
| 586 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 2 | 4 -> 0 | zero | checkered chroma | square x color 2 | 0.27 |
| 587 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 3 | 4 -> 12 | coarser | checkered chroma | square x color 2 | 0.29 |
| 588 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 4 | 4 -> 20 | coarser | checkered chroma | square x color 2 | 0.36 |
| 589 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 5 | 4 -> 36 | coarser | checkered chroma | square x color 2 | 0.50 |
| 590 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 6 | 4 -> 68 | coarser | checkered chroma | square x color 2 | 0.81 |
| 591 | 73 | 1 | 7 | 2,1 | plaid 2.0pxx4.0px cells | 7 | 4 -> 132 | coarser | checkered chroma | square x color 2 | 1.43 |
| 592 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 0 | 4 -> 5 | coarser | checkered chroma | square x color 3 | 0.25 |
| 593 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 1 | 4 -> 6 | coarser | checkered chroma | square x color 3 | 0.26 |
| 594 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 2 | 4 -> 0 | zero | checkered chroma | square x color 3 | 0.27 |
| 595 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 3 | 4 -> 12 | coarser | checkered chroma | square x color 3 | 0.29 |
| 596 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 4 | 4 -> 20 | coarser | checkered chroma | square x color 3 | 0.35 |
| 597 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 5 | 4 -> 36 | coarser | checkered chroma | square x color 3 | 0.50 |
| 598 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 6 | 4 -> 68 | coarser | checkered chroma | square x color 3 | 0.80 |
| 599 | 74 | 1 | 8 | 1,2 | plaid 4.0pxx2.0px cells | 7 | 4 -> 132 | coarser | checkered chroma | square x color 3 | 1.40 |
| 600 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 0 | 8 -> 9 | coarser | horizontal grating chroma | horizontal color 3 | 0.25 |
| 601 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 1 | 8 -> 10 | coarser | horizontal grating chroma | horizontal color 3 | 0.25 |
| 602 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 2 | 8 -> 12 | coarser | horizontal grating chroma | horizontal color 3 | 0.25 |
| 603 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 3 | 8 -> 0 | zero | horizontal grating chroma | horizontal color 3 | 0.26 |
| 604 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 4 | 8 -> 24 | coarser | horizontal grating chroma | horizontal color 3 | 0.26 |
| 605 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 5 | 8 -> 40 | coarser | horizontal grating chroma | horizontal color 3 | 0.28 |
| 606 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 6 | 8 -> 72 | coarser | horizontal grating chroma | horizontal color 3 | 0.33 |
| 607 | 75 | 1 | 9 | 0,3 | horizontal bands 1.3px | 7 | 8 -> 136 | coarser | horizontal grating chroma | horizontal color 3 | 0.42 |
| 608 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 0 | 16 -> 17 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 609 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 1 | 16 -> 18 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 610 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 2 | 16 -> 20 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 611 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 3 | 16 -> 24 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 612 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 4 | 16 -> 0 | zero | horizontal grating chroma | vertical color 4 | 0.25 |
| 613 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 5 | 16 -> 48 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 614 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 6 | 16 -> 80 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 615 | 76 | 1 | 10 | 0,4 | horizontal bands 1.0px | 7 | 16 -> 144 | coarser | horizontal grating chroma | vertical color 4 | 0.25 |
| 616 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 0 | 11 -> 10 | finer | checkered chroma | square x color 4 | 0.25 |
| 617 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 1 | 11 -> 9 | finer | checkered chroma | square x color 4 | 0.25 |
| 618 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 2 | 11 -> 15 | coarser | checkered chroma | square x color 4 | 0.25 |
| 619 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 3 | 11 -> 3 | finer | checkered chroma | square x color 4 | 0.25 |
| 620 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 4 | 11 -> 27 | coarser | checkered chroma | square x color 4 | 0.25 |
| 621 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 5 | 11 -> 43 | coarser | checkered chroma | square x color 4 | 0.26 |
| 622 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 6 | 11 -> 75 | coarser | checkered chroma | square x color 4 | 0.26 |
| 623 | 77 | 1 | 11 | 1,3 | plaid 4.0pxx1.3px cells | 7 | 11 -> 139 | coarser | checkered chroma | square x color 4 | 0.27 |
| 624 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 0 | 9 -> 8 | finer | checkered chroma | square x color 5 | 0.25 |
| 625 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 1 | 9 -> 11 | coarser | checkered chroma | square x color 5 | 0.25 |
| 626 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 2 | 9 -> 13 | coarser | checkered chroma | square x color 5 | 0.25 |
| 627 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 3 | 9 -> 1 | finer | checkered chroma | square x color 5 | 0.25 |
| 628 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 4 | 9 -> 25 | coarser | checkered chroma | square x color 5 | 0.26 |
| 629 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 5 | 9 -> 41 | coarser | checkered chroma | square x color 5 | 0.27 |
| 630 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 6 | 9 -> 73 | coarser | checkered chroma | square x color 5 | 0.29 |
| 631 | 78 | 1 | 12 | 2,2 | checkerboard 2.0px cells | 7 | 9 -> 137 | coarser | checkered chroma | square x color 5 | 0.34 |
| 632 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 0 | 11 -> 10 | finer | checkered chroma | square x color 6 | 0.25 |
| 633 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 1 | 11 -> 9 | finer | checkered chroma | square x color 6 | 0.25 |
| 634 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 2 | 11 -> 15 | coarser | checkered chroma | square x color 6 | 0.25 |
| 635 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 3 | 11 -> 3 | finer | checkered chroma | square x color 6 | 0.25 |
| 636 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 4 | 11 -> 27 | coarser | checkered chroma | square x color 6 | 0.25 |
| 637 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 5 | 11 -> 43 | coarser | checkered chroma | square x color 6 | 0.26 |
| 638 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 6 | 11 -> 75 | coarser | checkered chroma | square x color 6 | 0.26 |
| 639 | 79 | 1 | 13 | 3,1 | plaid 1.3pxx4.0px cells | 7 | 11 -> 139 | coarser | checkered chroma | square x color 6 | 0.27 |
| 640 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 0 | 16 -> 17 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 641 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 1 | 16 -> 18 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 642 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 2 | 16 -> 20 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 643 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 3 | 16 -> 24 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 644 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 4 | 16 -> 0 | zero | vertical grating chroma | vertical color 5 | 0.25 |
| 645 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 5 | 16 -> 48 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 646 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 6 | 16 -> 80 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 647 | 80 | 1 | 14 | 4,0 | vertical bands 1.0px | 7 | 16 -> 144 | coarser | vertical grating chroma | vertical color 5 | 0.25 |
| 648 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 0 | 16 -> 17 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 649 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 1 | 16 -> 18 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 650 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 2 | 16 -> 20 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 651 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 3 | 16 -> 24 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 652 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 4 | 16 -> 0 | zero | vertical grating chroma | vertical color 6 | 0.25 |
| 653 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 5 | 16 -> 48 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 654 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 6 | 16 -> 80 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 655 | 81 | 1 | 15 | 5,0 | vertical bands 0.80px | 7 | 16 -> 144 | coarser | vertical grating chroma | vertical color 6 | 0.25 |
| 656 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | square x color 7 | 0.25 |
| 657 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | square x color 7 | 0.25 |
| 658 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | square x color 7 | 0.25 |
| 659 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | square x color 7 | 0.25 |
| 660 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 4 | 16 -> 0 | zero | checkered chroma | square x color 7 | 0.25 |
| 661 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | square x color 7 | 0.25 |
| 662 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | square x color 7 | 0.25 |
| 663 | 82 | 1 | 16 | 4,1 | plaid 1.0pxx4.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | square x color 7 | 0.25 |
| 664 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | square x color 8 | 0.25 |
| 665 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | square x color 8 | 0.25 |
| 666 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | square x color 8 | 0.25 |
| 667 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | square x color 8 | 0.25 |
| 668 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 4 | 16 -> 0 | zero | checkered chroma | square x color 8 | 0.25 |
| 669 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | square x color 8 | 0.25 |
| 670 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | square x color 8 | 0.25 |
| 671 | 83 | 1 | 17 | 3,2 | plaid 1.3pxx2.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | square x color 8 | 0.25 |
| 672 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | square x color 9 | 0.25 |
| 673 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | square x color 9 | 0.25 |
| 674 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | square x color 9 | 0.25 |
| 675 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | square x color 9 | 0.25 |
| 676 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | square x color 9 | 0.25 |
| 677 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | square x color 9 | 0.25 |
| 678 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | square x color 9 | 0.25 |
| 679 | 84 | 1 | 18 | 2,3 | plaid 2.0pxx1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | square x color 9 | 0.25 |
| 680 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | square x color 10 | 0.25 |
| 681 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | square x color 10 | 0.25 |
| 682 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | square x color 10 | 0.25 |
| 683 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | square x color 10 | 0.25 |
| 684 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | square x color 10 | 0.25 |
| 685 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | square x color 10 | 0.25 |
| 686 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | square x color 10 | 0.25 |
| 687 | 85 | 1 | 19 | 1,4 | plaid 4.0pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | square x color 10 | 0.25 |
| 688 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 0 | 16 -> 17 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 689 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 1 | 16 -> 18 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 690 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 2 | 16 -> 20 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 691 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 3 | 16 -> 24 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 692 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 4 | 16 -> 0 | zero | horizontal grating chroma | horizontal color 4 | 0.25 |
| 693 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 5 | 16 -> 48 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 694 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 6 | 16 -> 80 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 695 | 86 | 1 | 20 | 0,5 | horizontal bands 0.80px | 7 | 16 -> 144 | coarser | horizontal grating chroma | horizontal color 4 | 0.25 |
| 696 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 0 | 16 -> 17 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 697 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 1 | 16 -> 18 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 698 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 2 | 16 -> 20 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 699 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 3 | 16 -> 24 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 700 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 4 | 16 -> 0 | zero | horizontal grating chroma | horizontal color 5 | 0.25 |
| 701 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 5 | 16 -> 48 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 702 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 6 | 16 -> 80 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 703 | 87 | 1 | 21 | 0,6 | horizontal bands 0.67px | 7 | 16 -> 144 | coarser | horizontal grating chroma | horizontal color 5 | 0.25 |
| 704 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 705 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 706 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 707 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 708 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 709 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 710 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 711 | 88 | 1 | 22 | 1,5 | plaid 4.0pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 712 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 713 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 714 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 715 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 716 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 717 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 718 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 719 | 89 | 1 | 23 | 2,4 | plaid 2.0pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 720 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 721 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 722 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 723 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 724 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 725 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 726 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 727 | 90 | 1 | 24 | 3,3 | checkerboard 1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 728 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 729 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 730 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 731 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 732 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 733 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 734 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 735 | 91 | 1 | 25 | 4,2 | plaid 1.0pxx2.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 736 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 737 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 738 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 739 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 740 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 741 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 742 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 743 | 92 | 1 | 26 | 5,1 | plaid 0.80pxx4.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 744 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 0 | 16 -> 17 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 745 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 1 | 16 -> 18 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 746 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 2 | 16 -> 20 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 747 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 3 | 16 -> 24 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 748 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 4 | 16 -> 0 | zero | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 749 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 5 | 16 -> 48 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 750 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 6 | 16 -> 80 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 751 | 93 | 1 | 27 | 6,0 | vertical bands 0.67px | 7 | 16 -> 144 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 752 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 0 | 16 -> 17 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 753 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 1 | 16 -> 18 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 754 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 2 | 16 -> 20 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 755 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 3 | 16 -> 24 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 756 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 4 | 16 -> 0 | zero | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 757 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 5 | 16 -> 48 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 758 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 6 | 16 -> 80 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 759 | 94 | 1 | 28 | 7,0 | vertical bands 0.57px | 7 | 16 -> 144 | coarser | vertical grating chroma | rest of bits not yet categorized | 0.25 |
| 760 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 761 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 762 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 763 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 764 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 765 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 766 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 767 | 95 | 1 | 29 | 6,1 | plaid 0.67pxx4.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 768 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 769 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 770 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 771 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 772 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 773 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 774 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 775 | 96 | 1 | 30 | 5,2 | plaid 0.80pxx2.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 776 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 777 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 778 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 779 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 780 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 781 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 782 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 783 | 97 | 1 | 31 | 4,3 | plaid 1.0pxx1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 784 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 785 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 786 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 787 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 788 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 789 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 790 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 791 | 98 | 1 | 32 | 3,4 | plaid 1.3pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 792 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 793 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 794 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 795 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 796 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 797 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 798 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 799 | 99 | 1 | 33 | 2,5 | plaid 2.0pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 800 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 801 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 802 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 803 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 804 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 805 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 806 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 807 | 100 | 1 | 34 | 1,6 | plaid 4.0pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 808 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 0 | 16 -> 17 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 809 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 1 | 16 -> 18 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 810 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 2 | 16 -> 20 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 811 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 3 | 16 -> 24 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 812 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 4 | 16 -> 0 | zero | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 813 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 5 | 16 -> 48 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 814 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 6 | 16 -> 80 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 815 | 101 | 1 | 35 | 0,7 | horizontal bands 0.57px | 7 | 16 -> 144 | coarser | horizontal grating chroma | rest of bits not yet categorized | 0.25 |
| 816 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 817 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 818 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 819 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 820 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 821 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 822 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 823 | 102 | 1 | 36 | 1,7 | plaid 4.0pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 824 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 825 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 826 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 827 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 828 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 829 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 830 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 831 | 103 | 1 | 37 | 2,6 | plaid 2.0pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 832 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 833 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 834 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 835 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 836 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 837 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 838 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 839 | 104 | 1 | 38 | 3,5 | plaid 1.3pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 840 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 841 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 842 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 843 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 844 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 845 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 846 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 847 | 105 | 1 | 39 | 4,4 | checkerboard 1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 848 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 849 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 850 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 851 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 852 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 853 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 854 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 855 | 106 | 1 | 40 | 5,3 | plaid 0.80pxx1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 856 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 857 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 858 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 859 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 860 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 861 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 862 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 863 | 107 | 1 | 41 | 6,2 | plaid 0.67pxx2.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 864 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 865 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 866 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 867 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 868 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 869 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 870 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 871 | 108 | 1 | 42 | 7,1 | plaid 0.57pxx4.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 872 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 873 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 874 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 875 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 876 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 877 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 878 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 879 | 109 | 1 | 43 | 7,2 | plaid 0.57pxx2.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 880 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 881 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 882 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 883 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 884 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 885 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 886 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 887 | 110 | 1 | 44 | 6,3 | plaid 0.67pxx1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 888 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 889 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 890 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 891 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 892 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 893 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 894 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 895 | 111 | 1 | 45 | 5,4 | plaid 0.80pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 896 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 897 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 898 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 899 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 900 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 901 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 902 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 903 | 112 | 1 | 46 | 4,5 | plaid 1.0pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 904 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 905 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 906 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 907 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 908 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 909 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 910 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 911 | 113 | 1 | 47 | 3,6 | plaid 1.3pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 912 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 913 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 914 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 915 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 916 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 917 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 918 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 919 | 114 | 1 | 48 | 2,7 | plaid 2.0pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 920 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 921 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 922 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 923 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 924 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 925 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 926 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 927 | 115 | 1 | 49 | 3,7 | plaid 1.3pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 928 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 929 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 930 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 931 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 932 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 933 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 934 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 935 | 116 | 1 | 50 | 4,6 | plaid 1.0pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 936 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 937 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 938 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 939 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 940 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 941 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 942 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 943 | 117 | 1 | 51 | 5,5 | checkerboard 0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 944 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 945 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 946 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 947 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 948 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 949 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 950 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 951 | 118 | 1 | 52 | 6,4 | plaid 0.67pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 952 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 953 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 954 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 955 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 956 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 957 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 958 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 959 | 119 | 1 | 53 | 7,3 | plaid 0.57pxx1.3px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 960 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 961 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 962 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 963 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 964 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 965 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 966 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 967 | 120 | 1 | 54 | 7,4 | plaid 0.57pxx1.0px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 968 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 969 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 970 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 971 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 972 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 973 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 974 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 975 | 121 | 1 | 55 | 6,5 | plaid 0.67pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 976 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 977 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 978 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 979 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 980 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 981 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 982 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 983 | 122 | 1 | 56 | 5,6 | plaid 0.80pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 984 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 985 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 986 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 987 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 988 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 989 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 990 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 991 | 123 | 1 | 57 | 4,7 | plaid 1.0pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 992 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 993 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 994 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 995 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 996 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 997 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 998 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 999 | 124 | 1 | 58 | 5,7 | plaid 0.80pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1000 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1001 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1002 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1003 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1004 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1005 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1006 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1007 | 125 | 1 | 59 | 6,6 | checkerboard 0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1008 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1009 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1010 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1011 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1012 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1013 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1014 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1015 | 126 | 1 | 60 | 7,5 | plaid 0.57pxx0.80px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1016 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1017 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1018 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1019 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1020 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1021 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1022 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1023 | 127 | 1 | 61 | 7,6 | plaid 0.57pxx0.67px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1024 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1025 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1026 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1027 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1028 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1029 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1030 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1031 | 128 | 1 | 62 | 6,7 | plaid 0.67pxx0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1032 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 0 | 16 -> 17 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1033 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 1 | 16 -> 18 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1034 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 2 | 16 -> 20 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1035 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 3 | 16 -> 24 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1036 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 4 | 16 -> 0 | zero | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1037 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 5 | 16 -> 48 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1038 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 6 | 16 -> 80 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |
| 1039 | 129 | 1 | 63 | 7,7 | checkerboard 0.57px cells | 7 | 16 -> 144 | coarser | checkered chroma | rest of bits not yet categorized | 0.25 |

## 8. Notes for whoever builds the UI

* **Sample path rule.** Every image in the batch is at
  `b_x4/dqt/<folder>/b_x4_DQT_1080w_bit<NNNNN>.jpg`, where `<folder>` is
  the folder column of section 5 (or of the appendix) and the bit number is
  zero-padded to five digits. Section 5 gives the bit-0 path for each
  coefficient; raising the bit number by 1...7 walks the eight dial
  positions of that coefficient.
* **Two levels of choice.** Coefficient (128 options, each with a pattern
  name and, where useful, a sample thumbnail) then bit weight (8 options =
  the strength dial).
* **Disable, do not blacken.** Bits 0-7 and 520-527 have no images and never
  will: those flips make the file undecodable.
* **Say when a bit is inert.** Sections 5 and 6 give measured strengths; a
  coefficient at the 0.25 floor will look like nothing on these photos.
  Showing the sample anyway is fine - it teaches that the effect depends on
  the image.
* **The samples are pairs, not single pictures.** Each file is four panels
  (b, e, f, g left to right) with the *same* bit flipped in all four, so a
  UI can crop one panel per source or show the whole strip.
* **Effect direction matters.** `coarser` amplifies ringing; `finer`
  smooths; `zero` deletes that frequency. For a quantizer value that is a
  power of two the same bit can do either depending on whether it was set,
  which is why the per-bit `dir` column exists rather than a per-coefficient
  rule.
* **DC is the exception.** Bits 0-7 of each table's `coeff 0` are the only
  ones that read as global brightness/saturation rather than texture. On
  these photos the luma DC bits measured +2.8 and +5.6 mean luma shift for
  bits 8-9 (brighter) and -11 to -24 for bits 10-15 (darker), and the chroma
  DC bits measured +/-6 to +/-107 of red-minus-blue separation, which is what
  the `lower saturation` / `increase saturation` folders record.

## 9. Points to confirm

1. **Naming direction.** `vertical` holds coefficient (1,0), whose pattern
   is vertical stripes (variation along x) - so read as "vertical = stripe
   direction" the names are consistent. Confirm that is the intent, since
   the opposite reading would swap every horizontal/vertical label.
2. **One likely mislabel.** `vertical color 4` contains coefficient (0,4)
   (bits 584-591), which is a *horizontal* grating by the same rule. Every
   other cross-check of the 89 folders agrees with the geometry, so this one
   looks like a slip - worth a look before the names go into the UI.
3. **The checkered split.** `square x` covers both true checkerboards
   (`u == v`, e.g. (1,1), (3,3), (7,7)) and plaids (`u != v`, e.g. (1,5)).
   The doc keeps them under `checkered` as asked; say the word if the UI
   should split them into `checkered` and `plaid`.
4. **Inert bits.** If the UI is meant to show all 1024 flips doing
   something, the batch needs a broadband source (section 6, option 2).
   Want me to run that sweep and add it as a second sample set?
5. **A machine-readable table.** The appendix above can also be emitted as
   CSV/JSON if the UI would rather load data than parse markdown.

