package com.themoon.y1;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;



import com.themoon.y1.adapters.CategoryListAdapter;
import com.themoon.y1.adapters.SongListAdapter;
import com.themoon.y1.models.SongItem;
import com.themoon.y1.views.AudioVisualizerView;
import com.themoon.y1.views.BatteryIconView;
import com.themoon.y1.views.CircularBatteryView;
import com.themoon.y1.views.CustomAnalogClockView;
import com.themoon.y1.views.EqSliderView;
import com.themoon.y1.views.PieChartView;
import com.themoon.y1.views.WidgetBatteryBarView;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    // Note: Always add a trailing slash (/) at the end of the address!
    public static final String SERVER_BASE_URL = "http://knock2025.cafe24.com/knock_knock/y1/";
    public static final String METADATA_URL = SERVER_BASE_URL + "output-metadata.json";
    // 🚀 [Major overhaul complete] Smart control panel that lets you set the desired number of albums (odd numbers: 3, 5, 7, etc.) at any time
    private int visibleCoversCount = 7; // 💡 Back to 5! Just change this value (e.g. to 7) for testing and everything else updates automatically.

    private android.widget.FrameLayout coverFlowContainer;
    private android.view.View[] cfViews; // 💡 Size is determined dynamically by the UI builder below.




    private boolean isNavigatingToSubMenu = false; // 🚀 [Add one line here!] Guard that prevents focus tangling during direct access
    // Bluetooth A2DP proxy, target device, connecting-state, and reconnect-backoff state all live
    // in BluetoothAudioManager now -- see that class for the field-level rationale.
    private Y1UsbFocusHelper usbFocusHelper;
    // 🚀 [New] Control switch for the virtual screen-off (fake blackout)
    public boolean isFakeScreenOff = false;

    // 🚀 [New] "Wheel lock" to prevent pocket misfires — once the screen turns on (real hardware wake)
    // all button input is ignored until the wheel has been turned a certain number of clicks.
    // State machine lives in WheelLockManager; this Activity only builds the overlay View tree
    // and routes dispatchKeyEvent()/screen-wake into it.
    private LinearLayout layoutWheelLockOverlay;
    private com.themoon.y1.views.WheelLockRingView wheelLockRing;

    // 🚀 [New] Direct-shortcut back-navigation return-path tracker!
    public int backTargetForPlayer = STATE_BROWSER;
    public int backTargetForUtility = STATE_SETTINGS;
    // 🚀 [New] Guard that prevents a fake click event from firing when the virtual blackout wakes up
    public boolean ignoreNextKeyUp = false;
    // 🚀 [New] Variables for radio control
    public int activePlayer = 0; // 0: music player, 1: radio
    public boolean isRadioScanning = false;
    public java.util.List<Float> savedRadioStations = new java.util.ArrayList<>();

    public static final int BROWSER_COVER_FLOW = 9;
    private java.util.List<SongItem> uniqueAlbumList = new java.util.ArrayList<>();
    private int currentCoverFlowIndex = 0;

    // 🚀 [Unified engine] Perfectly syncs the status bar (ivStatusPlay) regardless of whether radio or the music player is active!
    public void updateGlobalStatusPlayIcon() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(MainActivity.this);
                    com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();

                    // 💡 If music is playing, control always goes to music! (radio is force-stopped)
                    if (am.isPlaying()) {
                        activePlayer = 0;
                        if (fm.isPowerUp) fm.powerDown();
                    } else if (fm.isPowerUp) {
                        activePlayer = 1; // 💡 If radio is on, control goes to radio!
                    }

                    if (ivStatusPlay != null) {
                        if (fm.isPowerUp || am.isPlaying()) {
                            ivStatusPlay.setVisibility(View.VISIBLE);
                            ivStatusPlay.setImageResource(android.R.drawable.ic_media_play);
                        } else {
                            // when both are off
                            if (currentPlaylist.isEmpty() && !am.isNavidromeMode && activePlayer == 0) {
                                ivStatusPlay.setVisibility(View.GONE);
                            } else {
                                ivStatusPlay.setVisibility(View.VISIBLE);
                                ivStatusPlay.setImageResource(android.R.drawable.ic_media_pause);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "updateGlobalStatusPlayIcon failed", e);
                }
            }
        });
    }

    // 🚀 [New tool] Left/right saved-channel jump feature for hardware buttons
    public void tuneToNextSavedRadioChannel(boolean isNext) {
        com.themoon.y1.managers.FmRadioUiManager.getInstance().tuneToNextSavedChannel(this, isNext);
    }
    // 🚀 [New] Memory slot to hold the Material Icons font
    private android.graphics.Typeface materialIconFont = null;
    public boolean isLongPressConsumed = false; // 🚀 Added long-press guard variable
    public boolean isSeekPerformed = false;
    private long lastSeekTime = 0;
    // 🚀 [New] Global audio effect variables and profile state management
    public android.media.audiofx.BassBoost bassBoost;
    public android.media.audiofx.Virtualizer virtualizer;
    public int currentBassBoostStep = 0;    // 0: OFF, 1: Weak, 2: Normal, 3: Strong
    public int currentVirtualizerStep = 0;  // 0: OFF, 1: Weak, 2: Normal, 3: Strong
    public String currentEqProfile = "preset_0"; // preset_0~X or custom_name
    public int[] customBandLevels = new int[32]; // Cache bank for custom tuning values
    public int settingsSubMode = 0;         // 0: general, 1: date/time, 2: equalizer routing
    public int currentAudioSessionId = -1;  // 🚀 [Added] Variable to remember the audio session ID currently in use
    public int currentAdjustingBand = -1;   // 🚀 [Added] Remembers which frequency is currently being adjusted in the Graphic EQ.
    public boolean isWidgetFocusImageOn = false; // 🚀 [Added] Power variable for the focus-image widget
    // 💡 [Added] Home-screen widget related variables
    public boolean isWidgetClockOn = false;
    public boolean isWidgetBatteryOn = false;
    public boolean isWidgetAlbumOn = false;
    public boolean isWidgetAnalogClockOn = false;
    public boolean isWidgetCircularBatteryOn = false;

    // 🚀 [New engine control variable] List-box hide and loop-scroll switch

    public boolean isLoopScrollOn = true; // 💡 Set to true by default so infinite loop scrolling works!
    private TextView tvWidgetClock;
    // 🚀 [Fix] Renamed to the horizontal Bar class!
    private WidgetBatteryBarView widgetBatteryView;
    // Add the line below near where the other widget variables are declared (e.g. WidgetBatteryBarView widgetBatteryView; etc.).
    CircularBatteryView customCircularBatteryView;
    CustomAnalogClockView customAnalogClockView;
    private ImageView ivWidgetAlbum;
    public String lastBrowserFocusText = "";
    private String lastMainMenuFocusAction = "";
    // 🚀 [Added] Title/artist variables dedicated to the album widget
    private TextView tvWidgetAlbumTitle;
    private TextView tvWidgetAlbumArtist;
    // 💡 [Added] Variables dedicated to fast index jump (alphabet scroll)
    public List<String> currentScrollIndexList = new ArrayList<>();
    public long lastWheelTime = 0;
    public int wheelFastCount = 0;
    public static MainActivity instance;
    public long lastTrackChangeTime = 0; // 🚀 Guard variable to block duplicate key signals from the device
    // 💡 [Added] Audio spectrum related variables
    private android.media.audiofx.Visualizer audioVisualizer;
    private AudioVisualizerView visualizerView;
    // 🚀 [New] LRC lyrics parser and UI variables
    private android.widget.ScrollView lyricScrollView;
    private TextView tvLyrics;
    private java.util.TreeMap<Integer, String> currentLyrics = new java.util.TreeMap<>();
    private List<Integer> lyricTimestamps = new ArrayList<>();
    private int lastLyricIndex = -1;
    // 💡 Bucket that holds the "unsynchronized" plain-text lyrics embedded inside the MP3
    private String plainLyrics = null;

    private boolean isVisualizerShowing = false;
    public static final int STATE_MENU = 1;
    public static final int STATE_BROWSER = 2;
    public static final int STATE_PLAYER = 3;
    public static final int STATE_SETTINGS = 4;
    public static final int STATE_BLUETOOTH = 5;
    public static final int STATE_WIFI = 6;
    public static final int STATE_WIFI_KEYBOARD = 7;
    public static final int STATE_BRIGHTNESS = 8;
    public static final int STATE_STORAGE = 9;
    public static final int STATE_WEBSERVER = 10;
    public static final int STATE_NAVIDROME = 11;
    // 💡 Media library browser state management variables
    public static final int BROWSER_ROOT = 0;
    public static final int BROWSER_FOLDER = 1;
    public static final int BROWSER_ARTISTS = 2;
    public static final int BROWSER_ALBUMS = 3;
    public static final int BROWSER_VIRTUAL_SONGS = 4;
    // 💡 [Added] Blacklist that remembers "poison files" that were corrupted and crashed the app
    private java.util.Set<String> blacklist = new java.util.HashSet<>();
    public int currentBrowserMode = BROWSER_ROOT;
    public String virtualQueryType = "";
    public String virtualQueryValue = "";
    public List<File> virtualSongList = new ArrayList<>();
    // 💡 Background media control (screen-off) variable

    private ImageView ivStatusPlay;

    // 💡 Added near the media library browser state management variables
    public static final int BROWSER_FAVORITES = 5;
    // 🚀 [New virtual browser mode dedicated to native M3U]
    public static final int BROWSER_PLAYLISTS = 6;
    public static final int BROWSER_M3U_SONGS = 7;
    public static final int BROWSER_AUDIOBOOKS = 8; // 🚀 [Added] Activates the audiobook browser state
    // 🚀 [New constants] Loads the year and genre state switches with unique, non-overlapping numbers.
    public static final int BROWSER_YEARS = 10;
    public static final int BROWSER_GENRES = 11;
    // 🚀 [New] Cover flow state constants and data storage
