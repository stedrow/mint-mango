package com.themoon.y1.managers;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LanguageManager {
    private static LanguageManager instance;
    private Context context;

    // 💡 In-memory dictionary where translated words are stored
    private HashMap<String, String> dictionary = new HashMap<>();

    public List<File> availableLangFiles = new ArrayList<>();
    public String currentLangFileName = "English (Default)";

    private LanguageManager(Context context) {
        this.context = context.getApplicationContext();
        loadAvailableLanguages();
    }

    public static LanguageManager getInstance(Context context) {
        if (instance == null) instance = new LanguageManager(context);
        return instance;
    }

    // 1. Scans the folder for .json language pack files.
    public void loadAvailableLanguages() {
        availableLangFiles.clear();
        File langDir = new File("/storage/sdcard0/Y1_Languages");
        if (!langDir.exists()) langDir.mkdirs();

        File[] files = langDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith(".json")) {
                    availableLangFiles.add(f);
                }
            }
        }
    }

    // 2. Reads the selected language pack JSON file and registers it in the dictionary.
    public void applyLanguage(String fileName) {
        dictionary.clear();
        currentLangFileName = fileName;

        if (fileName.equals("English (Default)")) return; // Keep an empty dictionary for the default value (outputs the original text)

        try {
            File f = new File("/storage/sdcard0/Y1_Languages/" + fileName);
            if (f.exists()) {
                FileInputStream fis = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                // A single read() call isn't guaranteed to fill the buffer for larger files,
                // which would silently truncate the language pack.
                int totalRead = 0;
                int r;
                while (totalRead < data.length && (r = fis.read(data, totalRead, data.length - totalRead)) != -1) {
                    totalRead += r;
                }
                fis.close();

                String jsonStr = new String(data, "UTF-8");
                JSONObject json = new JSONObject(jsonStr);

                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String originalText = keys.next();
                    String translatedText = json.getString(originalText);
                    dictionary.put(originalText, translatedText);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🚀 [Core mechanism] Right before drawing text on screen, checks the dictionary and swaps in the translation if one exists!
    public String t(String originalText) {
        if (originalText == null) return "";

        // Search using the original text first, in case invisible characters like icons are mixed in up front
        if (dictionary.containsKey(originalText)) {
            return dictionary.get(originalText);
        }

        // If there's no exact match, trim whitespace from both ends and search again
        String trimmed = originalText.trim();
        if (dictionary.containsKey(trimmed)) {
            // Slight massaging to preserve the original's surrounding whitespace or emoji shape
            return originalText.replace(trimmed, dictionary.get(trimmed));
        }

        // If the word isn't in the translation pack, just output the original English word as-is.
        return originalText;
    }
}