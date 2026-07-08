package com.themoon.y1.managers;

import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;

import java.io.File;
import java.util.ArrayList;

/**
 * Drives the Now Playing screen: player UI refresh, volume overlay, spectrum
 * visualizer, and lyrics (LRC file + embedded ID3 USLT) loading/scroll/highlight.
 * Extracted verbatim from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- the visualizer/lyrics state and player-screen views are
 * shared with MainActivity's progress-tick Runnable (updateProgressTask), which stays
 * in MainActivity since NavidromeManager/MainMenuManager reference it by field name
 * (a.progressHandler / a.updateProgressTask). Takes the MainActivity instance as a
 * parameter like FmRadioUiManager/SettingsUiManager.
 */
public class NowPlayingUiManager {
    private static final String TAG = "NowPlayingUiManager";
    private static NowPlayingUiManager instance;

    public static NowPlayingUiManager getInstance() {
        if (instance == null) instance = new NowPlayingUiManager();
        return instance;
    }

    private NowPlayingUiManager() {}

    public void refreshVisualizerState(MainActivity a) {
        View albumContainer = (View) a.ivAlbumArt.getParent();

        if (a.isVisualizerShowing) {
            albumContainer.setVisibility(View.GONE);

            // If the current track has lyrics? -> turn off the spectrum and turn on the lyrics window!
            if (!a.currentLyrics.isEmpty() || a.plainLyrics != null) {
                if (a.audioVisualizer != null) a.audioVisualizer.setEnabled(false);
                a.visualizerView.setVisibility(View.GONE);
                a.visualizerView.clearAnimation(); // Remove leftover animation artifacts

                a.lyricScrollView.setVisibility(View.VISIBLE);

                if (a.plainLyrics != null && a.currentLyrics.isEmpty()) {
                    a.lyricScrollView.post(new Runnable() {
                        public void run() { a.lyricScrollView.scrollTo(0, 0); }
                    });
                }
            }
            // If the current track has no lyrics? -> turn off the lyrics window and turn on the flashy spectrum!
            else {
                a.lyricScrollView.setVisibility(View.GONE);
                a.visualizerView.setVisibility(View.VISIBLE);
                a.visualizerView.invalidate();
                if (a.audioVisualizer != null) a.audioVisualizer.setEnabled(true);
            }
        } else {
            // If visualization mode is off entirely, hide everything and revert to album art
            a.visualizerView.setVisibility(View.GONE);
            a.lyricScrollView.setVisibility(View.GONE);
            albumContainer.setVisibility(View.VISIBLE);
            if (a.audioVisualizer != null) a.audioVisualizer.setEnabled(false);
        }
    }

    // 💡 Pressing the center button (click) just toggles the switch and calls the refresh worker.
    public void toggleVisualizer(MainActivity a) {
        a.isVisualizerShowing = !a.isVisualizerShowing;
        refreshVisualizerState(a);
    }

    // 💡 [Fix] Function that taps into the audio engine to pull out frequency data
    public void setupVisualizer(final MainActivity a) {
        try {
            // 🚀 [Fully fixed] Build a fresh engine every single time and mount it! (eliminates memory leaks at the source)
            if (a.audioVisualizer != null) {
                a.audioVisualizer.setEnabled(false);
                a.audioVisualizer.release();
                a.audioVisualizer = null;
            }

            // ⭕ [Overwrite with the code below]
            a.audioVisualizer = new android.media.audiofx.Visualizer(com.themoon.y1.managers.AudioPlayerManager.getInstance().getAudioSessionId());
            a.audioVisualizer.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[1]);
            a.audioVisualizer.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(android.media.audiofx.Visualizer visualizer, byte[] waveform,
                        int samplingRate) {
                }

                @Override
                public void onFftDataCapture(android.media.audiofx.Visualizer visualizer, byte[] fft,
                        int samplingRate) {
                    if (a.isVisualizerShowing && a.visualizerView != null
                            && a.visualizerView.getVisibility() == View.VISIBLE) {
                        a.visualizerView.updateVisualizer(fft, samplingRate);
                    }
                }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true);