//    public static final int BROWSER_COVER_FLOW = 9;
//    private java.util.List<SongItem> uniqueAlbumList = new java.util.ArrayList<>();
//    private int currentCoverFlowIndex = 0;
    private File currentM3uFile = null; // Address of the M3U file the user is currently viewing
    // 🚀 [Added] Variables dedicated to favorites
    public java.util.Set<String> favoritePaths = new java.util.HashSet<>();
    private TextView tvPlayerFavoriteStatus;

    public int consecutiveErrorCount = 0;
    // 🚀 [Added] Variables for displaying scan progress
    public ProgressBar pbLoadingProgress;
    public TextView tvLoadingProgress;
    private int totalAudioFiles = 0;
    private int scannedAudioFiles = 0;
    // 💡 [Ultra-fast engine] Recycler ListView (to handle thousands of tracks) alongside the existing ScrollView
    public android.widget.ListView listVirtualSongs;
    private View scrollViewBrowser;
    public boolean isScreenOffControlEnabled = false;
    public boolean isAutoFetchEnabled = true; // 🚀 [Added] Default value for the automatic internet lookup switch
    public static List<SongItem> customLibrary = new ArrayList<>();
    public static List<SongItem> audiobookLibrary = new ArrayList<>(); // 🚀 New bucket dedicated to audiobooks!

    public boolean isAudiobookLibraryMode = false; // 🚀 Switch that remembers which mode is currently active
    public File audiobookRootFolder = new File("/storage/sdcard0/Audiobooks"); // 🚀 Root folder dedicated to audiobooks
    // 🚀 [Added] Bank of smart control variables dedicated to the built-in radio
    // 🚀 [Added] Bank of smart control variables dedicated to the built-in radio (modal UI upgrade!)
    public boolean isRadioUIShowing = false; // determines whether the current screen is the radio
    public boolean isRadioSettingsMode = false; // determines whether we are in settings mode within the radio
    public boolean isRadioAdjustingFreq = false;

    public int lastRadioFocusIndex = 1;
    // (Volume-only variables and the complex focus index are no longer needed, so they were boldly removed!)
    private boolean isCustomScanning = false;
    public java.util.HashMap<String, Integer> trackNumberMap = new java.util.HashMap<>();
    public com.themoon.y1.db.LibraryCacheDb libraryCacheDb;
    public int currentScreenState = STATE_MENU;
    // 💡 Temporary variable for the custom date/time settings
    public int dtYear = 2026, dtMonth = 1, dtDay = 1, dtHour = 12, dtMinute = 0;
    private View layoutMainMenu, layoutBrowserMode;
    public View layoutSettingsMode;
    private View layoutBluetoothMode, layoutWifiMode, layoutWifiKeyboard;
    private View layoutPlayerMode, layoutVolumeOverlay;
    private View layoutBrightnessMode, layoutStorageMode, layoutWebServerMode;
    private View layoutNavidromeMode;
    public LinearLayout containerNavidromeItems;
    public TextView tvNavidromePath;
    public TextView tvNavidromeStatus;

    // Navidrome browse state
    public static final int NAV_ARTISTS = 0;
    public static final int NAV_ALBUMS  = 1;
    public static final int NAV_SONGS   = 2;
    public int navidromeBrowseDepth = NAV_ARTISTS;
    public com.themoon.y1.subsonic.SubsonicArtist selectedNavidromeArtist;
    public java.util.List<com.themoon.y1.subsonic.SubsonicArtist> lastNavidromeArtists = new java.util.ArrayList<>();
    public boolean isNavidromeLetterView = false; // letter-jump picker showing instead of artist list
    public int navidromeBackTarget = STATE_MENU;  // where the back button exits to (main menu or Music library)
    // Everything else Navidrome-specific (selected album, download queue, wake/wifi locks, etc.)
    // lives in NavidromeManager -- see that class for the field-level rationale.

    private LinearLayout containerBrowserItems;
    public LinearLayout containerSettingsItems;
    private LinearLayout containerBtItems, containerWifiItems;

    private TextView tvStatusClock, tvStatusBattery;
    private ImageView ivStatusBluetooth, ivStatusWifi, ivStatusHeadphone, ivMainBg;

    public TextView tvBrowserPath, tvPlayerTitle, tvPlayerArtist, tvPlayerTimeCurrent, tvPlayerTimeTotal;
    // 🚀 [Variables dedicated to the capsule UI]
    public LinearLayout layoutAudioQualityContainer;
    public TextView tvQualityExt;
    public TextView tvQualityFormat;
    public TextView tvQualityBitrate;

    public TextView tvPlayerTrackCount;
    private ImageView ivPlayerShuffleStatus, ivPlayerRepeatStatus; // 💡 Changed from TextView to ImageView!
    public ProgressBar playerProgress;
    private ProgressBar volumeProgress, pbBrightness, pbStorage;
    private TextView tvBrightnessVal, tvStorageDetails;
    // 💡 [Fix] Removed the manual APP_VERSION variable and only kept the server folder address.
    public boolean is24HourFormat = false;
    private TextView tvServerStatus, tvServerIp;
    private Button btnServerToggle;
    // 🚀 [Added] Advanced loading indicator overlay that covers the whole screen
    public LinearLayout layoutLoadingOverlay;
    public ImageView ivMenuPreview, ivAlbumArt, ivPlayerBgBlur, ivPauseOverlay;

    private Button btnNowPlaying, btnPlay, btnSettings, btnBluetooth, btnRadio;
    private Button btnScanBt, btnScanWifi;
    private LinearLayout btnWifiWebServer;

    private TextView tvKeyboardSsid, tvKeyboardInput;
    private TextView tvKeyPprev, tvKeyPrev, tvKeyCurrent, tvKeyNext, tvKeyNnext;

    public final String[] KEYBOARD_CHARS = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
            "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
            "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "!", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", ".", "?",
            "[DEL]", "[CONN]"
    };

    private android.media.RemoteControlClient remoteControlClient;
    private android.content.ComponentName mediaButtonReceiver;



    private boolean wasWifiOnBeforeSleep = false;
    public int keyboardIndex = 0;
    private String targetWifiSsid = "";
    private String typedPassword = "";
    private boolean isTargetWifiOpen = false;
    // 💡 Variable that tracks whether the media scanner is currently working
    private boolean isMediaScanning = false;
    private AudioManager audioManager;
    public File rootFolder = new File("/storage/sdcard0/Music");
    public File currentFolder = rootFolder;
    public List<File> originalPlaylist = new ArrayList<File>();
    public List<File> currentPlaylist = new ArrayList<File>();
    public int currentIndex = 0;
    public boolean isPausedByHand = true;
    private float currentClockSize = 48f;
    public java.io.FileInputStream currentFileInputStream = null;
    private TextView tvMenuPreviewTitle, tvMenuPreviewArtist;
    public SharedPreferences prefs;
    public boolean isShuffleMode = false;
    public int repeatMode = 0; // 0: OFF, 1: ONE (Repeat One), 2: ALL (Repeat Folder/All)
    public boolean isSoundEffectEnabled = true;
    public boolean isSpeakerDisabled = false;
    public boolean isVibrationEnabled = true;
    public boolean isPickingBackground = false;

    // 💡 Variable that remembers the last-played album art
    public byte[] lastAlbumArtBytes = null;
    // refreshWidgets() runs every second forever via clockTask; these cache the last decoded
    // widget album thumbnail (keyed by identity of lastAlbumArtBytes) and the last known battery
    // reading (updated by the ACTION_BATTERY_CHANGED receiver) so that per-second tick doesn't
    // re-decode a bitmap and make two Binder round-trips to fetch the sticky battery intent.
    private byte[] widgetAlbumArtCachedSource = null;
    private android.graphics.Bitmap widgetAlbumArtCachedBitmap = null;
    // Caches so refreshWidgets() (called every second) doesn't re-allocate/re-decode when nothing changed.
    private android.graphics.Bitmap widgetDefaultAlbumBitmap = null;
    private boolean widgetAlbumShowingDefault = false;
    private int lastKnownBatteryPct = -1;
    private boolean lastKnownBatteryCharging = false;
    // 💡 Added equalizer related variable
    public Equalizer equalizer;
    public List<String> eqPresetNames = new ArrayList<String>();
    public int currentEqPresetIndex = 0;

    public int lastSettingsFocusIndex = 0;
    public int currentSettingsDepth = 1;
    public static final int GROUP_PLAYBACK = 0, GROUP_SOUND = 1, GROUP_CONNECTIVITY = 2, GROUP_DISPLAY = 3, GROUP_STORAGE = 4, GROUP_SYSTEM = 5;
    public int currentSettingsGroup = GROUP_PLAYBACK;
    private boolean isScreenSleeping = false;
    public long lastScreenOnTime = 0;
    // 💡 [Added] Custom battery view variable
    private BatteryIconView batteryIconView;
    public int currentTimeoutIndex = 1;
    public final int[] TIMEOUT_VALUES = { 15000, 30000, 60000, 300000 };
    public final String[] TIMEOUT_NAMES = { "15 Sec", "30 Sec", "1 Min", "5 Min" };
    private TextView tvFocusPreviewClock; // 🚀 [New engine] Digital clock that ticks inside the live-preview box
    private ImageView ivWidgetFocusImage; // 🚀 [Added] Dynamic focus widget variable

    // 🚀 [New engine variable] Backup vault to remember the existing widget's body and original coordinates
    private LinearLayout layoutWidgetAlbumContainer; // Address of the album widget block

    // 🚀 [Added] Unified registry to globally manage every widget's memory
    public java.util.HashMap<View, ThemeManager.MenuElement> widgetViewRegistry = new java.util.HashMap<>();
    public int currentSystemBrightness = 255;

    private List<String> foundBtDevices = new ArrayList<String>();
    private List<String> foundWifiNetworks = new ArrayList<String>();

    private Y1WebServer webServer;
    public boolean isServerRunning = false;
    public int vibrationStrengthLevel = 1; // 0: Weak, 1: Normal, 2: Strong
    // 🚀 Append parentheses to fully separate this into a key distinct from the equalizer's Normal!
    public final String[] VIBE_STRENGTH_NAMES = {"Weak", "Normal (Vibe)", "Strong"};
    // 💡 Key: 10ms (very short pulse), 25ms (normal wheel), 50ms (heavy rumble)
    private final int[] VIBE_DURATIONS = {10, 25, 50};
    // Pre-built so the once-a-second clock tick (and refreshWidgets, called from the same tick)
    // don't construct a new SimpleDateFormat every second forever -- pattern parsing on every
    // tick was pure waste since only two patterns are ever used, chosen by is24HourFormat.
    private static final SimpleDateFormat STATUS_CLOCK_FORMAT_24 = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat STATUS_CLOCK_FORMAT_12 = new SimpleDateFormat("hh:mm a", Locale.US);

    public Handler clockHandler = new Handler();
    // Reused across every clock tick so we don't allocate a Date/Spannable every second.
    private final java.util.Date clockReusableDate = new java.util.Date();
    private String lastStatusClockText = null;
    private String lastFocusPreviewClockText = null;
    public Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            clockReusableDate.setTime(System.currentTimeMillis());
            SimpleDateFormat sdf = is24HourFormat ? STATUS_CLOCK_FORMAT_24 : STATUS_CLOCK_FORMAT_12;
            String statusText = sdf.format(clockReusableDate);
            // Clock text is minute-granular; only touch the TextView when it actually changed.
            if (!statusText.equals(lastStatusClockText)) {
                lastStatusClockText = statusText;
                tvStatusClock.setText(statusText);
            }

            // 🚀 [Live engine] If the preview's internal clock is VISIBLE on screen, swap the time in real time every second to make it tick!
            if (tvFocusPreviewClock != null && tvFocusPreviewClock.getVisibility() == View.VISIBLE) {
                if (!statusText.equals(lastFocusPreviewClockText)) {
                    lastFocusPreviewClockText = statusText;
                    tvFocusPreviewClock.setText(statusText);
                }
            }

            refreshWidgets(); // Simultaneously refresh the home-screen widgets
            clockHandler.postDelayed(this, 1000);
        }
    };
    // 🚀 [New] 5-second auto-hide timer engine for the pill UI
    public Handler qualityInfoHandler = new Handler();
    public Runnable hideQualityInfoTask = new Runnable() {
        @Override
        public void run() {
            // When the timer fires, fully hides the pill container from the screen!
            if (layoutAudioQualityContainer != null) {
                layoutAudioQualityContainer.setVisibility(View.GONE);
            }
        }
    };
    // 🚀 [Added] Bank of variables for global double-click and root power control
    public android.os.Handler doubleClickHandler = new android.os.Handler();
    public long lastCenterUpTime = 0;
    public Runnable singleClickRunnable = new Runnable() {
        @Override
        public void run() {
            try { handleCenterShortClick(); } catch (Exception e) { Log.d(TAG, "tuneToNextSavedRadioChannel failed", e); }
        }
    };
    // 🚀 [Added] A short translation helper to make it easy to wrap long English sentences.
    public String t(String text) {
        return com.themoon.y1.managers.LanguageManager.getInstance(this).t(text);
    }

    // 🚀 [Smart Wi-Fi power-saving engine]
    public void autoManageWifiPower(boolean isGoingToSleep) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;

        if (isGoingToSleep) {
            // 💡 On sleep: force Wi-Fi off if the web server isn't running!
            if (wm.isWifiEnabled()) {
                wasWifiOnBeforeSleep = true; // Remember that it was originally on
                if (!isServerRunning) {
                    wm.setWifiEnabled(false);
                }
            } else {
                wasWifiOnBeforeSleep = false;
            }
        } else {
            // 💡 On wake: turn Wi-Fi back ON only if it was on before sleeping!
            if (wasWifiOnBeforeSleep && !wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
            }
        }
    }
    
    public void turnOffScreen() {
        com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
        if (fm.isPowerUp || activePlayer == 1) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    isFakeScreenOff = true;
                    autoManageWifiPower(true); // 🚀 [Entering power-saving mode]
                    if (layoutLoadingOverlay != null) {
                        layoutLoadingOverlay.setBackgroundColor(0xFF000000); // 100% fully black blackout cover
                        if (pbLoadingProgress != null) pbLoadingProgress.setVisibility(View.GONE);
                        if (tvLoadingProgress != null) tvLoadingProgress.setText("");
                        layoutLoadingOverlay.setAlpha(0.0f); // 🚀 Start at 0% opacity
                        layoutLoadingOverlay.setVisibility(View.VISIBLE);
                    }

                    // 🚀 [New engine] Virtual fade-out view renderer that smoothly dims the screen
                    // (vsync-synced property animator instead of a manual 25ms Handler loop)
                    layoutLoadingOverlay.animate().cancel();
                    layoutLoadingOverlay.animate()
                            .alpha(1.0f)
                            .setDuration(325)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing() || isDestroyed()) return;
                                    // Once the blackout is fully complete, lower the backlight brightness to minimum.
                                    try {
                                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                                        lp.screenBrightness = 0.01f;
                                        getWindow().setAttributes(lp);
                                    } catch (Exception e) {
                                        Log.d(TAG, "turnOffScreen failed", e);
                                    }
                                }
                            })
                            .start();
                }
            });
        } else {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 26"});
            } catch (Exception e) {
                Log.d(TAG, "turnOffScreen failed", e);
            }
        }
    }

    // 🚀 [New engine] Lossless engine that swaps only the frequency and candy-capsule color at ultra speed without a reload
    public LinearLayout layoutRadioCandyContainer;

    // 🚀 [New engine] Lossless engine that swaps only the frequency and candy-capsule color at ultra speed without a reload (left-edge escape bug fully fixed)
    // 🚀 [Core technique 1] A "global screen-off sensor" mounted directly on every button!
    public View.OnLongClickListener globalScreenOffLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            clickFeedback();
            isLongPressConsumed = true; // 🚀 Mark long-press as consumed (blocks Android's chronic bug of firing a click on release)
            turnOffScreen(); // Trigger the global screen-off!
            return true; // 💡 Must return true so the button understands "ah, the long press was handled, so cancel the regular click!"
        }
    };
    public Handler progressHandler = new Handler();
    // ⭕ [Overwrite with the code below]
    private String lastCurrentTimeText = null;
    private String lastTotalTimeText = null;
    public Runnable updateProgressTask = new Runnable() {
        @Override
        public void run() {
            try {
                com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                if (am.isPlaying() || am.isNavidromeMode) {
                    am.maybeSavePlaybackStateThrottled();
                    int current = am.getCurrentPosition();
                    int duration = am.getDuration();
                    // When ExoPlayer is buffering it returns 0 for duration; fall back to API metadata
                    if (duration <= 0 && am.isNavidromeMode && !am.navidromePlaylist.isEmpty()) {
                        duration = am.navidromePlaylist.get(am.navidromeIndex).durationSecs * 1000;
                    }
                    int progress = duration > 0 ? (int) (((float) current / duration) * 100) : 0;
                    playerProgress.setProgress(progress);
                    // Only push text when it actually changed (time is second-granular but the
                    // tick fires every 500ms), avoiding a redundant TextView relayout each tick.
                    String curStr = formatTime(current);
                    if (!curStr.equals(lastCurrentTimeText)) {
                        lastCurrentTimeText = curStr;
                        tvPlayerTimeCurrent.setText(curStr);
                    }
                    String totStr = formatTime(duration);
                    if (!totStr.equals(lastTotalTimeText)) {
                        lastTotalTimeText = totStr;
                        tvPlayerTimeTotal.setText(totStr);
                    }

                    // 🚀 [New engine] USLT plain-text lyrics proportional auto-scroll engine! (includes a 5-second intro wait)
                    if (isVisualizerShowing && plainLyrics != null && currentLyrics.isEmpty()) {
                        int maxScroll = tvLyrics.getHeight() - lyricScrollView.getHeight();

                        if (maxScroll > 0 && duration > 0) {
                            int delayMs = 5000; // 💡 Set a 5-second (5000ms) wait

                            // The wait algorithm only runs if the track's total length is longer than 5 seconds
                            if (duration > delayMs) {
                                if (current <= delayMs) {
                                    // Keep the scroll pinned to the top (0) until 5 seconds have passed!
                                    lyricScrollView.smoothScrollTo(0, 0);
                                } else {
                                    // After 5 seconds, calculate the real progress based on 'remaining time' and start scrolling!
                                    float progressRatio = (float) (current - delayMs) / (duration - delayMs);
                                    int targetScroll = (int) (maxScroll * progressRatio);
                                    lyricScrollView.smoothScrollTo(0, targetScroll);
                                }
                            } else {
                                // For short clips under 5 seconds (like sound effects), just scroll with no delay
                                float progressRatio = (float) current / duration;
                                lyricScrollView.smoothScrollTo(0, (int) (maxScroll * progressRatio));
                            }
                        }
                    }

                    // (existing code) 🚀 [Lyrics scroll engine] If lyrics mode is on... (rest unchanged)
                    if (isVisualizerShowing && !currentLyrics.isEmpty()) {
                        int currentKey = -1;
                        for (int i = 0; i < lyricTimestamps.size(); i++) {
                            if (current >= lyricTimestamps.get(i)) currentKey = lyricTimestamps.get(i);
                            else break;
                        }

                        if (currentKey != -1) {
                            int highlightIndex = lyricTimestamps.indexOf(currentKey);

                            // To avoid load: only redraw the UI when the lyrics line has actually changed.
                            if (highlightIndex != lastLyricIndex) {
                                lastLyricIndex = highlightIndex;
                                StringBuilder sb = new StringBuilder();

                                // 🚀 [Fix 1] Build an invisible 7-line frame — always 3 lines above and below.
                                // This way the sky-blue highlighted line stays perfectly pinned to the frame's exact center (4th line)!
                                int start = highlightIndex - 3;
                                int end = highlightIndex + 3;

                                for (int i = start; i <= end; i++) {
                                    if (i < 0 || i >= lyricTimestamps.size()) {
                                        // 💡 For empty space with no lyrics (start or end of the track), insert a transparent blank line (&nbsp;) to force-balance the center.
                                        sb.append("&nbsp;<br>");
                                    } else {
                                        String lyricText = currentLyrics.get(lyricTimestamps.get(i));
                                        if (i == highlightIndex) {
                                            // 🚀 [Fix 2] Reduce the line break from <br><br> to a single <br> to prevent overflow, and enlarge the text with <big> to emphasize it clearly.
                                            sb.append("<font color='#00FFFF'><b><big>").append(lyricText).append("</big></b></font><br>");
                                        } else {
                                            sb.append("<font color='#888888'>").append(lyricText).append("</font><br>");
                                        }
                                    }
                                }
                                tvLyrics.setText(android.text.Html.fromHtml(sb.toString()));

                                // 🚀 [Fix 3] Trim the TextView's excessive top/bottom padding and lock the scroll firmly to the top (0,0)!
                                tvLyrics.setPadding(20, 10, 20, 10);
                                if (lyricScrollView != null) {
                                    lyricScrollView.scrollTo(0, 0);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "updateRadioMainPlayerUI failed", e);
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    private Handler volumeHandler = new Handler();
    private Runnable hideVolumeTask = new Runnable() {
        @Override
        public void run() {
            layoutVolumeOverlay.setVisibility(View.GONE);
        }
    };

    private BroadcastReceiver systemStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isScreenSleeping = true;
                autoManageWifiPower(true); // 🚀 [Entering power-saving mode]
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenSleeping = false;
                lastScreenOnTime = System.currentTimeMillis();
                autoManageWifiPower(false); // 🚀 [Exiting power-saving mode]
                if (com.themoon.y1.managers.WheelLockManager.getInstance().isEnabled())
                    com.themoon.y1.managers.WheelLockManager.getInstance().activate();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                // 🚀 [Added] Check battery charging state
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);

                int batteryPct = (int) ((level / (float) scale) * 100);
                lastKnownBatteryPct = batteryPct;
                lastKnownBatteryCharging = isCharging;
                tvStatusBattery.setText(batteryPct + "%");

                // 🚀 Feed the newly made battery icon the current level and charging state!
                if (batteryIconView != null) {
                    batteryIconView.setBatteryLevel(batteryPct, isCharging);
                }
                if (widgetBatteryView != null) {
                    widgetBatteryView.setBatteryLevel(batteryPct, isCharging);
                }
                if (customCircularBatteryView != null) {
                    customCircularBatteryView.setBatteryLevel(batteryPct, isCharging);
                }
            } else if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                int state = intent.getIntExtra("state", -1);
                if (state == 1) {
                    ivStatusHeadphone.setVisibility(View.VISIBLE);
                    ivStatusHeadphone.setColorFilter(0xFFFFFFFF);
                } else {
                    ivStatusHeadphone.setVisibility(View.GONE);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    ivStatusBluetooth.setVisibility(View.VISIBLE);
                    updateBluetoothStatusIcon();

                    // 🚀 [Added] The moment Bluetooth turns on, don't forget to pre-set up the A2DP engine!
                    BluetoothAdapter.getDefaultAdapter().getProfileProxy(context,
                            new android.bluetooth.BluetoothProfile.ServiceListener() {
                                @Override
                                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                                    if (profile == BluetoothProfile.A2DP) {
                                        com.themoon.y1.managers.BluetoothAudioManager.getInstance().setA2dp(proxy);
                                        updateBluetoothStatusIcon();
                                    }
                                }

                                @Override
                                public void onServiceDisconnected(int profile) {
                                    if (profile == BluetoothProfile.A2DP)
                                        com.themoon.y1.managers.BluetoothAudioManager.getInstance().clearA2dp();
                                }
                            }, BluetoothProfile.A2DP);

                } else {
                    ivStatusBluetooth.setVisibility(View.GONE);
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().clearA2dp(); // 🚀 Reset the engine too when Bluetooth turns off
                    cancelAudioReconnect(); // nothing to reconnect to while the radio is off
                    AapService.deviceDisconnected(context);
                    applySpeakerSetting(); // 🚀 Bluetooth is off now, so re-evaluate speaker mute state
                }
                // (rest of the existing code unchanged)
                // 🚀 [Bug fix 1] Guard added so refresh only happens while the user is on the main settings screen (depth 0)!
                if (currentScreenState == STATE_SETTINGS && currentSettingsDepth == 0)
                    buildSettingsUI();
                else if (currentScreenState == STATE_BLUETOOTH)
                    startBluetoothScan();
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    ivStatusWifi.setVisibility(View.VISIBLE);
                    ivStatusWifi.setColorFilter(0xFFFFBB00);
                } else {
                    ivStatusWifi.setVisibility(View.GONE);
                }

                // 🚀 [Bug fix 2] Added the same depth-0 condition to the Wi-Fi sensor too!
                if (currentScreenState == STATE_SETTINGS && currentSettingsDepth == 0)
                    buildSettingsUI();
                else if (currentScreenState == STATE_WIFI)
                    startWifiScan();
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    ivStatusWifi.setColorFilter(0xFF00FF00);
                    if (currentScreenState == STATE_WIFI)
                        startWifiScan();
                } else {
                    ivStatusWifi.setColorFilter(0xFFFFBB00);
                }
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                String deviceName = device.getName();
                String deviceAddress = device.getAddress();
                // 🚀 ⭕ [Fix] Even if a device's name hasn't resolved yet (null), never drop it — show it in the list as 'Unknown Device (MAC address)'!
                String displayName = (deviceName != null && !deviceName.trim().isEmpty()) ? deviceName
                        : "Unknown (" + deviceAddress + ")";

                if (!foundBtDevices.contains(deviceAddress)) {
                    foundBtDevices.add(deviceAddress);
                    // Always add newly discovered devices to the list!
                    addBluetoothItemToUI(displayName, device, false);
                }
            } else if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)) {
                int profileState = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                BluetoothDevice currentDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                // 🚀 [Ported from stock: zombie logic 1] A2DP dropped -- hand the recovery to the backoff watchdog.
                if (profileState == BluetoothProfile.STATE_DISCONNECTED) {
                    Toast.makeText(context, t("Audio Disconnected"), Toast.LENGTH_SHORT).show();
                    AapService.deviceDisconnected(context);
                    BluetoothDevice audioTarget = com.themoon.y1.managers.BluetoothAudioManager.getInstance().getTargetDeviceForAudio();
                    if (audioTarget != null && currentDevice != null
                            && audioTarget.getAddress().equals(currentDevice.getAddress())) {
                        scheduleAudioReconnect();
                    }
                } else if (profileState == BluetoothProfile.STATE_CONNECTED) {
                    String name = currentDevice != null ? currentDevice.getName() : "Unknown";
                    Toast.makeText(context, t("Audio Connected to ") + name, Toast.LENGTH_SHORT).show();
                    cancelAudioReconnect();
                    // Delay AAP's raw L2CAP connect so it doesn't race the A2DP link that just came
                    // up -- opening a second low-level connection to the same device in the same
                    // instant destabilized the just-established ACL on this controller and caused a
                    // reconnect storm (confirmed by disabling AAP entirely and seeing it stop).
                    final BluetoothDevice aapTarget = currentDevice;
                    if (aapTarget != null) {
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (com.themoon.y1.managers.BluetoothAudioManager.getInstance().isA2dpConnectedTo(aapTarget)) {
                                    AapService.deviceConnected(MainActivity.this, aapTarget);
                                }
                            }
                        }, 2000);
                    }
                }
                // 🚀 Re-evaluate speaker mute state whenever the Bluetooth connection state changes (since this broadcast
                // is the source of truth, use the state just observed instead of re-querying)
                applySpeakerSetting(profileState == BluetoothProfile.STATE_CONNECTED);
                updateBluetoothStatusIcon();

                if (profileState == BluetoothProfile.STATE_CONNECTED
                        || profileState == BluetoothProfile.STATE_DISCONNECTED) {
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().setBtConnectingState(false); // Unlock!
                    // Only rescan after a disconnect (to refresh the device as available again).
                    // Rescanning right after a successful connect makes the shared BT/Wi-Fi radio
                    // re-run inquiry while holding the fresh link, which destabilizes it and can
                    // itself trigger the very disconnect this listener would then rescan for again.
                    if (profileState == BluetoothProfile.STATE_DISCONNECTED
                            && currentScreenState == STATE_BLUETOOTH) {
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startBluetoothScan();
                            }
                        }, 300);
                    }
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (currentScreenState == STATE_BLUETOOTH && !com.themoon.y1.managers.BluetoothAudioManager.getInstance().isBtConnectingState()) {
                    new Handler().postDelayed(new Runnable() {
                        public void run() {
                            startBluetoothScan();
                        }
                    }, 300);
                }
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    BluetoothDevice bondedDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    // 🚀 [Ported from stock: zombie logic 2] Call the engine immediately, without delay, as soon as pairing completes!
                    if (bondedDevice != null)
                        connectBluetoothAudio(bondedDevice);
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice disconnectedDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // 🚀 [Ported from stock: zombie logic 3] The device's own radio link bounced -- let the watchdog recover it.
                BluetoothDevice aclAudioTarget = com.themoon.y1.managers.BluetoothAudioManager.getInstance().getTargetDeviceForAudio();
                if (aclAudioTarget != null && disconnectedDevice != null
                        && aclAudioTarget.getAddress().equals(disconnectedDevice.getAddress())) {
                    scheduleAudioReconnect();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice connectedDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // The radio link itself coming back (e.g. AirPods case opened, or the user's hand
                // came off the antenna) is a fresh signal that the device is reachable again --
                // reset the backoff to its minimum so the watchdog grabs the audio channel fast
                // instead of waiting out the previous long backoff interval.
                BluetoothDevice aclConnectedTarget = com.themoon.y1.managers.BluetoothAudioManager.getInstance().getTargetDeviceForAudio();
                if (aclConnectedTarget != null && connectedDevice != null
                        && aclConnectedTarget.getAddress().equals(connectedDevice.getAddress())) {
                    cancelAudioReconnect();
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().resetBackoffToMin();
                    scheduleAudioReconnect();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                btnScanBt.setText(t("Scan Complete (Retry)"));
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    List<ScanResult> results = wm.getScanResults();
                    btnScanWifi.setText(t("Scan Complete (Retry)"));
                    updateWifiUI(results);
                }
            }
            // 🚀 [Add here!] Sensor to detect the system media scanner
            else if (Intent.ACTION_MEDIA_SCANNER_STARTED.equals(action)) {
                isMediaScanning = true;
            } else if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
                isMediaScanning = false;

            }
        }
    };

    // Bluetooth A2DP connection engine (proxy lifecycle, connect/pair, reconnect watchdog) lives
    // in BluetoothAudioManager -- see that class for details. Kept as thin pass-throughs here
    // since callers across this file (and AudioPlayerManager) already call these by name.
    private void scheduleAudioReconnect() {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().scheduleAudioReconnect();
    }

    private void cancelAudioReconnect() {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().cancelAudioReconnect();
    }

    public void nudgeAudioReconnectForAirpods() {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().nudgeAudioReconnectForAirpods();
    }

    private void connectBluetoothAudio(BluetoothDevice targetDevice) {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().connectBluetoothAudio(this, targetDevice);
    }
    // Inside the init function (called once when the app launches)
    public void initRemoteControlClient(android.content.Context context) {
        if (remoteControlClient == null) {
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

            // Connect the media button receiver (MediaBtnReceiver) we built earlier.
            mediaButtonReceiver = new android.content.ComponentName(context.getPackageName(), MainActivity.MediaBtnReceiver.class.getName());
            audioManager.registerMediaButtonEventReceiver(mediaButtonReceiver);

            // Create the intent for the remote-control client
            android.content.Intent mediaButtonIntent = new android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(mediaButtonReceiver);
            int pendingIntentFlags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
            android.app.PendingIntent mediaPendingIntent = android.app.PendingIntent.getBroadcast(context, 0, mediaButtonIntent, pendingIntentFlags);

            // 🚀 Launching the Jelly Bean-only broadcast station!
            remoteControlClient = new android.media.RemoteControlClient(mediaPendingIntent);

            // Grant permission for which buttons can be pressed from the car steering wheel and Bluetooth devices
            int flags = android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                    | android.media.RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS;
            remoteControlClient.setTransportControlFlags(flags);

            audioManager.registerRemoteControlClient(remoteControlClient);
        }
    }

    // 🚀 Called when the track changes!
    public void updateBluetoothMetadata(String title, String artist, String album, android.graphics.Bitmap albumArtBmp) {
        if (remoteControlClient == null) return;

        android.media.RemoteControlClient.MetadataEditor editor = remoteControlClient.editMetadata(true);

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
        if (remoteControlClient == null) return;

        int state = isPlaying ? android.media.RemoteControlClient.PLAYSTATE_PLAYING : android.media.RemoteControlClient.PLAYSTATE_PAUSED;

        // On Jelly Bean, the car runs its own timer even without sending the current position (currentPosition).
        remoteControlClient.setPlaybackState(state);
    }

    // 🚀 [New helper] Function that reads the current screen's track info and image and sends it over Bluetooth
    private void sendBluetoothMetaToCar() {
        String title = tvPlayerTitle != null ? tvPlayerTitle.getText().toString() : "Unknown";
        String artist = tvPlayerArtist != null ? tvPlayerArtist.getText().toString() : "Unknown";
        android.graphics.Bitmap bmp = null;

        // If album art exists, compress it slightly to fit the Bluetooth transfer size before sending.
        if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
            try {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                bmp = android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
            } catch (Exception e) {
                Log.d(TAG, "sendBluetoothMetaToCar failed", e);
            }
        }

        updateBluetoothMetadata(title, artist, "Y1 Player", bmp);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installTls12TrustAll();
        // 🚀 Registers itself in a variable when the app launches.
        instance = this;
        usbFocusHelper = new Y1UsbFocusHelper(this);
        AapService.addListener(com.themoon.y1.managers.BluetoothAudioManager.getInstance().aapEarListener);
        // 🚀 [Ultra-fast cache engine activated] Allocates 1/8 of the device's max memory as a vault dedicated to album art!
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8; // (e.g. allocates 16MB)

        albumArtCache = new android.util.LruCache<String, android.graphics.Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, android.graphics.Bitmap bitmap) {
                // Manages the vault's capacity by calculating the actual size (KB) the bitmap occupies in RAM.
                if (android.os.Build.VERSION.SDK_INT >= 12) {
                    return bitmap.getByteCount() / 1024;
                }
                return (bitmap.getRowBytes() * bitmap.getHeight()) / 1024;
            }
        };

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    e.printStackTrace(pw);
                    java.io.File logFile = new java.io.File("/storage/sdcard0/y1_crash_log.txt");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
                    fos.write(("\n\n--- 💥 CRASH REPORT (" + new java.util.Date().toString() + ") ---\n").getBytes());
                    fos.write(sw.toString().getBytes());
                    fos.close();
                } catch (Exception ex) {
                    Log.d(TAG, "onCreate failed", ex);
                }
                System.exit(1);
            }
        });
        // 🚀 [Added] Secures A2DP audio control ahead of time.
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(this,
                new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                        if (profile == android.bluetooth.BluetoothProfile.A2DP) {
                            com.themoon.y1.managers.BluetoothAudioManager.getInstance().setA2dp(proxy); // Loaded and ready!
                            updateBluetoothStatusIcon();
                            resyncAapWithConnectedDevice();
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == android.bluetooth.BluetoothProfile.A2DP)
                            com.themoon.y1.managers.BluetoothAudioManager.getInstance().clearA2dp();
                    }
                }, android.bluetooth.BluetoothProfile.A2DP);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        // 🚀 [Fix complete] Overwrite the existing loading overlay code entirely with the content below!
        android.view.ViewGroup root = findViewById(android.R.id.content);
        layoutLoadingOverlay = new LinearLayout(this);
        layoutLoadingOverlay.setOrientation(LinearLayout.VERTICAL);
        layoutLoadingOverlay.setGravity(android.view.Gravity.CENTER);
        layoutLoadingOverlay.setBackgroundColor(0xDD000000);
        layoutLoadingOverlay.setClickable(true);
        layoutLoadingOverlay.setFocusable(true);
        layoutLoadingOverlay.setVisibility(View.GONE);

        // 1. Instead of a spinning spinner, use a horizontal fill ProgressBar!
        pbLoadingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbLoadingProgress.setMax(100);
        android.widget.LinearLayout.LayoutParams pbLp = new android.widget.LinearLayout.LayoutParams(
                (int) (250 * getResources().getDisplayMetrics().density),
                (int) (20 * getResources().getDisplayMetrics().density));
        layoutLoadingOverlay.addView(pbLoadingProgress, pbLp);

        // 2. TextView that shows the live number
        tvLoadingProgress = new TextView(this);
        tvLoadingProgress.setText(t("Preparing to scan...\nPlease wait."));
        tvLoadingProgress.setTextColor(0xFFFFFFFF);
        tvLoadingProgress.setTextSize(18);
        tvLoadingProgress.setGravity(android.view.Gravity.CENTER);
        tvLoadingProgress.setPadding(0, 30, 0, 0);
        layoutLoadingOverlay.addView(tvLoadingProgress);

        root.addView(layoutLoadingOverlay, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // 🚀 [End of addition!]

        // 🚀 [New] Wheel-lock overlay — safely ignores accidental button presses inside a pocket!
        layoutWheelLockOverlay = new LinearLayout(this);
        layoutWheelLockOverlay.setOrientation(LinearLayout.VERTICAL);
        layoutWheelLockOverlay.setGravity(android.view.Gravity.CENTER);
        layoutWheelLockOverlay.setBackgroundColor(0xDD000000);
        layoutWheelLockOverlay.setClickable(true);
        layoutWheelLockOverlay.setFocusable(true);
        layoutWheelLockOverlay.setVisibility(View.GONE);

        float wheelLockDensity = getResources().getDisplayMetrics().density;
        int ringSize = (int) (140 * wheelLockDensity);

        android.widget.FrameLayout wheelLockRingFrame = new android.widget.FrameLayout(this);
        wheelLockRing = new com.themoon.y1.views.WheelLockRingView(this);
        android.widget.FrameLayout.LayoutParams ringLp = new android.widget.FrameLayout.LayoutParams(ringSize, ringSize);
        wheelLockRingFrame.addView(wheelLockRing, ringLp);

        TextView tvWheelLockIcon = new TextView(this);
        tvWheelLockIcon.setText("\uE897"); // Material Icons "lock" glyph — same icon font used everywhere else in the app
        tvWheelLockIcon.setTextColor(0xFFFFFFFF);
        tvWheelLockIcon.setTextSize(40);
        tvWheelLockIcon.setGravity(android.view.Gravity.CENTER);
        if (materialIconFont == null) {
            try { materialIconFont = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/MaterialIcons-Regular.ttf"); }
            catch (Exception e) {
                Log.d(TAG, "onCreate failed", e);
            }
        }
        if (materialIconFont != null) tvWheelLockIcon.setTypeface(materialIconFont);
        // Tag it so applyFontToAllViews() (which walks the whole tree and reassigns the app's
        // regular font to every TextView) skips this one and leaves the icon-font glyph alone
        tvWheelLockIcon.setTag("icon_font");
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(ringSize, ringSize);
        wheelLockRingFrame.addView(tvWheelLockIcon, iconLp);

        layoutWheelLockOverlay.addView(wheelLockRingFrame, new LinearLayout.LayoutParams(ringSize, ringSize));

        TextView tvWheelLockTitle = new TextView(this);
        tvWheelLockTitle.setText(t("Rotate wheel to unlock"));
        tvWheelLockTitle.setTextColor(0xFFFFFFFF);
        tvWheelLockTitle.setTextSize(18);
        tvWheelLockTitle.setGravity(android.view.Gravity.CENTER);
        tvWheelLockTitle.setPadding(0, 30, 0, 0);
        layoutWheelLockOverlay.addView(tvWheelLockTitle);

        root.addView(layoutWheelLockOverlay, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        com.themoon.y1.managers.WheelLockManager.getInstance().bindViews(layoutWheelLockOverlay, wheelLockRing);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // 🚀 [Officially registered with the system] Mounts a receiver so button signals can be received even with the screen off!
        ComponentName componentName = new ComponentName(getPackageName(), MediaBtnReceiver.class.getName());
        audioManager.registerMediaButtonEventReceiver(componentName);
// 🚀 [Bluetooth engine startup] Launching the Jelly Bean AVRCP broadcast station!
        initRemoteControlClient(this);
        prefs = getSharedPreferences("Y1_SETTINGS", MODE_PRIVATE);

        // 🚀 1. First things first! Extract all the language packs (.json) hidden inside the APK to the device.
        installBundledLanguages();

        // 🚀 2. Then start the language engine and load the language the user selected from the extracted files.
        String savedLang = prefs.getString("app_language", "English (Default)");
        com.themoon.y1.managers.LanguageManager.getInstance(this).applyLanguage(savedLang);
        // 🚀 [Dynamic theme file loading] Reads theme files from a folder on the device!
        File themeFolder = new File("/storage/sdcard0/Y1_Themes");

        // 💡 Before reading the theme list, first extract and install any default theme bundled in the APK, if present!
        installBundledThemes();

        ThemeManager.loadThemesFromStorage(themeFolder);
        // 🚀 [Bluetooth AVRCP 1.6 force-injection engine]
        // Since Developer Options is blocked, send ADB shell commands directly to the system via su privileges.
        // Nothing later in onCreate depends on this having finished -- run it off the UI thread
        // so the root-shell spawn/wait can't stall cold start (this call was the exact frame
        // caught blocking the main thread in an ANR trace during testing).
        new Thread(() -> {
            try {
                String cmd1 = "setprop persist.bluetooth.avrcpversion 1.6";
                String cmd2 = "settings put global bluetooth_avrcp_version 1.6";

                // Chain the two commands together with &&(AND) and sync the system.
                String combinedCmd = cmd1 + " && " + cmd2 + " && sync";

                Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", combinedCmd});
                proc.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "AvrcpVersionForce").start();
        try {
            // Load the saved index number. (Handled safely in case the file was deleted)
            int savedThemeIndex = prefs.getInt("app_theme_index", 0);
            ThemeManager.setThemeIndex(savedThemeIndex);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        // (the blacklist and other settings-loading code below is unchanged)
        // 💡 1. Blacklist (loaded safely by wrapping it in a new HashSet to avoid an internal Android bug)
        try {
            java.util.Set<String> savedBlacklist = prefs.getStringSet("blacklist", new java.util.HashSet<String>());
            blacklist = new java.util.HashSet<>(savedBlacklist);

            String poisonFile = prefs.getString("last_attempted_file", null);
            if (poisonFile != null) {
                blacklist.add(poisonFile);
                prefs.edit().putStringSet("blacklist", blacklist).remove("last_attempted_file").apply();
            }
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        // 💡 2. Load each setting independently (never skipped under any circumstance!)
        try {
            isShuffleMode = prefs.getBoolean("shuffle", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            if (prefs.contains("repeat_mode")) {
                repeatMode = prefs.getInt("repeat_mode", 0);
            } else {
                repeatMode = prefs.getBoolean("repeat", false) ? 1 : 0;
            }
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            isSoundEffectEnabled = prefs.getBoolean("sound", true);
            applySoundSetting();
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            isSpeakerDisabled = prefs.getBoolean("speaker_disabled", false);
            applySpeakerSetting();
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            isVibrationEnabled = prefs.getBoolean("vibrate", true);
            vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        try {
            isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            com.themoon.y1.managers.WheelLockManager.getInstance().setEnabled(prefs.getBoolean("wheel_lock_on_wake", false));
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            isAutoFetchEnabled = prefs.getBoolean("auto_fetch", true);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        } // 🚀 [Added]
        try {
            currentTimeoutIndex = prefs.getInt("timeout_idx", 1);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        try {
            currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    255);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        try {
            // Load the previously saved format setting from the vault (SharedPreferences). (default is 12-hour format)
            is24HourFormat = prefs.getBoolean("is_24h_format", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        currentEqPresetIndex = prefs.getInt("eq_preset", 0);
        // 🚀 [Added] Links advanced effects and custom profile vault data
        currentEqProfile = prefs.getString("eq_profile_id", "preset_" + currentEqPresetIndex);
        currentBassBoostStep = prefs.getInt("bass_boost_step", 0);
        currentVirtualizerStep = prefs.getInt("virtualizer_step", 0);

        // 💡 [Auto-load EQ preset list] Fetches the list of equalizer presets the device supports.
        // Building a MediaPlayer/Equalizer just to enumerate names touches the audio HAL --
        // nothing reads eqPresetNames until the user opens Settings > Equalizer (every call
        // site there already guards with "index < eqPresetNames.size()"), so do it off the UI
        // thread instead of paying that cost on every cold start.
        new Thread(() -> {
            final List<String> names = new ArrayList<>();
            try {
                MediaPlayer dummyMp = new MediaPlayer();
                Equalizer dummyEq = new Equalizer(0, dummyMp.getAudioSessionId());
                short presets = dummyEq.getNumberOfPresets();
                for (short i = 0; i < presets; i++) {
                    names.add(dummyEq.getPresetName(i));
                }
                dummyEq.release();
                dummyMp.release();
            } catch (Exception e) {
                names.add("Normal (Default)");
            }
            runOnUiThread(() -> {
                eqPresetNames.clear();
                eqPresetNames.addAll(names);
                if (currentEqPresetIndex >= eqPresetNames.size()) currentEqPresetIndex = 0;
            });
        }, "EqPresetLoad").start();

        if (!rootFolder.exists())
            rootFolder.mkdirs();

        libraryCacheDb = new com.themoon.y1.db.LibraryCacheDb(this);
        com.themoon.y1.managers.AudioPlayerManager.getInstance().restoreLastPlaybackState();

        // Show last-known library instantly from the cache (no disk walk) so Cover Flow /
        // Music browsing don't sit empty on cold start; a background scan right after
        // reconciles with the real filesystem and silently updates anything that changed.
        loadLibraryFromCacheInstant();
        boolean hadCachedLibrary = !customLibrary.isEmpty() || !audiobookLibrary.isEmpty();
        if (!isCustomScanning) {
            startMediaLibraryScan(hadCachedLibrary);
        }
        layoutMainMenu = findViewById(R.id.layout_main_menu);
        ivMainBg = findViewById(R.id.iv_main_bg);
        ivMenuPreview = findViewById(R.id.iv_menu_preview);
        tvMenuPreviewTitle = findViewById(R.id.tv_menu_preview_title);
        tvMenuPreviewArtist = findViewById(R.id.tv_menu_preview_artist);

        // 🚀 1. Load the saved widget checkbox states
        try {
            isWidgetClockOn = prefs.getBoolean("widget_clock", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        try {
            isWidgetBatteryOn = prefs.getBoolean("widget_battery", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        try {
            isWidgetAlbumOn = prefs.getBoolean("widget_album", false);
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        try {
            isWidgetFocusImageOn = prefs.getBoolean("widget_focus_image", false); // 🚀 [Added] Load state
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
// 🚀 Restores the existing setting values of the new switches from the storage vault.

        isLoopScrollOn = prefs.getBoolean("loop_scroll_on", true);
        updateMainMenuBackground(); // 💡 Automatically applies the background based on saved state when the app launches

        layoutBrowserMode = findViewById(R.id.layout_browser_mode);
        layoutPlayerMode = findViewById(R.id.layout_player_mode);
        containerBrowserItems = findViewById(R.id.container_browser_items);

        // 🚀 [Add here!] Find the existing ScrollView and overlay it with the official recycler engine that easily handles tens of thousands of tracks.
        scrollViewBrowser = (View) containerBrowserItems.getParent();

        listVirtualSongs = new android.widget.ListView(this);
        listVirtualSongs.setDivider(null); // Remove the ugly default divider
        listVirtualSongs.setSelector(new android.graphics.drawable.ColorDrawable(0)); // Remove the default touch effect
        listVirtualSongs.setItemsCanFocus(true);
        listVirtualSongs.setSoundEffectsEnabled(false);
        listVirtualSongs.setVisibility(View.GONE); // Hidden by default.

        android.view.ViewGroup browserParent = (android.view.ViewGroup) scrollViewBrowser.getParent();
        browserParent.addView(listVirtualSongs, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        layoutVolumeOverlay = findViewById(R.id.layout_volume_overlay);
        volumeProgress = findViewById(R.id.volume_progress);
        volumeProgress.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

        layoutSettingsMode = findViewById(R.id.layout_settings_mode);
        containerSettingsItems = findViewById(R.id.container_settings_items);
        layoutBluetoothMode = findViewById(R.id.layout_bluetooth_mode);
        containerBtItems = findViewById(R.id.container_bt_items);
        btnScanBt = findViewById(R.id.btn_scan_bt);
        layoutWifiMode = findViewById(R.id.layout_wifi_mode);
        containerWifiItems = findViewById(R.id.container_wifi_items);
        btnScanWifi = findViewById(R.id.btn_scan_wifi);
        // 💡 [Added] Color change when wheel focus lands on the scan button, and block duplicate sounds
        View.OnFocusChangeListener scanFocusListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                Button btn = (Button) v;
                if (hasFocus) {
                    btn.setBackgroundColor(0x88FFFFFF); // Semi-transparent white background when the wheel lands on it
                    btn.setTextColor(0xFF000000); // Invert the text to black!
                } else {
                    btn.setBackgroundColor(0x00000000); // Back to a transparent background when the wheel leaves
                    btn.setTextColor(0xFFFFFFFF); // Text back to white as before!
                }
            }
        };
        btnScanBt.setOnFocusChangeListener(scanFocusListener);
        btnScanWifi.setOnFocusChangeListener(scanFocusListener);
        btnScanBt.setSoundEffectsEnabled(false);
        btnScanWifi.setSoundEffectsEnabled(false);
        layoutWifiKeyboard = findViewById(R.id.layout_wifi_keyboard);
        tvKeyboardSsid = findViewById(R.id.tv_keyboard_ssid);
        tvKeyboardInput = findViewById(R.id.tv_keyboard_input);
        tvKeyPprev = findViewById(R.id.tv_key_pprev);
        tvKeyPrev = findViewById(R.id.tv_key_prev);
        tvKeyCurrent = findViewById(R.id.tv_key_current);
        tvKeyNext = findViewById(R.id.tv_key_next);
        tvKeyNnext = findViewById(R.id.tv_key_nnext);

        layoutBrightnessMode = findViewById(R.id.layout_brightness_mode);
        pbBrightness = findViewById(R.id.pb_brightness);
        tvBrightnessVal = findViewById(R.id.tv_brightness_val);

        layoutStorageMode = findViewById(R.id.layout_storage_mode);
        pbStorage = findViewById(R.id.pb_storage);
        tvStorageDetails = findViewById(R.id.tv_storage_details);

        layoutWebServerMode = findViewById(R.id.layout_webserver_mode);
        tvServerStatus = findViewById(R.id.tv_server_status);
        tvServerIp = findViewById(R.id.tv_server_ip);
        btnServerToggle = findViewById(R.id.btn_server_toggle);

        layoutNavidromeMode = findViewById(R.id.layout_navidrome_mode);
        containerNavidromeItems = findViewById(R.id.container_navidrome_items);
        tvNavidromePath = findViewById(R.id.tv_navidrome_path);
        tvNavidromeStatus = findViewById(R.id.tv_navidrome_status);

        com.themoon.y1.subsonic.SubsonicClient.getInstance().loadSettings(this);
        // Only bind the proxy's localhost socket and spin up its thread if Navidrome is actually
        // configured — most launches never touch it, so this used to be pure startup overhead.
        if (com.themoon.y1.subsonic.SubsonicClient.getInstance().isConfigured()) {
            com.themoon.y1.subsonic.NavidromeProxyServer.ensureStarted();
        }
        try {
            // 1. Settings (settings menu)
            ((TextView) ((android.view.ViewGroup) layoutSettingsMode).getChildAt(0)).setText(t("Settings"));
            // 2. Bluetooth
            ((TextView) ((android.view.ViewGroup) layoutBluetoothMode).getChildAt(0)).setText(t("Bluetooth"));
            // 3. Wi-Fi
            ((TextView) ((android.view.ViewGroup) layoutWifiMode).getChildAt(0)).setText(t("Wi-Fi"));
            // 4. Brightness (screen brightness)
            ((TextView) ((android.view.ViewGroup) layoutBrightnessMode).getChildAt(0)).setText(t("Display Brightness"));
            // 5. Storage
            ((TextView) ((android.view.ViewGroup) layoutStorageMode).getChildAt(0)).setText(t("Storage"));
            // 6. Web Server
            ((TextView) ((android.view.ViewGroup) layoutWebServerMode).getChildAt(0)).setText(t("Web Server"));
        } catch (Exception e) {
            // Guard so the app doesn't crash even if the layout structure differs
        }
        // 🚀 [Added from here] Adjust the Web Server screen's text height and spacing
        float dt = getResources().getDisplayMetrics().density;

        try {
            android.view.ViewGroup webLayout = (android.view.ViewGroup) layoutWebServerMode;
            // The very first element (index 0) in the layout is usually the title text.
            android.widget.TextView tvHeader = (android.widget.TextView) webLayout.getChildAt(0);

            // 💡 Feel free to change this to whatever title you want!

            // 💡 If you'd like, you can also change the top title's font size or color here.
            // tvHeader.setTextSize(26);
            // tvHeader.setTextColor(0xFF00FFFF);
            tvHeader.setTranslationY(20 * dt);
        } catch (Exception e) {
            // Guard against crashes if the layout structure differs
        }
        // 🚀 2. Swap every screen's semi-transparent overlay color at once via the theme manager!
        int overlayColor = ThemeManager.getOverlayBackgroundColor();
        layoutBrowserMode.setBackgroundColor(overlayColor);
        layoutSettingsMode.setBackgroundColor(overlayColor);
        layoutBluetoothMode.setBackgroundColor(overlayColor);
        layoutWifiMode.setBackgroundColor(overlayColor);
        layoutWifiKeyboard.setBackgroundColor(overlayColor);
        layoutBrightnessMode.setBackgroundColor(overlayColor);
        layoutStorageMode.setBackgroundColor(overlayColor);
        layoutWebServerMode.setBackgroundColor(overlayColor);

        // Also update major fixed text like browser text to match the theme

        // 💡 Even at rest, give it a faint glass texture to visually indicate where the button area is.
        btnServerToggle.setBackgroundColor(0x15FFFFFF);

        btnServerToggle.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // 🚀 When the wheel lands on it: invert to a clear milky background with bold black text!
                    btnServerToggle.setBackgroundColor(0xDDFFFFFF);
                    btnServerToggle.setTextColor(0xFF000000);
                    btnServerToggle.setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    // 🚀 When the wheel leaves: revert to a subtle translucent glass look with thin white text!
                    btnServerToggle.setBackgroundColor(0x15FFFFFF);
                    btnServerToggle.setTextColor(0xFFFFFFFF);
                    btnServerToggle.setTypeface(null, android.graphics.Typeface.NORMAL);
                }
            }
        });

        btnServerToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                toggleWebServer();
                updateWebServerUI();
            }
        });

        tvStatusClock = findViewById(R.id.tv_status_clock);
        tvStatusBattery = findViewById(R.id.tv_status_battery);
        tvStatusClock.setShadowLayer(0, 0, 0, 0);
        // 🚀 [Add here!] Hide the existing battery number (text) and insert a flat icon in its place.
        tvStatusBattery.setVisibility(View.GONE);
        batteryIconView = new BatteryIconView(this);
        android.view.ViewGroup statusParent = (android.view.ViewGroup) tvStatusBattery.getParent();
        int bIdx = statusParent.indexOfChild(tvStatusBattery);

        float density = getResources().getDisplayMetrics().density;
        // 🚀 [Size boost] Made much bigger and cleaner at 54dp wide by 24dp tall!
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                (int) (54 * density), (int) (24 * density));
        blp.gravity = android.view.Gravity.CENTER_VERTICAL;
        blp.setMargins((int) (15 * density), 0, (int) (6 * density), 0); // Slightly adjusted margins to match the size increase
        statusParent.addView(batteryIconView, bIdx, blp);
        ivStatusBluetooth = findViewById(R.id.iv_status_bluetooth);
        ivStatusWifi = findViewById(R.id.iv_status_wifi);
        ivStatusHeadphone = findViewById(R.id.iv_status_headphone);
// 🚀🚀🚀 [Fix complete] Join the play icon to the right-side system icon group instead of the clock side! 🚀🚀🚀
        ivStatusPlay = new ImageView(this);
        ivStatusPlay.setImageResource(android.R.drawable.ic_media_play);
        ivStatusPlay.setColorFilter(0xFFFFFFFF);
        ivStatusPlay.setVisibility(View.GONE);

        // 1. Pinpoint the 'LinearLayout' where the right-side Bluetooth/Wi-Fi icons live, not the clock's parent.
        android.view.ViewGroup rightStatusGroup = (android.view.ViewGroup) ivStatusBluetooth.getParent();
        float statusDensity = getResources().getDisplayMetrics().density;

        // 2. Match the icon size exactly to the other right-side icons at 22dp.
        android.widget.LinearLayout.LayoutParams playLp = new android.widget.LinearLayout.LayoutParams(
                (int) (22 * statusDensity), (int) (22 * statusDensity));
        playLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        playLp.setMargins(0, 0, (int) (8 * statusDensity), 0); // 8dp gap from the right-side icons

        // 3. Slot it in at the very front (index 0) of the right-side icon group!
        rightStatusGroup.addView(ivStatusPlay, 0, playLp);
        // 🚀🚀🚀 [End of addition] 🚀🚀🚀
        tvBrowserPath = findViewById(R.id.tv_browser_path);
        tvBrowserPath.setTextColor(ThemeManager.getTextColorPrimary()); // 🚀 Changed the fixed white to the theme color!

        btnNowPlaying = findViewById(R.id.btn_now_playing);
        btnPlay = findViewById(R.id.btn_play);
        btnSettings = findViewById(R.id.btn_settings);
        btnBluetooth = findViewById(R.id.btn_bluetooth);
        btnRadio = findViewById(R.id.btn_radio);
        ((android.view.View) btnRadio.getParent()).setVisibility(View.VISIBLE);
        Button btnNavidrome = findViewById(R.id.btn_navidrome);
        tvPlayerTitle = findViewById(R.id.tv_player_title);
        tvPlayerArtist = findViewById(R.id.tv_player_artist);
        tvPlayerTimeCurrent = findViewById(R.id.tv_player_time_current);
        tvPlayerTimeTotal = findViewById(R.id.tv_player_time_total);
        ivAlbumArt = findViewById(R.id.iv_album_art);
        ivPlayerBgBlur = findViewById(R.id.iv_player_bg_blur);
        ivPauseOverlay = findViewById(R.id.iv_pause_overlay);
        playerProgress = findViewById(R.id.player_progress); // 💖 Ensures the progress bar stays fully visible
        tvPlayerTrackCount = findViewById(R.id.tv_player_track_count);

        ivPlayerShuffleStatus = findViewById(R.id.iv_player_shuffle_status);
        ivPlayerRepeatStatus = findViewById(R.id.iv_player_repeat_status);

        // 🚀 Keeps the frame that combines with the stock visualizer
        android.widget.FrameLayout albumContainer = (android.widget.FrameLayout) ivAlbumArt.getParent();
        android.widget.LinearLayout playerInnerLayout = (android.widget.LinearLayout) albumContainer.getParent();

        visualizerView = new AudioVisualizerView(this);
        visualizerView.setVisibility(View.GONE);

        int height190 = (int) (190 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams visLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, height190);
        visLp.setMargins(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
        playerInnerLayout.addView(visualizerView, 0, visLp);
// 🚀 [Lyrics UI added] Overlay a transparent lyrics-only scroll view the same size as the spectrum.
        lyricScrollView = new android.widget.ScrollView(this);
        lyricScrollView.setVisibility(View.GONE);
        lyricScrollView.setScrollbarFadingEnabled(true);

        tvLyrics = new TextView(this);
        tvLyrics.setTextColor(0xFFFFFFFF);
        tvLyrics.setTextSize(16f);
        tvLyrics.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP);
        // ... (lyrics UI setup code above omitted) ...
        tvLyrics.setLineSpacing(10f, 1.2f);
        tvLyrics.setPadding(20, 40, 20, 40);
        tvLyrics.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);

        // 🚀 [Bug completely eradicated] Force the box to always stack downward starting from the TOP!
        lyricScrollView.addView(tvLyrics, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL
        ));
        playerInnerLayout.addView(lyricScrollView, 0, visLp);
        // 🚀 Obtain the parent RelativeLayout (parentRel)
        android.widget.LinearLayout statusIconsLayout = (android.widget.LinearLayout) ivPlayerShuffleStatus.getParent();
        android.widget.RelativeLayout parentRel = (android.widget.RelativeLayout) statusIconsLayout.getParent();

        // 🚀 [Core fix] Align the pills to the left wall of parentRel, which spans the whole player screen, instead of inside the album image!

        layoutAudioQualityContainer = new LinearLayout(this);
        layoutAudioQualityContainer.setOrientation(LinearLayout.VERTICAL);
        layoutAudioQualityContainer.setVisibility(View.GONE);

        int capsuleBgColor = 0x44000000; // Highly readable 40%-opacity black translucent box
        float capsuleRadius = 20 * density; // Corner radius

        // ① Extension capsule (Format)
        tvQualityExt = new TextView(this);
        tvQualityExt.setTextSize(13);
        tvQualityExt.setTextColor(0xbbFFFFFF);
        tvQualityExt.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        tvQualityExt.setIncludeFontPadding(false); // 💡 Center-align the text exactly
        tvQualityExt.setPadding((int)(16*density), (int)(8*density), (int)(16*density), (int)(8*density));
        tvQualityExt.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable bgExt = new android.graphics.drawable.GradientDrawable();
        bgExt.setColor(capsuleBgColor);
        bgExt.setCornerRadius(capsuleRadius);
        tvQualityExt.setBackground(bgExt);

        // ② Type capsule (Type)
        tvQualityFormat = new TextView(this);
        tvQualityFormat.setTextSize(13);
        tvQualityFormat.setTextColor(0xbbFFFFFF);
        tvQualityFormat.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        tvQualityFormat.setIncludeFontPadding(false);
        tvQualityFormat.setPadding((int)(16*density), (int)(8*density), (int)(16*density), (int)(8*density));
        tvQualityFormat.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable bgFormat = new android.graphics.drawable.GradientDrawable();
        bgFormat.setColor(capsuleBgColor);
        bgFormat.setCornerRadius(capsuleRadius);
        tvQualityFormat.setBackground(bgFormat);

        // ③ Bitrate/quality capsule (Quality)
        tvQualityBitrate = new TextView(this);
        tvQualityBitrate.setTextSize(13);
        tvQualityBitrate.setTextColor(0xbbFFFFFF);
        tvQualityBitrate.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        tvQualityBitrate.setIncludeFontPadding(false);
        tvQualityBitrate.setPadding((int)(16*density), (int)(8*density), (int)(16*density), (int)(8*density));
        tvQualityBitrate.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable bgBitrate = new android.graphics.drawable.GradientDrawable();
        bgBitrate.setColor(capsuleBgColor);
        bgBitrate.setCornerRadius(capsuleRadius);
        tvQualityBitrate.setBackground(bgBitrate);

        // Apply separate LayoutParams so each pill box aligns on its own independent line
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int)(6 * density);
        lp1.gravity = android.view.Gravity.LEFT;

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.bottomMargin = (int)(6 * density);
        lp2.gravity = android.view.Gravity.LEFT;

        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.gravity = android.view.Gravity.LEFT;

        layoutAudioQualityContainer.addView(tvQualityExt, lp1);
        layoutAudioQualityContainer.addView(tvQualityFormat, lp2);
        layoutAudioQualityContainer.addView(tvQualityBitrate, lp3);

        // 🚀 [Precise position fix] Changed placement to right below the top-left track indicator (01 / 100) instead of the screen's exact center!
        android.widget.RelativeLayout.LayoutParams containerLp = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        containerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT); // Align to the screen's left wall
        containerLp.addRule(android.widget.RelativeLayout.BELOW, R.id.tv_player_track_count); // 💡 Slot it in right below the track indicator (01/100)!

        // 💡 [Tip] Adjust the margin values below to line it up perfectly with the "01 / 100" text.
        containerLp.leftMargin = (int) (density); // Margin set to align with the start line of the top-left track indicator
        containerLp.topMargin = (int) (16 * density);  // Gives an elegant vertical gap between the "01 / 100" text and the first pill

        parentRel.addView(layoutAudioQualityContainer, containerLp);

        // 🚀 Keeps and restores the heart and top-right icon alignment set that was already correctly assembled
        android.widget.LinearLayout verticalWrapper = new android.widget.LinearLayout(this);
        verticalWrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        verticalWrapper.setGravity(android.view.Gravity.RIGHT);

        android.widget.RelativeLayout.LayoutParams params = (android.widget.RelativeLayout.LayoutParams) statusIconsLayout.getLayoutParams();
        parentRel.removeView(statusIconsLayout);

        statusIconsLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        verticalWrapper.addView(statusIconsLayout);

        tvPlayerFavoriteStatus = new TextView(this);
        tvPlayerFavoriteStatus.setText("♥");
        tvPlayerFavoriteStatus.setTextSize(20);
        tvPlayerFavoriteStatus.setVisibility(View.GONE);

        android.widget.LinearLayout.LayoutParams heartLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        heartLp.topMargin = (int) (8 * density);
        heartLp.rightMargin = (int) (2 * density);
        verticalWrapper.addView(tvPlayerFavoriteStatus, heartLp);

        parentRel.addView(verticalWrapper, params);

        try {
            // Fetches all favorite paths from the DB when the app launches.
            favoritePaths = new java.util.HashSet<>(libraryCacheDb.loadFavoritePaths());
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }
        // 🚀🚀🚀 [End of addition] 🚀🚀🚀

        updatePlayerStatusIndicators();


        // 🚀 [Fix] Pass the icon filename (.png) as a parameter.
        setupMenuButton(btnNowPlaying, R.drawable.music_circle, "icon_now_playing.png");
        setupMenuButton(btnPlay, R.drawable.music_list, "icon_music.png");
        setupMenuButton(btnBluetooth, R.drawable.bluetooth_circle, "icon_bluetooth.png");
        setupMenuButton(btnSettings, R.drawable.setting_circle, "icon_setting.png");
        setupMenuButton(btnRadio, R.drawable.radio_circle, "icon_radio.png");
        setupMenuButton(btnNavidrome, R.drawable.ic_wifi, "icon_navidrome.png");

        btnNavidrome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navidromeBrowseDepth = NAV_ARTISTS;
                selectedNavidromeArtist = null;
                com.themoon.y1.managers.NavidromeManager.getInstance().clearSelectedAlbum();
                isNavidromeLetterView = false;
                navidromeBackTarget = STATE_MENU;
                changeScreen(STATE_NAVIDROME);
            }
        });
        btnNowPlaying.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                boolean navidromeActive = am.isNavidromeMode && !am.navidromePlaylist.isEmpty();
                if (currentPlaylist.isEmpty() && !navidromeActive) {
                    Toast.makeText(MainActivity.this, t("No music is currently playing."), Toast.LENGTH_SHORT).show();
                } else {
                    changeScreen(STATE_PLAYER);
                    if (navidromeActive) {
                        progressHandler.removeCallbacks(updateProgressTask);
                        progressHandler.post(updateProgressTask);
                    }
                }
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentBrowserMode = BROWSER_ROOT; // 💡 Go to the top of the library when entering Music!

                // 🚀 In case the initial scan failed because the SD card was recognized late right after reboot,
                if (customLibrary.isEmpty() && !isCustomScanning) {
                    startMediaLibraryScan();
                }
                changeScreen(STATE_BROWSER);
                // Only show the blocking popup if there's genuinely nothing to browse yet —
                // a background reconciliation scan with cached data already showing shouldn't.
                if (isCustomScanning && customLibrary.isEmpty() && audiobookLibrary.isEmpty()) {
                    showLoadingPopup();
                }
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_SETTINGS);
            }
        });
        btnBluetooth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_BLUETOOTH);
            }
        });
        btnRadio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(
                            new Intent().setClassName("com.mediatek.FMRadio", "com.mediatek.FMRadio.FMRadioActivity"));
                } catch (Exception e) {
                    Intent b = getPackageManager().getLaunchIntentForPackage("com.mediatek.FMRadio");
                    if (b != null)
                        startActivity(b);
                }
            }
        });

        btnScanBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBluetoothScan();
            }
        });
        btnScanWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWifiScan();
            }
        });
        clockHandler.post(clockTask);

        IntentFilter filter = new IntentFilter();
        // 🚀 [Added 1] Maximize the antenna priority so we intercept before the system does!
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

        // 🚀 [Added 2] Detects the signal that the system is about to show its pairing popup!
        filter.addAction("android.bluetooth.device.action.PAIRING_REQUEST");
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(systemStatusReceiver, filter);

        try {
            if (audioManager.isWiredHeadsetOn()) {
                ivStatusHeadphone.setVisibility(View.VISIBLE);
                // 💡 (Bonus) For consistency, it also looks nice to change the sky-blue (0xFF00FFFF) shown with wired earphones to white!
                ivStatusHeadphone.setColorFilter(0xFFFFFFFF);
            }
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.isEnabled()) {
                ivStatusBluetooth.setVisibility(View.VISIBLE);
                updateBluetoothStatusIcon();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                ivStatusWifi.setVisibility(View.VISIBLE);
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1)
                    ivStatusWifi.setColorFilter(0xFF00FF00);
                else
                    ivStatusWifi.setColorFilter(0xFFFFBB00);
            }
        } catch (Exception e) {
            Log.d(TAG, "onCreate failed", e);
        }

        btnNowPlaying.requestFocus();

        // 🚀 1. Also swap the main screen's background and text color to match the theme manager!
        applyThemeToMainMenu();

        triggerAutoReconnect();

        // 🚀 2. When the theme changes and the screen refreshes (recreate), make it return to the 'theme selection list' instead of the main screen!
        boolean rebootToTheme = prefs.getBoolean("reboot_to_theme", false);
        if (rebootToTheme) {
            prefs.edit().remove("reboot_to_theme").apply(); // Clear it since we've used the memory.

            // 🚀 [Bug fix] Force the guard flag that was cleared by recreate() back to true!
            // This lock is required to thoroughly block the timer bomb of the main settings screen (buildSettingsUI) from being scheduled inside changeScreen.
            isNavigatingToSubMenu = true;
            changeScreen(STATE_SETTINGS);
            buildThemeSelectorUI(); // Properly display the long-awaited theme list screen!
            isNavigatingToSubMenu = false; // Clear the flag since processing is complete
        } else {
            btnNowPlaying.requestFocus(); // On a normal app launch, focus the main menu as usual
        }
    }
    // 1. File count counter (takes a folder path and counts)
    private void countAudioFiles(File folder) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) countAudioFiles(f);
                else if (isAudioFile(f)) totalAudioFiles++;
            }
        }
    }

    /** One audio file discovered during the (cheap, single-threaded) directory walk, waiting on
     *  its tag extraction — either already resolved from cache, or running on {@link #scanExecutor}. */
    private static final class ScanTask {
        final File file;
        final boolean isAudiobook;
        final com.themoon.y1.db.LibraryCacheDb.CachedSong cachedResult; // non-null = cache hit, no future
        final java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future; // non-null = cache miss

        ScanTask(File file, boolean isAudiobook, com.themoon.y1.db.LibraryCacheDb.CachedSong cachedResult,
                 java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future) {
            this.file = file;
            this.isAudiobook = isAudiobook;
            this.cachedResult = cachedResult;
            this.future = future;
        }
    }

    /** Reads tags for one file with MediaMetadataRetriever. Runs on {@link #scanExecutor} for
     *  cache misses so multiple files' (slow, I/O-bound) tag reads overlap instead of running
     *  one after another. */
    private com.themoon.y1.db.LibraryCacheDb.CachedSong extractTags(File f, long mtime, long size, boolean isAudiobook) {
        String path = f.getAbsolutePath();
        String title = f.getName();
        String artist = t("Unknown Artist");
        String album = t("Unknown Album");
        String year = t("Unknown Year");
        String genre = t("Unknown Genre");
        int trackNum = 0;

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            mmr.setDataSource(fis.getFD());

            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            // Prefer ALBUMARTIST for grouping — track-artist tags like
            // "Coldplay • Avicii" scatter one album across the Artists list
            String aa = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String trackStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            String y = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE); // 💡 KEY_DATE holds the year.
            String g = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);

            if (t != null && !t.trim().isEmpty()) title = t;
            if (aa != null && !aa.trim().isEmpty()) artist = aa;
            else if (a != null && !a.trim().isEmpty()) artist = a;
            if (al != null && !al.trim().isEmpty()) album = al;
            if (y != null && !y.trim().isEmpty()) year = y;
            if (g != null && !g.trim().isEmpty()) genre = g;

            if (trackStr != null && !trackStr.isEmpty()) {
                try {
                    if (trackStr.contains("/")) trackNum = Integer.parseInt(trackStr.split("/")[0].trim());
                    else trackNum = Integer.parseInt(trackStr.trim());
                } catch (Exception e) {
                    Log.d(TAG, "extractTags failed", e);
                }
            }
        } catch (Exception e) {
            // 💡 Even if there's no tag or the scanner fails, the app doesn't crash and safely exits into this block.
        } finally {
            if (fis != null) try { fis.close(); } catch (Exception e) { Log.d(TAG, "extractTags failed", e); }
            try { mmr.release(); } catch (Exception e) { Log.d(TAG, "extractTags failed", e); }
        }
        return new com.themoon.y1.db.LibraryCacheDb.CachedSong(
                path, mtime, size, title, artist, album, year, genre, trackNum, isAudiobook);
    }

    // 2. Walk the folder tree and queue each audio file's tag lookup. cachedSongs holds tag
    // results from the last scan (keyed by absolute path) so files whose mtime/size haven't
    // changed skip MediaMetadataRetriever entirely — those resolve immediately with no thread
    // hand-off. Cache misses run on scanExecutor so several (slow, I/O-bound) tag reads overlap.
    private void buildCustomLibrary(File folder, List<ScanTask> pendingTasks,
                                     Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs,
                                     boolean isAudiobook,
                                     java.util.concurrent.ExecutorService scanExecutor) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (final File f : files) {
                if (f.isDirectory()) {
                    buildCustomLibrary(f, pendingTasks, cachedSongs, isAudiobook, scanExecutor);
                } else if (isAudioFile(f)) {
                    if (blacklist.contains(f.getAbsolutePath())) continue;

                    final long mtime = f.lastModified();
                    final long size = f.length();
                    com.themoon.y1.db.LibraryCacheDb.CachedSong cached = cachedSongs.get(f.getAbsolutePath());

                    if (cached != null && cached.mtime == mtime && cached.size == size) {
                        // Quick scan: file is unchanged, reuse the cached tags — no thread needed
                        pendingTasks.add(new ScanTask(f, isAudiobook, cached, null));
                    } else {
                        java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future =
                                scanExecutor.submit(new java.util.concurrent.Callable<com.themoon.y1.db.LibraryCacheDb.CachedSong>() {
                                    @Override
                                    public com.themoon.y1.db.LibraryCacheDb.CachedSong call() {
                                        return extractTags(f, mtime, size, isAudiobook);
                                    }
                                });
                        pendingTasks.add(new ScanTask(f, isAudiobook, null, future));
                    }
                }
            }
        }
    }

    /** Resolves every queued {@link ScanTask} (blocking on any still-running extraction, though
     *  most finish while later folders are still being walked) and files the result into the
     *  right library list, updating scan progress as it goes. */
    private void finalizeScanTasks(List<ScanTask> pendingTasks, List<SongItem> newCustomLibrary,
                                    List<SongItem> newAudiobookLibrary,
                                    java.util.HashMap<String, Integer> newTrackNumberMap,
                                    List<com.themoon.y1.db.LibraryCacheDb.CachedSong> freshEntries) {
        for (ScanTask task : pendingTasks) {
            com.themoon.y1.db.LibraryCacheDb.CachedSong result = task.cachedResult;
            if (result == null) {
                try {
                    result = task.future.get();
                } catch (Exception e) {
                    continue; // extraction thread crashed — skip rather than corrupt the library
                }
            }

            SongItem item = new SongItem(task.file, result.title, result.artist, result.album, result.year, result.genre);
            if (task.isAudiobook) newAudiobookLibrary.add(item);
            else newCustomLibrary.add(item);
            newTrackNumberMap.put(result.path, result.trackNumber);
            freshEntries.add(result);

            scannedAudioFiles++;
            if (totalAudioFiles > 0) {
                final int progress = (int) (((float) scannedAudioFiles / totalAudioFiles) * 100);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pbLoadingProgress != null) pbLoadingProgress.setProgress(progress);
                        if (tvLoadingProgress != null) {
                            tvLoadingProgress.setText("Scanning Media: " + progress + "%\n(" + scannedAudioFiles + " / " + totalAudioFiles + ")\nDo not turn off the screen.");
                        }
                    }
                });
            }
        }
    }
    // 3. Central scan engine (scans the two folders in order)
    /**
     * Populates customLibrary/audiobookLibrary/trackNumberMap directly from the SQLite
     * cache with no disk I/O — lets the UI (Cover Flow, Music browser) show last-known
     * data instantly on cold start instead of sitting empty until a full scan finishes.
     * A background scan should still run afterward to reconcile with the real filesystem.
     */
    private void loadLibraryFromCacheInstant() {
        Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs = libraryCacheDb.loadAll();
        if (cachedSongs.isEmpty()) return;

        customLibrary.clear();
        audiobookLibrary.clear();
        trackNumberMap.clear();
        for (com.themoon.y1.db.LibraryCacheDb.CachedSong cached : cachedSongs.values()) {
            SongItem item = new SongItem(new File(cached.path), cached.title, cached.artist,
                    cached.album, cached.year, cached.genre);
            if (cached.isAudiobook) audiobookLibrary.add(item);
            else customLibrary.add(item);
            trackNumberMap.put(cached.path, cached.trackNumber);
        }
    }

    public void startMediaLibraryScan() { startMediaLibraryScan(false); }

    /** @param silent Skip the blocking "Scanning..." popup — used when the cache already
     *  populated the library instantly and this run is just reconciling with disk in the background. */
    public void startMediaLibraryScan(final boolean silent) {
        if (isCustomScanning) return;
        isCustomScanning = true;

        if (!silent) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pbLoadingProgress != null) pbLoadingProgress.setProgress(0);
                    if (tvLoadingProgress != null) tvLoadingProgress.setText(t("Counting files...\nPlease wait."));
                    showLoadingPopup();
                }
            });
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SongItem> newCustomLibrary = new ArrayList<>();
                List<SongItem> newAudiobookLibrary = new ArrayList<>();
                java.util.HashMap<String, Integer> newTrackNumberMap = new java.util.HashMap<>();
                totalAudioFiles = 0;
                scannedAudioFiles = 0;

                // 🚀 Count files in both folders
                countAudioFiles(rootFolder);
                countAudioFiles(audiobookRootFolder);

                // Quick scan: load the previous scan's results so unchanged files skip tag re-reading
                Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs = libraryCacheDb.loadAll();
                List<com.themoon.y1.db.LibraryCacheDb.CachedSong> freshEntries = new ArrayList<>();

                // 🚀 Walk both folders (fast) queuing tag extraction for cache misses onto a small
                // thread pool so slow MediaMetadataRetriever reads overlap instead of serializing.
                List<ScanTask> pendingTasks = new ArrayList<>();
                int scanThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.ExecutorService scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(scanThreads);
                try {
                    buildCustomLibrary(rootFolder, pendingTasks, cachedSongs, false, scanExecutor);
                    buildCustomLibrary(audiobookRootFolder, pendingTasks, cachedSongs, true, scanExecutor);
                } finally {
                    scanExecutor.shutdown();
                }
                finalizeScanTasks(pendingTasks, newCustomLibrary, newAudiobookLibrary, newTrackNumberMap, freshEntries);

                libraryCacheDb.replaceAll(freshEntries);

                // Run the favorites auto-cleaner (based on Music)
                java.util.HashSet<String> aliveSongs = new java.util.HashSet<>();
                for (SongItem song : newCustomLibrary) aliveSongs.add(song.file.getAbsolutePath());
                for (SongItem book : newAudiobookLibrary) aliveSongs.add(book.file.getAbsolutePath()); // 💡 Include audiobooks too!

                java.util.Iterator<String> favIterator = favoritePaths.iterator();
                while (favIterator.hasNext()) {
                    String favPath = favIterator.next();
                    if (!aliveSongs.contains(favPath)) {
                        favIterator.remove();
                        libraryCacheDb.setFavorite(favPath, false);
                    }
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isCustomScanning = false;
                        // Swap the freshly reconciled results in now that scanning is done — avoids
                        // showing a half-populated library to anything reading these lists mid-scan.
                        customLibrary.clear();
                        customLibrary.addAll(newCustomLibrary);
                        audiobookLibrary.clear();
                        audiobookLibrary.addAll(newAudiobookLibrary);
                        trackNumberMap.clear();
                        trackNumberMap.putAll(newTrackNumberMap);

                        if (!silent) {
                            Toast.makeText(MainActivity.this, t("Scan Complete! Music")+": " + customLibrary.size()+" " + t("Books: ") + audiobookLibrary.size(), Toast.LENGTH_SHORT).show();
                        }

                        if (currentScreenState == STATE_BROWSER) {
                            if (currentBrowserMode == BROWSER_ROOT) buildFileBrowserUI();
                            else if (currentBrowserMode == BROWSER_ARTISTS) buildVirtualCategories("ARTIST");
                            else if (currentBrowserMode == BROWSER_ALBUMS) buildVirtualCategories("ALBUM");
                            else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) buildVirtualSongs();
                            else if (currentBrowserMode == BROWSER_COVER_FLOW) buildCoverFlowUI();
                        }
                    }
                });
            }
        }).start();
    }
    // 💡 [Overhaul complete] Reliable full-screen loading popup & screen-off prevention engine
    // 💡 [Overhaul complete] Reliable full-screen loading popup & screen-off prevention engine
    public void showLoadingPopup() {
        if (layoutLoadingOverlay != null) {
            // 🚀 [Fix 3] Also ensures the popup's opacity is fully set to 100% when showing the auto-scan screen!
            layoutLoadingOverlay.setAlpha(1.0f);
            layoutLoadingOverlay.setVisibility(View.VISIBLE);

            // 🚀 [Fix complete] Force-reset the shared canvas (tvLoadingProgress) text size back to its default (18f)!
            // This way, text that grew to 30f during frequency adjustment is properly restored back to a modest 18f during other scan operations.
            if (tvLoadingProgress != null) {
                tvLoadingProgress.setTextSize(18f);
            }

            // 🚀 [Core technique] Forces the system to never turn off the screen while scanning!
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            final Handler checker = new Handler();
            checker.post(new Runnable() {
                @Override
                public void run() {
                    // Guard against this self-rescheduling loop outliving the Activity (e.g. a
                    // scan stuck in isCustomScanning/isRadioScanning while the Activity is destroyed).
                    if (isFinishing() || isDestroyed()) return;
                    // 🚀 [Bug fix complete!] Don't close the window if either the music scan or radio scan is still running!
                    if (!isCustomScanning && !isRadioScanning) {
                        layoutLoadingOverlay.setVisibility(View.GONE);
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        checker.postDelayed(this, 200); // Check every 0.2 seconds
                    }
                }
            });
        }
    }
    // 💡 [Overhaul complete] Engine that shows download progress (%) and size (MB) in a live popup!
    // Overwrite the entire refreshWidgets() function located just above this comment!

    public void refreshWidgets() {
        // 1. Update the digital clock widget
        if (tvWidgetClock != null) {
            clockReusableDate.setTime(System.currentTimeMillis());
            com.themoon.y1.managers.WidgetClockManager.getInstance().update(
                    tvWidgetClock, widgetViewRegistry.get(tvWidgetClock), isWidgetClockOn,
                    is24HourFormat, currentClockSize, clockReusableDate,
                    getResources().getDisplayMetrics().density);
        }

        // 2. Update the bar-style battery widget
        if (widgetBatteryView != null) {
            ThemeManager.MenuElement el = widgetViewRegistry.get(widgetBatteryView);
            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                widgetBatteryView.setVisibility(isWidgetBatteryOn ? View.VISIBLE : View.GONE);
            }
            if (widgetBatteryView.getVisibility() == View.VISIBLE) {
                widgetBatteryView.setColor(ThemeManager.getTextColorPrimary());
                if (lastKnownBatteryPct != -1) widgetBatteryView.setBatteryLevel(lastKnownBatteryPct, lastKnownBatteryCharging);
            }
        }

        // 3. Update the album preview widget
        if (ivWidgetAlbum != null && tvWidgetAlbumTitle != null && tvWidgetAlbumArtist != null) {
            View parent = (View) ivWidgetAlbum.getParent();
            ThemeManager.MenuElement el = widgetViewRegistry.get(parent);

            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                if (parent != null) parent.setVisibility(isWidgetAlbumOn ? View.VISIBLE : View.GONE);
            }

            // 🚀 [Fix] Even if the settings switch (isWidgetAlbumOn) is off, always feed data as long as it's currently visible on screen!
            if (parent != null && parent.getVisibility() == View.VISIBLE) {
                tvWidgetAlbumTitle.setText(tvPlayerTitle != null ? tvPlayerTitle.getText() : "");
                tvWidgetAlbumArtist.setText(tvPlayerArtist != null ? tvPlayerArtist.getText() : "");

                if (lastAlbumArtBytes != null) {
                    try {
                        // Same bytes tick after tick while a track plays -- decode once per track
                        // instead of every second.
                        if (widgetAlbumArtCachedSource != lastAlbumArtBytes) {
                            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            widgetAlbumArtCachedBitmap = android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
                            widgetAlbumArtCachedSource = lastAlbumArtBytes;
                        }
                        ivWidgetAlbum.setImageBitmap(widgetAlbumArtCachedBitmap);
                        widgetAlbumShowingDefault = false;
                    } catch (Exception e) {
                        Log.d(TAG, "refreshWidgets failed", e);
                    }
                } else {
                    // Decoding the default album icon from assets every second is needless disk I/O.
                    // Cache it and only assign it once when we actually transition to "no art".
                    if (!widgetAlbumShowingDefault) {
                        if (widgetDefaultAlbumBitmap == null) {
                            widgetDefaultAlbumBitmap = ThemeManager.getCustomIcon("icon_default_album.png", this, R.drawable.default_album);
                        }
                        ivWidgetAlbum.setImageBitmap(widgetDefaultAlbumBitmap);
                        widgetAlbumShowingDefault = true;
                    }
                }
            }
        }

        // 4. Update the analog clock
        if (customAnalogClockView != null) {
            ThemeManager.MenuElement el = widgetViewRegistry.get(customAnalogClockView);
            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                View parent = (View) customAnalogClockView.getParent();
                if (parent != null && "analog_wrapper".equals(parent.getTag())) parent.setVisibility(isWidgetAnalogClockOn ? View.VISIBLE : View.GONE);
                else customAnalogClockView.setVisibility(isWidgetAnalogClockOn ? View.VISIBLE : View.GONE);
            }
        }

        // 5. Update the circular battery widget
        if (customCircularBatteryView != null) {
            ThemeManager.MenuElement el = widgetViewRegistry.get(customCircularBatteryView);
            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                View parent = (View) customCircularBatteryView.getParent();
                if (parent != null && "circular_wrapper".equals(parent.getTag())) parent.setVisibility(isWidgetCircularBatteryOn ? View.VISIBLE : View.GONE);
                else customCircularBatteryView.setVisibility(isWidgetCircularBatteryOn ? View.VISIBLE : View.GONE);
            }

            if (customCircularBatteryView.getVisibility() == View.VISIBLE) {
                if (lastKnownBatteryPct != -1) customCircularBatteryView.setBatteryLevel(lastKnownBatteryPct, lastKnownBatteryCharging);
            }
        }

        // 6. Update the dynamic focus-image widget
        if (ivWidgetFocusImage != null) {
            ThemeManager.MenuElement el = widgetViewRegistry.get(ivWidgetFocusImage);
            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                ivWidgetFocusImage.setVisibility(isWidgetFocusImageOn ? View.VISIBLE : View.GONE);
            }
        }
    }
    // 💡 [Fix complete] Main-screen theme applier. Added a call to the dynamic rendering engine!
    public void applyThemeToMainMenu() {
        // The default album placeholder is theme-dependent; drop the cache so the next
        // refreshWidgets() re-fetches it for the newly applied theme.
        widgetDefaultAlbumBitmap = null;
        widgetAlbumShowingDefault = false;
        try {
            if (ivMainBg != null) {
                int themeColor = ThemeManager.getOverlayBackgroundColor();
                int softTint = (themeColor & 0x00FFFFFF) | 0x66000000;
                ivMainBg.setColorFilter(softTint, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }

            View statusBar = findViewById(R.id.layout_status_bar);
            if (statusBar != null) {
                statusBar.setBackgroundColor(ThemeManager.getStatusBarBackgroundColor());
            }

            int primary = ThemeManager.getTextColorPrimary();
            int secondary = ThemeManager.getTextColorSecondary();

            // Overwrite the text color of the track title/artist in the right blank space, and the top status bar (clock, battery)
            if (tvMenuPreviewTitle != null) tvMenuPreviewTitle.setTextColor(primary);
            if (tvMenuPreviewArtist != null) tvMenuPreviewArtist.setTextColor(secondary);
            if (tvStatusClock != null) tvStatusClock.setTextColor(primary);
            if (tvStatusBattery != null) tvStatusBattery.setTextColor(primary);
            if (batteryIconView != null) batteryIconView.setColor(primary);

            int themeFocusColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            if (playerProgress != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) playerProgress.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) { playerProgress.getProgressDrawable().setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN); }
            }
            if (volumeProgress != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) volumeProgress.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) { volumeProgress.getProgressDrawable().setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN); }
            }
            if (pbBrightness != null) {
                try {
                    android.graphics.drawable.LayerDrawable layer = (android.graphics.drawable.LayerDrawable) pbBrightness.getProgressDrawable();
                    android.graphics.drawable.Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Exception e) { pbBrightness.getProgressDrawable().setColorFilter(themeFocusColor, android.graphics.PorterDuff.Mode.SRC_IN); }
            }

            android.view.ViewGroup root = findViewById(android.R.id.content);
            applyFontToAllViews(root, ThemeManager.getCustomFont());

            // 🚀🚀🚀 [This is the key part!] Wipe out the old legacy menu entirely and assemble it from JSON parts!
            buildDynamicMainMenuUI();

        } catch (Exception e) {
            Log.d(TAG, "applyThemeToMainMenu failed", e);
        }
    }
    // 💡 [Added] A dedicated screen that shows the full theme list and lets the user pick one
    public void buildThemeSelectorUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildThemeSelectorUI(this);
    }

    private void triggerAutoReconnect() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                wm.reconnect();
            }
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.isEnabled()) {
                java.util.Set<BluetoothDevice> pairedDevices = ba.getBondedDevices();
            }
        } catch (Exception e) {
            Log.d(TAG, "triggerAutoReconnect failed", e);
        }
    }

    public void toggleWebServer() {
        if (isServerRunning) {
            if (webServer != null)
                webServer.stopServer();
            isServerRunning = false;
        } else {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                Toast.makeText(this, t("Please turn ON Wi-Fi first"), Toast.LENGTH_SHORT).show();
                return;
            }
            webServer = new Y1WebServer(getApplicationContext());
            webServer.start();
            isServerRunning = true;
        }
    }

    private void updateWebServerUI() {
        if (isServerRunning) {
            // 💡 Apple style: drop the emoji, use a clean white!
            tvServerStatus.setText(t("SERVER RUNNING"));
            tvServerStatus.setTextColor(0xFFFFFFFF);
            tvServerIp.setText("http://" + webServer.getLocalIpAddress() + ":8080");
            tvServerIp.setTextColor(0xFFFFFFFF);
            btnServerToggle.setText(t("STOP SERVER"));
        } else {
            // 💡 Apple style: a subtle, understated gray!
            tvServerStatus.setText(t("SERVER STOPPED"));
            tvServerStatus.setTextColor(0xFF888888);
            tvServerIp.setText("http://---.---.---.---:8080");
            tvServerIp.setTextColor(0xFF888888);
            btnServerToggle.setText(t("START SERVER"));
        }
    }

    // Loads the active theme's own background image, if it ships one: first the "bg_image" declared in
    // config.json, then a background.png / bg.png dropped into the theme folder (resolved through
    // ThemeManager so both SD-card themes and the built-in default theme are handled). Returns null when
    // the theme provides no background image.
    private Bitmap loadThemeBackgroundBitmap() {
        try {
            ThemeManager.ThemeData currentTheme = ThemeManager.getCurrentTheme();
            if (currentTheme.bgImage != null && !currentTheme.bgImage.isEmpty()) {
                File bgFile = new File(currentTheme.folderPath, currentTheme.bgImage);
                if (bgFile.exists()) {
                    try { return BitmapFactory.decodeFile(bgFile.getAbsolutePath()); } catch (Exception e) { Log.d(TAG, "loadThemeBackgroundBitmap failed", e); }
                }
            }
            Bitmap bg = ThemeManager.getCustomIcon("background.png", this, 0);
            if (bg == null) bg = ThemeManager.getCustomIcon("bg.png", this, 0);
            return bg;
        } catch (Exception e) {
            return null;
        }
    }

    // Returns true if the active theme ships a usable background image file. Used by the "Apply Theme
    // Background" setting to decide whether to engage theme-default mode or fall back to album blur.
    public boolean currentThemeHasBackground() {
        try {
            ThemeManager.ThemeData currentTheme = ThemeManager.getCurrentTheme();
            if (currentTheme.bgImage != null && !currentTheme.bgImage.isEmpty()
                    && new File(currentTheme.folderPath, currentTheme.bgImage).exists()) return true;
            if (new File(currentTheme.folderPath, "background.png").exists()) return true;
            if (new File(currentTheme.folderPath, "bg.png").exists()) return true;
        } catch (Exception e) {
            Log.d(TAG, "currentThemeHasBackground failed", e);
        }
        return false;
    }

    // 💡 Auto-update the main screen background (high-quality Gaussian blur applied)
    // 💡 [Fix] Main screen background: theme-default > custom image > album-art blur
    public void updateMainMenuBackground() {
        try {
            String savedBgPath = prefs.getString("bg_path", null);

            // 🚀 1. Theme-default background mode: the user explicitly chose "Apply Theme Background".
            //    Show the active theme's own background image crisp (no blur). If the theme ships no
            //    image, quietly fall through to the album-art blur mode below.
            if ("THEME_DEFAULT".equals(savedBgPath)) {
                Bitmap themeBg = loadThemeBackgroundBitmap();
                if (themeBg != null) {
                    ivMainBg.setImageBitmap(themeBg);
                    return;
                }
                savedBgPath = null; // theme has no background — behave as if nothing is set (album blur)
            }

            // 🚀 2. Otherwise, check whether the user has set a custom background image!
            if (savedBgPath != null && !savedBgPath.isEmpty()) {
                File bgFile = new File(savedBgPath);
                if (bgFile.exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(savedBgPath, opts);

                    // Since we won't apply blur, keep the resolution generous (800) so quality isn't degraded.
                    int scale = 1;
                    int maxDim = Math.max(opts.outWidth, opts.outHeight);
                    while (maxDim / scale > 800) {
                        scale *= 2;
                    }

                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = scale;
                    Bitmap customBg = BitmapFactory.decodeFile(savedBgPath, opts);

                    // 🚀 Custom backgrounds are shown crisp and unblurred, exactly as the original!
                    ivMainBg.setImageBitmap(customBg);

                    // 🚀 Force-return here so the currently playing album art can never overwrite the background!
                    return;
                }
            }

            // 🚀 3. No theme/custom background — apply 'blur' to the album art or default image and render it as before.
            Bitmap sourceBitmap = null;
            if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                sourceBitmap = BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                sourceBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.default_back, opts);
            }

            if (sourceBitmap != null) {
                applyGaussianBlurAsync(sourceBitmap, (blurredBitmap, src) -> {
                    ivMainBg.setImageBitmap(blurredBitmap);
                    if (src != blurredBitmap) {
                        src.recycle();
                    }
                });
            } else {
                ivMainBg.setImageResource(R.drawable.default_back);
            }
        } catch (Throwable t) {
            ivMainBg.setImageResource(R.drawable.default_back);
        }
    }

    // 💡 [Added] Tool that mixes theme color and 'corner radius' to stamp out a button's background design
    public android.graphics.drawable.GradientDrawable createButtonBackground(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color); // Inject the theme color
        // Inject the corner radius set by the theme (converted from dp to pixels)
        float radius = ThemeManager.getButtonRadius() * getResources().getDisplayMetrics().density;
        shape.setCornerRadius(radius);
        return shape;
    }

    // 💡 [Fix] Connects the color shown when the wheel lands on the main screen's buttons to the theme engine!
    private void setupMenuButton(final Button btn, final int imageResId, final String iconFileName) {
        btn.setSoundEffectsEnabled(false);
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg())); // 🚀 Apply corner radius
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    // 🚀 [Fix] Checks the playback state to properly distinguish the initial icon (circular) from the empty-album icon (square)!
                    boolean anyWidgetActive = isWidgetClockOn || isWidgetBatteryOn || isWidgetAlbumOn;
                    if (!anyWidgetActive) {
                        if (btn.getId() == R.id.btn_now_playing) {

                            // 1. In the "initial state" where no song has ever been played -> keep the round music-note icon (music_circle)
                            if (currentPlaylist.isEmpty()
                                    && !com.themoon.y1.managers.AudioPlayerManager.getInstance().isNavidromeMode) {
                                ivMenuPreview.setImageBitmap(
                                        ThemeManager.getCustomIcon(iconFileName, MainActivity.this, imageResId));
                                ivMenuPreview.setImageResource(imageResId);
                                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                                    tvMenuPreviewTitle.setVisibility(View.GONE);
                                    tvMenuPreviewArtist.setVisibility(View.GONE);
                                }
                            }
                            // 2. While a song is playing
                            else {
                                if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
                                    try {
                                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                                        opts.inSampleSize = 2;
                                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                                                .decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts);
                                        ivMenuPreview.setImageBitmap(bmp);
                                    } catch (Exception e) {
                                        ivMenuPreview.setImageBitmap(ThemeManager.getCustomIcon(
                                                "icon_default_album.png", MainActivity.this, R.drawable.default_album));
                                        ivMenuPreview.setImageResource(R.drawable.default_album); // Square album placeholder on error
                                    }
                                } else {
                                    ivMenuPreview.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png",
                                            MainActivity.this, R.drawable.default_album));
                                    ivMenuPreview.setImageResource(R.drawable.default_album); // Square album placeholder if there is no image
                                }

                                // Always show the info text while playing.
                                if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                                    tvMenuPreviewTitle.setVisibility(View.VISIBLE);
                                    tvMenuPreviewArtist.setVisibility(View.VISIBLE);
                                    tvMenuPreviewTitle.setText(tvPlayerTitle.getText());
                                    tvMenuPreviewArtist.setText(tvPlayerArtist.getText());
                                }
                            }

                        } else {
                            // When landing on other menus (Settings, Bluetooth, etc.), just show the original icon and hide the text.
                            ivMenuPreview.setImageBitmap(
                                    ThemeManager.getCustomIcon(iconFileName, MainActivity.this, imageResId));
                            ivMenuPreview.setImageResource(imageResId);
                            if (tvMenuPreviewTitle != null && tvMenuPreviewArtist != null) {
                                tvMenuPreviewTitle.setVisibility(View.GONE);
                                tvMenuPreviewArtist.setVisibility(View.GONE);
                            }
                        }
                    }

                } else {
                    btn.setBackgroundColor(0x00000000);
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                }
            }
        });
    }

    public void changeScreen(int state) {
// 🚀 [Path-tracking engine] Right before the screen changes, precisely record where we started in the rearview mirror!
        if (state == STATE_PLAYER) {
            if (currentScreenState == STATE_MENU || currentScreenState == STATE_BROWSER || currentScreenState == STATE_SETTINGS) {
                backTargetForPlayer = currentScreenState;
            }

            // 🚀 [New] Auto-play music immediately when entering the player screen after selecting a track from the cover-flow list or a folder!
            if (currentScreenState == STATE_BROWSER) {
                com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                if (!am.isPlaying()) {
                    am.playOrPauseMusic(); // 💡 Fire a play signal immediately if it's currently stopped!
                }
            }
        } else if (state == STATE_BLUETOOTH || state == STATE_WIFI || state == STATE_BRIGHTNESS || state == STATE_STORAGE || state == STATE_WEBSERVER) {
            if (currentScreenState == STATE_MENU || currentScreenState == STATE_BROWSER || currentScreenState == STATE_SETTINGS
                    || currentScreenState == STATE_PLAYER) {
                backTargetForUtility = currentScreenState;
            }
        }
        int safeFocusIndex = lastSettingsFocusIndex;
        currentScreenState = state;
        layoutMainMenu.setVisibility(state == STATE_MENU ? View.VISIBLE : View.GONE);
        layoutBrowserMode.setVisibility(state == STATE_BROWSER ? View.VISIBLE : View.GONE);
        layoutPlayerMode.setVisibility(state == STATE_PLAYER ? View.VISIBLE : View.GONE);
        layoutSettingsMode.setVisibility(state == STATE_SETTINGS ? View.VISIBLE : View.GONE);
        layoutBluetoothMode.setVisibility(state == STATE_BLUETOOTH ? View.VISIBLE : View.GONE);
        layoutWifiMode.setVisibility(state == STATE_WIFI ? View.VISIBLE : View.GONE);
        layoutWifiKeyboard.setVisibility(state == STATE_WIFI_KEYBOARD ? View.VISIBLE : View.GONE);
        layoutBrightnessMode.setVisibility(state == STATE_BRIGHTNESS ? View.VISIBLE : View.GONE);
        layoutStorageMode.setVisibility(state == STATE_STORAGE ? View.VISIBLE : View.GONE);
        layoutWebServerMode.setVisibility(state == STATE_WEBSERVER ? View.VISIBLE : View.GONE);
        layoutNavidromeMode.setVisibility(state == STATE_NAVIDROME ? View.VISIBLE : View.GONE);
        layoutVolumeOverlay.setVisibility(View.GONE);
        View statusBar = findViewById(R.id.layout_status_bar);
        if (statusBar != null) {
            if (state == STATE_PLAYER) {
                // On the player screen (music playback screen), always make it fully transparent!
                statusBar.setBackgroundColor(0x00000000);
            } else {
                // On any other screen (menu, settings, file browser, etc.), restore the status bar color specified by the theme!
                statusBar.setBackgroundColor(ThemeManager.getStatusBarBackgroundColor());
            }
        }
        if (state == STATE_MENU) {
            isPickingBackground = false;

            // Try to restore focus to whichever main-menu button we left from. This must run
            // unconditionally (not gated on current focus state): Android auto-transfers focus
            // to the first focusable view the instant the previous screen's container goes GONE
            // (a few lines above), so by now getCurrentFocus() already looks "valid" even though
            // it's just the default first button, not a real user focus.
            View lastFocused = !lastMainMenuFocusAction.isEmpty()
                    ? layoutMainMenu.findViewWithTag(lastMainMenuFocusAction) : null;
            lastMainMenuFocusAction = ""; // one-shot, so reset right after use
            if (lastFocused != null) {
                lastFocused.requestFocus();
            } else {
                View c = getCurrentFocus();
                // 🚀 [Focus-vanish fix 1] Instead of the hidden legacy button (btnNowPlaying), find the real dynamically created button 0 (ID: 10000) and focus it!
                if (c == null || c.getVisibility() != View.VISIBLE) {
                    View dynamicFirstBtn = findViewById(10000); // Fetch dynamic button 0
                    if (dynamicFirstBtn != null) {
                        dynamicFirstBtn.requestFocus();
                    } else if (btnNowPlaying != null) {
                        btnNowPlaying.requestFocus(); // Safety net in case it hasn't been assembled yet
                    }
                }
            }
            refreshNowPlayingPreview();
        } else if (state == STATE_BROWSER) {
            if (currentBrowserMode == BROWSER_ROOT || currentBrowserMode == BROWSER_FOLDER) {
                buildFileBrowserUI();
            } else if (currentBrowserMode == BROWSER_COVER_FLOW) {
                buildCoverFlowUI(); // 🚀 Added
            } else if (currentBrowserMode == BROWSER_ARTISTS) {
                buildVirtualCategories("ARTIST");
            } else if (currentBrowserMode == BROWSER_PLAYLISTS) {
                buildM3uPlaylistUI();
            } else if (currentBrowserMode == BROWSER_M3U_SONGS) {
                buildM3uSongsUI(currentM3uFile);
            } else if (currentBrowserMode == BROWSER_ALBUMS) {
                buildVirtualCategories("ALBUM");
            } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                buildVirtualSongs();
            }
        } else if (state == STATE_SETTINGS) {
            // 🚀 Discards the index the system corrupted, restores the real position backed up in the vault, then draws the screen!
            lastSettingsFocusIndex = safeFocusIndex;
            // 🚀 [Fix] Only draw the main settings screen when not in the middle of a direct jump!
            if (!isNavigatingToSubMenu) {
                buildSettingsUI();
            }

        } else if (state == STATE_NAVIDROME) {
            buildNavidromeUI();
        } else if (state == STATE_BLUETOOTH) {
            startBluetoothScan();
        } else if (state == STATE_WIFI) {
            startWifiScan();
        } else if (state == STATE_WIFI_KEYBOARD) {
            openKeyboard();
        } else if (state == STATE_BRIGHTNESS) {
            loadBrightnessUI();
        } else if (state == STATE_STORAGE) {
            loadStorageUI();
        } else if (state == STATE_WEBSERVER) {
            updateWebServerUI();
            btnServerToggle.requestFocus();
        }
    }

    private void loadBrightnessUI() {
        try {
            currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    255);
        } catch (Exception e) {
            Log.d(TAG, "loadBrightnessUI failed", e);
        }
        pbBrightness.setProgress(currentSystemBrightness);
        int percent = (int) (((float) currentSystemBrightness / 255.0f) * 100);
        tvBrightnessVal.setText(percent + "%");
    }

    public void updateBrightness(int newBrightness) {
        currentSystemBrightness = newBrightness;
        pbBrightness.setProgress(currentSystemBrightness);
        int percent = (int) (((float) currentSystemBrightness / 255.0f) * 100);
        tvBrightnessVal.setText(percent + "%");

        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, currentSystemBrightness);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = currentSystemBrightness / 255.0f;
            getWindow().setAttributes(layoutParams);
        } catch (Exception e) {
            Log.d(TAG, "updateBrightness failed", e);
        }
    }

    // 💡 [Fix] Logic that hides the existing bar and dynamically shows our custom circular chart
    // 💡 [Fix] Apply detailed storage info text
    // 💡 [Fully fixed] Prevent storage-capacity calculation errors (overflow) and apply the actual theme color
    private void loadStorageUI() {
        try {
            android.os.StatFs stat = new android.os.StatFs("/storage/sdcard0");

            // 🚀 [Bug 1 fixed] Force-cast to (long) to prevent the number from overflowing and erroring out when device capacity is large,
            // when computing this!
            long blockSize = (long) stat.getBlockSize();
            long total = ((long) stat.getBlockCount() * blockSize) / (1024 * 1024);
            long free = ((long) stat.getAvailableBlocks() * blockSize) / (1024 * 1024);
            long used = total - free;

            if (pbStorage != null)
                pbStorage.setVisibility(View.GONE);

            LinearLayout storageLayout = findViewById(R.id.layout_storage_mode);
            PieChartView pieChart = (PieChartView) storageLayout.findViewWithTag("pie_chart");

            if (pieChart == null) {
                pieChart = new PieChartView(this);
                pieChart.setTag("pie_chart");

                // 🚀 [Bug 2 fixed] Optimized the size to 140dp so the chart isn't so big it pushes the text below off-screen.
                int size = (int) (140 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(0, 0, 0, 30);
                pieChart.setLayoutParams(lp);

                storageLayout.addView(pieChart, 1);
            }

            // 🚀 [Bug 3 fixed] Instead of a plain white text color, pull the theme's real accent color (button focus color), strip its opacity, and paint it in solid color!
            int themeColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            pieChart.setStorageData(used, total, themeColor);

            // Set the text info and force it visible on screen
            tvStorageDetails.setText(
                    t("Total Capacity") + " :  " + total + " MB\n" +
                            t("Used Space") + " :  " + used + " MB\n" +
                            t("Free Space") + " :  " + free + " MB");
            tvStorageDetails.setGravity(android.view.Gravity.CENTER);
            tvStorageDetails.setLineSpacing(15f, 1f);
            tvStorageDetails.setVisibility(View.VISIBLE);

        } catch (Exception e) {
            tvStorageDetails.setText(t("Storage Error: Failed to calculate space."));
            tvStorageDetails.setVisibility(View.VISIBLE);
        }
    }

    // 🚀 [Direct link success] Bypasses the focus system to jump directly into the song list of the centered album!
    public void handleCenterShortClick() {
        if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_COVER_FLOW) {
            if (uniqueAlbumList != null && !uniqueAlbumList.isEmpty() && currentCoverFlowIndex >= 0 && currentCoverFlowIndex < uniqueAlbumList.size()) {
                clickFeedback();
                SongItem chosen = uniqueAlbumList.get(currentCoverFlowIndex);
                currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                virtualQueryType = "COVER_FLOW_ALBUM";
                virtualQueryValue = chosen.album;
                buildVirtualSongs();
            }
            return; // 💡 The link has been triggered, so block it from falling through to the legacy focus-click routine below!
        }

        if (currentScreenState == STATE_PLAYER) {
            toggleVisualizer();
            clickFeedback();
        } else if (currentScreenState == STATE_WIFI_KEYBOARD) {
            handleKeyboardInput();
        } else if (currentScreenState != STATE_BRIGHTNESS && currentScreenState != STATE_STORAGE
                && currentScreenState != STATE_PLAYER) {
            View c = getCurrentFocus();
            if (c != null)
                c.performClick();
        }
    }

    // 💡 [Added] Timer variable to prevent overload
    private long lastClickTime = 0;

    public void clickFeedback() {
        long now = System.currentTimeMillis();

        // 🚀 [UI freeze fully blocked] Skip wheel signals that arrive consecutively within 0.03 seconds
        if (now - lastClickTime < 30)
            return;
        lastClickTime = now;

        try {
            if (isVibrationEnabled) {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    // 🚀 Vibration algorithm tailored for Jelly Bean!
                    // Fires one of 10ms, 25ms, or 50ms depending on the configured strength (Weak/Normal/Strong).
                    v.vibrate(VIBE_DURATIONS[vibrationStrengthLevel]);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "clickFeedback failed", e);
        }
    }
    private void openKeyboard() {
        typedPassword = "";
        keyboardIndex = 0;
        tvKeyboardSsid.setText(t("Target") + ": " + targetWifiSsid);
        updateKeyboardUI();
    }

    public void updateKeyboardUI() {
        int len = KEYBOARD_CHARS.length;
        int idxPprev = (keyboardIndex - 2 + len) % len;
        int idxPrev = (keyboardIndex - 1 + len) % len;
        int idxNext = (keyboardIndex + 1) % len;
        int idxNnext = (keyboardIndex + 2) % len;
        tvKeyPprev.setText(KEYBOARD_CHARS[idxPprev]);
        tvKeyPrev.setText(KEYBOARD_CHARS[idxPrev]);
        tvKeyCurrent.setText(KEYBOARD_CHARS[keyboardIndex]);
        tvKeyNext.setText(KEYBOARD_CHARS[idxNext]);
        tvKeyNnext.setText(KEYBOARD_CHARS[idxNnext]);
        if (isTargetWifiOpen) {
            tvKeyboardInput.setText(t("Open Network (Direct Connect)"));
            keyboardIndex = len - 1;
            tvKeyCurrent.setText(KEYBOARD_CHARS[keyboardIndex]);
        } else {
            tvKeyboardInput.setText(typedPassword.length() == 0 ? t("Enter Password...") : typedPassword);
        }
    }

    private void handleKeyboardInput() {
        String selectedChar = KEYBOARD_CHARS[keyboardIndex];
        clickFeedback();
        if (selectedChar.equals("[DEL]")) {
            if (typedPassword.length() > 0)
                typedPassword = typedPassword.substring(0, typedPassword.length() - 1);
        } else if (selectedChar.equals("[CONN]")) {
            connectToWifi();
        } else {
            typedPassword += selectedChar;
        }
        updateKeyboardUI();
    }

    private void connectToWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Toast.makeText(this, t("Wi-Fi is unavailable."), Toast.LENGTH_SHORT).show();
            return;
        }
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + targetWifiSsid + "\"";
        if (isTargetWifiOpen)
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        else
            conf.preSharedKey = "\"" + typedPassword + "\"";
        int netId = wm.addNetwork(conf);
        // addNetwork() returns -1 on failure (bad config, duplicate SSID, etc.) — this used to be
        // ignored, so a bad password/config would silently show "Connecting..." with no error.
        if (netId == -1) {
            Toast.makeText(this, t("Failed to save this network. Please check the password and try again."), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, t("Connecting to ") + targetWifiSsid + "...", Toast.LENGTH_SHORT).show();
        wm.disconnect();
        wm.enableNetwork(netId, true);
        wm.reconnect();
        wm.saveConfiguration();
        changeScreen(STATE_WIFI);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startBluetoothScan() {
        int currentFocusIndex = 0;
        if (containerBtItems != null) {
            for (int i = 0; i < containerBtItems.getChildCount(); i++) {
                if (containerBtItems.getChildAt(i).hasFocus()) {
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

        containerBtItems.removeAllViews();

        // 1. Power toggle button
        final LinearLayout btnToggle = createSettingRow(t("Bluetooth Power"), t(statusText));
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (ba != null) {
                    if (ba.isEnabled())
                        ba.disable();
                    else
                        ba.enable();
                    ((TextView) btnToggle.getChildAt(1)).setText(t("Wait..."));
                }
            }
        });
        containerBtItems.addView(btnToggle);

        if (!isOn) {
            btnScanBt.setText(t("Bluetooth is OFF"));
            if (btnScanBt.getParent() != null)
                ((android.view.ViewGroup) btnScanBt.getParent()).removeView(btnScanBt);
            containerBtItems.addView(btnScanBt);
            restoreBluetoothFocus(targetFocusIndex);
            return;
        }

        // 🚀 2. Fully implemented like the stock launcher: My Devices (PAIRED DEVICES) list
        TextView tvPaired = new TextView(this);
        tvPaired.setText("━ "+t("PAIRED DEVICES")+" ━");
        tvPaired.setTextColor(0xBBFFFFFF);
        tvPaired.setTextSize(14);
        tvPaired.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvPaired.setPadding(10, 30, 10, 5);
        containerBtItems.addView(tvPaired);

        try {
            java.util.Set<BluetoothDevice> pairedDevices = ba.getBondedDevices();
            if (pairedDevices != null && pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    addPairedBluetoothItemToUI(device); // Call the UI dedicated to paired devices
                }
            } else {
                TextView tvEmpty = new TextView(this);
                tvEmpty.setText(t("No paired devices."));
                tvEmpty.setTextColor(0xFF888888);
                tvEmpty.setPadding(10, 10, 10, 10);
                containerBtItems.addView(tvEmpty);
            }
        } catch (Exception e) {
            Log.d(TAG, "startBluetoothScan failed", e);
        }

        // 🚀 3. Newly found devices (AVAILABLE DEVICES) list
        TextView tvAvailable = new TextView(this);
        tvAvailable.setText("━ " + t("AVAILABLE DEVICES") + " ━");
        tvAvailable.setTextColor(0xBBFFFFFF);
        tvAvailable.setTextSize(14);
        tvAvailable.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvAvailable.setPadding(10, 30, 10, 5);
        containerBtItems.addView(tvAvailable);

        btnScanBt.setText(t("Scanning..."));
        foundBtDevices.clear();

        if (btnScanBt.getParent() != null)
            ((android.view.ViewGroup) btnScanBt.getParent()).removeView(btnScanBt);
        containerBtItems.addView(btnScanBt);

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

        restoreBluetoothFocus(targetFocusIndex);
    }

    // 🚀 [New] List and unpair menu dedicated to paired devices
    private void addPairedBluetoothItemToUI(final BluetoothDevice device) {
        String name = (device.getName() != null && !device.getName().isEmpty()) ? device.getName()
                : "Unknown (" + device.getAddress() + ")";

        boolean isConnected = com.themoon.y1.managers.BluetoothAudioManager.getInstance().isA2dpConnectedTo(device);

        String prefix = isConnected ? "((♪)) [CONNECTED] " : "✔ ";
        final Button btnDevice = createListButton(prefix + name);

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
        final android.view.View btnConnect = createListButtonWithIcon("\uE1B1", t("Connect Audio"), 0xFFFFFFFF);

        btnConnect.setVisibility(View.GONE); // Hidden initially.
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                connectBluetoothAudio(device); // 🚀 Call the central engine!
            }
        });

        // 🚀 [Hybrid engine integration] Inject the trash-can unicode ("\uE872") and red (0xFFFF5555).
        // (Note: the return type changes from Button to android.view.View)
        final android.view.View btnUnpair = createListButtonWithIcon("\uE872", t("Delete Device"), 0xFFFF5555);

        btnUnpair.setVisibility(View.GONE); // Hidden initially.
        btnUnpair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                try {
                    // Deleting the device is a deliberate "stop connecting to this" -- drop it as the
                    // watchdog target and cancel any pending reconnect so we don't re-pair behind the user.
                    com.themoon.y1.managers.BluetoothAudioManager.getInstance().forgetTargetIfMatches(device);
                    device.getClass().getMethod("removeBond").invoke(device);
                    Toast.makeText(MainActivity.this, t("Device Deleted."), Toast.LENGTH_SHORT).show();
                    startBluetoothScan(); // Refresh the screen after removal
                } catch (Exception e) {
                    Log.d(TAG, "addPairedBluetoothItemToUI failed", e);
                }
            }
        });

        // Strips the invisibility cloak off the hidden sub-menu buttons when the parent button is clicked.
        btnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
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
        containerBtItems.addView(btnDevice);
        containerBtItems.addView(btnConnect);
        containerBtItems.addView(btnUnpair);
    }

    // 🚀 [New] Dedicated to newly scanned (Available) devices
    private void addBluetoothItemToUI(String name, final BluetoothDevice device, boolean isPaired) {
        if (device.getBondState() == BluetoothDevice.BOND_BONDED)
            return; // Ignore since paired devices are drawn above

        final Button btnDevice = createListButton("🔍 " + name);

        btnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                connectBluetoothAudio(device); // 🚀 Even if unpaired, the central engine handles pairing first automatically!
            }
        });

        containerBtItems.addView(btnDevice, containerBtItems.getChildCount() - 1);
    }

    // 🚀 [Tool dedicated to focus restoration]
    private void restoreBluetoothFocus(final int targetFocusIndex) {
        containerBtItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (containerBtItems.getChildCount() > 0) {
                    if (targetFocusIndex >= 0 && targetFocusIndex < containerBtItems.getChildCount()) {
                        View target = containerBtItems.getChildAt(targetFocusIndex);
                        if (target.isFocusable()) {
                            target.requestFocus();
                            return;
                        }
                    }
                    containerBtItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    // 🚀 [Restore the most robust connection engine] Sub-menus and macros all removed!

    private void startWifiScan() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isOn = wm != null && wm.isWifiEnabled();
        updateWifiUI(null);

        if (isOn) {
            btnScanWifi.setText(t("Scanning..."));
            foundWifiNetworks.clear();
            // 💡 Always force-move focus to the top power button!
            if (containerWifiItems.getChildCount() > 0)
                containerWifiItems.getChildAt(0).requestFocus();
            wm.startScan();
        } else {
            btnScanWifi.setText(t("Wi-Fi is OFF"));
            // 💡 Always force-move focus to the top power button!
            if (containerWifiItems.getChildCount() > 0)
                containerWifiItems.getChildAt(0).requestFocus();
        }
    }

    private void updateWifiUI(List<ScanResult> results) {
        final WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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

        View existingToggle = containerWifiItems.findViewById(999992);
        if (existingToggle == null) {
            final LinearLayout btnToggle = createSettingRow(t("Wi-Fi Power"), t(statusText));
            btnToggle.setId(999992);
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (wm != null) {
                        boolean isCurrentlyOn = wm.isWifiEnabled();
                        if (isCurrentlyOn) {
                            Toast.makeText(MainActivity.this, t("Turning Wi-Fi OFF..."), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(false);
                        } else {
                            Toast.makeText(MainActivity.this, t("Turning Wi-Fi ON..."), Toast.LENGTH_SHORT).show();
                            wm.setWifiEnabled(true);
                        }
                        TextView tvRight = (TextView) btnToggle.getChildAt(1);
                        tvRight.setText(t("Wait..."));
                        if (!btnToggle.hasFocus())
                            tvRight.setTextColor(0xFFFFFF00);
                    }
                }
            });
            containerWifiItems.addView(btnToggle, 0);

            btnWifiWebServer = createSettingRow(t("Web Server"), "〉 ");
            btnWifiWebServer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    changeScreen(STATE_WEBSERVER);
                }
            });
            containerWifiItems.addView(btnWifiWebServer, 0);

            if (btnScanWifi.getParent() != null) {
                ((android.view.ViewGroup) btnScanWifi.getParent()).removeView(btnScanWifi);
            }
            containerWifiItems.addView(btnScanWifi);
        } else {
            LinearLayout btnToggle = (LinearLayout) existingToggle;
            TextView tvRight = (TextView) btnToggle.getChildAt(1);
            tvRight.setText(t(statusText));
            if (!btnToggle.hasFocus()) {
                if (statusText.equals("ON"))
                    tvRight.setTextColor(0xFFFFFFFF);
                else if (statusText.equals("OFF"))
                    tvRight.setTextColor(0xFF888888);
                else
                    tvRight.setTextColor(0xFFFFFFFF);
            }
            for (int i = containerWifiItems.getChildCount() - 1; i >= 0; i--) {
                View v = containerWifiItems.getChildAt(i);
                if (v != btnScanWifi && v != btnWifiWebServer && v.getId() != 999992) {
                    containerWifiItems.removeViewAt(i);
                }
            }
            if (btnWifiWebServer.getParent() == null) {
                containerWifiItems.addView(btnWifiWebServer, 0);
            }
        }

        if (!isOn)
            return;

        if (results != null) {
            foundWifiNetworks.clear();
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = manager.getConnectionInfo();
            String connectedSSID = "";
            if (wifiInfo != null && wifiInfo.getSSID() != null) {
                connectedSSID = wifiInfo.getSSID().replace("\"", "");
            }

            // 🚀 Priority 1: Find the currently connected Wi-Fi first and place it at the top!
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty() && !foundWifiNetworks.contains(result.SSID)) {
                    if (result.SSID.equals(connectedSSID)) {
                        foundWifiNetworks.add(result.SSID);
                        addWifiItemToUI(result.SSID, result.capabilities, true);
                    }
                }
            }

            // 🚀 Priority 2: List the rest of the unconnected miscellaneous Wi-Fi networks below it
            for (ScanResult result : results) {
                if (result.SSID != null && !result.SSID.isEmpty() && !foundWifiNetworks.contains(result.SSID)) {
                    foundWifiNetworks.add(result.SSID);
                    addWifiItemToUI(result.SSID, result.capabilities, false);
                }
            }
        }
    }

    // 💡 Function reworked to directly receive the connection state (isConnected) as a parameter
    private void addWifiItemToUI(final String ssid, String capabilities, final boolean isConnected) {
        final boolean isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP");
        String lockIcon = isOpen ? "📶 " : "🔒 ";

        // Give connected devices a nice Apple-style checkmark (✔) instead of plain text!
        String prefix = isConnected ? "✔ " : "";

        Button btnWifi = createListButton(prefix + lockIcon + ssid);

        if (isConnected) {
            btnWifi.setTextColor(0xFF00FF00); // Eye-catching green!
            btnWifi.setTypeface(null, android.graphics.Typeface.BOLD); // Emphasize with bold text!
        }

        btnWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (isConnected) {
                    return;
                }

                WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
                    Toast.makeText(MainActivity.this, t("Connecting to saved network..."), Toast.LENGTH_SHORT).show();
                    manager.disconnect();
                    manager.enableNetwork(savedNetId, true);
                    manager.reconnect();
                } else {
                    targetWifiSsid = ssid;
                    isTargetWifiOpen = isOpen;
                    changeScreen(STATE_WIFI_KEYBOARD);
                }
            }
        });
        containerWifiItems.addView(btnWifi);
    }

    public void createCategoryHeader(String title) {
        TextView tv = new TextView(this);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tv.setText(t(title)); // 🚀 [Fix] Run the incoming title through the translator once before setting it!
        // 💡 Drop the sky-blue and switch to Apple-style subtle translucent white & bold text!
        tv.setTextColor(0xBBFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(10, 30, 10, 5);
        containerSettingsItems.addView(tv);
    }

    public LinearLayout createSettingRow(String leftText, String rightText) {
        final LinearLayout layout = new LinearLayout(this);
        layout.setSoundEffectsEnabled(false);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setFocusable(true);
        layout.setPadding(20, 15, 20, 15);
        layout.setOnLongClickListener(globalScreenOffLongClickListener);
        // 🚀 [Fix complete] Removed the flat-color overwrite (setBackgroundColor) and only apply the rounded-corner background!
        layout.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));

        TextView tvLeft = new TextView(this);
        tvLeft.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        tvLeft.setText(t(leftText));
        tvLeft.setTextColor(ThemeManager.getTextColorPrimary());
        tvLeft.setTextSize(18);
        tvLeft.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvRight = new TextView(this);
        tvRight.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvRight.setText(rightText);
        tvRight.setTextSize(18);
        tvRight.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRight.setGravity(android.view.Gravity.RIGHT);
        tvRight.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (rightText.equals("ON") || rightText.equals("ONE") || rightText.equals("ALL"))
            tvRight.setTextColor(ThemeManager.getTextColorPrimary());
        else
            tvRight.setTextColor(ThemeManager.getTextColorSecondary());

        layout.addView(tvLeft);
        layout.addView(tvRight);

        layout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    layout.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    ((TextView) layout.getChildAt(0)).setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    ((TextView) layout.getChildAt(1)).setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    // 🚀 [Bug fully blocked, radio-compatible] Carefully saves focus into the right drawer depending on depth!
                    // 🚀 [Bug fully blocked, radio-compatible] Carefully saves focus into the right drawer depending on depth!
                    if (currentScreenState == STATE_SETTINGS) {
                        int idx = containerSettingsItems.indexOfChild(layout);
                        if (idx != -1) {
                            if (currentSettingsDepth == 0) lastSettingsFocusIndex = idx;
                            else if (currentSettingsDepth == 1) {
                                //lastRadioFocusIndex = idx; // 💡 Perfectly tracks radio focus!

                                // 🚀 [Ensure frequency display visibility]
                                // When focus lands on a top item (Power=2, Tune=3), Android's ScrollView
                                // would cut off the display above, so force-lock the scroll to the top (0,0) to prevent that.
                                if (idx <= 2) {
                                    if (containerSettingsItems.getParent() instanceof android.widget.ScrollView) {
                                        ((android.widget.ScrollView) containerSettingsItems.getParent()).scrollTo(0, 0);
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 🚀 Revert to the subtle background when focus leaves!
                    layout.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    ((TextView) layout.getChildAt(0)).setTextColor(ThemeManager.getTextColorPrimary());
                    TextView rightTv = (TextView) layout.getChildAt(1);
                    String currentText = rightTv.getText().toString();

                    // 🚀 [Bug fix] Guard added so SHOW-state text keeps the default primary text color (white) instead of turning gray!
                    if (currentText.equals("ON") || currentText.equals("ONE") || currentText.equals("ALL") || currentText.equals(t("SHOW")))
                        rightTv.setTextColor(ThemeManager.getTextColorPrimary());
                    else
                        rightTv.setTextColor(ThemeManager.getTextColorSecondary());
                }
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        layout.setLayoutParams(lp);

        return layout;
    }
    // 🚀 [Added engine] A helper function that auto-passes 0 (default theme color) when no color is specified, so existing code doesn't error out!
    public android.view.View createListButtonWithIcon(String iconUnicode, String textLabel) {
        return createListButtonWithIcon(iconUnicode, textLabel, 0);
    }

    // 🚀 [Main engine upgrade] Reworked to accept a third parameter, 'customColor' (the color to paint).
    public android.view.View createListButtonWithIcon(String iconUnicode, String textLabel, final int customColor) {
        float d = getResources().getDisplayMetrics().density;

        final android.widget.LinearLayout rowButton = new android.widget.LinearLayout(this);
        rowButton.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowButton.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowButton.setFocusable(true);
        rowButton.setClickable(true);
        rowButton.setSoundEffectsEnabled(false);
        rowButton.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));

        int padLeft = (int)(25 * d);
        int padTopBottom = (int)(12 * d);
        int padRight = (int)(10 * d);
        rowButton.setPadding(padLeft, padTopBottom, padRight, padTopBottom);

        android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 2, 0, 2);
        rowButton.setLayoutParams(rowLp);

        // 💡 [Key technique] Load the user-specified color into the variable if set, otherwise fall back to the theme's default color!
        final int normalColor = (customColor != 0) ? customColor : ThemeManager.getTextColorPrimary();

        final android.widget.TextView tvIcon = new android.widget.TextView(this);
        tvIcon.setText(iconUnicode);
        tvIcon.setTextSize(21f);
        tvIcon.setTextColor(normalColor); // 🚀 Painted with the specified color!

        if (materialIconFont == null) {
            try { materialIconFont = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/MaterialIcons-Regular.ttf"); }
            catch (Exception e) {
                Log.d(TAG, "createListButtonWithIcon failed", e);
            }
        }
        if (materialIconFont != null) tvIcon.setTypeface(materialIconFont);

        android.widget.LinearLayout.LayoutParams iconLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.rightMargin = (int)(15 * d);
        tvIcon.setLayoutParams(iconLp);

        final android.widget.TextView tvText = new android.widget.TextView(this);
        tvText.setText(textLabel);
        tvText.setTextSize(18f);
        tvText.setTextColor(normalColor); // 🚀 Text painted the same way!
        tvText.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);

        android.widget.LinearLayout.LayoutParams textLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tvText.setLayoutParams(textLp);

        rowButton.addView(tvIcon);
        rowButton.addView(tvText);
        rowButton.setTag(textLabel); // lets back-navigation re-find this row by its label, since it isn't a plain Button

        rowButton.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(android.view.View v, boolean hasFocus) {
                if (hasFocus) {
                    rowButton.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    tvIcon.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    tvText.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                } else {
                    // 🚀 [Focus-restoration bug fully resolved] When focus leaves, it doesn't reset to white — it returns exactly to the color originally painted!
                    rowButton.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    tvIcon.setTextColor(normalColor);
                    tvText.setTextColor(normalColor);
                }
            }
        });

        return rowButton;
    }

    public Button createListButton(String text) {
        final Button btn = new Button(this);

        // 🚀 [Fix complete] Removed the flat-color overwrite (setBackgroundColor) and only apply the rounded-corner background!
        btn.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        btn.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        btn.setSoundEffectsEnabled(false);
        btn.setText(t(text));
        btn.setTextSize(18);
        btn.setTextColor(ThemeManager.getTextColorPrimary());

        btn.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);

        // 🚀 [Fix] Give it generous padding (dp) scaled to the screen density!
        float density = getResources().getDisplayMetrics().density;
        int padLeft = (int)(25 * density); // Generous 25dp left padding!
        int padTopBottom = (int)(12 * density);
        int padRight = (int)(10 * density);
        btn.setPadding(padLeft, padTopBottom, padRight, padTopBottom);

        btn.setFocusable(true);
        btn.setSingleLine(true);
        btn.setOnLongClickListener(globalScreenOffLongClickListener);
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // 🚀 Apply the rounded focused-state background! (flat-color overwrite removed)
                    btn.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());

                } else {
                    // 🚀 Apply the rounded normal-state background! (flat-color overwrite removed)
                    btn.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                }
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        btn.setLayoutParams(lp);

        return btn;
    }

    public void buildSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildSettingsUI(this);
    }

    public void routeBackToSettingsGroup() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().routeBackToSettingsGroup(this);
    }

    public void buildPlaybackGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildPlaybackGroupUI(this);
    }

    public void buildSoundVibrationGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildSoundVibrationGroupUI(this);
    }

    public void buildConnectivityGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildConnectivityGroupUI(this);
    }

    public void buildDisplayInterfaceGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildDisplayInterfaceGroupUI(this);
    }

    public void buildStorageLibraryGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildStorageLibraryGroupUI(this);
    }

    public void buildSystemGroupUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildSystemGroupUI(this);
    }
    // 💡 [New] Screen dedicated to selecting a language pack
    public void buildLanguageSelectorUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildLanguageSelectorUI(this);
    }
    public void buildRadioUI() {
        com.themoon.y1.managers.FmRadioUiManager.getInstance().build(this);
    }

    public void buildUpdateSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildUpdateSettingsUI(this);
    }
    // 💡 [New] Dedicated sub-menu for vibration ON/OFF and intensity control!
    public void buildVibrationSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildVibrationSettingsUI(this);
    }
    // 💡 [Added] Sub-menu screen dedicated to toggling widgets on and off
    // 💡 [Fix complete] Unified widget settings menu that can toggle all 5 widgets on and off
    public void buildWidgetSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildWidgetSettingsUI(this);
    }
    // 💡 [Added] Sub-menu screen that combines setting and clearing the wallpaper into one
    public void buildBackgroundSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildBackgroundSettingsUI(this);
    }

    // 💡 [Overhaul complete] Engine that shows download progress (%) and size (MB) in a live popup!
    public void downloadAndInstallApk(final String apkUrl) {
        // 🚀 1. Assemble the design of the 'download progress popup' shown on screen directly in Java code.
        final ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        final TextView tvProgress = new TextView(this);
        tvProgress.setGravity(android.view.Gravity.CENTER);
        tvProgress.setPadding(0, 30, 0, 0);
        tvProgress.setTextSize(16);
        tvProgress.setText(t("Connecting to server..."));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);
        layout.addView(progressBar);
        layout.addView(tvProgress);

        final AlertDialog progressDialog = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Downloading Update"))
                .setView(layout)
                .setCancelable(false) // 💡 Lock the dialog so it can't be dismissed by tapping elsewhere during download!
                .create();

        progressDialog.show();

        // 🚀 2. Start the background download thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(apkUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                    // 🚀 [Required 1] Break through GitHub's security (TLS 1.2)
                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new TLSSocketFactory());
                        } catch (Exception e) {
                            Log.d(TAG, "downloadAndInstallApk failed", e);
                        }
                    }

                    conn.setInstanceFollowRedirects(false);

                    // 🚀 [Add here!!] Turn off Android's meddling automatic compression (GZIP)! (blocks fake size inflation at the source)
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setUseCaches(false);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    // 🚀 [Required 2] Chase the GitHub redirect (address forwarding) and grab the file!
                    int status = conn.getResponseCode();
                    if (status == 301 || status == 302 || status == 303) {
                        String newUrl = conn.getHeaderField("Location");
                        conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
                        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                            try {
                                ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new TLSSocketFactory());
                            } catch (Exception e) {
                                Log.d(TAG, "downloadAndInstallApk failed", e);
                            }
                        }

                        // 🚀 [Add here!!] Re-issue the no-compression command on the redirected real download URL too!
                        conn.setRequestProperty("Accept-Encoding", "identity");
                    }

                    conn.connect();

                    // Find out the total size of the file from the server.
                    final int fileLength = conn.getContentLength();

                    // ❌ [Remove all the existing download-path-assignment code]
                    // File sdcard = android.os.Environment.getExternalStorageDirectory();
                    // ...

                    // 🚀 ⭕ [Overwrite with new code] Create an 'app-private internal vault' unaffected by the SD card.
                    File dir = getDir("update", Context.MODE_PRIVATE);
                    final File updateFile = new File(dir, "Y1_Launcher_Update.apk");

                    java.io.FileOutputStream fos = new java.io.FileOutputStream(updateFile);
                    java.io.InputStream is = conn.getInputStream();

                    byte[] buffer = new byte[4096]; // 💡 Quadrupled the buffer size for download speed.
                    int len;
                    long total = 0;

                    // Download the file piece by piece while pushing the percentage to the screen at the same time.
                    while ((len = is.read(buffer)) != -1) {
                        total += len;
                        fos.write(buffer, 0, len);

                        if (fileLength > 0) {
                            final int progress = (int) (total * 100 / fileLength);
                            final long downloadedMB = total / (1024 * 1024);
                            final long totalMB = fileLength / (1024 * 1024);

                            // Manipulating the UI must always be done on the main thread.
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progressBar.setProgress(progress);
                                    tvProgress.setText(progress + "%   (" + downloadedMB + "MB / " + totalMB + "MB)");
                                }
                            });
                        }
                    }
                    fos.close();
                    is.close();
                    // (earlier part omitted) Overwrite starting from the leftover-check section right after the loop ends

                    if (fileLength > 0 && total != fileLength) {
                        if (updateFile.exists())
                            updateFile.delete();
                        throw new Exception("Incomplete Download: Size Mismatch");
                    }

                    // 🚀 [Overwrite from here!!] When the download finishes, don't close the window immediately — display the size the server reported and the size we actually received on screen!
                    final String debugMessage = "Server told: " + fileLength + " bytes\nActually got: " + total
                            + " bytes";

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 1. Fill the progress bar completely and show the key numbers we need to verify on screen.
                            progressBar.setProgress(100);
                            tvProgress.setText(t("Download Finished! Waiting 3 sec...\n\n") + debugMessage);
                            tvProgress.setTextColor(0xFF000000); // Eye-catching yellow!

                            // 2. Freeze the screen for exactly 3 seconds (3000ms), then close the popup and attempt installation.
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    progressDialog.dismiss();
                                    installApk(updateFile);
                                }
                            }, 3000);
                        }
                    });

                    // 👆 [End of overwrite here] 👆
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressDialog.dismiss();
                            Toast.makeText(MainActivity.this, t("Download failed. Check your internet connection."),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private boolean isAudioFile(File f) {
        if (f == null || !f.isFile())
            return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".ogg")
                || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".ape") || name.endsWith(".wma");
    }

    private boolean isApkFile(File f) {
        if (f == null || !f.isFile())
            return false;
        return f.getName().toLowerCase().endsWith(".apk");
    }

    private boolean isImageFile(File f) {
        if (f == null || !f.isFile())
            return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    // 💡 Function that digs through subfolders and collects only the 'paths' of music files
    private void collectAudioFiles(File file, List<String> paths) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    collectAudioFiles(f, paths); // Recurse in if it's a folder
                }
            }
        } else if (isAudioFile(file)) {
            paths.add(file.getAbsolutePath()); // Add it to the list if it's a music file!
        }
    }


    // 🚀 [New] Song list generator dedicated to '💖 My Favorites'
    private void buildVirtualSongsForFavorites() {
        // Only block on a scan that's still in progress if there's no cached library to show
        // yet at all — a background reconciliation scan shouldn't hide already-available data.
        if (isCustomScanning && customLibrary.isEmpty() && audiobookLibrary.isEmpty()) {
            showLoadingPopup();
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);

        tvBrowserPath.setText(t("Library") + ": " + t("My Favorites"));

        virtualSongList.clear();
        currentScrollIndexList.clear();
        List<SongItem> targetSongs = new ArrayList<>();

        for (SongItem song : customLibrary) {
            // Add to the list if this song's path is recorded in the vault (favoritePaths)!
            if (favoritePaths.contains(song.file.getAbsolutePath())) {
                targetSongs.add(song);
            }
        }

        // 🚀 [Fix] Also sort the favorites list case-insensitively by title!
        java.util.Collections.sort(targetSongs, new java.util.Comparator<SongItem>() {
            @Override
            public int compare(SongItem s1, SongItem s2) {
                return s1.title.compareToIgnoreCase(s2.title);
            }
        });

        // Fill the playback list and index in sorted order.
        for (SongItem song : targetSongs) {
            virtualSongList.add(song.file);
            currentScrollIndexList.add(song.title);
        }

        if (targetSongs.isEmpty()) {
            Toast.makeText(this, t("No favorites added yet."), Toast.LENGTH_SHORT).show();
        }

        SongListAdapter adapter = new SongListAdapter(targetSongs);
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(() -> {
            if (listVirtualSongs.getChildCount() > 0) listVirtualSongs.getChildAt(0).requestFocus();
        });
    }
    // 💡 2. Library main router (with the custom scan button applied)
    // 💡 Existing code modified
    public void buildFileBrowserUI() {
        if (scrollViewBrowser != null)
            scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null)
            listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();

        // 🚀 [Fix] Group the condition so the folder-browser frame also applies in audiobook mode.
        if (isPickingBackground || currentBrowserMode == BROWSER_FOLDER || currentBrowserMode == BROWSER_AUDIOBOOKS) {
            buildFolderBrowserUI();
            return;
        }

        if (currentBrowserMode == BROWSER_ROOT) {

            // 🎵 [Music library mode]
            if (!isAudiobookLibraryMode) {
                tvBrowserPath.setText(t("Library") + ": " + t("Music"));

                android.view.View btnCoverFlow = createListButtonWithIcon("\uE3B6", t("Cover Flow"));

                // setOnClickListener works exactly the same even if the returned view is a LinearLayout!
                btnCoverFlow.setOnClickListener(v -> { clickFeedback(); buildCoverFlowUI(); });
                containerBrowserItems.addView(btnCoverFlow);

                // \u2601\uFE0F Navidrome \uC2A4\uD2B8\uB9AC\uBC0D \u2014 back returns here, not the main menu
                android.view.View btnNavidromeLib = createListButtonWithIcon("\uE2BD", t("Navidrome"));
                btnNavidromeLib.setOnClickListener(v -> {
                    clickFeedback();
                    navidromeBrowseDepth = NAV_ARTISTS;
                    selectedNavidromeArtist = null;
                    com.themoon.y1.managers.NavidromeManager.getInstance().clearSelectedAlbum();
                    isNavidromeLetterView = false;
                    navidromeBackTarget = STATE_BROWSER;
                    changeScreen(STATE_NAVIDROME);
                });
                containerBrowserItems.addView(btnNavidromeLib);

                android.view.View btnM3uPlaylist = createListButtonWithIcon("\uE05F", t("Playlists"));
                btnM3uPlaylist.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_PLAYLISTS; buildM3uPlaylistUI(); });
                containerBrowserItems.addView(btnM3uPlaylist);

                //Button btnFolder = createListButton("📁 " + t("Folders"));
                android.view.View btnFolder = createListButtonWithIcon("\uE2C7", t("Folders"));
                btnFolder.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_FOLDER; currentFolder = rootFolder; buildFileBrowserUI(); });
                containerBrowserItems.addView(btnFolder);

               /// Button btnArtist = createListButton("👤 " + t("Artists"));
                android.view.View btnArtist = createListButtonWithIcon("\uE7FD", t("Artists"));
                btnArtist.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_ARTISTS; virtualQueryValue = ""; buildVirtualCategories("ARTIST"); });
                containerBrowserItems.addView(btnArtist);

              //  Button btnAlbum = createListButton("💿 " + t("Albums"));
                android.view.View btnAlbum = createListButtonWithIcon("\uE019", t("Albums"));
                btnAlbum.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_ALBUMS; virtualQueryValue = ""; buildVirtualCategories("ALBUM"); });
                containerBrowserItems.addView(btnAlbum);

                android.view.View btnYear = createListButtonWithIcon("\uE916", t("Years"));
                btnYear.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_YEARS; virtualQueryValue = ""; buildVirtualCategories("YEAR"); });
                containerBrowserItems.addView(btnYear);

                android.view.View btnGenre = createListButtonWithIcon("\uE030", t("Genres"));
                btnGenre.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_GENRES; virtualQueryValue = ""; buildVirtualCategories("GENRE"); });
                containerBrowserItems.addView(btnGenre);
               // Button btnAll = createListButton("🎵 " + t("All Songs"));
                android.view.View btnAll = createListButtonWithIcon("\uE03D", t("All Songs"));
                btnAll.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_VIRTUAL_SONGS; virtualQueryType = "ALL"; buildVirtualSongs(); });
                containerBrowserItems.addView(btnAll);


                android.view.View btnFav = createListButtonWithIcon("\uE87D", t("My Favorites"));

