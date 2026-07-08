package com.themoon.y1.managers;

import android.content.SharedPreferences;

import com.themoon.y1.MainActivity;

import java.io.File;

/**
 * One-time-per-version copy of the language packs and theme zips bundled inside the APK's
 * assets/ folder out to SD card storage, gated by a stored "last installed app version" pref so
 * repeat boots on the same version skip the copy. Extracted verbatim from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- both methods are pure Context/AssetManager plumbing with no state
 * of their own -- so they take the MainActivity instance as a parameter like the other
 * extractions. Neither method has an external caller (both are onCreate()-only, called from
 * within MainActivity itself), so MainActivity keeps its own private call sites pointed at this
 * manager rather than exposing public wrappers.
 */
public class BundledAssetsInstaller {
    private static final String TAG = "BundledAssetsInstaller";
    private static BundledAssetsInstaller instance;

    private BundledAssetsInstaller() {}

    public static synchronized BundledAssetsInstaller getInstance() {
        if (instance == null) {
            instance = new BundledAssetsInstaller();
        }
        return instance;
    }

    // 🚀 [New engine] Automatically copies the language packs (assets/languages) bundled inside the APK to device storage!
    public void installBundledLanguages(MainActivity a) {
        SharedPreferences prefs = a.getSharedPreferences("Y1_SETTINGS", MainActivity.MODE_PRIVATE);
        int lastInstalledVersion = prefs.getInt("last_lang_version", 0);

        int currentAppVersion = 1;
        try {
            android.content.pm.PackageInfo pInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            currentAppVersion = pInfo.versionCode;
        } catch (Exception e) {
            android.util.Log.d(TAG, "installBundledLanguages failed", e);
        }

        // 🚀 If the latest version of the default language pack is already installed on the device, skip the duplicate copy to speed things up.
        if (lastInstalledVersion >= currentAppVersion) return;

        // 💡 Note: make sure the path below matches the folder your LanguageManager currently reads files from!
        // It's usually set to something like "/storage/sdcard0/Y1_Languages".
        File targetDir = new File("/storage/sdcard0/Y1_Languages");
        if (!targetDir.exists()) targetDir.mkdirs();

        try {
            android.content.res.AssetManager assetManager = a.getAssets();
            // Sweep up the list of files inside the assets/languages folder.
            String[] files = assetManager.list("languages");

            if (files != null) {
                for (String filename : files) {
                    if (filename.toLowerCase().endsWith(".json")) {
                        java.io.InputStream is = assetManager.open("languages/" + filename);
                        File outFile = new File(targetDir, filename);
                        java.io.FileOutputStream fout = new java.io.FileOutputStream(outFile);

                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = is.read(buffer)) != -1) {
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                        is.close();
                    }
                }
            }
            // 🚀 If the copy succeeded, record the app version in the vault so it's skipped on the next boot.
            prefs.edit().putInt("last_lang_version", currentAppVersion).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Extracts every zip in assets/themes/ to /storage/sdcard0/Y1_Themes/<zip-name-without-extension>.
    public void installBundledThemes(MainActivity a) {
        SharedPreferences prefs = a.getSharedPreferences("Y1_SETTINGS", MainActivity.MODE_PRIVATE);

        // 🚀 [Reworked] Instead of a simple true/false, reads the 'app version number' the theme was last installed under.
        int lastInstalledVersion = prefs.getInt("last_theme_version", 0);

        // Find out the actual version number (versionCode) of the app currently running.
        int currentAppVersion = 1;
        try {
            android.content.pm.PackageInfo pInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            currentAppVersion = pInfo.versionCode; // e.g. 1, 2, 3...
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 🚀 [Core guard] Skip if a theme at the same version or higher is already installed!
        // If the app version has gone up (e.g. 1 -> 2), pass through this condition and overwrite the theme.
        if (lastInstalledVersion >= currentAppVersion) return;

        File targetDir = new File("/storage/sdcard0/Y1_Themes");
        if (!targetDir.exists()) targetDir.mkdirs();

        try {
            android.content.res.AssetManager assetManager = a.getAssets();
            String[] files = assetManager.list("themes");

            if (files != null) {
                for (String filename : files) {
                    if (filename.toLowerCase().endsWith(".zip")) {
                        String folderName = filename.substring(0, filename.lastIndexOf("."));
                        File themeFolder = new File(targetDir, folderName);
                        if (!themeFolder.exists()) themeFolder.mkdirs();

                        java.io.InputStream is = assetManager.open("themes/" + filename);
                        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(is));
                        java.util.zip.ZipEntry ze;

                        while ((ze = zis.getNextEntry()) != null) {
                            File extractFile = new File(themeFolder, ze.getName());
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
                    }
                }
            }

            // 🚀 Once the theme overwrite assembly is fully complete, save the current version to the vault.
            prefs.edit().putInt("last_theme_version", currentAppVersion).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
