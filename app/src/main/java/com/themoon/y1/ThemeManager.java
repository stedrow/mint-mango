package com.themoon.y1;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class ThemeManager {
    private static final String TAG = "ThemeManager";

    public static class MenuElement {
        public String id, type, parentId, liveWidget, visibleOnFocus; // 🚀 [big overhaul] added the liveWidget field
        public int x, y, width, height;
        public String textNormal, textFocused, textRight;
        public String textRightColor, textRightFocusedColor;
        public String iconNormal, iconFocused, previewImage;
        public String action, gravity;
        public int radius, focusIndex, textSize, textSecondarySize;
        public String textPosition, textAlign, bgColor;
        public int padding;
        public int focusOffsetX, focusOffsetY;
        public float focusScale;

        // 🚀 Adds a String liveWidget parameter near the end of the constructor's parameter list!
        public MenuElement(String id, String type, String parentId, String liveWidget, String visibleOnFocus, int x, int y, int width, int height,
                           String textNormal, String textFocused, String textRight,
                           String textRightColor, String textRightFocusedColor,
                           String iconNormal, String iconFocused, String previewImage, String action,
                           String gravity, int radius, int focusIndex, int textSize, int textSecondarySize,
                           String textPosition, String textAlign, String bgColor, int padding, int focusOffsetX, int focusOffsetY, float focusScale) {
            this.id = id; this.type = type; this.parentId = parentId;
            this.liveWidget = liveWidget; // 🚀 mapped
            this.visibleOnFocus = visibleOnFocus; // 🚀 mapped
            this.x = x; this.y = y;
            this.width = width; this.height = height;
            this.textNormal = textNormal; this.textFocused = textFocused; this.textRight = textRight;
            this.textRightColor = textRightColor; this.textRightFocusedColor = textRightFocusedColor;
            this.iconNormal = iconNormal; this.iconFocused = iconFocused; this.previewImage = previewImage;
            this.action = action; this.gravity = gravity; this.radius = radius;
            this.focusIndex = focusIndex; this.textSize = textSize; this.textSecondarySize = textSecondarySize;
            this.textPosition = textPosition; this.textAlign = textAlign; this.bgColor = bgColor;
            this.padding = padding;
            this.focusOffsetX = focusOffsetX; this.focusOffsetY = focusOffsetY;
            this.focusScale = focusScale;
        }
    }

    public static class ThemeData {
        public String folderPath, name;
        public String bgImage = ""; // Optional full-screen background image filename (from config.json "bg_image")
        public android.graphics.Typeface customFont;
        public int textPrimary, textSecondary;
        public int bgOverlay, statusBarBg;
        public int btnNormal, btnFocused, btnFocusedText, buttonRadius;
        public List<MenuElement> menuElements;

        public ThemeData(String folderPath, String name, android.graphics.Typeface customFont,
                         int textPrimary, int textSecondary, int bgOverlay, int statusBarBg,
                         int btnNormal, int btnFocused, int btnFocusedText, int buttonRadius) {
            this.folderPath = folderPath; this.name = name; this.customFont = customFont;
            this.textPrimary = textPrimary; this.textSecondary = textSecondary;
            this.bgOverlay = bgOverlay; this.statusBarBg = statusBarBg;
            this.btnNormal = btnNormal; this.btnFocused = btnFocused;
            this.btnFocusedText = btnFocusedText; this.buttonRadius = buttonRadius;
            this.menuElements = new ArrayList<>();
        }
    }

    public static List<ThemeData> availableThemes = new ArrayList<>();
    private static int currentThemeIndex = 0;

    private static int safeParseColor(String colorStr, int defaultColor) {
        try {
            if (colorStr != null && !colorStr.trim().isEmpty()) {
                return Color.parseColor(colorStr.trim());
            }
        } catch (Exception e) {
            android.util.Log.d(TAG, "Bad color string \"" + colorStr + "\", using default", e);
        }
        return defaultColor;
    }

    // Icon files get re-decoded from disk/resources on every call otherwise, and this is invoked
    // from the menu wheel's onFocusChange handler — i.e. once per scroll step. Cache the decoded
    // bitmap keyed by theme folder + filename so scrolling back and forth over the same items
    // doesn't keep hitting disk/resource decode.
    private static android.util.LruCache<String, android.graphics.Bitmap> iconCache = new android.util.LruCache<>(80);

    // 🚀 [default theme overhaul] Hybrid bitmap loader that fully supports both built-in resources and external folders
    public static android.graphics.Bitmap getCustomIcon(String iconFileName, android.content.Context context, int defaultResId) {
        if (!availableThemes.isEmpty() && iconFileName != null && !iconFileName.isEmpty()) {
            String folder = getCurrentTheme().folderPath;
            String cacheKey = folder + "|" + iconFileName + "|" + defaultResId;
            android.graphics.Bitmap cached = iconCache.get(cacheKey);
            if (cached != null) return cached;

            // 💡 Case 1: For an externally downloaded SD card theme, look up the physical file path
            if (!folder.equals("default")) {
                File iconFile = new File(folder, iconFileName);
                if (iconFile.exists()) {
                    try {
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                        if (bmp != null) iconCache.put(cacheKey, bmp);
                        return bmp;
                    } catch (Exception e) {
                        android.util.Log.d(TAG, "Failed to decode theme icon file " + iconFile, e);
                    }
                }
            }
            // 💡 Case 2: For the app's built-in default theme, resolve the unique resource name back to a file in res/drawable!
            else {
                try {
                    String resName = iconFileName;
                    if (resName.contains(".")) {
                        resName = resName.substring(0, resName.lastIndexOf(".")); // strip the extension (.png)
                    }
                    // Dynamically resolves the unique resource ID (int) that matches this filename in the drawable folder at runtime!
                    int resId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
                    if (resId != 0) {
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(context.getResources(), resId);
                        if (bmp != null) iconCache.put(cacheKey, bmp);
                        return bmp;
                    }
                } catch (Exception e) {
                    android.util.Log.d(TAG, "Failed to resolve/decode built-in icon " + iconFileName, e);
                }
            }
        }
        if (defaultResId != 0) {
            try {
                return android.graphics.BitmapFactory.decodeResource(context.getResources(), defaultResId);
            } catch (Exception e) {
                android.util.Log.d(TAG, "Failed to decode default icon resource", e);
            }
        }
        return null;
    }

    public static int getStatusBarBackgroundColor() {
        if (availableThemes.isEmpty()) return 0x66000000;
        return availableThemes.get(currentThemeIndex).statusBarBg;
    }

    public static void loadThemesFromStorage(File themeFolder) {
        availableThemes.clear();

        ThemeData defaultTheme = new ThemeData("default", "Dark (Default)", android.graphics.Typeface.DEFAULT,
                0xFFFFFFFF, 0xFF888888, 0x88000000, 0x88000000, 0x15FFFFFF, 0xDDFFFFFF, 0xFF000000, 15);

        // 🚀 [bug fix complete] Reordered every element's arguments so their order and count (30) match the constructor format exactly!

        // 1. Place the base border frame and scroll box
        defaultTheme.menuElements.add(new MenuElement("box", "box", "", "none", "", 0, 0, 240, 325, "", "", "", "", "", "", "", "", "NONE", "top|left", 0, -1, 16, -1, "bottom", "left", "#A0000000", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("main_scroll_list", "list_box", "", "none", "", 10, 15, 220, 290, "", "", "", "", "", "", "", "", "NONE", "top|left", -1, -1, 16, -1, "bottom", "left", "", 0, 0, 0, 1.0f));

        // 2. Set of 8 buttons dedicated to the left main list (icons left empty as usual; right-side preview filenames placed normally)
        defaultTheme.menuElements.add(new MenuElement("btn_now", "button", "main_scroll_list", "none", "", 0, 0, -1, 48, "Now Playing", "Now Playing", "〉", "", "", "", "", "music_circle.png", "OPEN_PLAYER", "top|left", -1, 0, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_music", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Music", "Music", "〉", "", "", "", "", "music_list.png", "OPEN_BROWSER", "top|left", -1, 1, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_radio", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Radio", "Radio", "〉", "", "", "", "", "radio_circle.png", "OPEN_RADIO", "top|left", -1, 2, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_audiobook", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Audiobooks", "Audiobooks", "〉", "", "", "", "", "music_list.png", "OPEN_AUDIOBOOKS", "top|left", -1, 3, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_bt", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Bluetooth", "Bluetooth", "〉", "", "", "", "", "bluetooth_circle.png", "OPEN_BLUETOOTH", "top|left", -1, 4, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_wifi", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Wi-Fi", "Wi-Fi", "〉", "", "", "", "", "wifi_circle.png", "OPEN_WIFI", "top|left", -1, 5, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("btn_set", "button", "main_scroll_list", "none", "", 0, 8, -1, 48, "Settings", "Settings", "〉", "", "", "", "", "setting_circle.png", "OPEN_SETTINGS", "top|left", -1, 6, 22, -1, "bottom", "left", "", 0, 0, 0, 1.0f));

        // 3. Set of dynamic right-side, focus-linked widgets (visible_on_focus target mapping synced, matches the 30-parameter formula exactly)
        // 3. Set of dynamic right-side, focus-linked widgets (visible_on_focus target mapping synced, matches the 30-parameter formula exactly)
        defaultTheme.menuElements.add(new MenuElement("widget_clock", "widget_clock", "", "none", "btn_now", 284, 18, 150, 81, "", "", "", "", "", "", "", "", "NONE", "top|left", 0, -1, 16, -1, "bottom", "left", "", 8, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("widget_album", "widget_album", "", "none", "btn_now", 254, 13, 211, 212, "", "", "", "", "", "", "", "", "NONE", "bottom|left", -1, -1, 16, 12, "bottom", "center", "", 0, 0, 0, 1.0f));
        defaultTheme.menuElements.add(new MenuElement("widget_bt_preview", "widget_focus_image", "", "none", "btn_bt", 254, 13, 211, 212, "", "", "", "", "", "", "", "bluetooth_circle.png", "NONE", "top|left", -1, -1, 16, -1, "bottom", "center", "", 0, 0, 0, 1.0f));
        availableThemes.add(defaultTheme);

        if (!themeFolder.exists()) {
            themeFolder.mkdirs();
        }
// 🚀 [new engine activated!] Before loading themes, first scan the folder for any stray '.zip' files!
        File[] allFiles = themeFolder.listFiles();
        if (allFiles != null) {
            for (File file : allFiles) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".zip")) {
                    try {
                        // 1. Strip '.zip' from the zip filename to create the new folder name.
                        String folderName = file.getName().substring(0, file.getName().lastIndexOf("."));
                        File extractDir = new File(themeFolder, folderName);
                        if (!extractDir.exists()) extractDir.mkdirs();

                        // 2. Extract the archive!
                        java.io.FileInputStream fis = new java.io.FileInputStream(file);
                        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(fis));
                        java.util.zip.ZipEntry ze;

                        String extractDirCanonical = extractDir.getCanonicalPath();
                        while ((ze = zis.getNextEntry()) != null) {
                            File extractFile = new File(extractDir, ze.getName());
                            // Zip Slip guard: reject entries whose name (e.g. "../../foo") would
                            // resolve outside extractDir. Theme zips are user-supplied files dropped
                            // onto the SD card, so this can't be trusted.
                            String extractFileCanonical = extractFile.getCanonicalPath();
                            if (!extractFileCanonical.equals(extractDirCanonical) && !extractFileCanonical.startsWith(extractDirCanonical + File.separator)) {
                                zis.closeEntry();
                                continue;
                            }
                            if (ze.isDirectory()) {
                                extractFile.mkdirs();
                            } else {
                                File parent = extractFile.getParentFile();
                                if (!parent.exists()) parent.mkdirs();
                                java.io.FileOutputStream fout = new java.io.FileOutputStream(extractFile);
                                byte[] buffer = new byte[8192];
                                int count;
                                while ((count = zis.read(buffer)) != -1) {
                                    fout.write(buffer, 0, count);
                                }
                                fout.close();
                            }
                            zis.closeEntry();
                        }
                        zis.close();
                        fis.close();

                        // 3. Once extraction finishes cleanly, delete the shell (zip file) to free up space!
                        file.delete();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        File[] folders = themeFolder.listFiles();
        if (folders != null) {
            for (File subFolder : folders) {
                if (subFolder.isDirectory()) {
                    File configFile = new File(subFolder, "config.json");
                    if (configFile.exists() && configFile.isFile()) {
                        try {
                            FileInputStream fis = new FileInputStream(configFile);
                            byte[] data = new byte[(int) configFile.length()];
                            // A single read() call isn't guaranteed to fill the buffer for larger
                            // files, which would silently truncate config.json into invalid JSON.
                            int totalRead = 0;
                            int r;
                            while (totalRead < data.length && (r = fis.read(data, totalRead, data.length - totalRead)) != -1) {
                                totalRead += r;
                            }
                            fis.close();

                            String jsonStr = new String(data, "UTF-8").replace("\uFEFF", "");
                            JSONObject json = new JSONObject(jsonStr);

                            int parsedOverlayBg = safeParseColor(json.optString("bgOverlay"), 0x88000000);
                            int parsedStatusBarBg = safeParseColor(json.optString("statusBarBg"), parsedOverlayBg);
                            int parsedTextPrimary = safeParseColor(json.optString("textPrimary"), 0xFFFFFFFF);
                            int parsedTextSecondary = safeParseColor(json.optString("textSecondary"), 0xFF888888);
                            int parsedBtnNormal = safeParseColor(json.optString("btnNormal"), 0x15FFFFFF);
                            int parsedBtnFocused = safeParseColor(json.optString("btnFocused"), 0xDDFFFFFF);
                            int parsedBtnFocusedText = safeParseColor(json.optString("btnFocusedText"), 0xFF000000);
                            int parsedRadius = json.optInt("button_radius", 15);

                            android.graphics.Typeface parsedFont = android.graphics.Typeface.DEFAULT;
                            if (json.has("font")) {
                                File fontFile = new File(subFolder, json.getString("font"));
                                if (fontFile.exists() && fontFile.isFile()) {
                                    try {
                                        parsedFont = android.graphics.Typeface.createFromFile(fontFile);
                                    } catch (Exception e) {
                                        android.util.Log.d(TAG, "Failed to load theme font " + fontFile, e);
                                    }
                                }
                            }

                            ThemeData theme = new ThemeData(
                                    subFolder.getAbsolutePath(),
                                    json.optString("name", subFolder.getName()),
                                    parsedFont,
                                    parsedTextPrimary, parsedTextSecondary,
                                    parsedOverlayBg, parsedStatusBarBg,
                                    parsedBtnNormal, parsedBtnFocused, parsedBtnFocusedText,
                                    parsedRadius
                            );

                            // Optional full-screen background image declared in config.json (empty if absent)
                            theme.bgImage = json.optString("bg_image", "");

                            if (json.has("main_menu")) {
                                JSONArray menuArray = json.getJSONArray("main_menu");
                                for (int i = 0; i < menuArray.length(); i++) {
                                    JSONObject el = menuArray.getJSONObject(i);

                                    // 🚀 [added] Reads the "live_widget" key from JSON! (defaults to "none" if not present)
                                    theme.menuElements.add(new MenuElement(
                                            el.optString("id", "item_" + i),
                                            el.optString("type", "button"),
                                            el.optString("parent_id", ""),
                                            el.optString("live_widget", "none"),
                                            el.optString("visible_on_focus", ""), // 💡 Reads the visible_on_focus string from JSON!
                                            el.optInt("x", 0),
                                            el.optInt("y", i * 60),
                                            el.optInt("width", 200),
                                            el.optInt("height", 50),
                                            el.optString("text_normal", ""),
                                            el.optString("text_focused", ""),
                                            el.optString("text_right", ""),
                                            el.optString("text_right_color", ""),
                                            el.optString("text_right_focused_color", ""),
                                            el.optString("icon_normal", ""),
                                            el.optString("icon_focused", ""),
                                            el.optString("preview_image", ""),
                                            el.optString("action", "NONE"),
                                            el.optString("gravity", "top|left"),
                                            el.optInt("radius", -1),
                                            el.optInt("focus_index", i + 1),
                                            el.optInt("text_size", -1),
                                            el.optInt("text_secondary_size", -1),
                                            el.optString("text_position", "bottom"),
                                            el.optString("text_align", "center"),
                                            el.optString("bg_color", ""),
                                            el.optInt("padding", 0),
                                            el.optInt("focus_offset_x", 0), // 🚀 read from JSON
                                            el.optInt("focus_offset_y", 0),  // 🚀 read from JSON
                                    (float) el.optDouble("focus_scale", 1.0) // 🚀 read from JSON (defaults to 1.0x if not present)
                                    ));
                                }
                            }
                            availableThemes.add(theme);
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }
        }
    }

    public static void setThemeIndex(int index) { if (index >= 0 && index < availableThemes.size()) currentThemeIndex = index; else currentThemeIndex = 0; }
    public static int getCurrentThemeIndex() { return currentThemeIndex; }
    public static ThemeData getCurrentTheme() { return availableThemes.get(currentThemeIndex); }
    public static android.graphics.Typeface getCustomFont() { if (availableThemes.isEmpty()) return android.graphics.Typeface.DEFAULT; return availableThemes.get(currentThemeIndex).customFont; }
    public static int getTextColorPrimary() { return getCurrentTheme().textPrimary; }
    public static int getTextColorSecondary() { return getCurrentTheme().textSecondary; }
    public static int getOverlayBackgroundColor() { return getCurrentTheme().bgOverlay; }
    public static int getListButtonNormalBg() { return getCurrentTheme().btnNormal; }
    public static int getListButtonFocusedBg() { return getCurrentTheme().btnFocused; }
    public static int getListButtonFocusedTextColor() { return getCurrentTheme().btnFocusedText; }
    public static int getButtonRadius() { return getCurrentTheme().buttonRadius; }
}