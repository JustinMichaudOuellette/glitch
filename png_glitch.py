#!/usr/bin/env python3
"""PNG glitcher - flip one bit, re-decode, re-render (jpg_glitch.py style).

Reads a PNG file, decodes it and shows it in a window. The number keys
pick *what* gets corrupted; each SPACE press flips one bit in that
target and re-decodes / re-renders the image:

    1  scanline: any bit        random bit anywhere in the decoded
                                 filtered scanline data (default)
    2  scanline: filter bytes   only the 1 filter byte that starts each
                                 row -> row shifts / banding
    3  scanline: one row        one random row's pixel bytes -> a stripe
    4  scanline: MSB            only the most significant bit (bit 7)
    5  scanline: LSB            only the least significant bit (bit 0)
    6  raw IDAT file bits       literal flip inside the compressed file
                                 bytes (zlib rejects most flips, so this
                                 mode is slow and often finds nothing)
    7  palette (PLTE)           palette bytes (palette images only)
    8  aux chunks               metadata (tEXt, gAMA, ...), usually no
                                 visible change

Modes 1-5 decode IDAT to its raw filtered scanline stream, flip one bit
there, then re-compress it back into IDAT - random file-level flips in
a deflate stream break inflate ~99.99% of the time. Modes 6-8 operate
on the file bytes directly (with the affected chunk's CRC kept valid).

The signature, chunk framing and the IEND terminator are never
targeted. Candidates that fail to decode - or decode to exactly the
same picture - are rolled back and another bit is tried. (Row-aware
modes 2-3 need a non-interlaced PNG.)

Keys:
    1-8    select the corruption target
    SPACE  flip one bit in the selected target and re-render
    Z      undo the last flip (restore the previous frame)
    S      save the current bytes to <name>_glitch_N.png (next to source)
    R      reset to the original file bytes
    ESC/Q  quit
"""
import argparse
import io
import os
import random
import sys
import zlib

from PIL import Image, ImageTk
import tkinter as tk

PNG_SIG = b"\x89PNG\r\n\x1a\n"

# corruption targets (mode name -> number key + short label)
MODE_KEYS = {
    "1": "scan-any",
    "2": "scan-filter",
    "3": "scan-row",
    "4": "scan-msb",
    "5": "scan-lsb",
    "6": "raw-idat",
    "7": "PLTE",
    "8": "aux",
}
MODE_LABELS = {
    "scan-any": "scanline: any bit",
    "scan-filter": "scanline: filter bytes",
    "scan-row": "scanline: one row",
    "scan-msb": "scanline: MSB bit 7",
    "scan-lsb": "scanline: LSB bit 0",
    "raw-idat": "raw IDAT (file bits)",
    "PLTE": "palette (PLTE)",
    "aux": "aux chunks",
}
SCANLINE_MODES = {"scan-any", "scan-filter", "scan-row", "scan-msb", "scan-lsb"}

DEFAULT_MAX_PX = 1400  # long edge cap for the on-screen image
MAX_SCAN_TRIES = 400  # scanline flips: cheap, almost always visible
MAX_FAST_TRIES = 500  # PLTE/aux file-level flips
MAX_RAW_TRIES = 40_000  # raw-IDAT flips break zlib ~99.99%; huge, slow budget
IDAT_COMPRESS_LEVEL = 6  # zlib level used when re-encoding the scanline stream
MAX_DECODE_PIXELS = 50_000_000  # refuse to materialize absurd IHDR sizes


def parse_png(data):
    """Walk the PNG chunk stream.

    Returns (chunks, regions): chunks is a list of
    {type, payload:(start,end), crc:offset}; regions maps each corruptable
    category to payload ranges. IHDR and IEND are never returned.
    """
    if not data.startswith(PNG_SIG):
        raise ValueError("file does not start with a PNG signature")
    chunks = []
    off = len(PNG_SIG)
    n = len(data)
    while off + 8 <= n:
        length = int.from_bytes(data[off : off + 4], "big")
        ctype = data[off + 4 : off + 8]
        payload_off = off + 8
        crc_off = payload_off + length
        end = crc_off + 4
        if end > n:
            raise ValueError(f"truncated {ctype!r} chunk")
        chunks.append(
            {
                "type": ctype.decode("ascii", "replace"),
                "payload": (payload_off, crc_off),
                "crc": crc_off,
            }
        )
        off = end
        if ctype == b"IEND":
            break

    regions = {"PLTE": [], "IDAT": [], "aux": []}
    for chunk in chunks:
        name = chunk["type"]
        if name in regions:
            regions[name].append(chunk["payload"])
        elif name not in ("IHDR", "IEND"):
            regions["aux"].append(chunk["payload"])
    return chunks, regions