//                btnFav.setTextColor(0xFFFF8888);
                btnFav.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_FAVORITES; buildVirtualSongsForFavorites(); });
                containerBrowserItems.addView(btnFav);
                // 🎧 Switch-to-audiobook-mode button
               // Button btnAudiobook = createListButton("🎧 " + t("Switch to Audiobooks"));
                android.view.View btnAudiobook = createListButtonWithIcon("\uE86D", t("Switch to Audiobooks"));
          //      btnAudiobook.setTextColor(0xFF00FFFF);
                btnAudiobook.setOnClickListener(v -> { clickFeedback(); isAudiobookLibraryMode = true; buildFileBrowserUI(); });
                containerBrowserItems.addView(btnAudiobook);
            }
            // 📚 [Audiobook library mode]
            else {
                tvBrowserPath.setText(t("Library") + ": " + t("Audiobooks"));

//                Button btnFolder = createListButton("📁 " + t("Folders"));
                android.view.View btnFolder = createListButtonWithIcon("\uE2C7", t("Folders"));
                btnFolder.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_FOLDER; currentFolder = audiobookRootFolder; buildFileBrowserUI(); });
                containerBrowserItems.addView(btnFolder);

                android.view.View btnAuthor = createListButtonWithIcon("\uE7FD", t("Authors"));
                btnAuthor.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_ARTISTS; virtualQueryValue = ""; buildVirtualCategories("ARTIST"); });
                containerBrowserItems.addView(btnAuthor);

                android.view.View btnBook = createListButtonWithIcon("\uE86D", t("Books"));
                btnBook.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_ALBUMS; virtualQueryValue = ""; buildVirtualCategories("ALBUM"); });
                containerBrowserItems.addView(btnBook);

                android.view.View btnAll = createListButtonWithIcon("\uE8FE", t("All Audiobooks"));

                btnAll.setOnClickListener(v -> { clickFeedback(); currentBrowserMode = BROWSER_VIRTUAL_SONGS; virtualQueryType = "ALL"; buildVirtualSongs(); });
                containerBrowserItems.addView(btnAll);

                // 🎵 Switch-back-to-music-mode button
                android.view.View btnMusic = createListButtonWithIcon("\uE03D", t("Switch to Music"));

                btnMusic.setOnClickListener(v -> { clickFeedback(); isAudiobookLibraryMode = false; buildFileBrowserUI(); });
                containerBrowserItems.addView(btnMusic);
            }
            // Uses the hourglass unicode (\uE88B) while scanning, and the sync-arrow unicode (\uE863) otherwise.
            String scanIcon = isCustomScanning ? "\uE88B" : "\uE863";
            String scanText = isCustomScanning ? t("Scanning Media...") : t("Scan Media Library");

            android.view.View btnScan = createListButtonWithIcon(scanIcon, scanText);

            btnScan.setOnClickListener(v -> {
                clickFeedback();
                startMediaLibraryScan();
            });
            containerBrowserItems.addView(btnScan);

            if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
        }
        // 🚀 [Added] Once the screen finishes drawing (50ms later), find the folder/menu that just appeared and auto-focus it!
        containerBrowserItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                if (!lastBrowserFocusText.isEmpty()) {
                    for (int i = 0; i < containerBrowserItems.getChildCount(); i++) {
                        View v = containerBrowserItems.getChildAt(i);
                        boolean isMatch = (v instanceof Button && ((Button) v).getText().toString().equals(lastBrowserFocusText))
                                || lastBrowserFocusText.equals(v.getTag());
                        if (isMatch) {
                            v.requestFocus();
                            if (containerBrowserItems.getParent() instanceof android.widget.ScrollView) {
                                ((android.widget.ScrollView) containerBrowserItems.getParent())
                                        .requestChildFocus(containerBrowserItems, v);
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found && containerBrowserItems.getChildCount() > 0) {
                    containerBrowserItems.getChildAt(0).requestFocus(); // Fall back to the top if not found
                }
                lastBrowserFocusText = ""; // 🚀 One-shot, so reset the memory right after use
            }
        }, 50);
    }

    // 💡 3. Extract artist/album categories from the local DB (ultra-fast engine applied!)
    public void buildVirtualCategories(final String type) {
        // Only block on a scan that's still in progress if there's no cached library to show
        // yet at all — a background reconciliation scan shouldn't hide already-available data.
        if (isCustomScanning && customLibrary.isEmpty() && audiobookLibrary.isEmpty()) {
            showLoadingPopup(); // 🚀 Show the nice loading popup if a scan is in progress!
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }

        // 🚀 Turn off the slow ScrollView for the category tabs too, and turn on the ultra-fast ListView!
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);

        // 🚀 [Fix] Corrected so the top title syncs correctly for both the music library (Artists/Albums) and the audiobook library (Authors/Books)!
        if (isAudiobookLibraryMode) {
            tvBrowserPath.setText(t("Library") + ": " + (type.equals("ARTIST") ? t("Authors") : t("Books")));
        } else {
            tvBrowserPath.setText(t("Library") + ": " + (type.equals("ARTIST") ? t("Artists") : t("Albums")));
        }

        // 🚀 Swap the bucket to search through depending on the switch!
        List<SongItem> activeLibrary = isAudiobookLibraryMode ? audiobookLibrary : customLibrary;

        java.util.HashSet<String> uniqueCategories = new java.util.HashSet<>();
        for (SongItem song : activeLibrary) {
            // ❌ existing code: String val = type.equals("ARTIST") ? song.artist : song.album;

            // 🟢 [Fully fixed] Added YEAR and GENRE branches to gather a duplicate-free list of values.
            String val = "Unknown";
            if (type.equals("ARTIST")) val = song.artist;
            else if (type.equals("ALBUM")) val = song.album;
            else if (type.equals("YEAR")) val = song.year;
            else if (type.equals("GENRE")) val = song.genre;

            uniqueCategories.add(val);
        }

        List<String> categories = new ArrayList<>(uniqueCategories);
        // 🚀 [Fix] Sort perfectly in alphabetical order, case-insensitively!
        java.util.Collections.sort(categories, String.CASE_INSENSITIVE_ORDER);
        // 🚀 [Added] Remember the artist/album name for jumping
        // 🚀 [Added] Remember the artist/album name for jumping
        currentScrollIndexList.clear();
        currentScrollIndexList.addAll(categories);
        // 🚀 Push hundreds of artist/album entries into the recycler engine (adapter) too.
        CategoryListAdapter adapter = new CategoryListAdapter(categories, type);
        listVirtualSongs.setAdapter(adapter);

        // 🚀 [Overwrite from here!] Find the name of the previously entered artist/album and compute its index.
        // 🚀 [Fix] Find the name of the previously entered artist/album and compute its index.
        final int targetIndex = categories.indexOf(virtualQueryValue);

        listVirtualSongs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (targetIndex >= 0) {
                    // 1. Instantly pull that position to the very top of the screen! (perfectly pinned)
                    listVirtualSongs.setSelectionFromTop(targetIndex, 0);

                    // 2. After a short delay for the layout to settle, snap the wheel focus precisely onto that cell.
                    listVirtualSongs.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            int visiblePos = targetIndex - listVirtualSongs.getFirstVisiblePosition();
                            if (visiblePos >= 0 && visiblePos < listVirtualSongs.getChildCount()) {
                                listVirtualSongs.getChildAt(visiblePos).requestFocus();
                            }
                        }
                    }, 50);
                } else if (listVirtualSongs.getChildCount() > 0) {
                    listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    } // end of buildVirtualCategories function
      // 💡 [Added] Function that ignores leading special characters in a name and extracts only the pure first letter

    public char getInitialChar(String text) {
        if (text == null || text.isEmpty())
            return '#';
        String clean = text.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "").trim().toUpperCase();
        if (clean.isEmpty())
            return '#';
        return clean.charAt(0);
    }


    // 🚀 [New] Zero-delay, ultra-fast album-art RAM cache memory
    private android.util.LruCache<String, android.graphics.Bitmap> albumArtCache;

    // Menu icons get rescaled with createScaledBitmap on every focus change (the wheel fires this
    // per scroll step), which allocates a fresh bitmap each time even though ThemeManager already
    // caches the decoded source. Cache the scaled result too, keyed by theme+icon+target size.
    private static final android.util.LruCache<String, android.graphics.Bitmap> scaledIconCache = new android.util.LruCache<>(80);

    private android.graphics.Bitmap getScaledThemedIcon(String iconFileName, int size) {
        String key = ThemeManager.getCurrentThemeIndex() + "|" + iconFileName + "|" + size;
        android.graphics.Bitmap cached = scaledIconCache.get(key);
        if (cached != null) return cached;

        android.graphics.Bitmap raw = ThemeManager.getCustomIcon(iconFileName, this, 0);
        if (raw == null) return null;

        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(raw, size, size, true);
        scaledIconCache.put(key, scaled);
        return scaled;
    }
    // 🚀 [Stock 3D engine 1] Screen build
    // 🚀 [Stock 3D engine 1] Screen build (invisibility-cloak bug fully fixed version)
