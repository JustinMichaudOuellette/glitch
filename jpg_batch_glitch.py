#!/usr/bin/env python3
"""Side-by-side pair glitcher for the JPEG header tables (DQT / DHT).

Takes two JPEGs, walks the DQT (and/or DHT) one bit at a time and flips
that same bit in *both* files. Each bit gets one output: a single JPEG
with file A's result on the left and file B's on the right, so the two
responses to one identical corruption can be compared directly.

The DQT sweep runs on downscaled copies of both files (1080 px wide by
default): one quantization value moving is easy to miss at full camera
resolution, and it shows on a smaller frame. Sources wider than the
target are decoded, resized with LANCZOS and re-encoded; sources already
at or below it are used untouched. Those copies are written next to the
batch as <stem>_<width>w.jpg, and the DQT rows in the manifest describe
*those* bytes, not the originals. The DHT sweep is unaffected and always
runs on the files as they are.

"The same bit" is the n-th bit of the selected table in each file, not the
same absolute file offset. Two JPEGs rarely put their DQT/DHT at the same
offset, so a shared file offset would corrupt unrelated bytes; the n-th
table bit means the same thing in both files. When the two files do share
a layout (same encoder, same segment order) the n-th table bit is also the
same absolute bit, so both readings agree - and if the tables differ in
size the sweep is the shorter of the two, and Bit N is still the same
position in each file's own table. Bits are counted byte by byte, LSB
first (bit 0 = the 1s bit, matching `1 << bit` in jpg_glitch.py), and only
table payload is touched: marker and segment length bytes are never
flipped, and neither is the EOI marker.

Decoding uses the viewer's own `decode`, so "decodable" means exactly what
jpg_glitch.py means by it. A side that no longer decodes is drawn as a
black panel and the pair is still written - the other side may be the
interesting one. A bit that breaks *both* files produces no output at all.

Output goes to <A>_vs_<B>/ next to file A unless -o/--out says otherwise,
named <A>_<B>_<REGION>_bit<N>.jpg - plus the width, as in
<A>_<B>_DQT_1080w_bit<N>.jpg, when that sweep ran on a downscaled copy, so
runs at different widths never collide in one folder. manifest.csv in that
folder records, per bit, the absolute byte offset each file actually had
flipped, the byte value before/after, which sides decoded, and the canvas
size.

Note: EXIF orientation is not applied, same as jpg_glitch.py - a photo
whose orientation lives in an EXIF tag renders in its stored orientation.
That only affects files used as-is; the downscaled DQT copies are
re-encoded and carry no EXIF. Composites are re-encoded too (a display
artifact, not a glitch output), so JPEG_QUALITY below applies to them.

Examples:
    python jpg_batch_glitch.py a.jpg b.jpg              # DQT + DHT, every bit
    python jpg_batch_glitch.py a.jpg b.jpg -r dht       # DHT only, originals
    python jpg_batch_glitch.py a.jpg b.jpg -w 720       # DQT copies at 720 px
    python jpg_batch_glitch.py a.jpg b.jpg --dry-run   # plan, write nothing
"""
import argparse
import csv
import io
import os
import sys

from PIL import Image

# Shared with the interactive tool so the two never disagree about which
# bytes are "the DQT"/"the DHT", nor about what counts as decodable.
from jpg_glitch import decode, parse_jpeg, region_size

# Region names as parse_jpeg keys them, and as they appear in file names.
REGION_KEYS = ("DQT", "DHT")
MANIFEST_NAME = "manifest.csv"
DEFAULT_GAP_PX = 8  # black seam between the two panels
JPEG_QUALITY = 95  # for the composite only
BLACK = (0, 0, 0)

# The DQT sweep runs on copies downscaled to this width unless -w says
# otherwise; 0 keeps the originals.
DEFAULT_DQT_WIDTH = 1080
DEFAULT_SCALE_QUALITY = 92  # for those copies

# A pair composite is roughly the size of both sources, so a full DHT
# sweep on phone photos is easily tens of GB. The run stops at this much
# written output unless --yes says otherwise; the partial batch is kept
# and can be finished with --skip-existing --yes.
BIG_BATCH_BYTES = 2 * 1024 ** 3

