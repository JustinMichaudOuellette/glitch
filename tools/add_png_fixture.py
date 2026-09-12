"""Add the PNG fixture the non-JPEG input path needs.

It holds exactly the pixels of source_rgb.bin, so encoding it should reproduce
the same JPEG our encoder makes, and the test can tell our encoder apart from
Skia's by the tables and sampling factors in the header.
"""
import io, os
from PIL import Image

OUT = r"C:\Users\akach.9800X3D\repos\glitch\app\src\androidTest\assets\parity"
rgb = open(os.path.join(OUT, "source_rgb.bin"), "rb").read()
image = Image.frombytes("RGB", (192, 144), rgb)
image.save(os.path.join(OUT, "source.png"), format="PNG")
size = os.path.getsize(os.path.join(OUT, "source.png"))
print(f"source.png: {size} bytes, {image.size}")