            if (a.isVisualizerShowing) {
                a.audioVisualizer.setEnabled(true);
            }
        } catch (Exception e) {
            Log.d(TAG, "setupVisualizer failed", e);
        }
    }

    // 🚀 [Lyrics engine] Finds a .lrc file and splits it into time and text stored in memory.
    // 🚀 [Lyrics engine] Looks for a .lrc file first, and if none exists, extracts the lyrics embedded inside the MP3 directly!
    public void loadLyrics(MainActivity a, File audioFile) {
        a.currentLyrics.clear();
        a.lyricTimestamps.clear();
        a.lastLyricIndex = -1;
        a.plainLyrics = null;
        if (a.tvLyrics != null) a.tvLyrics.setText("");

        if (audioFile == null) return;
        String path = audioFile.getAbsolutePath();
        int dotIdx = path.lastIndexOf(".");
        if (dotIdx > 0) {
            String lrcPath = path.substring(0, dotIdx) + ".lrc";
            File lrcFile = new File(lrcPath);

            // 1. Check whether an external .lrc file exists (karaoke mode takes top priority)
            if (lrcFile.exists()) {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(lrcFile), "UTF-8"));
                    String line;
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)");

                    while ((line = br.readLine()) != null) {
                        java.util.regex.Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            int min = Integer.parseInt(matcher.group(1));
                            int sec = Integer.parseInt(matcher.group(2));
                            int ms = Integer.parseInt(matcher.group(3));
                            if (matcher.group(3).length() == 2) ms *= 10;

                            int totalMs = (min * 60 * 1000) + (sec * 1000) + ms;
                            String text = matcher.group(4).trim();

                            if (!text.isEmpty()) {
                                a.currentLyrics.put(totalMs, text);
                            }
                        }
                    }
                    br.close();
                    a.lyricTimestamps = new ArrayList<>(a.currentLyrics.keySet());
                    return; // If it succeeded, end the engine early here!
                } catch (Exception e) {
                    Log.d(TAG, "loadLyrics failed", e);
                }
            }
        }

        // 2. If there's no external .lrc file, mine the lyrics (USLT) embedded inside the MP3 itself!
        a.plainLyrics = extractEmbeddedLyrics(audioFile);
        if (a.plainLyrics != null && !a.plainLyrics.isEmpty()) {
            if (a.tvLyrics != null) {
                // Since embedded lyrics can't move in sync with time, just display them all at once in white (default) color.
                a.tvLyrics.setText(a.plainLyrics);
            }
        }
    }

    // 🚀 [New engine] Precision parser that digs directly into an MP3's ID3 tags to extract the lyrics (USLT)
    public String extractEmbeddedLyrics(File file) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            byte[] header = new byte[10];
            raf.readFully(header);

            // Check whether an ID3v2 tag exists (at the start of the MP3 file)
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int majorVersion = header[3];
                // Compute the tag's total size (using the syncsafe integer scheme)
                int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);

                // Lyrics are usually near the front, so read at most 512KB to fully prevent a memory blowup!
                int readSize = Math.min(tagSize, 512 * 1024);
                byte[] tagData = new byte[readSize];
                int actualRead = raf.read(tagData);
                raf.close();

                int pos = 0;
                while (pos < actualRead - 10) {
                    String frameId = new String(tagData, pos, 4);
                    int frameSize;

                    // Fully handle the frame-size calculation differences between ID3v2.3 and ID3v2.4
                    if (majorVersion == 4) {
                        frameSize = ((tagData[pos+4] & 0x7F) << 21) | ((tagData[pos+5] & 0x7F) << 14) | ((tagData[pos+6] & 0x7F) << 7) | (tagData[pos+7] & 0x7F);
                    } else {
                        frameSize = ((tagData[pos+4] & 0xFF) << 24) | ((tagData[pos+5] & 0xFF) << 16) | ((tagData[pos+6] & 0xFF) << 8) | (tagData[pos+7] & 0xFF);
                    }

                    if (frameSize <= 0 || frameSize > actualRead - pos - 10) break;

                    // 💡 Found a USLT (Unsynchronized lyric/text transcription) lyrics frame!
                    if (frameId.equals("USLT")) {
                        int encoding = tagData[pos + 10]; // encoding scheme
                        int textPos = pos + 14; // skip encoding(1) + language code(3)

                        // Skip the descriptor string (e.g. lyrics title) (look for a null character 0x00)
                        if (encoding == 1 || encoding == 2) { // UTF-16 (2-byte null character)
                            while (textPos < pos + 10 + frameSize - 1) {
                                if (tagData[textPos] == 0 && tagData[textPos+1] == 0) { textPos += 2; break; }
                                textPos++;
                            }
                        } else { // ISO-8859-1 or UTF-8 (1-byte null character)
                            while (textPos < pos + 10 + frameSize) {
                                if (tagData[textPos] == 0) { textPos += 1; break; }
                                textPos++;
                            }
                        }

                        int lyricsLength = (pos + 10 + frameSize) - textPos;
                        if (lyricsLength > 0) {
                            String charset = "UTF-8";
                            if (encoding == 0) charset = "ISO-8859-1";
                            else if (encoding == 1) charset = "UTF-16"; // UTF-16 with BOM
                            else if (encoding == 2) charset = "UTF-16BE"; // UTF-16 Big Endian

                            return new String(tagData, textPos, lyricsLength, charset).trim(); // Lyrics text extraction complete!
                        }
                    }
                    pos += 10 + frameSize; // Quickly skip ahead to the next frame.
                }
            }
            raf.close();
        } catch (Exception e) {
            Log.d(TAG, "extractEmbeddedLyrics failed", e);
        }
        return null;
    }

    public String getRepeatModeText(int mode) {
        switch (mode) {
            case 1:
                return "ONE";
            case 2:
                return "ALL";
            default:
                return "OFF";
        }
    }

    public void updatePlayerStatusIndicators(MainActivity a) {
        try {
            // 1. Shuffle icon setup
            if (a.ivPlayerShuffleStatus != null) {
                if (a.isShuffleMode) {
                    a.ivPlayerShuffleStatus.setImageResource(R.drawable.ic_shuffle);
                    a.ivPlayerShuffleStatus.setVisibility(View.VISIBLE);
                } else {
                    a.ivPlayerShuffleStatus.setVisibility(View.GONE);
                }
            }
            if (a.ivPlayerRepeatStatus != null) {
                if (a.repeatMode == 1) { // repeat one
                    a.ivPlayerRepeatStatus.setImageResource(R.drawable.ic_repeat_one);
                    a.ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else if (a.repeatMode == 2) { // repeat all
                    a.ivPlayerRepeatStatus.setImageResource(R.drawable.ic_repeat);
                    a.ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else { // repeat off
                    a.ivPlayerRepeatStatus.setVisibility(View.GONE);
                }
            }
            if (a.tvPlayerFavoriteStatus != null) {
                String favPath = getCurrentTrackPathForFavorites(a);
                if (favPath != null && a.favoritePaths.contains(favPath)) {
                    a.tvPlayerFavoriteStatus.setVisibility(View.VISIBLE);
                } else {
                    a.tvPlayerFavoriteStatus.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "updatePlayerStatusIndicators failed", e);
        }
    }

    // 🚀 [New] Favorites-toggle function triggered by a long press on the wheel button! (place it among the other functions)
    /**
     * The path favorites should key on for whatever is actually playing.
     * Navidrome streams map to their downloaded file (favorites are local paths);
     * returns null for a stream that hasn't been downloaded.
     */
    public String getCurrentTrackPathForFavorites(MainActivity a) {
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        if (am.isNavidromeMode) {
            if (am.navidromePlaylist.isEmpty()) return null;
            return am.navidromePlaylist.get(am.navidromeIndex).getExistingLocalPath();
        }
        if (a.currentPlaylist.isEmpty() || a.currentIndex < 0 || a.currentIndex >= a.currentPlaylist.size()) return null;
        return a.currentPlaylist.get(a.currentIndex).getAbsolutePath();
    }

    public void toggleFavorite(MainActivity a) {
        String path = getCurrentTrackPathForFavorites(a);
        if (path == null) {
            if (com.themoon.y1.managers.AudioPlayerManager.getInstance().isNavidromeMode) {
                Toast.makeText(a, "⬇ Download this track to add it to Favorites", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        boolean nowFavorite;
        if (a.favoritePaths.contains(path)) {
            a.favoritePaths.remove(path);
            nowFavorite = false;
            Toast.makeText(a, "♡ Removed from Favorites", Toast.LENGTH_SHORT).show();
        } else {
            a.favoritePaths.add(path);
            nowFavorite = true;
            Toast.makeText(a, "♥ Added to Favorites", Toast.LENGTH_SHORT).show();
        }

        try {
            a.libraryCacheDb.setFavorite(path, nowFavorite); // Save it permanently right away!
        } catch (Exception e) {
            Log.d(TAG, "toggleFavorite failed", e);
        }

        updatePlayerStatusIndicators(a); // 💖 Refresh the icon
    }

    public void updatePlayerUI(MainActivity a) {
        try {
            com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
            if (am.isNavidromeMode) {
                // currentPlaylist still points at the last LOCAL track — its lyrics
                // and quality info don't belong to the stream we're playing.
                a.currentLyrics.clear();
                a.plainLyrics = null;
                a.updateNavidromeQualityInfo(am);
            } else if (!a.currentPlaylist.isEmpty() && a.currentIndex >= 0 && a.currentIndex < a.currentPlaylist.size()) {
                File currentFile = a.currentPlaylist.get(a.currentIndex);
                a.updateAudioQualityInfo(currentFile);

                // 🚀 Every time the track changes, check whether a same-named .lrc file exists!
                loadLyrics(a, currentFile);
                refreshVisualizerState(a);
            }

            if (am.isPlaying()) {
                a.ivAlbumArt.setAlpha(1.0f);
                a.ivPauseOverlay.setVisibility(View.GONE);
                a.progressHandler.post(a.updateProgressTask);
            } else {
                a.ivAlbumArt.setAlpha(0.4f);
                a.ivPauseOverlay.setVisibility(View.VISIBLE);
                a.progressHandler.removeCallbacks(a.updateProgressTask);
            }

            a.updateGlobalStatusPlayIcon();
            updatePlayerStatusIndicators(a); // 💡 The function that used to error! (works correctly now)
            // 🚀 [Car Bluetooth integration] Sends the track info and play/pause state to the car in real time!
            a.sendBluetoothMetaToCar();
            a.updateBluetoothPlaybackState(am.isPlaying());
            // 🚀 [Real-time sync] While the main screen is watching Now Playing, if the track changes in the background, refresh the preview image live too!
            if (a.currentScreenState == MainActivity.STATE_MENU && a.ivWidgetFocusImage != null && a.tvFocusPreviewClock != null && a.tvFocusPreviewClock.getVisibility() == View.VISIBLE) {
                for (ThemeManager.MenuElement el : ThemeManager.getCurrentTheme().menuElements) {
                    if ("OPEN_PLAYER".equals(el.action)) {
                        a.updateFocusPreviewLiveContent(el);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "updatePlayerUI failed", e);
        }
    }

    public void adjustVolume(MainActivity a, boolean up) {
        int currentVol = a.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = a.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (up && currentVol < maxVol)
            currentVol++;
        else if (!up && currentVol > 0)
            currentVol--;
        a.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol, 0);

        // 🚀 [Bug fix complete] If the radio is on, also sync-lower the hardware volume on the MediaTek radio-dedicated channel (STREAM_FM = 10)!
        try {
            com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(a);
            if (fm.isPowerUp) {
                int streamFm = 10;
                try { streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null); } catch (Exception e) { Log.d(TAG, "adjustVolume failed", e); }
                int fmMax = a.audioManager.getStreamMaxVolume(streamFm);
                int fmVol = (int) (((float)currentVol / maxVol) * fmMax);
                a.audioManager.setStreamVolume(streamFm, fmVol, 0);
            }
        } catch (Exception e) {
            Log.d(TAG, "adjustVolume failed", e);
        }

        showDynamicVolumeOverlay(a);
    }

    public void showDynamicVolumeOverlay(MainActivity a) {
        int currentVol = a.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        a.layoutVolumeOverlay.setVisibility(View.VISIBLE);
        a.volumeProgress.setProgress(currentVol);
        a.volumeHandler.removeCallbacks(a.hideVolumeTask);
        a.volumeHandler.postDelayed(a.hideVolumeTask, 2000);
    }

    // Reused buffer so the 2x/second progress tick doesn't spin up a Formatter + autobox ints
    // (as String.format does) on every call. Only ever touched from the UI thread.
    private final StringBuilder timeFmtBuilder = new StringBuilder(8);
    public String formatTime(int ms) {
        int s = (ms / 1000) % 60;
        int m = (ms / (1000 * 60)) % 60;
        StringBuilder b = timeFmtBuilder;
        b.setLength(0);
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (s < 10) b.append('0');
        b.append(s);
        return b.toString();
    }

    // Shared by both the screen-off-control path and the normal player path in onKeyDown:
    // first press starts long-press tracking, repeats seek by seekMs at most every 300ms.
    public boolean handleMediaSeekKeyRepeat(MainActivity a, KeyEvent event, int seekMs) {
        if (event.getRepeatCount() == 0) {
            event.startTracking();
            a.isSeekPerformed = false;
        } else {
            long now = System.currentTimeMillis();
            if (now - a.lastSeekTime > 300) {
                a.isSeekPerformed = true;
                a.lastSeekTime = now;
                com.themoon.y1.managers.AudioPlayerManager.getInstance().seekRelative(seekMs);
                a.clickFeedback();
            }
        }
        return true;
    }
}
