package com.themoon.y1.subsonic;

public class SubsonicSong {
    public String id;
    public String title;
    public String artist;
    public String album;
    public String albumId;
    public int track;
    public int durationSecs;
    public long sizeBytes;
    public String suffix;
    public String coverArtId;
    public int year;
    public String genre;
    public String albumArtist; // folder grouping — track artist strings like
                               // "Coldplay • Avicii • Chris Martin" would scatter one album

    // Straight into the music root — downloads are just music, no separate tree
    public static final String DOWNLOAD_ROOT = "/storage/sdcard0/Music";

    /** Where this song lives on the SD card once downloaded in original quality.
     *  Single source of truth for the downloader and play-local-if-downloaded. */
    public String getLocalPath() {
        String artistForPath = albumArtist != null && !albumArtist.isEmpty() ? albumArtist : artist;
        String safeArtist = artistForPath != null ? artistForPath.replaceAll("[/\\\\:*?\"<>|]", "_") : "Unknown";
        String safeAlbum = album != null ? album.replaceAll("[/\\\\:*?\"<>|]", "_") : "Unknown";
        String safeTitle = title != null ? title.replaceAll("[/\\\\:*?\"<>|]", "_") : "track";
        String ext = suffix != null ? suffix : "mp3";
        String trackPrefix = track > 0 ? String.format(java.util.Locale.US, "%02d - ", track) : "";
        return DOWNLOAD_ROOT + "/" + safeArtist + "/" + safeAlbum + "/" + trackPrefix + safeTitle + "." + ext;
    }

    /** Save path for the transcoded (MP3 192kbps) download variant. */
    public String getLocalPathMp3() {
        String p = getLocalPath();
        int dot = p.lastIndexOf('.');
        return (dot > 0 ? p.substring(0, dot) : p) + ".mp3";
    }

    /** Whichever downloaded variant exists — original preferred — or null. */
    public String getExistingLocalPath() {
        java.io.File f = new java.io.File(getLocalPath());
        if (f.exists() && f.length() > 1024) return f.getAbsolutePath();
        java.io.File m = new java.io.File(getLocalPathMp3());
        if (m.exists() && m.length() > 1024) return m.getAbsolutePath();
        return null;
    }

    public boolean isDownloaded() {
        return getExistingLocalPath() != null;
    }
}
