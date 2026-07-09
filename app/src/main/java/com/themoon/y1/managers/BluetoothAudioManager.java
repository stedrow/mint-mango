package com.themoon.y1.managers;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.themoon.y1.AapService;
import com.themoon.y1.MainActivity;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The reflection-based A2DP connection engine: proxy lifecycle, connect/disconnect/pairing, and
 * the exponential-backoff reconnect watchdog that re-latches onto AirPods after a signal drop.
 * Extracted from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * The actual intent-broadcast dispatch (onReceive()'s giant action-name switchboard) and the
 * Bluetooth-screen UI builders stay in MainActivity -- they're not part of "connection
 * management" and moving them would mean touching the same shared receiver every other broadcast
 * type (Wi-Fi scan, media scanner, battery, headset) also flows through. Those call sites now
 * reach into this manager's public methods instead of touching the (now-private-to-this-class)
 * globalA2dp/targetDeviceForAudio/isBtConnectingState state directly.
 *
 * Uses MainActivity.instance for the handful of UI callbacks (Toast, status icon, translator)
 * the engine needs mid-flow, the same load-bearing pattern WheelLockManager uses -- this class
 * doesn't need enough of MainActivity's surface to justify the heavier "MainActivity a" parameter
 * pattern FmRadioUiManager/KeyEventRouter/SettingsUiManager use.
 *
 * Correctness-critical: verify real AirPods pair/connect/disconnect/reconnect on-device before
 * trusting any change here, per the roadmap's non-negotiable for this subsystem.
 */
public class BluetoothAudioManager {
    private static final String TAG = "BluetoothAudioManager";
    private static BluetoothAudioManager instance;

    private BluetoothProfile globalA2dp;
    private BluetoothDevice targetDeviceForAudio = null; // Target device to keep latching onto like a zombie
    private boolean isBtConnectingState = false;

    // Reconnect watchdog. A hand over the antenna or the Y1 going in a pocket attenuates 2.4GHz
    // enough to drop the AirPods link even at close range; the old logic fired a fixed budget of
    // immediate retries and then gave up *forever*. Those retries burn instantly against a device
    // that's still unreachable (hand still on it), and the budget only reset on a successful
    // reconnect -- which can't happen while the obstruction is present -- so once the user cleared
    // their hand there was no event left to trigger a retry and they had to tap Connect manually.
    //
    // Instead we keep a single backoff timer that never permanently gives up: it re-attempts with
    // exponential backoff (RECONNECT_BACKOFF_MIN_MS, doubling up to RECONNECT_BACKOFF_MAX_MS) so
    // the moment the obstruction clears it reconnects on its own. It's cancelled on a real
    // STATE_CONNECTED, on Bluetooth off, and on unpair; and it backs off (no connect() spam) while
    // isLikelyStowed() reports both buds deliberately in the case.
    private final Handler reconnectHandler = new Handler();
    private Runnable reconnectRunnable = null;
    private long reconnectBackoffMs = 0;
    private static final long RECONNECT_BACKOFF_MIN_MS = 2000;
    private static final long RECONNECT_BACKOFF_MAX_MS = 15000;

    // AirPods have their own in-ear-detection hardware that mutes their local output when a
    // sensor read says "removed" -- independent of whatever the Bluetooth link is actually
    // carrying. A brief false "out" reading (confirmed via web search as a known AirPods/non-Apple
    // pairing quirk) can leave them muted even after AapService's own auto-resume calls
    // AudioPlayerManager.resumeForAirpods(), since that only toggles play/pause and doesn't force
    // a fresh audio session. The "ears just came back in" transition (see onAapStateChanged below)
    // triggers AudioPlayerManager.restartAudioPipelineQuietly(), which rebuilds the local
    // AudioTrack/A2DP session (stop -> reprepare -> resume) without touching the Bluetooth link;
    // that falls back to a full BT disconnect+reconnect (nudgeAudioReconnectForAirpods(), the
    // confirmed-but-heavier fix) only when there's no simple local file to reload (Navidrome).
    private static final long AUDIO_NUDGE_COOLDOWN_MS = 20000;
    private long lastAudioNudgeAtMs = 0;
    private boolean aapLastBothInEar = true;

    private BluetoothAudioManager() {}

    public static synchronized BluetoothAudioManager getInstance() {
        if (instance == null) {
            instance = new BluetoothAudioManager();
        }
        return instance;
    }

    public final AapService.Listener aapEarListener = new AapService.Listener() {
        @Override
        public void onAapStateChanged(AapService.AapState state) {
            boolean nowBothInEar = state.earLeft == AapService.EAR_IN_EAR && state.earRight == AapService.EAR_IN_EAR;
            if (!aapLastBothInEar && nowBothInEar) {
                // AapService.publishState() calls listeners straight from its own read-loop
                // worker thread, not main -- same signal AapService's own handleEarDetectionForAutoPause
                // hops to mainHandler for. onEarsReinserted() reaches ExoPlayer via
                // restartAudioPipelineQuietly(), which must only be touched from the thread it
                // was created on (main); without this hop that throws "Player is accessed on
                // the wrong thread" and kills the in-flight AAP session.
                reconnectHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onEarsReinserted();
                    }
                });
            }
            aapLastBothInEar = nowBothInEar;
        }

        @Override
        public void onAapConnectionChanged(boolean connected) {
        }
    };

    private void onEarsReinserted() {
        BluetoothDevice target = targetDeviceForAudio;
        if (target == null || !isA2dpConnectedTo(target)) return;

        long now = System.currentTimeMillis();
        if (now - lastAudioNudgeAtMs < AUDIO_NUDGE_COOLDOWN_MS) return; // still cooling down

        lastAudioNudgeAtMs = now;
        AudioPlayerManager.getInstance().restartAudioPipelineQuietly();
    }

    // --- proxy lifecycle -----------------------------------------------------------------

    // getProfileProxy() used to be called from three separate places (MainActivity.onCreate,
    // the ACTION_STATE_CHANGED-ON handler, and this class's old connect fallback), each
    // registering its own ServiceListener and never calling closeProfileProxy(). Every one of
    // those listeners stayed alive for the rest of the process and kept reacting to every future
    // system-wide A2DP service bind/unbind -- so a *stale* listener's onServiceDisconnected could
    // null out globalA2dp out from under a newer, live proxy another listener had just installed.
    // That's a real, compounding source of "sometimes it just won't connect" instability (gets
    // worse the more times Bluetooth is toggled or a connect is attempted in a session).
    //
    // Now there's exactly one ServiceListener for the process lifetime, and exactly one
    // getProfileProxy() call ever in flight at a time (ensureA2dp queues callbacks instead of
    // re-requesting).
    private final java.util.List<Runnable> pendingProxyCallbacks = new java.util.ArrayList<>();
    private boolean proxyRequestPending = false;

    private final BluetoothProfile.ServiceListener a2dpServiceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.A2DP) return;
            globalA2dp = proxy;
            proxyRequestPending = false;
            List<Runnable> callbacks = new java.util.ArrayList<>(pendingProxyCallbacks);
            pendingProxyCallbacks.clear();
            for (Runnable callback : callbacks) callback.run();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) globalA2dp = null;
        }
    };

    /** Runs {@code onReady} once the A2DP proxy is available -- immediately if it already is,
     * otherwise as soon as the single outstanding getProfileProxy() request completes. Safe to
     * call from multiple places concurrently; it never issues more than one request in flight. */
    @SuppressLint("MissingPermission") // system-signed app; Bluetooth permissions are granted at install
    public void ensureA2dp(Context context, Runnable onReady) {
        if (globalA2dp != null) {
            if (onReady != null) onReady.run();
            return;
        }
        if (onReady != null) pendingProxyCallbacks.add(onReady);
        if (proxyRequestPending) return; // request already in flight -- onServiceConnected will drain the queue
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        proxyRequestPending = true;
        adapter.getProfileProxy(context.getApplicationContext(), a2dpServiceListener, BluetoothProfile.A2DP);
    }

    public boolean hasA2dp() {
        return globalA2dp != null;
    }

    /** Bluetooth radio is going off -- release the proxy properly instead of just dropping the
     * reference, so the binder connection to the system A2DP service doesn't leak. */
    public void releaseA2dp() {
        if (globalA2dp == null) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, globalA2dp);
        } catch (Exception e) {
            Log.d(TAG, "releaseA2dp failed", e);
        }
        globalA2dp = null;
    }

    public boolean isAnyDeviceConnected() {
        try {
            return globalA2dp != null && !globalA2dp.getConnectedDevices().isEmpty();
        } catch (Exception e) {
            Log.d(TAG, "isAnyDeviceConnected failed", e);
            return false;
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (globalA2dp == null) return null;
        return globalA2dp.getConnectedDevices();
    }

    // getConnectionState(BluetoothDevice) is a plain public method on the BluetoothProfile
    // interface (unlike connect()/disconnect(), which really are hidden API and need the
    // reflection elsewhere in this file) -- no reflection needed, and calling it directly means
    // this can't silently return a wrong STATE_DISCONNECTED from a reflection failure, which
    // would otherwise defeat the connect() reentrancy guard below right when it matters most.
    public int getConnectionState(BluetoothDevice device) {
        if (globalA2dp == null || device == null) return BluetoothProfile.STATE_DISCONNECTED;
        return globalA2dp.getConnectionState(device);
    }

    public boolean isA2dpConnectedTo(BluetoothDevice device) {
        return getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
    }

    /** AapService only (re)starts from the A2DP CONNECTION_STATE_CHANGED broadcast, which fires
     * on a state transition. If Android kills this process while AirPods are already connected,
     * the process comes back with no AAP session and no new broadcast to trigger one (the state
     * never changed) -- ear-detection stays dead until the user manually toggles Bluetooth.
     * Re-checking on resume (and right after the A2DP proxy first binds) self-heals that without
     * needing a manual reconnect. */
    public void resyncAapWithConnectedDevice(Context context) {
        if (globalA2dp == null) return;
        try {
            List<BluetoothDevice> connected = globalA2dp.getConnectedDevices();
            if (!connected.isEmpty()) {
                BluetoothDevice device = connected.get(0);
                targetDeviceForAudio = device;
                AapService.deviceConnected(context, device);
            }
        } catch (Exception e) {
            Log.d(TAG, "resyncAapWithConnectedDevice failed", e);
        }
    }

    // --- target device / connecting-state bookkeeping -------------------------------------

    public BluetoothDevice getTargetDeviceForAudio() {
        return targetDeviceForAudio;
    }

    public void setTargetDeviceForAudio(BluetoothDevice device) {
        targetDeviceForAudio = device;
    }

    public boolean isBtConnectingState() {
        return isBtConnectingState;
    }

    public void setBtConnectingState(boolean connecting) {
        isBtConnectingState = connecting;
        if (!connecting) cancelConnectingStateTimeout();
    }

    // Belt-and-suspenders for isBtConnectingState: it's meant to be cleared by the A2DP
    // CONNECTION_STATE_CHANGED broadcast, but a pairing attempt that fails before the stack ever
    // emits that broadcast (createBond() rejected, device goes out of range mid-pair, etc.) would
    // otherwise leave it stuck true forever, permanently blocking the bond/scan debounce.
    private static final long CONNECTING_STATE_TIMEOUT_MS = 15000;
    private Runnable connectingStateTimeoutRunnable = null;

    private void armConnectingStateTimeout() {
        cancelConnectingStateTimeout();
        connectingStateTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                connectingStateTimeoutRunnable = null;
                isBtConnectingState = false;
            }
        };
        reconnectHandler.postDelayed(connectingStateTimeoutRunnable, CONNECTING_STATE_TIMEOUT_MS);
    }

    private void cancelConnectingStateTimeout() {
        if (connectingStateTimeoutRunnable != null) {
            reconnectHandler.removeCallbacks(connectingStateTimeoutRunnable);
            connectingStateTimeoutRunnable = null;
        }
    }

    public void resetBackoffToMin() {
        reconnectBackoffMs = RECONNECT_BACKOFF_MIN_MS;
    }

    /** Deleting a paired device is a deliberate "stop connecting to this" -- drop it as the
     * watchdog target and cancel any pending reconnect so we don't re-pair behind the user. */
    public void forgetTargetIfMatches(BluetoothDevice device) {
        if (targetDeviceForAudio != null && device != null
                && targetDeviceForAudio.getAddress().equals(device.getAddress())) {
            cancelAudioReconnect();
            targetDeviceForAudio = null;
        }
    }

    // --- connect / pair --------------------------------------------------------------------

    public void nudgeAudioReconnectForAirpods() {
        BluetoothDevice target = targetDeviceForAudio;
        if (target != null) nudgeAudioReconnect(target);
    }

    private void nudgeAudioReconnect(final BluetoothDevice target) {
        if (target == null || globalA2dp == null) return;
        try {
            Method disconnectMethod = globalA2dp.getClass().getDeclaredMethod("disconnect", BluetoothDevice.class);
            disconnectMethod.setAccessible(true);
            disconnectMethod.invoke(globalA2dp, target);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // AapService's ear-detection callback runs on its own background worker thread, not
        // main, so this must use a Handler already bound to the main Looper -- a bare `new
        // Handler()` here throws ("Can't create handler inside thread that has not called
        // Looper.prepare()") and kills the AAP session every time the nudge fires.
        reconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                connectBluetoothAudio(MainActivity.instance, target);
            }
        }, 1000);
    }

    // 🚀 [Fully ported from stock launcher] Centralized Bluetooth connection engine
    @SuppressLint("MissingPermission") // system-signed app; Bluetooth permissions are granted at install
    public void connectBluetoothAudio(final Context context, final BluetoothDevice targetDevice) {
        if (targetDevice == null)
            return;
        targetDeviceForAudio = targetDevice; // 1. Permanently lock in the target!

        // Reentrancy guard: this method has four independent callers (user tap, the reconnect
        // watchdog, ACTION_BOND_STATE_CHANGED's post-pair kick, and the AirPods nudge) that can
        // all fire close together. Calling connect() a second time while the stack already has
        // this device CONNECTING or CONNECTED makes it abort and restart the link -- that's the
        // connect/disconnect storm seen pairing to AirPods Pro, whose multi-profile handshake
        // (A2DP/AVRCP/HFP) already keeps it in STATE_CONNECTING for a while. Bail out instead of
        // re-issuing connect() onto a link that's already forming or up.
        int existingState = getConnectionState(targetDevice);
        if (existingState == BluetoothProfile.STATE_CONNECTED || existingState == BluetoothProfile.STATE_CONNECTING) {
            return;
        }

        isBtConnectingState = true; // block the bond/scan debounce until STATE_CONNECTED/DISCONNECTED clears it
        armConnectingStateTimeout(); // safety net in case pairing fails silently with no A2DP broadcast to clear it

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isDiscovering()) {
            adapter.cancelDiscovery(); // 2. Always stop scanning first to avoid overload
        }

        // 3. If not paired yet, pair first! (once done, the receiver calls this function again)
        if (targetDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
            Toast.makeText(context, MainActivity.instance.t("Pairing with ") + targetDevice.getName() + "...", Toast.LENGTH_SHORT).show();
            try {
                targetDevice.getClass().getMethod("createBond").invoke(targetDevice);
            } catch (Exception e) {
                Log.d(TAG, "connectBluetoothAudio failed", e);
            }
            return;
        }

        Toast.makeText(context, MainActivity.instance.t("Connecting Audio..."), Toast.LENGTH_SHORT).show();

        // 4. If the engine is alive, connect immediately; if not, revive it (through the single
        // shared proxy request -- see ensureA2dp) then connect.
        ensureA2dp(context, new Runnable() {
            @Override
            public void run() {
                executeA2dpConnect(targetDevice);
            }
        });
    }

    // 🚀 [Core detail] The one actually handling the audio connection
    private void executeA2dpConnect(BluetoothDevice targetDevice) {
        if (globalA2dp == null || targetDevice == null)
            return;
        Context context = MainActivity.instance;
        try {
            // 💡 [Secret of the stock code] Before connecting, ruthlessly disconnect any other audio device already attached!
            List<BluetoothDevice> connectedDevices = null;
            try {
                connectedDevices = globalA2dp.getConnectedDevices();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (connectedDevices != null) {
                for (BluetoothDevice connected : connectedDevices) {
                    if (!connected.getAddress().equals(targetDevice.getAddress())) {
                        try {
                            Method disconnectMethod = globalA2dp.getClass().getDeclaredMethod("disconnect",
                                    BluetoothDevice.class);
                            disconnectMethod.setAccessible(true);
                            disconnectMethod.invoke(globalA2dp, connected);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            // 💡 Once the obstruction is gone, finally fire the audio beam at the target device!
            Method connectMethod = null;
            try {
                connectMethod = globalA2dp.getClass().getDeclaredMethod("connect", BluetoothDevice.class);
            } catch (NoSuchMethodException e) {
                // If not found, try to iterate
                for (Method m : globalA2dp.getClass().getMethods()) {
                    if (m.getName().equals("connect") && m.getParameterTypes().length == 1) {
                        connectMethod = m;
                        break;
                    }
                }
            }

            if (connectMethod == null) {
                Toast.makeText(context, MainActivity.instance.t("Audio connection error: connect method not found"), Toast.LENGTH_LONG).show();
                return;
            }

            connectMethod.setAccessible(true);
            boolean result = (Boolean) connectMethod.invoke(globalA2dp, targetDevice);
            if (!result) {
                Toast.makeText(context, MainActivity.instance.t("Audio connection rejected by system."), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, MainActivity.instance.t("Audio connection initiated."), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            String errorStr = e.getClass().getSimpleName();
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                Throwable cause = ((java.lang.reflect.InvocationTargetException) e).getCause();
                if (cause != null) {
                    errorStr += " (Cause: " + cause.getClass().getSimpleName() + " - " + cause.getMessage() + ")";
                }
            } else {
                errorStr += " - " + e.getMessage();
            }
            Toast.makeText(context, "Audio error: " + errorStr, Toast.LENGTH_LONG).show();
        }
    }

    // --- reconnect watchdog ------------------------------------------------------------------

    /** Starts a self-perpetuating backoff loop; the loop only stops when the device is actually
     * connected again (STATE_CONNECTED cancels it, or attemptAudioReconnect sees the live
     * connection), the radio goes off, or the device is unpaired. */
    public void scheduleAudioReconnect() {
        if (targetDeviceForAudio == null) return;
        if (reconnectRunnable != null) return; // a reconnect cycle is already in flight
        if (reconnectBackoffMs < RECONNECT_BACKOFF_MIN_MS)
            reconnectBackoffMs = RECONNECT_BACKOFF_MIN_MS; // fresh dropout -> start from the short interval
        armAudioReconnect();
    }

    private void armAudioReconnect() {
        cancelAudioReconnect();
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                attemptAudioReconnect();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, reconnectBackoffMs);
    }

    public void cancelAudioReconnect() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void attemptAudioReconnect() {
        final BluetoothDevice target = targetDeviceForAudio;
        if (target == null) return;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            // Radio off -- ACTION_STATE_CHANGED restarts everything if it comes back; stop watching.
            return;
        }

        if (isA2dpConnectedTo(target)) {
            // Recovered (possibly on its own) -- stop the loop and reset for next time.
            reconnectBackoffMs = RECONNECT_BACKOFF_MIN_MS;
            return;
        }

        if (AapService.isLikelyStowed()) {
            // Both buds deliberately in the case: don't hammer connect(), just keep a slow watch.
            // Popping a bud back into an ear fires ACL_CONNECTED, which resets us to a fast retry.
            reconnectBackoffMs = RECONNECT_BACKOFF_MAX_MS;
            armAudioReconnect();
            return;
        }

        connectBluetoothAudio(MainActivity.instance, target);

        // Grow the backoff (capped) and keep watching. A real STATE_CONNECTED cancels this loop.
        reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, RECONNECT_BACKOFF_MAX_MS);
        armAudioReconnect();
    }

    // 🚀 Jelly Bean-era RemoteControlClient: feeds track metadata/playback state to car
    // head units and Bluetooth AVRCP targets over the same registered media button receiver.
    public void initRemoteControlClient(Context context) {
        MainActivity a = MainActivity.instance;
        if (a.remoteControlClient == null) {
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

            // Connect the media button receiver (MediaBtnReceiver) we built earlier.
            a.mediaButtonReceiver = new android.content.ComponentName(context.getPackageName(), MainActivity.MediaBtnReceiver.class.getName());
            audioManager.registerMediaButtonEventReceiver(a.mediaButtonReceiver);

            // Create the intent for the remote-control client
            android.content.Intent mediaButtonIntent = new android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(a.mediaButtonReceiver);
            int pendingIntentFlags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
            android.app.PendingIntent mediaPendingIntent = android.app.PendingIntent.getBroadcast(context, 0, mediaButtonIntent, pendingIntentFlags);

            // 🚀 Launching the Jelly Bean-only broadcast station!
            a.remoteControlClient = new android.media.RemoteControlClient(mediaPendingIntent);

            // Grant permission for which buttons can be pressed from the car steering wheel and Bluetooth devices
            int flags = android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS;
            a.remoteControlClient.setTransportControlFlags(flags);

            audioManager.registerRemoteControlClient(a.remoteControlClient);
        }
    }

    // 🚀 Called when the track changes!
    public void updateBluetoothMetadata(String title, String artist, String album, android.graphics.Bitmap albumArtBmp) {
        MainActivity a = MainActivity.instance;
        if (a.remoteControlClient == null) return;

        android.media.RemoteControlClient.MetadataEditor editor = a.remoteControlClient.editMetadata(true);

        // 1. Fill in text info (Jelly Bean uses MediaMetadataRetriever's constants)
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, title != null ? title : "Unknown Title");
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST, artist != null ? artist : "Unknown Artist");
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, album != null ? album : "Unknown Album");

        // 2. 🚀 [Key] Send the album art bitmap to the car display!
        if (albumArtBmp != null) {
            editor.putBitmap(android.media.RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, albumArtBmp);
        }

        // Send to the system once packaging is complete
        editor.apply();
    }

    // 🚀 Called when music starts or stops playing!
    public void updateBluetoothPlaybackState(boolean isPlaying) {
        MainActivity a = MainActivity.instance;
        if (a.remoteControlClient == null) return;

        int state = isPlaying ? android.media.RemoteControlClient.PLAYSTATE_PLAYING : android.media.RemoteControlClient.PLAYSTATE_PAUSED;

        // On Jelly Bean, the car runs its own timer even without sending the current position (currentPosition).
        a.remoteControlClient.setPlaybackState(state);
    }

    // 🚀 [New helper] Function that reads the current screen's track info and image and sends it over Bluetooth
    public void sendBluetoothMetaToCar() {
        MainActivity a = MainActivity.instance;
        String title = a.tvPlayerTitle != null ? a.tvPlayerTitle.getText().toString() : "Unknown";
        String artist = a.tvPlayerArtist != null ? a.tvPlayerArtist.getText().toString() : "Unknown";
        android.graphics.Bitmap bmp = null;

        // If album art exists, compress it slightly to fit the Bluetooth transfer size before sending.
        if (a.lastAlbumArtBytes != null && a.lastAlbumArtBytes.length > 0) {
            try {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                bmp = android.graphics.BitmapFactory.decodeByteArray(a.lastAlbumArtBytes, 0, a.lastAlbumArtBytes.length, opts);
            } catch (Exception e) {
                Log.d(TAG, "sendBluetoothMetaToCar failed", e);
            }
        }

        updateBluetoothMetadata(title, artist, "Y1 Player", bmp);
    }
}