MANIFEST_FIELDS = (
    "index",
    "filename",
    "region",
    "bit_index",
    "source",
    "a_byte_offset",
    "a_byte_offset_hex",
    "a_bit",
    "a_byte_before",
    "a_byte_after",
    "a_decodes",
    "b_byte_offset",
    "b_byte_offset_hex",
    "b_bit",
    "b_byte_before",
    "b_byte_after",
    "b_decodes",
    "canvas_width",
    "canvas_height",
    "bytes",
    "status",
)


def bits_in_ranges(ranges):
    """Yield (byte_offset, bit_index) for every bit in `ranges`, in file order.

    `ranges` is the [(start, end), ...] list parse_jpeg returns. The bit
    index is LSB-based: 0 is the 1s bit, 7 is the 128s bit.
    """
    for start, end in ranges:
        for offset in range(start, end):
            for bit in range(8):
                yield offset, bit


def pair_targets(plan, keys, limit=None):
    """Pair up the bits of both files for each region, in file order.

    Returns [(region, bit_index, off_a, bit_a, off_b, bit_b), ...] where
    bit_index counts bits inside the region, so it is the same position in
    both files. The sweep is the shorter of the two tables, and `limit`
    caps the number of bits tried. Ranges come from the bytes `plan` holds
    for that region - the downscaled copies for DQT.
    """
    targets = []
    for key in keys:
        bits_a = bits_in_ranges(plan[key]["regions_a"][key])
        bits_b = bits_in_ranges(plan[key]["regions_b"][key])
        for bit_index, ((off_a, bit_a), (off_b, bit_b)) in enumerate(zip(bits_a, bits_b)):
            targets.append((key, bit_index, off_a, bit_a, off_b, bit_b))
            if limit is not None and len(targets) >= limit:
                return targets
    return targets


def pair_output_name(stem_a, stem_b, region, bit_index, width=None):
    """One composite per bit; the name carries the bit that was flipped.

    `width` marks a sweep that ran on a downscaled copy, so runs at
    different widths cannot overwrite each other in one folder.
    """
    tag = f"_{width}w" if width else ""
    return f"{stem_a}_{stem_b}_{region}{tag}_bit{bit_index:05d}.jpg"


def try_decode(data):
    """The decoded image, or None when the bytes no longer decode."""
    try:
        return decode(data)
    except Exception:  # noqa: BLE001 - the decoder signals many exception types
        return None


def downscale(raw, target_width, quality):
    """A JPEG copy of `raw` at most `target_width` px wide.

    Returns (bytes, (w, h), changed). Files already at or below the target
    are handed back untouched - there is nothing to downsize, and
    re-encoding them would only add artifacts. Raises when the source does
    not decode, since the copy could not be built.
    """
    with Image.open(io.BytesIO(raw)) as opened:
        opened.load()
        img = opened.convert("RGB")
    try:
        if img.width <= target_width:
            return raw, img.size, False
        height = max(1, round(img.height * target_width / img.width))
        scaled = img.resize((target_width, height), Image.LANCZOS)
        try:
            buf = io.BytesIO()
            scaled.save(buf, format="JPEG", quality=quality)
        finally:
            scaled.close()
        return buf.getvalue(), (target_width, height), True
    finally:
        img.close()


def build_dqt_source(path, raw, target_width, quality):
    """The bytes the DQT sweep flips bits in: (bytes, (w, h), changed).

    Returns None (after reporting why) when a file that needs downscaling
    cannot be decoded - mixing one downscaled side with one original side
    would make the comparison meaningless, so the run stops instead.
    """
    try:
        return downscale(raw, target_width, quality)
    except Exception as exc:  # noqa: BLE001 - decoder may raise many kinds
        print(f"error: cannot downscale '{path}' for the DQT sweep ({exc}).\n"
              f"       the DQT sweep needs both files to decode; use -r dht "
              f"to sweep the originals, or -w 0 to sweep them for DQT too",
              file=sys.stderr)
        return None


def probe_size(raw):
    """Stored pixel size from the header alone, or None if unreadable.

    Header only (no pixel decode), so this still answers for a file whose
    pixels are broken - which is what gives a black panel its shape.
    """
    try:
        with Image.open(io.BytesIO(raw)) as img:
            return img.size
    except Exception:  # noqa: BLE001
        return None


