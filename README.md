<img width="480" height="360" alt="Screenshot_20260701_103518" src="https://github.com/user-attachments/assets/808ef561-22f8-4d99-b712-eb98af5a1146" />
<img width="480" height="360" alt="Screenshot_20260704_234025" src="https://github.com/user-attachments/assets/349e0092-b80d-4019-95e4-805505caf8c2" />
<img width="480" height="360" alt="Screenshot_20260704_151027" src="https://github.com/user-attachments/assets/61e05b31-6ed7-4d72-a8f9-87ad91e34069" />
<img width="480" height="360" alt="Screenshot_20260704_124902" src="https://github.com/user-attachments/assets/b6b6b74f-5d17-4859-bf0c-e7093c5bdda8" />
<img width="480" height="360" alt="Screenshot_20260615_172845" src="https://github.com/user-attachments/assets/d804a2a5-a2c8-4a0e-ad16-d1da7551d5fd" />
<img width="480" height="360" alt="Screenshot_20260615_172516" src="https://github.com/user-attachments/assets/5b76d3c5-2d62-4119-8c01-49b9b79c055d" />
<img width="480" height="360" alt="Screenshot_20260615_172223" src="https://github.com/user-attachments/assets/14c658dd-1836-4c1a-a8bf-696e51b59cb3" />
<img width="480" height="360" alt="Screenshot_20260624_181608" src="https://github.com/user-attachments/assets/28a48351-68c6-43c4-87d7-86d692abb661" />
<img width="480" height="360" alt="Screenshot_20260624_181513" src="https://github.com/user-attachments/assets/4ceb794c-fea9-4bea-a716-f1993ea29c67" />


# JJ Launcher (MO-ON Launcher)
for innioasis y1
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Status-Beta-Orange?style=flat-square" alt="Status">
</p>

> [!WARNING]
> <span style="color:#d9381e; font-weight:bold;">⚠️ IMPORTANT: This has only been tested on System Software version 2.1.9. It is highly recommended to check your own firmware version before proceeding with the installation.</span>

---

## 🛠️ Note for Users / Bug Reports

> ⚠️ **Important Notice**
> Please note that this launcher is currently in its **Alpha stage**, and you may encounter unexpected bugs or stability issues. 

If you find any bugs or have suggestions, please **open an Issue** in this repository. I will do my best to address them and release updates whenever my schedule allows. Thank you for your patience and support! 🙏

---

## 🌟 Key Features

1. **Custom Embedded Media Scanner**
   * Automatically and rapidly categorizes large music libraries without relying on the Android system scanner.
2. **Advanced Playback & EQ Controls**
   * Full support for shuffle, repeat modes, and built-in device Equalizer (EQ) presets.
3. **Global Hardware Key Mapping**
   * Skip to the previous or next track using physical hardware buttons from any screen within the app.
4. **Real-time Main Screen Sync**
   * The album art and track information on the main screen sync instantly whenever the active song changes.
5. **Infinite Custom Themes**
   * Easily add and apply your own custom color themes simply by copying files.
6. **Dynamic HD Blur Background**
   * Generates a high-definition blurred wallpaper dynamically matching the currently playing album art.
7. **In-App Bluetooth Pairing**
   * Scan and pair Bluetooth devices instantly within the launcher settings without needing to leave the app.
8. **Wheel-Optimized Virtual Keyboard**
   * Type Wi-Fi passwords and connect directly using a specialized keyboard tailored for wheel-input devices.
9. **Wireless PC File Upload**
   * Host a local Wi-Fi Web Server to wirelessly upload music files directly from your PC browser without cables.
