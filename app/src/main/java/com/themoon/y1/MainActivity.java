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
    public int visibleCoversCount = 7; // 💡 Back to 5! Just change this value (e.g. to 7) for testing and everything else updates automatically.

    public android.widget.FrameLayout coverFlowContainer;
    public android.view.View[] cfViews; // 💡 Size is determined dynamically by the UI builder below.




    public boolean isNavigatingToSubMenu = false; // 🚀 [Add one line here!] Guard that prevents focus tangling during direct access
    // Bluetooth A2DP proxy, target device, connecting-state, and reconnect-backoff state all live
    // in BluetoothAudioManager now -- see that class for the field-level rationale.
    private Y1UsbFocusHelper usbFocusHelper;
    // 🚀 [New] Zero-delay, ultra-fast album-art RAM cache memory. Initialized in onCreate(),
    // read by MusicBrowserManager's Cover Flow binding.
    public android.util.LruCache<String, android.graphics.Bitmap> albumArtCache;
    public long lastCoverFlowTime = 0; // 🚀 Time-machine variable for smart speed shifting
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
    public java.util.List<SongItem> uniqueAlbumList = new java.util.ArrayList<>();
    public int currentCoverFlowIndex = 0;

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
    public long lastSeekTime = 0;
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
    public TextView tvWidgetClock;
    // 🚀 [Fix] Renamed to the horizontal Bar class!
    public WidgetBatteryBarView widgetBatteryView;
    // Add the line below near where the other widget variables are declared (e.g. WidgetBatteryBarView widgetBatteryView; etc.).
    public CircularBatteryView customCircularBatteryView;
    public CustomAnalogClockView customAnalogClockView;
    public ImageView ivWidgetAlbum;
    public String lastBrowserFocusText = "";
    public String lastMainMenuFocusAction = "";
    // 🚀 [Added] Title/artist variables dedicated to the album widget
    public TextView tvWidgetAlbumTitle;
    public TextView tvWidgetAlbumArtist;
    // 💡 [Added] Variables dedicated to fast index jump (alphabet scroll)
    public List<String> currentScrollIndexList = new ArrayList<>();
    public long lastWheelTime = 0;
    public int wheelFastCount = 0;
    public static MainActivity instance;
    public long lastTrackChangeTime = 0; // 🚀 Guard variable to block duplicate key signals from the device
    // 💡 [Added] Audio spectrum related variables
    public android.media.audiofx.Visualizer audioVisualizer;
    public AudioVisualizerView visualizerView;
    // 🚀 [New] LRC lyrics parser and UI variables
    public android.widget.ScrollView lyricScrollView;
    public TextView tvLyrics;
    public java.util.TreeMap<Integer, String> currentLyrics = new java.util.TreeMap<>();
    public List<Integer> lyricTimestamps = new ArrayList<>();
    public int lastLyricIndex = -1;
    // 💡 Bucket that holds the "unsynchronized" plain-text lyrics embedded inside the MP3
    public String plainLyrics = null;

    public boolean isVisualizerShowing = false;
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
    public java.util.Set<String> blacklist = new java.util.HashSet<>();
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
    public File currentM3uFile = null; // Address of the M3U file the user is currently viewing
    // 🚀 [Added] Variables dedicated to favorites
    public java.util.Set<String> favoritePaths = new java.util.HashSet<>();
    public TextView tvPlayerFavoriteStatus;

    public int consecutiveErrorCount = 0;
    // 🚀 [Added] Variables for displaying scan progress
    public ProgressBar pbLoadingProgress;
    public TextView tvLoadingProgress;
    public int totalAudioFiles = 0;
    public int scannedAudioFiles = 0;
    // 💡 [Ultra-fast engine] Recycler ListView (to handle thousands of tracks) alongside the existing ScrollView
    public android.widget.ListView listVirtualSongs;
    public View scrollViewBrowser;
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
    public boolean isCustomScanning = false;
    public java.util.HashMap<String, Integer> trackNumberMap = new java.util.HashMap<>();
    public com.themoon.y1.db.LibraryCacheDb libraryCacheDb;
    public int currentScreenState = STATE_MENU;
    // 💡 Temporary variable for the custom date/time settings
    public int dtYear = 2026, dtMonth = 1, dtDay = 1, dtHour = 12, dtMinute = 0;
    public View layoutMainMenu;
    private View layoutBrowserMode;
    public View layoutSettingsMode;
    private View layoutBluetoothMode, layoutWifiMode, layoutWifiKeyboard;
    public View layoutPlayerMode, layoutVolumeOverlay;
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

    public LinearLayout containerBrowserItems;
    public LinearLayout containerSettingsItems;
    public LinearLayout containerBtItems, containerWifiItems;

    private TextView tvStatusClock, tvStatusBattery;
    private ImageView ivStatusBluetooth, ivStatusWifi, ivStatusHeadphone, ivMainBg;

    public TextView tvBrowserPath, tvPlayerTitle, tvPlayerArtist, tvPlayerTimeCurrent, tvPlayerTimeTotal;
    // 🚀 [Variables dedicated to the capsule UI]
    public LinearLayout layoutAudioQualityContainer;
    public TextView tvQualityExt;
    public TextView tvQualityFormat;
    public TextView tvQualityBitrate;

    public TextView tvPlayerTrackCount;
    public ImageView ivPlayerShuffleStatus, ivPlayerRepeatStatus; // 💡 Changed from TextView to ImageView!
    public ProgressBar playerProgress;
    public ProgressBar volumeProgress;
    private ProgressBar pbBrightness, pbStorage;
    private TextView tvBrightnessVal, tvStorageDetails;
    // 💡 [Fix] Removed the manual APP_VERSION variable and only kept the server folder address.
    public boolean is24HourFormat = false;
    private TextView tvServerStatus, tvServerIp;
    private Button btnServerToggle;
    // 🚀 [Added] Advanced loading indicator overlay that covers the whole screen
    public LinearLayout layoutLoadingOverlay;
    public ImageView ivMenuPreview, ivAlbumArt, ivPlayerBgBlur, ivPauseOverlay;

    private Button btnNowPlaying, btnPlay, btnSettings, btnBluetooth, btnRadio;
    public Button btnScanBt, btnScanWifi;
    public LinearLayout btnWifiWebServer;

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

    public android.media.RemoteControlClient remoteControlClient;
    public android.content.ComponentName mediaButtonReceiver;



    private boolean wasWifiOnBeforeSleep = false;
    public int keyboardIndex = 0;
    public String targetWifiSsid = "";
    public String typedPassword = "";
    public boolean isTargetWifiOpen = false;
    // 💡 Variable that tracks whether the media scanner is currently working
    private boolean isMediaScanning = false;
    public AudioManager audioManager;
    public File rootFolder = new File("/storage/sdcard0/Music");
    public File currentFolder = rootFolder;
    public List<File> originalPlaylist = new ArrayList<File>();
    public List<File> currentPlaylist = new ArrayList<File>();
    public int currentIndex = 0;
    public boolean isPausedByHand = true;
    public float currentClockSize = 48f;
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
    public TextView tvFocusPreviewClock; // 🚀 [New engine] Digital clock that ticks inside the live-preview box
    public ImageView ivWidgetFocusImage; // 🚀 [Added] Dynamic focus widget variable

    // 🚀 [New engine variable] Backup vault to remember the existing widget's body and original coordinates
    public LinearLayout layoutWidgetAlbumContainer; // Address of the album widget block

    // 🚀 [Added] Unified registry to globally manage every widget's memory
    public java.util.HashMap<View, ThemeManager.MenuElement> widgetViewRegistry = new java.util.HashMap<>();
    public int currentSystemBrightness = 255;

    public List<String> foundBtDevices = new ArrayList<String>();
    public List<String> foundWifiNetworks = new ArrayList<String>();

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

    public Handler volumeHandler = new Handler();
    public Runnable hideVolumeTask = new Runnable() {
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

    public void connectBluetoothAudio(BluetoothDevice targetDevice) {
        com.themoon.y1.managers.BluetoothAudioManager.getInstance().connectBluetoothAudio(this, targetDevice);
    }
    // Inside the init function (called once when the app launches)
    public void initRemoteControlClient(android.content.Context context) { com.themoon.y1.managers.BluetoothAudioManager.getInstance().initRemoteControlClient(context); }
    public void updateBluetoothMetadata(String title, String artist, String album, android.graphics.Bitmap albumArtBmp) { com.themoon.y1.managers.BluetoothAudioManager.getInstance().updateBluetoothMetadata(title, artist, album, albumArtBmp); }
    public void updateBluetoothPlaybackState(boolean isPlaying) { com.themoon.y1.managers.BluetoothAudioManager.getInstance().updateBluetoothPlaybackState(isPlaying); }
    public void sendBluetoothMetaToCar() { com.themoon.y1.managers.BluetoothAudioManager.getInstance().sendBluetoothMetaToCar(); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.themoon.y1.managers.NetworkTrustManager.installTls12TrustAll();
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
        com.themoon.y1.managers.BundledAssetsInstaller.getInstance().installBundledLanguages(this);

        // 🚀 2. Then start the language engine and load the language the user selected from the extracted files.
        String savedLang = prefs.getString("app_language", "English (Default)");
        com.themoon.y1.managers.LanguageManager.getInstance(this).applyLanguage(savedLang);
        // 🚀 [Dynamic theme file loading] Reads theme files from a folder on the device!
        File themeFolder = new File("/storage/sdcard0/Y1_Themes");

        // 💡 Before reading the theme list, first extract and install any default theme bundled in the APK, if present!
        com.themoon.y1.managers.BundledAssetsInstaller.getInstance().installBundledThemes(this);

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
        com.themoon.y1.managers.MediaLibraryScanManager.getInstance().loadLibraryFromCacheInstant(this);
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

        focusFirstMainMenuButton();

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
            focusFirstMainMenuButton(); // On a normal app launch, focus the main menu as usual
        }
    }
    public void startMediaLibraryScan() { com.themoon.y1.managers.MediaLibraryScanManager.getInstance().startMediaLibraryScan(this); }
    public void startMediaLibraryScan(boolean silent) { com.themoon.y1.managers.MediaLibraryScanManager.getInstance().startMediaLibraryScan(this, silent); }
    public void showLoadingPopup() { com.themoon.y1.managers.MediaLibraryScanManager.getInstance().showLoadingPopup(this); }
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

    // 🚀 [Focus-vanish fix 1] Dynamic themes replace the static list menu (btnNowPlaying) with
    // buttons built at runtime by MainMenuManager; btnNowPlaying is GONE for those themes, so
    // requestFocus() on it is a silent no-op. Find the real dynamically created button 0 (ID:
    // 10000) first, and only fall back to btnNowPlaying if the dynamic menu hasn't been built yet.
    private void focusFirstMainMenuButton() {
        View dynamicFirstBtn = findViewById(10000);
        if (dynamicFirstBtn != null) {
            dynamicFirstBtn.requestFocus();
        } else if (btnNowPlaying != null) {
            btnNowPlaying.requestFocus();
        }
    }

    // A view can't take real (visible, window-level) focus until its window actually has input
    // focus -- calling requestFocus() during onCreate() sets the view's local focus state but
    // gets silently dropped once the window really attaches/focuses shortly after, which is why
    // the main menu showed no highlight on cold boot until navigating into a submenu and back
    // (that path runs after the window already has focus, so the same call works fine there).
    // Re-applies focus every time the window regains focus while sitting on the menu with
    // nothing focused -- window focus can be lost/regained more than once during boot (boot
    // animation, the wheel-lock overlay), so this can't be a one-shot "first call only" guard;
    // it just needs to be idempotent, same as changeScreen(STATE_MENU)'s equivalent restore.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && currentScreenState == STATE_MENU) {
            View cur = getCurrentFocus();
            if (cur == null || cur.getVisibility() != View.VISIBLE) {
                focusFirstMainMenuButton();
            }
        }
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
            statusBar.setBackgroundColor(ThemeManager.getStatusBarBackgroundColor());
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
                if (c == null || c.getVisibility() != View.VISIBLE) {
                    focusFirstMainMenuButton();
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

    // Bluetooth/Wi-Fi settings-screen UI construction lives in ConnectivityScreenManager -- see
    // that class for details. Kept as thin pass-throughs here for call sites elsewhere in this
    // Activity (the broadcast receiver, buildConnectivityGroupUI, the wifi keyboard flow).
    private void connectToWifi() {
        com.themoon.y1.managers.ConnectivityScreenManager.getInstance().connectToWifi(this);
    }

    private void startBluetoothScan() {
        com.themoon.y1.managers.ConnectivityScreenManager.getInstance().startBluetoothScan(this);
    }

    private void addBluetoothItemToUI(String name, final BluetoothDevice device, boolean isPaired) {
        com.themoon.y1.managers.ConnectivityScreenManager.getInstance().addBluetoothItemToUI(this, name, device, isPaired);
    }

    private void startWifiScan() {
        com.themoon.y1.managers.ConnectivityScreenManager.getInstance().startWifiScan(this);
    }

    private void updateWifiUI(List<ScanResult> results) {
        com.themoon.y1.managers.ConnectivityScreenManager.getInstance().updateWifiUI(this, results);
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
    public void downloadAndInstallApk(final String apkUrl) { com.themoon.y1.managers.ApkInstallManager.getInstance().downloadAndInstallApk(this, apkUrl); }

    public boolean isAudioFile(File f) {
        if (f == null || !f.isFile())
            return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".ogg")
                || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".ape") || name.endsWith(".wma");
    }

    public boolean isApkFile(File f) {
        if (f == null || !f.isFile())
            return false;
        return f.getName().toLowerCase().endsWith(".apk");
    }

    public boolean isImageFile(File f) {
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
    public void buildVirtualSongsForFavorites() {
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
    // Music library browser (folder tree, virtual categories, audiobook mode) and Cover Flow
    // (3D transform math, reflection bitmaps, z-index layering) live in MusicBrowserManager --
    // see that class for details. Kept as thin pass-throughs here for call sites elsewhere in
    // this Activity, KeyEventRouter, and CategoryListAdapter.
    public void buildFileBrowserUI() {
        com.themoon.y1.managers.MusicBrowserManager.getInstance().buildFileBrowserUI(this);
    }

    public void buildVirtualCategories(final String type) {
        com.themoon.y1.managers.MusicBrowserManager.getInstance().buildVirtualCategories(this, type);
    }

    public char getInitialChar(String text) {
        return com.themoon.y1.managers.MusicBrowserManager.getInstance().getInitialChar(this, text);
    }

    public android.graphics.Bitmap getScaledThemedIcon(String iconFileName, int size) {
        return com.themoon.y1.managers.MusicBrowserManager.getInstance().getScaledThemedIcon(this, iconFileName, size);
    }

    public void buildCoverFlowUI() {
        com.themoon.y1.managers.MusicBrowserManager.getInstance().buildCoverFlowUI(this);
    }

    public void scrollCoverFlow(boolean isNext) {
        com.themoon.y1.managers.MusicBrowserManager.getInstance().scrollCoverFlow(this, isNext);
    }

    public void buildVirtualSongs() {
        com.themoon.y1.managers.MusicBrowserManager.getInstance().buildVirtualSongs(this);
    }
    // The theme-driven dynamic main menu (JSON element parsing, widget/button construction, the
    // main-menu button action-routing switch) lives in MainMenuManager -- see that class for
    // details. Kept as a thin pass-through here for the one call site in this Activity.
    private void buildDynamicMainMenuUI() {
        com.themoon.y1.managers.MainMenuManager.getInstance().buildDynamicMainMenuUI(this);
    }
    public void collectAudioFilesAsFile(File dir, List<File> list) {
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
    public void installApk(File apkFile) { com.themoon.y1.managers.ApkInstallManager.getInstance().installApk(this, apkFile); }

    // 🚀 [New engine] The dedicated worker that determines the spectrum/lyrics state in real time and switches the screen accordingly!
    private void refreshVisualizerState() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().refreshVisualizerState(this); }
    // 💡 Pressing the center button (click) just toggles the switch and calls the refresh worker.
    private void toggleVisualizer() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().toggleVisualizer(this); }
    public void setupVisualizer() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().setupVisualizer(this); }
    private void loadLyrics(File audioFile) { com.themoon.y1.managers.NowPlayingUiManager.getInstance().loadLyrics(this, audioFile); }
    public String getRepeatModeText(int mode) { return com.themoon.y1.managers.NowPlayingUiManager.getInstance().getRepeatModeText(mode); }
    public void updatePlayerStatusIndicators() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().updatePlayerStatusIndicators(this); }
    public String getCurrentTrackPathForFavorites() { return com.themoon.y1.managers.NowPlayingUiManager.getInstance().getCurrentTrackPathForFavorites(this); }
    public void toggleFavorite() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().toggleFavorite(this); }
    public void updatePlayerUI() { com.themoon.y1.managers.NowPlayingUiManager.getInstance().updatePlayerUI(this); }
    public void adjustVolume(boolean up) { com.themoon.y1.managers.NowPlayingUiManager.getInstance().adjustVolume(this, up); }
    private String formatTime(int ms) { return com.themoon.y1.managers.NowPlayingUiManager.getInstance().formatTime(ms); }
    public boolean handleMediaSeekKeyRepeat(KeyEvent event, int seekMs) { return com.themoon.y1.managers.NowPlayingUiManager.getInstance().handleMediaSeekKeyRepeat(this, event, seekMs); }

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
        com.themoon.y1.managers.TrackInfoFetchManager.getInstance().fetchTrackInfoFromInternet(this, track, originalQuery, hasValidTags, origTitle, origArtist);
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
    public void buildM3uPlaylistUI() { com.themoon.y1.managers.PlaylistFavoritesManager.getInstance().buildM3uPlaylistUI(this); }
    public void buildM3uSongsUI(File m3uFile) { com.themoon.y1.managers.PlaylistFavoritesManager.getInstance().buildM3uSongsUI(this, m3uFile); }
    public void showAddToPlaylistDialog(final File songFile) { com.themoon.y1.managers.PlaylistFavoritesManager.getInstance().showAddToPlaylistDialog(this, songFile); }
    public void showRemoveFromPlaylistDialog(final File songFile) { com.themoon.y1.managers.PlaylistFavoritesManager.getInstance().showRemoveFromPlaylistDialog(this, songFile); }
    public void showRemoveFromFavoritesDialog(final File songFile) { com.themoon.y1.managers.PlaylistFavoritesManager.getInstance().showRemoveFromFavoritesDialog(this, songFile); }

    // 🚀 [New engine] Extracts the file extension and metadata to determine lossless status and bitrate (kbps).
    // Navidrome browse-screen UI and the download-queue engine live in NavidromeManager -- see
    // that class for details. Kept as thin pass-throughs here for the handful of call sites
    // outside that cluster (AudioPlayerManager, KeyEventRouter, this Activity's own onDestroy).
    public void updateNavidromeQualityInfo(com.themoon.y1.managers.AudioPlayerManager am) {
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
    public void setupAudiobookProgress(final android.widget.Button btn, final int pos, final int dur) { com.themoon.y1.managers.AudiobookProgressManager.getInstance().setupAudiobookProgress(this, btn, pos, dur); }

    // 🚀 [New engine] Full-screen popup controller for real-time wheel-driven frequency adjustment
    public void showRadioFreqPopup(float freq) {
        com.themoon.y1.managers.FmRadioUiManager.getInstance().showFreqPopup(this, freq);
    }
    // 🚀 [Ultimate architecture complete] Dynamic layout engine that switches in real time purely by looking at the parent_id relationships between objects, with no hardcoded terms
    public void updateFocusPreviewLiveContent(ThemeManager.MenuElement focusedElement) {
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


    public void showQuickMenu() { com.themoon.y1.managers.SongContextMenuManager.getInstance().showQuickMenu(this); }
    public void showSongOptionsDialog(final java.io.File file) { com.themoon.y1.managers.SongContextMenuManager.getInstance().showSongOptionsDialog(this, file); }
    public void showDeleteAlbumDialog(final String albumName) { com.themoon.y1.managers.SongContextMenuManager.getInstance().showDeleteAlbumDialog(this, albumName); }
    public void showThemedOptionsDialog(String title, String subtitle, String[] options, final Runnable[] actions) { com.themoon.y1.managers.SongContextMenuManager.getInstance().showThemedOptionsDialog(this, title, subtitle, options, actions); }
    public void showThemedOptionsDialog(String title, String subtitle, String[] icons, String[] options, final Runnable[] actions) { com.themoon.y1.managers.SongContextMenuManager.getInstance().showThemedOptionsDialog(this, title, subtitle, icons, options, actions); }


}