// 🚀 [Stock 3D engine 1] Screen build (dynamic slot allocation version)
    public void buildCoverFlowUI() {
        currentBrowserMode = BROWSER_COVER_FLOW;
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();

        uniqueAlbumList.clear();
        java.util.HashSet<String> seenAlbums = new java.util.HashSet<>();
        List<SongItem> activeLibrary = isAudiobookLibraryMode ? audiobookLibrary : customLibrary;
        for (SongItem song : activeLibrary) {
            if (!seenAlbums.contains(song.album)) {
                seenAlbums.add(song.album);
                uniqueAlbumList.add(song);
            }
        }
        java.util.Collections.sort(uniqueAlbumList, (s1, s2) -> s1.album.compareToIgnoreCase(s2.album));

        if (uniqueAlbumList.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(t("No albums found."));
            tvEmpty.setTextColor(0xFF888888);
            tvEmpty.setGravity(android.view.Gravity.CENTER);
            tvEmpty.setPadding(20, 50, 20, 50);
            containerBrowserItems.addView(tvEmpty);
            return;
        }

        if(currentCoverFlowIndex >= uniqueAlbumList.size()) currentCoverFlowIndex = 0;

        coverFlowContainer = new android.widget.FrameLayout(this);
        coverFlowContainer.setClipChildren(false);
        coverFlowContainer.setClipToPadding(false);

        // 🚀 [Bug fully resolved] Completely suppresses the parent layouts' instinct to 'crop margins'!
        // Now the album image is no longer clipped like a knife even when it extends into the margin/padding zones at the screen edges.
        containerBrowserItems.setClipChildren(false);
        containerBrowserItems.setClipToPadding(false);
        if (scrollViewBrowser instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) scrollViewBrowser).setClipChildren(false);
            ((android.view.ViewGroup) scrollViewBrowser).setClipToPadding(false);
        }

        int height = (int)(320 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams containerLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
        containerLp.topMargin = (int)(15 * getResources().getDisplayMetrics().density);
        coverFlowContainer.setLayoutParams(containerLp);

        // 🚀 [Key] Dynamically spawns as many slots as the configured variable (visibleCoversCount)!
        cfViews = new android.view.View[visibleCoversCount];
        for(int i = 0; i < visibleCoversCount; i++) {
            cfViews[i] = createSingleCoverView();
            coverFlowContainer.addView(cfViews[i]);
        }

        containerBrowserItems.addView(coverFlowContainer);
        initCoverFlowPositions();
    }

    // 🚀 [Stock 3D engine 4] Initial position setup (fully algorithmic now)
    private void initCoverFlowPositions() {
        int total = uniqueAlbumList.size();
        if(total == 0) return;

        int centerIdx = visibleCoversCount / 2;

        // Bind data by calculating the forward/backward indices relative to dead center
        for(int i = 0; i < visibleCoversCount; i++) {
            int offsetFromCenter = i - centerIdx;
            int targetIdx = (currentCoverFlowIndex + offsetFromCenter + total * 3) % total;
            bindCoverData(cfViews[i], targetIdx);
        }

        float d = getResources().getDisplayMetrics().density;

        // 🚀 Mathematical-algorithm loop: scale/coordinate formulas map automatically regardless of view count
        for(int i = 0; i < visibleCoversCount; i++) {
            int dist = Math.abs(i - centerIdx);
            float sign = (i < centerIdx) ? -1f : 1f; // negative (-) to the left, positive (+) to the right

            float transX = sign * getTransXForDist(dist, d);
            float rotY = -sign * getRotYForDist(dist);
            float scale = getScaleForDist(dist);
            float alpha = getAlphaForDist(dist);

            applyTransform(cfViews[i], transX, rotY, scale, alpha);
        }

        arrangeZIndex();

        for(int i = 0; i < visibleCoversCount; i++) {
            setCardTitleAlpha(cfViews[i], i == centerIdx, 0);
        }

        tvBrowserPath.setText(t("Cover Flow") + " (" + (currentCoverFlowIndex + 1) + "/" + total + ")");
    }
    // 🚀 [Algorithm engine] Computes the X-axis travel distance based on distance from center
