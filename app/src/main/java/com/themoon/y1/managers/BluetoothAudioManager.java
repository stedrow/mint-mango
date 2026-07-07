package com.themoon.y1.managers;

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
                onEarsReinserted();
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

    public void setA2dp(BluetoothProfile proxy) {
        globalA2dp = proxy;
    }

    public void clearA2dp() {
        globalA2dp = null;
    }

    public boolean hasA2dp() {
        return globalA2dp != null;
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

    public int getConnectionState(BluetoothDevice device) {
        if (globalA2dp == null || device == null) return BluetoothProfile.STATE_DISCONNECTED;
        try {
            return (int) globalA2dp.getClass().getMethod("getConnectionState", BluetoothDevice.class)
                    .invoke(globalA2dp, device);
        } catch (Exception e) {
            Log.d(TAG, "getConnectionState failed", e);
            return BluetoothProfile.STATE_DISCONNECTED;
        }
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
    public void connectBluetoothAudio(final Context context, final BluetoothDevice targetDevice) {
        if (targetDevice == null)
            return;
        targetDeviceForAudio = targetDevice; // 1. Permanently lock in the target!
        isBtConnectingState = true; // block the bond/scan debounce until STATE_CONNECTED/DISCONNECTED clears it

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

        // 4. If the engine is alive, connect immediately; if not, revive it in the background then connect!
        if (globalA2dp != null) {
            executeA2dpConnect(targetDevice);
        } else {
            if (adapter != null) {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) {
                            globalA2dp = proxy;
                            executeA2dpConnect(targetDevice);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP)
                            globalA2dp = null;
                    }
                }, BluetoothProfile.A2DP);
            }
        }
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
}