def region_size(ranges):
    return sum(end - start for start, end in ranges)


def random_bit_in_ranges(data, ranges, rng):
    """Flip one random bit inside the union of `ranges`. Mutates `data`.

    Returns (byte_offset, bit_index) or None if the region has no bytes.
    """
    total = region_size(ranges)
    if total <= 0:
        return None
    k = rng.randrange(total)  # uniform over all region bytes
    for start, end in ranges:
        span = end - start
        if k < span:
            pos = start + k
            bit = rng.randrange(8)
            data[pos] ^= 1 << bit
            return pos, bit
        k -= span
    raise AssertionError("unreachable")


def refresh_chunk_crc(data, chunk):
    """Recompute and store the CRC of one chunk after its payload changed."""
    start, end = chunk["payload"]
    crc = zlib.crc32(data[start - 4 : end]) & 0xFFFFFFFF  # over type + payload
    data[chunk["crc"] : chunk["crc"] + 4] = crc.to_bytes(4, "big")


def _inflates(data, ranges):
    """Cheap gate: does the concatenated payload still inflate?"""
    stream = b"".join(bytes(data[start:end]) for start, end in ranges)
    try:
        zlib.decompress(stream)
        return True
    except Exception:  # noqa: BLE001 - any zlib failure means "no"
        return False


def _chunk_bytes(ctype, payload):
    """Build one PNG chunk (length + type + payload + CRC) as bytes."""
    body = ctype.encode("ascii") + payload
    return len(payload).to_bytes(4, "big") + body + zlib.crc32(body).to_bytes(4, "big")


def rebuild_with_idat(data, chunks, new_stream):
    """Return the file bytes with every IDAT chunk replaced by one new IDAT.

    `chunks` must describe `data`; non-IDAT chunks are copied verbatim
    (including any file-level flips already committed in them).
    """
    out = bytearray(data[: len(PNG_SIG)])
    put_idat = False
    for chunk in chunks:
        if chunk["type"] == "IDAT":
            if not put_idat:
                out += _chunk_bytes("IDAT", new_stream)
                put_idat = True
            continue
        start = chunk["payload"][0] - 8  # chunk length field
        out += data[start : chunk["crc"] + 4]
    return bytes(out)


def idat_payload(data, chunks):
    """Concatenate the current IDAT payloads (the raw deflate stream)."""
    return b"".join(
        bytes(data[start:end])
        for chunk in chunks
        if chunk["type"] == "IDAT"
        for start, end in (chunk["payload"],)
    )


def scanline_layout(data):
    """Pixel layout from IHDR: bits-per-pixel and bytes per filtered row.

    Only valid for non-interlaced PNGs.
    """
    # IHDR is the first chunk: signature(8) length(4) type(4) payload(13)
    w = int.from_bytes(data[16:20], "big")
    h = int.from_bytes(data[20:24], "big")
    bit_depth = data[24]
    color_type = data[25]
    interlace = data[28]
    bits_per_px = {0: bit_depth, 2: 3 * bit_depth, 3: bit_depth,
                   4: 2 * bit_depth, 6: 4 * bit_depth}[color_type]
    stride = 1 + (w * bits_per_px + 7) // 8  # filter byte + row pixels
    return {
        "width": w,
        "height": h,
        "bit_depth": bit_depth,
        "color_type": color_type,
        "interlace": interlace,
        "bits_per_px": bits_per_px,
        "stride": stride,
    }


def decode(data):
    """Fully decode PNG bytes and return an RGB PIL.Image.

    Raises whatever PIL raises when the stream cannot be decoded (or a
    ValueError when the header advertises an absurd image size).
    """
    with Image.open(io.BytesIO(bytes(data))) as img:
        w, h = img.size
        if w * h > MAX_DECODE_PIXELS:
            raise ValueError(f"decoded size {w}x{h} too large to display")
        img.load()
        return img.convert("RGB")


def fit_size(width, height, screen_w, screen_h, max_px=DEFAULT_MAX_PX):
    """Scale an image size down to fit the window while keeping aspect."""
    w, h = width, height
    scale = min(1.0, max_px / max(w, h), (screen_w - 120) / w, (screen_h - 180) / h)
    if scale < 1.0:
        w, h = int(w * scale), int(h * scale)
    return w, h


