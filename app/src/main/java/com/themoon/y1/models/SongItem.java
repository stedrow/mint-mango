package com.themoon.y1.models; // Keep this matching your own package path!

import java.io.File;

public class SongItem {
    public File file;
    public String title;
    public String artist;
    public String album;

    // 🚀 [New] Declares fields to remember the year and genre!
    public String year;
    public String genre;

    // 💡 Default constructor for backward compatibility (guards against errors from M3U parsing, etc.)
    public SongItem(File file, String title, String artist, String album) {
        this.file = file;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.year = "Unknown Year";
        this.genre = "Unknown Genre";
    }

    // 🚀 [New] An evolved constructor that also fills in year and genre!
    public SongItem(File file, String title, String artist, String album, String year, String genre) {
        this.file = file;
        this.title = title;
        this.artist = artist;
        this.album = album;
        // If a value is empty (null), it automatically gets an 'Unknown' label.
        this.year = (year != null && !year.trim().isEmpty()) ? year : "Unknown Year";
        this.genre = (genre != null && !genre.trim().isEmpty()) ? genre : "Unknown Genre";
    }
}