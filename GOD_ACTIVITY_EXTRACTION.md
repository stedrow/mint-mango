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

## Next candidates, ranked by isolation / risk

Field+method occurrence counts are from before the extractions above — use as a rough
"how much to move" gauge, not a precise LOC count.

### 1. Wheel-lock overlay — DONE, see above.

### 2. FM radio UI — DONE, see above.

### Not yet scoped (larger, more entangled — don't attempt without dedicated review)
- **Settings UI** (~15 sub-pages, each building its own `LinearLayout` tree with click
  listeners closing over dozens of MainActivity fields). This is the single biggest chunk of
  MainActivity's line count but has the least natural boundary — nearly every setting reads or
  writes some other subsystem's state directly. The FM radio extraction confirmed this: even a
  single sub-page needed half a dozen MainActivity fields/helpers widened to public. Extracting
  this well probably means extracting the *other* subsystems first (EQ UI naturally follows once
  `AudioEffectManager`'s UI-facing surface is cleaner, Bluetooth UI naturally follows a
  Bluetooth-manager extraction, etc.) so Settings shrinks on its own rather than being tackled as
  one giant page-by-page project.
- **Bluetooth connection management** (`globalA2dp`, the reflection-based
  connect/disconnect/getConnectionState calls). Correctness-critical and touches the same
  reflection code item (d) already deferred pending AirPods hardware access — don't extract
  this without also being able to runtime-verify AirPods reconnect, for the same reason caching
  the reflected Methods was deferred.
- **Key-event routing** (`onKeyDown`/`dispatchKeyEvent`, ~700+ lines between them). This is the
  connective tissue between nearly every other subsystem (wheel-lock, screen-off control, media
  keys, settings navigation) — extract everything that currently plugs into it *first*, then
  this file shrinks to routing logic almost by itself. The wheel-lock extraction already pulled
  its slice out of `dispatchKeyEvent()`'s top gate; the remaining routing logic is what's left.

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
