#!/usr/bin/env python3
"""Side-by-side multi-image glitcher for the JPEG header tables (DQT / DHT).

Takes N JPEGs, walks the DQT (and/or DHT) one bit at a time and flips that
same bit in *every* file. Each bit gets one output: a single JPEG holding
all N results side by side (panel 1 leftmost, in the order the files were
given), so the same corruption can be compared across the whole set.

"The same bit" is the n-th bit of the selected table in each file, not the
same absolute file offset. JPEGs rarely put their DQT/DHT at the same
offset, so a shared file offset would corrupt unrelated bytes; the n-th
table bit means the same thing in every file. When the files do share a
layout (same encoder, same segment order) the n-th table bit is also the
same absolute bit, so both readings agree. The sweep covers the shortest
table on the command line - if the tables differ in size, Bit N is still
the same position in each file's own table. Bits are counted byte by byte,
LSB first (bit 0 = the 1s bit, matching `1 << bit` in jpg_glitch.py), and
only table payload is touched: marker and segment length bytes are never
flipped, and neither is the EOI marker.

Sweeps run on downsized copies of every file by default (1080 px wide):
a single bit flipping in a header table is easy to miss at full camera
resolution, and it shows on a smaller frame. Files wider than the target
are decoded, resized with LANCZOS and re-encoded at q92; files already at
or below it are used untouched. --no-downsize sweeps the originals, and
-w/--width changes the target. One copy per input is written next to the
batch, and the manifest rows describe *those* bytes, not the originals.

Because the copies are re-encoded, their tables are the encoder's, not
your camera's: the sweep explores that standard table set (which is the
point - the tables are compact and every entry is live), not the tables
stored in the original files.

Decoding uses the viewer's own `decode`, so "decodable" means exactly what
jpg_glitch.py means by it. Panels are all-or-nothing: if every file still
decodes the frame shows the whole set, and if *any* file fails to decode
the frame is written entirely black (status `written-black` in the
manifest, which still records which panels broke). A bit that breaks
*every* file produces no output at all.

Output goes to <stem1>_x<N>/ next to the first file unless -o/--out says
otherwise, named <stem1>_x<N>_<REGION>_bit<M>.jpg - plus the width, as in
<stem1>_x3_DHT_1080w_bit<M>.jpg, when the sweep ran on downsized copies, so
runs at different widths never collide in one folder. The batch folder
holds two indexes:

    sources.csv   one row per panel: which file, what bytes were used, and
                  the name of the saved downsized copy
    manifest.csv  one row per bit: the output name, the byte offset and bit
                  each panel actually had flipped, and which panels decoded

The `source` column in manifest.csv names the sweep (1080w or original), so
it is the same for every panel of a row even when only some files needed
downsizing; sources.csv carries that per-panel detail. Rows skipped by
--skip-existing are not re-decoded, so their decode and canvas columns are
left empty and only the size on disk is recorded.

Note: EXIF orientation is not applied, same as jpg_glitch.py - a photo
whose orientation lives in an EXIF tag renders in its stored orientation.
That only affects files used as-is; the downsized copies are re-encoded
and carry no EXIF. Composites are re-encoded too (a display artifact, not
a glitch output), so JPEG_QUALITY below applies to them.

Examples:
    python jpg_batch_glitch.py a.jpg b.jpg                # 2 panels, DQT + DHT
    python jpg_batch_glitch.py a.jpg b.jpg c.jpg -r dht   # 3 panels, DHT only
    python jpg_batch_glitch.py *.jpg -w 720 --columns 3   # 720 px, 3 per row
    python jpg_batch_glitch.py a.jpg b.jpg --no-downsize  # sweep the originals
    python jpg_batch_glitch.py a.jpg b.jpg --dry-run     # plan, write nothing
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
SOURCES_NAME = "sources.csv"
DEFAULT_GAP_PX = 8  # black seam between panels
JPEG_QUALITY = 95  # for the composite only
BLACK = (0, 0, 0)

# Sweeps run on copies downsized to this width unless --no-downsize says
# otherwise.
DEFAULT_WIDTH = 1080
DEFAULT_SCALE_QUALITY = 92  # for those copies

# A composite is roughly all the sources side by side, so a full DHT sweep
# on phone photos is easily tens of GB. The run stops at this much written
# output unless --yes says otherwise; the partial batch is kept and can be
# finished with --skip-existing --yes.
BIG_BATCH_BYTES = 2 * 1024 ** 3

SOURCES_FIELDS = (
    "panel",
    "path",
    "stem",
    "bytes",
    "width",
    "height",
    "source",
    "saved_as",
    "downscaled",
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


def multi_targets(plan, keys, limit=None):
    """Pair up the bits of every panel for each region, in file order.

    Returns [(region, bit_index, [(off, bit), ...per panel]), ...] where
    bit_index counts bits inside the region, so it is the same position in
    every file. The sweep is the shortest table of the set, and `limit`
    caps the number of bits tried. Ranges come from the bytes `plan` holds
    for that region - the downscaled copies for DQT.
    """
    targets = []
    for key in keys:
        entry = plan[key]
        iterators = [bits_in_ranges(regions[key]) for regions in entry["regions"]]
        for bit_index, combo in enumerate(zip(*iterators)):
            targets.append((key, bit_index, combo))
            if limit is not None and len(targets) >= limit:
                return targets
    return targets


def output_name(stem, count, region, bit_index, width=None):
    """One composite per bit; the name carries the bit that was flipped.

    `width` marks a sweep that ran on downscaled copies, so runs at
    different widths cannot overwrite each other in one folder.
    """
    tag = f"_{width}w" if width else ""
    return f"{stem}_x{count}_{region}{tag}_bit{bit_index:05d}.jpg"


def try_decode(data):
    """The decoded image, or None when the bytes no longer decode."""
    try:
        return decode(data)
    except Exception:  # noqa: BLE001 - the decoder signals many exception types
        return None


def downscale(raw, target_width, quality):
    """A JPEG copy of `raw` at most `target_width` px wide.

    Returns (bytes, (w, h), changed). Files already at or below the target
    are handed back untouched, decided from the header alone - there is
    nothing to downsize, re-encoding them would only add artifacts, and a
    file that no longer decodes can still be swept (its frame comes out
    black). Only a file that actually has to be resized must decode, and
    that raises when it cannot.
    """
    size = probe_size(raw)
    if size is not None and size[0] <= target_width:
        return raw, size, False

    with Image.open(io.BytesIO(raw)) as opened:
        opened.load()
        img = opened.convert("RGB")
    try:
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


def build_source(path, raw, target_width, quality):
    """The bytes a sweep flips bits in: (bytes, (w, h), changed).

    Returns None (after reporting why) when a file that has to be resized
    cannot be decoded - one panel at full size next to the rest downscaled
    would make the comparison meaningless, so the run stops instead.
    """
    try:
        return downscale(raw, target_width, quality)
    except Exception as exc:  # noqa: BLE001 - decoder may raise many kinds
        print(f"error: cannot downsize '{path}' ({exc}).\n"
              f"       a file wider than {target_width} px must decode to be "
              f"resized; pass --no-downsize to sweep the originals instead",
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


def bit_row(raws, target):
    """The manifest row for one bit, before anything is decoded."""
    region, bit_index, combo = target
    row = {"region": region, "bit_index": bit_index}
    for i, (raw, (offset, bit)) in enumerate(zip(raws, combo), start=1):
        row[f"p{i}_byte_offset"] = offset
        row[f"p{i}_byte_offset_hex"] = f"0x{offset:x}"
        row[f"p{i}_bit"] = bit
        row[f"p{i}_byte_before"] = raw[offset]
        row[f"p{i}_byte_after"] = raw[offset] ^ (1 << bit)
        row[f"p{i}_decodes"] = ""
    return row


def panel_slots(panels, sizes):
    """Slot size per panel: its decoded size, else the source header's.

    At least one panel decoded, so a panel with neither still gets a shape
    to paint black.
    """
    known = next((p.size for p in panels if p is not None), None)
    return [
        panel.size if panel is not None else (sizes[i] or known)
        for i, panel in enumerate(panels)
    ]


def layout(panels, slots, gap, columns):
    """Paste `panels` into a black canvas, `columns` of them per row.

    Returns (canvas, [(width, height), ...]) - the canvas plus the slot
    each panel occupied, so a caller can check where the black areas are.
    None panels are simply left black.
    """
    per_row = columns if columns and columns > 0 else len(panels)
    rows = [list(range(i, min(i + per_row, len(panels))))
            for i in range(0, len(panels), per_row)]

    widths = [
        panel.width if panel is not None else slots[i][0]
        for i, panel in enumerate(panels)
    ]
    heights = [
        panel.height if panel is not None else slots[i][1]
        for i, panel in enumerate(panels)
    ]

    row_widths = [sum(widths[i] for i in row) + gap * (len(row) - 1) for row in rows]
    row_heights = [max(heights[i] for i in row) for row in rows]
    canvas = Image.new(
        "RGB",
        (max(row_widths), sum(row_heights) + gap * (len(rows) - 1)),
        BLACK,
    )

    occupied = [None] * len(panels)
    y = 0
    for row, row_height in zip(rows, row_heights):
        x = 0
        for i in row:
            top = y + (row_height - heights[i]) // 2
            if panels[i] is not None:
                canvas.paste(panels[i], (x, top))
            occupied[i] = (x, top, widths[i], heights[i])
            x += widths[i] + gap
        y += row_height + gap
    return canvas, occupied


def render_frame(raws, target, gap, sizes, match_height=False, columns=0):
    """Build the side-by-side composite for one bit.

    Returns (encoded_jpeg_bytes or None, [decoded flags], canvas_size).
    The bytes are None exactly when every panel fails to decode - the one
    case that produces no file. When *some* panels fail the frame is
    written entirely black: a half-readable comparison invites misreading a
    bit that only one file survived, so an invalid bit looks invalid.
    """
    _, _, combo = target
    panels = [try_decode(flip(raw, offset, bit))
              for raw, (offset, bit) in zip(raws, combo)]
    ok = [panel is not None for panel in panels]

    if not any(ok):
        return None, ok, None

    slots = panel_slots(panels, sizes)

    # Optional fair comparison: scale every panel that decoded to the
    # tallest slot, so the set is shown at one size. Off by default - the
    # pixels are then pasted exactly as they decoded.
    if match_height:
        height = max(slot[1] for slot in slots)
        if height > 0:
            for i, panel in enumerate(panels):
                if panel is not None and panel.height != height:
                    panels[i] = panel.resize(
                        (max(1, round(panel.width * height / panel.height)), height),
                        Image.LANCZOS,
                    )
                    panel.close()
            slots = [
                (panels[i].width if panels[i] is not None else slots[i][0], height)
                for i in range(len(panels))
            ]

    # One bad panel invalidates the whole frame, but the canvas keeps the
    # size the composite would have had, so black frames do not jump.
    drawn = panels if all(ok) else [None] * len(panels)
    canvas, _ = layout(drawn, slots, gap, columns)
    buf = io.BytesIO()
    canvas.save(buf, format="JPEG", quality=JPEG_QUALITY)
    size = canvas.size

    for panel in panels:
        if panel is not None:
            panel.close()
    canvas.close()
    return buf.getvalue(), ok, size


def plan_saved_names(stems, width_tag, changed):
    """Name of the saved downscaled copy per panel, or None when unchanged.

    Computed once per run so the file that gets written, the dry-run
    listing and sources.csv can never disagree. Two inputs sharing a stem
    would collide, so those copies carry a panel prefix.
    """
    if not width_tag:
        return [None] * len(stems)
    names, used = [], set()
    for i, stem in enumerate(stems):
        if not changed[i]:
            names.append(None)
            continue
        base = f"{stem}_{width_tag}w.jpg"
        name = f"p{i + 1}_{base}" if stems.count(stem) > 1 else base
        if name in used:  # defensive: never let one copy overwrite another
            name = f"p{i + 1}_{base}"
        used.add(name)
        names.append(name)
    return names


def human_bytes(count):
    size = float(count)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024 or unit == "TiB":
            return f"{int(size)} B" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    raise AssertionError("unreachable")


def manifest_fields(count):
    """Per-panel columns for `count` panels, then the shared columns."""
    fields = ["index", "filename", "region", "bit_index", "source", "status"]
    for i in range(1, count + 1):
        fields += [f"p{i}_byte_offset", f"p{i}_byte_offset_hex", f"p{i}_bit",
                   f"p{i}_byte_before", f"p{i}_byte_after", f"p{i}_decodes"]
    fields += ["bytes", "canvas_width", "canvas_height"]
    return tuple(fields)


def build_parser():
    parser = argparse.ArgumentParser(
        description="Flip the same DQT and/or DHT bit in N JPEGs and write one "
                    "side-by-side composite per bit, panels in the order the "
                    "files were given. If any panel fails to decode the whole "
                    "frame is black; if every panel fails nothing is written.",
    )
    parser.add_argument(
        "images",
        nargs="+",
        metavar="IMAGE",
        help="two or more .jpg files, left to right",
    )
    parser.add_argument(
        "-r", "--region",
        choices=("dqt", "dht", "both"),
        default="both",
        help="which table to sweep: dqt, dht or both (default: both)",
    )
    parser.add_argument(
        "-o", "--out",
        default=None,
        help="batch folder (default: <stem1>_x<N> next to the first file)",
    )
    parser.add_argument(
        "--downsize",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="sweep downscaled copies of every file, so a bit flip in a header "
             "table is visible on a smaller frame (default: on; --no-downsize "
             "sweeps the originals)",
    )
    parser.add_argument(
        "-w", "--width", "--dqt-width",
        dest="width",
        type=int,
        default=DEFAULT_WIDTH,
        metavar="PX",
        help=f"width those copies are downsized to (default: {DEFAULT_WIDTH})",
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
        help=f"black seam between panels (default: {DEFAULT_GAP_PX}, 0 for none)",
    )
    parser.add_argument(
        "-c", "--columns",
        type=int,
        default=0,
        metavar="N",
        help="panels per row (default: all of them in one row)",
    )
    parser.add_argument(
        "--match-height",
        action="store_true",
        help="scale every panel to the tallest one's height so all are shown "
             "at the same size (default: paste the pixels as decoded)",
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
        help="try at most N bits (fewer files are written when every panel breaks)",
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
    paths = args.images
    count = len(paths)

    if count < 2:
        print("error: give at least two images to compare", file=sys.stderr)
        return 1
    if args.limit is not None and args.limit <= 0:
        print("error: --limit must be a positive number of bits", file=sys.stderr)
        return 1
    if args.gap < 0:
        print("error: --gap cannot be negative", file=sys.stderr)
        return 1
    if args.columns < 0:
        print("error: --columns cannot be negative", file=sys.stderr)
        return 1
    if args.width < 1:
        print("error: --width must be at least 1 px (use --no-downsize to sweep "
              "the originals)", file=sys.stderr)
        return 1
    if not 1 <= args.quality <= 100:
        print("error: --quality must be between 1 and 100", file=sys.stderr)
        return 1

    raws = [read_source(path) for path in paths]
    if any(raw is None for raw in raws):
        return 1

    keys = REGION_KEYS if args.region == "both" else (args.region.upper(),)
    stems = [os.path.splitext(os.path.basename(path))[0] for path in paths]

    # One source set feeds every swept region: downscaled copies by default
    # (a single bit in a header table is easy to miss at full camera
    # resolution, and it shows on a smaller frame), the originals with
    # --no-downsize. DQT and DHT therefore always see the same bytes.
    if args.downsize:
        built = [build_source(path, raw, args.width, args.quality)
                 for path, raw in zip(paths, raws)]
        if any(item is None for item in built):
            return 1
        used_raws = [item[0] for item in built]
        used_sizes = [item[1] for item in built]
        changed = [item[2] for item in built]
        width_tag = args.width if any(changed) else None
    else:
        used_raws = list(raws)
        used_sizes = [probe_size(raw) for raw in raws]
        changed = [False] * count
        width_tag = None

    saved_names = plan_saved_names(stems, width_tag, changed)
    parsed = [parse_jpeg(bytearray(raw)) for raw in used_raws]
    plan = {
        key: {
            "raws": used_raws,
            "sizes": used_sizes,
            "changed": changed,
            "width_tag": width_tag,
            "regions": parsed,
            "saved_names": saved_names,
        }
        for key in keys
    }

    for key in keys:
        for i, regions in enumerate(plan[key]["regions"]):
            if region_size(regions[key]) == 0:
                print(f"error: no {key} payload bytes in '{paths[i]}'",
                      file=sys.stderr)
                return 1

    targets = multi_targets(plan, keys, limit=args.limit)
    if not targets:
        print("error: the images share no table bits to sweep", file=sys.stderr)
        return 1

    folder = args.out or os.path.join(
        os.path.dirname(os.path.abspath(paths[0])), f"{stems[0]}_x{count}"
    )

    print(f"images       : {count}")
    for i, (path, raw) in enumerate(zip(paths, raws), start=1):
        size = probe_size(raw)
        print(f"  p{i}         : {path} ({len(raw)} bytes"
              + (f", {size[0]}x{size[1]}" if size else "") + ")")

    for key in keys:
        entry = plan[key]
        sizes = [region_size(regions[key]) for regions in entry["regions"]]
        shared = min(sizes) * 8
        note = "" if len(set(sizes)) == 1 else \
            "  <- tables differ, sweeping the shortest"
        print(f"{key:<13}: payload {'/'.join(str(s) for s in sizes)} B "
              f"-> {shared} shared bits" + note)
        if len(set(sizes)) != 1:
            print(f"               bit N is the N-th bit of each file's own {key},"
                  f" not a shared offset")

    if plan[keys[0]]["width_tag"]:
        print(f"sources      : downscaled to {args.width} px wide, q{args.quality}")
        for i, raw in enumerate(raws, start=1):
            origin = probe_size(raw)
            if changed[i - 1]:
                dims = f"{origin[0]}x{origin[1]} -> " \
                       f"{used_sizes[i-1][0]}x{used_sizes[i-1][1]}"
                verb = "downsized"
            else:
                dims = f"{origin[0]}x{origin[1]}" if origin else "?"
                verb = "already narrow, kept"
            print(f"               p{i}: {verb} {dims}")
    else:
        why = "--no-downsize" if not args.downsize else \
            f"all files already at or below {args.width} px"
        print(f"sources      : the originals ({why})")

    per_row = args.columns if args.columns > 0 else count
    print(f"bits to try  : {len(targets)} (every panel decoded for each)")
    print(f"output       : {folder}")
    print(f"layout       : {count} panel(s), {per_row} per row, "
          f"{args.gap}px black seams; a frame with any failed panel is all black")
    if args.match_height:
        print("               panels scaled to a common height (--match-height)")
    if args.limit is not None:
        print("               (capped by --limit; the rest is left for another run)")

    if args.dry_run:
        print("\ndry run - nothing written, nothing decoded. First names:")
        for region, bit_index, _ in targets[:5]:
            print("  " + output_name(stems[0], count, region, bit_index,
                                      plan[region]["width_tag"]))
        if len(targets) > 5:
            print(f"  ... and {len(targets) - 5} more")
        names = [name for name in saved_names if name]
        if names:
            print(f"  (the downsized sources would be written as "
                  f"{', '.join(names)})")
        print(f"\n(composite size depends on content; the run stops after "
              f"{human_bytes(BIG_BATCH_BYTES)} unless --yes)")
        return 0

    try:
        os.makedirs(folder, exist_ok=True)
    except OSError as exc:
        print(f"error: cannot create '{folder}': {exc}", file=sys.stderr)
        return 1

    # Keep the exact bytes the manifest offsets refer to, so a row can
    # always be reproduced. One copy per panel: every region shares them.
    for i, name in enumerate(saved_names):
        if not name:
            continue
        path = os.path.join(folder, name)
        try:
            with open(path, "wb") as f:
                f.write(used_raws[i])
            print(f"saved source: {name}")
        except OSError as exc:
            print(f"warning: cannot save '{path}': {exc}", file=sys.stderr)

    sources_path = os.path.join(folder, SOURCES_NAME)
    manifest_path = os.path.join(folder, MANIFEST_NAME)

    try:
        sources_file = open(sources_path, "w", newline="", encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot write '{sources_path}': {exc}", file=sys.stderr)
        return 1
    with sources_file:
        writer = csv.DictWriter(sources_file, fieldnames=SOURCES_FIELDS)
        writer.writeheader()
        for i, (path, raw, stem) in enumerate(zip(paths, raws, stems), start=1):
            saved_as = saved_names[i - 1] or ""
            downscaled = bool(saved_as)
            size = probe_size(raw)
            writer.writerow({
                "panel": i,
                "path": path,
                "stem": stem,
                "bytes": len(raw),
                "width": size[0] if size else "",
                "height": size[1] if size else "",
                "source": f"{width_tag}w" if downscaled else "original",
                "saved_as": saved_as,
                "downscaled": "yes" if downscaled else "no",
            })

    written = dropped_all = skipped = black_frames = 0
    failed = [0] * count
    total_bytes = 0
    progress_step = max(1, len(targets) // 20)

    try:
        manifest = open(manifest_path, "w", newline="", encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot write '{manifest_path}': {exc}", file=sys.stderr)
        return 1

    with manifest:
        fields = manifest_fields(count)
        writer = csv.DictWriter(manifest, fieldnames=fields)
        writer.writeheader()

        for index, target in enumerate(targets, start=1):
            region, bit_index, _ = target
            entry = plan[region]
            name = output_name(stems[0], count, region, bit_index, entry["width_tag"])
            path = os.path.join(folder, name)

            row = bit_row(entry["raws"], target)
            row["source"] = f"{entry['width_tag']}w" if entry["width_tag"] else "original"
            encoded = None

            if args.skip_existing and os.path.exists(path):
                status = "skipped-existing"
                skipped += 1
                # Not re-decoded this run: the decode and canvas columns stay
                # empty, but the row still points at the file on disk.
                try:
                    written_bytes = os.path.getsize(path)
                except OSError:
                    written_bytes = 0
            else:
                encoded, ok, size = render_frame(
                    entry["raws"], target, args.gap, entry["sizes"],
                    match_height=args.match_height, columns=args.columns,
                )
                written_bytes = 0
                for i, decoded in enumerate(ok, start=1):
                    row[f"p{i}_decodes"] = "yes" if decoded else "no"
                if size is not None:
                    row["canvas_width"], row["canvas_height"] = size

                if encoded is None:
                    # Every panel broke: no file at all for this bit.
                    status = "skipped-all-broken"
                    dropped_all += 1
                else:
                    try:
                        with open(path, "wb") as f:
                            f.write(encoded)
                    except OSError as exc:
                        print(f"\nerror: cannot write '{path}': {exc}",
                              file=sys.stderr)
                        return 1
                    # A frame with any panel broken is written entirely black.
                    status = "written" if all(ok) else "written-black"
                    if not all(ok):
                        black_frames += 1
                    written += 1
                    written_bytes = len(encoded)
                    total_bytes += len(encoded)
                    for i, decoded in enumerate(ok):
                        if not decoded:
                            failed[i] += 1

            row["index"] = index
            row["filename"] = name
            row["bytes"] = written_bytes
            row["status"] = status
            writer.writerow(row)

            if index % progress_step == 0 or index == len(targets):
                print(f"  {index}/{len(targets)} tried, {written} kept "
                      f"({human_bytes(total_bytes)}), {dropped_all} all-broken")

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
    if black_frames:
        detail = ", ".join(f"p{i + 1} {n}" for i, n in enumerate(failed) if n)
        print(f"black frames : {black_frames} of them, all-black because a panel "
              f"broke ({detail})")
    print(f"dropped      : {dropped_all} bit(s) where every panel stopped decoding")
    if skipped:
        print(f"left {skipped} existing file(s) alone (--skip-existing)")
    print(f"sources      : {sources_path}")
    print(f"manifest     : {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
