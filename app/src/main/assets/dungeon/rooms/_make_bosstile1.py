"""Single-purpose generator for bosstile1.png.

Run with: py _make_bosstile1.py
Produces bosstile1.png in the same folder.

Layout (25x25 canvas, painted region 7x7 after bbox trim):

    . . . R . . .
    . # # # # # .
    . # # # # # .
    R # # # # # R
    . # # # # # .
    . # # # # # .
    . . . R . . .

A 5x5 boss chamber with one red connector on every side. White = void,
black = floor, red = connector. The file is kept in the rooms folder so
the same workflow re-creates it if the user wants to nudge it later.
"""

import struct
import zlib
from pathlib import Path

WIDTH = 25
HEIGHT = 25
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
RED = (255, 0, 0)


def write_png(path: Path, pixels: list[tuple[int, int, int]], w: int, h: int) -> None:
    def chunk(typ: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(typ + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + typ + data + struct.pack(">I", crc)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))  # 8-bit truecolor RGB
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter: None
        for x in range(w):
            r, g, b = pixels[y * w + x]
            raw.append(r)
            raw.append(g)
            raw.append(b)
    idat = chunk(b"IDAT", zlib.compress(bytes(raw), level=9))
    iend = chunk(b"IEND", b"")
    path.write_bytes(sig + ihdr + idat + iend)


def main() -> None:
    canvas = [WHITE] * (WIDTH * HEIGHT)

    # 5x5 chamber at (10,10)-(14,14)
    for y in range(10, 15):
        for x in range(10, 15):
            canvas[y * WIDTH + x] = BLACK

    # One connector per side, centered on x=12 / y=12
    canvas[9 * WIDTH + 12] = RED   # top
    canvas[15 * WIDTH + 12] = RED  # bottom
    canvas[12 * WIDTH + 9] = RED   # left
    canvas[12 * WIDTH + 15] = RED  # right

    out = Path(__file__).with_name("bosstile1.png")
    write_png(out, canvas, WIDTH, HEIGHT)
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
