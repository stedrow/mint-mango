package com.themoon.y1.managers;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import java.lang.reflect.Method;

public class FmRadioManager {
    private static FmRadioManager instance;
    private Context context;
    private AudioManager audioManager;

    public boolean isDeviceOpen = false;
    public boolean isPowerUp = false;
    public float currentFreq = 87.5f; // default frequency
    public boolean isSpeakerOn = false;

    public String lastError = "";

    private Class<?> fmNativeClass;
    private MediaPlayer fmPlayer; // 🚀 [core] MediaPlayer that routes the sound out to the speaker

    private FmRadioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);

        try {
            fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative");
        } catch (Throwable t1) {
            String[] jarPaths = {
                    "/system/framework/mediatek-framework.jar",
                    "/system/framework/custom_ext.jar",
                    "/system/framework/com.mediatek.hardware.jar"
            };
            for (String path : jarPaths) {
                if (new java.io.File(path).exists()) {
                    try {
                        dalvik.system.PathClassLoader cl = new dalvik.system.PathClassLoader(path, ClassLoader.getSystemClassLoader());
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl);
                        break;
                    } catch (Throwable t2) {}
                }
            }
            if (fmNativeClass == null) {
                String[] packages = {"com.mediatek.FMRadio", "com.innioasis.fm", "com.innioasis.y1"};
                for (String pkg : packages) {
                    try {
                        android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
                        dalvik.system.PathClassLoader cl = new dalvik.system.PathClassLoader(info.sourceDir, ClassLoader.getSystemClassLoader());
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl);
                        break;
                    } catch (Throwable t3) {}
                }
            }
        }

        if (fmNativeClass == null) {
            lastError = "FMRadioNative Driver completely missing.";
        } else {
            try { System.loadLibrary("fmjni"); } catch (Throwable t) {
                try { System.load("/system/lib/libfmjni.so"); } catch (Throwable ignore) {}
            }
        }
    }

    public static FmRadioManager getInstance(Context context) {
        if (instance == null) instance = new FmRadioManager(context);
        return instance;
    }

    private Method getNativeMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> clazz = fmNativeClass;
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) { clazz = clazz.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    // 🚀 [new core technique] Function that connects the chipset's audio to the Android speaker/earphones!
    private void startFmAudio() {
        try {
            if (fmPlayer != null) {
                fmPlayer.release();
            }
            fmPlayer = new MediaPlayer();
            // 💡 MediaTek-only hidden FM radio audio stream address
            fmPlayer.setDataSource("MEDIATEK://MEDIAPLAYER_PLAYERTYPE_FM");

            // 💡 Find the hidden STREAM_FM (usually index 10) channel and route the volume through it.
            int streamFm = 10;
            try { streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null); } catch (Exception e) {}

            fmPlayer.setAudioStreamType(streamFm);
            fmPlayer.prepare();
            fmPlayer.start();

            // 🚀 [bug fix 2] Prevents the system from routing sound to the speaker on its own, and forces the output to match the current UI setting (isSpeakerOn)!
            setSpeaker(isSpeakerOn);

        } catch (Throwable t) {
            lastError = "Audio Routing Failed: " + t.getMessage();
        }
    }

    // 🚀 [new technique] Turn off radio sound
    private void stopFmAudio() {
        if (fmPlayer != null) {
            try {
                if (fmPlayer.isPlaying()) fmPlayer.stop();
                fmPlayer.release();
            } catch (Throwable t) {}
            fmPlayer = null;
        }
    }

    public interface PowerUpCallback {
        void onResult(boolean success);
    }

    // powerUp() does Thread.sleep(300) plus several "su -c" process spawns and reflection calls,
    // which is 350ms+ of blocking work — always run it off the main thread to avoid freezing the UI
    // when the radio is turned on (this was previously called directly from onKeyDown/onClick).
    public void powerUpAsync(final float freq, final PowerUpCallback callback) {
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            final boolean result = powerUp(freq);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(result));
            }
        }).start();
    }

    // 1. Power on the hardware
    public boolean powerUp(float freq) {
        if (fmNativeClass == null) {
            if (lastError.isEmpty()) lastError = "Driver class is null.";
            return false;
        }
        try {
            // 🚀 1. Reliably kill any stock radio apps hiding in the background to release their hold on the hardware.
            Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.mediatek.FMRadio"});
            Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.innioasis.fm"});
            Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.fmradio"});

            // 🚀 2. [most critical!] Force-open (chmod 666) permissions on the FM hardware chipset (/dev/fm) that the system holds tightly, so any app can use it!
            Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 /dev/fm"});

            Thread.sleep(300); // 💡 Give it 0.3s for the permission change to apply and the chipset to settle

            // 🚀 3. If a previous connection is stuck in a bad state, forcibly close it once and reset.
            try {
                Method closeDev = getNativeMethod("closedev");
                closeDev.invoke(null);
            } catch (Throwable ignore) {}

            isDeviceOpen = false;

            // 🚀 4. Open the chipset now that all obstructions are cleared!
            if (!isDeviceOpen) {
                Method openDev = getNativeMethod("opendev");
                isDeviceOpen = (Boolean) openDev.invoke(null);
            }

            if (!isDeviceOpen) {
                lastError = "Failed to open /dev/fm (Hardware is busy or blocked)";
                return false;
            }

            Method powerUp = getNativeMethod("powerup", float.class);
            isPowerUp = (Boolean) powerUp.invoke(null, freq);

            if (isPowerUp) {
                currentFreq = freq;
                setMute(false);
                startFmAudio(); // 🚀 Connects the audio path once powered on!
            } else {
                try {
                    Method switchAntenna = getNativeMethod("switchAntenna", int.class);
                    switchAntenna.invoke(null, 1);

                    isPowerUp = (Boolean) powerUp.invoke(null, freq);
                    if (isPowerUp) {
                        currentFreq = freq;
                        setMute(false);
                        startFmAudio(); // 🚀 Also connects the audio path when the antenna bypass succeeds!
                    } else {
                        lastError = "Power up rejected by Hardware.";
                    }
                } catch (Throwable ex) {
                    lastError = "Earphones required and Antenna bypass failed.";
                }
            }
            return isPowerUp;
        } catch (Throwable t) {
            lastError = "Exception: " + t.getClass().getSimpleName() + " - " + t.getMessage();
        }
        return false;
    }
    // 2. Power off the hardware
    public void powerDown() {
        if (fmNativeClass == null || !isPowerUp) return;
        try {
            stopFmAudio(); // 🚀 Also disconnects the audio path when powering off!
            setMute(true);
            Method powerDown = getNativeMethod("powerdown", int.class);
            powerDown.invoke(null, 0);

            Method closeDev = getNativeMethod("closedev");
            closeDev.invoke(null);

            isPowerUp = false;
            isDeviceOpen = false;
        } catch (Throwable e) { }
    }

    // 3. Manual frequency tuning (Manual Tuning)
    public boolean tune(float freq) {
        if (fmNativeClass == null || !isPowerUp) return false;
        try {
            Method tuneMethod = getNativeMethod("tune", float.class);
            boolean success = (Boolean) tuneMethod.invoke(null, freq);
            if (success) currentFreq = freq;
            return success;
        } catch (Throwable e) { return false; }
    }

    // 🚀 4. Auto scan engine (Auto Scan)
    public float[] autoScan() {
        if (fmNativeClass == null || !isPowerUp) {
            lastError = "Turn on the radio first.";
            return null;
        }
        try {
            Method autoScanMethod = getNativeMethod("autoscan");
            short[] result = (short[]) autoScanMethod.invoke(null);

            if (result != null && result.length > 0) {
                float[] freqs = new float[result.length];
                for (int i = 0; i < result.length; i++) {
                    freqs[i] = result[i] / 10.0f; // MediaTek returns 875 for 87.5, so convert to a decimal
                }
                return freqs;
            }
        } catch (Throwable t) {
            lastError = "AutoScan failed: " + t.getMessage();
        }
        return null;
    }

    // 5. Mute control
    public void setMute(boolean mute) {
        if (fmNativeClass == null) return;
        try {
            Method setMuteMethod = getNativeMethod("setmute", boolean.class);
            setMuteMethod.invoke(null, mute);
        } catch (Throwable e) {}
    }

    // 6. Force-switch speaker output
    public void setSpeaker(boolean useSpeaker) {
        try {
            Method setForceUse = Class.forName("android.media.AudioSystem").getDeclaredMethod("setForceUse", int.class, int.class);
            setForceUse.setAccessible(true);
            setForceUse.invoke(null, 5, useSpeaker ? 1 : 0);
            isSpeakerOn = useSpeaker;
        } catch (Throwable e) {}
    }
}