def flip(raw, offset, bit):
    """A copy of `raw` with one bit toggled."""
    data = bytearray(raw)
    data[offset] ^= 1 << bit
    return data


def bit_row(raw_a, raw_b, target):
    """The manifest row for one bit, before anything is decoded."""
    region, bit_index, off_a, bit_a, off_b, bit_b = target
    return {
        "region": region,
        "bit_index": bit_index,
        "a_byte_offset": off_a,
        "a_byte_offset_hex": f"0x{off_a:x}",
        "a_bit": bit_a,
        "a_byte_before": raw_a[off_a],
        "a_byte_after": raw_a[off_a] ^ (1 << bit_a),
        "a_decodes": "",
        "b_byte_offset": off_b,
        "b_byte_offset_hex": f"0x{off_b:x}",
        "b_bit": bit_b,
        "b_byte_before": raw_b[off_b],
        "b_byte_after": raw_b[off_b] ^ (1 << bit_b),
        "b_decodes": "",
        "canvas_width": "",
        "canvas_height": "",
    }


def panel_width(size, panel, match_height):
    """Width the panel occupies: its own if decoded (scaled or not), else
    the shape the header promised for the black rectangle."""
    if panel is None:
        return size[0]
    return panel.width if match_height else max(size[0], panel.width)


