"""Generate the reference fixtures the on-device parity tests compare against.

Everything here is Pillow (libjpeg-turbo), which is the library the b_x4 sweep
was measured with, so these files are the reference the app has to reproduce.

Layout, under app/src/androidTest/assets/parity/:

  source.jpg          192x144 crop of b.jpg, q92 baseline 4:2:0
  source_rgb.bin      Pillow's decode of source.jpg, 3 bytes per pixel
  encoded_q92.jpg     Pillow's encode of source_rgb.bin at q92 - the byte target
  scaled_96_rgb.bin   Pillow's LANCZOS resize of source_rgb.bin to 96 wide
  scaled_96_size.json the width and height of that
"""
import io, json, os
from PIL import Image

ROOT = r"C:\Users\akach.9800X3D\repos\glitch"
OUT = os.path.join(ROOT, "app", "src", "androidTest", "assets", "parity")
os.makedirs(OUT, exist_ok=True)

CROP = 192          # width of the fixture crop
SCALE_TO = 96       # the resize target the parity test checks
QUALITY = 92

# --- the source: a real photo crop, through the sweep's own encoder settings --
raw = open(os.path.join(ROOT, "b.jpg"), "rb").read()
with Image.open(io.BytesIO(raw)) as opened:
    opened.load()
    rgb = opened.convert("RGB")

# Somewhere with detail, not a flat corner: a quarter of the way in.
left, top = rgb.width // 4, rgb.height // 4
crop = rgb.crop((left, top, left + CROP, top + int(CROP * 3 / 4)))
print("fixture crop:", crop.size)

buf = io.BytesIO()
crop.save(buf, format="JPEG", quality=QUALITY)
source = buf.getvalue()
open(os.path.join(OUT, "source.jpg"), "wb").write(source)
print(f"source.jpg: {len(source)} bytes")

# --- the pixels Pillow decodes it to ----------------------------------------
with Image.open(io.BytesIO(source)) as opened:
    opened.load()
    decoded = opened.convert("RGB")
assert decoded.size == crop.size, (decoded.size, crop.size)
open(os.path.join(OUT, "source_rgb.bin"), "wb").write(decoded.tobytes())
print(f"source_rgb.bin: {decoded.size[0]}x{decoded.size[1]}")

# --- Pillow's own encode of those pixels: the byte-for-byte target ----------
buf = io.BytesIO()
decoded.save(buf, format="JPEG", quality=QUALITY)
encoded = buf.getvalue()
open(os.path.join(OUT, "encoded_q92.jpg"), "wb").write(encoded)
print(f"encoded_q92.jpg: {len(encoded)} bytes")

# --- Pillow's LANCZOS resize of those pixels: the resampler target ----------
height = max(1, round(decoded.height * SCALE_TO / decoded.width))
scaled = decoded.resize((SCALE_TO, height), Image.LANCZOS)
open(os.path.join(OUT, "scaled_96_rgb.bin"), "wb").write(scaled.tobytes())
json.dump(
    {"width": SCALE_TO, "height": height},
    open(os.path.join(OUT, "scaled_96_size.json"), "w"),
)
print(f"scaled_96_rgb.bin: {scaled.size}")

# --- report what the fixture's header looks like, for the record ------------
def header(data):
    out = {"sampling": None, "dht": [], "app0": False, "dri": 0}
    n, pos = len(data), 2
    while pos + 3 < n:
        if data[pos] != 0xFF:
            break
        code = data[pos + 1]
        if code == 0xD9 or code == 0xDA:
            break
        if code == 0x01 or 0xD0 <= code <= 0xD8:
            pos += 2
            continue
        seg = int.from_bytes(data[pos + 2:pos + 4], "big")
        end = pos + 2 + seg
        body = data[pos + 4:end]
        if code in (0xC0, 0xC1, 0xC2):
            comps = body[5]
            out["sampling"] = [(body[7 + 3 * i] >> 4, body[7 + 3 * i] & 15) for i in range(comps)]
        elif code == 0xC4:
            p = 0
            while p + 17 <= len(body):
                counts = list(body[p + 1:p + 17])
                out["dht"].append(counts)
                p += 17 + sum(counts)
        elif code == 0xE0:
            out["app0"] = True
        elif code == 0xDD:
            out["dri"] = int.from_bytes(body[:2], "big")
        pos = end
    return out


info = header(source)
print("\nsource.jpg header:", info["sampling"], "| APP0:", info["app0"], "| DRI:", info["dri"])
print("luma DC counts:", info["dht"][0] if info["dht"] else None)
total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
print(f"\nfixtures: {len(os.listdir(OUT))} files, {total/1024:.0f} KB")
