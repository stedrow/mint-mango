package com.themoon.y1;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class VideoPlayerActivity extends Activity {
    private VideoView videoView;
    private LinearLayout layoutControls, layoutVolumeOverlay;
    private TextView tvCurrent, tvTotal, tvSubtitle;
    private ProgressBar progressVideo, volumeProgress;
    private ImageView ivPauseIcon;
    // 꾹 누르기(Seek) 연사 속도 조절용 변수
    private boolean isSeekPerformed = false;
    private long lastSeekTime = 0;
    private Handler uiHandler = new Handler();
    private boolean isUIHiding = false;

    // 볼륨 오버레이 자동 숨김용 타이머
    private Handler volumeHandler = new Handler();
    private Runnable hideVolumeTask = () -> layoutVolumeOverlay.setVisibility(View.GONE);

    // 자막(SRT) 파서 금고
    private TreeMap<Integer, String> subtitlesMap = new TreeMap<>();
    private AudioManager audioManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_video_player);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        videoView = findViewById(R.id.video_view);
        layoutControls = findViewById(R.id.layout_controls);
        tvCurrent = findViewById(R.id.tv_time_current);
        tvTotal = findViewById(R.id.tv_time_total);
        progressVideo = findViewById(R.id.progress_video);
        ivPauseIcon = findViewById(R.id.iv_pause_icon);
        tvSubtitle = findViewById(R.id.tv_subtitle);

        layoutVolumeOverlay = findViewById(R.id.layout_volume_overlay);
        volumeProgress = findViewById(R.id.volume_progress);

        volumeProgress.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

        // 🚀 볼륨바 색상을 테마의 포커스 색상으로 맞춰줌!
        try {
            int themeFocusColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            volumeProgress.getProgressDrawable().setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception e) {}

        String videoPath = getIntent().getStringExtra("VIDEO_PATH");

        if (videoPath == null || !new File(videoPath).exists()) {
            Toast.makeText(this, "🚨 Invalid Video File", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadSubtitles(videoPath);

        videoView.setVideoURI(Uri.parse(videoPath));
        videoView.setOnPreparedListener(mp -> {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    float speed = com.themoon.y1.managers.AudioPlayerManager.getInstance().getCurrentSpeed();
                    if (speed != 1.0f) {
                        mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
                    }
                } catch (Exception e) {}
            }
            // Honour "Disable Built-in Speaker" the same way the music players do -- by muting our
            // own playback rather than the system's routing. Android's silent/ringer mode does not
            // affect STREAM_MUSIC (its affected-streams mask has no MUSIC bit), and the three
            // system-level routes were already tried and rejected on this hardware; see
            // MainActivity.applySpeakerSetting. Without this, video was the one player that ignored
            // the setting and blasted out of the speaker.
            mediaPlayer = mp;
            applySpeakerMute();

            videoView.start();
            showControls(false); // hide the UI a few seconds after playback starts
            uiHandler.post(updateUITask); // start the progress-bar loop
        });

        videoView.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING || what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                Toast.makeText(VideoPlayerActivity.this, "⚠️ 비디오가 무거워 재생이 지연될 수 있습니다.", Toast.LENGTH_SHORT).show();
            }
            return false;
        });

        videoView.setOnCompletionListener(mp -> finish());
    }

    private Runnable hideUITask = () -> {
        layoutControls.setVisibility(View.GONE);
        isUIHiding = true;
    };

    private void showControls(boolean keepVisible) {
        layoutControls.setVisibility(View.VISIBLE);
        isUIHiding = false;
        uiHandler.removeCallbacks(hideUITask);
        if (!keepVisible) {
            uiHandler.postDelayed(hideUITask, 3000);
        }
    }

    // 🚀 [수정 완료] 메인 앱의 볼륨 엔진 100% 이식 (라디오 동기화 포함)
    private void adjustVolume(boolean up) {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        if (up && currentVol < maxVol)
            currentVol++;
        else if (!up && currentVol > 0)
            currentVol--;

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol, 0);

        try {
            com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
            if (fm != null && fm.isPowerUp) {
                int streamFm = 10;
                try {
                    streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null);
                } catch (Exception e) {}
                int fmMax = audioManager.getStreamMaxVolume(streamFm);
                int fmVol = (int) (((float) currentVol / maxVol) * fmMax);
                audioManager.setStreamVolume(streamFm, fmVol, 0);
            }
        } catch (Exception e) {}

        showDynamicVolumeOverlay();
    }

    // 🚀 [수정 완료] 오리지널 애니메이션을 위한 오버레이 호출 함수
    private void showDynamicVolumeOverlay() {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        layoutVolumeOverlay.setVisibility(View.VISIBLE);
        volumeProgress.setProgress(currentVol);
        volumeHandler.removeCallbacks(hideVolumeTask);
        volumeHandler.postDelayed(hideVolumeTask, 2000); // 2초 뒤에 사라짐
    }

    private Runnable updateUITask = new Runnable() {
        @Override
        public void run() {
            // Not while scrubbing: the bar and clock are showing the pending target, and playback's
            // real position would overwrite it every 300ms.
            if (videoView != null && videoView.isPlaying() && !isScrubbing) {
                int current = videoView.getCurrentPosition();
                int total = videoView.getDuration();

                tvCurrent.setText(formatTime(current));
                tvTotal.setText(formatTime(total));
                if (total > 0) progressVideo.setProgress((int) (((float) current / total) * 100));

                // 자막 업데이트
                if (!subtitlesMap.isEmpty()) {
                    Map.Entry<Integer, String> entry = subtitlesMap.floorEntry(current);
                    if (entry != null && !entry.getValue().isEmpty()) {
                        tvSubtitle.setText(entry.getValue());
                        tvSubtitle.setVisibility(View.VISIBLE);
                    } else {
                        tvSubtitle.setVisibility(View.GONE);
                    }
                }
            }
            uiHandler.postDelayed(this, 300);
        }
    };

    /**
     * Same control language as Now Playing, so the wheel means one thing across the launcher:
     *
     *   wheel (19/20)      ghost-scrub, 5s a step -- moves a target without seeking yet
     *   centre / media     commit a pending scrub, otherwise play/pause
     *   back               drop a pending scrub, otherwise leave
     *   volume keys        volume, and nothing else does volume
     *
     * Upstream had the wheel on volume and no scrubbing at all, which is backwards from the music
     * player: the wheel is how you move through a track everywhere else in the app.
     */
    private static final int SCRUB_STEP_MS =
            com.themoon.y1.managers.NowPlayingUiManager.SCRUB_STEP_MS;
    private MediaPlayer mediaPlayer;
    private boolean isScrubbing = false;
    private int scrubTargetMs = 0;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // A pending scrub is the thing being backed out of, not the video.
            if (isScrubbing) {
                cancelScrub();
                return true;
            }
            finish();
            return true;
        }

        // Volume is the volume buttons' job alone now.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjustVolume(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            adjustVolume(true);
            return true;
        }

        // Wheel up/back, and the side skip buttons, step the scrub target.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            scrubStep(-SCRUB_STEP_MS);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            scrubStep(SCRUB_STEP_MS);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == 23
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {

            if (isScrubbing) {
                commitScrub();
                return true;
            }

            if (videoView.isPlaying()) {
                videoView.pause();
                ivPauseIcon.setVisibility(View.VISIBLE);
                showControls(true); // hold the bar up while paused
            } else {
                videoView.start();
                ivPauseIcon.setVisibility(View.GONE);
                showControls(false); // fade it out again shortly after resuming
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /** Mute when the setting is on and there is nothing but the built-in speaker to play out of. */
    private void applySpeakerMute() {
        if (mediaPlayer == null) return;
        try {
            boolean externalAudio =
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().isAnyDeviceConnected();
            boolean mute = MainActivity.instance != null
                    && MainActivity.instance.isSpeakerDisabled && !externalAudio;
            mediaPlayer.setVolume(mute ? 0f : 1f, mute ? 0f : 1f);
        } catch (Exception e) {
            android.util.Log.d("VideoPlayerActivity", "could not apply the speaker mute", e);
        }
    }

    /** Move the pending scrub target and show it, without seeking the decoder yet. */
    private void scrubStep(int deltaMs) {
        int duration = videoView.getDuration();
        if (!isScrubbing) {
            isScrubbing = true;
            scrubTargetMs = videoView.getCurrentPosition();
        }
        scrubTargetMs += deltaMs;
        if (scrubTargetMs < 0) scrubTargetMs = 0;
        if (duration > 0 && scrubTargetMs > duration) scrubTargetMs = duration;

        // Seeking on every wheel step would thrash the decoder for a keyframe each time; the bar
        // and the clock show where you are heading, and one seek happens on commit.
        if (duration > 0) progressVideo.setProgress((int) ((scrubTargetMs / (float) duration) * 100));
        tvCurrent.setText(formatTime(scrubTargetMs));
        showControls(true);
    }

    private void commitScrub() {
        if (!isScrubbing) return;
        isScrubbing = false;
        videoView.seekTo(scrubTargetMs);
        if (!videoView.isPlaying()) {
            videoView.start();
            ivPauseIcon.setVisibility(View.GONE);
        }
        showControls(false);
    }

    private void cancelScrub() {
        if (!isScrubbing) return;
        isScrubbing = false;
        // Snap the bar back to where playback actually is.
        int duration = videoView.getDuration();
        if (duration > 0) {
            progressVideo.setProgress((int) ((videoView.getCurrentPosition() / (float) duration) * 100));
        }
        tvCurrent.setText(formatTime(videoView.getCurrentPosition()));
        showControls(false);
    }

    // 🚀 자막(SRT) 파서 엔진
    private void loadSubtitles(String videoPath) {
        try {
            String basePath = videoPath.substring(0, videoPath.lastIndexOf('.'));
            File srtFile = new File(basePath + ".srt");
            if (!srtFile.exists()) return;

            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(srtFile), "UTF-8"));
            String line;
            int startTime = 0;
            StringBuilder text = new StringBuilder();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.matches("\\d+")) { // 번호표
                    if (text.length() > 0 && startTime > 0) {
                        subtitlesMap.put(startTime, text.toString().trim());
                    }
                    text.setLength(0);
                } else if (line.contains("-->")) { // 타임스탬프
                    String[] parts = line.split("-->");
                    startTime = parseSrtTime(parts[0].trim());
                    int endTime = parseSrtTime(parts[1].trim());
                    subtitlesMap.put(endTime, ""); // 끝나는 시간에 자막 지우기
                } else if (!line.isEmpty()) { // 자막 텍스트
                    text.append(line).append("\n");
                }
            }
            if (text.length() > 0 && startTime > 0) {
                subtitlesMap.put(startTime, text.toString().trim());
            }
            br.close();
        } catch (Exception e) {}
    }

    private int parseSrtTime(String timeStr) {
        try {
            String[] parts = timeStr.replace(',', '.').split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            String[] sParts = parts[2].split("\\.");
            int s = Integer.parseInt(sParts[0]);
            int ms = sParts.length > 1 ? Integer.parseInt(sParts[1]) : 0;
            return (h * 3600 + m * 60 + s) * 1000 + ms;
        } catch (Exception e) { return 0; }
    }

    private String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        int min = totalSeconds / 60;
        int sec = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    // Upstream calls forceFiveBandAudioMode() here, which turns off their software equalizer and
    // rebuilds the hardware one before playing. We have no software EQ -- that arrived with their
    // newer audio processors, which this fork doesn't carry -- so there is nothing to switch away
    // from, and MediaPlayer takes the hardware path regardless.

    @Override
    protected void onResume() {
        super.onResume();
        applySpeakerMute(); // Bluetooth may have connected or dropped while we were away
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(updateUITask);
        uiHandler.removeCallbacks(hideUITask);
        volumeHandler.removeCallbacks(hideVolumeTask);
        if (videoView != null) videoView.stopPlayback();
    }
}