def render_frame(raw_a, raw_b, target, gap, probed_a, probed_b, match_height=False):
    """Build the side-by-side composite for one bit.

    Returns (encoded_jpeg_bytes or None, decodes_a, decodes_b, canvas_size).
    The bytes are None exactly when both sides fail to decode - the one
    case that produces no file.
    """
    _, _, off_a, bit_a, off_b, bit_b = target

    data_a = flip(raw_a, off_a, bit_a)
    data_b = flip(raw_b, off_b, bit_b)
    panel_a = try_decode(data_a)
    panel_b = try_decode(data_b)

    if panel_a is None and panel_b is None:
        return None, False, False, None

    # Panel shape: the decoded image wins, else the source header, else the
    # other panel. A black side still keeps its own dimensions.
    fallback = panel_a.size if panel_a is not None else panel_b.size
    size_a = panel_a.size if panel_a is not None else (probed_a or fallback)
    size_b = panel_b.size if panel_b is not None else (probed_b or fallback)

    # Optional fair comparison: scale the shorter panel to the taller one's
    # height so the two are shown at the same size. Off by default - the
    # pixels are then pasted exactly as they decoded.
    height = max(size_a[1], size_b[1])
    if match_height and height > 0:
        if panel_a is not None and panel_a.height != height:
            panel_a = panel_a.resize(
                (max(1, round(panel_a.width * height / panel_a.height)), height),
                Image.LANCZOS,
            )
        if panel_b is not None and panel_b.height != height:
            panel_b = panel_b.resize(
                (max(1, round(panel_b.width * height / panel_b.height)), height),
                Image.LANCZOS,
            )

    width_a = panel_width(size_a, panel_a, match_height)
    width_b = panel_width(size_b, panel_b, match_height)
    height = max(
        height,
        panel_a.height if panel_a is not None else 0,
        panel_b.height if panel_b is not None else 0,
    )

    canvas = Image.new("RGB", (width_a + gap + width_b, height), BLACK)
    if panel_a is not None:
        canvas.paste(panel_a, (0, (height - panel_a.height) // 2))
    if panel_b is not None:
        canvas.paste(panel_b, (width_a + gap, (height - panel_b.height) // 2))
    buf = io.BytesIO()
    canvas.save(buf, format="JPEG", quality=JPEG_QUALITY)
    size = canvas.size

    for img in (panel_a, panel_b, canvas):
        if img is not None:
            img.close()
    return buf.getvalue(), panel_a is not None, panel_b is not None, size


def human_bytes(count):
    size = float(count)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024 or unit == "TiB":
            return f"{int(size)} B" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    raise AssertionError("unreachable")


def build_parser():
    parser = argparse.ArgumentParser(
        description="Flip the same DQT and/or DHT bit in two JPEGs and write "
                    "one side-by-side composite per bit: A on the left, B on "
                    "the right. A side that no longer decodes is black; a bit "
                    "that breaks both sides writes nothing.",
    )
    parser.add_argument("image_a", help="path to the left .jpg")
    parser.add_argument("image_b", help="path to the right .jpg")
    parser.add_argument(
        "-r", "--region",
        choices=("dqt", "dht", "both"),
        default="both",
        help="which table to sweep: dqt, dht or both (default: both)",
    )
    parser.add_argument(
        "-o", "--out",
        default=None,
        help="batch folder (default: <A>_vs_<B> next to file A)",
    )
    parser.add_argument(
        "-w", "--dqt-width",
        type=int,
        default=DEFAULT_DQT_WIDTH,
        metavar="PX",
        help=f"width the DQT sweep downsizes both files to before flipping "
             f"(default: {DEFAULT_DQT_WIDTH}; 0 glitches the originals)",
    )
    parser.add_argument(
        "--quality",
        type=int,
        default=DEFAULT_SCALE_QUALITY,
        metavar="N",
        help=f"JPEG quality of those downscaled copies "
             f"(default: {DEFAULT_SCALE_QUALITY})",
    )
    parser.add_argument(
        "--gap",
        type=int,
        default=DEFAULT_GAP_PX,
        metavar="PX",
        help=f"black seam between the two panels (default: {DEFAULT_GAP_PX}, 0 for none)",
    )
    parser.add_argument(
        "--match-height",
        action="store_true",
        help="scale the shorter panel to the taller one's height so both are "
             "shown at the same size (default: paste the pixels as decoded)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the plan and the first few names, write nothing",
    )
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="leave files that are already there alone (resume an interrupted run)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        metavar="N",
        help="try at most N bits (fewer files are written when both sides break)",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help=f"keep going past {human_bytes(BIG_BATCH_BYTES)} of output "
             f"instead of stopping",
    )
    return parser


def read_source(path):
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as exc:
        print(f"error: cannot read '{path}': {exc}", file=sys.stderr)
        return None
    if not data.startswith(b"\xff\xd8"):
        print(f"error: '{path}' does not start with a JPEG SOI marker", file=sys.stderr)
        return None
    return data


def main(argv=None):
    args = build_parser().parse_args(argv)

    if args.limit is not None and args.limit <= 0:
        print("error: --limit must be a positive number of bits", file=sys.stderr)
        return 1
    if args.gap < 0:
        print("error: --gap cannot be negative", file=sys.stderr)
        return 1
    if args.dqt_width < 0:
        print("error: --dqt-width cannot be negative (0 sweeps the originals)",
              file=sys.stderr)
        return 1
    if not 1 <= args.quality <= 100:
        print("error: --quality must be between 1 and 100", file=sys.stderr)
        return 1

    raw_a = read_source(args.image_a)
    raw_b = read_source(args.image_b)
    if raw_a is None or raw_b is None:
        return 1

    keys = REGION_KEYS if args.region == "both" else (args.region.upper(),)

    # What each sweep actually flips bits in. The DQT sweep moves to
    # downscaled copies of both files - one quantization value changing is
    # easy to miss at full camera resolution - while DHT stays on the
    # files as they are.
    plan = {}
    if "DQT" in keys and args.dqt_width > 0:
        built_a = build_dqt_source(args.image_a, raw_a, args.dqt_width, args.quality)
        built_b = build_dqt_source(args.image_b, raw_b, args.dqt_width, args.quality)
        if built_a is None or built_b is None:
            return 1
        scaled_a, size_a, changed_a = built_a
        scaled_b, size_b, changed_b = built_b
        plan["DQT"] = {
            "raw_a": scaled_a, "raw_b": scaled_b,
            "size_a": size_a, "size_b": size_b,
            "width_tag": args.dqt_width if (changed_a or changed_b) else None,
            "changed_a": changed_a, "changed_b": changed_b,
        }
    elif "DQT" in keys:
        plan["DQT"] = {"raw_a": raw_a, "raw_b": raw_b, "width_tag": None}
    if "DHT" in keys:
        plan["DHT"] = {"raw_a": raw_a, "raw_b": raw_b, "width_tag": None}

    for key, entry in plan.items():
        entry.setdefault("changed_a", False)
        entry.setdefault("changed_b", False)
        entry.setdefault("size_a", probe_size(entry["raw_a"]))
        entry.setdefault("size_b", probe_size(entry["raw_b"]))
        entry["regions_a"] = parse_jpeg(bytearray(entry["raw_a"]))
        entry["regions_b"] = parse_jpeg(bytearray(entry["raw_b"]))

    for key in keys:
        size_a = region_size(plan[key]["regions_a"][key])
        size_b = region_size(plan[key]["regions_b"][key])
        if size_a == 0 or size_b == 0:
            which = args.image_a if size_a == 0 else args.image_b
            print(f"error: no {key} payload bytes in '{which}'", file=sys.stderr)
            return 1

    targets = pair_targets(plan, keys, limit=args.limit)
    if not targets:
        print("error: the two files share no table bits to sweep", file=sys.stderr)
        return 1

    stem_a = os.path.splitext(os.path.basename(args.image_a))[0]
    stem_b = os.path.splitext(os.path.basename(args.image_b))[0]
    folder = args.out or os.path.join(
        os.path.dirname(os.path.abspath(args.image_a)), f"{stem_a}_vs_{stem_b}"
    )

    probed_a, probed_b = probe_size(raw_a), probe_size(raw_b)
    print(f"left  (a)    : {args.image_a} ({len(raw_a)} bytes"
          + (f", {probed_a[0]}x{probed_a[1]}" if probed_a else "") + ")")
    print(f"right (b)    : {args.image_b} ({len(raw_b)} bytes"
          + (f", {probed_b[0]}x{probed_b[1]}" if probed_b else "") + ")")

    for key in keys:
        entry = plan[key]
        size_a = region_size(entry["regions_a"][key])
        size_b = region_size(entry["regions_b"][key])
        shared = min(size_a, size_b) * 8
        note = "" if size_a == size_b else "  <- different sizes, sweeping the shorter"
        print(f"{key:<13}: a {size_a} B / b {size_b} B payload -> {shared} shared bits"
              + note)
        if size_a != size_b:
            print(f"               bit N is the N-th bit of each file's own {key},"
                  f" not a shared offset")
        if key == "DQT" and entry["width_tag"]:
            for side, name, changed, size in (
                ("a", args.image_a, entry["changed_a"], entry["size_a"]),
                ("b", args.image_b, entry["changed_b"], entry["size_b"]),
            ):
                origin = probe_size(raw_a if side == "a" else raw_b)
                verb = "downscaled" if changed else "already small enough"
                dims = f"{origin[0]}x{origin[1]} -> {size[0]}x{size[1]}" if changed \
                    else f"{origin[0]}x{origin[1]}" if origin else "?"
                print(f"               DQT runs on {side} ({name}): {verb} "
                      f"{dims}, q{args.quality}")
        elif key == "DQT":
            why = "-w 0" if args.dqt_width == 0 else \
                f"both already at or below {args.dqt_width} px"
            print(f"               DQT runs on the originals ({why})")

    print(f"bits to try  : {len(targets)} (both sides decoded for each)")
    print(f"output       : {folder}")
    print(f"layout       : a + {args.gap}px black seam + b, painted black where a "
          f"side fails to decode")
    if args.match_height:
        print("               panels scaled to a common height (--match-height)")
    if args.limit is not None:
        print("               (capped by --limit; the rest is left for another run)")

    if args.dry_run:
        print("\ndry run - nothing written, nothing decoded. First names:")
        for region, bit_index, _, _, _, _ in targets[:5]:
            print(f"  {pair_output_name(stem_a, stem_b, region, bit_index, plan[region]['width_tag'])}")
        if len(targets) > 5:
            print(f"  ... and {len(targets) - 5} more")
        for key, entry in plan.items():
            if entry["width_tag"]:
                print(f"  (downscaled {key} sources would be written as "
                      f"{stem_a}_{entry['width_tag']}w.jpg / {stem_b}_{entry['width_tag']}w.jpg)")
        print(f"\n(composite size depends on content; the run stops after "
              f"{human_bytes(BIG_BATCH_BYTES)} unless --yes)")
        return 0

    try:
        os.makedirs(folder, exist_ok=True)
    except OSError as exc:
        print(f"error: cannot create '{folder}': {exc}", file=sys.stderr)
        return 1

    # Keep the exact bytes the DQT offsets refer to, so a manifest row can
    # always be reproduced.
    for key, entry in plan.items():
        if not entry["width_tag"]:
            continue
        for stem, side in ((stem_a, "a"), (stem_b, "b")):
            if entry[f"changed_{side}"]:
                path = os.path.join(folder, f"{stem}_{entry['width_tag']}w.jpg")
                try:
                    with open(path, "wb") as f:
                        f.write(entry[f"raw_{side}"])
                    print(f"saved {key} source: {os.path.basename(path)}")
                except OSError as exc:
                    print(f"warning: cannot save '{path}': {exc}", file=sys.stderr)

    manifest_path = os.path.join(folder, MANIFEST_NAME)
    written = dropped_both = black_a = black_b = skipped = 0
    total_bytes = 0
    progress_step = max(1, len(targets) // 20)

    try:
        manifest = open(manifest_path, "w", newline="", encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot write '{manifest_path}': {exc}", file=sys.stderr)
        return 1

    with manifest:
        writer = csv.DictWriter(manifest, fieldnames=MANIFEST_FIELDS)
        writer.writeheader()

        for index, target in enumerate(targets, start=1):
            region, bit_index = target[0], target[1]
            entry = plan[region]
            raw_a_used, raw_b_used = entry["raw_a"], entry["raw_b"]
            name = pair_output_name(
                stem_a, stem_b, region, bit_index, entry["width_tag"]
            )
            path = os.path.join(folder, name)

            row = bit_row(raw_a_used, raw_b_used, target)
            row["source"] = f"{entry['width_tag']}w" if entry["width_tag"] else "original"
            encoded = None

            if args.skip_existing and os.path.exists(path):
                status = "skipped-existing"
                skipped += 1
            else:
                encoded, ok_a, ok_b, size = render_frame(
                    raw_a_used, raw_b_used, target, args.gap,
                    entry["size_a"], entry["size_b"],
                    match_height=args.match_height,
                )
                row["a_decodes"] = "yes" if ok_a else "no"
                row["b_decodes"] = "yes" if ok_b else "no"
                if size is not None:
                    row["canvas_width"], row["canvas_height"] = size

                if encoded is None:
                    # Both sides broke: no file at all for this bit.
                    status = "skipped-both-broken"
                    dropped_both += 1
                else:
                    try:
                        with open(path, "wb") as f:
                            f.write(encoded)
                    except OSError as exc:
                        print(f"\nerror: cannot write '{path}': {exc}",
                              file=sys.stderr)
                        return 1
                    status = "written"
                    written += 1
                    total_bytes += len(encoded)
                    if not ok_a:
                        black_a += 1
                    if not ok_b:
                        black_b += 1

            row["index"] = index
            row["filename"] = name
            row["bytes"] = len(encoded) if encoded is not None else 0
            row["status"] = status
            writer.writerow(row)

            if index % progress_step == 0 or index == len(targets):
                print(f"  {index}/{len(targets)} tried, {written} kept "
                      f"({human_bytes(total_bytes)}), {dropped_both} both-broken")

            if total_bytes > BIG_BATCH_BYTES and not args.yes:
                print(f"\nstopping: {human_bytes(total_bytes)} written, over the "
                      f"{human_bytes(BIG_BATCH_BYTES)} guard", file=sys.stderr)
                print(f"the {written} file(s) so far are kept - finish with "
                      f"--skip-existing --yes, or use --limit / --region dht",
                      file=sys.stderr)
                print(f"manifest: {manifest_path}")
                return 1

    print(f"\ntried {len(targets)} bit(s): wrote {written} file(s) "
          f"({human_bytes(total_bytes)}) to {folder}")
    print(f"black panels : left {black_a}, right {black_b}")
    print(f"dropped      : {dropped_both} bit(s) where both sides stopped decoding")
    if skipped:
        print(f"left {skipped} existing file(s) alone (--skip-existing)")
    print(f"manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
