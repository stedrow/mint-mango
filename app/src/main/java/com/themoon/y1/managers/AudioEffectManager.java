package com.themoon.y1.managers;

import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.widget.Toast;
import com.themoon.y1.MainActivity;
import java.io.File;

public class AudioEffectManager {
    private static AudioEffectManager instance;

    private AudioEffectManager() {}

    public static synchronized AudioEffectManager getInstance() {
        if (instance == null) {
            instance = new AudioEffectManager();
        }
        return instance;
    }

    // 🎧 1. Keeps the audio effect hardware chipset ready and reconnects it as needed
    public void ensureAudioEffectsReady() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            // 🚀 [Fix] Instead of the old mediaPlayer, safely fetch the session ID through the new manager's bridge function!
            int sessionId = com.themoon.y1.managers.AudioPlayerManager.getInstance().getAudioSessionId();
            if (main.equalizer == null || main.currentAudioSessionId != sessionId) {
                if (main.equalizer != null) main.equalizer.release();
                main.equalizer = new Equalizer(0, sessionId); main.equalizer.setEnabled(true);
            }
            if (main.bassBoost == null || main.currentAudioSessionId != sessionId) {
                if (main.bassBoost != null) main.bassBoost.release();
                main.bassBoost = new android.media.audiofx.BassBoost(0, sessionId); main.bassBoost.setEnabled(true);
            }
            if (main.virtualizer == null || main.currentAudioSessionId != sessionId) {
                if (main.virtualizer != null) main.virtualizer.release();
                main.virtualizer = new android.media.audiofx.Virtualizer(0, sessionId); main.virtualizer.setEnabled(true);
            }
            main.currentAudioSessionId = sessionId;
        } catch (Exception e) {}
    }

    // 🎧 2. Maps physical frequency pressure for the bass booster and virtualizer (spatial) effect
    public void applyAudioEffects() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        ensureAudioEffectsReady();
        if (main.bassBoost == null || main.virtualizer == null) return;
        try {
            short bassStrength = (short) (main.currentBassBoostStep * 333);
            if (main.currentBassBoostStep == 3) bassStrength = 1000;
            if (main.bassBoost.getStrengthSupported()) main.bassBoost.setStrength(bassStrength);

            short virtStrength = (short) (main.currentVirtualizerStep * 333);
            if (main.currentVirtualizerStep == 3) virtStrength = 1000;
            if (main.virtualizer.getStrengthSupported()) main.virtualizer.setStrength(virtStrength);
        } catch (Exception e) {}
    }

    // 🎧 3. Loads stock preset or custom fader band data
    public void applyEqProfile() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        ensureAudioEffectsReady();
        if (main.equalizer == null) return;
        try {
            if (main.currentEqProfile.startsWith("preset_")) {
                int pIdx = Integer.parseInt(main.currentEqProfile.replace("preset_", ""));
                if (pIdx < main.equalizer.getNumberOfPresets()) {
                    main.equalizer.usePreset((short) pIdx);
                    main.currentEqPresetIndex = pIdx;
                    main.prefs.edit().putInt("eq_preset", main.currentEqPresetIndex).putString("eq_profile_id", main.currentEqProfile).apply();
                }
            } else {
                String name = main.currentEqProfile.replace("custom_", "");
                short bands = main.equalizer.getNumberOfBands();
                for (short i = 0; i < bands; i++) {
                    int level = main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0);
                    main.customBandLevels[i] = level;
                    main.equalizer.setBandLevel(i, (short) level);
                }
                main.prefs.edit().putString("eq_profile_id", main.currentEqProfile).apply();
            }
        } catch (Exception e) {}
    }

    // 🎧 4. Saves to local shared preferences
    public void saveCustomEqProfile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null || main.equalizer == null) return;
        short bands = main.equalizer.getNumberOfBands();
        SharedPreferences.Editor editor = main.prefs.edit();
        for (short i = 0; i < bands; i++) {
            editor.putInt("eq_custom_" + name + "_band_" + i, main.customBandLevels[i]);
        }
        editor.apply();
    }

    // 🎧 5. Permanently deletes both the internal storage entry and the external JSON backup
    public void deleteCustomEqProfile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File file = new File("/storage/sdcard0/Y1_EQs/" + name + ".json");
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String listStr = main.prefs.getString("custom_eq_list", "");
        String[] items = listStr.split(",");
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (!item.equals(name) && !item.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(item);
            }
        }

        SharedPreferences.Editor editor = main.prefs.edit();
        editor.putString("custom_eq_list", sb.toString());

        for (int i = 0; i < 32; i++) {
            editor.remove("eq_custom_" + name + "_band_" + i);
        }
        editor.apply();

        Toast.makeText(main, "Profile Deleted Completely.", Toast.LENGTH_SHORT).show();
    }

    // 🎧 6. [For external sharing] JSON export engine
    public void exportEqProfileToFile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File folder = new File("/storage/sdcard0/Y1_EQs");
            if (!folder.exists()) folder.mkdirs();
            File file = new File(folder, name + ".json");

            org.json.JSONObject json = new org.json.JSONObject();
            json.put("profile_name", name);

            org.json.JSONArray bandsArray = new org.json.JSONArray();
            short bands = (main.equalizer != null) ? main.equalizer.getNumberOfBands() : 5;
            for (short i = 0; i < bands; i++) {
                bandsArray.put(main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0));
            }
            json.put("bands", bandsArray);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(json.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🎧 7. [Two-way sync] Folder scan and auto-import structure
    public void loadAndSyncExternalEqProfiles() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File folder = new File("/storage/sdcard0/Y1_EQs");
            if (!folder.exists()) { folder.mkdirs(); return; }
            File[] files = folder.listFiles();

            java.util.ArrayList<String> validCustomList = new java.util.ArrayList<>();

            if (files != null) {
                SharedPreferences.Editor editor = main.prefs.edit();
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                        try {
                            java.io.FileInputStream fis = new java.io.FileInputStream(f);
                            byte[] data = new byte[(int) f.length()];
                            // A single read() call isn't guaranteed to fill the buffer for larger
                            // files, which would silently truncate the EQ profile into invalid JSON.
                            int totalRead = 0;
                            int r;
                            while (totalRead < data.length && (r = fis.read(data, totalRead, data.length - totalRead)) != -1) {
                                totalRead += r;
                            }
                            fis.close();

                            org.json.JSONObject json = new org.json.JSONObject(new String(data, "UTF-8"));
                            String name = json.optString("profile_name", f.getName().replace(".json", ""));
                            org.json.JSONArray bandsArray = json.optJSONArray("bands");

                            if (bandsArray != null) {
                                for (int i = 0; i < bandsArray.length(); i++) {
                                    editor.putInt("eq_custom_" + name + "_band_" + i, bandsArray.getInt(i));
                                }
                                if (!validCustomList.contains(name)) {
                                    validCustomList.add(name);
                                }
                            }
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < validCustomList.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(validCustomList.get(i));
                }
                editor.putString("custom_eq_list", sb.toString());
                editor.apply();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}