class GlitchViewer:
    def __init__(self, root, data, src_path=None):
        self.root = root
        self.original = bytearray(data)
        self.data = bytearray(data)
        self.chunks, self.regions = parse_png(self.data)
        self.mode = "scan-any"  # default: random bit in decoded scanlines
        self.rng = random.SystemRandom()
        self.flips = 0
        self.undo_stack = []  # byte snapshots taken before each accepted flip
        self.save_count = 0
        self.src_path = src_path
        self.window_sized = False  # size the window to the first frame once

        root.title("PNG glitcher")
        root.configure(bg="#202020")

        self.canvas = tk.Canvas(root, bg="#202020", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

        self.status = tk.Label(
            root,
            text="",
            fg="#d0d0d0",
            bg="#202020",
            font=("Consolas", 10),
            anchor="w",
            padx=8,
            pady=4,
            justify=tk.LEFT,
        )
        self.status.pack(fill=tk.X, side=tk.BOTTOM)

        self.photo = None  # must keep a reference or tkinter drops the image
        self.canvas_image = None

        # Decode the pristine file once to validate it up front.
        self._render(decode(self.data))
        self._refresh_status("original file loaded")

        root.bind("<space>", lambda e: self.glitch_once())
        for key, mode in MODE_KEYS.items():
            root.bind(key, lambda e, m=mode: self.select_mode(m))
        root.bind("s", lambda e: self.save_now())
        root.bind("S", lambda e: self.save_now())
        root.bind("r", lambda e: self.reset())
        root.bind("R", lambda e: self.reset())
        root.bind("z", lambda e: self.undo())
        root.bind("Z", lambda e: self.undo())
        root.bind("<Escape>", lambda e: root.destroy())
        root.bind("q", lambda e: root.destroy())
        root.bind("Q", lambda e: root.destroy())
        root.focus_force()

    def _refresh_status(self, note, error=False):
        label = MODE_LABELS[self.mode]
        if self.mode in SCANLINE_MODES or self.mode == "raw-idat":
            size = self._idat_byte_count()
        else:
            size = region_size(self.regions.get(self.mode, []))
        text = (
            f"mode {self.mode} [{label}, {size} bytes] | {note} | "
            f"flips: {self.flips} | keys: 1 any 2 filter 3 row 4 MSB 5 LSB, "
            f"6 raw 7 PLTE 8 aux | SPACE flip, Z undo, S save, R reset, ESC/Q quit"
        )
        self.status.config(text=text, fg="#ff6b6b" if error else "#d0d0d0")

    def _idat_byte_count(self):
        return len(idat_payload(self.data, self.chunks))

    def select_mode(self, mode):
        self.mode = mode
        if mode in ("PLTE", "aux"):
            empty = region_size(self.regions.get(mode, [])) == 0
        else:
            empty = self._idat_byte_count() == 0
        self._refresh_status("target selected", error=empty)

    def _render(self, img):
        """Resize the PIL image to fit the screen and draw it on the canvas."""
        self.current_px = img.tobytes()  # reference for "did the picture change?"
        w, h = img.size
        disp_w, disp_h = fit_size(w, h, self.root.winfo_screenwidth(), self.root.winfo_screenheight())
        if (disp_w, disp_h) != (w, h):
            img = img.resize((disp_w, disp_h), Image.BILINEAR)
        self.photo = ImageTk.PhotoImage(img)
        self.canvas.delete("all")
        self.canvas_image = self.canvas.create_image(0, 0, image=self.photo, anchor=tk.NW)
        self.canvas.configure(scrollregion=(0, 0, disp_w, disp_h))
        if not self.window_sized:
            # Open the window at roughly the image size (status bar below).
            self.root.geometry(f"{disp_w}x{disp_h + 60}")
            self.window_sized = True

    def _chunk_at(self, pos):
        """Return the chunk whose payload contains `pos`, or None."""
        for chunk in self.chunks:
            start, end = chunk["payload"]
            if start <= pos < end:
                return chunk
        return None

    def glitch_once(self):
        """Corrupt the selected target by one bit, then re-decode/re-render."""
        if self.mode in SCANLINE_MODES:
            self._glitch_scanline()
        elif self.mode == "raw-idat":
            self._glitch_raw_idat()
        else:  # PLTE / aux - file-level flips
            ranges = self.regions.get(self.mode, [])
            if region_size(ranges) == 0:
                self._refresh_status(
                    f"no {MODE_LABELS[self.mode]} in this file", error=True
                )
                return
            self._glitch_file_level(ranges)

    def _pick_scanline_bit(self, raw, layout):
        """Choose (raw_offset, bit_index) for the current scanline mode."""
        n = len(raw)
        mode = self.mode
        if mode == "scan-filter":
            # one filter byte starts each row
            pos = self.rng.randrange(layout["height"]) * layout["stride"]
            return pos, self.rng.randrange(8)
        if mode == "scan-row":
            row = self.rng.randrange(layout["height"])
            start = row * layout["stride"] + 1  # skip the row's filter byte
            end = (row + 1) * layout["stride"]
            if end <= start:
                return None  # 1-bit image rows have no pixel bytes
            pos = self.rng.randrange(start, end)
            return pos, self.rng.randrange(8)
        pos = self.rng.randrange(n)
        if mode == "scan-msb":
            return pos, 7
        if mode == "scan-lsb":
            return pos, 0
        return pos, self.rng.randrange(8)  # scan-any

    def _glitch_scanline(self):
        """Decode IDAT, flip one bit in the raw filtered scanlines, re-encode."""
        try:
            raw = zlib.decompress(idat_payload(self.data, self.chunks))
        except Exception as exc:  # noqa: BLE001
            self._refresh_status(f"IDAT stream cannot inflate ({exc})", error=True)
            return
        if not raw:
            self._refresh_status("IDAT holds no image data", error=True)
            return
        layout = scanline_layout(self.data)
        row_aware = self.mode in ("scan-filter", "scan-row")
        if row_aware and (layout["interlace"] or len(raw) != layout["stride"] * layout["height"]):
            self._refresh_status(
                "filter/row modes need a non-interlaced PNG with intact rows - "
                "press 1/4/5 instead",
                error=True,
            )
            return
        snapshot = bytearray(self.data)  # undo point: state before this press
        for attempt in range(1, MAX_SCAN_TRIES + 1):
            picked = self._pick_scanline_bit(raw, layout)
            if picked is None:
                self._refresh_status(
                    "rows have no pixel bytes to flip (1-bit image?)", error=True
                )
                return
            i, bit = picked
            raw2 = bytearray(raw)
            raw2[i] ^= 1 << bit
            rebuilt = rebuild_with_idat(
                self.data,
                self.chunks,
                zlib.compress(bytes(raw2), IDAT_COMPRESS_LEVEL),
            )
            try:
                img = decode(rebuilt)
            except Exception:  # noqa: BLE001 - re-encoded stream is valid zlib
                continue
            if img.tobytes() == self.current_px:
                continue
            self.undo_stack.append(snapshot)
            self.data = bytearray(rebuilt)
            self.chunks, self.regions = parse_png(self.data)
            self.flips += 1
            self._render(img)
            note = f" (retried {attempt - 1} bad flip(s))" if attempt > 1 else ""
            self._refresh_status(
                f"flip #{self.flips}: byte 0x{i:x}, bit {bit}{note} "
                f"(IDAT re-encoded)"
            )
            return
        self._refresh_status(
            f"no useful bit found in {MAX_SCAN_TRIES} tries - "
            f"image unchanged, keep pressing or press R to reset",
            error=True,
        )

    def _glitch_raw_idat(self):
        """Literal bit flip inside the compressed IDAT file bytes.

        zlib rejects almost every such flip, so this is slow and often
        finds nothing usable (kept as an experimental target).
        """
        ranges = self.regions["IDAT"]
        if region_size(ranges) == 0:
            self._refresh_status("no IDAT bytes in this file", error=True)
            return
        snapshot = bytearray(self.data)  # undo point: state before this press
        for attempt in range(1, MAX_RAW_TRIES + 1):
            flipped = random_bit_in_ranges(self.data, ranges, self.rng)
            if flipped is None:
                return
            pos, bit = flipped
            if not _inflates(self.data, ranges):
                self.data[pos] ^= 1 << bit  # deflate stream broken - cheap reject
                continue
            try:
                img = decode(self.data)
            except Exception:  # noqa: BLE001 - decoder may raise many kinds
                self.data[pos] ^= 1 << bit
                continue
            if img.tobytes() == self.current_px:
                self.data[pos] ^= 1 << bit
                continue
            chunk = self._chunk_at(pos)
            if chunk is not None:
                refresh_chunk_crc(self.data, chunk)
            self.undo_stack.append(snapshot)
            self.flips += 1
            self._render(img)
            note = f" (retried {attempt - 1} bad flip(s))" if attempt > 1 else ""
            self._refresh_status(
                f"flip #{self.flips}: file byte 0x{pos:x}, bit {bit}{note}"
            )
            return
        self._refresh_status(
            f"no useful bit found in {MAX_RAW_TRIES} tries - "
            f"image unchanged, keep pressing or press R to reset",
            error=True,
        )

    def _glitch_file_level(self, ranges):
        """Flip one random bit straight inside the file bytes (PLTE/aux)."""
        snapshot = bytearray(self.data)  # undo point: state before this press
        for attempt in range(1, MAX_FAST_TRIES + 1):
            flipped = random_bit_in_ranges(self.data, ranges, self.rng)
            if flipped is None:  # shouldn't happen (checked above)
                return
            pos, bit = flipped
            chunk = self._chunk_at(pos)
            if chunk is not None:
                # PIL validates CRCs of header-ish chunks at open, so keep the
                # CRC fresh during the attempt and restore it if we roll back.
                crc_backup = bytes(self.data[chunk["crc"] : chunk["crc"] + 4])
                refresh_chunk_crc(self.data, chunk)
            else:
                crc_backup = None
            try:
                img = decode(self.data)
            except Exception:  # noqa: BLE001 - decoder may raise many kinds
                self.data[pos] ^= 1 << bit  # roll the breaking flip back
                if crc_backup is not None:
                    self.data[chunk["crc"] : chunk["crc"] + 4] = crc_backup
                continue
            if self.mode != "aux" and img.tobytes() == self.current_px:
                # no visible change (aux chunks only carry metadata, so for
                # that mode any decodable flip counts)
                self.data[pos] ^= 1 << bit
                if crc_backup is not None:
                    self.data[chunk["crc"] : chunk["crc"] + 4] = crc_backup
                continue
            self.undo_stack.append(snapshot)
            self.flips += 1
            self._render(img)
            note = f" (retried {attempt - 1} bad flip(s))" if attempt > 1 else ""
            self._refresh_status(
                f"flip #{self.flips}: byte 0x{pos:x}, bit {bit}{note}"
            )
            return
        self._refresh_status(
            f"no useful bit found in {MAX_FAST_TRIES} tries - "
            f"image unchanged, keep pressing or press R to reset",
            error=True,
        )

    def save_now(self):
        """Write the current bytes to <source stem>_glitch_<N>.png."""
        if self.src_path:
            folder = os.path.dirname(os.path.abspath(self.src_path))
            stem = os.path.splitext(os.path.basename(self.src_path))[0]
        else:
            folder = os.getcwd()
            stem = "image"
        # skip names that already exist so earlier saves are never overwritten
        while True:
            self.save_count += 1
            path = os.path.join(folder, f"{stem}_glitch_{self.save_count}.png")
            if not os.path.exists(path):
                break
        try:
            with open(path, "wb") as f:
                f.write(bytes(self.data))
        except OSError as exc:
            self._refresh_status(f"save failed: {exc}", error=True)
            return
        self._refresh_status(f"saved {os.path.basename(path)} ({len(self.data)} bytes)")

    def undo(self):
        """Undo the last flip by restoring the snapshot taken before it."""
        if not self.undo_stack:
            self._refresh_status("nothing to undo - no flips yet")
            return
        previous = self.undo_stack.pop()
        self.data = previous
        self.chunks, self.regions = parse_png(self.data)
        self.flips -= 1
        try:
            img = decode(self.data)
        except Exception as exc:  # noqa: BLE001 - should not happen (it decoded before)
            self.undo_stack.append(previous)
            self.flips += 1
            self._refresh_status(f"undo failed: {exc}", error=True)
            return
        self._render(img)
        self._refresh_status(f"undid last flip (flips: {self.flips})")

    def reset(self):
        self.data = bytearray(self.original)
        self.chunks, self.regions = parse_png(self.data)
        self.flips = 0
        self.undo_stack.clear()
        self._render(decode(self.data))
        self._refresh_status("reset to the original file")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Show a PNG and glitch it one bit at a time: keys 1-5 "
                    "corrupt the decoded scanlines (any/filter/row/MSB/LSB), "
                    "6 flips raw compressed IDAT bytes, 7/8 corrupt PLTE/aux "
                    "chunks; SPACE flips and re-renders, S saves, R resets."
    )
    parser.add_argument("image", help="path to a .png file")
    args = parser.parse_args(argv)

    try:
        with open(args.image, "rb") as f:
            raw = f.read()
    except OSError as exc:
        print(f"error: cannot read '{args.image}': {exc}", file=sys.stderr)
        return 1

    if not raw.startswith(PNG_SIG):
        print(f"error: '{args.image}' does not start with a PNG signature",
              file=sys.stderr)
        return 1

    root = tk.Tk()
    try:
        GlitchViewer(root, raw, src_path=args.image)
    except Exception as exc:  # noqa: BLE001
        print(f"error: {exc}", file=sys.stderr)
        root.destroy()
        return 1
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
