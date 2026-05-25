# app/icon-source/

Source / reference PNGs for the **launcher icon** (the icon that appears on the
Android home screen and app drawer).

This folder lives **outside `app/src/main/`** on purpose: Android Gradle Plugin
does not look here, so nothing in this folder is compiled, packaged into the
APK, or referenced at runtime. It exists purely as a design-time staging area
for the source art you feed into Android Studio's Image Asset Wizard.

---

## Why the launcher icon is NOT in `app/src/main/assets/sprites/`

| | Launcher icon | In-game sprite |
|---|---|---|
| Read by | Android OS, before your app starts | Your game code, at runtime |
| Lives in | `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_launcher_background.xml`, `res/mipmap-anydpi-v26/...`, `res/mipmap-anydpi/...` | `assets/sprites/*.png` |
| Format on disk | Vector XML today; PNGs at multiple densities after running the Image Asset Wizard | PNG (preferred for static art) |
| Replaced via | Android Studio: New -> Image Asset (Launcher Icons - Adaptive and Legacy) | Overwrite the PNG file with same name |

Replacing a file in `assets/sprites/` will never change the home-screen icon,
and replacing a file in `res/drawable/ic_launcher_*` will never change in-game
art. They are two independent pipelines.

---

## Files in this folder

| File | Size | Purpose |
|---|---|---|
| `launcher_t_full.png` | 512 x 512 | Visual preview of the current placeholder icon (cream "T" on a dark square). Open this to see exactly what is on the launcher today. NOT used as a direct input to anything -- it is documentation only. |
| `launcher_t_foreground.png` | 512 x 512 | Foreground-only version: the cream "T" on a transparent background, sized for the Image Asset Wizard's adaptive-icon foreground slot. Drop this (or your replacement art) into the wizard's "Foreground Layer -> Source Asset" field. |

The 108 x 108 vector grid used inside `res/drawable/ic_launcher_*.xml` is
scaled here to 512 x 512 (4.74x). That keeps the safe-zone math identical: the
inner 66 x 66 safe zone in vector space becomes a 313 x 313 area centered in
the 512 x 512 PNG. Anything outside that inner region risks being clipped by
the launcher's circle / squircle / teardrop mask on real devices.

---

## How to redesign the icon

1. Open `launcher_t_foreground.png` in your editor.
2. Replace the "T" with your real foreground art. Keep the canvas at 512 x 512
   (or larger and proportional) and keep the focus inside the centered safe
   zone described above.
3. Optional: also prepare a background image, or just pick a solid color in
   the wizard.
4. In Android Studio: right-click `app/src/main/res/` -> **New -> Image Asset**.
5. Icon Type: `Launcher Icons (Adaptive and Legacy)`.
6. Foreground Layer -> Source Asset: select your edited `launcher_t_foreground.png`
   (or copy it here as `launcher_<your_name>_foreground.png` and use that).
7. Background Layer: pick a solid color, or a new image you prepared.
8. Legacy Icon: leave on. The wizard auto-generates the API 24-25 PNG fallbacks.
9. Next -> Finish. The wizard overwrites:
   - `res/drawable/ic_launcher_foreground.xml` (or PNG, if you chose raster)
   - `res/drawable/ic_launcher_background.xml`
   - `res/mipmap-anydpi-v26/ic_launcher.xml` + `_round.xml`
   - PNG bitmaps in `res/mipmap-mdpi/` ... `res/mipmap-xxxhdpi/`
10. Rebuild and reinstall on a device or emulator. New icon appears.

After replacement, keep this folder around: drop your final source PSDs / SVGs
/ PNGs here so future-you can rerun the Image Asset Wizard from the same
source instead of guessing what the original art was.