//    private float getTransXForDist(int dist, float d) {
//        if (dist == 0) return 0f;
//        if (dist == 1) return 110 * d;
//        if (dist == 2) return 180 * d;
//        return 230 * d; // when distance is 3 or more
//    }

    private float getTransXForDist(int dist, float d) {
        if (dist == 0) return 0f;
        if (dist == 1) return 130 * d;
        if (dist == 2) return 170 * d;
        return 220 * d; // when distance is 3 or more
    }

    // 🚀 The higher the number, the sharper the angle, like books tilted on a bookshelf!
//    private float getRotYForDist(int dist) {
//        if (dist == 0) return 0f;
//        if (dist == 1) return 60f;  // 💡 45 degrees -> a deeper 60 degrees!
//        if (dist == 2) return 75f;  // 💡 60 degrees -> a deeper 75 degrees!
//        return 80f;
//    }
    private float getRotYForDist(int dist) {
        if (dist == 0) return 0f;
        if (dist == 1) return 65f;  // 💡 45 degrees -> a deeper 60 degrees!
//        if (dist == 2) return 75f;  // 💡 60 degrees -> a deeper 75 degrees!
        return 65f;
    }
    // 🚀 [Algorithm engine] Computes the size-shrink ratio based on distance from center
    private float getScaleForDist(int dist) {
        if (dist == 0) return 1.0f;
        if (dist == 1) return 0.8f;
        if (dist == 2) return 0.8f;
        return 0.8f;
    }
    // 🚀 [Algorithm engine] Computes opacity based on distance from center
