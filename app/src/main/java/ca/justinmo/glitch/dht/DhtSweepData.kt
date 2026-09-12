package ca.justinmo.glitch.dht

/**
 * The classification from the `b_x4` sweep, one character per bit of the DHT
 * payload - **generated from `DHT.md`, do not edit by hand**.
 *
 * Each character is the folder the sample for that bit was filed in:
 *
 * ```
 *   -   no sample: the flip stops the file decoding
 *   .   no visible change
 *   B   bad            (all four sample photos wrecked)
 *   Z   Y   X          75pct bad / 50pct bad / 25pct bad
 *   3   2   1          75pct / 50pct / 25pct - that many of the four changed
 *   4   100pct         all four changed, and not a wreck
 * ```
 *
 * The sweep flipped one bit of the DHT payload in b.jpg, e.jpg, f.jpg and
 * g.jpg (each 1080 px wide, q92) and laid the four results side by side, so an
 * outcome describes those four photos - not the photo the user has open. It is
 * the best evidence available for "will this edit wreck the picture", and it is
 * a measurement rather than a guess.
 *
 * Read it through [DhtSweep]; nothing else should index these strings.
 */
internal object DhtSweepData {
  /** One character per payload bit, 3328 of them, in bit order. */
  const val ENCODED: String =
    "..--.-------------------------------------------------------------------------------------------" +
    "----------------------------------------BBBB----BBBB----BBBB----BBBB----BBBB----BBBB----BBBB----" +
    "BBBB----BBBB----ZZZZ----X111----....----..------------------------------------------------------" +
    "--------------------------------------------------------------------------------BBBBZBBBBBBBZBBB" +
    "BBBBXXBBBBBB....BBBBYXBBBBBBYYBBBBBBYYZBBBBBYYYZBBBBYYYYBBBBYYYZBBBBYYYZBBBBYYBBBBBBYYYZBBBBYYYY" +
    "BBBBYXYYBBBBYYBBBBBBYYYYBBBBYXXYBBBZYYYBBBBBYYYYBBBBXYYXBBBBXXYXBBBBY1Y1BZBBYYYYBBYBYYYYZBZZYYXY" +
    "BBZB2221BBBB1X11ZBBZYYYYBBZZXYXYZZZZ.X..ZZBZBBBBZZZYXXYYYYYYXXYYZBZZY22YZZZZ..1XYXYY111.YYYXXXXY" +
    "........YYYYYYYYXYYXXYXXXXXX1XXX................XXXXX1XX11121.221X.X..11........................" +
    "X24Y112XX111..111.......................................XXXX222XX1XX11.2XXX....................." +
    "........................33XX222X1XXX1111111......XX.....................................2X2X2112" +
    "111X....1.11............................................2XXX...11XXX............................" +
    "........................3312....1111....................................................XXXX.XX." +
    "X212....XXXX....................................................XYXX..1.X111...................." +
    "........................................XX2X..X.111X....1111...................................." +
    "................XXXX....X111............................................................XYXX...." +
    "XXXX............................................................ZZZZ21111111....11.1............" +
    "................................................XYYY..1........................................." +
    "................................B.--.-----------------------------------------------------------" +
    "------------------------------------------------------------------------BBBB----BBBB----BBBB----" +
    "BBBB----BBBB----ZZBB----ZZZZ----ZZZZ----YYYZ----....----....----....----B.----------------------" +
    "------------------------------------------------------------------------------------------------" +
    "----------------BBBB....BBBBZZZZBBBB3ZZZBBBB1ZZZBBBB2YZZBBBZ2XZZZZZZ11ZZBBBB23ZZBBBB11YZZZZZ..2X" +
    "BBBB133YZBBB221YZBZZ232Z1X21....ZBBB.11XZZZB222XZZZB1.2XBBZB1.12ZBZZ..22ZZZZ.2X1........ZZZZ1.12" +
    "ZYZY...2ZZZZ2XX1ZZZZ1.1.ZZZZ1.1.ZZZZ..11........ZZZZ..113X33....XX4X....ZZZZZZZZYYX1....1311...1" +
    "2X11....ZZZZ1111........2222....3X33....2221....YZYY....1111....XXYX............................" +
    "................................................11X............................................." +
    ".222....111......................................................111............................" +
    "................................................................................................" +
    "................................................................11.1............................" +
    "................................................................................................" +
    "................................................................................................" +
    "................................................................................................" +
    "................................................................................................" +
    "................................................................................................" +
    "................................................................"

  /**
   * The 416 payload bytes the sweep flipped bits in, as hex.
   *
   * This is the layout every [ENCODED] index refers to, so a file whose DHT
   * payload is not these bytes cannot be described by the sweep at all: the
   * bit that was flipped would not be the same bit. [DhtSweep.appliesTo]
   * compares against it, and the screen offers the exact-value editor instead
   * when it does not match.
   */
  const val BASELINE_HEX: String =
    "0000010501010101010100000000000000000102030405060708090a0b100002" +
    "010303020403050504040000017d010203000411051221314106135161072271" +
    "14328191a1082342b1c11552d1f02433627282090a161718191a25262728292a" +
    "3435363738393a434445464748494a535455565758595a636465666768696a73" +
    "7475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9" +
    "aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4" +
    "e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa01000301010101010101010100000000" +
    "00000102030405060708090a0b11000201020404030407050404000102770001" +
    "02031104052131061241510761711322328108144291a1b1c109233352f01562" +
    "72d10a162434e125f11718191a262728292a35363738393a434445464748494a" +
    "535455565758595a636465666768696a737475767778797a8283848586878889" +
    "8a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5" +
    "c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa"
}
