package com.themoon.y1.managers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;

import java.util.List;

/**
 * Builds the Bluetooth and Wi-Fi settings screens: device/network scan lists, pairing/connect
 * click handlers, and focus restoration after a rebuild. Extracted from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * The actual connection engines already live elsewhere (BluetoothAudioManager for A2DP
 * reflection calls, plain WifiManager/WifiConfiguration calls here since Wi-Fi has no
 * reflection-based engine of its own) -- this class is purely the two screens' UI-construction
 * half, the same role FmRadioUiManager/SettingsUiManager play for their subsystems. No clean
 * field boundary (containerBtItems/containerWifiItems/btnScanBt/btnScanWifi/etc. are simple view
 * bindings from MainActivity.onCreate(), same as every other screen builder), so it takes the
 * MainActivity instance as a parameter.
 */
public class ConnectivityScreenManager {
    private static final String TAG = "ConnectivityScreenManager";
    private static ConnectivityScreenManager instance;

    private ConnectivityScreenManager() {}

    public static synchronized ConnectivityScreenManager getInstance() {
        if (instance == null) {
            instance = new ConnectivityScreenManager();
        }
        return instance;
    }

    public void connectToWifi(MainActivity a) {
        WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Toast.makeText(a, a.t("Wi-Fi is unavailable."), Toast.LENGTH_SHORT).show();
            return;
        }
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + a.targetWifiSsid + "\"";
        if (a.isTargetWifiOpen)
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        else
            conf.preSharedKey = "\"" + a.typedPassword + "\"";
        int netId = wm.addNetwork(conf);
        // addNetwork() returns -1 on failure (bad config, duplicate SSID, etc.) — this used to be
        // ignored, so a bad password/config would silently show "Connecting..." with no error.
        if (netId == -1) {
            Toast.makeText(a, a.t("Failed to save this network. Please check the password and try again."), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(a, a.t("Connecting to ") + a.targetWifiSsid + "...", Toast.LENGTH_SHORT).show();
        wm.disconnect();
        wm.enableNetwork(netId, true);
        wm.reconnect();
        wm.saveConfiguration();
        a.changeScreen(a.STATE_WIFI);
    }

    public void startBluetoothScan(MainActivity a) {
        int currentFocusIndex = 0;
        if (a.containerBtItems != null) {
            for (int i = 0; i < a.containerBtItems.getChildCount(); i++) {
                if (a.containerBtItems.getChildAt(i).hasFocus()) {
                    currentFocusIndex = i;
                    break;
                }
            }
        }
        final int targetFocusIndex = currentFocusIndex;

        final BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean isOn = false;
        String statusText = "OFF";

        if (ba != null) {
            int state = ba.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                isOn = true;
                statusText = "ON";
            } else if (state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF) {
                statusText = "Wait...";
            }
        }

        a.containerBtItems.removeAllViews();

        // 1. Power toggle button
        final LinearLayout btnToggle = a.createSettingRow(a.t("Bluetooth Power"), a.t(statusText));
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (ba != null) {
                    if (ba.isEnabled())
                        ba.disable();
                    else
                        ba.enable();
                    ((TextView) btnToggle.getChildAt(1)).setText(a.t("Wait..."));
                }
            }
        });
        a.containerBtItems.addView(btnToggle);

        if (!isOn) {
            a.btnScanBt.setText(a.t("Bluetooth is OFF"));
            if (a.btnScanBt.getParent() != null)
                ((android.view.ViewGroup) a.btnScanBt.getParent()).removeView(a.btnScanBt);
            a.containerBtItems.addView(a.btnScanBt);
            restoreBluetoothFocus(a, targetFocusIndex);
            return;
        }

        // 🚀 2. Fully implemented like the stock launcher: My Devices (PAIRED DEVICES) list
        TextView tvPaired = new TextView(a);
        tvPaired.setText("━ "+a.t("PAIRED DEVICES")+" ━");
        tvPaired.setTextColor(0xBBFFFFFF);
        tvPaired.setTextSize(14);
        tvPaired.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvPaired.setPadding(10, 30, 10, 5);
        a.containerBtItems.addView(tvPaired);

        try {
            java.util.Set<BluetoothDevice> pairedDevices = ba.getBondedDevices();
            if (pairedDevices != null && pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    addPairedBluetoothItemToUI(a, device); // Call the UI dedicated to paired devices
                }
            } else {
                TextView tvEmpty = new TextView(a);
                tvEmpty.setText(a.t("No paired devices."));
                tvEmpty.setTextColor(0xFF888888);
                tvEmpty.setPadding(10, 10, 10, 10);
                a.containerBtItems.addView(tvEmpty);
            }
        } catch (Exception e) {
            Log.d(TAG, "startBluetoothScan failed", e);
        }

        // 🚀 3. Newly found devices (AVAILABLE DEVICES) list
        TextView tvAvailable = new TextView(a);
        tvAvailable.setText("━ " + a.t("AVAILABLE DEVICES") + " ━");
        tvAvailable.setTextColor(0xBBFFFFFF);
        tvAvailable.setTextSize(14);
        tvAvailable.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvAvailable.setPadding(10, 30, 10, 5);
        a.containerBtItems.addView(tvAvailable);

        a.btnScanBt.setText(a.t("Scanning..."));
        a.foundBtDevices.clear();

        if (a.btnScanBt.getParent() != null)
            ((android.view.ViewGroup) a.btnScanBt.getParent()).removeView(a.btnScanBt);
        a.containerBtItems.addView(a.btnScanBt);

        if (ba.isDiscovering()) {
            ba.cancelDiscovery();
            new Handler().postDelayed(new Runnable() {
                public void run() {
                    ba.startDiscovery();
                }
            }, 500);
        } else {
            ba.startDiscovery();
        }

        restoreBluetoothFocus(a, targetFocusIndex);
    }

    public void addPairedBluetoothItemToUI(MainActivity a, final BluetoothDevice device) {
        String name = (device.getName() != null && !device.getName().isEmpty()) ? device.getName()
                : "Unknown (" + device.getAddress() + ")";

        boolean isConnected = com.themoon.y1.managers.BluetoothAudioManager.getInstance().isA2dpConnectedTo(device);

        String prefix = isConnected ? "((♪)) [CONNECTED] " : "✔ ";
        final Button btnDevice = a.createListButton(prefix + name);

        if (isConnected) {
            int themeColor = 0xFF00FFFF;
            try {
                themeColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            } catch (Exception e) {
                Log.d(TAG, "addPairedBluetoothItemToUI failed", e);
            }
            btnDevice.setTextColor(themeColor);
            btnDevice.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            btnDevice.setTextColor(0xFF00FF00);
        }

        // 🚀 [Critical bug fix] Blow away the transparent sub-menu folder (LinearLayout) and attach directly to the list!

        // 🚀 [Hybrid engine integration] Inject the audio-connect unicode ("\uE1B1") and white (0xFFFFFFFF).
        // (Note: the return type changes from Button to android.view.View)
        final android.view.View btnConnect = a.createListButtonWithIcon("\uE1B1", a.t("Connect Audio"), 0xFFFFFFFF);

        btnConnect.setVisibility(View.GONE); // Hidden initially.
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.connectBluetoothAudio(device); // 🚀 Call the central engine!
            }
        });

        // 🚀 [Hybrid engine integration] Inject the trash-can unicode ("\uE872") and red (0xFFFF5555).
        // (Note: the return type changes from Button to android.view.View)
        final android.view.View btnUnpair = a.createListButtonWithIcon("\uE872", a.t("Delete Device"), 0xFFFF5555);

        btnUnpair.setVisibility(View.GONE); // Hidden initially.
        btnUnpair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                try {
                    // Deleting the device is a deliberate "stop connecting to this" -- drop it as the
                    // watchdog target and cancel any pending reconnect so we don't re-pair behind the user.
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().forgetTargetIfMatches(device);
                    device.getClass().getMethod("removeBond").invoke(device);
                    Toast.makeText(a, a.t("Device Deleted."), Toast.LENGTH_SHORT).show();
                    startBluetoothScan(a); // Refresh the screen after removal
                } catch (Exception e) {
                    Log.d(TAG, "addPairedBluetoothItemToUI failed", e);
                }
            }
        });

        // Strips the invisibility cloak off the hidden sub-menu buttons when the parent button is clicked.
        btnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (btnConnect.getVisibility() == View.GONE) {
                    btnConnect.setVisibility(View.VISIBLE);
                    btnUnpair.setVisibility(View.VISIBLE);

                    // 💡 Makes the wheel cursor naturally land on the 'Connect Audio' button as soon as the sub-menu opens!
                    btnConnect.post(new Runnable() {
                        public void run() {
                            btnConnect.requestFocus();
                        }
                    });
                } else {
                    btnConnect.setVisibility(View.GONE);
                    btnUnpair.setVisibility(View.GONE);
                }
            }
        });

        // 🚀 To prevent index tangling, just stack them up in order when building the screen structure.
        a.containerBtItems.addView(btnDevice);
        a.containerBtItems.addView(btnConnect);
        a.containerBtItems.addView(btnUnpair);
    }

    public void addBluetoothItemToUI(MainActivity a, String name, final BluetoothDevice device, boolean isPaired) {
        if (device.getBondState() == BluetoothDevice.BOND_BONDED)
            return; // Ignore since paired devices are drawn above

        final Button btnDevice = a.createListButton("🔍 " + name);

        btnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.connectBluetoothAudio(device); // 🚀 Even if unpaired, the central engine handles pairing first automatically!
            }
        });

        a.containerBtItems.addView(btnDevice, a.containerBtItems.getChildCount() - 1);
    }

    public void restoreBluetoothFocus(MainActivity a, final int targetFocusIndex) {
        a.containerBtItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (a.containerBtItems.getChildCount() > 0) {
                    if (targetFocusIndex >= 0 && targetFocusIndex < a.containerBtItems.getChildCount()) {
                        View target = a.containerBtItems.getChildAt(targetFocusIndex);
                        if (target.isFocusable()) {
                            target.requestFocus();
                            return;
                        }
                    }
                    a.containerBtItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    public void startWifiScan(MainActivity a) {
        WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isOn = wm != null && wm.isWifiEnabled();
        updateWifiUI(a, null);

        if (isOn) {
            a.btnScanWifi.setText(a.t("Scanning..."));
            a.foundWifiNetworks.clear();
            // 💡 Always force-move focus to the top power button!
            if (a.containerWifiItems.getChildCount() > 0)
                a.containerWifiItems.getChildAt(0).requestFocus();
            wm.startScan();
        } else {
            a.btnScanWifi.setText(a.t("Wi-Fi is OFF"));
            // 💡 Always force-move focus to the top power button!
            if (a.containerWifiItems.getChildCount() > 0)
                a.containerWifiItems.getChildAt(0).requestFocus();
        }
    }

    public void updateWifiUI(MainActivity a, List<ScanResult> results) {
        final WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isOn = false;
        String statusText = "OFF";

        if (wm != null) {
            int state = wm.getWifiState();
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                isOn = true;
                statusText = "ON";
            } else if (state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_DISABLING) {
                statusText = "Wait...";
            }
        }

        View existingToggle = a.containerWifiItems.findViewById(999992);
        if (existingToggle == null) {
            final LinearLayout btnToggle = a.createSettingRow(a.t("Wi-Fi Power"), a.t(statusText));
            btnToggle.setId(999992);
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    if (wm != null) {
                        boolean isCurrentlyOn = wm.isWifiEnabled();
                        if (isCurrentlyOn) {
                            Toast.makeText(a, a.t("Turning Wi-Fi OFF..."), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(false);
                        } else {
                            Toast.makeText(a, a.t("Turning Wi-Fi ON..."), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(true);
                        }
                        TextView tvRight = (TextView) btnToggle.getChildAt(1);
                        tvRight.setText(a.t("Wait..."));
                        if (!btnToggle.hasFocus())
                            tvRight.setTextColor(0xFFFFFF00);
                    }
                }
            });
            a.containerWifiItems.addView(btnToggle, 0);

            a.btnWifiWebServer = a.createSettingRow(a.t("Web Server"), "〉 ");
            a.btnWifiWebServer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    a.changeScreen(a.STATE_WEBSERVER);
                }
            });
            a.containerWifiItems.addView(a.btnWifiWebServer, 0);

            if (a.btnScanWifi.getParent() != null) {
                ((android.view.ViewGroup) a.btnScanWifi.getParent()).removeView(a.btnScanWifi);
            }
            a.containerWifiItems.addView(a.btnScanWifi);
        } else {
            LinearLayout btnToggle = (LinearLayout) existingToggle;
            TextView tvRight = (TextView) btnToggle.getChildAt(1);
            tvRight.setText(a.t(statusText));
            if (!btnToggle.hasFocus()) {
                if (statusText.equals("ON"))
                    tvRight.setTextColor(0xFFFFFFFF);
                else if (statusText.equals("OFF"))
                    tvRight.setTextColor(0xFF888888);
                else
                    tvRight.setTextColor(0xFFFFFFFF);
            }
            for (int i = a.containerWifiItems.getChildCount() - 1; i >= 0; i--) {
                View v = a.containerWifiItems.getChildAt(i);
                if (v != a.btnScanWifi && v != a.btnWifiWebServer && v.getId() != 999992) {
                    a.containerWifiItems.removeViewAt(i);
                }
            }
            if (a.btnWifiWebServer.getParent() == null) {
                a.containerWifiItems.addView(a.btnWifiWebServer, 0);
            }
        }

        if (!isOn)
            return;

        if (results != null) {
            a.foundWifiNetworks.clear();
            WifiManager manager = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = manager.getConnectionInfo();
            String connectedSSID = "";
            if (wifiInfo != null && wifiInfo.getSSID() != null) {
                connectedSSID = wifiInfo.getSSID().replace("\"", "");
            }

            // 🚀 Priority 1: Find the currently connected Wi-Fi first and place it at the top!
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty() && !a.foundWifiNetworks.contains(result.SSID)) {
                    if (result.SSID.equals(connectedSSID)) {
                        a.foundWifiNetworks.add(result.SSID);
                        addWifiItemToUI(a, result.SSID, result.capabilities, true);
                    }
                }
            }

            // 🚀 Priority 2: List the rest of the unconnected miscellaneous Wi-Fi networks below it
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty() && !a.foundWifiNetworks.contains(result.SSID)) {
                    a.foundWifiNetworks.add(result.SSID);
                    addWifiItemToUI(a, result.SSID, result.capabilities, false);
                }
            }
        }
    }

    public void addWifiItemToUI(MainActivity a, final String ssid, String capabilities, final boolean isConnected) {
        final boolean isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP");
        String lockIcon = isOpen ? "📶 " : "🔒 ";

        // Give connected devices a nice Apple-style checkmark (✔) instead of plain text!
        String prefix = isConnected ? "✔ " : "";

        Button btnWifi = a.createListButton(prefix + lockIcon + ssid);

        if (isConnected) {
            btnWifi.setTextColor(0xFF00FF00); // Eye-catching green!
            btnWifi.setTypeface(null, android.graphics.Typeface.BOLD); // Emphasize with bold text!
        }

        btnWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (isConnected) {
                    return;
                }

                WifiManager manager = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                boolean isSaved = false;
                int savedNetId = -1;
                try {
                    List<WifiConfiguration> configuredNetworks = manager.getConfiguredNetworks();
                    if (configuredNetworks != null) {
                        for (WifiConfiguration conf : configuredNetworks) {
                            if (conf.SSID != null && conf.SSID.equals("\"" + ssid + "\"")) {
                                isSaved = true;
                                savedNetId = conf.networkId;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "addWifiItemToUI failed", e);
                }

                if (isSaved && savedNetId != -1) {
                    Toast.makeText(a, a.t("Connecting to saved network..."), Toast.LENGTH_SHORT).show();
                    manager.disconnect();
                    manager.enableNetwork(savedNetId, true);
                    manager.reconnect();
                } else {
                    a.targetWifiSsid = ssid;
                    a.isTargetWifiOpen = isOpen;
                    a.changeScreen(a.STATE_WIFI_KEYBOARD);
                }
            }
        });
        a.containerWifiItems.addView(btnWifi);
    }

}
