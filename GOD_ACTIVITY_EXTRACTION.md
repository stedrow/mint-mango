# MainActivity God-Activity Extraction — Roadmap

## Current state (as of `e35a967`)

`MainActivity.java` is **11,841 lines**, ~757 field/method declarations at the top level
(`grep -c "private \|public \|protected "`), 498 of which are methods. It is a single-Activity
HOME launcher, so it owns essentially the entire app's runtime state — this number will not
reach zero, but it should not be doing this much.

Already pulled out into their own classes (some by the original perf-review pass, one —
`GaussianBlurManager` — as the first step of this effort):

| Class | Owns |
|---|---|
| `managers/AudioPlayerManager` | ExoPlayer/MediaPlayer engines, track load/prepare, playback state persistence |
| `managers/AudioEffectManager` | Equalizer/BassBoost/Virtualizer attach + apply |
| `managers/FmRadioManager` | FM chipset reflection (open/close/power/mute), backend only — UI still in MainActivity |
| `managers/AudiobookManager` | Audiobook resume-position bookmarks |
| `managers/GaussianBlurManager` | RenderScript blur context + blur calls (extracted this pass — see below for the pattern) |
| `db/LibraryCacheDb` | All SQLite |
| `ThemeManager` | Theme file loading/parsing, color/icon resolution |
| `Y1UsbFocusHelper` | USB-host focus-reclaim polling |
| `AapService` + `AapPacketFraming` | AirPods AAP protocol over L2CAP |

Everything else — UI construction for every screen (Settings' ~15 sub-pages, Music library
browser, Bluetooth pairing, Wi-Fi, Navidrome browsing, the on-screen widgets, wheel-lock,
theming application, key-event routing, the whole onCreate) — is still in MainActivity.

## The proven extraction pattern

This is what worked for `GaussianBlurManager` (commit `0545a19`) and is a compile-checkable,
low-risk recipe for the next one. Read the whole thing before starting; skipping steps is how
a "safe" extraction breaks something.

1. **Pick a subsystem with a clean boundary.** Good candidates: its own fields, one or two
   entry points, minimal reach into unrelated MainActivity state. Grep for the field/method
   names to find every call site *before* touching anything — see the candidate list below for
   counts on the next few options.
