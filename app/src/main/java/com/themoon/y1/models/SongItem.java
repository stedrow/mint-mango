package com.themoon.y1.models;

import java.io.File;

public class SongItem {
    public File file;
    public String title;
    public String artist;
    public String album;

    public SongItem(File f, String t, String a, String al) {
        this.file = f;
        this.title = t;
        this.artist = a;
        this.album = al;
    }
}