---
name: theme-editor
description: Create or modify a Y1 launcher theme (colors, layout, icons) using tools/theme-editor and a live browser, then apply the result to the theme's live location (device SD card and/or the repo's asset copy). Use when the user wants to design a new theme, tweak an existing theme's colors/positions/icons, or asks to "open/load/check the theme editor", "move <element>", "change the icon color", "make the theme better/cohesive", "flash the theme", etc.
---

# Theme editor workflow

This repo ships a standalone theme editor at `tools/theme-editor/index.html` — a
single-file HTML/JS tool (no build step) that mirrors the exact schema and
rendering rules of `ThemeManager.java` / `MainMenuManager.java`. Use it as a
live, visual scratchpad while chatting with the user about theme changes.

Themes are self-contained zips/folders: `config.json` at the root plus any PNG
assets it references, no wrapping folder. `unzip -l` a shipped theme if
unsure of the convention.

## CRITICAL: where the running app actually reads themes from

`app/src/main/assets/themes/<Name>.zip` in this repo is **dead weight** —
confirmed by grepping the entire Java source and CI/ROM scripts, nothing
ever reads it at runtime (no `AssetManager` copy step exists). Editing it
does *nothing* on a real device or emulator, and this has already caused a
"the new theme isn't live" confusion once — don't repeat it.

`ThemeManager.loadThemesFromStorage` (called from `MainActivity`) only scans
**`/storage/sdcard0/Y1_Themes/`** on the device itself. Each theme is either
a `.zip` dropped there (auto-extracted once, then deleted) or an
already-extracted folder, e.g. `/storage/sdcard0/Y1_Themes/mint_mango/`.
**That on-device folder is the one to edit** for changes to actually show up:

```bash
adb shell ls /storage/sdcard0/Y1_Themes/<Name>/         # confirm it exists first
adb push <local file> "/storage/sdcard0/Y1_Themes/<Name>/<same filename>"
adb shell am force-stop com.themoon.y1 && adb shell am start -n com.themoon.y1/.MainActivity
```
The force-stop+restart (or a full `adb reboot`) is required — theme state is
loaded once at startup and cached in memory, editing files on disk alone
doesn't hot-reload it.

Still update `app/src/main/assets/themes/<Name>.zip` too, if it exists,
so the repo's copy doesn't silently drift from what's actually live on
device — just don't mistake updating it for the fix.

## Loop

1. **Serve it.** The editor can't be opened via `file://` in the browser
   tooling — serve it locally:
   ```
   cd tools/theme-editor && nohup python3 -m http.server 8934 > /tmp/theme_editor_server.log 2>&1 & disown
   ```
   Reuse the same port across a session; kill it (`pkill -f "http.server 8934"`)
   when done.

2. **Drive it with the browser tools** (Playwright/chrome-devtools MCP), not
   WebFetch — it's a JS app, static fetch won't render it.
   - Navigate to `http://localhost:8934/index.html`.
   - Click "Import Theme (.zip/.json)" → accept the confirm dialog → upload
     the real zip from `app/src/main/assets/themes/`. The tool can't read
     paths outside the repo tree, so copy any outside file (e.g. something
     from `~/Downloads`) into the repo first (`tools/theme-editor/_preview.zip`
     is a good scratch name — delete it before finishing).
   - Click list buttons to simulate focus (updates focused colors + any
     `visible_on_focus` widgets, matching real device behavior).
   - Drag free-floating elements (box/list_box/widgets) directly on the
     canvas to reposition; the properties panel's X/Y update live.
   - Screenshot (`browser_take_screenshot`) to show/verify visually.

3. **Read state back out.** The page's top-level `theme` object (and
   `selectedId`/`focusedId`) are accessible directly in
   `browser_evaluate`/`browser_run_code_unsafe` — no need to click through
   the UI to find out what changed:
   ```js
   () => { const e = theme.main_menu.find(x => x.id === 'widget_focus_preview');
           return { x: e.x, y: e.y, gravity: e.gravity }; }
   ```
   This is the fastest way to recover "where did the user just drag that"
   after they interact with the browser themselves.

4. **Apply the change.** Don't hand-edit the zip in place — unzip to a
   scratch dir, edit `config.json` (or swap/add PNGs), rezip flat, preview it
   back through the editor (import → click through affected buttons →
   screenshot) to confirm, then push it live:
   ```
   unzip -o app/src/main/assets/themes/<Name>.zip -d $SCRATCH/theme
   # edit $SCRATCH/theme/config.json or replace PNGs
   cd $SCRATCH/theme && zip -X -r $SCRATCH/out.zip . -x '.*'

   # 1. The repo copy (keeps it in sync, but NOT what the device reads):
   cp $SCRATCH/out.zip app/src/main/assets/themes/<Name>.zip

   # 2. The actual live location on the connected device:
   for f in $SCRATCH/theme/*; do
     adb push "$f" "/storage/sdcard0/Y1_Themes/<Name>/$(basename "$f")"
   done
   adb shell am force-stop com.themoon.y1 && adb shell am start -n com.themoon.y1/.MainActivity
   ```
   `zip -X -r . -x '.*'` from *inside* the folder keeps files at the zip
   root (no wrapper dir) and skips dotfiles/macOS cruft.

   If the user says "flash"/"install" rather than just "preview", they mean
   a real APK rebuild + reinstall, not a theme-file push — see "Building and
   flashing the APK" below. Pushing updated theme *asset* files to
   `/storage/sdcard0/Y1_Themes/` (above) is enough on its own to see theme
   changes; it does not require rebuilding or reflashing the app.