2. **Create the new class** in `managers/` (or top-level if it's not really a "manager" —
   `AapPacketFraming` lives at top level because it's a pure helper, not stateful).
   - If the subsystem needs `Context`, take it as a method parameter (like
     `GaussianBlurManager.applyGaussianBlur(Context, Bitmap)`) rather than storing it — avoids
     leaking an Activity reference into a singleton and matches the existing manager pattern
     (`AudioPlayerManager`, `AudioEffectManager`, `FmRadioManager` are all `getInstance()`
     singletons with no stored Activity).
   - Move the fields and methods verbatim first. Don't "improve" anything in the same commit —
     a rename or a logic tweak makes it much harder to tell a real regression from an
     intentional change if something breaks on-device.
3. **Leave thin pass-through methods on MainActivity** for any call site outside MainActivity
   itself (e.g. `AudioPlayerManager` calling `main.applyGaussianBlur(...)`). This means the
   extraction is invisible to every other class — zero-risk for callers, and you can grep to
   confirm every external call site still compiles unchanged.
4. **Remove now-dead imports** (`grep` for the extracted class's Android imports across the
   whole file — if the only matches left are inside comments, delete the import).
5. **Compile before doing anything else**: `bash gradlew compileDebugJavaWithJavac`. This
   catches 90% of extraction mistakes (missed field reference, wrong visibility) in about a
   second, before you've spent time on a device flash.
6. **Build, flash, boot-check, exercise the specific feature you moved.** Full `assembleDebug`
   → push to `/system/app/` → reboot → confirm no ANR → confirm the moved feature still visibly
   works (for the blur extraction: main-menu blurred background + Now Playing blurred
   background, since both call sites needed to keep working).
7. **Check logcat for exceptions** across the whole boot+exercise sequence before calling it
   done. `adb logcat -d | grep -i "themoon\|FATAL" | grep -iE "exception|error|fatal|crash"`.
8. **One extraction per commit.** Never batch two subsystem extractions together — if
   something breaks days later, you want `git bisect` to land on one class, not a grab-bag diff.

## Extractions completed so far

- `managers/WidgetClockManager` — digital clock widget text-rendering/caching (PR #10)
- `managers/WheelLockManager` — wheel-lock pocket-misfire guard state machine + input gate (PR #10)
- `managers/FmRadioUiManager` — FM radio screen build (player + settings sub-page), tune,
  frequency-adjust popup (PR #10). Turned out to have no clean field boundary of its own (shares
  MainActivity's settings-page scaffolding like every other Settings sub-page) — took the
  MainActivity instance as a method parameter instead of trying to own that state, and widened
  visibility on several shared fields/helpers from private to public. First extraction that
  needed real hardware (FM chip) to verify on-device; turned out the test device has it.
- `managers/KeyEventRouter` — dispatchKeyEvent()/onKeyDown()/onKeyUp()/onKeyLongPress(), ~900
  lines combined (PR #11). The connective tissue candidate itself: no clean boundary at all (it
  reaches into browser navigation, settings depth, radio, Navidrome, web server, wheel-lock,
  screen-off, volume — nearly everything), so it used the same MainActivity-instance-as-parameter
  pattern as FmRadioUiManager, at much larger scale (~30 fields/methods/constants widened from
  private to public, compile-error driven). MainActivity kept the four Activity-lifecycle
  overrides as one-line delegates plus superOnKeyDown()/superDispatchKeyEvent()/superOnKeyUp()/
  superOnKeyLongPress() helpers, since a non-Activity class can't call super.onKeyDown() etc.
  Verified on-device across nearly every screen state (menu, Bluetooth, Wi-Fi + password
  keyboard, web server + its AlertDialog, music library in every browse mode, player, settings
  depth chain, radio wheel-tune). Long-press screen-off and the double-click quick-menu window
  were reviewed by inspection only (this device's adb `input` predates `--longpress`), since
  they're unmodified code carried over verbatim.
- `managers/SettingsUiManager` — the whole Settings tree: depth-0 group list, all six group
  screens, and their leaf sub-pages (theme selector, language selector, update checker,
  vibration, widgets, background picker, date/time, equalizer/graphic EQ) (PR #12). ~1700 lines
  across 19 methods, same MainActivity-instance-as-parameter pattern as the two above. Built via
  a scripted extraction (line-range copy + compile-error-driven `a.` prefixing, iterated to
  convergence) rather than fully by hand, given the size -- worked cleanly but needed a few manual
  fixups afterward (missing imports for `Intent`/`DialogInterface`/`Settings`/`R`, `switch` case
  labels needing `MainActivity.GROUP_X` instead of `a.GROUP_X` since a compile-time constant must
  be referenced through the class, not an instance). Verified on-device across every group and
  leaf screen including both AlertDialog confirmation flows (clear cache, power off) and the
  network-hitting system-update checker.
- `managers/BluetoothAudioManager` — the reflection-based A2DP connection engine: proxy
  lifecycle, connect/pair/disconnect, exponential-backoff reconnect watchdog, AAP ear-detection
  listener (PR #13). Unlike the four extractions above, this one had a genuinely clean field
  boundary (globalA2dp/targetDeviceForAudio/isBtConnectingState/backoff state were private to
  the engine itself) -- compiled clean on the first try, no iteration needed. The giant
  intent-broadcast receiver and Bluetooth-screen UI builders stayed in MainActivity (not part of
  "connection management"); those call sites now go through the manager's public API. Verified
  on real AirPods hardware per the roadmap's non-negotiable: connect, full Bluetooth power-off
  (clean teardown), power-on (proxy rebind + watchdog auto-reconnect with zero manual
  intervention).
- `managers/NavidromeManager` — Navidrome/Subsonic browse screens (artists/albums/songs,
  letter-jump index) and the one-at-a-time download queue (wake/wifi locks, transfer, library
  registration, cover art caching, retry) (PR #14). ~730 lines across 29 methods. Genuinely clean
  field boundary like BluetoothAudioManager for the download-queue state; a handful of fields
  stayed in MainActivity because KeyEventRouter already touches them directly
  (navidromeBrowseDepth/selectedNavidromeArtist/isNavidromeLetterView/lastNavidromeArtists/
  navidromeBackTarget) plus the onCreate() view bindings. Built via the same scripted extraction
  as SettingsUiManager -- caught a real mistake mid-pass: the first attempt deleted one
  contiguous span from the first Navidrome method to the last, which silently also deleted
  unrelated interleaved methods (showQuickMenu, showRadioFreqPopup, setupAudiobookProgress) that
  happened to sit between two non-contiguous Navidrome clusters. Caught immediately by the
  resulting compile errors, reverted, redone with each method's verified range deleted
  individually. **Lesson for future scripted extractions: never delete first-start to
  last-end as one span -- always delete each verified per-method range separately, even when
  it means more edits.** Verified against a real Navidrome server: browsing, real streaming
  playback with quality-info badges, and a full download completing end-to-end.
- `managers/ConnectivityScreenManager` — Bluetooth and Wi-Fi settings-screen UI construction:
  device/network scan lists, pairing/connect click handlers, focus restoration after a rebuild
  (PR #15). ~450 lines across 8 methods. The connection engines already lived elsewhere
  (BluetoothAudioManager for A2DP reflection calls; Wi-Fi has no reflection engine, just plain
  WifiManager calls) -- this was purely the two screens' UI-construction half, same role as
  FmRadioUiManager/SettingsUiManager. No clean field boundary (view bindings from onCreate()),
  MainActivity-instance parameter. 3 of the 8 methods (addPairedBluetoothItemToUI,
  restoreBluetoothFocus, addWifiItemToUI) had no external callers and were deleted outright
  rather than kept as pass-throughs. Verified against real Bluetooth (AirPods) and Wi-Fi
  hardware: power on/off/on cycles, paired-device sub-menu, real network scan with correct
  locked/open icons and connected-network-first sort.

- `managers/NetworkTrustManager` — `TLSSocketFactory` + `installTls12TrustAll()`. Zero
  MainActivity field dependencies, moved verbatim as static methods/nested class; two existing
  external callers (`ApkInstallManager`, `SettingsUiManager`) updated from
  `MainActivity.TLSSocketFactory` to `NetworkTrustManager.TLSSocketFactory`.
- `managers/NowPlayingUiManager` — player-screen UI refresh, volume overlay, spectrum
  visualizer, and lyrics (LRC file + embedded ID3 USLT) loading/scroll/highlight. No clean field
  boundary (shares view state with the progress-tick Runnable that stays in MainActivity since
  NavidromeManager/MainMenuManager reference it by field name), so it takes the MainActivity
  instance as a parameter. Widened several player-screen fields and two Bluetooth/Navidrome
  helper methods from private to public. Verified on-device: play/pause, progress tick, volume
  adjust, visualizer toggle with live spectrum render, no exceptions across the session.
- Folded Bluetooth remote-control metadata (`initRemoteControlClient`,
  `updateBluetoothMetadata`, `updateBluetoothPlaybackState`, `sendBluetoothMetaToCar`) into the
  existing `managers/BluetoothAudioManager` rather than a new class -- same subsystem (Jelly Bean
  `RemoteControlClient` feeding car head units/AVRCP), same `MainActivity.instance` callback
  pattern that manager already uses. `MediaBtnReceiver` stayed in MainActivity since it's
  registered by fully-qualified name in `AndroidManifest.xml`.

**Note:** several other managers now exist that predate this note but were never logged here
(`MusicBrowserManager`, `MainMenuManager`, `MediaLibraryScanManager`, `TrackInfoFetchManager`,
`LanguageManager`, `ApkInstallManager`, `BundledAssetsInstaller`, `AudiobookProgressManager`,
`PlaylistFavoritesManager`, `SongContextMenuManager`) -- this doc had drifted out of sync with
actual extraction history well before the three entries above. Treat the "Extractions completed
so far" list as incomplete for anything before `NetworkTrustManager`; check `managers/` directly
for the current true state.

## Next candidates, ranked by isolation / risk

### 1. Wheel-lock overlay — DONE, see above.

### 2. FM radio UI — DONE, see above.

### 3. Key-event routing — DONE, see above.

### 4. Settings UI — DONE, see above.

### 5. Bluetooth connection management — DONE, see above.

### 6. Navidrome browsing + download engine — DONE, see above.

### 7. Bluetooth/Wi-Fi screen UI — DONE, see above.

### Not yet scoped
Every subsystem originally identified in this roadmap has been extracted, plus two more found
along the way (Navidrome, Bluetooth/Wi-Fi screen UI). MainActivity is still a large
single-Activity HOME launcher (Settings' remaining UI-construction glue, Music library browser,
theming application, the whole onCreate) -- that's expected and not itself a problem; see
"Current state" at the top. Future passes should pick a fresh subsystem the same way these were
picked: grep for a clean-ish field boundary, check call-site count, and follow the pattern below.

## Known cosmetic issue, unrelated to extraction work (spotted during Settings UI testing)

Several `AlertDialog.Builder` confirmation dialogs (Power Off, Clear Cache & Track Info, Web
Server "is running" prompt, Wi-Fi save-failed) use `android.R.style.Theme_DeviceDefault_Dialog_Alert`
and render with the stock Android white dialog look instead of the app's theme. This is
pre-existing (not introduced by any extraction — the style constant was carried over verbatim in
every pass) and should be a follow-up: either theme these via a custom dialog style, or swap them
for in-app-themed overlay views like the rest of the UI.

## Non-negotiables for any future extraction pass

- **Never batch extractions.** One subsystem, one commit, full verify cycle before starting
  the next.
- **Never extract and refactor in the same change.** If you spot something else wrong while
  moving code, note it and fix it separately — bundling makes every regression ambiguous.
- **Never skip the on-device exercise step**, even for something that "obviously" can't break.
  The blur extraction *was* obviously safe by inspection, and still got a real on-device check
  (main-menu + Now Playing background) before being called done — that's the standard to hold
  every future extraction to, not just the risky ones.
- **`MainActivity.instance`** is a load-bearing static reference other classes use to reach back
  into MainActivity (documented in `onDestroy()`) — extractions that need to call back into
  MainActivity should go through it the same way `AudioPlayerManager` already does, not
  introduce a second pattern. (The FM radio extraction used an explicit `MainActivity a`
  parameter instead, since it needed far more of MainActivity's surface than a single
  callback — both patterns are fine, pick whichever fits how much state the subsystem needs.)
