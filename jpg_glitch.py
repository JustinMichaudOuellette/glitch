#!/usr/bin/env python3
"""JPEG bit-flip glitcher with region targeting.

Reads a JPEG file, decodes it and shows it in a window. The JPEG is
parsed into byte regions, and you pick which region to corrupt with the
number keys. Each SPACE press then flips one random bit inside that
region and re-decodes / re-renders the image:

    1  quantization tables   (DQT) - payload bytes only
    2  Huffman tables        (DHT) - payload bytes only
    3  scan / entropy data   (default, same as the original tool)
    4  restart markers       (RST, FF D0-FF D7 inside scan data)

DQT/DHT live in the file header, so keys 1-2 deliberately relax the
original "never in the header" rule; the EOI marker is never touched by
any mode. Flips that make the JPEG undecodable - or that leave the
picture unchanged - are rolled back and another random bit is tried, so
each press shows a freshly glitched frame.

Keys:
    1-4    select which region to corrupt
    SPACE  flip one random bit in the selected region and re-render
    Z      undo the last flip (restore the previous frame)
    S      save the current bytes to <name>_glitch_N.jpg (next to the source)
    R      reset to the original file bytes
    ESC/Q  quit
"""
import argparse
import io
import os
import random
import sys

from PIL import Image, ImageTk
import tkinter as tk

# JPEG marker codes
EOI_CODE = 0xD9  # end of image
SOS_CODE = 0xDA  # start of scan (entropy-coded data follows)
DQT_CODE = 0xDB  # define quantization table(s)
DHT_CODE = 0xC4  # define Huffman table(s)

# region key -> (marker list, human name)
REGION_NAMES = {
    "DQT": "quantization tables (DQT)",
    "DHT": "Huffman tables (DHT)",
    "scan": "scan / entropy data",
    "RST": "restart markers (RST)",
}

DEFAULT_MAX_PX = 1400  # long edge cap for the on-screen image
MAX_FLIP_TRIES = 50  # attempts per space press (roll back undecodable/no-op flips)


def parse_jpeg(data):
    """Walk the JPEG structure and return byte ranges per corruptable region.

    Returns a dict {region: [(start, end), ...]} where each (start, end)
    is a half-open byte range. Ranges never include marker/length bytes
    for DQT/DHT (payload only) and never include the EOI marker.
    """
    regions = {"DQT": [], "DHT": [], "scan": [], "RST": []}
    n = len(data)
    pos = 2  # just past SOI (FF D8)

    while pos + 1 < n:
        if data[pos] != 0xFF:
            break  # lost sync (trailing junk etc.) - stop cleanly
        if data[pos + 1] == 0xFF:
            pos += 1  # fill bytes before a marker code
            continue
        code = data[pos + 1]
        if code == EOI_CODE:
            break
        # standalone markers with no length field (TEM/RST/SOI/EOI)
        if code == 0x01 or 0xD0 <= code <= 0xD8:
            pos += 2
            continue
        if pos + 3 >= n:
            break  # not enough bytes for a segment length
        seg_len = int.from_bytes(data[pos + 2 : pos + 4], "big")
        if seg_len < 2 or pos + 2 + seg_len > n:
            break  # bogus length - refuse to walk further
        seg_end = pos + 2 + seg_len

        if code == SOS_CODE:
            pos = _consume_scan(data, seg_end, regions)
            continue
        if code == DQT_CODE:
            regions["DQT"].append((pos + 4, seg_end))  # payload only
        elif code == DHT_CODE:
            regions["DHT"].append((pos + 4, seg_end))  # payload only
        pos = seg_end
    return regions


def _consume_scan(data, p, regions):
    """Classify entropy-coded data of one scan starting at offset p.

    Handles 0xFF-stuffing (FF 00 = a data byte 0xFF) and standalone
    restart markers (FF D0-FF D7). Returns the offset of the marker that
    terminates the scan (or len(data) at end of file).
    """
    n = len(data)
    run_start = p
    while p < n:
        b = data[p]
        if b != 0xFF:
            p += 1
            continue
        nb = data[p + 1] if p + 1 < n else None
        if nb == 0x00:  # stuffed 0xFF data byte - part of the scan
            p += 2
            continue
        if nb is None:  # lone 0xFF at end of file: treat as scan byte
            p += 1
            continue
        if 0xD0 <= nb <= 0xD7:  # restart marker: its own region
            if p > run_start:
                regions["scan"].append((run_start, p))
            regions["RST"].append((p, p + 2))
            p += 2
            run_start = p
            continue
        # any other marker (EOI, next SOS, ...) ends this scan
        if p > run_start:
            regions["scan"].append((run_start, p))
        return p
    if p > run_start:
        regions["scan"].append((run_start, p))
    return n


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