//    private float getAlphaForDist(int dist) {
//        if (dist == 0) return 1.0f;
//        if (dist == 1) return 0.8f;
//        if (dist == 2) return 0.5f;
//        return 0.1f;
//    }
    private float getAlphaForDist(int dist) {
//        if (dist == 0) return 1.0f;
//        if (dist == 1) return 0.8f;
//        if (dist == 2) return 0.5f;
        return 1f;
    }
    // 🚀 [Stock 3D engine 3] Data binding with a forced 3D lookup back through the cache folder!
    // 🚀 [Hybrid 3D binding engine] Links the original and reflection images with the RAM cache to maintain 60fps scrolling.
    private void bindCoverData(View card, int dataIndex) {
        if(uniqueAlbumList.isEmpty() || dataIndex < 0 || dataIndex >= uniqueAlbumList.size()) return;
        SongItem item = uniqueAlbumList.get(dataIndex);

        final ImageView ivCover = card.findViewById(1001);
        final ImageView ivReflection = card.findViewById(1004); // 🚀 Obtain the reflection layer
        TextView tvTitle = card.findViewById(1002);
        TextView tvArtist = card.findViewById(1003);

        tvTitle.setText(item.album);
        tvArtist.setText(item.artist);

        final String path = item.file.getAbsolutePath();
        ivCover.setTag(path); // Fully blocks async race conditions

        // 1. Search the ultra-fast RAM cache vault (searches for both the original and reflection image sets at once)
        android.graphics.Bitmap cachedBmp = null;
        android.graphics.Bitmap cachedRef = null;
        if (albumArtCache != null) {
            cachedBmp = albumArtCache.get(path);
            cachedRef = albumArtCache.get("ref_" + path);
        }

        if (cachedBmp != null) {
            // 💡 If both are in the RAM vault, double-bind them instantly in 0.0001 seconds!
            ivCover.setImageBitmap(cachedBmp);
            if (ivReflection != null) {
                ivReflection.setImageBitmap(cachedRef);
                ivReflection.setVisibility(cachedRef != null ? View.VISIBLE : View.INVISIBLE);
            }
            return;
        }

        // If not cached, bind a blank placeholder canvas and dispatch a worker thread
        ivCover.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", MainActivity.this, R.drawable.default_album));
        if (ivReflection != null) ivReflection.setImageBitmap(null);

        new Thread(new Runnable() {
            @Override
            public void run() {
                android.graphics.Bitmap bmp = null;
                String cachedArtPath = libraryCacheDb.getAlbumArtPath(path);

                if (cachedArtPath != null && new File(cachedArtPath).exists()) {
                    bmp = BitmapFactory.decodeFile(cachedArtPath);
                } else {
                    try {
                        String songName = item.file.getName();
                        int dot = songName.lastIndexOf(".");
                        if (dot > 0) songName = songName.substring(0, dot);

                        File fallbackFile = new File("/storage/sdcard0/Y1_Covers", songName + ".jpg");
                        if (fallbackFile.exists()) {
                            bmp = BitmapFactory.decodeFile(fallbackFile.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "bindCoverData failed", e);
                    }
                }

                if (bmp == null) {
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        mmr.setDataSource(path);
                        byte[] embeddedArt = mmr.getEmbeddedPicture();
                        mmr.release();
                        if (embeddedArt != null) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            bmp = BitmapFactory.decodeByteArray(embeddedArt, 0, embeddedArt.length, opts);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "bindCoverData failed", e);
                    }
                }

                final android.graphics.Bitmap finalBmp = bmp;

                // 🚀 [Important] The reflection is generated on this background thread, not the main thread, so there is 0% performance overhead!
                final android.graphics.Bitmap finalRef = getReflectionBitmap(finalBmp);

                // Store the original and reflection images side by side in the RAM vault for the next lookup
                if (finalBmp != null && albumArtCache != null) {
                    albumArtCache.put(path, finalBmp);
                    if (finalRef != null) {
                        albumArtCache.put("ref_" + path, finalRef);
                    }
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Only render to the screen if the target hasn't changed to a different track while the wheel was spinning
                        if (path.equals(ivCover.getTag())) {
                            if (finalBmp != null) ivCover.setImageBitmap(finalBmp);
                            if (ivReflection != null) {
                                ivReflection.setImageBitmap(finalRef);
                                ivReflection.setVisibility(finalRef != null ? View.VISIBLE : View.INVISIBLE);
                            }
                        }
                    }
                });
            }
        }).start();
    }
    // 🚀 [Bug completely eliminated] Fixes the cover-image clipping issue at its root by locking the overall frame margin to 0.
    private View createSingleCoverView() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                (int)(350 * d), (int)(320 * d));
        lp.gravity = android.view.Gravity.CENTER;

        // 🚀 [Core fix] Changed the existing -25 value to '0'! This stops the cover image from being forced to bounce upward.
        lp.topMargin = 0;

        card.setLayoutParams(lp);

        ImageView ivCover = new ImageView(this);
        ivCover.setId(1001);
        android.widget.LinearLayout.LayoutParams imgLp = new android.widget.LinearLayout.LayoutParams((int)(200 * d), (int)(200 * d));
        imgLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        ivCover.setLayoutParams(imgLp);
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCover.setBackground(createButtonBackground(0x00000000));

        ImageView ivReflection = new ImageView(this);
        ivReflection.setId(1004);
        android.widget.LinearLayout.LayoutParams refLp = new android.widget.LinearLayout.LayoutParams((int)(200 * d), (int)(50 * d));
        refLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        refLp.topMargin = (int)(2 * d);
        ivReflection.setLayoutParams(refLp);
        ivReflection.setScaleType(ImageView.ScaleType.CENTER_CROP);

        TextView tvTitle = new TextView(this);
        tvTitle.setId(1002);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setAlpha(0f);

        android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        // 🚀 Positions the album title with a nice gap directly under the reflection image only.
        titleLp.topMargin = (int)(-40 * d);
        titleLp.bottomMargin = (int)(0 * d);
        tvTitle.setLayoutParams(titleLp);

        TextView tvArtist = new TextView(this);
        tvArtist.setId(1003);
        tvArtist.setTextColor(ThemeManager.getTextColorSecondary());
        tvArtist.setTextSize(14f);
        tvArtist.setGravity(android.view.Gravity.CENTER);
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvArtist.setAlpha(0f);

        android.widget.LinearLayout.LayoutParams artistLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = (int)(2 * d);
        tvArtist.setLayoutParams(artistLp);

        card.addView(ivCover);
        card.addView(ivReflection);
        card.addView(tvTitle);
        card.addView(tvArtist);

        card.setOnClickListener(v -> {
            int centerIdx = visibleCoversCount / 2;
            if (v == cfViews[centerIdx] && currentCoverFlowIndex >= 0 && currentCoverFlowIndex < uniqueAlbumList.size()) {
                clickFeedback();
                SongItem chosen = uniqueAlbumList.get(currentCoverFlowIndex);
                currentBrowserMode = BROWSER_VIRTUAL_SONGS;
                virtualQueryType = "COVER_FLOW_ALBUM";
                virtualQueryValue = chosen.album;
                buildVirtualSongs();
            }
        });

        return card;
    }
    // 🚀 [Reflection graphics parser] Flips the original upside down, applies a gradient mask, and renders it in stock Jelly Bean style.
    private android.graphics.Bitmap getReflectionBitmap(android.graphics.Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int reqH = h / 4; // Use only the bottom 25% of the original as the reflection area
            if (reqH <= 0) return null;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.preScale(1, -1); // Apply a vertical-flip matrix

            // Crop just the bottom part and generate a flipped bitmap
            android.graphics.Bitmap flipped = android.graphics.Bitmap.createBitmap(src, 0, h - reqH, w, reqH, matrix, false);
            android.graphics.Bitmap reflection = android.graphics.Bitmap.createBitmap(w, reqH, android.graphics.Bitmap.Config.ARGB_8888);

            android.graphics.Canvas canvas = new android.graphics.Canvas(reflection);
            canvas.drawBitmap(flipped, 0, 0, null);
            flipped.recycle();

            // Paint a gradient mask that fades away toward the bottom
            android.graphics.Paint paint = new android.graphics.Paint();
            android.graphics.LinearGradient shader = new android.graphics.LinearGradient(
                    0, 0, 0, reqH,
                    0x44FFFFFF, 0x00FFFFFF, // Starts at ~25% subtle reflection opacity -> fully transparent at 0%
                    android.graphics.Shader.TileMode.CLAMP);
            paint.setShader(shader);
            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            canvas.drawRect(0, 0, w, reqH, paint);

            return reflection;
        } catch (Exception e) {
            return null;
        }
    }
    // 🚀 [Helper function 4] Only the card that's centered shows its title; cards pushed to the side hide theirs!
    private void setCardTitleAlpha(View card, boolean isCenter, int duration) {
        View tvTitle = card.findViewById(1002);
        View tvArtist = card.findViewById(1003);
        if (tvTitle != null && tvArtist != null) {
            float targetAlpha = isCenter ? 1.0f : 0.0f; // 100% if centered, 0% otherwise
            if (duration > 0) {
                tvTitle.animate().alpha(targetAlpha).setDuration(duration).start();
                tvArtist.animate().alpha(targetAlpha).setDuration(duration).start();
            } else {
                tvTitle.setAlpha(targetAlpha);
                tvArtist.setAlpha(targetAlpha);
            }
        }
    }
    // 🚀 [Helper function 1] Function that instantly moves and transforms a view
    private void applyTransform(View v, float transX, float rotY, float scale, float alpha) {
        v.setTranslationX(transX);
        v.setRotationY(rotY);
        v.setScaleX(scale); v.setScaleY(scale);
        v.setAlpha(alpha);
    }

    private void animateTransform(View v, float transX, float rotY, float scale, float alpha, int duration) {
        v.animate().translationX(transX).rotationY(rotY).scaleX(scale).scaleY(scale).alpha(alpha).setDuration(duration).start();
    }

    // 🚀 [Depth engine] Stacks cards in order from the outermost to dead center, according to the configured count.
    private void arrangeZIndex() {
        int centerIdx = visibleCoversCount / 2;

        // Bring-to-front the cards in reverse order, from the farthest distance down to dead center (0)
        for (int d = centerIdx; d >= 0; d--) {
            int leftViewIdx = centerIdx - d;
            int rightViewIdx = centerIdx + d;

            if (leftViewIdx >= 0) cfViews[leftViewIdx].bringToFront();
            if (rightViewIdx < visibleCoversCount) cfViews[rightViewIdx].bringToFront();
        }

        for(int i = 0; i < visibleCoversCount; i++) cfViews[i].invalidate();
        coverFlowContainer.invalidate();
    }

    private long lastCoverFlowTime = 0; // 🚀 Time-machine variable for smart speed shifting

    // 🚀 [Stock 3D engine 5] Ultra-fast sliding engine (fully variable-count-aware geometry)
    public void scrollCoverFlow(boolean isNext) {
        int total = uniqueAlbumList.size();
        if(total == 0) return;

        float d = getResources().getDisplayMetrics().density;
        int centerIdx = visibleCoversCount / 2;

        long now = System.currentTimeMillis();
        long diff = now - lastCoverFlowTime;
        lastCoverFlowTime = now;
        int duration = (diff < 80) ? 30 : 180;

        if (isNext) {
            currentCoverFlowIndex = (currentCoverFlowIndex + 1) % total;
            View oldLeft = cfViews[0];

            // 🚀 Dynamic index shuffling
            for(int i = 0; i < visibleCoversCount - 1; i++) cfViews[i] = cfViews[i+1];
            cfViews[visibleCoversCount - 1] = oldLeft;

            bindCoverData(cfViews[visibleCoversCount - 1], (currentCoverFlowIndex + centerIdx + total * 3) % total);

            float maxOff = getTransXForDist(centerIdx, d);
            float maxRot = getRotYForDist(centerIdx);
            float maxScale = getScaleForDist(centerIdx);
            applyTransform(cfViews[visibleCoversCount - 1], maxOff * 1.5f, -maxRot, maxScale, 0f);
        } else {
            currentCoverFlowIndex = (currentCoverFlowIndex - 1 + total) % total;
            View oldRight = cfViews[visibleCoversCount - 1];

            for(int i = visibleCoversCount - 1; i > 0; i--) cfViews[i] = cfViews[i-1];
            cfViews[0] = oldRight;

            bindCoverData(cfViews[0], (currentCoverFlowIndex - centerIdx + total * 3) % total);

            float maxOff = getTransXForDist(centerIdx, d);
            float maxRot = getRotYForDist(centerIdx);
            float maxScale = getScaleForDist(centerIdx);
            applyTransform(cfViews[0], -maxOff * 1.5f, maxRot, maxScale, 0f);
        }

        arrangeZIndex();

        // 🚀 Fire off the full dynamic-slot animation barrage!
        for(int i = 0; i < visibleCoversCount; i++) {
            setCardTitleAlpha(cfViews[i], i == centerIdx, duration);

            int dist = Math.abs(i - centerIdx);
            float sign = (i < centerIdx) ? -1f : 1f;

            float transX = sign * getTransXForDist(dist, d);
            float rotY = -sign * getRotYForDist(dist);
            float scale = getScaleForDist(dist);
            float alpha = getAlphaForDist(dist);

            animateTransform(cfViews[i], transX, rotY, scale, alpha, duration);
        }

        tvBrowserPath.setText(t("Cover Flow") + " (" + (currentCoverFlowIndex + 1) + "/" + total + ")");
    }
    // 💡 4. Function that pulls songs from the local DB and pushes them into the 'recycler engine'
    public void buildVirtualSongs() {
        // Only block on a scan that's still in progress if there's no cached library to show
        // yet at all — a background reconciliation scan shouldn't hide already-available data.
        if (isCustomScanning && customLibrary.isEmpty() && audiobookLibrary.isEmpty()) {
            showLoadingPopup(); // 🚀 Show a large spinner popup instead of hard-to-see text!
            currentBrowserMode = BROWSER_ROOT;
            buildFileBrowserUI();
            return;
        }
        // 🚀 Turn off the existing bloated, slow ScrollView and turn on the ultra-fast ListView!
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);

        // 🚀 [Fix] Changed so the top header title displays correctly for both all-music and all-audiobooks mode!
        if (virtualQueryType.equals("ALL")) {
            tvBrowserPath.setText(t("Library") + ": " + (isAudiobookLibraryMode ? t("All Audiobooks") : t("All Songs")));
        } else {
            tvBrowserPath.setText(t("Library") + ": " + virtualQueryValue); // Output the artist/album name as-is
        }
        virtualSongList.clear();
        currentScrollIndexList.clear(); // 🚀 [Added] Reset the existing index
        final List<SongItem> targetSongs = new ArrayList<>();

        // 🚀 Swap the bucket to search through depending on the switch!
        List<SongItem> activeLibrary = isAudiobookLibraryMode ? audiobookLibrary : customLibrary;

        for (SongItem song : activeLibrary) {
            if (virtualQueryType.equals("ALL") ||
                    (virtualQueryType.equals("ARTIST") && song.artist.equals(virtualQueryValue)) ||
                    (virtualQueryType.equals("ALBUM") && song.album.equals(virtualQueryValue)) ||
                    (virtualQueryType.equals("COVER_FLOW_ALBUM") && song.album.equals(virtualQueryValue)) || // 🚀 [2 lines added below!]
                    (virtualQueryType.equals("YEAR") && song.year.equals(virtualQueryValue)) ||
                    (virtualQueryType.equals("GENRE") && song.genre.equals(virtualQueryValue))) {
                targetSongs.add(song);
            }
        }

        java.util.Collections.sort(targetSongs, new java.util.Comparator<SongItem>() {
            @Override
            public int compare(SongItem s1, SongItem s2) {
                // 🚀 [Path-recovery 2] Also add sorting by track number for the cover-flow-dedicated tag.
                if ("ALBUM".equals(virtualQueryType) || "COVER_FLOW_ALBUM".equals(virtualQueryType)) {
                    int t1 = trackNumberMap.containsKey(s1.file.getAbsolutePath()) ? trackNumberMap.get(s1.file.getAbsolutePath()) : 0;
                    int t2 = trackNumberMap.containsKey(s2.file.getAbsolutePath()) ? trackNumberMap.get(s2.file.getAbsolutePath()) : 0;

                    if (t1 != t2) {
                        return Integer.valueOf(t1).compareTo(t2);
                    }
                }
                return s1.title.compareToIgnoreCase(s2.title);
            }
        });
        // ... (rest below unchanged)

        // 🚀 Fill the actual playback list and the fast-scroll index in the sorted order.
        for (SongItem song : targetSongs) {
            virtualSongList.add(song.file);
            currentScrollIndexList.add(song.title);
        }

        // 🚀 Load thousands of tracks' worth of data into the recycler engine (adapter).

        // 🚀 Load thousands of tracks' worth of data into the recycler engine (adapter).
        SongListAdapter adapter = new SongListAdapter(targetSongs);
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (listVirtualSongs.getChildCount() > 0) {
                    listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private void buildFolderBrowserUI() {
        containerBrowserItems.removeAllViews();
        tvBrowserPath.setText(t("Path") + ": " + currentFolder.getAbsolutePath().replace("/storage/sdcard0", ""));
        File[] files = currentFolder.listFiles();

        if (files == null || files.length == 0) {
            Button btnEmpty = createListButton(files == null ? "⚠️ " + t("USB Disconnect Required (Tap to go back)") : "📂 " + t("Empty Folder (Tap to go back)"));
            btnEmpty.setTextColor(0xFFFF5555);
            btnEmpty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    // 🚀 [Fix] Handle the whole-folder case here too
                    if (currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath()) || currentFolder.getAbsolutePath().equals("/storage/sdcard0")) {
                        if (isPickingBackground) {
                            isPickingBackground = false;
                            changeScreen(STATE_SETTINGS);
                            buildBackgroundSettingsUI();
                        } else {
                            currentBrowserMode = BROWSER_ROOT;
                            buildFileBrowserUI();
                        }
                    } else {
                        currentFolder = currentFolder.getParentFile();
                        buildFileBrowserUI();
                    }
                }
            });
            containerBrowserItems.addView(btnEmpty);
            return;
        }

        List<File> folders = new ArrayList<File>();
        List<File> audioFiles = new ArrayList<File>();
        List<File> apkFiles = new ArrayList<File>();
        List<File> imageFiles = new ArrayList<File>();

        for (File f : files) {
            if (f.isDirectory())
                folders.add(f);
            else if (isPickingBackground && isImageFile(f))
                imageFiles.add(f);
            else if (!isPickingBackground && isAudioFile(f))
                audioFiles.add(f);
            else if (!isPickingBackground && isApkFile(f))
                apkFiles.add(f);
        }
        // 🚀 [10 new lines added here!!] Sort the collected files and folders by name (alphabetical A-Z, case-insensitive)!
        java.util.Comparator<File> fileSorter = new java.util.Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        };
        java.util.Collections.sort(folders, fileSorter);
        java.util.Collections.sort(audioFiles, fileSorter);
        java.util.Collections.sort(apkFiles, fileSorter);
        java.util.Collections.sort(imageFiles, fileSorter);
        // 🚀🚀🚀 [Add here!] If the current folder has even one audio file, create a 'Play All' button at the top
        if (!isPickingBackground && (audioFiles.size() > 0 || folders.size() > 0)) {
            Button btnPlayAll = createListButton("▶ " + t("Play All"));
            btnPlayAll.setTextColor(0xFFFFFFFF); // green!
            btnPlayAll.setTypeface(null, android.graphics.Typeface.BOLD);

            btnPlayAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();

                    // 1. Prepare a large empty bucket to sweep up subfolders too
                    final List<File> allAudioInFolder = new ArrayList<>();

                    // 2. In case there are a lot of files, lock the wheel and show a popup!
                    showLoadingPopup();

                    // 3. Run the background engine (to avoid freezing the system)
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // Call the vacuum-cleaner function we just built!
                            collectAudioFilesAsFile(currentFolder, allAudioInFolder);

                            // Sort the collected files neatly by name (reusing the existing fileSorter)
                            java.util.Collections.sort(allAudioInFolder, fileSorter);

                            // 4. Once the collection is done, come back to the screen and issue the play command.
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Close the loading popup
                                    if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(View.GONE);

                                    if (allAudioInFolder.isEmpty()) {
                                        Toast.makeText(MainActivity.this, t("No audio files found in subfolders."), Toast.LENGTH_SHORT).show();
                                    } else {
//                                        Toast.makeText(MainActivity.this, "Loaded " + allAudioInFolder.size() + " songs!", Toast.LENGTH_SHORT).show();
                                        com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(allAudioInFolder, 0); // Start playing right from track 0!

                                        // 🚀 [Fix] Automatically switch to the player screen after Play All!
                                        changeScreen(STATE_PLAYER);
                                    }
                                }
                            });
                        }
                    }).start();
                }
            });
            containerBrowserItems.addView(btnPlayAll);
        }
        // 🚀🚀🚀 [End of addition]
        for (final File folder : folders) {
            android.view.View b = createListButtonWithIcon("\uE2C7",folder.getName());
         //   Button b = createListButton("📁 " + folder.getName());
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    currentFolder = folder;
                    buildFileBrowserUI();
                }
            });
            containerBrowserItems.addView(b);
        }

        if (isPickingBackground) {
            for (final File img : imageFiles) {
//                Button b = createListButton("🖼 " + img.getName());
                android.view.View b = createListButtonWithIcon("\uE410",img.getName());
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        try {
                            prefs.edit().putString("bg_path", img.getAbsolutePath()).apply();
                        } catch (Exception e) {
                            Log.d(TAG, "buildFolderBrowserUI failed", e);
                        }

                        updateMainMenuBackground(); // 💡 Apply the blur to the main screen immediately on selection

                        Toast.makeText(MainActivity.this, t("Background Applied!"), Toast.LENGTH_SHORT).show();
                        isPickingBackground = false;
                        changeScreen(STATE_SETTINGS);
                        buildBackgroundSettingsUI();
                    }
                });
                containerBrowserItems.addView(b);
            }
        } else {
            for (final File apk : apkFiles) {
                Button b = createListButton("📦 [" + t("INSTALL") + "] " + apk.getName());
                b.setTextColor(0xFF00FFFF);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        installApk(apk);
                    }
                });
                containerBrowserItems.addView(b);
            }
            for (final File audio : audioFiles) {
                Button b = createListButton("🎵 " + audio.getName());

                // 🚀 [Added] Draw the progress bar if we're in audiobook mode or inside an audiobook folder!
                if (isAudiobookLibraryMode || currentBrowserMode == BROWSER_AUDIOBOOKS) {
                    com.themoon.y1.db.LibraryCacheDb.Bookmark bm = libraryCacheDb.getBookmark(audio.getAbsolutePath());
                    if (bm != null && bm.posMs > 0 && bm.durMs > 0) {
                        setupAudiobookProgress(b, bm.posMs, bm.durMs); // 💡 Replaced with a call to the new engine!
                    }
                }

                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clickFeedback();
                        if (currentBrowserMode == BROWSER_AUDIOBOOKS) {
                            com.themoon.y1.managers.AudiobookManager.getInstance(MainActivity.this).setupBookPlaylist(MainActivity.this, audio, currentFolder);
                        } else {
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().setupFolderPlaylist(audio, currentFolder);
                        }
                        // 🚀 [Fix] Automatically switch to the player screen when playing an individual song from a folder!
                        changeScreen(STATE_PLAYER);
                    }
                });

                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        clickFeedback();
                        isLongPressConsumed = true; // 🚀 [Bug fix] Force-enable the long-press guard the instant the popup appears!
                        showAddToPlaylistDialog(audio);
                        return true;
                    }
                });
                containerBrowserItems.addView(b);
            }
        }
        if (containerBrowserItems.getChildCount() > 0)
            containerBrowserItems.getChildAt(0).requestFocus();

        // 🚀 [Added] Once the screen finishes drawing (50ms later), find the folder/menu that just appeared and auto-focus it!
        containerBrowserItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                if (!lastBrowserFocusText.isEmpty()) {
                    for (int i = 0; i < containerBrowserItems.getChildCount(); i++) {
                        View v = containerBrowserItems.getChildAt(i);
                        boolean isMatch = (v instanceof Button && ((Button) v).getText().toString().equals(lastBrowserFocusText))
                                || lastBrowserFocusText.equals(v.getTag());
                        if (isMatch) {
                            v.requestFocus();
                            if (containerBrowserItems.getParent() instanceof android.widget.ScrollView) {
                                ((android.widget.ScrollView) containerBrowserItems.getParent())
                                        .requestChildFocus(containerBrowserItems, v);
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found && containerBrowserItems.getChildCount() > 0) {
                    containerBrowserItems.getChildAt(0).requestFocus(); // Fall back to the top if not found
                }
                lastBrowserFocusText = ""; // 🚀 One-shot, so reset the memory right after use
            }
        }, 50);
    }
    // 🚀 [Added tool 1] Function that translates an English gravity string into something Android understands
    private int parseGravity(String gravityStr) {
        int g = android.view.Gravity.TOP | android.view.Gravity.LEFT; // default value
        if (gravityStr == null || gravityStr.isEmpty()) return g;
        gravityStr = gravityStr.toLowerCase();
        g = 0;
        if (gravityStr.contains("top")) g |= android.view.Gravity.TOP;
        if (gravityStr.contains("bottom")) g |= android.view.Gravity.BOTTOM;
        if (gravityStr.contains("center_vertical")) g |= android.view.Gravity.CENTER_VERTICAL;
        if (gravityStr.contains("left")) g |= android.view.Gravity.LEFT;
        if (gravityStr.contains("right")) g |= android.view.Gravity.RIGHT;
        if (gravityStr.contains("center_horizontal")) g |= android.view.Gravity.CENTER_HORIZONTAL;
        if (gravityStr.equals("center")) g = android.view.Gravity.CENTER; // perfectly centered

        if (g == 0) g = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        return g;
    }
    // 🚀 [Added tool 3] Factory that converts JSON X, Y, width, height, and alignment into absolute-coordinate layout params!
    private android.widget.FrameLayout.LayoutParams createDynamicLayoutParams(ThemeManager.MenuElement el, float density) {
        int w = el.width > 0 ? (int)(el.width * density) : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        int h = el.height > 0 ? (int)(el.height * density) : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(w, h);
        lp.gravity = parseGravity(el.gravity);

        // Intelligently apply the X, Y margins according to the alignment (gravity).
        if ((lp.gravity & android.view.Gravity.RIGHT) == android.view.Gravity.RIGHT) lp.rightMargin = (int)(el.x * density);
        else lp.leftMargin = (int)(el.x * density);

        if ((lp.gravity & android.view.Gravity.BOTTOM) == android.view.Gravity.BOTTOM) lp.bottomMargin = (int)(el.y * density);
        else lp.topMargin = (int)(el.y * density);

        return lp;
    }
    // 🚀 [Added tool 2] Function that smartly blends the theme's default corner radius with an individual button's corner radius
    private android.graphics.drawable.GradientDrawable createDynamicButtonBackground(int color, int elementRadius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        // If the JSON specifies an individual radius (not -1), use it; otherwise fall back to the theme default!
        float r = (elementRadius == -1 ? ThemeManager.getButtonRadius() : elementRadius) * getResources().getDisplayMetrics().density;
        shape.setCornerRadius(r);
        return shape;
    }
    // 🚀 [Added tool 4] Widget-specific: builds a nice box with the given background color and corner radius.
    private android.graphics.drawable.GradientDrawable createWidgetBackground(String bgColorStr, int elementRadius) {
        if (bgColorStr == null || bgColorStr.trim().isEmpty()) return null;
        int color;
        try { color = android.graphics.Color.parseColor(bgColorStr.trim()); }
        catch (Exception e) { return null; }

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        // If the JSON doesn't specify a radius, follow the theme's default radius.
        float r = (elementRadius == -1 ? ThemeManager.getButtonRadius() : elementRadius) * getResources().getDisplayMetrics().density;
        shape.setCornerRadius(r);
        return shape;
    }
    // 🚀 [Focus control fully conquered] Spinning the wheel ignores physical layout and cycles strictly through the JSON index order!
    private void buildDynamicMainMenuUI() {
        android.view.ViewGroup mainMenu = (android.view.ViewGroup) layoutMainMenu;
        // 🚀 [Status-bar shield activated!!]
        // Reads the 'top margin (status bar height)' that was in the existing XML skeleton.
        int safeTopPadding = mainMenu.getPaddingTop();

        // If the existing margin couldn't be read, force a fallback shield using Android's default status bar height of 24dp!
        if (safeTopPadding == 0) {
            safeTopPadding = (int)(24 * getResources().getDisplayMetrics().density);
        }

        // Reapply the padding as left(0), top(shield), right(0), bottom(0).
        mainMenu.setPadding(0, safeTopPadding, 0, 0);

        for (int i = 0; i < mainMenu.getChildCount(); i++) {
            mainMenu.getChildAt(i).setVisibility(View.GONE);
        }

        // 🚀 [Bug fix: ghost-view residue eliminated]
        android.view.View oldCanvas = mainMenu.findViewWithTag("dynamic_canvas");
        while (oldCanvas != null) {
            if (oldCanvas instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) oldCanvas).removeAllViews();
            }
            mainMenu.removeView(oldCanvas);
            oldCanvas = mainMenu.findViewWithTag("dynamic_canvas");
        }

        // 🚀 [Type-declaration restored] Add the canvas variable declaration back!
        android.widget.FrameLayout canvas = new android.widget.FrameLayout(this);
        canvas.setTag("dynamic_canvas");
        canvas.setBackgroundColor(ThemeManager.getOverlayBackgroundColor());

        // 🚀 [Core fix 3] Unseal so icons can pop out large even at the canvas level.
        canvas.setClipChildren(false);
        canvas.setClipToPadding(false);

        mainMenu.addView(canvas, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        tvWidgetClock = null; widgetBatteryView = null; ivWidgetAlbum = null;
        tvWidgetAlbumTitle = null; tvWidgetAlbumArtist = null; ivWidgetFocusImage = null; // 🚀 Added reset

        final float density = getResources().getDisplayMetrics().density;
        List<ThemeManager.MenuElement> elements = ThemeManager.getCurrentTheme().menuElements;

        List<ThemeManager.MenuElement> buttonElements = new ArrayList<>();
        List<ThemeManager.MenuElement> widgetElements = new ArrayList<>();

        for (ThemeManager.MenuElement el : elements) {
            if (el.type.equals("button")) buttonElements.add(el);
            else widgetElements.add(el);
        }

        java.util.Collections.sort(buttonElements, new java.util.Comparator<ThemeManager.MenuElement>() {
            @Override
            public int compare(ThemeManager.MenuElement e1, ThemeManager.MenuElement e2) {
                return e1.focusIndex - e2.focusIndex;
            }
        });

        // 🚀 [New unified vault] Central-command memory that stores the address and JSON info of every widget created
// 🚀 [Bug fix] Reset and reuse the existing global-variable vault! (removed the final local-variable declaration)
        widgetViewRegistry.clear();
        final java.util.HashMap<String, LinearLayout> listContainers = new java.util.HashMap<>();

        // 💡 Draw widgets
        for (ThemeManager.MenuElement el : widgetElements) {
            android.graphics.drawable.GradientDrawable widgetBg = createWidgetBackground(el.bgColor, el.radius);
            int p = (int)(el.padding * density);
            View createdWidgetView = null; // 🚀 Widget reference variable

            if (el.type.equals("list_box")) {
                final android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.setLayoutParams(createDynamicLayoutParams(el, density));
                sv.setVerticalScrollBarEnabled(false);
                sv.setFocusable(false); sv.setFocusableInTouchMode(false);
                sv.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                if (widgetBg != null) sv.setBackground(widgetBg);
                sv.setVisibility(View.VISIBLE); // 🚀 Always keep the shell (list box) open.
                sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        android.view.ViewParent p = sv.getParent();
                        if (p instanceof android.view.View) ((android.view.View) p).invalidate();
                        sv.invalidate();
                    }
                });

                LinearLayout innerLayout = new LinearLayout(this);
                innerLayout.setOrientation(LinearLayout.VERTICAL);
                innerLayout.setPadding(p, p, p, p);
                sv.addView(innerLayout, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                canvas.addView(sv);
                listContainers.put(el.id, innerLayout);
                createdWidgetView = sv;
            }
            else if (el.type.equals("box")) {
                ImageView boxView = new ImageView(this);
                boxView.setLayoutParams(createDynamicLayoutParams(el, density));
                if (widgetBg == null) widgetBg = createWidgetBackground("#00000000", el.radius);
                boxView.setBackground(widgetBg);

                String imgName = (el.iconNormal != null && !el.iconNormal.isEmpty()) ? el.iconNormal : el.textNormal;
                if (imgName != null && !imgName.isEmpty() && !imgName.equals("New Item")) {
                    android.graphics.Bitmap bmp = ThemeManager.getCustomIcon(imgName, MainActivity.this, 0);
                    if (bmp != null) {
                        int maxTexSize = 2048;
                        if (bmp.getWidth() > maxTexSize || bmp.getHeight() > maxTexSize) {
                            float ratio = Math.min((float)maxTexSize / bmp.getWidth(), (float)maxTexSize / bmp.getHeight());
                            boxView.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, (int)(bmp.getWidth() * ratio), (int)(bmp.getHeight() * ratio), true));
                        } else boxView.setImageBitmap(bmp);
                        boxView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    }
                }
                canvas.addView(boxView);
                createdWidgetView = boxView;
            }
            else if (el.type.equals("widget_clock")) {
                tvWidgetClock = new TextView(this);
                tvWidgetClock.setGravity(android.view.Gravity.CENTER);
                tvWidgetClock.setLayoutParams(createDynamicLayoutParams(el, density));
                tvWidgetClock.setTextColor(ThemeManager.getTextColorPrimary());
                tvWidgetClock.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                currentClockSize = el.textSize > 0 ? el.textSize : 48f;
                tvWidgetClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, currentClockSize * density);
                if (widgetBg != null) tvWidgetClock.setBackground(widgetBg);
                tvWidgetClock.setPadding(p, p, p, p);

                canvas.addView(tvWidgetClock); // 🚀 Restored as a direct child of the canvas!
                createdWidgetView = tvWidgetClock;
            }
            else if (el.type.equals("widget_battery")) {
                widgetBatteryView = new WidgetBatteryBarView(this);
                widgetBatteryView.setLayoutParams(createDynamicLayoutParams(el, density));
                widgetBatteryView.setPadding(p, p, p, p);
                canvas.addView(widgetBatteryView);
                createdWidgetView = widgetBatteryView;
            }
            else if (el.type.equals("widget_album")) {
                LinearLayout albumContainer = new LinearLayout(this);
                layoutWidgetAlbumContainer = albumContainer;
                boolean isHorizontal = el.textPosition.equalsIgnoreCase("left") || el.textPosition.equalsIgnoreCase("right");
                albumContainer.setOrientation(isHorizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                albumContainer.setGravity(android.view.Gravity.CENTER);
                albumContainer.setLayoutParams(createDynamicLayoutParams(el, density));
                if (widgetBg != null) albumContainer.setBackground(widgetBg);
                albumContainer.setPadding(p, p, p, p);

                ivWidgetAlbum = new ImageView(this);
                ivWidgetAlbum.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int pSubtract = el.padding * 2;
                int imgSize = isHorizontal ? (int)((el.height - pSubtract) * density) : (int)((el.height - pSubtract) * 0.65f * density);
                if(imgSize <= 0) imgSize = (int)(110 * density);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);

                LinearLayout textContainer = new LinearLayout(this);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                int textGravity = el.textAlign.equalsIgnoreCase("left") ? (android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL) : (el.textAlign.equalsIgnoreCase("right") ? (android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
                textContainer.setGravity(textGravity);
                LinearLayout.LayoutParams textContainerLp = isHorizontal ? new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f) : new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);

                int safeWidth = el.width > 0 ? (int)(el.width * density) : (int)(200 * density);
                int availableWidth = isHorizontal ? (safeWidth - imgSize - (int)(15 * density) - (p * 2)) : (safeWidth - (p * 2));
                if (availableWidth <= 0) availableWidth = (int)(150 * density);
                LinearLayout.LayoutParams textViewLp = new LinearLayout.LayoutParams(availableWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

                tvWidgetAlbumTitle = new TextView(this);
                tvWidgetAlbumTitle.setLayoutParams(textViewLp); tvWidgetAlbumTitle.setGravity(textGravity);
                tvWidgetAlbumTitle.setSingleLine(true); tvWidgetAlbumTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvWidgetAlbumTitle.setTextColor(ThemeManager.getTextColorPrimary());
                tvWidgetAlbumTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                tvWidgetAlbumTitle.setTextSize(el.textSize > 0 ? el.textSize : 16);
                textContainer.addView(tvWidgetAlbumTitle);

                tvWidgetAlbumArtist = new TextView(this);
                tvWidgetAlbumArtist.setLayoutParams(textViewLp); tvWidgetAlbumArtist.setGravity(textGravity);
                tvWidgetAlbumArtist.setSingleLine(true); tvWidgetAlbumArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvWidgetAlbumArtist.setTextColor(ThemeManager.getTextColorSecondary());
                tvWidgetAlbumArtist.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
                tvWidgetAlbumArtist.setTextSize(el.textSecondarySize > 0 ? el.textSecondarySize : 12);
                textContainer.addView(tvWidgetAlbumArtist);

                if (el.textPosition.equalsIgnoreCase("left")) {
                    imgLp.leftMargin = (int)(15 * density); albumContainer.addView(textContainer, textContainerLp); albumContainer.addView(ivWidgetAlbum, imgLp);
                } else if (el.textPosition.equalsIgnoreCase("right")) {
                    textContainerLp.leftMargin = (int)(15 * density); albumContainer.addView(ivWidgetAlbum, imgLp); albumContainer.addView(textContainer, textContainerLp);
                } else if (el.textPosition.equalsIgnoreCase("top")) {
                    textContainerLp.bottomMargin = (int)(5 * density); albumContainer.addView(textContainer, textContainerLp); albumContainer.addView(ivWidgetAlbum, imgLp);
                } else {
                    textContainerLp.topMargin = (int)(5 * density); albumContainer.addView(ivWidgetAlbum, imgLp); albumContainer.addView(textContainer, textContainerLp);
                }

                canvas.addView(albumContainer); // 🚀 Restored as a direct child of the canvas!
                createdWidgetView = albumContainer;
            }
            else if (el.type.equals("widget_analog_clock")) {
                customAnalogClockView = new CustomAnalogClockView(this);
                customAnalogClockView.setLayoutParams(createDynamicLayoutParams(el, density));
                customAnalogClockView.setPadding(p, p, p, p);
                if (el.bgColor != null && !el.bgColor.trim().isEmpty()) {
                    try { customAnalogClockView.setClockBackgroundColor(android.graphics.Color.parseColor(el.bgColor.trim())); } catch (Exception e) { Log.d(TAG, "buildDynamicMainMenuUI failed", e); }
                }
                canvas.addView(customAnalogClockView);
                createdWidgetView = customAnalogClockView;
            }
            else if (el.type.equals("widget_circular_battery")) {
                customCircularBatteryView = new CircularBatteryView(this);
                customCircularBatteryView.setLayoutParams(createDynamicLayoutParams(el, density));
                customCircularBatteryView.setPadding(p, p, p, p);
                if (el.textSize > 0) customCircularBatteryView.setCustomTextSize(el.textSize * density);
                canvas.addView(customCircularBatteryView);
                createdWidgetView = customCircularBatteryView;
            }
            else if (el.type.equals("widget_focus_image")) {
                ivWidgetFocusImage = new ImageView(this); // 🚀 Slimmed down to a standalone ImageView skeleton for hybrid unification!
                ivWidgetFocusImage.setLayoutParams(createDynamicLayoutParams(el, density));
                ivWidgetFocusImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                if (widgetBg != null) ivWidgetFocusImage.setBackground(widgetBg);
                ivWidgetFocusImage.setPadding(p, p, p, p);

                canvas.addView(ivWidgetFocusImage);
                createdWidgetView = ivWidgetFocusImage;
            }

            // 🚀 Carefully registers this widget object and its theme JSON design info in the address book.
            if (createdWidgetView != null) {
                // ❌ [Fatal error cause removed] Fully prevents the app from crashing due to modifying a list mid-loop!
                widgetViewRegistry.put(createdWidgetView, el); // Store it in the correct vault.

                // 🚀 [Logic fix] If this isn't a parentId but rather a newly created visibleOnFocus (watch target), hide it by default!
                if (el.visibleOnFocus != null && !el.visibleOnFocus.trim().isEmpty()) {
                    createdWidgetView.setVisibility(View.GONE);
                }
            }
        }
        // 💡 Draw buttons
        List<LinearLayout> createdButtons = new ArrayList<>(); // 🚀 Upgraded from Button to LinearLayout

        // 🚀 [Hide-filtering engine] Completely excludes from the list any button the user chose to hide in settings!
        List<ThemeManager.MenuElement> visibleButtonElements = new ArrayList<>();
        for (ThemeManager.MenuElement el : buttonElements) {
            if (!prefs.getBoolean("hide_btn_" + el.id, false)) {
                visibleButtonElements.add(el);
            }
        }

        // Build the UI and wire up the focus chain (ID) using only the 'buttons set to show', not the full list.
        for (int i = 0; i < visibleButtonElements.size(); i++) {
            final ThemeManager.MenuElement el = visibleButtonElements.get(i);

            // 🚀 1. The overall container wrapping the button (LinearLayout)
            final LinearLayout btn = new LinearLayout(this);
            btn.setId(10000 + i);
            btn.setTag(el.action);
            btn.setSoundEffectsEnabled(false);
            btn.setFocusable(true);
            // 🚀 [Focus-vanish fix 3] Inject the clickable instinct, since Android ignores a button's existence when the clickable attribute is missing!
            btn.setClickable(true);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setOnLongClickListener(globalScreenOffLongClickListener);
            // 🚀 2. Left-side main text and icon view
            final TextView tvMain = new TextView(this);
            tvMain.setSingleLine(true);
            tvMain.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 🚀 [Android bug fix] Physically eliminate the TextView's characteristic invisible ghost margin (~5px).
            tvMain.setIncludeFontPadding(false);
            tvMain.setPadding(0, 0, 0, 0);
            tvMain.setMinimumWidth(0);
            tvMain.setMinimumHeight(0);

            // 🚀 3. Right-side arrow and point text view
            final TextView tvRight = new TextView(this);
            tvRight.setSingleLine(true);
            tvRight.setIncludeFontPadding(false); // Match this here too.
            tvRight.setPadding(0, 0, 0, 0);

            final boolean isIconOnly = (el.textNormal == null || el.textNormal.trim().isEmpty());

            // 🚀 [Ultimate formula] Also fully blocks the crash where enlarged padding drives the icon size negative.
            final int calculatedIconSize;
            if (isIconOnly) {
                int w = el.width > 0 ? el.width : 50;
                int h = el.height > 0 ? el.height : 50;
                int p = (int)(el.padding * density);
                int tempSize = (int)(Math.min(w, h) * density) - (p * 2);
                // Guards so the icon keeps a minimum size of 10dp even if it would otherwise shrink too much.
                calculatedIconSize = tempSize > 0 ? tempSize : (int)(10 * density);
            } else {
                int h = el.height > 0 ? el.height : 50;
                calculatedIconSize = (int)(h * density * 0.5f);
            }

            int textGravity = android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL;
            if (el.textAlign != null && !el.textAlign.isEmpty()) {
                String ta = el.textAlign.toLowerCase();
                if (ta.equals("center")) textGravity = android.view.Gravity.CENTER;
                else if (ta.equals("right")) textGravity = android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL;
                else if (ta.equals("top")) textGravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                else if (ta.equals("bottom")) textGravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            } else {
                if (el.gravity.toLowerCase().contains("center")) textGravity = android.view.Gravity.CENTER;
            }

            if (isIconOnly) {
                btn.setGravity(android.view.Gravity.CENTER);
                int p = (int)(el.padding * density);
                btn.setPadding(p, p, p, p);
                tvMain.setGravity(android.view.Gravity.CENTER);
            } else {
                btn.setGravity(android.view.Gravity.CENTER_VERTICAL);
                tvMain.setGravity(textGravity);
                tvRight.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);

                // 🚀 [Fix 1] Text-only buttons now also properly get the padding value set in the editor!
                int customPad = (int)(el.padding * density);

                if (el.textAlign != null && (el.textAlign.equalsIgnoreCase("top") || el.textAlign.equalsIgnoreCase("bottom"))) {
                    // Use the user's value if set; otherwise apply the default of 15
                    int verticalPad = el.padding > 0 ? customPad : (int)(15 * density);
                    btn.setPadding(customPad, verticalPad, customPad, verticalPad);
                } else {
                    int horizontalPad = el.padding > 0 ? customPad : (int)(15 * density);
                    btn.setPadding(horizontalPad, customPad, horizontalPad, customPad);
                }
            }

            btn.setLayoutParams(createDynamicLayoutParams(el, density));
            tvMain.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
            tvRight.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);

            if (!isIconOnly) {
                // 🚀 [Font-size bug fix] Uses pixel (PX) units instead of Android's default (SP) unit, forcing the size to exactly match the editor!
                float mainSize = el.textSize > 0 ? el.textSize : 16; // Default to the same 16px as the editor
                float rightSize = el.textSecondarySize > 0 ? el.textSecondarySize : mainSize; // Supports an independent size for the right-side text

                tvMain.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mainSize * density);
                tvRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rightSize * density);
            }

            LinearLayout.LayoutParams lpMain;
            LinearLayout.LayoutParams lpRight;

            if (isIconOnly) {
                // 🚀 [Core fix 1] For icon-only buttons, completely remove the 10dp right margin (the "thief") so it keeps only its own size and stays centered!
                lpMain = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpRight = new LinearLayout.LayoutParams(0, 0);
                tvRight.setVisibility(View.GONE); // Dismiss the ghost text view

                // 🚀 [Core fix 2] Unsealed so it isn't clipped by the padding line when the zoom animation fires!
                btn.setClipChildren(false);
                btn.setClipToPadding(false);
            } else {
                // Regular buttons keep a weight of 1.0f so the text pushes into the remaining space
                lpMain = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpRight = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpRight.leftMargin = (int)(10 * density);
            }

            btn.addView(tvMain, lpMain);
            btn.addView(tvRight, lpRight);

            final Runnable setNormalState = new Runnable() {
                public void run() {
                    // 🚀 [Bug fix 1] Prioritize the individually specified background color (bgColor) from the editor over the theme's default background color, if present!
                    int normalBgColor = ThemeManager.getListButtonNormalBg();
                    if (el.bgColor != null && !el.bgColor.trim().isEmpty()) {
                        try { normalBgColor = android.graphics.Color.parseColor(el.bgColor.trim()); } catch (Exception e) { Log.d(TAG, "buildDynamicMainMenuUI failed", e); }
                    }

                    // 🚀 [Bug fix 2] Removed the forced transparent-color assignment for both icon-only and regular buttons — always paint the background color!
                    btn.setBackground(createDynamicButtonBackground(normalBgColor, el.radius));

                    if (isIconOnly) {
                        tvMain.setText("");
                    } else {
                        tvMain.setText(t(el.textNormal));
                        tvMain.setTextColor(ThemeManager.getTextColorPrimary());
                        tvRight.setText(el.textRight != null ? t(el.textRight) : "");

                        if (el.textRightColor != null && !el.textRightColor.isEmpty()) {
                            try { tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightColor)); }
                            catch (Exception e) { tvRight.setTextColor(ThemeManager.getTextColorPrimary()); }
                        } else {
                            tvRight.setTextColor(ThemeManager.getTextColorPrimary());
                        }
                    }

                    if (el.iconNormal != null && !el.iconNormal.isEmpty()) {
                        // 🚀 [Core technique 1] Physically crop the bitmap itself at the pixel level so Android can't ignore and override the intended size!
                        android.graphics.Bitmap scaledBmp = getScaledThemedIcon(el.iconNormal, calculatedIconSize);
                        if (scaledBmp != null) {
                            android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), scaledBmp);

                            d.setBounds(0, 0, calculatedIconSize, calculatedIconSize);
                            tvMain.setCompoundDrawables(d, null, null, null);

                            tvMain.setCompoundDrawablePadding(isIconOnly ? 0 : (int)(10 * density));
                        } else {
                            tvMain.setCompoundDrawables(null, null, null, null);
                        }
                    } else {
                        tvMain.setCompoundDrawables(null, null, null, null);
                    }
                    tvMain.setTranslationX(0);
                    tvMain.setTranslationY(0);
                    tvMain.setScaleX(1.0f);
                    tvMain.setScaleY(1.0f);
                }
            };
            setNormalState.run();

            btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        btn.setBackground(createDynamicButtonBackground(ThemeManager.getListButtonFocusedBg(), el.radius));

                        if (isIconOnly) {
                            tvMain.setText("");
                        } else {
                            // 🚀 Simultaneously change the main text and right-arrow colors on focus!
                            tvMain.setTextColor(ThemeManager.getListButtonFocusedTextColor());
// 🚀 Apply a dedicated focus color for the right-side text
                            if (el.textRightFocusedColor != null && !el.textRightFocusedColor.isEmpty()) {
                                try { tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightFocusedColor)); }
                                catch (Exception e) { tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor()); }
                            } else {
                                tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                            }
                            if (el.textFocused != null && !el.textFocused.isEmpty()) tvMain.setText(t(el.textFocused));
                            else tvMain.setText(t(el.textNormal));
                        }

                        String targetIcon = (el.iconFocused != null && !el.iconFocused.isEmpty()) ? el.iconFocused : el.iconNormal;
                        if (targetIcon != null && !targetIcon.isEmpty()) {
                            // 🚀 [Core technique 2] On focus, likewise physically crop the bitmap before inserting it!
                            android.graphics.Bitmap scaledBmpF = getScaledThemedIcon(targetIcon, calculatedIconSize);
                            if (scaledBmpF != null) {
                                android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), scaledBmpF);

                                d.setBounds(0, 0, calculatedIconSize, calculatedIconSize);
                                tvMain.setCompoundDrawables(d, null, null, null);
                            }
                        }
                        updateFocusPreviewLiveContent(el);
                        tvMain.animate()
                                .translationX(el.focusOffsetX * density)
                                .translationY(el.focusOffsetY * density)
                                .scaleX(el.focusScale).scaleY(el.focusScale)
                                .setDuration(150).start();

                    } else {
                        // Restore to the original state when focus leaves
                        tvMain.animate()
                                .translationX(0).translationY(0)
                                .scaleX(1.0f).scaleY(1.0f)
                                .setDuration(150).start();
                        setNormalState.run();
                    }
                }
            });

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    lastMainMenuFocusAction = el.action; // remember which main-menu button we left from, so back returns focus here
                    switch (el.action) {
                        case "OPEN_PLAYER": {
                            com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                            boolean navidromeActive = am.isNavidromeMode && !am.navidromePlaylist.isEmpty();
                            if (currentPlaylist.isEmpty() && !navidromeActive) {
                                Toast.makeText(MainActivity.this, "No music is currently playing.", Toast.LENGTH_SHORT).show();
                            } else {
                                changeScreen(STATE_PLAYER);
                                if (navidromeActive) {
                                    // Restart the progress poller — it stops when the player screen is left
                                    progressHandler.removeCallbacks(updateProgressTask);
                                    progressHandler.post(updateProgressTask);
                                }
                            }
                            break;
                        }
// 🎵 Enter the music library
                        case "OPEN_COVER_FLOW":
                            currentBrowserMode = BROWSER_COVER_FLOW;
                            changeScreen(STATE_BROWSER);
                            break;
                        case "OPEN_BROWSER":
                            isAudiobookLibraryMode = false;
                            currentBrowserMode = BROWSER_ROOT;
                            if (customLibrary.isEmpty() && !isCustomScanning) startMediaLibraryScan();
                            changeScreen(STATE_BROWSER);
                            if (isCustomScanning && customLibrary.isEmpty()) showLoadingPopup();
                            break;

                        // 📚 Jump directly into the audiobook library (set the action to "OPEN_AUDIOBOOKS" in the theme settings to enable this!)
                        case "OPEN_AUDIOBOOKS":
                            isAudiobookLibraryMode = true;
                            currentBrowserMode = BROWSER_ROOT;
                            if (audiobookLibrary.isEmpty() && !isCustomScanning) startMediaLibraryScan();
                            changeScreen(STATE_BROWSER);
                            if (isCustomScanning && audiobookLibrary.isEmpty()) showLoadingPopup();
                            break;
                        case "OPEN_BLUETOOTH": changeScreen(STATE_BLUETOOTH); break;
                        case "OPEN_SETTINGS": changeScreen(STATE_SETTINGS); break;
                        case "OPEN_WEBSERVER": changeScreen(STATE_WEBSERVER); break;
// 🚀 [Radio revival] Turns on Android's built-in FM radio when the radio button is pressed in the theme!
                        case "OPEN_RADIO":
                            clickFeedback();
                            // 🚀 Instead of the clunky stock app, go directly into our own sleek built-in radio studio.
                            isNavigatingToSubMenu = true;
                            changeScreen(STATE_SETTINGS);
                            buildRadioUI();
                            isNavigatingToSubMenu = false;
                            break;
                        // 🚀🚀🚀 [New direct-shortcut actions start here!] 🚀🚀🚀
                        case "OPEN_ROOT_FOLDER":
                            currentBrowserMode = BROWSER_FOLDER;
                            currentFolder = new File("/storage/sdcard0"); // Force-move to the topmost root folder!
                            changeScreen(STATE_BROWSER);
                            break;
                        case "OPEN_WIFI": changeScreen(STATE_WIFI); break;
                        case "OPEN_NAVIDROME":
                            navidromeBrowseDepth = NAV_ARTISTS;
                            selectedNavidromeArtist = null;
                            com.themoon.y1.managers.NavidromeManager.getInstance().clearSelectedAlbum();
                            isNavidromeLetterView = false;
                            navidromeBackTarget = STATE_MENU;
                            changeScreen(STATE_NAVIDROME);
                            break;
                        case "OPEN_BRIGHTNESS": changeScreen(STATE_BRIGHTNESS); break;
                        case "OPEN_STORAGE_INFO": changeScreen(STATE_STORAGE); break;
                        case "OPEN_WIDGET_SETTINGS":
                            isNavigatingToSubMenu = true; changeScreen(STATE_SETTINGS); buildWidgetSettingsUI(); isNavigatingToSubMenu = false; break;
                        case "OPEN_BACKGROUND_SETTINGS":
                            isNavigatingToSubMenu = true; changeScreen(STATE_SETTINGS); buildBackgroundSettingsUI(); isNavigatingToSubMenu = false; break;
                        case "OPEN_THEME_SETTINGS":
                            isNavigatingToSubMenu = true; changeScreen(STATE_SETTINGS); buildThemeSelectorUI(); isNavigatingToSubMenu = false; break;
                        case "OPEN_TIME_SETTINGS":
                            java.util.Calendar c = java.util.Calendar.getInstance();
                            dtYear = c.get(java.util.Calendar.YEAR); dtMonth = c.get(java.util.Calendar.MONTH) + 1; dtDay = c.get(java.util.Calendar.DAY_OF_MONTH);
                            dtHour = c.get(java.util.Calendar.HOUR_OF_DAY); dtMinute = c.get(java.util.Calendar.MINUTE);
                            isNavigatingToSubMenu = true; changeScreen(STATE_SETTINGS); buildDateTimeUI(); isNavigatingToSubMenu = false; break;
                        // 🚀🚀🚀 [End of addition] 🚀🚀🚀

                        default: break;
                    }
                }
            });

            if (el.parentId != null && !el.parentId.isEmpty() && listContainers.containsKey(el.parentId)) {
                // 💡 1. If it belongs to a list box: adjust the attributes to match vertical (LinearLayout) alignment rules.
                LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        el.height > 0 ? (int)(el.height * density) : LinearLayout.LayoutParams.WRAP_CONTENT);
                // Inside a list, smartly reuse the Y value as a top margin (vertical gap) and the X value as horizontal spacing!
                listLp.setMargins((int)(el.x * density), (int)(el.y * density), (int)(el.x * density), 0);
                btn.setLayoutParams(listLp);

                // Instead of the canvas, it goes inside the parent group (list box)!
                listContainers.get(el.parentId).addView(btn);
            } else {
                // 💡 2. If it doesn't belong to anything: plug in the X, Y absolute coordinates directly onto the canvas as before.
                btn.setLayoutParams(createDynamicLayoutParams(el, density));
                canvas.addView(btn);
            }

            createdButtons.add(btn);

        }

        int totalBtns = createdButtons.size();
        for (int i = 0; i < totalBtns; i++) {
            LinearLayout currentBtn = createdButtons.get(i);
            // 🚀 [Loop-condition branch] Depending on the loop-scroll setting, either allow infinite wrapping at both ends or cut it off (View.NO_ID).
            int prevId = (i == 0) ? (isLoopScrollOn ? 10000 + totalBtns - 1 : View.NO_ID) : 10000 + i - 1;
            int nextId = (i == totalBtns - 1) ? (isLoopScrollOn ? 10000 : View.NO_ID) : 10000 + i + 1;

            currentBtn.setNextFocusUpId(prevId);
            currentBtn.setNextFocusLeftId(prevId);

            currentBtn.setNextFocusDownId(nextId);
            currentBtn.setNextFocusRightId(nextId);
        }

        refreshWidgets();

        // 🚀 [Bug fix] Once the screen assembly is completely done (50ms safety wait), forcefully focus button 0!
        if (!createdButtons.isEmpty()) {
            final LinearLayout firstBtn = createdButtons.get(0);
            firstBtn.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 🚀 [Culprit of the focus-vanish bug caught!] Guard added so focus is only pulled back while looking at the main screen!
                    // Only steal focus if nothing is already focused — otherwise this clobbers the
                    // back-navigation focus restore that changeScreen() already applied synchronously.
                    View cur = getCurrentFocus();
                    if (currentScreenState == STATE_MENU && (cur == null || cur.getVisibility() != View.VISIBLE)) {
                        firstBtn.requestFocus();

                        android.view.ViewParent parent = firstBtn.getParent();
                        if (parent != null && parent.getParent() instanceof android.widget.ScrollView) {
                            ((android.widget.ScrollView) parent.getParent()).scrollTo(0, 0);
                        }
                    }
                }
            }, 50);
        }
    }
    private void collectAudioFilesAsFile(File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    collectAudioFilesAsFile(f, list); // Recurse into it if it's a folder!
                } else if (isAudioFile(f)) {
                    list.add(f); // Put it in the bucket if it's a music file.
                }
            }
        }
    }
    private void installApk(File apkFile) {
        try {
            // 1. Keep the existing permission opening (for installer access)
            try {
                Runtime.getRuntime().exec("chmod 777 " + apkFile.getParentFile().getAbsolutePath());
                Runtime.getRuntime().exec("chmod 777 " + apkFile.getAbsolutePath());
            } catch (Exception e) {
                Log.d(TAG, "installApk failed", e);
            }

            // 🚀 2. [Perfect solution: Silent Background Install]
            try {
                Process process = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(process.getOutputStream());

                // 🚀 [Core fix] Attach a redirect (> .. 2>&1) so the install result is saved to a text file (y1_update_log.txt) on the device!
                os.writeBytes("pm install -r " + apkFile.getAbsolutePath() + " > /storage/sdcard0/y1_update_log.txt 2>&1 \n");

                // 💡 Give the package manager a 3-second cooldown (rest) so it can finish installing.
                os.writeBytes("sleep 3\n");

                // Step 2: once the install completes, immediately relaunch the launcher (app) and return to the screen!
                os.writeBytes("am start -n " + getPackageName() + "/.MainActivity\n");

                os.writeBytes("exit\n");
                os.flush();
                os.close();
                process.waitFor();

                return;
            } catch (Exception e) {
                // Fall through to Plan B on a root-permission error
            }

            // 3. [Plan B] On non-rooted devices, show the traditional manual install screen as before
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri = Uri.parse("file://" + apkFile.getAbsolutePath());
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(this, t("Install Failed."), Toast.LENGTH_SHORT).show();
        }
    }

    // 🚀 [New engine] The dedicated worker that determines the spectrum/lyrics state in real time and switches the screen accordingly!
    private void refreshVisualizerState() {
        View albumContainer = (View) ivAlbumArt.getParent();

        if (isVisualizerShowing) {
            albumContainer.setVisibility(View.GONE);

            // If the current track has lyrics? -> turn off the spectrum and turn on the lyrics window!
            if (!currentLyrics.isEmpty() || plainLyrics != null) {
                if (audioVisualizer != null) audioVisualizer.setEnabled(false);
                visualizerView.setVisibility(View.GONE);
                visualizerView.clearAnimation(); // Remove leftover animation artifacts

                lyricScrollView.setVisibility(View.VISIBLE);

                if (plainLyrics != null && currentLyrics.isEmpty()) {
                    lyricScrollView.post(new Runnable() {
                        public void run() { lyricScrollView.scrollTo(0, 0); }
                    });
                }
            }
            // If the current track has no lyrics? -> turn off the lyrics window and turn on the flashy spectrum!
            else {
                lyricScrollView.setVisibility(View.GONE);
                visualizerView.setVisibility(View.VISIBLE);
                visualizerView.invalidate();
                if (audioVisualizer != null) audioVisualizer.setEnabled(true);
            }
        } else {
            // If visualization mode is off entirely, hide everything and revert to album art
            visualizerView.setVisibility(View.GONE);
            lyricScrollView.setVisibility(View.GONE);
            albumContainer.setVisibility(View.VISIBLE);
            if (audioVisualizer != null) audioVisualizer.setEnabled(false);
        }
    }

    // 💡 Pressing the center button (click) just toggles the switch and calls the refresh worker.
    private void toggleVisualizer() {
        isVisualizerShowing = !isVisualizerShowing;
        refreshVisualizerState();
    }
    // 💡 [Fix] Function that taps into the audio engine to pull out frequency data
    public void setupVisualizer() {
        try {
            // 🚀 [Fully fixed] Build a fresh engine every single time and mount it! (eliminates memory leaks at the source)
            if (audioVisualizer != null) {
                audioVisualizer.setEnabled(false);
                audioVisualizer.release();
                audioVisualizer = null;
            }

            // ⭕ [Overwrite with the code below]
            audioVisualizer = new android.media.audiofx.Visualizer(com.themoon.y1.managers.AudioPlayerManager.getInstance().getAudioSessionId());
            audioVisualizer.setCaptureSize(android.media.audiofx.Visualizer.getCaptureSizeRange()[1]);
            audioVisualizer.setDataCaptureListener(new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(android.media.audiofx.Visualizer visualizer, byte[] waveform,
                        int samplingRate) {
                }

                @Override
                public void onFftDataCapture(android.media.audiofx.Visualizer visualizer, byte[] fft,
                        int samplingRate) {
                    if (isVisualizerShowing && visualizerView != null
                            && visualizerView.getVisibility() == View.VISIBLE) {
                        visualizerView.updateVisualizer(fft, samplingRate);
                    }
                }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true);

            if (isVisualizerShowing) {
                audioVisualizer.setEnabled(true);
            }
        } catch (Exception e) {
            Log.d(TAG, "setupVisualizer failed", e);
        }
    }
    // 🚀 [Lyrics engine] Finds a .lrc file and splits it into time and text stored in memory.
    // 🚀 [Lyrics engine] Looks for a .lrc file first, and if none exists, extracts the lyrics embedded inside the MP3 directly!
    private void loadLyrics(File audioFile) {
        currentLyrics.clear();
        lyricTimestamps.clear();
        lastLyricIndex = -1;
        plainLyrics = null;
        if (tvLyrics != null) tvLyrics.setText("");

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
                                currentLyrics.put(totalMs, text);
                            }
                        }
                    }
                    br.close();
                    lyricTimestamps = new ArrayList<>(currentLyrics.keySet());
                    return; // If it succeeded, end the engine early here!
                } catch (Exception e) {
                    Log.d(TAG, "loadLyrics failed", e);
                }
            }
        }

        // 2. If there's no external .lrc file, mine the lyrics (USLT) embedded inside the MP3 itself!
        plainLyrics = extractEmbeddedLyrics(audioFile);
        if (plainLyrics != null && !plainLyrics.isEmpty()) {
            if (tvLyrics != null) {
                // Since embedded lyrics can't move in sync with time, just display them all at once in white (default) color.
                tvLyrics.setText(plainLyrics);
            }
        }
    }
    // 🚀 [New engine] Precision parser that digs directly into an MP3's ID3 tags to extract the lyrics (USLT)
    private String extractEmbeddedLyrics(File file) {
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

    public void updatePlayerStatusIndicators() {
        try {
            // 1. Shuffle icon setup
            if (ivPlayerShuffleStatus != null) {
                if (isShuffleMode) {
                    ivPlayerShuffleStatus.setImageResource(R.drawable.ic_shuffle);
                    ivPlayerShuffleStatus.setVisibility(View.VISIBLE);
                } else {
                    ivPlayerShuffleStatus.setVisibility(View.GONE);
                }
            }
            if (ivPlayerRepeatStatus != null) {
                if (repeatMode == 1) { // repeat one
                    ivPlayerRepeatStatus.setImageResource(R.drawable.ic_repeat_one);
                    ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else if (repeatMode == 2) { // repeat all
                    ivPlayerRepeatStatus.setImageResource(R.drawable.ic_repeat);
                    ivPlayerRepeatStatus.setVisibility(View.VISIBLE);
                } else { // repeat off
                    ivPlayerRepeatStatus.setVisibility(View.GONE);
                }
            }
            if (tvPlayerFavoriteStatus != null) {
                String favPath = getCurrentTrackPathForFavorites();
                if (favPath != null && favoritePaths.contains(favPath)) {
                    tvPlayerFavoriteStatus.setVisibility(View.VISIBLE);
                } else {
                    tvPlayerFavoriteStatus.setVisibility(View.GONE);
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
    private String getCurrentTrackPathForFavorites() {
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        if (am.isNavidromeMode) {
            if (am.navidromePlaylist.isEmpty()) return null;
            return am.navidromePlaylist.get(am.navidromeIndex).getExistingLocalPath();
        }
        if (currentPlaylist.isEmpty() || currentIndex < 0 || currentIndex >= currentPlaylist.size()) return null;
        return currentPlaylist.get(currentIndex).getAbsolutePath();
    }

    private void toggleFavorite() {
        String path = getCurrentTrackPathForFavorites();
        if (path == null) {
            if (com.themoon.y1.managers.AudioPlayerManager.getInstance().isNavidromeMode) {
                Toast.makeText(this, "⬇ Download this track to add it to Favorites", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        boolean nowFavorite;
        if (favoritePaths.contains(path)) {
            favoritePaths.remove(path);
            nowFavorite = false;
            Toast.makeText(this, "♡ Removed from Favorites", Toast.LENGTH_SHORT).show();
        } else {
            favoritePaths.add(path);
            nowFavorite = true;
            Toast.makeText(this, "♥ Added to Favorites", Toast.LENGTH_SHORT).show();
        }

        try {
            libraryCacheDb.setFavorite(path, nowFavorite); // Save it permanently right away!
        } catch (Exception e) {
            Log.d(TAG, "toggleFavorite failed", e);
        }

        updatePlayerStatusIndicators(); // 💖 Refresh the icon
    }
    public void updatePlayerUI() {
            try {
                com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                if (am.isNavidromeMode) {
                    // currentPlaylist still points at the last LOCAL track — its lyrics
                    // and quality info don't belong to the stream we're playing.
                    currentLyrics.clear();
                    plainLyrics = null;
                    updateNavidromeQualityInfo(am);
                } else if (!currentPlaylist.isEmpty() && currentIndex >= 0 && currentIndex < currentPlaylist.size()) {
                    File currentFile = currentPlaylist.get(currentIndex);
                    updateAudioQualityInfo(currentFile);

                    // 🚀 Every time the track changes, check whether a same-named .lrc file exists!
                    loadLyrics(currentFile);
                    refreshVisualizerState();
                }

                if (am.isPlaying()) {
                    ivAlbumArt.setAlpha(1.0f);
                    ivPauseOverlay.setVisibility(View.GONE);
                    progressHandler.post(updateProgressTask);
                } else {
                    ivAlbumArt.setAlpha(0.4f);
                    ivPauseOverlay.setVisibility(View.VISIBLE);
                    progressHandler.removeCallbacks(updateProgressTask);
                }

                updateGlobalStatusPlayIcon();
                updatePlayerStatusIndicators(); // 💡 The function that used to error! (works correctly now)
// 🚀 [Car Bluetooth integration] Sends the track info and play/pause state to the car in real time!
                sendBluetoothMetaToCar();
                updateBluetoothPlaybackState(am.isPlaying());
                // 🚀 [Real-time sync] While the main screen is watching Now Playing, if the track changes in the background, refresh the preview image live too!
                if (currentScreenState == STATE_MENU && ivWidgetFocusImage != null && tvFocusPreviewClock != null && tvFocusPreviewClock.getVisibility() == View.VISIBLE) {
                    for (ThemeManager.MenuElement el : ThemeManager.getCurrentTheme().menuElements) {
                        if ("OPEN_PLAYER".equals(el.action)) {
                            updateFocusPreviewLiveContent(el);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "updatePlayerUI failed", e);
            }
        }
        // 🚀 [Bug cause removed] Perfectly removed one unnecessary stray closing brace '}' that was here!
    public void adjustVolume(boolean up) {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (up && currentVol < maxVol)
            currentVol++;
        else if (!up && currentVol > 0)
            currentVol--;
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol, 0);

        // 🚀 [Bug fix complete] If the radio is on, also sync-lower the hardware volume on the MediaTek radio-dedicated channel (STREAM_FM = 10)!
        try {
            com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
            if (fm.isPowerUp) {
                int streamFm = 10;
                try { streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null); } catch (Exception e) { Log.d(TAG, "adjustVolume failed", e); }
                int fmMax = audioManager.getStreamMaxVolume(streamFm);
                int fmVol = (int) (((float)currentVol / maxVol) * fmMax);
                audioManager.setStreamVolume(streamFm, fmVol, 0);
            }
        } catch (Exception e) {
            Log.d(TAG, "adjustVolume failed", e);
        }

        showDynamicVolumeOverlay();
    }

    private void showDynamicVolumeOverlay() {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        layoutVolumeOverlay.setVisibility(View.VISIBLE);
        volumeProgress.setProgress(currentVol);
        volumeHandler.removeCallbacks(hideVolumeTask);
        volumeHandler.postDelayed(hideVolumeTask, 2000);
    }

    // Reused buffer so the 2x/second progress tick doesn't spin up a Formatter + autobox ints
    // (as String.format does) on every call. Only ever touched from the UI thread.
    private final StringBuilder timeFmtBuilder = new StringBuilder(8);
    private String formatTime(int ms) {
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
    public boolean handleMediaSeekKeyRepeat(KeyEvent event, int seekMs) {
        if (event.getRepeatCount() == 0) {
            event.startTracking();
            isSeekPerformed = false;
        } else {
            long now = System.currentTimeMillis();
            if (now - lastSeekTime > 300) {
                isSeekPerformed = true;
                lastSeekTime = now;
                com.themoon.y1.managers.AudioPlayerManager.getInstance().seekRelative(seekMs);
                clickFeedback();
            }
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return com.themoon.y1.managers.KeyEventRouter.getInstance().dispatchKeyEvent(this, event);
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return com.themoon.y1.managers.KeyEventRouter.getInstance().onKeyDown(this, keyCode, event);
    }
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return com.themoon.y1.managers.KeyEventRouter.getInstance().onKeyUp(this, keyCode, event);
    }
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return com.themoon.y1.managers.KeyEventRouter.getInstance().onKeyLongPress(this, keyCode, event);
    }

    public boolean superDispatchKeyEvent(KeyEvent event) { return super.dispatchKeyEvent(event); }
    public boolean superOnKeyDown(int keyCode, KeyEvent event) { return super.onKeyDown(keyCode, event); }
    public boolean superOnKeyUp(int keyCode, KeyEvent event) { return super.onKeyUp(keyCode, event); }
    public boolean superOnKeyLongPress(int keyCode, KeyEvent event) { return super.onKeyLongPress(keyCode, event); }
    // AapService only (re)starts from the A2DP CONNECTION_STATE_CHANGED broadcast, which fires on
    // a state transition. If Android kills this process while AirPods are already connected, the
    // process comes back with no AAP session and no new broadcast to trigger one (the state never
    // changed) -- ear-detection stays dead until the user manually toggles Bluetooth. Re-checking
    // on resume (and right after the A2DP proxy first binds) self-heals that without needing a
    // manual reconnect.
    private void resyncAapWithConnectedDevice() {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().resyncAapWithConnectedDevice(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resyncAapWithConnectedDevice();
        if (usbFocusHelper != null) usbFocusHelper.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Resume the pure-UI tick loops when we become visible again. Both are stopped in
        // onStop() so they don't keep waking the CPU every 0.5-1s while the screen is off /
        // the launcher is backgrounded. removeCallbacks first makes these idempotent.
        clockHandler.removeCallbacks(clockTask);
        clockHandler.post(clockTask);
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        if (am.isPlaying() || am.isNavidromeMode) {
            progressHandler.removeCallbacks(updateProgressTask);
            progressHandler.post(updateProgressTask);
        }
    }

    @Override
    protected void onStop() {
        // Stop the 1s clock/widget refresh and the 500ms progress loop while not visible.
        // On this always-running HOME launcher these otherwise tick forever (there is no
        // reliable onDestroy), continuously waking a very power-constrained CPU.
        clockHandler.removeCallbacks(clockTask);
        progressHandler.removeCallbacks(updateProgressTask);
        // Persist playback position before going quiet so a background kill doesn't lose it.
        // (Throttled to at most once/5s -- identical staleness bound to the periodic loop.)
        try {
            com.themoon.y1.managers.AudioPlayerManager.getInstance().maybeSavePlaybackStateThrottled();
        } catch (Exception ignored) {
            Log.d(TAG, "onStop failed", ignored);
        }
        super.onStop();
    }

    // ⭕ [Overwrite with the code below]
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbFocusHelper != null) usbFocusHelper.onDestroy();
        clockHandler.removeCallbacks(clockTask);
        progressHandler.removeCallbacks(updateProgressTask);
        volumeHandler.removeCallbacks(hideVolumeTask);
        com.themoon.y1.managers.WheelLockManager.getInstance().cancelPendingReset();
        qualityInfoHandler.removeCallbacks(hideQualityInfoTask);
        doubleClickHandler.removeCallbacks(singleClickRunnable);
        com.themoon.y1.managers.FmRadioUiManager.getInstance().cancelPendingReset();
        cancelAudioReconnect();
        releaseNavidromeDownloadLocks();

        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();

        if (currentBrowserMode == BROWSER_AUDIOBOOKS && !currentPlaylist.isEmpty()) {
            com.themoon.y1.managers.AudiobookManager.getInstance(this).saveBookmark(
                    currentPlaylist.get(currentIndex).getAbsolutePath(),
                    am.getCurrentPosition(),
                    currentIndex
            );
        }

        am.releasePlayer(); // 🚀 Politely ask the manager instead of turning it off directly!

        if (currentFileInputStream != null) {
            try { currentFileInputStream.close(); } catch (Exception e) { Log.d(TAG, "onDestroy failed", e); }
        }
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        com.themoon.y1.managers.GaussianBlurManager.getInstance().destroy();

        try {
            unregisterReceiver(systemStatusReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered / never registered -- avoid crashing teardown.
        }

        // Deliberately NOT nulling `instance` here: this is a single-Activity home launcher
        // where MainActivity.instance is relied on everywhere (managers, MediaBtnReceiver)
        // as the de facto app-instance handle. MediaBtnReceiver in particular must still be
        // able to drive playback (e.g. AirPods play/pause) via this reference for the window
        // between the Activity being torn down (screen off, memory trim) and recreated
        // (screen unlock) — nulling it here silently breaks background media-button control.
    }
    // 💡 Function that directly blocks/allows the Android system's own hardware click-sound stream
    public void applySoundSetting() {
        try {
            if (audioManager != null) {
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, !isSoundEffectEnabled);
            }
            // 💡 Key: overwrites the system setting that forcibly blocks the device's touch-panel hardware click sound!
            Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED,
                    isSoundEffectEnabled ? 1 : 0);
        } catch (Exception e) {
            Log.d(TAG, "applySoundSetting failed", e);
        }
    }

    // 💡 Force-mutes the built-in speaker. Two AudioManager-based approaches both failed on this device:
    // (1) AudioSystem.setForceUse(FORCE_HEADPHONES) has nowhere to route to since the device has no wired-headphone jack at all,
    // so it produces no muting effect; (2) setStreamMute() uses a reference-count scheme, so if the mute(true)/mute(false)
    // call counts don't match up, it gets stuck forever
    // (confirmed "Mute count: 166" in dumpsys audio); (3) setStreamVolume() has a separate volume index per device,
    // so it can zero out whichever device (speaker/Bluetooth) happens to be routed at that moment — the wrong one.
    // In the end, instead of touching the system's audio routing at all, we only adjust
    // our own player's internal volume (AudioPlayerManager.setSpeakerMuted).
    // This stays predictable regardless of which physical output device is active.
    public void applySpeakerSetting() {
        applySpeakerSetting(com.themoon.y1.managers.BluetoothAudioManager.getInstance().isAnyDeviceConnected());
    }

    public void applySpeakerSetting(boolean externalAudioConnected) {
        boolean shouldMute = isSpeakerDisabled && !externalAudioConnected;
        com.themoon.y1.managers.AudioPlayerManager.getInstance().setSpeakerMuted(shouldMute);
    }

    // 💡 Updates the Bluetooth status-bar icon color — as many phones commonly do, we distinguish between simply being on (white)
    // and actually being connected to a device (earphones, etc.) (blue).
    private static final int BT_ICON_COLOR_ON = 0xFFFFFFFF;
    private static final int BT_ICON_COLOR_CONNECTED = 0xFF2FA8FF;

    private void updateBluetoothStatusIcon() {
        if (ivStatusBluetooth == null) return;
        boolean connected = com.themoon.y1.managers.BluetoothAudioManager.getInstance().isAnyDeviceConnected();
        ivStatusBluetooth.setColorFilter(connected ? BT_ICON_COLOR_CONNECTED : BT_ICON_COLOR_ON);
    }

    // Gaussian blur (RenderScript) lives in GaussianBlurManager -- see that class for details.
    // Kept as a thin pass-through here since callers across the codebase (and AudioPlayerManager)
    // already call MainActivity.applyGaussianBlur(...)/applyGaussianBlurAsync(...).
    public Bitmap applyGaussianBlur(Bitmap original) {
        return com.themoon.y1.managers.GaussianBlurManager.getInstance().applyGaussianBlur(this, original);
    }

    private interface BlurResultCallback {
        void onBlurred(Bitmap blurred, Bitmap source);
    }

    private void applyGaussianBlurAsync(final Bitmap source, final BlurResultCallback callback) {
        com.themoon.y1.managers.GaussianBlurManager.getInstance().applyGaussianBlurAsync(this, source, callback::onBlurred);
    }

    // 💡 1. Main date/time settings screen (time-error and focus-lock bugs fully fixed version)
    public void buildDateTimeUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildDateTimeUI(this);
    }

    // 💡 2. Vertical list screen for selecting numbers (year/month/day/hour/minute)
    public void buildDateTimeSelectorUI(final String type, int min, int max, int currentValue) {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildDateTimeSelectorUI(this, type, min, max, currentValue);
    }

    // 💡 [Receiver dedicated to screen-off] While the screen is off, the system sends button signals here!
    public static class MediaBtnReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction()) && MainActivity.instance != null) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);

                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    // AirPods squeezes arrive as Bluetooth AVRCP passthrough events on a
                    // virtual input device named "AVRCP" (verified via logcat), distinct
                    // from the device's own physical wheel/button (device "mtk-tpd"). Those
                    // always pass through regardless of the Screen-Off Control setting --
                    // that setting exists to stop accidental in-pocket presses of the
                    // physical wheel, not intentional AirPods gestures.
                    android.view.InputDevice inputDevice = event.getDevice();
                    boolean isFromAirpods = inputDevice != null
                            && inputDevice.getName() != null
                            && inputDevice.getName().contains("AVRCP");

                    if (!isFromAirpods && !MainActivity.instance.isScreenOffControlEnabled)
                        return;

                    int keyCode = event.getKeyCode();

                    // ⏮ Previous track button
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                        // 🚀 [Screen-off control integration] If the radio is on, move to the saved previous channel!
                        if (MainActivity.instance.activePlayer == 1) {
                            MainActivity.instance.tuneToNextSavedRadioChannel(false);
                        } else {
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().prevTrack();
                        }
                        MainActivity.instance.clickFeedback();
                    }
                    // ⏭ Next track button
                    else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                        // 🚀 [Screen-off control integration] If the radio is on, move to the saved next channel!
                        if (MainActivity.instance.activePlayer == 1) {
                            MainActivity.instance.tuneToNextSavedRadioChannel(true);
                        } else {
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().nextTrack();
                        }
                        MainActivity.instance.clickFeedback();
                    }
                    // ⏯ Play/pause button
                    // AirPods send separate PLAY (126) and PAUSE (127) key codes via AVRCP —
                    // not the combined toggle code (85) — so both must be handled here or a
                    // resume-after-pause press while the Activity is backgrounded (screen off)
                    // silently does nothing.
                    else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86
                            || keyCode == 126 || keyCode == 127) {
                        // 🚀 [Bug fix 2] When the radio is on (activePlayer == 1), completely block the receiver from playing music too!
                        if (MainActivity.instance.activePlayer == 1) {
                            // 💡 In radio mode, ignore the bottom button and don't play music.
                        } else {
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic();
                            MainActivity.instance.clickFeedback();
                        }
                    }
                    // 🔊 Defensive code in case the device sends wheel actions (21, 22) as media signals
                    else if (keyCode == 21) {
                        MainActivity.instance.adjustVolume(false);
                        MainActivity.instance.clickFeedback();
                    } else if (keyCode == 22) {
                        MainActivity.instance.adjustVolume(true);
                        MainActivity.instance.clickFeedback();
                    }
                }
            }
        }
    }

    // 💡 [Fix] Read a dynamic button's tag to smartly show the correct album art!
    public void refreshNowPlayingPreview() {
        refreshWidgets();
    }
    // 💡 [Added] 1. Function that loads a cover image fetched from the internet out of the cache folder and displays it on screen
    public void applyCachedCoverArt(String imagePath) {
        try {
            // Sharp, centered album art
            android.graphics.BitmapFactory.Options optsCenter = new android.graphics.BitmapFactory.Options();
            optsCenter.inSampleSize = 2;
            android.graphics.Bitmap bmpCenter = android.graphics.BitmapFactory.decodeFile(imagePath, optsCenter);
            ivAlbumArt.setImageBitmap(bmpCenter);

            // Background blur processing
            android.graphics.BitmapFactory.Options optsBg = new android.graphics.BitmapFactory.Options();
            optsBg.inSampleSize = 4;
            android.graphics.Bitmap sourceBg = android.graphics.BitmapFactory.decodeFile(imagePath, optsBg);
            applyGaussianBlurAsync(sourceBg, (blurredBg, src) -> {
                ivPlayerBgBlur.setImageBitmap(blurredBg);
                if (src != blurredBg) src.recycle();
            });

            // Convert the file data to a byte[] and store it in lastAlbumArtBytes so it can also drive the main-menu background.
            java.io.File file = new java.io.File(imagePath);
            int size = (int) file.length();
            byte[] bytes = new byte[size];
            java.io.BufferedInputStream buf = new java.io.BufferedInputStream(new java.io.FileInputStream(file));
            buf.read(bytes, 0, bytes.length);
            buf.close();

            lastAlbumArtBytes = bytes;
            updateMainMenuBackground();
            refreshNowPlayingPreview();
            // 🚀 Album art has been freshly loaded, so send it to the car display too!
            sendBluetoothMetaToCar();

        } catch (Exception e) {
            Log.d(TAG, "applyCachedCoverArt failed", e);
        }
    }


    public void fetchTrackInfoFromInternet(final File track, final String originalQuery, final boolean hasValidTags,
                                           final String origTitle, final String origArtist) {

        // 🚀 [Added] Offline guard: silently turn back if there's no Wi-Fi or data connection!
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo == null || !netInfo.isConnected()) {
            return;
        }

        final String cleanQuery = originalQuery
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("^[0-9\\s\\-]+", "")
                .replaceAll("\\s[0-9]{2}\\s", " ")
                .trim();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "🔍 Searching: " + cleanQuery, Toast.LENGTH_SHORT).show();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String query = java.net.URLEncoder.encode(cleanQuery, "UTF-8");
                    String urlString = "http://api.deezer.com/search?q=" + query;
                    java.net.URL url = new java.net.URL(urlString);

                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200)
                        throw new Exception("HTTP Response Code: " + responseCode);

                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                        response.append(line);
                    reader.close();

                    org.json.JSONObject jsonResponse = new org.json.JSONObject(response.toString());
                    org.json.JSONArray dataArray = jsonResponse.optJSONArray("data");

                    if (dataArray != null && dataArray.length() > 0) {
                        org.json.JSONObject trackInfo = dataArray.getJSONObject(0);

                        final String fetchedTitle = trackInfo.getString("title");
                        final String fetchedArtist = trackInfo.getJSONObject("artist").getString("name");

                        final String finalTitle = fetchedTitle;
                        final String finalArtist = fetchedArtist;

                        String coverUrl = trackInfo.getJSONObject("album").getString("cover_xl").replace("https://", "http://");
                        java.net.URL imgUrl = new java.net.URL(coverUrl);
                        java.net.HttpURLConnection imgConn = (java.net.HttpURLConnection) imgUrl.openConnection();
                        java.io.InputStream in = imgConn.getInputStream();
                        final android.graphics.Bitmap coverBitmap = android.graphics.BitmapFactory.decodeStream(in);
                        in.close();

                        File coverFolder = new File("/storage/sdcard0/Y1_Covers");
                        if (!coverFolder.exists()) coverFolder.mkdirs();
                        String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "");
                        final File coverFile = new File(coverFolder, safeFileName + ".jpg");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile);
                        coverBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                        fos.close();

                        // 🚀 [Added] Don't forget to also save the album cover path (album_art_) to storage!
                        libraryCacheDb.setMetaOverride(track.getAbsolutePath(), finalTitle, finalArtist);
                        libraryCacheDb.setAlbumArtPath(track.getAbsolutePath(), coverFile.getAbsolutePath()); // 💡 The key line that had been missing

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "✅ Album Art & Info Updated!", Toast.LENGTH_SHORT).show();
                                if (currentPlaylist.get(currentIndex).getAbsolutePath().equals(track.getAbsolutePath())) {
                                    tvPlayerTitle.setText(finalTitle);
                                    tvPlayerArtist.setText(finalArtist);
                                    applyCachedCoverArt(coverFile.getAbsolutePath());
                                }
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() { Toast.makeText(MainActivity.this, "❌ No results found.", Toast.LENGTH_SHORT).show(); }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { Toast.makeText(MainActivity.this, "⚠️ Connection Error", Toast.LENGTH_LONG).show(); }
                    });
                }
            }
        }).start();
    }
    // 💡 [Added] A dedicated socket factory that forces legacy Android's dormant modern security (TLS 1.2) to wake up
    public static class TLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private javax.net.ssl.SSLSocketFactory internalSSLSocketFactory;

        public TLSSocketFactory() throws java.security.KeyManagementException, java.security.NoSuchAlgorithmException {
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, null, null);
            internalSSLSocketFactory = context.getSocketFactory();
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return internalSSLSocketFactory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return internalSSLSocketFactory.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket() throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket());
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port)
                throws java.io.IOException, java.net.UnknownHostException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException, java.net.UnknownHostException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress,
                int localPort) throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(address, port, localAddress, localPort));
        }

        // 🚀 The key part! Forces every socket that's opened to lock its setting to TLSv1.2.
        private java.net.Socket enableTLSOnSocket(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) socket).setEnabledProtocols(new String[] { "TLSv1.1", "TLSv1.2" });
            }
            return socket;
        }
    }

    // 💡 [Fix] Solid-filled real pie-chart class

    // 💡 [Added] Recursive engine that finds every piece of text on screen and re-skins it in the theme font!
    private void applyFontToAllViews(android.view.ViewGroup parent, android.graphics.Typeface font) {
        if (font == null)
            return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);

            // 1. If it's a folder (layout), recurse inward.
            if (child instanceof android.view.ViewGroup) {
                applyFontToAllViews((android.view.ViewGroup) child, font);
            }
            // 2. If it's text (TextView, Button, etc.), swap the font immediately.
            else if (child instanceof android.widget.TextView && !"icon_font".equals(child.getTag())) {
                // If bold was already set, preserve that trait!
                android.graphics.Typeface current = ((android.widget.TextView) child).getTypeface();
                int style = android.graphics.Typeface.NORMAL;
                if (current != null)
                    style = current.getStyle();

                ((android.widget.TextView) child).setTypeface(font, style);
            }
        }
    }

    // 🚀 [New engine] Automatically copies the language packs (assets/languages) bundled inside the APK to device storage!
    private void installBundledLanguages() {
        SharedPreferences prefs = getSharedPreferences("Y1_SETTINGS", MODE_PRIVATE);
        int lastInstalledVersion = prefs.getInt("last_lang_version", 0);

        int currentAppVersion = 1;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentAppVersion = pInfo.versionCode;
        } catch (Exception e) {
            Log.d(TAG, "installBundledLanguages failed", e);
        }

        // 🚀 If the latest version of the default language pack is already installed on the device, skip the duplicate copy to speed things up.
        if (lastInstalledVersion >= currentAppVersion) return;

        // 💡 Note: make sure the path below matches the folder your LanguageManager currently reads files from!
        // It's usually set to something like "/storage/sdcard0/Y1_Languages".
        File targetDir = new File("/storage/sdcard0/Y1_Languages");
        if (!targetDir.exists()) targetDir.mkdirs();

        try {
            android.content.res.AssetManager assetManager = getAssets();
            // Sweep up the list of files inside the assets/languages folder.
            String[] files = assetManager.list("languages");

            if (files != null) {
                for (String filename : files) {
                    if (filename.toLowerCase().endsWith(".json")) {
                        java.io.InputStream is = assetManager.open("languages/" + filename);
                        File outFile = new File(targetDir, filename);
                        java.io.FileOutputStream fout = new java.io.FileOutputStream(outFile);

                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = is.read(buffer)) != -1) {
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                        is.close();
                    }
                }
            }
            // 🚀 If the copy succeeded, record the app version in the vault so it's skipped on the next boot.
            prefs.edit().putInt("last_lang_version", currentAppVersion).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Extracts every zip in assets/themes/ to /storage/sdcard0/Y1_Themes/<zip-name-without-extension>.
    private void installBundledThemes() {
        SharedPreferences prefs = getSharedPreferences("Y1_SETTINGS", MODE_PRIVATE);

        // 🚀 [Reworked] Instead of a simple true/false, reads the 'app version number' the theme was last installed under.
        int lastInstalledVersion = prefs.getInt("last_theme_version", 0);

        // Find out the actual version number (versionCode) of the app currently running.
        int currentAppVersion = 1;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentAppVersion = pInfo.versionCode; // e.g. 1, 2, 3...
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 🚀 [Core guard] Skip if a theme at the same version or higher is already installed!
        // If the app version has gone up (e.g. 1 -> 2), pass through this condition and overwrite the theme.
        if (lastInstalledVersion >= currentAppVersion) return;

        File targetDir = new File("/storage/sdcard0/Y1_Themes");
        if (!targetDir.exists()) targetDir.mkdirs();

        try {
            android.content.res.AssetManager assetManager = getAssets();
            String[] files = assetManager.list("themes");

            if (files != null) {
                for (String filename : files) {
                    if (filename.toLowerCase().endsWith(".zip")) {
                        String folderName = filename.substring(0, filename.lastIndexOf("."));
                        File themeFolder = new File(targetDir, folderName);
                        if (!themeFolder.exists()) themeFolder.mkdirs();

                        java.io.InputStream is = assetManager.open("themes/" + filename);
                        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(is));
                        java.util.zip.ZipEntry ze;

                        while ((ze = zis.getNextEntry()) != null) {
                            File extractFile = new File(themeFolder, ze.getName());
                            if (ze.isDirectory()) {
                                extractFile.mkdirs();
                            } else {
                                File parent = extractFile.getParentFile();
                                if (!parent.exists()) parent.mkdirs();

                                java.io.FileOutputStream fout = new java.io.FileOutputStream(extractFile);
                                byte[] buffer = new byte[8192];
                                int count;
                                while ((count = zis.read(buffer)) != -1) {
                                    fout.write(buffer, 0, count);
                                }
                                fout.close();
                            }
                            zis.closeEntry();
                        }
                        zis.close();
                    }
                }
            }

            // 🚀 Once the theme overwrite assembly is fully complete, save the current version to the vault.
            prefs.edit().putInt("last_theme_version", currentAppVersion).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // Advanced EQ main sub-page builder
    public void buildEqualizerSettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildEqualizerSettingsUI(this);
    }

    // Preset and profile selection window (Depth 2)
    public void buildEqProfileSelectorUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildEqProfileSelectorUI(this);
    }

    // =========================================================================
    // 🚀 [Fully fixed] Graphic EQ studio with zero-pixel error and the focus-clipping bug resolved
    // =========================================================================
    public void buildGraphicEqualizerUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildGraphicEqualizerUI(this);
    }
    // 🚀 [Native engine 1] Build the M3U list screen
    public void buildM3uPlaylistUI() {
        if (scrollViewBrowser != null) scrollViewBrowser.setVisibility(View.VISIBLE);
        if (listVirtualSongs != null) listVirtualSongs.setVisibility(View.GONE);
        containerBrowserItems.removeAllViews();
        tvBrowserPath.setText(t("Library") + ": "+t("Playlists"));

        // Set up a dedicated playlist storage bin
        File playlistDir = new File("/storage/sdcard0/Y1_Playlists");
        if (!playlistDir.exists()) playlistDir.mkdirs();

        File[] files = playlistDir.listFiles();
        List<File> m3uFiles = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                // 🚀 [Fix] Collect all files whose names end in either (OR) .m3u or .m3u8!
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) {
                    m3uFiles.add(f);
                }
            }
        }

        // Sort cleanly, case-insensitive alphabetical order
        java.util.Collections.sort(m3uFiles, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

        if (m3uFiles.isEmpty()) {
            android.view.View btnEmpty = createListButtonWithIcon("\uE05F", t("No .m3u files found in Y1_Playlists"), ThemeManager.getTextColorSecondary());
            containerBrowserItems.addView(btnEmpty);
        } else {
            for (final File m3u : m3uFiles) {
                // Strip the extension and list only the pure playlist name
                String cleanName = m3u.getName().substring(0, m3u.getName().lastIndexOf("."));
//                Button b = createListButton("📝 " + cleanName);
                android.view.View b = createListButtonWithIcon("\uE05F", cleanName);

                // 1. Existing behavior: short press enters the playlist
                b.setOnClickListener(v -> {
                    clickFeedback();
                    currentBrowserMode = BROWSER_M3U_SONGS;
                    currentM3uFile = m3u;
                    buildM3uSongsUI(m3u);
                });

                // 🚀 2. New behavior: long press physically deletes the playlist file itself!
                b.setLongClickable(true); // allow long press
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        clickFeedback();
                        new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(t("Delete Playlist"))
                                .setMessage(t("Are you sure you want to completely delete this playlist file?") + "\n\n[ " + m3u.getName() + " ]")
                                .setPositiveButton(t("Delete"), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        // 🚀 Actually delete the .m3u / .m3u8 file
                                        if (m3u.exists() && m3u.delete()) {
                                            Toast.makeText(MainActivity.this, t("Playlist deleted."), Toast.LENGTH_SHORT).show();
                                            buildM3uPlaylistUI(); // 🚀 Refresh the screen right away so it disappears from the list!
                                        } else {
                                            Toast.makeText(MainActivity.this, t("Failed to delete."), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .setNegativeButton(t("Cancel"), null)
                                .show();

                        return true; // 🚀 Must return true so the short-click (enter) event doesn't also fire redundantly.
                    }
                });

                containerBrowserItems.addView(b);
            }
        }
        if (containerBrowserItems.getChildCount() > 0) containerBrowserItems.getChildAt(0).requestFocus();
    }

    // 🚀 [Native engine 2] M3U live text-path parser (the core detail work)
    private List<SongItem> parseM3uFile(File m3uFile) {
        List<SongItem> songs = new ArrayList<>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m3uFile), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Smoothly skip blank lines or comment lines (like #EXTINF)
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Auto-fix Windows-style backslashes (\) by swapping them to Linux/Android-style forward slashes (/)!
                line = line.replace("\\", "/");

                File audioFile = new File(line);
                // If it looks like a PC-relative-path file, force-map it based on the default Music folder!
                if (!audioFile.isAbsolute()) {
                    audioFile = new File(rootFolder, line);
                }

                // Physically verify the final check that a real track actually lives at that path
                if (audioFile.exists() && isAudioFile(audioFile)) {
                    String title = audioFile.getName();
                    // Strip the extension
                    int dotIdx = title.lastIndexOf(".");
                    if (dotIdx > 0) title = title.substring(0, dotIdx);

                    // For native playback speed, skip the heavy tag lookup and just assemble the title at lightning speed!
                    songs.add(new SongItem(audioFile, title, "M3U Playlist", ""));
                }
            }
            br.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return songs;
    }

    // 🚀 [Native engine 3] Feed the extracted tracks directly into the ultra-fast recycler engine (ListView)
    private void buildM3uSongsUI(File m3uFile) {
        scrollViewBrowser.setVisibility(View.GONE);
        listVirtualSongs.setVisibility(View.VISIBLE);
        tvBrowserPath.setText(t("Playlist") + ": " + m3uFile.getName().substring(0, m3uFile.getName().lastIndexOf(".")));

        virtualSongList.clear();
        currentScrollIndexList.clear();

        List<SongItem> songs = parseM3uFile(m3uFile);

        // Preserved exactly "as-is" in the order the user manually arranged them in the .m3u file, without sorting!
        for (SongItem song : songs) {
            virtualSongList.add(song.file);
            currentScrollIndexList.add(song.title);
        }

        if (songs.isEmpty()) {
            Toast.makeText(this, "No valid tracks found in this playlist.", Toast.LENGTH_SHORT).show();
        }

        SongListAdapter adapter = new SongListAdapter(songs);
        listVirtualSongs.setAdapter(adapter);
        listVirtualSongs.post(() -> {
            if (listVirtualSongs.getChildCount() > 0) listVirtualSongs.getChildAt(0).requestFocus();
        });
    }

    // 🚀 [Design overhaul and wheel bug fully fixed]
    public void showAddToPlaylistDialog(final File songFile) {
        final File playlistDir = new File("/storage/sdcard0/Y1_Playlists");
        if (!playlistDir.exists()) playlistDir.mkdirs();

        File[] files = playlistDir.listFiles();
        final List<File> playlistFiles = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) {
                    playlistFiles.add(f);
                }
            }
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setBackgroundColor(0xFF222222);

        final android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);
        scrollView.addView(layout);

        // 🚀 1. Instead of the dated system title, we draw our own 'custom title' identical to the main screen!
        TextView tvTitle = new TextView(this);
        tvTitle.setText("━ ADD TO PLAYLIST ━");
        tvTitle.setTextColor(0xFFFFFFFF); // Pretty sky-blue!
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 30);
        tvTitle.setTextSize(16);
        layout.addView(tvTitle);

        // 🚀 2. A 'popup-dedicated steering wheel (Listener)' that also recognizes the wheel (21, 22) inside the popup window
        android.view.View.OnKeyListener dialogWheelListener = new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (keyCode == 21) { // wheel up (UP)
                        int idx = layout.indexOfChild(v);
                        for (int i = idx - 1; i >= 0; i--) {
                            if (layout.getChildAt(i).isFocusable()) {
                                layout.getChildAt(i).requestFocus();
                                clickFeedback();
                                return true;
                            }
                        }
                        return true; // Stop if the top is blocked
                    }
                    if (keyCode == 22) { // wheel down (DOWN)
                        int idx = layout.indexOfChild(v);
                        for (int i = idx + 1; i < layout.getChildCount(); i++) {
                            if (layout.getChildAt(i).isFocusable()) {
                                layout.getChildAt(i).requestFocus();
                                clickFeedback();
                                return true;
                            }
                        }
                        return true; // Stop if the bottom is blocked
                    }
                }
                return false;
            }
        };

        // 🚀 3. When building the system popup, remove the stock title (.setTitle) to keep it hidden!
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scrollView)
                .create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 4. [First button] Create a new playlist
        Button btnNew = createListButton("➕ Create New Playlist");
        btnNew.setTextColor(0xFF00FFFF);
        btnNew.setOnKeyListener(dialogWheelListener); // 🚀 Wire the popup-dedicated steering wheel to the button!
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                int count = 1;
                File newPlaylistFile;
                do {
                    newPlaylistFile = new File(playlistDir, "Playlist " + count + ".m3u8");
                    count++;
                } while (newPlaylistFile.exists());

                writeSongToM3uFile(newPlaylistFile, songFile, false);
                Toast.makeText(MainActivity.instance, t("Created Playlist ") + (count-1) +" "+ t("successfully!"), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        layout.addView(btnNew);

        // 5. [Remaining buttons] List of existing playlist files
        for (final File targetM3u : playlistFiles) {
            String cleanName = targetM3u.getName().substring(0, targetM3u.getName().lastIndexOf("."));
            Button btnExisting = createListButton("📝 " + cleanName);
            btnExisting.setOnKeyListener(dialogWheelListener); // 🚀 Wire the popup-dedicated steering wheel to the button!
            btnExisting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    writeSongToM3uFile(targetM3u, songFile, true);
                    Toast.makeText(MainActivity.instance, t("Added to playlist successfully!"), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            });
            layout.addView(btnExisting);
        }

        dialog.show();

        // 6. Automatically focus the 'first button' when the popup opens
        layout.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Index 0 of the layout is the 'custom title text', so give focus to index 1 (btnNew) instead!
                if (layout.getChildCount() > 1) layout.getChildAt(1).requestFocus();
            }
        }, 50);
    }
    // 🚀 [Custom playlist engine, step 4] Live physical hard-disk recording stream
    private void writeSongToM3uFile(File m3uFile, File songFile, boolean append) {
        try {
            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(m3uFile, append), "UTF-8"));

            // For a new file, write out the standard playlist header spec.
            if (!append) {
                bw.write("#EXTM3U\n");
            }

            // Safely mark the song's absolute path, then add a line break.
            bw.write(songFile.getAbsolutePath() + "\n");
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // 🚀 [Custom playlist engine, step 5] Popup for deleting a track inside a playlist
    public void showRemoveFromPlaylistDialog(final File songFile) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Remove Song"))
                .setMessage(t("Do you want to remove") + "\n'" + songFile.getName() + "'\n" + t("from this playlist?"))
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        clickFeedback();
                        removeSongFromM3uFile(currentM3uFile, songFile);
                        buildM3uSongsUI(currentM3uFile); // 🚀 Redraw the list screen immediately to remove it!
                    }
                })
                .setNegativeButton(t("Cancel"), null)
                .show();
    }

    // 🚀 [Custom playlist engine, step 6] Feature that finds and removes only the matching song's text line in the M3U file
    public void removeSongFromM3uFile(File m3uFile, File songFile) {
        if (m3uFile == null || !m3uFile.exists()) return;
        try {
            List<String> lines = new ArrayList<>();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m3uFile), "UTF-8"));
            String line;
            boolean isRemoved = false; // 💡 Guard so that if the same song appears multiple times, only one is removed at a time

            while ((line = br.readLine()) != null) {
                String cleanLine = line.replace("\\", "/").trim();

                // Pass comments and blank lines straight through, unremoved, to preserve the M3U format's integrity.
                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) {
                    lines.add(line);
                    continue;
                }

                // If the filename matches the song to delete and hasn't already been removed this pass (skipped = not added to the list = deleted)
                if (!isRemoved && cleanLine.endsWith(songFile.getName())) {
                    isRemoved = true;
                    continue;
                }

                lines.add(line); // Keep any song that isn't the deletion target
            }
            br.close();

            // Overwrite (Append = false) the original M3U file with the updated (one-song-shorter) list.
            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(m3uFile, false), "UTF-8"));
            for (String l : lines) {
                bw.write(l + "\n");
            }
            bw.close();

            Toast.makeText(this, t("Removed successfully."), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🚀 [Bonus] A dedicated popup that also lets you long-press inside Favorites to remove it right away!
    public void showRemoveFromFavoritesDialog(final File songFile) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Remove from Favorites"))
                .setMessage(t("Remove this song from your favorites list?"))
                .setPositiveButton(t("Remove"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        clickFeedback();
                        if (favoritePaths.contains(songFile.getAbsolutePath())) {
                            favoritePaths.remove(songFile.getAbsolutePath());
                            try { libraryCacheDb.setFavorite(songFile.getAbsolutePath(), false); } catch (Exception e) { Log.d(TAG, "showRemoveFromFavoritesDialog failed", e); }
                            buildVirtualSongsForFavorites(); // Instantly refresh the screen
                            Toast.makeText(MainActivity.instance, t("Removed from Favorites."), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(t("Cancel"), null)
                .show();
    }

    // 🚀 [New engine] Extracts the file extension and metadata to determine lossless status and bitrate (kbps).
    // Navidrome browse-screen UI and the download-queue engine live in NavidromeManager -- see
    // that class for details. Kept as thin pass-throughs here for the handful of call sites
    // outside that cluster (AudioPlayerManager, KeyEventRouter, this Activity's own onDestroy).
    private void updateNavidromeQualityInfo(com.themoon.y1.managers.AudioPlayerManager am) {
        com.themoon.y1.managers.NavidromeManager.getInstance().updateNavidromeQualityInfo(this, am);
    }

    public void buildNavidromeUI() {
        com.themoon.y1.managers.NavidromeManager.getInstance().buildNavidromeUI(this);
    }

    public void buildNavidromeArtistsUI(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
        com.themoon.y1.managers.NavidromeManager.getInstance().buildNavidromeArtistsUI(this, artists);
    }

    private void releaseNavidromeDownloadLocks() {
        com.themoon.y1.managers.NavidromeManager.getInstance().releaseNavidromeDownloadLocks(this);
    }

    public void loadNavidromeCoverArt(final com.themoon.y1.subsonic.SubsonicSong song) {
        com.themoon.y1.managers.NavidromeManager.getInstance().loadNavidromeCoverArt(this, song);
    }

    public void updateAudioQualityInfo(File audioFile) {
        if (layoutAudioQualityContainer == null || audioFile == null || !audioFile.exists()) {
            if (layoutAudioQualityContainer != null) layoutAudioQualityContainer.setVisibility(View.GONE);
            return;
        }

        // 1. Determine format and losslessness from the file extension
        String ext = "";
        String name = audioFile.getName().toLowerCase();
        int dotIdx = name.lastIndexOf(".");
        if (dotIdx > 0) ext = name.substring(dotIdx + 1).toUpperCase();

        boolean isLossless = ext.equals("FLAC") || ext.equals("WAV") || ext.equals("APE") || ext.equals("ALAC");
        String formatTag = isLossless ? "LOSSLESS" : "LOSSY";
        if (ext.equals("WAV")) formatTag = "UNCOMPRESSED";

        // 2. Attempt to extract the bitrate (kbps)
        String bitrateStr = "";
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(audioFile.getAbsolutePath());
            String br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null && !br.isEmpty()) {
                int bps = Integer.parseInt(br);
                bitrateStr = (bps / 1000) + " kbps";
            }
            mmr.release();
        } catch (Exception e) {
            Log.d(TAG, "updateAudioQualityInfo failed", e);
        }

        // ⭕ [Overwrite with the code below]
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        if (bitrateStr.isEmpty() && am.getDuration() > 0) {
            try {
                int durationMs = am.getDuration();
                if (durationMs > 0) {
                    long fileSize = audioFile.length();
                    int kbps = (int) ((fileSize * 8000) / durationMs);
                    bitrateStr = kbps + " kbps";
                }
            } catch (Exception e) {
                Log.d(TAG, "updateAudioQualityInfo failed", e);
            }
        }

        // 4. 🚀 Inject each one neatly as its own vertical list line
        tvQualityExt.setText(ext);
        tvQualityFormat.setText(formatTag);

        if (!bitrateStr.isEmpty()) {
            tvQualityBitrate.setText(bitrateStr);
            tvQualityBitrate.setVisibility(View.VISIBLE);
        } else {
            tvQualityBitrate.setVisibility(View.GONE);
        }

        layoutAudioQualityContainer.setVisibility(View.VISIBLE);
        qualityInfoHandler.removeCallbacks(hideQualityInfoTask);
        qualityInfoHandler.postDelayed(hideQualityInfoTask, 3000);
    }
    // 🚀 [New 1] Focus-retention listener builder dedicated to audiobook buttons
    public void setupAudiobookProgress(final android.widget.Button btn, final int pos, final int dur) {
        // Draw when it first appears on screen
        applyProgressBackground(btn, pos, dur, btn.hasFocus());

        // 💡 Overwrites the button's original plain solid-color focus listener with a 'progress-dedicated listener'!
        btn.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(android.view.View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    // Redraw the progress with the on-focus color
                    applyProgressBackground(btn, pos, dur, true);
                } else {
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                    // Redraw the progress with the normal off-focus color (to prevent it from disappearing!)
                    applyProgressBackground(btn, pos, dur, false);
                }
            }
        });
    }

    // 🚀 [New 2] Progress-rendering function that smartly adjusts color based on focus state (isFocused)
    public void applyProgressBackground(android.widget.Button btn, int currentMs, int totalMs, boolean isFocused) {
        if (currentMs <= 0 || totalMs <= 0) return;

        int progressPercent = (int) (((float) currentMs / totalMs) * 10000);
        if (progressPercent > 10000) progressPercent = 10000;

        int baseColor = isFocused ? ThemeManager.getListButtonFocusedBg() : ThemeManager.getListButtonNormalBg();
        android.graphics.drawable.Drawable baseBg = createButtonBackground(baseColor);

        int progressColor;
        if (isFocused) {
            progressColor = 0x66FFFFFF; // When the wheel lands on it: an eye-catching translucent white
        } else {
            progressColor = (ThemeManager.getListButtonFocusedBg() & 0x00FFFFFF) | 0x44000000; // Normally: a translucent theme color
        }
        android.graphics.drawable.Drawable progressBg = createButtonBackground(progressColor);

        android.graphics.drawable.ClipDrawable clipProgress = new android.graphics.drawable.ClipDrawable(progressBg, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        clipProgress.setLevel(progressPercent);

        android.graphics.drawable.LayerDrawable layerBg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{baseBg, clipProgress});

        // 🚀 [Margin-vanish bug fully blocked!] Safely remember the existing padding before changing the background.
        int pLeft = btn.getPaddingLeft();
        int pTop = btn.getPaddingTop();
        int pRight = btn.getPaddingRight();
        int pBottom = btn.getPaddingBottom();

        btn.setBackground(layerBg); // 🚨 Android zeroes out the padding here!

        btn.setPadding(pLeft, pTop, pRight, pBottom); // 💡 Instantly restore the padding that was wiped out, back to 100%!

        // Handling to prevent text clipping and duplicate display
        String originalText = btn.getText().toString();
        if (originalText.contains("  ⏱")) {
            originalText = originalText.substring(0, originalText.indexOf("  ⏱"));
        }

        // 🚀 [Fix] 20 characters is too short and leaves the right side looking empty. Generously extended it to 45 characters!
        int maxLength = 45;
        if (originalText.length() > maxLength) {
            originalText = originalText.substring(0, maxLength) + "...";
        }

        int min = (currentMs / 1000) / 60;
        int maxMin = (totalMs / 1000) / 60;
        btn.setText(originalText + "  ⏱ [" + min + "m / " + maxMin + "m]");
    }

    // 🚀 [New engine] Full-screen popup controller for real-time wheel-driven frequency adjustment
    public void showRadioFreqPopup(float freq) {
        com.themoon.y1.managers.FmRadioUiManager.getInstance().showFreqPopup(this, freq);
    }
    // 🚀 [Ultimate architecture complete] Dynamic layout engine that switches in real time purely by looking at the parent_id relationships between objects, with no hardcoded terms
    private void updateFocusPreviewLiveContent(ThemeManager.MenuElement focusedElement) {
        FrameLayout canvas = (FrameLayout) layoutMainMenu.findViewWithTag("dynamic_canvas");
        if (canvas == null) return;

        // Pull out the full list of menu elements registered in the current theme.
        java.util.List<ThemeManager.MenuElement> allElements = ThemeManager.getCurrentTheme().menuElements;

        boolean hasLiveWidgetActivated = false;

        // 🚀 [First-pass scan] Thoroughly inspect every widget laid out on the canvas and apply branch filtering!
        for (ThemeManager.MenuElement el : allElements) {
            if (el.type.equals("button") || el.type.equals("box") || el.type.equals("list_box")) continue;

            // Look up the widget's actual object address inside the canvas.
            View widgetView = null;
            if (el.type.equals("widget_clock")) widgetView = tvWidgetClock;
            else if (el.type.equals("widget_battery")) widgetView = widgetBatteryView;
            else if (el.type.equals("widget_album")) widgetView = layoutWidgetAlbumContainer;
            else if (el.type.equals("widget_analog_clock")) widgetView = customAnalogClockView;
            else if (el.type.equals("widget_circular_battery")) widgetView = customCircularBatteryView;
            else if (el.type.equals("widget_focus_image")) widgetView = ivWidgetFocusImage;

            if (widgetView == null) continue;

            // 💡 [Key condition] Has this widget declared the "ID of the currently focused button" as its watch target (visibleOnFocus)?!
            if (focusedElement.id.equals(el.visibleOnFocus)) {

                // 👉 Match found! Turn on the real widget's power at the position the user wants (x, y as written in the JSON)!
                widgetView.setVisibility(View.VISIBLE);
                hasLiveWidgetActivated = true;

                // widget_focus_image is a per-button preview image (e.g. the Bluetooth icon shown
                // while Bluetooth has focus) -- it needs its own previewImage bitmap loaded here,
                // otherwise this branch only flips visibility and the ImageView keeps showing
                // whatever the previously-focused button last set (stuck on the last icon).
                if (el.type.equals("widget_focus_image") && el.previewImage != null && !el.previewImage.isEmpty()) {
                    android.graphics.Bitmap bmpPreview = ThemeManager.getCustomIcon(el.previewImage, this, 0);
                    if (bmpPreview != null) ivWidgetFocusImage.setImageBitmap(bmpPreview);
                    else ivWidgetFocusImage.setImageDrawable(null);
                }

                // For the album-info widget specifically, live-refresh it by injecting the real Now Playing data.
                if (el.type.equals("widget_album")) {
                    if (tvWidgetAlbumTitle != null && tvPlayerTitle != null) tvWidgetAlbumTitle.setText(tvPlayerTitle.getText());
                    if (tvWidgetAlbumArtist != null && tvPlayerArtist != null) tvWidgetAlbumArtist.setText(tvPlayerArtist.getText());
                    if (ivWidgetAlbum != null) {
                        if (lastAlbumArtBytes != null && lastAlbumArtBytes.length > 0) {
                            try {
                                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                                opts.inSampleSize = 2;
                                ivWidgetAlbum.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes.length, opts));
                            } catch (Exception e) { ivWidgetAlbum.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", this, R.drawable.default_album)); }
                        } else {
                            ivWidgetAlbum.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", this, R.drawable.default_album));
                        }
                    }
                }
            }
            // 💡 If this widget's watch target (visibleOnFocus) is empty, follow the global switch (isWidgetOn) rule the user set in the theme settings!
            else if (el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                if (el.type.equals("widget_clock")) widgetView.setVisibility(isWidgetClockOn ? View.VISIBLE : View.GONE);
                else if (el.type.equals("widget_battery")) widgetView.setVisibility(isWidgetBatteryOn ? View.VISIBLE : View.GONE);
                else if (el.type.equals("widget_album")) widgetView.setVisibility(isWidgetAlbumOn ? View.VISIBLE : View.GONE);
                else if (el.type.equals("widget_analog_clock")) widgetView.setVisibility(isWidgetAnalogClockOn ? View.VISIBLE : View.GONE);
                else if (el.type.equals("widget_circular_battery")) widgetView.setVisibility(isWidgetCircularBatteryOn ? View.VISIBLE : View.GONE);
                else if (el.type.equals("widget_focus_image")) widgetView.setVisibility(isWidgetFocusImageOn ? View.VISIBLE : View.GONE);
            }
            // 💡 Turn off the power if this is a widget dedicated to some other button, unrelated to the currently focused one.
            else {
                widgetView.setVisibility(View.GONE);
            }
        }

        // 🚀 [Second-pass scan] If focus landed on some button but there isn't a single special widget in the JSON that treats that button as its parent?
        // To keep faithful to the 100% minimalist design mission, fill the button's own preview_image into the full-screen preview viewport (ivWidgetFocusImage)!
        if (ivWidgetFocusImage != null) {
            if (!hasLiveWidgetActivated && focusedElement.previewImage != null && !focusedElement.previewImage.isEmpty()) {
                ivWidgetFocusImage.setVisibility(View.VISIBLE);
                android.graphics.Bitmap bmpPreview = ThemeManager.getCustomIcon(focusedElement.previewImage, this, 0);
                if (bmpPreview != null) ivWidgetFocusImage.setImageBitmap(bmpPreview);
                else ivWidgetFocusImage.setImageDrawable(null);
            } else if (!hasLiveWidgetActivated) {
                // If there's no live widget to show and no image assigned either, cleanly clear the preview area for a plain button.
                ivWidgetFocusImage.setImageDrawable(null);
            }
        }
    }

    // 💡 [Smart bug-fix complete] Removes the top header and keeps the focus lock intact while controlling the middle menu, in the editor sub-menu
    public void buildMainMenuVisibilitySettingsUI() {
        com.themoon.y1.managers.SettingsUiManager.getInstance().buildMainMenuVisibilitySettingsUI(this);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NAVIDROME BROWSER
    // ═══════════════════════════════════════════════════════════════════════════


    /** Double-click center on Now Playing: playback/queue/Wi-Fi/Bluetooth shortcuts without
     *  leaving the player screen (center long-press is already claimed by screen-off there). */
    public void showQuickMenu() {
        String favPath = getCurrentTrackPathForFavorites();
        final boolean isFav = favPath != null && favoritePaths.contains(favPath);

        showThemedOptionsDialog(t("Quick Menu"), null,
                new String[]{
                        null,
                        "", // format_list_bulleted
                        "", // wifi
                        ""  // bluetooth
                },
                new String[]{
                        isFav ? "♥  " + t("Remove Favorite") : "♡  " + t("Add Favorite"),
                        t("Playlist"),
                        t("Wi-Fi"),
                        t("Bluetooth")
                },
                new Runnable[]{
                        new Runnable() { @Override public void run() { toggleFavorite(); } },
                        new Runnable() { @Override public void run() { showQueueDialog(); } },
                        new Runnable() { @Override public void run() { changeScreen(STATE_WIFI); } },
                        new Runnable() { @Override public void run() { changeScreen(STATE_BLUETOOTH); } }
                });
    }

    /** Queue viewer opened from the quick menu: click a row to jump to that track,
     *  long-press to remove it from the queue (the currently-playing row can't be removed —
     *  skip to it moving on is what nextTrack()/prevTrack() are for). */
    private void showQueueDialog() {
        if (currentPlaylist.isEmpty()) {
            Toast.makeText(this, t("Playlist is empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createButtonBackground(0xF2151515));
        root.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (14 * d));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(t("Playlist") + " (" + currentPlaylist.size() + ")");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        root.addView(tvTitle);

        final android.widget.ListView listView = new android.widget.ListView(this);
        listView.setDivider(null);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d));
        listParams.topMargin = (int) (10 * d);
        listView.setLayoutParams(listParams);

        final int[] cursor = { currentIndex };

        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                boolean isPlaying = position == currentIndex;
                boolean isCursor = position == cursor[0];
                String label = currentPlaylist.get(position).getName();
                if (isPlaying) label = "▶  " + label;
                tv.setText(label);
                tv.setBackground(isCursor ? createButtonBackground(ThemeManager.getListButtonFocusedBg()) : null);
                tv.setTextColor(isCursor ? ThemeManager.getListButtonFocusedTextColor()
                        : isPlaying ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000)
                        : ThemeManager.getTextColorPrimary());
                tv.setTypeface(ThemeManager.getCustomFont());
                tv.setTextSize(15f);
                tv.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return tv;
            }
        };
        for (File f : currentPlaylist) adapter.add(f.getName());
        listView.setAdapter(adapter);
        listView.setSelection(currentIndex);

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                clickFeedback();
                dialog.dismiss();
                com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(currentPlaylist, position);
            }
        });
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == currentIndex) {
                    Toast.makeText(MainActivity.this, t("Can't remove the track that's playing"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                clickFeedback();
                currentPlaylist.remove(position);
                if (position < currentIndex) currentIndex--;
                if (position < cursor[0] || cursor[0] >= currentPlaylist.size()) {
                    cursor[0] = Math.max(0, Math.min(cursor[0], currentPlaylist.size() - 1));
                }
                adapter.clear();
                for (File f : currentPlaylist) adapter.add(f.getName());
                adapter.notifyDataSetChanged();
                return true;
            }
        });

        root.addView(listView);

        TextView hint = new TextView(this);
        hint.setText(t("Click: jump to track") + "   •   " + t("Long-press: remove"));
        hint.setTextSize(11f);
        hint.setTypeface(ThemeManager.getCustomFont());
        hint.setTextColor(ThemeManager.getTextColorSecondary());
        hint.setPadding(0, (int) (8 * d), 0, 0);
        root.addView(hint);

        // Wheel rotation → moves our own cursor (not ListView's built-in selection, which
        // doesn't draw/track reliably once the window has seen a touch event) and scrolls
        // it into view. 21/22 are the wheel's synthetic key codes.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != 21 && keyCode != 22) return false;
                cursor[0] += keyCode == 22 ? 1 : -1;
                cursor[0] = Math.max(0, Math.min(cursor[0], currentPlaylist.size() - 1));
                listView.setSelection(cursor[0]);
                adapter.notifyDataSetChanged();
                clickFeedback();
                return true;
            }
        });

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        listView.requestFocus();
    }

    /** Long-press menu for library songs: playlist add or delete from device. */
    public void showSongOptionsDialog(final java.io.File file) {
        showThemedOptionsDialog(file.getName(), null,
                new String[]{ "➕  " + t("Add to Playlist"), "🗑  " + t("Delete from Device"), t("Cancel") },
                new Runnable[]{
                        new Runnable() { @Override public void run() { showAddToPlaylistDialog(file); } },
                        new Runnable() { @Override public void run() { showDeleteSongDialog(file); } },
                        null
                });
    }

    private void showDeleteSongDialog(final java.io.File file) {
        showThemedOptionsDialog(t("Delete from Device"), file.getName(),
                new String[]{ "🗑  " + t("Delete"), t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                if (deleteLibrarySong(file)) {
                                    Toast.makeText(MainActivity.this, "🗑 " + t("Deleted"), Toast.LENGTH_SHORT).show();
                                    if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) buildVirtualSongs();
                                }
                            }
                        },
                        null
                });
    }

    /** Long-press on an album row — wipe the whole album from the device. */
    public void showDeleteAlbumDialog(final String albumName) {
        List<SongItem> active = isAudiobookLibraryMode ? audiobookLibrary : customLibrary;
        final List<SongItem> targets = new ArrayList<>();
        for (SongItem s : active) {
            if (albumName.equals(s.album)) targets.add(s);
        }
        if (targets.isEmpty()) return;
        showThemedOptionsDialog(t("Delete Album"), albumName + "  (" + targets.size() + " " + t("tracks") + ")",
                new String[]{ "🗑  " + t("Delete"), t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                int deleted = 0;
                                for (SongItem s : targets) {
                                    if (deleteLibrarySong(s.file)) deleted++;
                                }
                                Toast.makeText(MainActivity.this, "🗑 " + t("Deleted") + " " + deleted, Toast.LENGTH_SHORT).show();
                                if (currentBrowserMode == BROWSER_ALBUMS) buildVirtualCategories("ALBUM");
                            }
                        },
                        null
                });
    }

    /** Delete a track from the SD card and scrub every launcher record of it:
     *  libraries, favorites, per-track prefs, cover cache, empty folders. */
    public boolean deleteLibrarySong(java.io.File f) {
        String path = f.getAbsolutePath();
        if (f.exists() && !f.delete()) return false;

        java.util.Iterator<SongItem> it = customLibrary.iterator();
        while (it.hasNext()) if (it.next().file.getAbsolutePath().equals(path)) it.remove();
        it = audiobookLibrary.iterator();
        while (it.hasNext()) if (it.next().file.getAbsolutePath().equals(path)) it.remove();
        virtualSongList.remove(f);
        trackNumberMap.remove(path);
        favoritePaths.remove(path);
        try {
            libraryCacheDb.deleteSongState(path);
        } catch (Exception ignored) {
            Log.d(TAG, "deleteLibrarySong failed", ignored);
        }
        try {
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            new java.io.File("/storage/sdcard0/Y1_Covers", base + ".jpg").delete();
        } catch (Exception ignored) {
            Log.d(TAG, "deleteLibrarySong failed", ignored);
        }

        // Prune up to two levels of newly-empty folders, but never the roots
        java.io.File dir = f.getParentFile();
        for (int i = 0; i < 2 && dir != null; i++) {
            String dp = dir.getAbsolutePath();
            if (dp.equals("/storage/sdcard0/Music") || dp.equals("/storage/sdcard0/Audiobooks")
                    || dp.equals("/storage/sdcard0")) break;
            if (!dir.delete()) break; // fails while non-empty — that's the stop signal
            dir = dir.getParentFile();
        }
        return true;
    }

    /**
     * List-style modal matching the launcher UI: themed rounded panel, custom
     * font, and createListButton rows. Handles wheel rotation itself — dialogs
     * swallow keys before MainActivity.onKeyDown, and the wheel's 21/22 codes
     * only move focus between HORIZONTAL neighbours natively.
     */
    public void showThemedOptionsDialog(String title, String subtitle, String[] options, final Runnable[] actions) {
        showThemedOptionsDialog(title, subtitle, null, options, actions);
    }

    /** Same as above but each row can carry a Material Icons codepoint (index-matched to options);
     *  pass null in the icons slot to fall back to the plain text row. */
    public void showThemedOptionsDialog(String title, String subtitle, String[] icons, String[] options, final Runnable[] actions) {
        float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final int maxScrollHeightPx = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Caps how tall this can grow so long option lists (e.g. the quick menu) scroll
                // instead of running off-screen, while short dialogs still just wrap to content.
                int capped = android.view.View.MeasureSpec.makeMeasureSpec(maxScrollHeightPx, android.view.View.MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, capped);
            }
        };
        scroll.setBackground(createButtonBackground(0xF2151515));
        scroll.setVerticalScrollBarEnabled(false);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (14 * d));
        scroll.addView(root, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT, android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        root.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(14f);
            tvSub.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
            tvSub.setTextColor(ThemeManager.getTextColorSecondary());
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvSub.setPadding(0, (int) (2 * d), 0, 0);
            root.addView(tvSub);
        }

        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (10 * d)));
        root.addView(spacer);

        for (int i = 0; i < options.length; i++) {
            final Runnable action = actions[i];
            String icon = icons != null ? icons[i] : null;
            View btn = icon != null ? createListButtonWithIcon(icon, options[i]) : createListButton(options[i]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    dialog.dismiss();
                    if (action != null) action.run();
                }
            });
            root.addView(btn);
        }

        // Wheel rotation → focus walk over the option rows (same logic onKeyDown
        // applies to LinearLayout lists, but scoped to this dialog)
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != 21 && keyCode != 22) return false;
                View cur = root.findFocus();
                int index = cur != null ? root.indexOfChild(cur) : -1;
                int dir = keyCode == 22 ? 1 : -1;
                int count = root.getChildCount();
                int i = index == -1 ? (dir == 1 ? 0 : count - 1) : index + dir;
                for (int steps = 0; steps < count; steps++, i += dir) {
                    if (i < 0) i += count;
                    if (i >= count) i -= count;
                    View n = root.getChildAt(i);
                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                        n.requestFocus();
                        clickFeedback();
                        break;
                    }
                }
                return true;
            }
        });

        dialog.setContentView(scroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Land focus on the first option so the wheel works immediately
        root.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < root.getChildCount(); i++) {
                    View n = root.getChildAt(i);
                    if (n.isFocusable()) { n.requestFocus(); break; }
                }
            }
        });
    }


    private static void installTls12TrustAll() {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
            ctx.init(null, trustAll, null);
            final javax.net.ssl.SSLSocketFactory base = ctx.getSocketFactory();
            // Wrap so every socket explicitly enables TLS 1.2 — Android 4.x defaults to TLS 1.0
            javax.net.ssl.SSLSocketFactory tls12 = new javax.net.ssl.SSLSocketFactory() {
                private java.net.Socket patch(java.net.Socket s) {
                    if (s instanceof javax.net.ssl.SSLSocket)
                        ((javax.net.ssl.SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.1","TLSv1"});
                    return s;
                }
                public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
                public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
                public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean ac) throws java.io.IOException { return patch(base.createSocket(s,h,p,ac)); }
                public java.net.Socket createSocket(String h, int p) throws java.io.IOException { return patch(base.createSocket(h,p)); }
                public java.net.Socket createSocket(String h, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return patch(base.createSocket(h,p,la,lp)); }
                public java.net.Socket createSocket(java.net.InetAddress h, int p) throws java.io.IOException { return patch(base.createSocket(h,p)); }
                public java.net.Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return patch(base.createSocket(a,p,la,lp)); }
            };
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(tls12);
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(
                new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                });
        } catch (Exception ignored) {
            Log.d(TAG, "installTls12TrustAll failed", ignored);
        }
    }

}


