# AMDL application icon

The user approved the music-only ImageGen reference on 2026-11-09. The white musical notes and blue layered tile are the selected design; do not restore a download arrow.

- `amdl-reference.png`: the exact approved reference, copied from built-in ImageGen output `exec-d7871ec2-2082-4b7a-b300-2d0faed5be75.png`.
- `amdl-master.png`: built-in ImageGen's transparent-background production preparation of that reference, output `exec-89c1aae8-b4e5-42b1-b6d2-2418e97b6df6.png`.
- `amdl.png`: 1024px production preview with normalized optical bounds and transparent safety margin.
- `amdl.ico`: ten PNG-backed Windows frames: 16, 20, 24, 32, 40, 48, 64, 96, 128 and 256 pixels.
- `../src/main/resources/amdl.png`: the same design at 256px for the window and caption.

Run `pwsh.exe -NoProfile -File scripts/create-icon.ps1` from the project root to package the master again. The script only measures optical bounds and resamples the approved artwork; it does not redraw the symbol. The earlier arrow-based vector and generator have been removed; the directory now contains the approved reference, the transparent master, the current PNG/ICO outputs, and this note.

## Production preparation prompt

Built-in `image_gen` edit, with `transparent_background: true` and the approved reference as the only image input:

> Production asset preparation of the APPROVED app icon in the supplied image. Preserve the actual blue rounded-square tile, its subtle layered blue tonal shapes, and the exact white beamed musical-note silhouette. This is background extraction, not a redesign. Remove ONLY the light neutral canvas outside the blue icon, leaving a truly transparent alpha background and transparent corner regions. Retain the tile's blue continuous corners and clean antialiased edges; no external drop shadow. Reframe the approved icon centered on a square canvas so its outer blue bounds occupy 94 percent of width/height, with a small equal transparent safety margin on all four sides. Preserve proportions, shape, white note scale relative to the tile, colors, and the existing gentle finish. No download arrow, no extra symbol, no text, no new decorative detail, no perspective. One finished icon on genuine transparency, suitable for Windows PNG/ICO packaging.