def decode(data):
    """Fully decode JPEG bytes and return an RGB PIL.Image.

    Raises whatever PIL raises when the stream cannot be decoded.
    """
    with Image.open(io.BytesIO(bytes(data))) as img:
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
        self.regions = parse_jpeg(self.data)
        self.mode = "scan"  # default region: scan / entropy data
        self.rng = random.SystemRandom()
        self.flips = 0
        self.undo_stack = []  # (byte_offset, bit_index) of each accepted flip
        self.save_count = 0
        self.src_path = src_path
        self.window_sized = False  # size the window to the first frame once

        root.title("JPEG bit-flip glitcher")
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
        for key, region in (("1", "DQT"), ("2", "DHT"), ("3", "scan"), ("4", "RST")):
            root.bind(key, lambda e, r=region: self.select_region(r))
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
        size = region_size(self.regions[self.mode])
        text = (
            f"mode {self.mode} [{REGION_NAMES[self.mode]}, {size} bytes] | {note} | "
            f"flips: {self.flips} | keys: 1 DQT 2 DHT 3 scan 4 RST, "
            f"SPACE flip, Z undo, S save, R reset, ESC/Q quit"
        )
        self.status.config(text=text, fg="#ff6b6b" if error else "#d0d0d0")

    def select_region(self, region):
        self.mode = region
        self._refresh_status("region selected", error=region_size(self.regions[region]) == 0)

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

    def glitch_once(self):
        """Flip one random bit in the selected region, then re-decode/re-render.

        Flips that make the JPEG undecodable - or that decode to exactly the
        same picture - are rolled back and another random bit is tried.
        """
        ranges = self.regions[self.mode]
        if region_size(ranges) == 0:
            self._refresh_status(
                f"no {REGION_NAMES[self.mode]} bytes in this file", error=True
            )
            return
        for attempt in range(1, MAX_FLIP_TRIES + 1):
            flipped = random_bit_in_ranges(self.data, ranges, self.rng)
            if flipped is None:  # shouldn't happen (checked above)
                return
            pos, bit = flipped
            try:
                img = decode(self.data)
            except Exception:  # noqa: BLE001 - decoder may raise many kinds
                self.data[pos] ^= 1 << bit  # roll the breaking flip back
                continue
            if img.tobytes() == self.current_px:  # no visible change
                self.data[pos] ^= 1 << bit
                continue
            self.flips += 1
            self.undo_stack.append((pos, bit))
            self._render(img)
            note = f" (retried {attempt - 1} bad flip(s))" if attempt > 1 else ""
            self._refresh_status(
                f"flip #{self.flips}: byte 0x{pos:x}, bit {bit}{note}"
            )
            return
        self._refresh_status(
            f"no useful bit found in {MAX_FLIP_TRIES} tries - "
            f"image unchanged, keep pressing or press R to reset",
            error=True,
        )

    def save_now(self):
        """Write the current bytes to <source stem>_glitch_<N>.jpg."""
        if self.src_path:
            folder = os.path.dirname(os.path.abspath(self.src_path))
            stem = os.path.splitext(os.path.basename(self.src_path))[0]
        else:
            folder = os.getcwd()
            stem = "image"
        # skip names that already exist so earlier saves are never overwritten
        while True:
            self.save_count += 1
            path = os.path.join(folder, f"{stem}_glitch_{self.save_count}.jpg")
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
        """Undo the last flip by toggling its bit back."""
        if not self.undo_stack:
            self._refresh_status("nothing to undo - no flips yet")
            return
        pos, bit = self.undo_stack.pop()
        self.data[pos] ^= 1 << bit
        self.flips -= 1
        try:
            img = decode(self.data)
        except Exception as exc:  # noqa: BLE001 - should not happen (it decoded before)
            self.data[pos] ^= 1 << bit  # restore
            self.undo_stack.append((pos, bit))
            self.flips += 1
            self._refresh_status(f"undo failed: {exc}", error=True)
            return
        self._render(img)
        self._refresh_status(
            f"undid flip: toggled byte 0x{pos:x}, bit {bit} back (flips: {self.flips})"
        )

    def reset(self):
        self.data = bytearray(self.original)
        self.flips = 0
        self.undo_stack.clear()
        self._render(decode(self.data))
        self._refresh_status("reset to the original file")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Show a JPEG; keys 1-4 choose which byte region to "
                    "corrupt (1=DQT quant tables, 2=DHT Huffman tables, "
                    "3=scan/entropy data, 4=restart markers); SPACE flips "
                    "one random bit in that region and re-renders."
    )
    parser.add_argument("image", help="path to a .jpg file")
    args = parser.parse_args(argv)

    try:
        with open(args.image, "rb") as f:
            raw = f.read()
    except OSError as exc:
        print(f"error: cannot read '{args.image}': {exc}", file=sys.stderr)
        return 1

    if not raw.startswith(b"\xff\xd8"):
        print(f"error: '{args.image}' does not start with a JPEG SOI marker",
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
