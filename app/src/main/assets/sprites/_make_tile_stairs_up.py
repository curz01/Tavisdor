"""Generate a 64x64 placeholder tile_stairs_up.png.

Mirrors the look of `tile_stairs_down.png` so the cell is recognizable
in-game as a staircase, but tinted yellow and with the chevrons
inverted so it reads as "going UP". Replace this PNG with real art at
any time; the loader doesn't care about the source as long as the
filename stays `tile_stairs_up.png`.

Usage:
    cd app/src/main/assets/sprites
    python _make_tile_stairs_up.py
"""

from PIL import Image, ImageDraw

SIZE = 64
OUT = "tile_stairs_up.png"


def make() -> None:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Stone base (matches the brown floor sprite vibe).
    draw.rectangle((0, 0, SIZE - 1, SIZE - 1), fill=(40, 30, 18, 255))
    draw.rectangle((2, 2, SIZE - 3, SIZE - 3), fill=(72, 56, 30, 255))

    # Yellow inset so the cell screams "stairs up" even at small zoom.
    draw.rectangle((8, 8, SIZE - 9, SIZE - 9), fill=(190, 160, 50, 255))

    # Three chevrons pointing UP (top is wide, narrowing as they go down).
    chev_color = (30, 22, 8, 255)
    rows = [
        (14, 10, SIZE - 15, 22),   # widest at top
        (20, 24, SIZE - 21, 36),
        (26, 38, SIZE - 27, 50),   # narrowest at bottom
    ]
    for x0, y0, x1, y1 in rows:
        # filled trapezoid look: thin horizontal bar with a slight
        # upward tick on each end so it reads as a step.
        draw.rectangle((x0, y0, x1, y1 - 8), fill=chev_color)
        draw.line((x0, y1 - 8, x0 + 4, y0), fill=chev_color, width=2)
        draw.line((x1, y1 - 8, x1 - 4, y0), fill=chev_color, width=2)

    img.save(OUT)
    print(f"Wrote {OUT} ({SIZE}x{SIZE}).")


if __name__ == "__main__":
    make()