5. **Check `git status`** on the theme zip before and after — it's a small
   binary, easy to verify the diff is intentional. Confirm no uncommitted
   work existed before overwriting.

6. **Verify on-device if one is connected** (`adb devices`): screenshot
   before/after (`adb shell screencap -p /sdcard/x.png && adb pull ...`,
   remember to `adb shell rm` the device-side copy after). A `KEYCODE_POWER`
   or `KEYCODE_WAKEUP` keyevent may be needed first if the screen is asleep —
   check `adb shell dumpsys power | grep mWakefulness`.

7. **Clean up.** Remove `.playwright-mcp/`, any screenshots dropped in the
   repo root, and scratch preview zips under `tools/theme-editor/` before
   ending the turn. Don't leave test artifacts in the tracked tree.

## Building and flashing the APK

Only needed for actual code changes (Java, layouts) — not for theme-only
edits (see above). Full steps are in `README.md` under "Building from
Source"; short version:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
bash gradlew assembleDebug
adb shell mount -o remount,rw /system
adb push app/build/outputs/apk/debug/app-debug.apk /system/app/com.themoon.y1.apk
adb shell chmod 644 /system/app/com.themoon.y1.apk
adb reboot
```
It's a **system app**, not a normal `adb install` target — remounting
`/system` read-write and overwriting the system APK is required. This is
invasive/hard-to-reverse; confirm with the user before doing it rather than
running it as a routine step.

## Schema notes (read before guessing at fields)

- Colors are Android `#AARRGGBB` hex (`ThemeManager.safeParseColor` via
  `Color.parseColor`), not `#RRGGBBAA`.
- `-1` is a real sentinel meaning "inherit the theme default" for `radius`,
  `width`/`height` (wrap/fill), `text_size`, etc. Don't treat it as a bug.
- Per-button `bg_color` only overrides the **normal** state
  (`MainMenuManager.java` ~line 477); on focus the app *always* paints the
  global `btnFocused` color unconditionally, ignoring `bg_color`. The editor
  matches this — if focus color ever looks like it's not respecting a
  per-button override, that's correct, not a bug.
- `widget_focus_image` has singleton fallback behavior: when nothing's
  `visible_on_focus`-matched to the currently focused button, it shows that
  button's own `preview_image` instead of staying blank. This is why a
  generic "focus preview" widget with `visible_on_focus: "_none_"` still
  lights up per-button icons in practice.
- Free-floating elements (`box`, `list_box`, widgets) are positioned via
  `gravity` + `x`/`y` offsets from that anchor corner (see
  `MainMenuManager.createDynamicLayoutParams`/`parseGravity`) — `x`/`y` are
  not raw absolute coordinates once gravity includes `right`/`bottom`.
- Buttons inside a `list_box` (`parent_id` set) use `x`/`y` differently:
  `x` is a symmetric left/right margin, `y` is top spacing before the next
  item — not gravity-anchored.
- Before assuming an asset filename is wired up, grep
  `app/src/main/java/com/themoon/y1/**/*.java` for the literal string (e.g.
  `getCustomIcon("...")`). Some filenames in a theme zip (like a stray
  `cover.png`) are never read by any code path — don't spend effort
  "improving" dead assets, and don't assume a file in the zip is doing
  anything just because it's there. (One real bug found this way: the
  built-in default album art must be named `icon_default_album.png`, not
  `default_album.png` — the latter silently never loads.)

## Icon work

- Extracting a palette from reference art: PIL `quantize` + `getcolors` on
  the reference image gives dominant hex colors fast — don't eyeball it.
- Recoloring an icon to match an exact accent color (e.g. the theme's
  `btnFocused`) while keeping its shading/bevel: convert to HSV, replace the
  channel with the target hue, keep saturation/value *but normalize value so
  the brightest pixel hits 1.0* — otherwise the recolor comes out muted/dark
  instead of matching the target hex exactly at the highlight.
- Cropping several icons out of one sheet image: don't eyeball grid lines —
  slice into equal cells, then take the alpha-channel `getbbox()` within
  each cell to find the actual icon bounds, pad by ~10%, center on a square
  transparent canvas, resize to the target size (256×256 matches this repo's
  existing theme assets).
- Match existing icon dimensions/mode (`PIL.Image.open(...).size/.mode`) in
  the theme you're editing before generating replacements.