10. **Navidrome / Subsonic Streaming**
    * Browse and stream your Navidrome library over Wi-Fi, or download albums for offline playback. See [below](#-navidrome--subsonic-streaming) for details.
11. **AirPods Pro 2/3 Support**
    * Real audio (not just pairing), ear-detection auto-pause/resume, and squeeze controls that keep working with the screen off. See [below](#-airpods-support-pro-2--pro-3) for details.
12. **Pocket-Press Protection**
    * A wheel-rotation unlock gesture (with an animated ring) prevents the wheel from accidentally controlling playback while the device is in your pocket.

---

## 🚀 Installation & Update Guide

You can easily install the launcher using the **Innioasis Updater**.
👉 [Download Innioasis Updater](https://www.innioasis.com/pages/download)

<img width="1214" height="612" alt="Installation Screenshot" src="https://github.com/user-attachments/assets/95e9e82a-7b2a-48ee-a75b-6f055a77db07" />

---

## 🎵 Navidrome / Subsonic Streaming

The launcher includes a built-in Subsonic API client so you can browse and stream your [Navidrome](https://www.navidrome.org/) music library over Wi-Fi, and download albums locally for offline playback — no cable required.

### Features
- Browse your Navidrome library: **Artist → Album → Songs**
- Stream songs directly on the Y1 over Wi-Fi (transcoded to 192 kbps MP3 by Navidrome)
- Download individual songs or entire albums to `/storage/sdcard0/Navidrome/`
- Downloaded files are organised as `Artist/Album/NN - Title.ext` and play back offline through the normal Music library

> **Note on streaming and FLAC:** The Y1's connection speed cannot sustain lossless FLAC bitrates over the internet. The app requests Navidrome to transcode on the fly to 192 kbps MP3, which streams reliably. Downloaded files are saved in their original format (FLAC, ALAC, etc.) for full quality offline playback. Navidrome requires **ffmpeg** to be installed on the server for transcoding to work — see the [Navidrome transcoding docs](https://www.navidrome.org/docs/usage/transcoding/).

### Usage
- **Navidrome** button on the main menu → Artists list
- Select an artist → album list
- Select an album → song list
  - **▶ Play Album** — streams the full album starting from track 1
  - **⬇ Download Album** — downloads all tracks in the background
  - Tap a song title to play from that track
  - Long-press a song title to download that track only
- Back button navigates up: Songs → Albums → Artists → Main Menu

---

## 🛠️ Building from Source

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (provides the JDK)
- ADB — install via Homebrew on macOS: `brew install android-platform-tools`

### Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
bash gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Flash to device

The launcher ships as a **system app** (`/system/app/com.themoon.y1.apk`), so a standard `adb install` won't work. Use the root shell instead:

1. Connect the Y1 via USB (ADB is enabled by default on this firmware — just plug in)
2. Run:

```bash
adb shell mount -o remount,rw /system
adb push app/build/outputs/apk/debug/app-debug.apk /system/app/com.themoon.y1.apk
adb shell chmod 644 /system/app/com.themoon.y1.apk
adb reboot
```

One-liner for subsequent builds:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && \
bash gradlew assembleDebug && \
adb shell mount -o remount,rw /system && \
adb push app/build/outputs/apk/debug/app-debug.apk /system/app/com.themoon.y1.apk && \
adb shell chmod 644 /system/app/com.themoon.y1.apk && \
adb reboot
```

> **Note:** ADB shell runs as root on this device — no `su` required.

### Useful ADB commands
```bash
adb devices                        # confirm device is connected
adb logcat | grep com.themoon.y1   # live logs from the launcher
adb shell pm path com.themoon.y1   # confirm install location
adb shell pm grant com.themoon.y1 android.permission.WRITE_SECURE_SETTINGS  # fix Bluetooth errors
```

---

## 🎧 Bluetooth Connection & Pairing

Bluetooth headphone connection testing has been successfully completed, and it works perfectly!

<img width="480" height="360" alt="Bluetooth Pairing Screenshot" src="https://github.com/user-attachments/assets/73d0cec4-ddeb-406d-916d-0cdcb788533a" />

> [!CAUTION]
> **⚠️ Important Security Permission Note**
> If you encounter a security-related error message when trying to connect after pairing, you must grant the secure settings permission via ADB by running the following script:

```bash
adb shell pm grant com.themoon.y1 android.permission.WRITE_SECURE_SETTINGS
```
---

## 🎧 AirPods Support (Pro 2 / Pro 3)

### Audio Fix

AirPods pair and connect to the Y1 but play **no sound** — the player shows a
track playing while the AirPods stay silent. This is a bug in the Y1's MediaTek
Bluetooth firmware (not the launcher): the A2DP/SBC audio stream carries broken
RTP timestamps, which most headphones ignore but modern AirPods reject by
silently dropping the stream.

The fix is a small proxy driver that replaces `/system/lib/libbluetoothdrv.so`,
wraps the stock MediaTek driver, and rewrites the outgoing RTP timestamps so the
AirPods accept the stream. It installs **live over adb** (this firmware has a
root shell) — no SP Flash Tool or `system.img` patching needed — and preserves
the stock driver so it's fully reversible.

Everything needed to build, install, verify, and revert lives in
[`scripts/airpods-rtpfix/`](scripts/airpods-rtpfix/):

```bash
cd scripts/airpods-rtpfix
brew install --cask android-ndk && brew install lld android-platform-tools  # one-time
./build.sh      # compile the proxy against the device's ABI
./install.sh    # back up the stock driver, install the proxy, reboot
./status.sh     # confirm it's active and tail the fix log while audio plays
./revert.sh     # restore the stock driver if ever needed
```

See the [folder README](scripts/airpods-rtpfix/README.md) for how it works, why
the live adb swap is safe on this firmware, and build variants (e.g. an optional
SBC bitpool clamp). Credit for the timestamp-normalization approach:
[Semy0nBu/y1-airpods-rtpfix](https://github.com/Semy0nBu/y1-airpods-rtpfix).

### Ear-Detection Auto-Pause/Resume

Once audio works, the launcher also speaks Apple's AAP (Apple Accessory
Protocol) directly to the AirPods over a separate L2CAP channel (a background
`AapService`, distinct from the A2DP audio connection). This enables real
"AirPods-native" behavior: playback automatically pauses the moment an AirPod
is removed, and resumes when both are reinserted — without the AirPods needing
to be connected to a phone at all.

This required a small binary patch to the stock `libextjsr82.so` (the closed
JSR82/L2CAP socket layer) since the stock MediaTek stack otherwise rejects the
kind of raw-PSM L2CAP connection AAP requires. See
[`scripts/airpods-rtpfix/PHASE2_PLAN.md`](scripts/airpods-rtpfix/PHASE2_PLAN.md)
for the full investigation, protocol notes, and the patch's scope/safety
rationale.

### Squeeze Controls (Play/Pause/Skip), Even With the Screen Off

Squeezing an AirPod stem to play/pause or skip tracks works from any screen in
the launcher, including with the screen off. AirPods squeezes are recognized as
Bluetooth AVRCP passthrough events (identified via a distinct virtual input
device named `AVRCP`) and always pass through, independent of the
**Screen-Off Control** setting — that setting only gates the device's own
physical wheel/button, so it can stay off (preventing accidental in-pocket
presses) while AirPods gestures still work.

---

## ⚙️ Other Settings & Fixes in This Fork

A few additional settings and fixes added on top of the upstream launcher,
found in the Settings menu unless noted:

- **Lock Wheel on Wake** — after the screen wakes, the wheel is locked until
  you rotate it far enough to intentionally unlock (shown as an animated
  ring), preventing accidental playback/volume changes from pocket presses.
- **Disable Built-in Speaker** — force audio to Bluetooth/output-only setups
  by disabling the device's own speaker.
- **Screen-Off Control** — governs whether the device's own physical
  wheel/buttons do anything with the screen off (default off, to prevent
  in-pocket presses). Does not affect AirPods squeeze controls, which always
  work — see [AirPods Support](#-airpods-support-pro-2--pro-3) above.
- **Bluetooth status icon** now distinguishes Bluetooth being merely "on" from
  actually "connected" to a device, instead of showing one ambiguous icon for
  both.
- **Security & reliability fixes**: a path-traversal fix in file handling, an
  audit of hot paths that were blocking on background threads, and fixes for
  a few silent failures — including the Navidrome web-UI settings screen no
  longer masking real connection failures as a generic error.

---

# 🎨 Y1 Theme Editor User Manual

* You can easily design and customize your own themes using the web editor below:
  👉 [Go to Theme Editor](https://theme-editor-gules.vercel.app/)
<img width="1262" height="746" alt="스크린샷 2026-06-24 220127" src="https://github.com/user-attachments/assets/5a13893e-bb7a-412e-bbe9-41b358cde8e4" />


⚠️ **Note:** This manual and the themes created are only compatible with **Launcher version 0.8 or higher**.

The Y1 Theme Editor is a powerful web-based tool that allows you to intuitively design and build custom themes for your Android device directly from your smartphone or PC browser. Even without coding knowledge, you can create a complete theme package (.zip) simply by dragging, dropping, and adjusting properties.

---

## 🚀 1. Basic Interface (Modes)

You can toggle between two main modes using the buttons at the top left of the editor.

* 🛠️ **Edit Mode:** A design mode where you can click to select elements and freely move them around the screen using **drag and drop**.
* ▶️ **Play Mode:** A mode to simulate how elements will react to wheel scrolling or touching on the actual device. Hover over buttons to preview their focused colors and icon changes.

---

## 🌍 2. Global Settings

Define the core rules and color palette that apply to the entire theme.

* **Theme Name:** Name your theme. (This will also be used as the generated `.zip` file name.)
* **Custom Font:** Upload a custom font (`.ttf`, `.otf`, etc.) to use on the device. It will be applied to the preview window immediately.
* **Colors & Styles (Android Hex):** Set colors using Android's Hex format (`#AARRGGBB`). The editor provides a slider to easily adjust transparency (Alpha).
    * *Text Primary / Secondary:* Main and sub-text colors.
    * *Bg Overlay:* A translucent overlay color placed over the main background wallpaper.
    * *Status Bar Bg:* Background color of the top status bar.
    * *Btn Normal / Focused:* Button background colors for default and selected (focused) states.
* **Default Button Radius:** The default corner roundness applied to all buttons in the theme.

---

## 🧩 3. Element Settings & Types

These are the components (elements) you can place on the screen. Click **[Add New Element]** and change the `Type` to use them. 
*(Note: The editor automatically renders overlapping items in this order: **Design Box ➔ Widget ➔ Button**)*

### 1) Standard Button
* Interactive elements that the user can click or navigate to.
* You can add text and icons, and dictate the wheel scrolling order using the `Focus Index`.

### 2) Widgets
* **Digital Clock:** Displays the current time and date in text format.
* **Analog Clock:** An analog clock with a smooth second hand that inherits your theme's color settings.
* **Circular Battery:** A circular battery gauge with the percentage in the center.
* **Battery Bar:** A horizontal, pill-shaped battery bar.
* **Album Art:** Displays the cover, title, and artist of the currently playing track. You can flexibly align the text relative to the album image (Top, Bottom, Left, Right).

### 3) Design Box
* A pure design element used to partition the screen or create an aesthetic background/frame behind buttons.
* Beyond solid colors, you can **upload high-resolution photos from your PC**. The editor will automatically optimize (compress) them to prevent device overload and create a perfect 'Center Crop' frame matching the box's radius.

---

## 🎯 4. Key Properties Guide

### 📌 Layout & Coordinates
* **Gravity (Anchor):** Determines the reference point for the element's position. (e.g., Selecting `bottom|center_horizontal` and setting Y to 15 will fix the element 15px above the bottom-center of the screen).
* **X / Y:** The offset distance from the chosen Gravity anchor point.
* **Width / Height:** The dimensions of the element. Entering `0` will automatically adjust the size based on its contents (Auto).

### 📌 Text Align
* Specifies the alignment and position of the text inside a button or widget (`Left`, `Center`, `Right`, `Top`, `Bottom`).

### 📌 Focus Index
* A crucial setting exclusively for standard buttons (`Type: button`)!
* It explicitly dictates **the exact order in which the focus moves when spinning the physical wheel** on the Android device. Start from `0` and assign numbers sequentially (`1, 2, 3...`).

### 📌 Action
The direct shortcut command executed on the device when a button is clicked.
* `Now Playing` (Current playback screen)
* `Music Library` (Audio browser)
* `Root Folder` (Device's overall file manager)
* `Bluetooth`, `Wi-Fi Settings`, `Settings Menu` 
* `Web Server` (Wireless file transfer server)
* `Display Brightness`, `Storage Info`, `Date & Time Settings`, etc.

---

## 💾 5. Export & Application

1. Once your design is complete, click the **[Download Theme (.zip)]** button at the bottom right.
2. The editor will gather all the custom fonts, icons, and background images you uploaded and bundle them perfectly into a single `.zip` file.
3. **Unzip (extract)** the downloaded `.zip` file, and move the extracted folder into the `/Y1_Themes/` directory on your Android device. Then, open the app's theme settings and select your new theme to apply it instantly!

💡 **Tip (Import Config):** If you want to modify a theme later, simply extract your downloaded `.zip` file and load the `config.json` file inside it using the **[Import Config]** button. Your workspace and layout will be perfectly restored!



# How to Create a Custom Theme for JJ Launcher for v0.6

Creating a custom theme is the ultimate way to make this DAP (Digital Audio Player) truly yours! The launcher dynamically loads theme resources (colors, icons, and fonts) directly from the device's internal storage.

## 🛠️ Step 1: Create the Theme Folder
1. Connect your device to a PC or open your file manager app.
2. Navigate to the root theme directory: `/storage/sdcard0/Y1_Themes/`
3. Create a new folder inside it and give it a name without spaces (e.g., `/storage/sdcard0/Y1_Themes/Cyberpunk_Dark/`).

## 🛠️ Step 2: Prepare Custom Icons & Fonts (Optional)
Drop your custom assets directly into your new theme folder.

* **Custom Icons:** Must be `.png` files with a transparent background. Name them exactly as follows:
  * `icon_now_playing.png` (Now Playing menu)
  * `icon_music.png` (All Songs / Library menu)
  * `icon_bluetooth.png` (Bluetooth setup)
  * `icon_setting.png` (Settings menu)
  * `icon_radio.png` (FM Radio)
  * `icon_server.png` (Web Server menu)
  * `icon_default_album.png` (Fallback image for missing album art)
* **Custom Font:** Drop a `.ttf` or `.otf` font file into the folder (e.g., `myfont.ttf`).

## 🛠️ Step 3: Create the `config.json` File
This is the core of your theme. Create a text file named exactly `config.json` inside your theme folder and paste the following template:


```json
{
  "name": "My Awesome Theme",
  "font": "myfont.ttf",
  "textPrimary": "#FFFFFF",
  "textSecondary": "#88AADD",
  "bgOverlay": "#DD0F172A",
  "statusBarBg": "#99002255",
  "btnNormal": "#221E40AF",
  "btnFocused": "#DD3B82F6",
  "btnFocusedText": "#000000",
  "button_radius": 30
}
```

## 🛠️ Step 4: Understanding the Configuration Values
* `name`: The display name of your theme in the Settings menu.
* `font`: The exact filename of the custom font (delete this line to use the system default font).
* `textPrimary`: Color for main titles, active text, and the clock.
* `textSecondary`: Color for artist names and inactive text.
* `bgOverlay`: Background color for menus. The first two characters dictate transparency (e.g., `DD`).
* `statusBarBg`: Background color for the top status bar. (Delete this line to default to `bgOverlay`).
* `btnNormal`: Default background color of list buttons.
* `btnFocused`: Highlight color when a button is selected (also applies to battery ring and volume bars).
* `btnFocusedText`: Text color inside a highlighted button.
* `button_radius`: Controls button roundness (`0` = sharp square, `10` = slightly rounded, `30+` = fully rounded pill).

> **💡 Quick Tip:** Always use **8-character Hex Codes** (e.g., `#DD0F172A`) for background colors if you want transparency. The first two characters (`DD`) control the opacity (`00` for invisible, `FF` for solid).


