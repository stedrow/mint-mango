package com.themoon.y1.managers;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.widget.Toast;

import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioPlayerManager {
    private static AudioPlayerManager instance;
    public SimpleExoPlayer exoPlayer;
    public MediaPlayer legacyPlayer;
    public boolean isUsingLegacyPlayer = false;
    private java.io.FileInputStream currentFileInputStream;

    // Navidrome streaming state
    public boolean isNavidromeMode = false;
    public java.util.List<com.themoon.y1.subsonic.SubsonicSong> navidromePlaylist = new java.util.ArrayList<>();
    public int navidromeIndex = 0;
    private int navidromeErrorCount = 0; // consecutive stream failures — stops the skip loop when WiFi dies

    private float currentSpeed = 1.0f;

    // Set when AapService auto-pauses on AirPods removal (see pauseForAirpods());
    // only that path is allowed to auto-resume, so it never fights a real user pause.
    private boolean pausedByAirpods = false;

    // Position (ms) to seek to the first time playback is resumed after a cold start —
    // set by restoreLastPlaybackState(), consumed and cleared by playOrPauseMusic().
    private int pendingResumePositionMs = 0;
    private long lastPlaybackStateSaveTime = 0;

    // "Disable Built-in Speaker" setting. Deliberately done at the player level
    // (ExoPlayer/MediaPlayer's own volume) rather than via AudioManager: this
    // device's AudioService keeps separate per-output-device volume indices, so
    // AudioManager.setStreamVolume()/setStreamMute() silently hit whichever
    // device happens to be routed at that instant -- sometimes the Bluetooth
    // device's index instead of the speaker's, corrupting it (confirmed via
    // dumpsys audio while chasing this bug). Muting our own player's output
    // is entirely internal to the app and immune to that.
    private boolean speakerMuted = false;

    public void setSpeakerMuted(boolean muted) {
        speakerMuted = muted;
        applyPlayerVolumeState();
    }

    private void applyPlayerVolumeState() {
        float vol = speakerMuted ? 0f : 1f;
        if (exoPlayer != null) exoPlayer.setVolume(vol);
        if (legacyPlayer != null) legacyPlayer.setVolume(vol, vol);
    }

    private AudioPlayerManager() {}



    public static synchronized AudioPlayerManager getInstance() {
        if (instance == null) instance = new AudioPlayerManager();
        return instance;
    }

    public void initPlayer(Context context) {
        if (exoPlayer == null) {
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            15000,  // minBufferMs
                            50000,  // maxBufferMs
                            1500,   // bufferForPlaybackMs (down from 2500ms default)
                            2500)   // bufferForPlaybackAfterRebufferMs (down from 5000ms default)
                    .build();
            exoPlayer = new SimpleExoPlayer.Builder(context.getApplicationContext())
                    .setLoadControl(loadControl)
                    .build();
            // Hold CPU + WiFi awake while playing — without this, Navidrome streams
            // stall when the screen sleeps and the device dozes.
            exoPlayer.setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK);
            exoPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    android.util.Log.d("NaviStream", "onPlayerStateChanged playWhenReady=" + playWhenReady + " state=" + playbackState);
                    if (playbackState == Player.STATE_READY && !isUsingLegacyPlayer) {
                        navidromeErrorCount = 0; // playback actually started
                        if (MainActivity.instance != null) {
                            MainActivity.instance.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (AudioEffectManager.getInstance() != null) AudioEffectManager.getInstance().applyAudioEffects();
                                        MainActivity.instance.setupVisualizer();

                                        int duration = getDuration();
                                        int s = (duration / 1000) % 60;
                                        int m = (duration / (1000 * 60)) % 60;
                                        MainActivity.instance.tvPlayerTimeTotal.setText(String.format(Locale.US, "%02d:%02d", m, s));
                                    } catch (Exception e) {}
                                }
                            });
                        }
                    } else if (playbackState == Player.STATE_ENDED && !isUsingLegacyPlayer) {
                        handleTrackCompletion();
                    }
                    if (MainActivity.instance != null) {
                        MainActivity.instance.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (MainActivity.instance != null) MainActivity.instance.updatePlayerUI();
                            }
                        });
                    }
                }

                @Override
                public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                    android.util.Log.e("NaviStream", "ExoPlayer error: " + error.getMessage(), error);
                    if (!isUsingLegacyPlayer) handleTrackError("Cannot play this file.");
                }
            });
            applyPlayerVolumeState(); // 💡 A freshly created ExoPlayer always starts at volume 1.0, so reapply the mute state
        }
    }

    public void setPlaybackSpeed(float speed) {
        this.currentSpeed = speed;
        if (exoPlayer != null && !isUsingLegacyPlayer) {
            exoPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1.0f));
        } else if (isUsingLegacyPlayer) {
            if (MainActivity.instance != null) {
                MainActivity.instance.runOnUiThread(() ->
                        Toast.makeText(MainActivity.instance, "⚠️ Speed control is disabled for FLAC files on this device.", Toast.LENGTH_SHORT).show()
                );
            }
        }
    }

    public float getCurrentSpeed() { return currentSpeed; }

    private void handleTrackCompletion() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        if (isNavidromeMode) {
            main.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    int repeatMode = main.prefs.getInt("repeat_mode", 0);
                    if (repeatMode == 1) { // repeat one
                        if (isUsingLegacyPlayer && legacyPlayer != null) {
                            legacyPlayer.seekTo(0); legacyPlayer.start();
                        } else if (exoPlayer != null) {
                            exoPlayer.seekTo(0); exoPlayer.setPlayWhenReady(true);
                        }
                    } else if (repeatMode == 0 && navidromeIndex >= navidromePlaylist.size() - 1) {
                        // End of album, repeat off — stop instead of looping forever
                        // (don't restart a stream just to sit paused on track 1)
                        main.isPausedByHand = true;
                        if (!isUsingLegacyPlayer && exoPlayer != null) exoPlayer.setPlayWhenReady(false);
                        main.updatePlayerUI();
                    } else {
                        nextNavidromeSong();
                    }
                }
            });
            return;
        }
        main.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    int repeatMode = main.prefs.getInt("repeat_mode", 0);
                    if (repeatMode == 1) {
                        if (isUsingLegacyPlayer && legacyPlayer != null) {
                            legacyPlayer.seekTo(0); legacyPlayer.start();
                        } else if (exoPlayer != null) {
                            exoPlayer.seekTo(0); exoPlayer.setPlayWhenReady(true);
                        }
                    } else if (repeatMode == 2) {
                        nextTrack();
                    } else {
                        if (main.currentIndex < main.currentPlaylist.size() - 1) {
                            nextTrack();
                        } else {
                            main.currentIndex = 0;
                            prepareMusicTrack(main.currentIndex);
                            main.isPausedByHand = true;
                            main.updatePlayerUI();
                        }
                    }
                } catch (Exception e) { nextTrack(); }
            }
        });
    }

    private void handleTrackError(String errorMsg) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        main.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isNavidromeMode) {
                    // Without this cap, a dead connection makes every track fail in
                    // turn and the album skips in an endless toast loop.
                    navidromeErrorCount++;
                    if (navidromeErrorCount >= Math.max(1, Math.min(navidromePlaylist.size(), 3))) {
                        navidromeErrorCount = 0;
                        Toast.makeText(main, "⚠️ Streaming failed — check WiFi / server. Stopped.", Toast.LENGTH_LONG).show();
                        main.isPausedByHand = true;
                        if (!isUsingLegacyPlayer && exoPlayer != null) exoPlayer.setPlayWhenReady(false);
                        main.updatePlayerUI();
                        return;
                    }
                }
                Toast.makeText(main, "⚠️ " + errorMsg + " Skipping...", Toast.LENGTH_SHORT).show();
                nextTrack();
            }
        });
    }

    public void playTrackList(List<File> list, int index) {
        isNavidromeMode = false;
        pendingResumePositionMs = 0;
        saveAudiobookBookmarkIfNeeded();

        final MainActivity main = MainActivity.instance;
        if (main == null) return;

        initPlayer(main);

        List<File> newList = new java.util.ArrayList<>(list);
        main.originalPlaylist.clear();
        main.originalPlaylist.addAll(newList);
        main.currentPlaylist.clear();
        main.currentPlaylist.addAll(newList);

        boolean isShuffle = main.prefs.getBoolean("shuffle", false);
        if (isShuffle && !newList.isEmpty()) {
            File currentSong = main.originalPlaylist.get(index);
            java.util.Collections.shuffle(main.currentPlaylist);
            main.currentIndex = main.currentPlaylist.indexOf(currentSong);
            if (main.currentIndex == -1) main.currentIndex = 0;
        } else {
            main.currentIndex = index;
        }

        main.isPausedByHand = false; // 🚀 Flip the switch ahead of time!
        prepareMusicTrack(main.currentIndex);
        main.updatePlayerUI(); // 🚀 Start the timer immediately!
    }

    public void playTrackListWithOffset(List<File> list, int index, int offsetMs) {
        playTrackList(list, index);
        if (offsetMs > 0) {
            try {
                seekRelative(offsetMs - getCurrentPosition());
                final int totalSec = offsetMs / 1000;
                final int min = totalSec / 60;
                final int sec = totalSec % 60;
                if (MainActivity.instance != null) {
                    MainActivity.instance.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.widget.Toast toast = android.widget.Toast.makeText(MainActivity.instance,
                                        "🎧 Resuming playback from " + min + "m " + sec + "s",
                                        android.widget.Toast.LENGTH_SHORT);
                                android.widget.LinearLayout toastLayout = (android.widget.LinearLayout) toast.getView();
                                android.widget.TextView toastTV = (android.widget.TextView) toastLayout.getChildAt(0);
                                toastTV.setTextSize(18f);
                                toast.show();
                            } catch (Exception e) {}
                        }
                    });
                }
            } catch (Exception e) {}
        }
    }

    public void setupFolderPlaylist(File clickedFile, File parentFolder) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;

        List<File> folderAudio = new java.util.ArrayList<>();
        File[] files = parentFolder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isAudioFile(f.getName())) folderAudio.add(f);
            }
            java.util.Collections.sort(folderAudio, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
        }
        int idx = folderAudio.indexOf(clickedFile);
        if (idx == -1) idx = 0;
        playTrackList(folderAudio, idx);
    }

    private boolean isAudioFile(String name) {
        name = name.toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".m4a") || name.endsWith(".aac");
    }

    public void playOrPauseMusic() {
        pausedByAirpods = false; // any manual toggle cancels a pending AirPods auto-resume

        // Nothing loaded yet this session (e.g. right after a cold start) but a track was
        // restored from the last session by restoreLastPlaybackState() — start it now,
        // seeking to where playback left off, instead of silently doing nothing.
        if (legacyPlayer == null && exoPlayer == null) {
            MainActivity main = MainActivity.instance;
            if (main != null && main.currentPlaylist != null && !main.currentPlaylist.isEmpty()) {
                int offset = pendingResumePositionMs;
                pendingResumePositionMs = 0;
                playTrackListWithOffset(main.currentPlaylist, main.currentIndex, offset);
            }
            return;
        }

        if (isUsingLegacyPlayer && legacyPlayer != null) {
            if (legacyPlayer.isPlaying()) {
                saveAudiobookBookmarkIfNeeded();
                legacyPlayer.pause();
                if (MainActivity.instance != null) MainActivity.instance.isPausedByHand = true;
            } else {
                legacyPlayer.start();
                if (MainActivity.instance != null) MainActivity.instance.isPausedByHand = false;
            }
        } else if (exoPlayer != null) {
            if (exoPlayer.getPlayWhenReady()) {
                saveAudiobookBookmarkIfNeeded();
                exoPlayer.setPlayWhenReady(false);
                if (MainActivity.instance != null) MainActivity.instance.isPausedByHand = true;
            } else {
                exoPlayer.setPlayWhenReady(true);
                if (MainActivity.instance != null) MainActivity.instance.isPausedByHand = false;
            }
        }
        if (MainActivity.instance != null) MainActivity.instance.updatePlayerUI();
    }

    /** Auto-pause on AirPods removal (AapService). Never overrides an already-paused state. */
    public void pauseForAirpods() {
        if (!isPlaying()) return;
        saveAudiobookBookmarkIfNeeded();
        if (isUsingLegacyPlayer && legacyPlayer != null) {
            legacyPlayer.pause();
        } else if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
        pausedByAirpods = true;
        if (MainActivity.instance != null) MainActivity.instance.updatePlayerUI();
    }

    /** Resumes only if {@link #pauseForAirpods()} was the one that paused (never fights a user pause). */
    public void resumeForAirpods() {
        if (!pausedByAirpods) return;
        pausedByAirpods = false;
        if (isPlaying()) return;
        if (isUsingLegacyPlayer && legacyPlayer != null) {
            legacyPlayer.start();
        } else if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(true);
        }
        if (MainActivity.instance != null) MainActivity.instance.updatePlayerUI();
    }

    public void nextTrack() {
        if (isNavidromeMode) { nextNavidromeSong(); return; }
        saveAudiobookBookmarkIfNeeded();
        MainActivity main = MainActivity.instance;
        if (main == null || main.currentPlaylist.isEmpty()) return;
        main.lastTrackChangeTime = System.currentTimeMillis();
        main.currentIndex = (main.currentIndex + 1) % main.currentPlaylist.size();
        prepareMusicTrack(main.currentIndex);
        main.updatePlayerUI();
    }

    public void prevTrack() {
        if (isNavidromeMode) { prevNavidromeSong(); return; }
        saveAudiobookBookmarkIfNeeded();
        MainActivity main = MainActivity.instance;
        if (main == null || main.currentPlaylist.isEmpty()) return;
        main.lastTrackChangeTime = System.currentTimeMillis();
        main.currentIndex--;
        if (main.currentIndex < 0) main.currentIndex = main.currentPlaylist.size() - 1;
        prepareMusicTrack(main.currentIndex);
        main.updatePlayerUI();
    }

    public void seekRelative(int offsetMs) {
        long currentPos = getCurrentPosition();
        long duration = getDuration();
        long targetPos = currentPos + offsetMs;
        if (targetPos < 0) targetPos = 0;
        if (targetPos > duration && duration > 0) targetPos = duration;

        if (isUsingLegacyPlayer && legacyPlayer != null) {
            legacyPlayer.seekTo((int) targetPos);
        } else if (exoPlayer != null) {
            exoPlayer.seekTo(targetPos);
        }
    }

    // 🚀 [restored stock behavior and full sync] Completely eliminated the flicker delay!
    public void prepareMusicTrack(int index) {
        final MainActivity main = MainActivity.instance;
        if (main == null || main.currentPlaylist.isEmpty()) return;

        final File track = main.currentPlaylist.get(index);
        main.lastAlbumArtBytes = null;
        main.currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;

        if (!track.exists() || track.length() < 1024) {
            main.tvPlayerTitle.setText("Corrupted File");
            main.tvPlayerArtist.setText("Skipping...");
            main.ivAlbumArt.setImageResource(R.drawable.default_album);

            main.consecutiveErrorCount++;

            if (main.consecutiveErrorCount >= main.currentPlaylist.size()) {
                Toast.makeText(main, "❌ All tracks failed. Playback stopped.", Toast.LENGTH_SHORT).show();
                main.isPausedByHand = true;
                main.updatePlayerUI();
                main.consecutiveErrorCount = 0;
            } else {
                Toast.makeText(main, "Corrupted file detected. Skipping...", Toast.LENGTH_SHORT).show();
                new Handler().postDelayed(() -> nextTrack(), 1500);
            }
            return;
        }

        // 🚀 Removed the separate Thread and process this immediately on the main thread, completely blocking the flicker/delay!
        main.tvPlayerTitle.setText(track.getName());
        main.tvPlayerArtist.setText("Loading...");
        main.ivAlbumArt.setImageResource(R.drawable.default_album);
        main.ivPlayerBgBlur.setImageResource(0);
        main.playerProgress.setProgress(0);
        main.tvPlayerTimeCurrent.setText("00:00");
        main.tvPlayerTimeTotal.setText("00:00");

        String ext = track.getName().toLowerCase();
        isUsingLegacyPlayer = ext.endsWith(".flac"); // FLAC detector

        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        java.io.FileInputStream fisMmr = null;
        try {
            // 🚀 Don't block FLAC files — parse their tags normally too!
            fisMmr = new java.io.FileInputStream(track);
            mmr.setDataSource(fisMmr.getFD());

            String t = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            main.lastAlbumArtBytes = mmr.getEmbeddedPicture();

            String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "").replace(".wav", "").replace(".m4a", "");
            File coverFile = new File("/storage/sdcard0/Y1_Covers", safeFileName + ".jpg");

            com.themoon.y1.db.LibraryCacheDb.MetaOverride metaOverride = main.libraryCacheDb.getMetaOverride(track.getAbsolutePath());
            if (metaOverride != null) {
                if (metaOverride.title != null) t = metaOverride.title;
                if (metaOverride.artist != null) a = metaOverride.artist;
            }

            boolean hasValidTags = (t != null && !t.trim().isEmpty() && a != null && !a.trim().isEmpty() && !a.equalsIgnoreCase("Unknown Artist"));

            if (t != null && !t.trim().isEmpty()) main.tvPlayerTitle.setText(t);
            else main.tvPlayerTitle.setText(safeFileName);

            if (a != null && !a.trim().isEmpty()) main.tvPlayerArtist.setText(a);
            else main.tvPlayerArtist.setText("Unknown Artist");

            // 🚀 Synchronous rendering transitions smoothly, 100% flicker-free.
            if (main.lastAlbumArtBytes != null && main.lastAlbumArtBytes.length > 0) {
                main.updateMainMenuBackground();
                main.refreshNowPlayingPreview();
                try {
                    android.graphics.BitmapFactory.Options optsCenter = new android.graphics.BitmapFactory.Options();
                    optsCenter.inSampleSize = 2;
                    android.graphics.Bitmap bmpCenter = android.graphics.BitmapFactory.decodeByteArray(main.lastAlbumArtBytes, 0, main.lastAlbumArtBytes.length, optsCenter);
                    main.ivAlbumArt.setImageBitmap(bmpCenter);

                    android.graphics.BitmapFactory.Options optsBg = new android.graphics.BitmapFactory.Options();
                    optsBg.inSampleSize = 4;
                    android.graphics.Bitmap sourceBg = android.graphics.BitmapFactory.decodeByteArray(main.lastAlbumArtBytes, 0, main.lastAlbumArtBytes.length, optsBg);
                    android.graphics.Bitmap blurredBg = main.applyGaussianBlur(sourceBg);
                    main.ivPlayerBgBlur.setImageBitmap(blurredBg);
                    if (sourceBg != blurredBg) sourceBg.recycle();

                    try {
                        int centerX = bmpCenter.getWidth() / 2;
                        int centerY = (int) (bmpCenter.getHeight() * 0.8);
                        main.currentAlbumColor = bmpCenter.getPixel(centerX, centerY) | 0xFF000000;
                    } catch (Exception e) {
                        main.currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
                    }
                } catch (Throwable e) {}
            } else if (coverFile.exists()) {
                main.applyCachedCoverArt(coverFile.getAbsolutePath());
            } else {
                main.ivAlbumArt.setImageResource(R.drawable.default_album);
                main.currentAlbumColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
                main.ivPlayerBgBlur.setImageResource(0);
                main.updateMainMenuBackground();
                main.refreshNowPlayingPreview();

                boolean isAutoFetchEnabled = main.prefs.getBoolean("auto_fetch", true);
                if (isAutoFetchEnabled) {
                    String searchQuery = hasValidTags ? (a + " " + t) : safeFileName.replace("-", " ").replace("_", " ");
                    main.fetchTrackInfoFromInternet(track, searchQuery, hasValidTags, t, a);
                }
            }
        } catch (Throwable t) {
        } finally {
            if (fisMmr != null) try { fisMmr.close(); } catch (Exception e) {}
            try { mmr.release(); } catch (Exception e) {}
        }

        // 🚀 Engine startup section
        try {
            if (isUsingLegacyPlayer) {
                // FLAC: special engine for deadlock recovery
                if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }

                if (legacyPlayer == null) {
                    legacyPlayer = new MediaPlayer();
                    legacyPlayer.setWakeMode(main.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                    legacyPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    legacyPlayer.setOnCompletionListener(mp -> handleTrackCompletion());
                    legacyPlayer.setOnErrorListener((mp, what, extra) -> {
                        handleTrackError("Legacy Player Error: " + what);
                        return true;
                    });
                } else {
                    legacyPlayer.reset();
                }

                if (currentFileInputStream != null) {
                    try { currentFileInputStream.close(); } catch (Exception e) {}
                }
                currentFileInputStream = new java.io.FileInputStream(track);
                legacyPlayer.setDataSource(currentFileInputStream.getFD());
                legacyPlayer.prepare(); // 💡 loaded and ready!
                applyPlayerVolumeState(); // 💡 A new MediaPlayer always starts at volume 1.0, so reapply the mute state

                // 🚀 [core logic 1] Right before playback, check if this is an audiobook and force-jump to the saved position!
                com.themoon.y1.db.LibraryCacheDb.Bookmark savedBookmark = main.libraryCacheDb.getBookmark(track.getAbsolutePath());
                int savedPos = savedBookmark != null ? savedBookmark.posMs : 0;
                if (savedPos > 0 && (main.isAudiobookLibraryMode || track.getAbsolutePath().contains("/Audiobooks"))) {
                    legacyPlayer.seekTo(savedPos);
                }

                if (!main.isPausedByHand) legacyPlayer.start();

                if (AudioEffectManager.getInstance() != null) AudioEffectManager.getInstance().applyAudioEffects();
                main.setupVisualizer();

                int duration = legacyPlayer.getDuration();
                int s = (duration / 1000) % 60;
                int m = (duration / (1000 * 60)) % 60;
                main.tvPlayerTimeTotal.setText(String.format(Locale.US, "%02d:%02d", m, s));

            } else {
                // MP3/WAV: ultra-fast ExoPlayer
                if (legacyPlayer != null) { legacyPlayer.stop(); legacyPlayer.reset(); }

                if (exoPlayer == null) initPlayer(main.getApplicationContext());
                else exoPlayer.stop();

                com.google.android.exoplayer2.MediaItem mediaItem = com.google.android.exoplayer2.MediaItem.fromUri(Uri.fromFile(track));
                DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(main, Util.getUserAgent(main, "Y1_Launcher"));
                DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
                MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem);

                exoPlayer.setMediaSource(mediaSource);
                exoPlayer.prepare(); // 💡 loaded and ready!

                // 🚀 [core logic 2] Right before playback, check if this is an audiobook and force-jump to the saved position!
                com.themoon.y1.db.LibraryCacheDb.Bookmark savedBookmark = main.libraryCacheDb.getBookmark(track.getAbsolutePath());
                int savedPos = savedBookmark != null ? savedBookmark.posMs : 0;
                if (savedPos > 0 && (main.isAudiobookLibraryMode || track.getAbsolutePath().contains("/Audiobooks"))) {
                    exoPlayer.seekTo(savedPos);
                }

                exoPlayer.setPlaybackParameters(new PlaybackParameters(currentSpeed, 1.0f));

                if (!main.isPausedByHand) exoPlayer.setPlayWhenReady(true);
            }

            main.consecutiveErrorCount = 0;
            String currentTrackNum = String.format(Locale.US, "%02d", index + 1);
            String totalTrackNum = String.format(Locale.US, "%02d", main.currentPlaylist.size());
            main.tvPlayerTrackCount.setText(currentTrackNum + " / " + totalTrackNum);

        } catch (Throwable e) {
            main.consecutiveErrorCount++;
            String failReason = "Unknown Error";
            if (e instanceof OutOfMemoryError) failReason = "Album Art is too huge!";
            else if (e instanceof java.io.FileNotFoundException) failReason = "File not found";
            else if (e instanceof java.io.IOException) failReason = "Broken file";

            main.tvPlayerTitle.setText("Load Failed ❌");
            main.tvPlayerArtist.setText(failReason);
            Toast.makeText(main, "🚨 " + failReason, Toast.LENGTH_SHORT).show();

            if (main.consecutiveErrorCount >= main.currentPlaylist.size()) {
                main.isPausedByHand = true;
                main.updatePlayerUI();
                main.consecutiveErrorCount = 0;
            } else {
                new Handler().postDelayed(() -> nextTrack(), 2000);
            }
        }
    }

    /** Persists what's currently loaded (playlist/index/position) so it survives a restart. */
    private void persistCurrentPlaybackState() {
        try {
            MainActivity main = MainActivity.instance;
            if (main == null || main.libraryCacheDb == null) return;
            if (main.currentPlaylist == null || main.currentPlaylist.isEmpty()) return;
            if (main.currentIndex < 0 || main.currentIndex >= main.currentPlaylist.size()) return;
            List<String> paths = new ArrayList<>();
            for (File f : main.currentPlaylist) paths.add(f.getAbsolutePath());
            main.libraryCacheDb.savePlayerState(paths, main.currentIndex, getCurrentPosition(), main.isAudiobookLibraryMode);
        } catch (Exception ignored) {}
    }

    /** Same as {@link #persistCurrentPlaybackState()} but throttled — call from a frequent tick. */
    public void maybeSavePlaybackStateThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastPlaybackStateSaveTime < 5000) return;
        lastPlaybackStateSaveTime = now;
        persistCurrentPlaybackState();
    }

    /**
     * Restores the last playlist/track/position from the DB on cold start — populates the
     * player UI with the last-played track's info immediately, without starting playback.
     * The saved position is actually seeked to the first time the user presses play
     * (see playOrPauseMusic()), since eagerly starting audio on launch would be jarring.
     */
    public void restoreLastPlaybackState() {
        try {
            MainActivity main = MainActivity.instance;
            if (main == null || main.libraryCacheDb == null) return;
            com.themoon.y1.db.LibraryCacheDb.PlayerState state = main.libraryCacheDb.loadPlayerState();
            if (state == null || state.playlist.isEmpty()) return;
            if (state.index < 0 || state.index >= state.playlist.size()) return;

            List<File> restoredPlaylist = new ArrayList<>();
            for (String p : state.playlist) restoredPlaylist.add(new File(p));
            File track = restoredPlaylist.get(state.index);
            if (!track.exists()) return;

            main.originalPlaylist.clear();
            main.originalPlaylist.addAll(restoredPlaylist);
            main.currentPlaylist.clear();
            main.currentPlaylist.addAll(restoredPlaylist);
            main.currentIndex = state.index;
            main.isAudiobookLibraryMode = state.isAudiobook;
            main.isPausedByHand = true;
            pendingResumePositionMs = state.positionMs;

            displayTrackMetadataOnly(track);
        } catch (Exception ignored) {}
    }

    /** Populates the player/preview UI (title, artist, art) for a track without touching the audio engine. */
    private void displayTrackMetadataOnly(File track) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "").replace(".wav", "").replace(".m4a", "");
            String t = null, a = null;
            byte[] embeddedArt = null;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            java.io.FileInputStream fis = null;
            try {
                fis = new java.io.FileInputStream(track);
                mmr.setDataSource(fis.getFD());
                t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                embeddedArt = mmr.getEmbeddedPicture();
            } catch (Exception ignored) {
            } finally {
                if (fis != null) try { fis.close(); } catch (Exception e) {}
                try { mmr.release(); } catch (Exception e) {}
            }

            com.themoon.y1.db.LibraryCacheDb.MetaOverride metaOverride = main.libraryCacheDb.getMetaOverride(track.getAbsolutePath());
            if (metaOverride != null) {
                if (metaOverride.title != null) t = metaOverride.title;
                if (metaOverride.artist != null) a = metaOverride.artist;
            }

            main.tvPlayerTitle.setText(t != null && !t.trim().isEmpty() ? t : safeFileName);
            main.tvPlayerArtist.setText(a != null && !a.trim().isEmpty() ? a : "Unknown Artist");

            main.lastAlbumArtBytes = embeddedArt;
            if (embeddedArt != null && embeddedArt.length > 0) {
                main.updateMainMenuBackground();
                main.refreshNowPlayingPreview();
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                main.ivAlbumArt.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(embeddedArt, 0, embeddedArt.length, opts));
            } else {
                File coverFile = new File("/storage/sdcard0/Y1_Covers", safeFileName + ".jpg");
                if (coverFile.exists()) {
                    main.applyCachedCoverArt(coverFile.getAbsolutePath());
                } else {
                    main.ivAlbumArt.setImageResource(R.drawable.default_album);
                    main.updateMainMenuBackground();
                    main.refreshNowPlayingPreview();
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveAudiobookBookmarkIfNeeded() {
        try {
            // In Navidrome mode currentPlaylist/currentIndex still point at the last
            // LOCAL track — saving here would write the stream's position into that
            // file's bookmark and corrupt audiobook resume points.
            if (isNavidromeMode) return;
            persistCurrentPlaybackState();
            MainActivity main = MainActivity.instance;
            if (main != null && main.currentPlaylist != null && !main.currentPlaylist.isEmpty()) {
                if (main.currentIndex >= 0 && main.currentIndex < main.currentPlaylist.size()) {
                    String filePath = main.currentPlaylist.get(main.currentIndex).getAbsolutePath();

                    // Only save when it's in the Audiobooks folder or audiobook mode is active!
                    if (filePath.startsWith("/storage/sdcard0/Audiobooks") || main.isAudiobookLibraryMode) {
                        AudiobookManager.getInstance(main).saveBookmark(filePath, getCurrentPosition(), main.currentIndex);

                        // 🚀 [core addition] Quietly save "current position" and "total length" keyed by the file path, for drawing the progress bar.
                        main.libraryCacheDb.setBookmark(filePath, getCurrentPosition(), getDuration());
                    }
                }
            }
        } catch (Exception e) {}
    }

    public int getCurrentPosition() {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer.getCurrentPosition();
        if (!isUsingLegacyPlayer && exoPlayer != null) {
            long pos = exoPlayer.getCurrentPosition();
            return pos < 0 ? 0 : (int) pos;
        }
        return 0;
    }

    public int getDuration() {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer.getDuration();
        if (!isUsingLegacyPlayer && exoPlayer != null) {
            long duration = exoPlayer.getDuration();
            return duration < 0 ? 0 : (int) duration;
        }
        return 0;
    }

    public boolean isPlaying() {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer.isPlaying();
        if (!isUsingLegacyPlayer && exoPlayer != null) return exoPlayer.getPlayWhenReady();
        return false;
    }

    public int getAudioSessionId() {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer.getAudioSessionId();
        if (!isUsingLegacyPlayer && exoPlayer != null) return exoPlayer.getAudioSessionId();
        return 0;
    }

    public void playNavidromeSong(Context context, com.themoon.y1.subsonic.SubsonicSong song, String streamUrl) {
        saveAudiobookBookmarkIfNeeded();
        isNavidromeMode = true;
        isUsingLegacyPlayer = false;

        final MainActivity main = MainActivity.instance;
        if (main == null) return;

        if (legacyPlayer != null) { try { legacyPlayer.stop(); legacyPlayer.reset(); } catch (Exception ignored) {} }
        initPlayer(context);
        if (exoPlayer != null) exoPlayer.stop();

        main.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                main.tvPlayerTitle.setText(song.title != null ? song.title : "Unknown");
                main.tvPlayerArtist.setText(song.artist != null ? song.artist : "Unknown");
                main.ivAlbumArt.setImageResource(com.themoon.y1.R.drawable.default_album);
                main.ivPlayerBgBlur.setImageResource(0);
                main.playerProgress.setProgress(0);
                main.tvPlayerTimeCurrent.setText("00:00");
                int totalSec = song.durationSecs;
                int m = totalSec / 60, s = totalSec % 60;
                main.tvPlayerTimeTotal.setText(String.format(Locale.US, "%02d:%02d", m, s));
                String countCurrent = String.format(Locale.US, "%02d", navidromeIndex + 1);
                String countTotal = String.format(Locale.US, "%02d", navidromePlaylist.size());
                main.tvPlayerTrackCount.setText(countCurrent + " / " + countTotal);
                main.loadNavidromeCoverArt(song);
            }
        });

        // Already downloaded (either quality variant)? Play the local file — zero
        // bandwidth, works offline. playLocalFile handles the FLAC/legacy split.
        final String existingPath = song.getExistingLocalPath();
        if (existingPath != null) {
            playLocalFile(context, new java.io.File(existingPath));
            main.runOnUiThread(new Runnable() {
                @Override public void run() {
                    main.isPausedByHand = false;
                    main.consecutiveErrorCount = 0;
                    main.updatePlayerUI();
                }
            });
            return;
        }

        // Stream via local HTTP proxy (port 8081) — ExoPlayer uses plain HTTP to localhost;
        // the proxy fetches from Navidrome over HTTPS using our SSL-fixed HttpURLConnection.
        String proxyUrl = "http://127.0.0.1:8081/stream?id=" + song.id;

        try {
            com.google.android.exoplayer2.MediaItem mediaItem =
                    com.google.android.exoplayer2.MediaItem.fromUri(android.net.Uri.parse(proxyUrl));
            DataSource.Factory dsf = new DefaultDataSourceFactory(context.getApplicationContext(),
                    com.google.android.exoplayer2.util.Util.getUserAgent(context, "Y1Player"));
            MediaSource src = new ProgressiveMediaSource.Factory(dsf, new DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true))
                    .createMediaSource(mediaItem);
            exoPlayer.setMediaSource(src);
            exoPlayer.prepare();
            exoPlayer.setPlaybackParameters(new PlaybackParameters(currentSpeed, 1.0f));
            exoPlayer.setPlayWhenReady(true);

            main.runOnUiThread(new Runnable() {
                @Override public void run() {
                    main.isPausedByHand = false;
                    main.consecutiveErrorCount = 0;
                    main.updatePlayerUI();
                }
            });
        } catch (Throwable e) {
            main.runOnUiThread(new Runnable() {
                @Override public void run() {
                    android.widget.Toast.makeText(main, "Stream error: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void playLocalFile(Context context, java.io.File file) {
        String ext = file.getName().toLowerCase();
        boolean useExo = !ext.endsWith(".flac");
        isUsingLegacyPlayer = !useExo;
        initPlayer(context);

        if (useExo) {
            if (exoPlayer != null) exoPlayer.stop();
            com.google.android.exoplayer2.MediaItem mediaItem =
                    com.google.android.exoplayer2.MediaItem.fromUri(android.net.Uri.fromFile(file));
            DataSource.Factory dsf = new DefaultDataSourceFactory(context.getApplicationContext(),
                    com.google.android.exoplayer2.util.Util.getUserAgent(context, "Y1Player"));
            MediaSource src = new ProgressiveMediaSource.Factory(dsf, new DefaultExtractorsFactory())
                    .createMediaSource(mediaItem);
            exoPlayer.setMediaSource(src);
            exoPlayer.prepare();
            exoPlayer.setPlaybackParameters(new PlaybackParameters(currentSpeed, 1.0f));
            exoPlayer.setPlayWhenReady(true);
        } else {
            try {
                if (legacyPlayer == null) {
                    legacyPlayer = new android.media.MediaPlayer();
                    legacyPlayer.setWakeMode(context.getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
                    legacyPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                    legacyPlayer.setOnCompletionListener(mp -> handleTrackCompletion());
                    legacyPlayer.setOnErrorListener((mp, what, extra) -> {
                        handleTrackError("Cannot play this file.");
                        return true;
                    });
                } else {
                    legacyPlayer.reset();
                }
                legacyPlayer.setDataSource(file.getAbsolutePath());
                legacyPlayer.prepare();
                legacyPlayer.start();
            } catch (Exception e) {
                handleTrackError("Cannot play: " + e.getMessage());
            }
        }
    }

    public void nextNavidromeSong() {
        if (navidromePlaylist.isEmpty()) return;
        navidromeIndex = (navidromeIndex + 1) % navidromePlaylist.size();
        com.themoon.y1.subsonic.SubsonicSong song = navidromePlaylist.get(navidromeIndex);
        String url = com.themoon.y1.subsonic.SubsonicClient.getInstance().getStreamUrl(song.id);
        MainActivity main = MainActivity.instance;
        if (main != null) playNavidromeSong(main, song, url);
    }

    public void prevNavidromeSong() {
        if (navidromePlaylist.isEmpty()) return;
        navidromeIndex--;
        if (navidromeIndex < 0) navidromeIndex = navidromePlaylist.size() - 1;
        com.themoon.y1.subsonic.SubsonicSong song = navidromePlaylist.get(navidromeIndex);
        String url = com.themoon.y1.subsonic.SubsonicClient.getInstance().getStreamUrl(song.id);
        MainActivity main = MainActivity.instance;
        if (main != null) playNavidromeSong(main, song, url);
    }

    public void releasePlayer() {
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
        if (legacyPlayer != null) { legacyPlayer.release(); legacyPlayer = null; }
        if (currentFileInputStream != null) { try { currentFileInputStream.close(); } catch (Exception e) {} }
    }
}