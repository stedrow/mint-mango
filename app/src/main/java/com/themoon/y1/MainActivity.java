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

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;


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
    // Note: Always add a trailing slash (/) at the end of the address!
    private static final String SERVER_BASE_URL = "http://knock2025.cafe24.com/knock_knock/y1/";
    private static final String METADATA_URL = SERVER_BASE_URL + "output-metadata.json";
    // 🚀 [Major overhaul complete] Smart control panel that lets you set the desired number of albums (odd numbers: 3, 5, 7, etc.) at any time
    private int visibleCoversCount = 7; // 💡 Back to 5! Just change this value (e.g. to 7) for testing and everything else updates automatically.

    private android.widget.FrameLayout coverFlowContainer;
    private android.view.View[] cfViews; // 💡 Size is determined dynamically by the UI builder below.




    private boolean isNavigatingToSubMenu = false; // 🚀 [Add one line here!] Guard that prevents focus tangling during direct access
    // 🚀 [Added] Global variable that keeps the audio channel always on standby
    private android.bluetooth.BluetoothProfile globalA2dp;
    private BluetoothDevice targetDeviceForAudio = null; // 🚀 [Added] Target device to keep latching onto like a zombie
    private boolean isBtConnectingState = false;
    // Caps the zombie-reconnect logic below: isLikelyStowed() reads AapService's last-known ear
    // state, but there's an inherent race between the A2DP disconnect broadcast and the AAP L2CAP
    // socket delivering its final "both in case" packet -- if the socket dies before that packet
    // is parsed, isLikelyStowed() reports false and we'd otherwise retry against an unreachable
    // stowed device forever (the "Connecting/Connected/Disconnected" spam loop). Reset on a real
    // STATE_CONNECTED and on ACL reconnect so a genuine dropout still gets full retry budget.
    private int zombieReconnectAttempts = 0;
    private static final int MAX_ZOMBIE_RECONNECT_ATTEMPTS = 3;
    // 🚀 [New] Control switch for the virtual screen-off (fake blackout)
    public boolean isFakeScreenOff = false;

    // 🚀 [New] "Wheel lock" to prevent pocket misfires — once the screen turns on (real hardware wake)
    // all button input is ignored until the wheel has been turned a certain number of clicks.
    private boolean isWheelLockEnabled = false; // Settings toggle (default OFF)
    private boolean isWheelLockActive = false; // whether it is currently locked and waiting
    private int wheelUnlockProgress = 0;
    // Half as many segments as the ring now only sweeps a half circle (180°) instead of
    // a full one — keeps each wedge the same angular width as before, so filling the
    // shorter arc only takes half the wheel rotation instead of a full turn.
    private static final int WHEEL_UNLOCK_THRESHOLD = 4;
    private LinearLayout layoutWheelLockOverlay;
    private com.themoon.y1.views.WheelLockRingView wheelLockRing;
    // Remembers the last tick direction (0 = none yet) so reversing direction doesn't count toward progress
    private int lastWheelDirection = 0;
    // Timer that resets progress if the wheel stops turning (no further tick) before the unlock completes
    private final Handler wheelLockHandler = new Handler();
    private static final long WHEEL_UNLOCK_RELEASE_TIMEOUT_MS = 500;
    private final Runnable wheelLockReleaseResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (isWheelLockActive && wheelUnlockProgress < WHEEL_UNLOCK_THRESHOLD) {
                wheelUnlockProgress = 0;
                lastWheelDirection = 0;
                if (wheelLockRing != null) wheelLockRing.resetProgress();
            }
        }
    };

    // 🚀 [New] Direct-shortcut back-navigation return-path tracker!
    private int backTargetForPlayer = STATE_BROWSER;
    private int backTargetForUtility = STATE_SETTINGS;
    // 🚀 [New] Guard that prevents a fake click event from firing when the virtual blackout wakes up
    public boolean ignoreNextKeyUp = false;
    // 🚀 [New] Variables for radio control
    public int activePlayer = 0; // 0: music player, 1: radio
    public boolean isRadioScanning = false;
    public java.util.List<Float> savedRadioStations = new java.util.ArrayList<>();

    private static final int BROWSER_COVER_FLOW = 9;
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
                } catch(Exception e){}
            }
        });
    }

    // 🚀 [New tool] Left/right saved-channel jump feature for hardware buttons
    private void tuneToNextSavedRadioChannel(boolean isNext) {
        com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
        if (savedRadioStations.isEmpty()) {
            Toast.makeText(this, t("No saved channels."), Toast.LENGTH_SHORT).show();
            return;
        }
        float target = savedRadioStations.get(0);
        if (isNext) {
            for (float f : savedRadioStations) {
                if (f > fm.currentFreq) { target = f; break; }
            }
        } else {
            target = savedRadioStations.get(savedRadioStations.size() - 1);
            for (int i = savedRadioStations.size() - 1; i >= 0; i--) {
                if (savedRadioStations.get(i) < fm.currentFreq) { target = savedRadioStations.get(i); break; }
            }
        }
        if (fm.isPowerUp) fm.tune(target);
        else fm.currentFreq = target;

        // 🚀 [Fix complete] Blocks full reloads and, on the player screen, only runs an ultra-fast partial refresh to prevent flicker!
        if (currentScreenState == STATE_SETTINGS) {
            if (isRadioUIShowing && !isRadioSettingsMode) {
                updateRadioMainPlayerUI();
            } else {
                buildRadioUI();
            }
        }
    }
    // 🚀 [New] Memory slot to hold the Material Icons font
    private android.graphics.Typeface materialIconFont = null;
    public boolean isLongPressConsumed = false; // 🚀 Added long-press guard variable
    private boolean isSeekPerformed = false;
    private long lastSeekTime = 0;
    // 🚀 [New] Global audio effect variables and profile state management
    public android.media.audiofx.BassBoost bassBoost;
    public android.media.audiofx.Virtualizer virtualizer;
    public int currentBassBoostStep = 0;    // 0: OFF, 1: Weak, 2: Normal, 3: Strong
    public int currentVirtualizerStep = 0;  // 0: OFF, 1: Weak, 2: Normal, 3: Strong
    public String currentEqProfile = "preset_0"; // preset_0~X or custom_name
    public int[] customBandLevels = new int[32]; // Cache bank for custom tuning values
    private int settingsSubMode = 0;         // 0: general, 1: date/time, 2: equalizer routing
    public int currentAudioSessionId = -1;  // 🚀 [Added] Variable to remember the audio session ID currently in use
    private int currentAdjustingBand = -1;   // 🚀 [Added] Remembers which frequency is currently being adjusted in the Graphic EQ.
    private boolean isWidgetFocusImageOn = false; // 🚀 [Added] Power variable for the focus-image widget
    // 💡 [Added] Home-screen widget related variables
    private boolean isWidgetClockOn = false;
    private boolean isWidgetBatteryOn = false;
    private boolean isWidgetAlbumOn = false;
    private boolean isWidgetAnalogClockOn = false;
    private boolean isWidgetCircularBatteryOn = false;

    // 🚀 [New engine control variable] List-box hide and loop-scroll switch

    private boolean isLoopScrollOn = true; // 💡 Set to true by default so infinite loop scrolling works!
    private TextView tvWidgetClock;
    // 🚀 [Fix] Renamed to the horizontal Bar class!
    private WidgetBatteryBarView widgetBatteryView;
    // Add the line below near where the other widget variables are declared (e.g. WidgetBatteryBarView widgetBatteryView; etc.).
    CircularBatteryView customCircularBatteryView;
    CustomAnalogClockView customAnalogClockView;
    private ImageView ivWidgetAlbum;
    private String lastBrowserFocusText = "";
    private String lastMainMenuFocusAction = "";
    // 🚀 [Added] Title/artist variables dedicated to the album widget
    private TextView tvWidgetAlbumTitle;
    private TextView tvWidgetAlbumArtist;
    // 💡 [Added] Variables dedicated to fast index jump (alphabet scroll)
    private List<String> currentScrollIndexList = new ArrayList<>();
    private long lastWheelTime = 0;
    private int wheelFastCount = 0;
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
    private static final int STATE_MENU = 1;
    private static final int STATE_BROWSER = 2;
    private static final int STATE_PLAYER = 3;
    private static final int STATE_SETTINGS = 4;
    private static final int STATE_BLUETOOTH = 5;
    private static final int STATE_WIFI = 6;
    private static final int STATE_WIFI_KEYBOARD = 7;
    private static final int STATE_BRIGHTNESS = 8;
    private static final int STATE_STORAGE = 9;
    private static final int STATE_WEBSERVER = 10;
    private static final int STATE_NAVIDROME = 11;
    // 💡 Media library browser state management variables
    private static final int BROWSER_ROOT = 0;
    private static final int BROWSER_FOLDER = 1;
    private static final int BROWSER_ARTISTS = 2;
    private static final int BROWSER_ALBUMS = 3;
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
    private static final int BROWSER_FAVORITES = 5;
    // 🚀 [New virtual browser mode dedicated to native M3U]
    private static final int BROWSER_PLAYLISTS = 6;
    private static final int BROWSER_M3U_SONGS = 7;
    private static final int BROWSER_AUDIOBOOKS = 8; // 🚀 [Added] Activates the audiobook browser state
    // 🚀 [New constants] Loads the year and genre state switches with unique, non-overlapping numbers.
    private static final int BROWSER_YEARS = 10;
    private static final int BROWSER_GENRES = 11;
    // 🚀 [New] Cover flow state constants and data storage
//    private static final int BROWSER_COVER_FLOW = 9;
//    private java.util.List<SongItem> uniqueAlbumList = new java.util.ArrayList<>();
//    private int currentCoverFlowIndex = 0;
    private File currentM3uFile = null; // Address of the M3U file the user is currently viewing
    // 🚀 [Added] Variables dedicated to favorites
    private java.util.Set<String> favoritePaths = new java.util.HashSet<>();
    private TextView tvPlayerFavoriteStatus;

    public int consecutiveErrorCount = 0;
    // 🚀 [Added] Variables for displaying scan progress
    private ProgressBar pbLoadingProgress;
    private TextView tvLoadingProgress;
    private int totalAudioFiles = 0;
    private int scannedAudioFiles = 0;
    // 💡 [Ultra-fast engine] Recycler ListView (to handle thousands of tracks) alongside the existing ScrollView
    private android.widget.ListView listVirtualSongs;
    private View scrollViewBrowser;
    private boolean isScreenOffControlEnabled = false;
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

    private int lastRadioFocusIndex = 1;
    // (Volume-only variables and the complex focus index are no longer needed, so they were boldly removed!)
    private boolean isCustomScanning = false;
    public java.util.HashMap<String, Integer> trackNumberMap = new java.util.HashMap<>();
    public com.themoon.y1.db.LibraryCacheDb libraryCacheDb;
    private int currentScreenState = STATE_MENU;
    // 💡 Temporary variable for the custom date/time settings
    private int dtYear = 2026, dtMonth = 1, dtDay = 1, dtHour = 12, dtMinute = 0;
    private View layoutMainMenu, layoutBrowserMode, layoutSettingsMode;
    private View layoutBluetoothMode, layoutWifiMode, layoutWifiKeyboard;
    private View layoutPlayerMode, layoutVolumeOverlay;
    private View layoutBrightnessMode, layoutStorageMode, layoutWebServerMode;
    private View layoutNavidromeMode;
    private LinearLayout containerNavidromeItems;
    private TextView tvNavidromePath, tvNavidromeStatus;

    // Navidrome browse state
    private static final int NAV_ARTISTS = 0;
    private static final int NAV_ALBUMS  = 1;
    private static final int NAV_SONGS   = 2;
    private int navidromeBrowseDepth = NAV_ARTISTS;
    private com.themoon.y1.subsonic.SubsonicArtist selectedNavidromeArtist;
    private com.themoon.y1.subsonic.SubsonicAlbum  selectedNavidromeAlbum;
    private java.util.List<com.themoon.y1.subsonic.SubsonicSong> lastNavidromeSongs = new java.util.ArrayList<>();
    private java.util.List<com.themoon.y1.subsonic.SubsonicArtist> lastNavidromeArtists = new java.util.ArrayList<>();
    private boolean isNavidromeLoading = false;
    private boolean isNavidromeLetterView = false; // letter-jump picker showing instead of artist list
    private int lastSeenNavidromeConfigVersion = 0; // detects a server/user/pass change made via the Web Server web UI
    private int navidromeBackTarget = STATE_MENU;  // where the back button exits to (main menu or Music library)

    // Navidrome download queue — one transfer at a time (the ~190kbps link can't
    // share), with progress shown in tv_navidrome_status
    private static class NavidromeDownloadItem {
        final com.themoon.y1.subsonic.SubsonicSong song;
        final boolean transcoded; // true = MP3 192kbps, false = original file
        int retryCount = 0; // bumped on each failed attempt, reset never — item is discarded once exhausted
        NavidromeDownloadItem(com.themoon.y1.subsonic.SubsonicSong song, boolean transcoded) {
            this.song = song;
            this.transcoded = transcoded;
        }
    }
    private static final int MAX_NAVIDROME_DOWNLOAD_RETRIES = 2; // up to 3 attempts total per track
    private final java.util.ArrayDeque<NavidromeDownloadItem> navidromeDownloadQueue = new java.util.ArrayDeque<>();
    private boolean isNavidromeDownloading = false;
    private int navidromeQueueTotal = 0;
    private int navidromeQueueDone = 0;
    // Keep CPU + WiFi awake while the queue runs — otherwise transfers stall
    // and time out as soon as the screen sleeps
    private android.os.PowerManager.WakeLock navidromeDownloadWakeLock;
    private android.net.wifi.WifiManager.WifiLock navidromeDownloadWifiLock;
    private String currentNavidromeCoverArtId; // guards against stale async art landing on a newer track

    private LinearLayout containerBrowserItems, containerSettingsItems;
    private LinearLayout containerBtItems, containerWifiItems;

    private TextView tvStatusClock, tvStatusBattery;
    private ImageView ivStatusBluetooth, ivStatusWifi, ivStatusHeadphone, ivMainBg;

    public TextView tvBrowserPath, tvPlayerTitle, tvPlayerArtist, tvPlayerTimeCurrent, tvPlayerTimeTotal;
    // 🚀 [Variables dedicated to the capsule UI]
    private LinearLayout layoutAudioQualityContainer;
    private TextView tvQualityExt;
    private TextView tvQualityFormat;
    private TextView tvQualityBitrate;

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
    private LinearLayout layoutLoadingOverlay;
    public ImageView ivMenuPreview, ivAlbumArt, ivPlayerBgBlur, ivPauseOverlay;

    private Button btnNowPlaying, btnPlay, btnSettings, btnBluetooth, btnRadio;
    private Button btnScanBt, btnScanWifi;
    private LinearLayout btnWifiWebServer;

    private TextView tvKeyboardSsid, tvKeyboardInput;
    private TextView tvKeyPprev, tvKeyPrev, tvKeyCurrent, tvKeyNext, tvKeyNnext;

    private final String[] KEYBOARD_CHARS = {
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
    private int keyboardIndex = 0;
    private String targetWifiSsid = "";
    private String typedPassword = "";
    private boolean isTargetWifiOpen = false;
    // 💡 Variable that tracks whether the media scanner is currently working
    private boolean isMediaScanning = false;
    private AudioManager audioManager;
    private File rootFolder = new File("/storage/sdcard0/Music");
    private File currentFolder = rootFolder;
    public List<File> originalPlaylist = new ArrayList<File>();
    public List<File> currentPlaylist = new ArrayList<File>();
    public int currentIndex = 0;
    public boolean isPausedByHand = true;
    private float currentClockSize = 48f;
    public java.io.FileInputStream currentFileInputStream = null;
    private TextView tvMenuPreviewTitle, tvMenuPreviewArtist;
    public SharedPreferences prefs;
    private boolean isShuffleMode = false;
    private int repeatMode = 0; // 0: OFF, 1: ONE (Repeat One), 2: ALL (Repeat Folder/All)
    private boolean isSoundEffectEnabled = true;
    private boolean isSpeakerDisabled = false;
    private boolean isVibrationEnabled = true;
    private boolean isPickingBackground = false;

    // 💡 Variable that remembers the last-played album art
    public byte[] lastAlbumArtBytes = null;
    // refreshWidgets() runs every second forever via clockTask; these cache the last decoded
    // widget album thumbnail (keyed by identity of lastAlbumArtBytes) and the last known battery
    // reading (updated by the ACTION_BATTERY_CHANGED receiver) so that per-second tick doesn't
    // re-decode a bitmap and make two Binder round-trips to fetch the sticky battery intent.
    private byte[] widgetAlbumArtCachedSource = null;
    private android.graphics.Bitmap widgetAlbumArtCachedBitmap = null;
    private int lastKnownBatteryPct = -1;
    private boolean lastKnownBatteryCharging = false;
    // 💡 Added equalizer related variable
    public Equalizer equalizer;
    private List<String> eqPresetNames = new ArrayList<String>();
    public int currentEqPresetIndex = 0;

    private int lastSettingsFocusIndex = 0;
    private int currentSettingsDepth = 1;
    private static final int GROUP_PLAYBACK = 0, GROUP_SOUND = 1, GROUP_CONNECTIVITY = 2, GROUP_DISPLAY = 3, GROUP_STORAGE = 4, GROUP_SYSTEM = 5;
    private int currentSettingsGroup = GROUP_PLAYBACK;
    private boolean isScreenSleeping = false;
    private long lastScreenOnTime = 0;
    // 💡 [Added] Custom battery view variable
    private BatteryIconView batteryIconView;
    private int currentTimeoutIndex = 1;
    private final int[] TIMEOUT_VALUES = { 15000, 30000, 60000, 300000 };
    private final String[] TIMEOUT_NAMES = { "15 Sec", "30 Sec", "1 Min", "5 Min" };
    private TextView tvFocusPreviewClock; // 🚀 [New engine] Digital clock that ticks inside the live-preview box
    private ImageView ivWidgetFocusImage; // 🚀 [Added] Dynamic focus widget variable

    // 🚀 [New engine variable] Backup vault to remember the existing widget's body and original coordinates
    private LinearLayout layoutWidgetAlbumContainer; // Address of the album widget block

    // 🚀 [Added] Unified registry to globally manage every widget's memory
    public java.util.HashMap<View, ThemeManager.MenuElement> widgetViewRegistry = new java.util.HashMap<>();
    private int currentSystemBrightness = 255;

    private List<String> foundBtDevices = new ArrayList<String>();
    private List<String> foundWifiNetworks = new ArrayList<String>();

    private Y1WebServer webServer;
    private boolean isServerRunning = false;
    private int vibrationStrengthLevel = 1; // 0: Weak, 1: Normal, 2: Strong
    // 🚀 Append parentheses to fully separate this into a key distinct from the equalizer's Normal!
    private final String[] VIBE_STRENGTH_NAMES = {"Weak", "Normal (Vibe)", "Strong"};
    // 💡 Key: 10ms (very short pulse), 25ms (normal wheel), 50ms (heavy rumble)
    private final int[] VIBE_DURATIONS = {10, 25, 50};
    // Pre-built so the once-a-second clock tick (and refreshWidgets, called from the same tick)
    // don't construct a new SimpleDateFormat every second forever -- pattern parsing on every
    // tick was pure waste since only two patterns are ever used, chosen by is24HourFormat.
    private static final SimpleDateFormat STATUS_CLOCK_FORMAT_24 = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat STATUS_CLOCK_FORMAT_12 = new SimpleDateFormat("hh:mm a", Locale.US);
    private static final SimpleDateFormat WIDGET_CLOCK_FORMAT_24 = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat WIDGET_CLOCK_FORMAT_12 = new SimpleDateFormat("hh:mm", Locale.US);
    private static final SimpleDateFormat WIDGET_DATE_FORMAT = new SimpleDateFormat("EEE, MMM dd", Locale.US);

    private Handler clockHandler = new Handler();
    private Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            SimpleDateFormat sdf = is24HourFormat ? STATUS_CLOCK_FORMAT_24 : STATUS_CLOCK_FORMAT_12;
            tvStatusClock.setText(sdf.format(new Date()));

            // 🚀 [Live engine] If the preview's internal clock is VISIBLE on screen, swap the time in real time every second to make it tick!
            if (tvFocusPreviewClock != null && tvFocusPreviewClock.getVisibility() == View.VISIBLE) {
                tvFocusPreviewClock.setText(sdf.format(new Date()));
            }

            refreshWidgets(); // Simultaneously refresh the home-screen widgets
            clockHandler.postDelayed(this, 1000);
        }
    };
    // 🚀 [New] 5-second auto-hide timer engine for the pill UI
    private Handler qualityInfoHandler = new Handler();
    private Runnable hideQualityInfoTask = new Runnable() {
        @Override
        public void run() {
            // When the timer fires, fully hides the pill container from the screen!
            if (layoutAudioQualityContainer != null) {
                layoutAudioQualityContainer.setVisibility(View.GONE);
            }
        }
    };
    // 🚀 [Added] Bank of variables for global double-click and root power control
    private android.os.Handler doubleClickHandler = new android.os.Handler();
    private long lastCenterUpTime = 0;
    private Runnable singleClickRunnable = new Runnable() {
        @Override
        public void run() {
            try { handleCenterShortClick(); } catch (Exception e) {}
        }
    };
    // 🚀 [Added] A short translation helper to make it easy to wrap long English sentences.
    public String t(String text) {
        return com.themoon.y1.managers.LanguageManager.getInstance(this).t(text);
    }

    // 🚀 [Smart Wi-Fi power-saving engine]
    private void autoManageWifiPower(boolean isGoingToSleep) {
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
    
    // 🚀 [Wheel lock] Called right after the hardware screen wake (ACTION_SCREEN_ON) — until the wheel has
    // been turned enough, all key input is blocked at the source in dispatchKeyEvent.
    private void activateWheelLock() {
        isWheelLockActive = true;
        wheelUnlockProgress = 0;
        lastWheelDirection = 0;
        wheelLockHandler.removeCallbacks(wheelLockReleaseResetRunnable);
        if (layoutWheelLockOverlay != null) {
            if (wheelLockRing != null) wheelLockRing.resetProgress();
            layoutWheelLockOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void deactivateWheelLock() {
        isWheelLockActive = false;
        wheelUnlockProgress = 0;
        lastWheelDirection = 0;
        wheelLockHandler.removeCallbacks(wheelLockReleaseResetRunnable);
        if (layoutWheelLockOverlay != null) {
            layoutWheelLockOverlay.setVisibility(View.GONE);
        }
    }

    private void updateWheelLockProgress() {
        if (wheelLockRing != null) {
            wheelLockRing.setProgress(wheelUnlockProgress);
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
                                    } catch (Exception e) {}
                                }
                            })
                            .start();
                }
            });
        } else {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 26"});
            } catch (Exception e) {}
        }
    }

    // 🚀 [New engine] Lossless engine that swaps only the frequency and candy-capsule color at ultra speed without a reload
    private LinearLayout layoutRadioCandyContainer;

    // 🚀 [New engine] Lossless engine that swaps only the frequency and candy-capsule color at ultra speed without a reload (left-edge escape bug fully fixed)
    private void updateRadioMainPlayerUI() {
        final com.themoon.y1.managers.FmRadioManager fmManager = com.themoon.y1.managers.FmRadioManager.getInstance(this);

        // 1. Pinpoint-refresh only the main large frequency display text
        TextView tvFreq = (TextView) containerSettingsItems.findViewWithTag("radio_main_freq_text");
        if (tvFreq != null) {
            tvFreq.setText(String.format(java.util.Locale.US, "%.1f MHz", fmManager.currentFreq));
            tvFreq.setTextColor(fmManager.isPowerUp ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000) : 0xFF888888);
        }

        // 2. Silently refresh only the background and text color of the pills inside the candy pouch
        if (layoutRadioCandyContainer != null) {
            int themeHighlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < layoutRadioCandyContainer.getChildCount(); i++) {
                final View child = layoutRadioCandyContainer.getChildAt(i);
                if (child instanceof TextView && child.getTag() instanceof Float) {
                    TextView tvCandy = (TextView) child;
                    float stationFreq = (Float) child.getTag();
                    android.graphics.drawable.GradientDrawable candyBg = new android.graphics.drawable.GradientDrawable();
                    candyBg.setCornerRadius(20 * density);

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor);
                        tvCandy.setTextColor(ThemeManager.getListButtonFocusedTextColor());

                        layoutRadioCandyContainer.post(new Runnable() {
                            @Override
                            public void run() {
                                android.view.ViewParent parent = layoutRadioCandyContainer.getParent();
                                if (parent instanceof android.widget.HorizontalScrollView) {
                                    android.widget.HorizontalScrollView hsv = (android.widget.HorizontalScrollView) parent;
                                    int scrollX = child.getLeft() - (hsv.getWidth() / 2) + (child.getWidth() / 2);

                                    // 🚀 [Core guard] If the scroll calculation goes negative, force it to 0 (leftmost) to permanently prevent the first channel from being cut off!
                                    if (scrollX < 0) scrollX = 0;

                                    hsv.smoothScrollTo(scrollX, 0);
                                }
                            }
                        });
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg());
                        tvCandy.setTextColor(ThemeManager.getTextColorSecondary());
                    }
                    tvCandy.setBackground(candyBg);
                }
            }
        }
    }
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
                    tvPlayerTimeCurrent.setText(formatTime(current));
                    tvPlayerTimeTotal.setText(formatTime(duration));

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
            } catch (Exception e) {}
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
                if (isWheelLockEnabled) activateWheelLock();
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
                                        globalA2dp = proxy;
                                        updateBluetoothStatusIcon();
                                    }
                                }

                                @Override
                                public void onServiceDisconnected(int profile) {
                                    if (profile == BluetoothProfile.A2DP)
                                        globalA2dp = null;
                                }
                            }, BluetoothProfile.A2DP);

                } else {
                    ivStatusBluetooth.setVisibility(View.GONE);
                    globalA2dp = null; // 🚀 Reset the engine too when Bluetooth turns off
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

                // 🚀 [Ported from stock: zombie logic 1] If AirPods reject the audio, re-call the engine within 0.1s!
                if (profileState == BluetoothProfile.STATE_DISCONNECTED) {
                    Toast.makeText(context, t("Audio Disconnected"), Toast.LENGTH_SHORT).show();
                    boolean stowed = AapService.isLikelyStowed();
                    AapService.deviceDisconnected(context);
                    if (!stowed && targetDeviceForAudio != null && currentDevice != null
                            && targetDeviceForAudio.getAddress().equals(currentDevice.getAddress())) {
                        if (zombieReconnectAttempts < MAX_ZOMBIE_RECONNECT_ATTEMPTS) {
                            zombieReconnectAttempts++;
                            connectBluetoothAudio(targetDeviceForAudio);
                        } else {
                            android.util.Log.i("BtZombie", "giving up zombie-reconnect after " + zombieReconnectAttempts
                                    + " attempts, likely stowed/out of range");
                        }
                    }
                } else if (profileState == BluetoothProfile.STATE_CONNECTED) {
                    String name = currentDevice != null ? currentDevice.getName() : "Unknown";
                    Toast.makeText(context, t("Audio Connected to ") + name, Toast.LENGTH_SHORT).show();
                    zombieReconnectAttempts = 0;
                    if (currentDevice != null) AapService.deviceConnected(context, currentDevice);
                }
                // 🚀 Re-evaluate speaker mute state whenever the Bluetooth connection state changes (since this broadcast
                // is the source of truth, use the state just observed instead of re-querying)
                applySpeakerSetting(profileState == BluetoothProfile.STATE_CONNECTED);
                updateBluetoothStatusIcon();

                if (profileState == BluetoothProfile.STATE_CONNECTED
                        || profileState == BluetoothProfile.STATE_DISCONNECTED) {
                    isBtConnectingState = false; // Unlock!
                    if (currentScreenState == STATE_BLUETOOTH) {
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startBluetoothScan();
                            }
                        }, 300);
                    }
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (currentScreenState == STATE_BLUETOOTH && !isBtConnectingState) {
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
                // 🚀 [Ported from stock: zombie logic 3] Re-call the engine immediately if the device's own connection bounces!
                if (!AapService.isLikelyStowed() && targetDeviceForAudio != null && disconnectedDevice != null
                        && targetDeviceForAudio.getAddress().equals(disconnectedDevice.getAddress())) {
                    if (zombieReconnectAttempts < MAX_ZOMBIE_RECONNECT_ATTEMPTS) {
                        zombieReconnectAttempts++;
                        connectBluetoothAudio(targetDeviceForAudio);
                    } else {
                        android.util.Log.i("BtZombie", "giving up zombie-reconnect (ACL) after " + zombieReconnectAttempts
                                + " attempts, likely stowed/out of range");
                    }
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice connectedDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // The radio link itself coming back (e.g. AirPods case opened) is a fresh signal
                // that the device might be reachable again -- reset the zombie-retry budget so a
                // real reconnect isn't blocked by attempts spent on the previous stow/out-of-range
                // stretch.
                if (targetDeviceForAudio != null && connectedDevice != null
                        && targetDeviceForAudio.getAddress().equals(connectedDevice.getAddress())) {
                    zombieReconnectAttempts = 0;
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

    // 🚀 [Fully ported from stock launcher] Centralized Bluetooth connection engine
    private void connectBluetoothAudio(final BluetoothDevice targetDevice) {
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
            Toast.makeText(this, t("Pairing with ") + targetDevice.getName() + "...", Toast.LENGTH_SHORT).show();
            try {
                targetDevice.getClass().getMethod("createBond").invoke(targetDevice);
            } catch (Exception e) {
            }
            return;
        }

        Toast.makeText(this, t("Connecting Audio..."), Toast.LENGTH_SHORT).show();

        // 4. If the engine is alive, connect immediately; if not, revive it in the background then connect!
        if (globalA2dp != null) {
            executeA2dpConnect(targetDevice);
        } else {
            if (adapter != null) {
                adapter.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
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
        try {
            // 💡 [Secret of the stock code] Before connecting, ruthlessly disconnect any other audio device already attached!
            java.util.List<BluetoothDevice> connectedDevices = null;
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
                Toast.makeText(this, t("Audio connection error: connect method not found"), Toast.LENGTH_LONG).show();
                return;
            }
            
            connectMethod.setAccessible(true);
            boolean result = (Boolean) connectMethod.invoke(globalA2dp, targetDevice);
            if (!result) {
                Toast.makeText(this, t("Audio connection rejected by system."), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, t("Audio connection initiated."), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Audio error: " + errorStr, Toast.LENGTH_LONG).show();
        }
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
            android.app.PendingIntent mediaPendingIntent = android.app.PendingIntent.getBroadcast(context, 0, mediaButtonIntent, 0);

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
            } catch (Exception e) {}
        }

        updateBluetoothMetadata(title, artist, "Y1 Player", bmp);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installTls12TrustAll();
        // 🚀 Registers itself in a variable when the app launches.
        instance = this;
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
                            globalA2dp = proxy; // Loaded and ready!
                            updateBluetoothStatusIcon();
                            resyncAapWithConnectedDevice();
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == android.bluetooth.BluetoothProfile.A2DP)
                            globalA2dp = null;
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
        wheelLockRing.setSegments(WHEEL_UNLOCK_THRESHOLD);
        android.widget.FrameLayout.LayoutParams ringLp = new android.widget.FrameLayout.LayoutParams(ringSize, ringSize);
        wheelLockRingFrame.addView(wheelLockRing, ringLp);

        TextView tvWheelLockIcon = new TextView(this);
        tvWheelLockIcon.setText("\uE897"); // Material Icons "lock" glyph — same icon font used everywhere else in the app
        tvWheelLockIcon.setTextColor(0xFFFFFFFF);
        tvWheelLockIcon.setTextSize(40);
        tvWheelLockIcon.setGravity(android.view.Gravity.CENTER);
        if (materialIconFont == null) {
            try { materialIconFont = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/MaterialIcons-Regular.ttf"); }
            catch (Exception e) {}
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
        try {
            // 🚀 [Bluetooth AVRCP 1.6 force-injection engine]
            // Since Developer Options is blocked, send ADB shell commands directly to the system via su privileges.
            String cmd1 = "setprop persist.bluetooth.avrcpversion 1.6";
            String cmd2 = "settings put global bluetooth_avrcp_version 1.6";

            // Chain the two commands together with &&(AND) and sync the system.
            String combinedCmd = cmd1 + " && " + cmd2 + " && sync";

            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", combinedCmd});
            proc.waitFor(); // Wait briefly until the command finishes applying

            // To apply this silently, feel free to remove the toast once testing is done.
            // Toast.makeText(this, "Bluetooth AVRCP 1.6 forced via Root.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            // Load the saved index number. (Handled safely in case the file was deleted)
            int savedThemeIndex = prefs.getInt("app_theme_index", 0);
            ThemeManager.setThemeIndex(savedThemeIndex);
        } catch (Exception e) {
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
        }

        // 💡 2. Load each setting independently (never skipped under any circumstance!)
        try {
            isShuffleMode = prefs.getBoolean("shuffle", false);
        } catch (Exception e) {
        }

        try {
            if (prefs.contains("repeat_mode")) {
                repeatMode = prefs.getInt("repeat_mode", 0);
            } else {
                repeatMode = prefs.getBoolean("repeat", false) ? 1 : 0;
            }
        } catch (Exception e) {
        }

        try {
            isSoundEffectEnabled = prefs.getBoolean("sound", true);
            applySoundSetting();
        } catch (Exception e) {
        }

        try {
            isSpeakerDisabled = prefs.getBoolean("speaker_disabled", false);
            applySpeakerSetting();
        } catch (Exception e) {
        }

        try {
            isVibrationEnabled = prefs.getBoolean("vibrate", true);
            vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1);
        } catch (Exception e) {
        }
        try {
            isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false);
        } catch (Exception e) {
        }

        try {
            isWheelLockEnabled = prefs.getBoolean("wheel_lock_on_wake", false);
        } catch (Exception e) {
        }

        try {
            isAutoFetchEnabled = prefs.getBoolean("auto_fetch", true);
        } catch (Exception e) {
        } // 🚀 [Added]
        try {
            currentTimeoutIndex = prefs.getInt("timeout_idx", 1);
        } catch (Exception e) {
        }

        try {
            currentSystemBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    255);
        } catch (Exception e) {
        }
        try {
            // Load the previously saved format setting from the vault (SharedPreferences). (default is 12-hour format)
            is24HourFormat = prefs.getBoolean("is_24h_format", false);
        } catch (Exception e) {}
        // 💡 [Auto-load EQ preset list] Fetches the list of equalizer presets the device supports.
        try {
            MediaPlayer dummyMp = new MediaPlayer();
            Equalizer dummyEq = new Equalizer(0, dummyMp.getAudioSessionId());
            short presets = dummyEq.getNumberOfPresets();
            for (short i = 0; i < presets; i++) {
                eqPresetNames.add(dummyEq.getPresetName(i));
            }
            dummyEq.release();
            dummyMp.release();
        } catch (Exception e) {
            eqPresetNames.add("Normal (Default)");
        }

        currentEqPresetIndex = prefs.getInt("eq_preset", 0);
        // 🚀 [Added] Links advanced effects and custom profile vault data
        currentEqProfile = prefs.getString("eq_profile_id", "preset_" + currentEqPresetIndex);
        currentBassBoostStep = prefs.getInt("bass_boost_step", 0);
        currentVirtualizerStep = prefs.getInt("virtualizer_step", 0);
        if (currentEqPresetIndex >= eqPresetNames.size())
            currentEqPresetIndex = 0;

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
        }
        try {
            isWidgetBatteryOn = prefs.getBoolean("widget_battery", false);
        } catch (Exception e) {
        }
        try {
            isWidgetAlbumOn = prefs.getBoolean("widget_album", false);
        } catch (Exception e) {
        }
        try {
            isWidgetFocusImageOn = prefs.getBoolean("widget_focus_image", false); // 🚀 [Added] Load state
        } catch (Exception e) {
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
        } catch (Exception e) {}
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
                selectedNavidromeAlbum = null;
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

    // 2. Extract tags and put them in a bucket (specify the target bucket)
    // cachedSongs holds tag results from the last scan (keyed by absolute path) so files whose
    // mtime/size haven't changed skip MediaMetadataRetriever entirely; freshEntries collects the
    // up-to-date cache rows to persist once the whole scan finishes.
    private void buildCustomLibrary(File folder, List<SongItem> targetLibrary,
                                     Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs,
                                     boolean isAudiobook,
                                     List<com.themoon.y1.db.LibraryCacheDb.CachedSong> freshEntries,
                                     java.util.HashMap<String, Integer> targetTrackNumberMap) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    buildCustomLibrary(f, targetLibrary, cachedSongs, isAudiobook, freshEntries, targetTrackNumberMap);
                } else if (isAudioFile(f)) {
                    if (blacklist.contains(f.getAbsolutePath())) continue;

                    String path = f.getAbsolutePath();
                    long mtime = f.lastModified();
                    long size = f.length();
                    com.themoon.y1.db.LibraryCacheDb.CachedSong cached = cachedSongs.get(path);

                    String title, artist, album, year, genre;
                    int trackNum;

                    if (cached != null && cached.mtime == mtime && cached.size == size) {
                        // Quick scan: file is unchanged, reuse the cached tags instead of re-reading them
                        title = cached.title;
                        artist = cached.artist;
                        album = cached.album;
                        year = cached.year;
                        genre = cached.genre;
                        trackNum = cached.trackNumber;
                    } else {
                        // 🚀 [Fix] Load default values first so it survives even if an error causes a crash!
                        title = f.getName(); // Use the file name in place of a missing title!
                        artist = t("Unknown Artist"); // 🚀 Translator applied!
                        album = t("Unknown Album");   // 🚀 Translator applied!
                        year = t("Unknown Year");     // 🚀 Translator applied!
                        genre = t("Unknown Genre");   // 🚀 Translator applied!
                        trackNum = 0;

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

                            // 🚀 Only overwrite the "Unknown..." default with real data if the tag actually exists!
                            if (t != null && !t.trim().isEmpty()) title = t;
                            if (aa != null && !aa.trim().isEmpty()) artist = aa;
                            else if (a != null && !a.trim().isEmpty()) artist = a;
                            if (al != null && !al.trim().isEmpty()) album = al;
                            if (y != null && !y.trim().isEmpty()) year = y;     // 🚀 Overwrite the year
                            if (g != null && !g.trim().isEmpty()) genre = g;    // 🚀 Overwrite the genre

                            if (trackStr != null && !trackStr.isEmpty()) {
                                try {
                                    if (trackStr.contains("/")) trackNum = Integer.parseInt(trackStr.split("/")[0].trim());
                                    else trackNum = Integer.parseInt(trackStr.trim());
                                } catch (Exception e) {}
                            }
                        } catch (Exception e) {
                            // 💡 Even if there's no tag or the scanner fails, the app doesn't crash and safely exits into this block.
                        } finally {
                            if (fis != null) try { fis.close(); } catch (Exception e) {}
                            try { mmr.release(); } catch (Exception e) {}
                        }
                    }

                    // 🚀 [Core fix] Add it to the bucket in the safe zone (outside the try-catch).
                    // Even if an error occurred, it's added and categorized normally using the pre-loaded defaults ("Unknown Artist", etc.)!
                    targetLibrary.add(new SongItem(f, title, artist, album, year, genre)); // 💡 All 6 fields filled in!
                    targetTrackNumberMap.put(path, trackNum);
                    freshEntries.add(new com.themoon.y1.db.LibraryCacheDb.CachedSong(
                            path, mtime, size, title, artist, album, year, genre, trackNum, isAudiobook));

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

    private void startMediaLibraryScan() { startMediaLibraryScan(false); }

    /** @param silent Skip the blocking "Scanning..." popup — used when the cache already
     *  populated the library instantly and this run is just reconciling with disk in the background. */
    private void startMediaLibraryScan(final boolean silent) {
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

                // 🚀 Scan both folders and put results into their respective buckets
                buildCustomLibrary(rootFolder, newCustomLibrary, cachedSongs, false, freshEntries, newTrackNumberMap);
                buildCustomLibrary(audiobookRootFolder, newAudiobookLibrary, cachedSongs, true, freshEntries, newTrackNumberMap);

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
    private void showLoadingPopup() {
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

    private void refreshWidgets() {
        // 1. Update the digital clock widget
        if (tvWidgetClock != null) {
            ThemeManager.MenuElement el = widgetViewRegistry.get(tvWidgetClock);
            // 🚀 [Core guard] If this widget is watching a specific button (visibleOnFocus), don't force it off via the settings switch!
            if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
                tvWidgetClock.setVisibility(isWidgetClockOn ? View.VISIBLE : View.GONE);
            }

            // 💡 Only refresh the time while it's VISIBLE, to avoid unnecessary load
            if (tvWidgetClock.getVisibility() == View.VISIBLE) {
                float d = getResources().getDisplayMetrics().density;
                tvWidgetClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (currentClockSize * 2.1f) * d);
                tvWidgetClock.setLineSpacing(0, 1.1f);

                java.util.Date now = new java.util.Date();
                SimpleDateFormat sdfTime = is24HourFormat ? WIDGET_CLOCK_FORMAT_24 : WIDGET_CLOCK_FORMAT_12;
                String timeStr = sdfTime.format(now);
                String dateStr = WIDGET_DATE_FORMAT.format(now);
                String fullText = timeStr + "\n" + dateStr;

                android.text.SpannableString spannable = new android.text.SpannableString(fullText);
                spannable.setSpan(new android.text.style.RelativeSizeSpan(0.47f), timeStr.length() + 1, fullText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), timeStr.length() + 1, fullText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvWidgetClock.setText(spannable);
            }
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
                    } catch (Exception e) {}
                } else {
                    ivWidgetAlbum.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", this, R.drawable.default_album));
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
    private void applyThemeToMainMenu() {
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

        } catch (Exception e) {}
    }
    // 💡 [Added] A dedicated screen that shows the full theme list and lets the user pick one
    private void buildThemeSelectorUI() {
        currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        File themeFolder = new File("/storage/sdcard0/Y1_Themes");
        ThemeManager.loadThemesFromStorage(themeFolder);

        containerSettingsItems.removeAllViews();

        // Turn each theme read from the SD card folder into a button.
        for (int i = 0; i < ThemeManager.availableThemes.size(); i++) {
            final int index = i;
            ThemeManager.ThemeData theme = ThemeManager.availableThemes.get(i);

            String prefix = (ThemeManager.getCurrentThemeIndex() == i) ? "✔ " : "   ";
            Button btn = createListButton(prefix + theme.name);

            if (ThemeManager.getCurrentThemeIndex() == i) {
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                btn.setTextColor(0xFF00FF00); // Highlight the currently active theme in bold green!
            }

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    ThemeManager.setThemeIndex(index);
                    try {
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putInt("app_theme_index", index);
                        editor.putBoolean("reboot_to_theme", true);

                        // 🚀 [Smart automation] Scans the selected theme's parts (JSON) and automatically toggles the widget switches!
                        boolean hasClock = false, hasAnalog = false, hasBattery = false, hasCircular = false, hasAlbum = false, hasFocusImage = false; // 🚀 Added variable

                        for (ThemeManager.MenuElement el : theme.menuElements) {
                            if ("widget_clock".equals(el.type)) hasClock = true;
                            if ("widget_analog_clock".equals(el.type)) hasAnalog = true;
                            if ("widget_battery".equals(el.type)) hasBattery = true;
                            if ("widget_circular_battery".equals(el.type)) hasCircular = true;
                            if ("widget_album".equals(el.type)) hasAlbum = true;
                            if ("widget_focus_image".equals(el.type)) hasFocusImage = true; // 🚀 Added check
                        }

                        // Force-sync widgets included in the theme to 'ON' and ones not included to 'OFF'!
                        editor.putBoolean("widget_clock", hasClock);
                        editor.putBoolean("widget_analog_clock", hasAnalog);
                        editor.putBoolean("widget_battery", hasBattery);
                        editor.putBoolean("widget_circular_battery", hasCircular);
                        editor.putBoolean("widget_album", hasAlbum);
                        editor.putBoolean("widget_focus_image", hasFocusImage); // 🚀 Added save

                        editor.apply(); // Settings saved
                    } catch (Exception e) {
                    }

                    recreate(); // Refresh the screen! (the new widget settings take effect immediately)
                }
            });
            containerSettingsItems.addView(btn);
        }

        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Fixed the bug where focus escaped to index 1 (the second item), and went further to find and focus the theme I just selected!
                int selectedIdx = ThemeManager.getCurrentThemeIndex();

                if (containerSettingsItems.getChildCount() > selectedIdx && selectedIdx >= 0) {
                    containerSettingsItems.getChildAt(selectedIdx).requestFocus();
                }
                // If an error occurs for any reason, safely fall back to focusing index 0 (the first item).
                else if (containerSettingsItems.getChildCount() > 0) {
                    containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
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
        }
    }

    private void toggleWebServer() {
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
                    try { return BitmapFactory.decodeFile(bgFile.getAbsolutePath()); } catch (Exception e) {}
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
    private boolean currentThemeHasBackground() {
        try {
            ThemeManager.ThemeData currentTheme = ThemeManager.getCurrentTheme();
            if (currentTheme.bgImage != null && !currentTheme.bgImage.isEmpty()
                    && new File(currentTheme.folderPath, currentTheme.bgImage).exists()) return true;
            if (new File(currentTheme.folderPath, "background.png").exists()) return true;
            if (new File(currentTheme.folderPath, "bg.png").exists()) return true;
        } catch (Exception e) {}
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
            if (currentScreenState == STATE_MENU || currentScreenState == STATE_BROWSER || currentScreenState == STATE_SETTINGS) {
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
        }
        pbBrightness.setProgress(currentSystemBrightness);
        int percent = (int) (((float) currentSystemBrightness / 255.0f) * 100);
        tvBrightnessVal.setText(percent + "%");
    }

    private void updateBrightness(int newBrightness) {
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
    private void handleCenterShortClick() {
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
        } catch (Exception e) {}
    }
    private void openKeyboard() {
        typedPassword = "";
        keyboardIndex = 0;
        tvKeyboardSsid.setText(t("Target") + ": " + targetWifiSsid);
        updateKeyboardUI();
    }

    private void updateKeyboardUI() {
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

        boolean isConnected = false;
        if (globalA2dp != null) {
            try {
                int state = (int) globalA2dp.getClass().getMethod("getConnectionState", BluetoothDevice.class)
                        .invoke(globalA2dp, device);
                isConnected = (state == BluetoothProfile.STATE_CONNECTED);
            } catch (Exception e) {
            }
        }

        String prefix = isConnected ? "((♪)) [CONNECTED] " : "✔ ";
        final Button btnDevice = createListButton(prefix + name);

        if (isConnected) {
            int themeColor = 0xFF00FFFF;
            try {
                themeColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            } catch (Exception e) {
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
                    device.getClass().getMethod("removeBond").invoke(device);
                    Toast.makeText(MainActivity.this, t("Device Deleted."), Toast.LENGTH_SHORT).show();
                    startBluetoothScan(); // Refresh the screen after removal
                } catch (Exception e) {
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

    private void createCategoryHeader(String title) {
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

    private LinearLayout createSettingRow(String leftText, String rightText) {
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
    private android.view.View createListButtonWithIcon(String iconUnicode, String textLabel) {
        return createListButtonWithIcon(iconUnicode, textLabel, 0);
    }

    // 🚀 [Main engine upgrade] Reworked to accept a third parameter, 'customColor' (the color to paint).
    private android.view.View createListButtonWithIcon(String iconUnicode, String textLabel, final int customColor) {
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
            catch (Exception e) {}
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

    private void buildSettingsUI() {
        currentSettingsDepth = 0; // 🚀 Main settings is depth 0

        // 🚀 [Safeguard] Fully clears the radio UI flag when entering the general settings screen.
        isRadioUIShowing = false;
        isRadioSettingsMode = false;

        // 🚀 [Added] Show the hidden top title text again when returning to the general settings screen.
        android.view.ViewGroup settingsGroup = (android.view.ViewGroup) layoutSettingsMode;
        if (settingsGroup != null && settingsGroup.getChildCount() > 0 && settingsGroup.getChildAt(0) instanceof TextView) {
            settingsGroup.getChildAt(0).setVisibility(View.VISIBLE);
        }

        final int targetFocusIndex = lastSettingsFocusIndex;
        containerSettingsItems.removeAllViews();

        LinearLayout btnGroupPlayback = createSettingRow(t("Playback"), "〉 ");
        btnGroupPlayback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildPlaybackGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupPlayback);

        LinearLayout btnGroupSound = createSettingRow(t("Sound & Vibration"), "〉 ");
        btnGroupSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSoundVibrationGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupSound);

        LinearLayout btnGroupConnectivity = createSettingRow(t("Connectivity"), "〉 ");
        btnGroupConnectivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildConnectivityGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupConnectivity);

        LinearLayout btnGroupDisplay = createSettingRow(t("Display & Interface"), "〉 ");
        btnGroupDisplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDisplayInterfaceGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupDisplay);

        LinearLayout btnGroupStorage = createSettingRow(t("Storage & Library"), "〉 ");
        btnGroupStorage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildStorageLibraryGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupStorage);

        LinearLayout btnGroupSystem = createSettingRow(t("System"), "〉 ");
        btnGroupSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildSystemGroupUI();
            }
        });
        containerSettingsItems.addView(btnGroupSystem);

        // 🚀 [Fix] Force-move to the correct position using the uncorrupted, safely backed-up index (targetFocusIndex)!
        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (targetFocusIndex >= 0 && targetFocusIndex < containerSettingsItems.getChildCount()) {
                    View target = containerSettingsItems.getChildAt(targetFocusIndex);
                    target.requestFocus();

                    // Force the ScrollView to find that button's position and scroll all the way down to it!
                    if (containerSettingsItems.getParent() instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) containerSettingsItems.getParent())
                                .requestChildFocus(containerSettingsItems, target);
                    }

                    // Sync the variable state after the move is complete.
                    lastSettingsFocusIndex = targetFocusIndex;
                } else if (containerSettingsItems.getChildCount() > 0) {
                    containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    } // end of buildSettingsUI function

    private void routeBackToSettingsGroup() {
        switch (currentSettingsGroup) {
            case GROUP_PLAYBACK: buildPlaybackGroupUI(); break;
            case GROUP_SOUND: buildSoundVibrationGroupUI(); break;
            case GROUP_CONNECTIVITY: buildConnectivityGroupUI(); break;
            case GROUP_DISPLAY: buildDisplayInterfaceGroupUI(); break;
            case GROUP_STORAGE: buildStorageLibraryGroupUI(); break;
            case GROUP_SYSTEM: buildSystemGroupUI(); break;
            default: buildSettingsUI();
        }
    }

    private void buildPlaybackGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_PLAYBACK;
        containerSettingsItems.removeAllViews();

        final LinearLayout btnShuffle = createSettingRow("Shuffle Mode", isShuffleMode ? t("ON") : t("OFF"));
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isShuffleMode = !isShuffleMode;
                TextView tvStatus = (TextView) btnShuffle.getChildAt(1);
                tvStatus.setText(isShuffleMode ? t("ON") : t("OFF"));
                updatePlayerStatusIndicators();
                try {
                    prefs.edit().putBoolean("shuffle", isShuffleMode).apply();
                } catch (Exception e) {
                }

                if (!currentPlaylist.isEmpty() && !originalPlaylist.isEmpty()) {
                    File currentSong = currentPlaylist.get(currentIndex);
                    if (isShuffleMode) {
                        java.util.Collections.shuffle(currentPlaylist);
                    } else {
                        currentPlaylist.clear();
                        currentPlaylist.addAll(originalPlaylist);
                    }
                    currentIndex = currentPlaylist.indexOf(currentSong);
                    if (currentIndex == -1)
                        currentIndex = 0;
                }
            }
        });
        containerSettingsItems.addView(btnShuffle);

        final LinearLayout btnRepeat = createSettingRow("Repeat Mode", t(getRepeatModeText(repeatMode)));
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                repeatMode = (repeatMode + 1) % 3;
                TextView tvStatus = (TextView) btnRepeat.getChildAt(1);
                tvStatus.setText(t(getRepeatModeText(repeatMode)));
                updatePlayerStatusIndicators();
                try {
                    prefs.edit().putInt("repeat_mode", repeatMode).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnRepeat);

        String eqDisplayName = "Normal";
        if (currentEqProfile.startsWith("preset_")) {
            int pIdx = Integer.parseInt(currentEqProfile.replace("preset_", ""));
            if (pIdx < eqPresetNames.size()) eqDisplayName = t(eqPresetNames.get(pIdx));
        } else {
            eqDisplayName = currentEqProfile.replace("custom_", "");
        }
        final LinearLayout btnEq = createSettingRow("Equalizer & Audio Effects", eqDisplayName + " 〉");

        btnEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildEqualizerSettingsUI();
            }
        });
        containerSettingsItems.addView(btnEq);

        final String[] speedLabels = {"1.0x (Normal)", "1.2x (Fast)", "1.5x (Faster)", "2.0x (Very Fast)"};
        final float[] speedValues = {1.0f, 1.2f, 1.5f, 2.0f};

        float currentSpd = com.themoon.y1.managers.AudioPlayerManager.getInstance().getCurrentSpeed();
        int spdIdx = 0;
        for (int i=0; i<speedValues.length; i++) { if (speedValues[i] == currentSpd) spdIdx = i; }

        final LinearLayout btnSpeed = createSettingRow("Playback Speed", t(speedLabels[spdIdx]));
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                float current = com.themoon.y1.managers.AudioPlayerManager.getInstance().getCurrentSpeed();
                int nextIdx = 0;
                for (int i=0; i<speedValues.length; i++) { if (speedValues[i] == current) nextIdx = (i + 1) % speedValues.length; }

                com.themoon.y1.managers.AudioPlayerManager.getInstance().setPlaybackSpeed(speedValues[nextIdx]);

                TextView tvStatus = (TextView) btnSpeed.getChildAt(1);
                tvStatus.setText(t(speedLabels[nextIdx]));
                android.widget.Toast.makeText(MainActivity.this, t("Speed set to ") + t(speedLabels[nextIdx]), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        containerSettingsItems.addView(btnSpeed);

        final LinearLayout btnAutoFetch = createSettingRow("Auto Fetch Album Art", isAutoFetchEnabled ? t("ON") : t("OFF"));
        btnAutoFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isAutoFetchEnabled = !isAutoFetchEnabled;
                ((TextView) btnAutoFetch.getChildAt(1)).setText(isAutoFetchEnabled ? t("ON") : t("OFF"));
                try {
                    prefs.edit().putBoolean("auto_fetch", isAutoFetchEnabled).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnAutoFetch);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    private void buildSoundVibrationGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_SOUND;
        containerSettingsItems.removeAllViews();

        final LinearLayout btnSound = createSettingRow("Button Sound", isSoundEffectEnabled ? t("ON") : t("OFF"));
        btnSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSoundEffectEnabled = !isSoundEffectEnabled;
                applySoundSetting();
                clickFeedback();
                TextView tvStatus = (TextView) btnSound.getChildAt(1);
                tvStatus.setText(isSoundEffectEnabled ? t("ON") : t("OFF"));
                try {
                    prefs.edit().putBoolean("sound", isSoundEffectEnabled).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnSound);

        final LinearLayout btnSpeakerDisable = createSettingRow("Disable Built-in Speaker", isSpeakerDisabled ? t("ON") : t("OFF"));
        btnSpeakerDisable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSpeakerDisabled = !isSpeakerDisabled;
                applySpeakerSetting();
                clickFeedback();
                TextView tvStatus = (TextView) btnSpeakerDisable.getChildAt(1);
                tvStatus.setText(isSpeakerDisabled ? t("ON") : t("OFF"));
                try {
                    prefs.edit().putBoolean("speaker_disabled", isSpeakerDisabled).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnSpeakerDisable);

        LinearLayout btnVibrateMenu = createSettingRow("Vibration", "〉 ");
        btnVibrateMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildVibrationSettingsUI();
            }
        });
        containerSettingsItems.addView(btnVibrateMenu);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    private void buildConnectivityGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_CONNECTIVITY;
        containerSettingsItems.removeAllViews();

        LinearLayout btnWifiMenu = createSettingRow(t("Wi-Fi"), "〉 ");
        btnWifiMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_WIFI);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnWifiMenu);

        LinearLayout btnBtMenu = createSettingRow("Bluetooth", "〉 ");
        btnBtMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_BLUETOOTH);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnBtMenu);

        LinearLayout btnServerMenu = createSettingRow(t("Web Server"), "〉 ");
        btnServerMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_WEBSERVER);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnServerMenu);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    private void buildDisplayInterfaceGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_DISPLAY;
        containerSettingsItems.removeAllViews();

        final LinearLayout btnTheme = createSettingRow("Theme", ThemeManager.getCurrentTheme().name);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildThemeSelectorUI();
            }
        });
        containerSettingsItems.addView(btnTheme);

        LinearLayout btnBgMenu = createSettingRow("Background", "〉 ");
        btnBgMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildBackgroundSettingsUI();
            }
        });
        containerSettingsItems.addView(btnBgMenu);

        final LinearLayout btnMenuVisibility = createSettingRow("Main Menu Items", t("Edit") + " 〉");
        btnMenuVisibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildMainMenuVisibilitySettingsUI();

                containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (containerSettingsItems.getChildCount() > 0) {
                            containerSettingsItems.getChildAt(0).requestFocus();
                        }
                    }
                }, 50);
            }
        });
        containerSettingsItems.addView(btnMenuVisibility);

        final LinearLayout btnScreenOffCtrl = createSettingRow("Screen-Off Control",
                isScreenOffControlEnabled ? t("ON") : t("OFF"));
        btnScreenOffCtrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isScreenOffControlEnabled = !isScreenOffControlEnabled;
                TextView tvStatus = (TextView) btnScreenOffCtrl.getChildAt(1);
                tvStatus.setText(isScreenOffControlEnabled ? t("ON") : t("OFF"));
                try {
                    prefs.edit().putBoolean("screen_off_control", isScreenOffControlEnabled).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnScreenOffCtrl);

        final LinearLayout btnWheelLock = createSettingRow("Lock Wheel on Wake", isWheelLockEnabled ? t("ON") : t("OFF"));
        btnWheelLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWheelLockEnabled = !isWheelLockEnabled;
                TextView tvStatus = (TextView) btnWheelLock.getChildAt(1);
                tvStatus.setText(isWheelLockEnabled ? t("ON") : t("OFF"));
                try {
                    prefs.edit().putBoolean("wheel_lock_on_wake", isWheelLockEnabled).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnWheelLock);

        final LinearLayout btnLoopScrollToggle = createSettingRow("Wheel Loop Scroll", isLoopScrollOn ? t("ON") : t("OFF"));
        btnLoopScrollToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isLoopScrollOn = !isLoopScrollOn;
                ((TextView) btnLoopScrollToggle.getChildAt(1)).setText(isLoopScrollOn ? t("ON") : t("OFF"));
                prefs.edit().putBoolean("loop_scroll_on", isLoopScrollOn).apply();
            }
        });
        containerSettingsItems.addView(btnLoopScrollToggle);

        final LinearLayout btnTimeout = createSettingRow("Screen Timeout", t(TIMEOUT_NAMES[currentTimeoutIndex]));
        btnTimeout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                currentTimeoutIndex = (currentTimeoutIndex + 1) % TIMEOUT_VALUES.length;
                ((TextView) btnTimeout.getChildAt(1)).setText(t(TIMEOUT_NAMES[currentTimeoutIndex]));

                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                            TIMEOUT_VALUES[currentTimeoutIndex]);
                } catch (Exception e) {
                }
                try {
                    prefs.edit().putInt("timeout_idx", currentTimeoutIndex).apply();
                } catch (Exception e) {
                }
            }
        });
        containerSettingsItems.addView(btnTimeout);

        LinearLayout btnBrightMenu = createSettingRow("Display Brightness", "〉 ");
        btnBrightMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_BRIGHTNESS);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnBrightMenu);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    private void buildStorageLibraryGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_STORAGE;
        containerSettingsItems.removeAllViews();

        LinearLayout btnStorageMenu = createSettingRow("Storage", "〉 ");
        btnStorageMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeScreen(STATE_STORAGE);
                clickFeedback();
            }
        });
        containerSettingsItems.addView(btnStorageMenu);

        LinearLayout btnClearCache = createSettingRow("Clear Album Art & Info", "〉 ");
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Clear Cache & Track Info"))
                        .setMessage(t("Delete all downloaded album covers and saved track information (Title/Artist)?"))
                        .setPositiveButton(t("Clear"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    File coverFolder = new File("/storage/sdcard0/Y1_Covers");
                                    int count = 0;
                                    if (coverFolder.exists()) {
                                        File[] files = coverFolder.listFiles();
                                        if (files != null) {
                                            for (File f : files) {
                                                if (f.isFile() && f.delete())
                                                    count++;
                                            }
                                        }
                                    }

                                    libraryCacheDb.clearAllMetaAndArt();

                                    Toast.makeText(MainActivity.this, "Deleted " + count + " covers & cleared track info.",
                                            Toast.LENGTH_SHORT).show();

                                    ivAlbumArt.setImageResource(R.drawable.default_album);
                                    ivPlayerBgBlur.setImageResource(0);
                                    lastAlbumArtBytes = null;

                                    if (!currentPlaylist.isEmpty()) {
                                        File currentFile = currentPlaylist.get(currentIndex);
                                        tvPlayerTitle.setText(currentFile.getName());
                                        tvPlayerArtist.setText("Unknown Artist");
                                    }

                                    updateMainMenuBackground();
                                    refreshNowPlayingPreview();
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "Failed to clear cache.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(t("Cancel"), null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnClearCache);

        LinearLayout btnRebuildCache = createSettingRow("Rebuild Library Cache", "〉 ");
        btnRebuildCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Rebuild Library Cache"))
                        .setMessage(t("Re-scan every song and re-read its tags from scratch? This is slower than a normal scan but fixes a stale or incorrect cache."))
                        .setPositiveButton(t("Rebuild"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (libraryCacheDb != null) libraryCacheDb.clear();
                                startMediaLibraryScan();
                            }
                        })
                        .setNegativeButton(t("Cancel"), null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnRebuildCache);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    private void buildSystemGroupUI() {
        currentSettingsDepth = 1;
        currentSettingsGroup = GROUP_SYSTEM;
        containerSettingsItems.removeAllViews();

        LinearLayout btnTime = createSettingRow("Date & Time", "〉");
        btnTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();

                java.util.Calendar c = java.util.Calendar.getInstance();
                dtYear = c.get(java.util.Calendar.YEAR);
                dtMonth = c.get(java.util.Calendar.MONTH) + 1;
                dtDay = c.get(java.util.Calendar.DAY_OF_MONTH);
                dtHour = c.get(java.util.Calendar.HOUR_OF_DAY);
                dtMinute = c.get(java.util.Calendar.MINUTE);

                buildDateTimeUI();
            }
        });
        containerSettingsItems.addView(btnTime);

        String myVersionName = "1.0";
        try {
            myVersionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
        }
        String displayLang = com.themoon.y1.managers.LanguageManager.getInstance(this).currentLangFileName.replace(".json", "");
        LinearLayout btnLangMenu = createSettingRow("Language", displayLang);
        btnLangMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildLanguageSelectorUI();
            }
        });
        containerSettingsItems.addView(btnLangMenu);

        LinearLayout btnUpdateCheck = createSettingRow("System Update", "v" + myVersionName);
        btnUpdateCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildUpdateSettingsUI();
            }
        });
        containerSettingsItems.addView(btnUpdateCheck);

        LinearLayout btnPowerOff = createSettingRow("Power Off", "〉 ");
        btnPowerOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Power Off"))
                        .setMessage(t("Do you want to shut down the device?"))
                        .setPositiveButton(t("Shut Down"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Process proc = Runtime.getRuntime().exec(new String[] { "su", "-c", "reboot -p" });
                                    proc.waitFor();
                                } catch (Exception e) {
                                    try {
                                        Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
                                        intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(intent);
                                    } catch (Exception ex) {
                                        Toast.makeText(MainActivity.this,
                                                t("System security prevents powering off directly from the app."),
                                                Toast.LENGTH_LONG).show();
                                    }
                                }
                            }
                        })
                        .setNegativeButton(t("Cancel"), null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnPowerOff);

        LinearLayout btnSwitchRockbox = createSettingRow(t("Switch to Rockbox"), "〉 ");
        btnSwitchRockbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();

                boolean isRockboxInstalled = false;
                try {
                    getPackageManager().getPackageInfo("org.rockbox", 0);
                    isRockboxInstalled = true;
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    isRockboxInstalled = false;
                }

                if (!isRockboxInstalled) {
                        new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(t("Not Installed ⚠️"))
                                .setMessage(t("Rockbox is not installed on this device.\nPlease install the Rockbox app (.apk) first."))
                                .setPositiveButton(t("OK"), null)
                            .show();
                    return;
                }

                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(t("Switch to Rockbox"))
                        .setMessage(t("Do you want to switch to Rockbox instantly without rebooting?"))
                        .setPositiveButton(t("Switch"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Toast.makeText(MainActivity.this, "Switching to Rockbox...", Toast.LENGTH_SHORT).show();

                                    String cmd = "pm enable org.rockbox && am start -n org.rockbox/.RockboxActivity && pm disable com.themoon.y1";

                                    Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });

                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "Failed: Root access required.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(t("Cancel"), null)
                        .show();
            }
        });
        containerSettingsItems.addView(btnSwitchRockbox);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }
    // 💡 [New] Screen dedicated to selecting a language pack
    private void buildLanguageSelectorUI() {
        currentSettingsDepth = 2;
        containerSettingsItems.removeAllViews();

        final com.themoon.y1.managers.LanguageManager langMgr = com.themoon.y1.managers.LanguageManager.getInstance(this);
        langMgr.loadAvailableLanguages(); // Re-scan the folder

        // 1. Default English button
        String enPrefix = langMgr.currentLangFileName.equals("English (Default)") ? "✔ " : "   ";
        Button btnEng = createListButton(enPrefix + "English (Default)");
        if (langMgr.currentLangFileName.equals("English (Default)")) { btnEng.setTextColor(0xFF00FF00); }
        btnEng.setOnClickListener(v -> {
            clickFeedback();
            prefs.edit().putString("app_language", "English (Default)").apply();
            langMgr.applyLanguage("English (Default)");
            recreate(); // Fully refresh the screen to apply immediately!
        });
        containerSettingsItems.addView(btnEng);

        // 2. List the JSON language packs read in from external storage
        for (final File f : langMgr.availableLangFiles) {
            String prefix = langMgr.currentLangFileName.equals(f.getName()) ? "✔ " : "   ";
            Button btnLang = createListButton(prefix + f.getName().replace(".json", ""));
            if (langMgr.currentLangFileName.equals(f.getName())) { btnLang.setTextColor(0xFF00FF00); }

            btnLang.setOnClickListener(v -> {
                clickFeedback();
                prefs.edit().putString("app_language", f.getName()).apply();
                langMgr.applyLanguage(f.getName());
                recreate(); // Reboot the activity immediately when the language changes!
            });
            containerSettingsItems.addView(btnLang);
        }

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }
    private void buildRadioUI() {
        currentSettingsDepth = 1;
        isRadioUIShowing = true; // 🚀 Tell the system I'm currently on the radio screen!
        containerSettingsItems.removeAllViews();

        // 🚀 Fully block/hide the ghost "Settings" title at the top
        android.view.ViewGroup settingsGroup = (android.view.ViewGroup) layoutSettingsMode;
        if (settingsGroup != null && settingsGroup.getChildCount() > 0 && settingsGroup.getChildAt(0) instanceof TextView) {
            settingsGroup.getChildAt(0).setVisibility(View.GONE);
        }

        final com.themoon.y1.managers.FmRadioManager fmManager = com.themoon.y1.managers.FmRadioManager.getInstance(this);

        if (savedRadioStations.isEmpty()) {
            try {
                String savedStationsStr = prefs.getString("radio_stations", "");
                if (!savedStationsStr.isEmpty()) {
                    for(String s : savedStationsStr.split(",")) savedRadioStations.add(Float.parseFloat(s));
                }
            } catch(Exception e){}
        }

        final float density = getResources().getDisplayMetrics().density;

        // ==========================================================
        // 🎧 [Mode 1] Default player mode (🔮 neon highlight glow + bottom alignment)
        // ==========================================================
        if (!isRadioSettingsMode) {

            // Advanced outer frame panel setup
            android.widget.FrameLayout freqPanel = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams panelLp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            panelLp.setMargins((int)(15 * density), (int)(30 * density), (int)(15 * density), (int)(15 * density));
            freqPanel.setLayoutParams(panelLp);

            android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
            panelBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            panelBg.setCornerRadius(18 * density);

            int themeHighlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;

            if (fmManager.isPowerUp) {
                int backlitColor = (themeHighlightColor & 0x00FFFFFF) | 0x42000000;
                panelBg.setColor(backlitColor);
                panelBg.setStroke((int)(4 * density), themeHighlightColor);
            } else {
                panelBg.setColor(0x15FFFFFF);
                panelBg.setStroke((int)(1 * density), 0x33FFFFFF);
            }
            freqPanel.setBackground(panelBg);

            // Large digital frequency text view
            final android.widget.TextView tvFreq = new android.widget.TextView(this);
            tvFreq.setTag("radio_main_freq_text");
            tvFreq.setText(String.format(java.util.Locale.US, "%.1f MHz", fmManager.currentFreq));
            tvFreq.setTextColor(fmManager.isPowerUp ? themeHighlightColor : 0xFF888888);
            tvFreq.setTextSize(54);
            tvFreq.setGravity(android.view.Gravity.CENTER);
            tvFreq.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tvFreq.setPadding(0, (int)(38 * density), 0, (int)(38 * density));

            freqPanel.addView(tvFreq);
            containerSettingsItems.addView(freqPanel);

            // 🍬 Horizontally scrolling pill channel container
            if (!savedRadioStations.isEmpty()) {
                android.widget.HorizontalScrollView hzScroll = new android.widget.HorizontalScrollView(this);
                hzScroll.setHorizontalScrollBarEnabled(false);
                hzScroll.setClipChildren(false);
                hzScroll.setClipToPadding(false);
                // 💡 This option needs to be on for channels to center nicely when there are only a few (1-3)!
                hzScroll.setFillViewport(true);
                hzScroll.setPadding(0, 15, 0, 15);

                android.widget.LinearLayout candyContainer = new android.widget.LinearLayout(this);
                candyContainer.setOrientation(android.widget.LinearLayout.HORIZONTAL);

                // 🚀 [Fix 1] Allow full CENTER alignment instead of just CENTER_VERTICAL.
                candyContainer.setGravity(android.view.Gravity.CENTER);

                for (int i = 0; i < savedRadioStations.size(); i++) {
                    float stationFreq = savedRadioStations.get(i);

                    android.widget.TextView tvCandy = new android.widget.TextView(this);
                    tvCandy.setText(String.format(java.util.Locale.US, "%.1f", stationFreq));
                    tvCandy.setTextSize(18f);
                    tvCandy.setGravity(android.view.Gravity.CENTER);
                    tvCandy.setPadding((int)(14*density), (int)(6*density), (int)(14*density), (int)(6*density));
                    tvCandy.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                    tvCandy.setTag(stationFreq);

                    android.graphics.drawable.GradientDrawable candyBg = new android.graphics.drawable.GradientDrawable();
                    candyBg.setCornerRadius(20 * density);

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor);
                        tvCandy.setTextColor(ThemeManager.getListButtonFocusedTextColor());

                        final android.view.View targetChild = tvCandy;
                        final android.widget.HorizontalScrollView finalHzScroll = hzScroll;
                        hzScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                int scrollX = targetChild.getLeft() - (finalHzScroll.getWidth() / 2) + (targetChild.getWidth() / 2);
                                if (scrollX < 0) scrollX = 0; // safety guard
                                finalHzScroll.scrollTo(scrollX, 0);
                            }
                        });
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg());
                        tvCandy.setTextColor(ThemeManager.getTextColorSecondary());
                    }

                    tvCandy.setBackground(candyBg);

                    android.widget.LinearLayout.LayoutParams candyLp = new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    candyLp.setMargins((int)(6*density), 0, (int)(6*density), 0);
                    tvCandy.setLayoutParams(candyLp);

                    candyContainer.addView(tvCandy);
                }

                // 🚀 [Fix 2 key point!] Completely drop MATCH_PARENT and CENTER_HORIZONTAL, switch to WRAP_CONTENT!
                // This way, even as more items are added, the left wall (0px point) doesn't collapse and it correctly scrolls to the right only.
                android.widget.FrameLayout.LayoutParams containerLp = new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);

                // ❌ Absolutely forbidden: containerLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

                hzScroll.addView(candyContainer, containerLp);

                // 🚀 [Bug fix complete] Snap the fully assembled horizontal-scroll pouch onto the main screen!
                containerSettingsItems.addView(hzScroll);

                layoutRadioCandyContainer = candyContainer;
            }

            // Weighted spacer for the bottom control layout
            android.view.View spacer = new android.view.View(this);
            android.widget.LinearLayout.LayoutParams spacerLp = new android.widget.LinearLayout.LayoutParams(0, 0, 1.0f);
            spacer.setLayoutParams(spacerLp);
            containerSettingsItems.addView(spacer);

            // 3. Settings-mode entry button (docked at the very bottom)
            android.widget.Button btnSettings = createListButton(t("Radio Settings"));
            btnSettings.setGravity(android.view.Gravity.CENTER);

            android.widget.LinearLayout.LayoutParams settingsLp = (android.widget.LinearLayout.LayoutParams) btnSettings.getLayoutParams();
            if (settingsLp != null) {
                settingsLp.bottomMargin = (int)(15 * density);
                btnSettings.setLayoutParams(settingsLp);
            }

            btnSettings.setOnClickListener(v -> {
                clickFeedback();
                isRadioSettingsMode = true;
                buildRadioUI();
            });
            containerSettingsItems.addView(btnSettings);

            containerSettingsItems.postDelayed(() -> {
                if (containerSettingsItems.getChildCount() > 0) {
                    containerSettingsItems.getChildAt(containerSettingsItems.getChildCount() - 1).requestFocus();
                }
            }, 50);

        }
        // ==========================================================
        // ⚙️ [Mode 2] Settings sub-page mode (existing logic fully preserved)
        // ==========================================================
        else {
            android.widget.Button btnClose = createListButton(t("Close Settings"));
            btnClose.setTextColor(0xFFFF8800);
            btnClose.setOnClickListener(v -> {
                clickFeedback();
                isRadioSettingsMode = false;
                isRadioAdjustingFreq = false;
                buildRadioUI();
            });
            containerSettingsItems.addView(btnClose);

            final android.widget.LinearLayout btnPower = createSettingRow("Radio Power", fmManager.isPowerUp ? t("ON") : t("OFF"));
            btnPower.setOnLongClickListener(globalScreenOffLongClickListener);
            btnPower.setOnClickListener(v -> {
                clickFeedback();
                if (fmManager.isPowerUp) {
                    fmManager.powerDown();
                    isRadioAdjustingFreq = false;
                    updateGlobalStatusPlayIcon();
                    buildRadioUI();
                } else {
                    com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                    if (am.isPlaying()) am.playOrPauseMusic();
                    // Give playback a moment to actually pause before the FM chip claims the audio
                    // session; posted with a delay instead of Thread.sleep so the UI thread isn't blocked.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        fmManager.powerUpAsync(fmManager.currentFreq, success -> {
                            if (success) activePlayer = 1;
                            else android.widget.Toast.makeText(MainActivity.this, "Radio Error: " + fmManager.lastError, android.widget.Toast.LENGTH_LONG).show();
                            updateGlobalStatusPlayIcon();
                            buildRadioUI();
                        });
                    }, 100);
                }
            });
            containerSettingsItems.addView(btnPower);

            String freqRightText = isRadioAdjustingFreq ? t("[ ADJUSTING ]") : t("Click to Tune");
            final android.widget.LinearLayout btnTune = createSettingRow("Tune Frequency", freqRightText);
            if (isRadioAdjustingFreq) ((android.widget.TextView)btnTune.getChildAt(1)).setTextColor(0xFFFF8800);
            btnTune.setOnLongClickListener(globalScreenOffLongClickListener);
            btnTune.setOnClickListener(v -> {
                clickFeedback();
                isRadioAdjustingFreq = !isRadioAdjustingFreq;
                buildRadioUI();
            });
            containerSettingsItems.addView(btnTune);

            boolean isSaved = savedRadioStations.contains(fmManager.currentFreq);
            final android.widget.LinearLayout btnSaveFreq = createSettingRow("Save Channel", isSaved ? "★ " + t("SAVED") : "☆ " + t("SAVE"));
            if (isSaved) ((android.widget.TextView)btnSaveFreq.getChildAt(1)).setTextColor(0xFFFF8800);
            btnSaveFreq.setOnLongClickListener(globalScreenOffLongClickListener);
            btnSaveFreq.setOnClickListener(v -> {
                clickFeedback();
                if (isSaved) {
                    savedRadioStations.remove(Float.valueOf(fmManager.currentFreq));
                    android.widget.Toast.makeText(MainActivity.this, t("Removed from saved channels."), android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    savedRadioStations.add(fmManager.currentFreq);
                    java.util.Collections.sort(savedRadioStations);
                    android.widget.Toast.makeText(MainActivity.this, t("Channel saved!"), android.widget.Toast.LENGTH_SHORT).show();
                }
                StringBuilder sb = new StringBuilder();
                for(int i=0; i<savedRadioStations.size(); i++) {
                    sb.append(savedRadioStations.get(i));
                    if(i < savedRadioStations.size()-1) sb.append(",");
                }
                prefs.edit().putString("radio_stations", sb.toString()).apply();
                buildRadioUI();
            });
            containerSettingsItems.addView(btnSaveFreq);

            final android.widget.LinearLayout btnAutoScan = createSettingRow("Auto Scan All", t("Start") + " >");
            btnAutoScan.setOnLongClickListener(globalScreenOffLongClickListener);
            btnAutoScan.setOnClickListener(v -> {
                clickFeedback();
                if (!fmManager.isPowerUp) {
                    android.widget.Toast.makeText(MainActivity.this, t("Please turn on Radio Power first!"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isRadioScanning) return;

                isRadioScanning = true;
                showLoadingPopup();

                new Thread(() -> {
                    float fakeFreq = 87.5f;
                    int progress = 0;
                    while (isRadioScanning) {
                        final int p = progress;
                        final float f = fakeFreq;
                        runOnUiThread(() -> {
                            if (pbLoadingProgress != null) {
                                pbLoadingProgress.setIndeterminate(false);
                                pbLoadingProgress.setProgress(p);
                            }
                            if (tvLoadingProgress != null) {

                                tvLoadingProgress.setText(String.format(java.util.Locale.US, t("Scanning FM Frequencies...\nSearching around %.1f MHz"), f));                            }
                        });
                        try { Thread.sleep(70); } catch(Exception e){}
                        progress += 1;
                        if (progress > 100) progress = 0;
                        fakeFreq += 0.1f;
                        if (fakeFreq > 108.0f) fakeFreq = 87.5f;
                    }
                }).start();

                new Thread(() -> {
                    final float[] foundStations = fmManager.autoScan();
                    isRadioScanning = false;

                    runOnUiThread(() -> {
                        if (layoutLoadingOverlay != null) layoutLoadingOverlay.setVisibility(android.view.View.GONE);
                        if (tvLoadingProgress != null) tvLoadingProgress.setText(t("Preparing to scan...\nPlease wait."));

                        if (foundStations != null && foundStations.length > 0) {
                            savedRadioStations.clear();
                            for (float f : foundStations) savedRadioStations.add(f);
                            java.util.Collections.sort(savedRadioStations);

                            StringBuilder sb = new StringBuilder();
                            for(int i=0; i<savedRadioStations.size(); i++) {
                                sb.append(savedRadioStations.get(i));
                                if(i < savedRadioStations.size()-1) sb.append(",");
                            }
                            prefs.edit().putString("radio_stations", sb.toString()).apply();
                            android.widget.Toast.makeText(MainActivity.this, t("Scan Complete!\nFound")+" " + foundStations.length + t("channels.\nTuning to")+" " + foundStations[0] + "MHz", android.widget.Toast.LENGTH_LONG).show();
                            fmManager.tune(foundStations[0]);
                        } else {
                            android.widget.Toast.makeText(MainActivity.this, t("No stations found.")+" (" + fmManager.lastError + ")", android.widget.Toast.LENGTH_LONG).show();
                        }
                        buildRadioUI();
                    });
                }).start();
            });
            containerSettingsItems.addView(btnAutoScan);

            final android.widget.LinearLayout btnSpeaker = createSettingRow("Audio Output", fmManager.isSpeakerOn ? t("Speaker") : t("Earphones"));
            btnSpeaker.setOnLongClickListener(globalScreenOffLongClickListener);
            btnSpeaker.setOnClickListener(v -> {
                clickFeedback();
                fmManager.setSpeaker(!fmManager.isSpeakerOn);
                buildRadioUI();
            });
            containerSettingsItems.addView(btnSpeaker);

            containerSettingsItems.postDelayed(() -> {
                int targetIdx = lastRadioFocusIndex;
                if (isRadioAdjustingFreq) {
                    targetIdx = 2;
                }
                if (targetIdx >= 0 && targetIdx < containerSettingsItems.getChildCount()) {
                    containerSettingsItems.getChildAt(targetIdx).requestFocus();
                } else if (containerSettingsItems.getChildCount() > 0) {
                    containerSettingsItems.getChildAt(0).requestFocus();
                }
            }, 50);
        }
    }

    private void buildUpdateSettingsUI() {
        currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        containerSettingsItems.removeAllViews();

        // 1. Get my device's current version
        String myVersionName = "1.0";
        int tempCode = 1;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            myVersionName = pInfo.versionName;
            tempCode = pInfo.versionCode;
        } catch (Exception e) {
        }

        final int myVersionCode = tempCode;

        // 2. Current version display line
        LinearLayout rowCurrent = createSettingRow("Current Version", "v" + myVersionName);
        containerSettingsItems.addView(rowCurrent);

        // 3. Server version display line (initially shows Checking...)
        final LinearLayout rowServer = createSettingRow("Latest Version", "Checking...");
        containerSettingsItems.addView(rowServer);

        createCategoryHeader("━━━━━━━━━━━━━━");

        // 4. Bottom update-execution button (hidden until the server check completes)
        final Button btnExecuteUpdate = createListButton("🚀 " + t("DOWNLOAD & UPDATE"));;
        btnExecuteUpdate.setVisibility(View.GONE);
        containerSettingsItems.addView(btnExecuteUpdate);

        // 🚀 5. As soon as the screen opens, read the server's output-metadata.json in the background!
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(METADATA_URL);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                    // 🚀 [Required 1] Break through GitHub's security (TLS 1.2): equip the secret weapon we built!
                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new TLSSocketFactory());
                        } catch (Exception e) {
                        }
                    }

                    conn.setInstanceFollowRedirects(false); // Turn off the default auto-follow so we can track redirects manually
                    conn.setConnectTimeout(5000);

                    // 🚀 [Required 2] Chase GitHub redirects (address forwarding) all the way to the end!
                    int status = conn.getResponseCode();
                    if (status == 301 || status == 302 || status == 303) {
                        String newUrl = conn.getHeaderField("Location");
                        conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
                        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                            try {
                                ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new TLSSocketFactory());
                            } catch (Exception e) {
                            }
                        }
                    }

                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null)
                        sb.append(line);
                    in.close();

                    org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                    org.json.JSONArray elements = root.getJSONArray("elements");
                    org.json.JSONObject element = elements.getJSONObject(0);

                    final int serverVersionCode = element.getInt("versionCode");
                    final String serverVersionName = element.getString("versionName");
                    final String apkFileName = element.getString("outputFile");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Update the server version text (e.g. Checking... -> v1.2)
                            TextView tvServer = (TextView) rowServer.getChildAt(1);
                            tvServer.setText("v" + serverVersionName);

                            // 🚀 [Comparison] When an update is needed
                            if (serverVersionCode > myVersionCode) {
                                tvServer.setTextColor(0xFF00FF00); // Make the server version an eye-catching green!

                                btnExecuteUpdate.setVisibility(View.VISIBLE);
                                btnExecuteUpdate.setTextColor(0xFFFFFFFF);
                                btnExecuteUpdate.setTypeface(null, android.graphics.Typeface.BOLD);
                                btnExecuteUpdate.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        clickFeedback();
                                        String downloadUrl = SERVER_BASE_URL + apkFileName;
                                        downloadAndInstallApk(downloadUrl); // Call the download engine
                                    }
                                });
                            }
                            // 🚀 [Comparison] When already on the latest version
                            else {
                                tvServer.setTextColor(ThemeManager.getTextColorSecondary());

                                btnExecuteUpdate.setText("✔ " + t("ALREADY UP TO DATE"));
                                btnExecuteUpdate.setVisibility(View.VISIBLE);
                                btnExecuteUpdate.setTextColor(ThemeManager.getTextColorSecondary());
                                btnExecuteUpdate.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        clickFeedback();
                                        Toast.makeText(MainActivity.this, t("You are using the latest version."),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView tvServer = (TextView) rowServer.getChildAt(1);
                            tvServer.setText(t("Network Error"));
                            tvServer.setTextColor(0xFFFF4444); // Red error indicator
                        }
                    });
                }
            }
        }).start();

        // Automatically focuses the second button (Current Version) on entry
        if (containerSettingsItems.getChildCount() > 0) {
            containerSettingsItems.getChildAt(0).requestFocus();
        }
    }
    // 💡 [New] Dedicated sub-menu for vibration ON/OFF and intensity control!
    private void buildVibrationSettingsUI() {
        currentSettingsDepth = 2; // Tell the system we've left the main settings screen
        containerSettingsItems.removeAllViews();

        // 1. Vibration power switch
        final LinearLayout btnToggle = createSettingRow("Vibration Power", isVibrationEnabled ? t("ON") : t("OFF"));
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isVibrationEnabled = !isVibrationEnabled;
                clickFeedback();
                ((TextView) btnToggle.getChildAt(1)).setText(isVibrationEnabled ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("vibrate", isVibrationEnabled).apply(); } catch (Exception e) {}
            }
        });
        containerSettingsItems.addView(btnToggle);

        // 2. Vibration intensity switch (cycles Weak -> Normal -> Strong)
        final LinearLayout btnStrength = createSettingRow("Vibration Strength", t(VIBE_STRENGTH_NAMES[vibrationStrengthLevel]));
        btnStrength.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vibrationStrengthLevel = (vibrationStrengthLevel + 1) % 3; // cycles 0, 1, 2

                // 💡 Since a vibration at the new intensity fires immediately on press, you can feel the change right away!
                clickFeedback();

                // 🚀 [Fix complete] Make sure the text is always passed through the translator t() when it changes on button press too!
                ((TextView) btnStrength.getChildAt(1)).setText(t(VIBE_STRENGTH_NAMES[vibrationStrengthLevel]));
                try { prefs.edit().putInt("vibrate_strength", vibrationStrengthLevel).apply(); } catch (Exception e) {}
            }
        });
        containerSettingsItems.addView(btnStrength);

        // Focus the first button on entering the menu!
        if (containerSettingsItems.getChildCount() > 0) {
            containerSettingsItems.getChildAt(0).requestFocus();
        }
    }
    // 💡 [Added] Sub-menu screen dedicated to toggling widgets on and off
    // 💡 [Fix complete] Unified widget settings menu that can toggle all 5 widgets on and off
    private void buildWidgetSettingsUI() {
        currentSettingsDepth = 1; // Tell the system we've left the main settings screen
        containerSettingsItems.removeAllViews();

        // 1. Existing: digital clock & date widget switch
        final LinearLayout btnClock = createSettingRow("Widget: Digital Clock", isWidgetClockOn ? t("ON") : t("OFF"));
        btnClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetClockOn = !isWidgetClockOn;
                ((TextView) btnClock.getChildAt(1)).setText(isWidgetClockOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_clock", isWidgetClockOn).apply(); } catch (Exception e) {}
                refreshWidgets(); // Instantly update the widget screen when the switch is turned on!
            }
        });
        containerSettingsItems.addView(btnClock);

        // 2. New: analog clock widget switch
        final LinearLayout btnAnalogClock = createSettingRow("Widget: Analog Clock", isWidgetAnalogClockOn ? t("ON") : t("OFF"));
        btnAnalogClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetAnalogClockOn = !isWidgetAnalogClockOn;
                ((TextView) btnAnalogClock.getChildAt(1)).setText(isWidgetAnalogClockOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_analog_clock", isWidgetAnalogClockOn).apply(); } catch (Exception e) {}
                refreshWidgets();
            }
        });
        containerSettingsItems.addView(btnAnalogClock);

        // 3. Existing: bar-style battery widget switch
        final LinearLayout btnBattery = createSettingRow("Widget: Battery Bar", isWidgetBatteryOn ? t("ON") : t("OFF"));
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetBatteryOn = !isWidgetBatteryOn;
                ((TextView) btnBattery.getChildAt(1)).setText(isWidgetBatteryOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_battery", isWidgetBatteryOn).apply(); } catch (Exception e) {}
                refreshWidgets();
            }
        });
        containerSettingsItems.addView(btnBattery);

        // 4. New: circular battery widget switch
        final LinearLayout btnCircularBattery = createSettingRow("Widget: Circular Battery", isWidgetCircularBatteryOn ? t("ON") : t("OFF"));
        btnCircularBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetCircularBatteryOn = !isWidgetCircularBatteryOn;
                ((TextView) btnCircularBattery.getChildAt(1)).setText(isWidgetCircularBatteryOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_circular_battery", isWidgetCircularBatteryOn).apply(); } catch (Exception e) {}
                refreshWidgets();
            }
        });
        containerSettingsItems.addView(btnCircularBattery);

        // 5. Existing: album art widget switch
        final LinearLayout btnAlbum = createSettingRow("Widget: Now Playing Album", isWidgetAlbumOn ? t("ON") : t("OFF"));
        btnAlbum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetAlbumOn = !isWidgetAlbumOn;
                ((TextView) btnAlbum.getChildAt(1)).setText(isWidgetAlbumOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_album", isWidgetAlbumOn).apply(); } catch (Exception e) {}
                refreshWidgets();
            }
        });
        containerSettingsItems.addView(btnAlbum);

        // 🚀 6. New: added dynamic focus-image widget switch!
        final LinearLayout btnFocusImage = createSettingRow("Widget: Dynamic Focus Image", isWidgetFocusImageOn ? t("ON") : t("OFF"));
        btnFocusImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isWidgetFocusImageOn = !isWidgetFocusImageOn;
                ((TextView) btnFocusImage.getChildAt(1)).setText(isWidgetFocusImageOn ? t("ON") : t("OFF"));
                try { prefs.edit().putBoolean("widget_focus_image", isWidgetFocusImageOn).apply(); } catch (Exception e) {}
                refreshWidgets(); // Reflect it on screen immediately when the switch is turned on!
            }
        });
        containerSettingsItems.addView(btnFocusImage);

        // Automatically focuses the second item on entry.
        if (containerSettingsItems.getChildCount() > 0) {
            containerSettingsItems.getChildAt(0).requestFocus();
        }
    }
    // 💡 [Added] Sub-menu screen that combines setting and clearing the wallpaper into one
    private void buildBackgroundSettingsUI() {
        currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        containerSettingsItems.removeAllViews();

        // 1. Set-new-background button
        LinearLayout btnSelectBg = createSettingRow("Select New Background", "〉 ");
        btnSelectBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                isPickingBackground = true;
                currentFolder = new File("/storage/sdcard0");
                changeScreen(STATE_BROWSER);
                Toast.makeText(MainActivity.this, t("Select a JPG/PNG image"), Toast.LENGTH_SHORT).show();
            }
        });
        containerSettingsItems.addView(btnSelectBg);

        // 🚀 2. Force the active theme's own background image (falls back to album blur if the theme has none)
        LinearLayout btnThemeBg = createSettingRow("Apply Theme Background", "〉 ");
        btnThemeBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (currentThemeHasBackground()) {
                    prefs.edit().putString("bg_path", "THEME_DEFAULT").apply();
                    Toast.makeText(MainActivity.this, t("Theme background applied."), Toast.LENGTH_SHORT).show();
                } else {
                    // The theme ships no background image — don't force a blank; drop back to album blur.
                    prefs.edit().remove("bg_path").apply();
                    Toast.makeText(MainActivity.this, t("This theme has no background. Switched to Album Blur."), Toast.LENGTH_SHORT).show();
                }
                updateMainMenuBackground(); // Render the change immediately
            }
        });
        containerSettingsItems.addView(btnThemeBg);

        // 3. Clear-existing-background button (returns to album-art blur mode)
        LinearLayout btnClearBg = createSettingRow("Clear Custom Background", "〉 ");
        btnClearBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                if (prefs.contains("bg_path")) {
                    prefs.edit().remove("bg_path").apply();
                    Toast.makeText(MainActivity.this, t("Custom background cleared."), Toast.LENGTH_SHORT).show();
                    updateMainMenuBackground(); // Instantly revert to the original theme background!
                } else {
                    Toast.makeText(MainActivity.this, t("No custom background set."), Toast.LENGTH_SHORT).show();
                }
            }
        });
        containerSettingsItems.addView(btnClearBg);

        // Automatically focuses the first button on entering the menu!
        if (containerSettingsItems.getChildCount() > 0) {
            containerSettingsItems.getChildAt(0).requestFocus();
        }
    }

    // 💡 [Overhaul complete] Engine that shows download progress (%) and size (MB) in a live popup!
    private void downloadAndInstallApk(final String apkUrl) {
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
    private void buildFileBrowserUI() {
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
                    selectedNavidromeAlbum = null;
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
    private void buildVirtualCategories(final String type) {
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

    private char getInitialChar(String text) {
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
    private void buildCoverFlowUI() {
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
                    } catch (Exception e) {}
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
                    } catch (Exception e) {}
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
    private void scrollCoverFlow(boolean isNext) {
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
                    try { customAnalogClockView.setClockBackgroundColor(android.graphics.Color.parseColor(el.bgColor.trim())); } catch (Exception e) {}
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
                        try { normalBgColor = android.graphics.Color.parseColor(el.bgColor.trim()); } catch (Exception e) {}
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
                            selectedNavidromeAlbum = null;
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
                } catch (Exception e) {}
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
        } catch (Exception e) {}
        return null;
    }

    private String getRepeatModeText(int mode) {
        switch (mode) {
            case 1:
                return "ONE";
            case 2:
                return "ALL";
            default:
                return "OFF";
        }
    }

    private void updatePlayerStatusIndicators() {
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
        } catch (Exception e) {}

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
            } catch (Exception e) {}
        }
        // 🚀 [Bug cause removed] Perfectly removed one unnecessary stray closing brace '}' that was here!
    private void adjustVolume(boolean up) {
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
                try { streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null); } catch (Exception e) {}
                int fmMax = audioManager.getStreamMaxVolume(streamFm);
                int fmVol = (int) (((float)currentVol / maxVol) * fmMax);
                audioManager.setStreamVolume(streamFm, fmVol, 0);
            }
        } catch (Exception e) {}

        showDynamicVolumeOverlay();
    }

    private void showDynamicVolumeOverlay() {
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        layoutVolumeOverlay.setVisibility(View.VISIBLE);
        volumeProgress.setProgress(currentVol);
        volumeHandler.removeCallbacks(hideVolumeTask);
        volumeHandler.postDelayed(hideVolumeTask, 2000);
    }

    private String formatTime(int ms) {
        int s = (ms / 1000) % 60;
        int m = (ms / (1000 * 60)) % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    // Shared by both the screen-off-control path and the normal player path in onKeyDown:
    // first press starts long-press tracking, repeats seek by seekMs at most every 300ms.
    private boolean handleMediaSeekKeyRepeat(KeyEvent event, int seekMs) {
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - lastScreenOnTime < 500);

        if (isWakingUp) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                }
                return true;
            }

            // 🚀 [Screen-off control radio interceptor inserted]
            if (isScreenOffControlEnabled && activePlayer == 1) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                    tuneToNextSavedRadioChannel(true);
                    clickFeedback();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                    tuneToNextSavedRadioChannel(false);
                    clickFeedback();
                    return true;
                }
            }

            if (isScreenOffControlEnabled && currentScreenState == STATE_PLAYER) {
                if (keyCode == 21) {
                    // 🚀 Guard: block volume adjustments for 0.3 seconds (300ms) right after a track skip!
                    if (System.currentTimeMillis() - lastTrackChangeTime > 300) {
                        adjustVolume(false);
                        clickFeedback();
                    }
                    return true;
                }
                if (keyCode == 22) {
                    if (System.currentTimeMillis() - lastTrackChangeTime > 300) {
                        adjustVolume(true);
                        clickFeedback();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                    return handleMediaSeekKeyRepeat(event, -10000);
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                    return handleMediaSeekKeyRepeat(event, 10000);
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {

//                    com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic();
//                    clickFeedback();
                    return true;
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                event.startTracking(); // 🚀 [Core technique] Start watching (tracking) for a long press.
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == 86) {
//            if (event.getRepeatCount() == 0) {
//
//
//                    com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic();
//
//            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            return handleMediaSeekKeyRepeat(event, 10000);
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            return handleMediaSeekKeyRepeat(event, -10000);
        }

        if (currentScreenState == STATE_WIFI_KEYBOARD) {
            if (keyCode == 21) {
                keyboardIndex = (keyboardIndex - 1 + KEYBOARD_CHARS.length) % KEYBOARD_CHARS.length;
                updateKeyboardUI();
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                keyboardIndex = (keyboardIndex + 1) % KEYBOARD_CHARS.length;
                updateKeyboardUI();
                clickFeedback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                changeScreen(STATE_WIFI);
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_PLAYER) {
            if (keyCode == 21) {
                adjustVolume(false);
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                adjustVolume(true);
                clickFeedback();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified] Always go back precisely to the screen we came from, not the browser!
                changeScreen(backTargetForPlayer);
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_BRIGHTNESS) {
            if (keyCode == 21) {
                currentSystemBrightness = Math.max(10, currentSystemBrightness - 15);
                updateBrightness(currentSystemBrightness);
                clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                currentSystemBrightness = Math.min(255, currentSystemBrightness + 15);
                updateBrightness(currentSystemBrightness);
                clickFeedback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified]
                changeScreen(backTargetForUtility);
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_STORAGE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified]
                changeScreen(backTargetForUtility);
                clickFeedback();
                return true;
            }
            return true;
        }

        if (currentScreenState == STATE_NAVIDROME) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19) {
                clickFeedback();
                if (navidromeBrowseDepth == NAV_SONGS) {
                    navidromeBrowseDepth = NAV_ALBUMS;
                    buildNavidromeUI();
                } else if (navidromeBrowseDepth == NAV_ALBUMS) {
                    navidromeBrowseDepth = NAV_ARTISTS;
                    selectedNavidromeArtist = null;
                    buildNavidromeUI();
                } else if (isNavidromeLetterView) {
                    // Letter picker → back to the artist list without refetching
                    tvNavidromePath.setText("NAVIDROME  ▸  Artists");
                    buildNavidromeArtistsUI(lastNavidromeArtists);
                } else {
                    if (navidromeBackTarget == STATE_BROWSER) lastBrowserFocusText = t("Navidrome");
                    changeScreen(navidromeBackTarget);
                }
                return true;
            }
        }

        if (currentScreenState == STATE_WEBSERVER) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                clickFeedback();
                if (isServerRunning) {
                    new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(t("Server is Running"))
                            .setMessage(
                                    t("The Web Server is still active. Do you want to shut it down completely and exit?"))
                            .setPositiveButton(t("Stop Server"), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    toggleWebServer();
                                    changeScreen(backTargetForUtility); // 🚀 Return!
                                }
                            })
                            .setNegativeButton(t("Keep Running"), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    changeScreen(backTargetForUtility); // 🚀 Return!
                                }
                            })
                            .show();
                } else {
                    changeScreen(backTargetForUtility); // 🚀 Return!
                }
                return true;
            }
        }

        if (currentScreenState == STATE_MENU || currentScreenState == STATE_BROWSER
                || currentScreenState == STATE_SETTINGS || currentScreenState == STATE_BLUETOOTH
                || currentScreenState == STATE_WIFI || currentScreenState == STATE_NAVIDROME) {

            // 🚀 [Stock cover-flow wheel control fully overhauled]
            if (currentScreenState == STATE_BROWSER && currentBrowserMode == BROWSER_COVER_FLOW) {
                if (keyCode == 21) { // turning the wheel up (left)
                    scrollCoverFlow(false);
                    clickFeedback();
                    return true;
                }
                if (keyCode == 22) { // turning the wheel down (right)
                    scrollCoverFlow(true);
                    clickFeedback();
                    return true;
                }
            }

            // (existing code unchanged) 🚀 In addition to the existing BACK key, pressing the top button (19) always goes back one step...
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19) {
                clickFeedback();
                if (currentScreenState == STATE_BROWSER) {
                    if (isPickingBackground) {
                        if (currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath())) {
                            isPickingBackground = false;
                            changeScreen(STATE_MENU);
                        } else {
                            currentFolder = currentFolder.getParentFile();
                            buildFileBrowserUI();
                        }
                    } else {
                        // 💡 [Bug fully fixed] Precisely remember (in lastBrowserFocusText) which room we just came out of!
                        if (currentBrowserMode == BROWSER_ROOT) {
                            changeScreen(STATE_MENU);
                        } else if (currentBrowserMode == BROWSER_COVER_FLOW) {
                            // 🚀 [Smart direct-exit sensor installed]
                            // If there's no memory (lastBrowserFocusText) of the previous browser menu, then we came straight in from the main menu!
                            if (lastBrowserFocusText == null || lastBrowserFocusText.trim().isEmpty()) {
                                changeScreen(STATE_MENU); // 🟢 Return directly to the main screen immediately, just like the other shortcuts!
                            } else {
                                // If we came in through the proper path via the library menu, return to the parent menu as usual
                                currentBrowserMode = BROWSER_ROOT;
                                buildFileBrowserUI();
                            }
                        } else if (currentBrowserMode == BROWSER_FOLDER) {
                            // 🚀 [Bug fix] Smartly checks whether we've reached the top-level folder, according to the current mode (music/audiobook).
                            boolean isAtFolderRoot = false;
                            if (isAudiobookLibraryMode) {
                                if (currentFolder.getAbsolutePath().equals(audiobookRootFolder.getAbsolutePath())) {
                                    isAtFolderRoot = true;
                                }
                            } else {
                                if (currentFolder.getAbsolutePath().equals(rootFolder.getAbsolutePath())) {
                                    isAtFolderRoot = true;
                                }
                            }

                            // Pressing back at either mode's top-level folder or the device's overall root folder returns to the library main screen (BROWSER_ROOT)!
                            if (isAtFolderRoot || currentFolder.getAbsolutePath().equals("/storage/sdcard0")) {
                                currentBrowserMode = BROWSER_ROOT;
                                lastBrowserFocusText = t("Folders");
                                buildFileBrowserUI();
                            } else {
                                String exitedName = currentFolder.getName(); // Remember the name of the folder we just left!
                                currentFolder = currentFolder.getParentFile();
                                if (currentFolder == null) {
                                    changeScreen(STATE_MENU);
                                } else {
                                    lastBrowserFocusText = exitedName;
                                    buildFileBrowserUI();
                                }
                            }
                        } else if (currentBrowserMode == BROWSER_VIRTUAL_SONGS) {
                            // 🚀 [Path-recovery 3] If the tag is cover-flow, always send it back to the cover-flow screen.
                            if (virtualQueryType.equals("COVER_FLOW_ALBUM")) {
                                currentBrowserMode = BROWSER_COVER_FLOW;
                                buildCoverFlowUI();
                            } else {
                                // Existing general routing logic
                                currentBrowserMode = virtualQueryType.equals("ALL") ? BROWSER_ROOT
                                        : (virtualQueryType.equals("ARTIST") ? BROWSER_ARTISTS : BROWSER_ALBUMS);
                                if (currentBrowserMode == BROWSER_ROOT) {
                                    lastBrowserFocusText = t("All Songs");
                                    buildFileBrowserUI();
                                } else {
                                    buildVirtualCategories(virtualQueryType);
                                }
                            }
                        } else if (currentBrowserMode == BROWSER_ARTISTS) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText =t("Artists");
                            buildFileBrowserUI();
                        } else if (currentBrowserMode == BROWSER_FAVORITES) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText =t("My Favorites");
                            buildFileBrowserUI();
                        } else if (currentBrowserMode == BROWSER_AUDIOBOOKS) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText = t("All Audiobooks");
                            buildFileBrowserUI();
                        } else if (currentBrowserMode == BROWSER_PLAYLISTS) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText = t("Playlists");
                            buildFileBrowserUI();
                        } else if (currentBrowserMode == BROWSER_M3U_SONGS) {
                            currentBrowserMode = BROWSER_PLAYLISTS;
                            buildM3uPlaylistUI();
                        } else if (currentBrowserMode == BROWSER_ALBUMS) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText = t("Albums");
                            buildFileBrowserUI();
                        }
                        // 🚀 [Add the code below here to open up the exit path!]
                        else if (currentBrowserMode == BROWSER_YEARS) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText = t("Years"); // 💡 Set so the wheel focus automatically locks onto the 'Years' button when leaving!
                            buildFileBrowserUI();
                        } else if (currentBrowserMode == BROWSER_GENRES) {
                            currentBrowserMode = BROWSER_ROOT;
                            lastBrowserFocusText = t("Genres"); // 💡 Set so the wheel focus automatically locks onto the 'Genres' button when leaving!
                            buildFileBrowserUI();
                        }
                    }
                } else if (currentScreenState == STATE_BLUETOOTH || currentScreenState == STATE_WIFI) {
                    changeScreen(backTargetForUtility);
                } else if (currentScreenState == STATE_SETTINGS) {

                    // 🚀 [Step 1] If in the radio settings sub-page mode, first escape to the radio main player mode!
                    if (isRadioUIShowing && isRadioSettingsMode) {
                        isRadioSettingsMode = false;
                        isRadioAdjustingFreq = false;
                        buildRadioUI();
                        clickFeedback();
                        return true;
                    }

                    // 🚀 [Bug completely fixed] Pressing back on the radio main player screen instantly jumps back to the home (main) screen!
                    if (isRadioUIShowing) {
                        isRadioUIShowing = false;
                        isRadioSettingsMode = false;
                        isRadioAdjustingFreq = false;
                        applyThemeToMainMenu(); // 🚀 Added! Refresh when returning to main from radio
                        changeScreen(STATE_MENU);
                        clickFeedback();
                        return true;
                    }

                    isRadioUIShowing = false;

                    // 🚀 [Routing cleanup fully restored] Figures out the depth and returns to the correct parent menu accordingly!
                    if (currentSettingsDepth == 0) {
                        applyThemeToMainMenu(); // 🚀 Added! Fully refresh the main screen when fully exiting the settings window!
                        changeScreen(STATE_MENU);
                    } else if (currentSettingsDepth == 1) {
                        buildSettingsUI(); // If it's a group screen (depth 1), go back one step to the group-selector root!
                    } else if (currentSettingsDepth == 2) {
                        // A leaf sub-menu directly under a group (depth 2) — go back one step to that group!
                        routeBackToSettingsGroup();
                    } else if (currentSettingsDepth == 3) {
                        // Handling for exiting an even deeper window (depth 3), like EQ presets or the date/time picker
                        if (settingsSubMode == 2 || settingsSubMode == 3) {
                            buildEqualizerSettingsUI();
                        } else {
                            routeBackToSettingsGroup();
                        }
                    }
                    clickFeedback();
                    return true;
                }
                return true;
            }
            // 🚀 [Overwrite from here!] While the ultra-fast ListView is active, hand the wheel signal off to the system's native smooth-scroll engine!
            if (currentScreenState == STATE_BROWSER && listVirtualSongs != null
                    && listVirtualSongs.getVisibility() == View.VISIBLE) {

                long now = System.currentTimeMillis();
                if (now - lastWheelTime < 40 && wheelFastCount < 2) {
                    lastWheelTime = now;
                    return true;
                }
                boolean isFastScroll = false;

                // 💡 [Automatic engine] If the wheel spins 3+ clicks in a row within 0.05 seconds (50ms), trigger 'fast-jump mode'!
                if (now - lastWheelTime < 50) {
                    wheelFastCount++;
                    if (wheelFastCount >= 3)
                        isFastScroll = true;
                } else {
                    wheelFastCount = 0; // Reset instantly if turned slowly
                }
                lastWheelTime = now;

                if (isFastScroll && !currentScrollIndexList.isEmpty()) {
                    // 🚀🚀 [Fast-jump mode] Scroll in big chunks by alphabet (first letter)!
                    int currentPos = listVirtualSongs.getSelectedItemPosition();
                    if (currentPos < 0)
                        currentPos = 0;
                    char currentChar = getInitialChar(currentScrollIndexList.get(currentPos));
                    int targetPos = currentPos;

                    if (keyCode == 22) { // wheel flicked down (find the next letter)
                        for (int i = currentPos + 1; i < currentScrollIndexList.size(); i++) {
                            if (getInitialChar(currentScrollIndexList.get(i)) != currentChar) {
                                targetPos = i;
                                break;
                            }
                        }
                    } else if (keyCode == 21) { // wheel flicked up (find the start of the previous letter)
                        char targetChar = currentChar;
                        boolean foundPrevChar = false;
                        for (int i = currentPos - 1; i >= 0; i--) {
                            char c = getInitialChar(currentScrollIndexList.get(i));
                            if (!foundPrevChar && c != currentChar) {
                                foundPrevChar = true;
                                targetChar = c;
                            }
                            if (foundPrevChar && c != targetChar) {
                                targetPos = i + 1;
                                break;
                            }
                            if (i == 0)
                                targetPos = 0;
                        }
                    }
                    listVirtualSongs.setSelection(targetPos);
                    clickFeedback();
                    return true;
                } else {
                    // 🐢🐢 [Normal-drive mode] Move slowly and precisely one track at a time, as usual!
                    if (keyCode == 21) {
                        int currentPos = listVirtualSongs.getSelectedItemPosition();
                        if (currentPos <= 0) {
                            // 🚀 [Loop-scroll condition control] Only jump instantly to the very last track when looping is enabled.
                            if (isLoopScrollOn) {
                                final int lastPos = listVirtualSongs.getCount() - 1;
                                listVirtualSongs.setSelection(lastPos);
                                listVirtualSongs.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        int visiblePos = lastPos - listVirtualSongs.getFirstVisiblePosition();
                                        if (visiblePos >= 0 && visiblePos < listVirtualSongs.getChildCount()) {
                                            listVirtualSongs.getChildAt(visiblePos).requestFocus();
                                        }
                                    }
                                });
                            }
                        } else {
                            listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                            listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
                        }
                        clickFeedback();
                        return true;
                    }
                    if (keyCode == 22) {
                        int currentPos = listVirtualSongs.getSelectedItemPosition();
                        if (currentPos == listVirtualSongs.getCount() - 1) {
                            // 🚀 [Loop-scroll condition control] Only jump back instantly to the very first track when looping is enabled.
                            if (isLoopScrollOn) {
                                listVirtualSongs.setSelection(0);
                                listVirtualSongs.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (listVirtualSongs.getChildCount() > 0)
                                            listVirtualSongs.getChildAt(0).requestFocus();
                                    }
                                });
                            }
                        } else {
                            listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                            listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                        }
                        clickFeedback();
                        return true;
                    }
                }
            }
            View c = getCurrentFocus();
            if (c != null) {
                if (keyCode == 21) { // wheel turned up (UP)

                    // 🚀 [Radio wheel control] Flicker fully eliminated version
                    if (currentScreenState == STATE_SETTINGS && isRadioUIShowing) {
                        if (!isRadioSettingsMode) {
                            adjustVolume(false);
                            return true;
                        } else if (isRadioAdjustingFreq) {
                            com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
                            float newFreq = fm.currentFreq - 0.1f;
                            if (newFreq < 87.5f) newFreq = 108.0f;
                            if (fm.isPowerUp) fm.tune(newFreq); else fm.currentFreq = newFreq;
                            showRadioFreqPopup(newFreq);
                            buildRadioUI();
                            return true;
                        }
                    }

                    // 🚀 [Main-menu control fully conquered] On the main screen, always force focus to move strictly through the index order we assembled!
                    if (currentScreenState == STATE_MENU) {
                        int targetId = c.getNextFocusUpId();
                        if (targetId != View.NO_ID) {
                            View target = findViewById(targetId);
                            if (target != null) {
                                target.requestFocus();
                                clickFeedback();
                                return true;
                            }
                        }
                    } else {
                        android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                        if (parent instanceof LinearLayout) {
                            int index = parent.indexOfChild(c);
                            boolean moved = false;
                            for (int i = index - 1; i >= 0; i--) {
                                View n = parent.getChildAt(i);
                                if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                    n.requestFocus();
                                    moved = true;
                                    break;
                                }
                            }
                            if (!moved && isLoopScrollOn) {
                                for (int i = parent.getChildCount() - 1; i > index; i--) {
                                    View n = parent.getChildAt(i);
                                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                        n.requestFocus();
                                        break;
                                    }
                                }
                            }
                        } else {
                            int targetId = c.getNextFocusUpId();
                            if (targetId != View.NO_ID) {
                                View target = findViewById(targetId);
                                if (target != null) {
                                    target.requestFocus();
                                    clickFeedback();
                                    return true;
                                }
                            }
                            View n = c.focusSearch(View.FOCUS_UP);
                            if (n != null) n.requestFocus();
                        }
                    }
                    clickFeedback();
                    return true;
                }
                if (keyCode == 22) { // wheel turned down (DOWN)

                    // 🚀 [Radio wheel control] Flicker fully eliminated version
                    if (currentScreenState == STATE_SETTINGS && isRadioUIShowing) {
                        if (!isRadioSettingsMode) {
                            adjustVolume(true);
                            return true;
                        } else if (isRadioAdjustingFreq) {
                            com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);
                            float newFreq = fm.currentFreq + 0.1f;
                            if (newFreq > 108.0f) newFreq = 87.5f;
                            if (fm.isPowerUp) fm.tune(newFreq); else fm.currentFreq = newFreq;
                            showRadioFreqPopup(newFreq);
                            buildRadioUI();
                            return true;
                        }
                    }

                    // 🚀 [Main-menu control fully conquered] On the main screen, always force focus to move strictly through the index order we assembled!
                    if (currentScreenState == STATE_MENU) {
                        int targetId = c.getNextFocusDownId();
                        if (targetId != View.NO_ID) {
                            View target = findViewById(targetId);
                            if (target != null) {
                                target.requestFocus();
                                clickFeedback();
                                return true;
                            }
                        }
                    } else {
                        android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                        if (parent instanceof LinearLayout) {
                            int index = parent.indexOfChild(c);
                            boolean moved = false;
                            for (int i = index + 1; i < parent.getChildCount(); i++) {
                                View n = parent.getChildAt(i);
                                if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                    n.requestFocus();
                                    moved = true;
                                    break;
                                }
                            }
                            if (!moved && isLoopScrollOn) {
                                for (int i = 0; i < index; i++) {
                                    View n = parent.getChildAt(i);
                                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                        n.requestFocus();
                                        break;
                                    }
                                }
                            }
                        } else {
                            int targetId = c.getNextFocusDownId();
                            if (targetId != View.NO_ID) {
                                View target = findViewById(targetId);
                                if (target != null) {
                                    target.requestFocus();
                                    clickFeedback();
                                    return true;
                                }
                            }
                            View n = c.focusSearch(View.FOCUS_DOWN);
                            if (n != null) n.requestFocus();
                        }
                    }
                    clickFeedback();
                    return true;
                }
            } else {
                // 🚀 [Focus-jump bug fully resolved] Right after first entering the screen, focus is temporarily absent (null),
                // and this completely blocks the system from ambiguously warping to some bottom button the moment the user first clicks the wheel.
                if (keyCode == 21 || keyCode == 22) {
                    android.view.View firstBtn = findViewById(10000); // Target the unique ID of button 0 (Now Playing)
                    if (firstBtn != null) {
                        firstBtn.requestFocus(); // Force it back to button 0!
                        clickFeedback();
                        return true; // 💡 Consume the event here to stop it from jumping to the wrong button.
                    }
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isWheelLockActive) {
            // 🚀 [Wheel lock] Nothing gets through except turning the wheel (21/22) —
            // whatever button gets pressed and however it happens inside a pocket, it's all absorbed here.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int keyCode = event.getKeyCode();
                if (keyCode == 21 || keyCode == 22) {
                    // Reversing direction invalidates prior progress — restart the count in the new direction
                    if (lastWheelDirection != 0 && lastWheelDirection != keyCode) {
                        wheelUnlockProgress = 0;
                    }
                    lastWheelDirection = keyCode;
                    wheelUnlockProgress++;
                    updateWheelLockProgress();

                    // While still turning, push the reset timer back; if the wheel stops before the
                    // unlock completes (within the timeout), progress resets.
                    wheelLockHandler.removeCallbacks(wheelLockReleaseResetRunnable);
                    if (wheelUnlockProgress >= WHEEL_UNLOCK_THRESHOLD) {
                        deactivateWheelLock();
                        clickFeedback();
                    } else {
                        wheelLockHandler.postDelayed(wheelLockReleaseResetRunnable, WHEEL_UNLOCK_RELEASE_TIMEOUT_MS);
                    }
                }
            }
            return true;
        }
        if (isFakeScreenOff) {
            int keyCode = event.getKeyCode();

            // 💡 1. The moment the button is pressed (ACTION_DOWN)
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Silently isolate and block the leftover repeated signal that occurs when the hand hasn't lifted yet after a long press
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.getRepeatCount() > 0) {
                    return true;
                }

                // 🚀 [Screen-off control integration] While in virtual-blackout state, pressing left/right changes the frequency without waking the screen, keeping it off!
                if (isScreenOffControlEnabled && activePlayer == 1) {
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                        tuneToNextSavedRadioChannel(true);
                        clickFeedback();
                        return true; // 💡 Break the signal here so it doesn't fall through to the screen-wake routine.
                    }
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                        tuneToNextSavedRadioChannel(false);
                        clickFeedback();
                        return true;
                    }
                }

                // If any other key is pressed, kick off the screen-wake steering!
                isFakeScreenOff = false;
                autoManageWifiPower(false); // 🚀 [Exiting power-saving mode]
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Instantly restore the hardware backlight brightness
                        try {
                            WindowManager.LayoutParams lp = getWindow().getAttributes();
                            lp.screenBrightness = currentSystemBrightness / 255.0f;
                            getWindow().setAttributes(lp);
                        } catch (Exception e) {}

                        // Run a smooth cinematic fade-in animation
                        // (vsync-synced property animator instead of a manual 25ms Handler loop)
                        layoutLoadingOverlay.animate().cancel();
                        layoutLoadingOverlay.animate()
                                .alpha(0.0f)
                                .setDuration(325)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing() || isDestroyed()) return;
                                        layoutLoadingOverlay.setVisibility(View.GONE);
                                        layoutLoadingOverlay.setBackgroundColor(0xDD000000); // Reset to the semi-transparent loading-screen color
                                        if (pbLoadingProgress != null) pbLoadingProgress.setVisibility(View.VISIBLE);
                                        if (currentScreenState == STATE_SETTINGS) buildRadioUI();
                                    }
                                })
                                .start();
                    }
                });
                clickFeedback();
            }

            // 🚀 [Core technique] All key actions (press, release — everything) that occur in the blackout state
            // are made to completely vanish here, never reaching lower views (like the radio option buttons)!
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
//        if (ignoreNextKeyUp) {
//            ignoreNextKeyUp = false; // Release the guard
//            return true; // 💡 Consume the event here so it doesn't flow down to handleCenterShortClick() etc. below!
//        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - lastScreenOnTime < 500);

        if (isWakingUp) {
            return true;
        }

        // 💡 [Key blocking zone] On 'release' of a wheel action (21, 22) or back (BACK)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 21 || keyCode == 22) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 🚀 [Guard] If a long press (screen-off or playlist popup) has already been handled, skip the short-click routine
            if (isLongPressConsumed) {
                isLongPressConsumed = false;
                return true;
            }

            if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                // 🚀 [Smart short-click branching] On the player screen, the long-press was given to screen-off,
                // so favorite (♥) toggling is elegantly rescued via a 'double-click (tap-tap!)' engine.
                if (currentScreenState == STATE_PLAYER) {
                    long now = System.currentTimeMillis();
                    if (now - lastCenterUpTime < 300) {
                        doubleClickHandler.removeCallbacks(singleClickRunnable);
                        lastCenterUpTime = 0; // Reset the timer
                        clickFeedback();
                        toggleFavorite(); // Double-tap to add/remove a favorite!
                    } else {
                        lastCenterUpTime = now;
                        doubleClickHandler.postDelayed(singleClickRunnable, 300);
                    }
                } else {
                    // 🚀 On every other screen (main menu selection, settings logic, library lists, etc.),
                    // there's no 0.3-second wait — a full one-touch, lightning-fast click fires instantly, removing any lag.
                    try { handleCenterShortClick(); } catch (Exception e) {}
                }
            }
            return true;
        }
        // 🚀 [Bug fully fixed] Smart, fully cleaned-up control scheme for the bottom hardware play/stop button!
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86 || keyCode == 126 || keyCode == 127) {
            if (event.getRepeatCount() == 0) {
                com.themoon.y1.managers.FmRadioManager fm = com.themoon.y1.managers.FmRadioManager.getInstance(this);

                // 💡 [Top-priority rule] If the user is currently looking at the 'music player screen (STATE_PLAYER)',
                // open the gate so the "always play/pause the music player" command works regardless of the radio's state!
                if (currentScreenState == STATE_PLAYER) {
                    if (fm.isPowerUp) {
                        fm.powerDown(); // If the radio was making sound, silently turn it off first.
                    }
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic();
                    activePlayer = 0; // Force control back to the music player!
                }
                // 💡 On any other general screen (main menu, settings, etc.), follow the originally designed activePlayer rule.
                else if (activePlayer == 1) {
                    if (fm.isPowerUp) {
//                        fm.powerDown();
                    } else {
                        // 🚀 [Error fix] Changed so playOrPauseMusic() only runs when music is actually playing!
                        com.themoon.y1.managers.AudioPlayerManager amInstance = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                        if (amInstance.isPlaying()) {
                            amInstance.playOrPauseMusic();
                        }
                        // Give the music player a moment to actually pause before the FM chip claims the
                        // audio session; posted with a delay instead of Thread.sleep so the UI thread isn't blocked.
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            fm.powerUpAsync(fm.currentFreq, success -> {
                                if (!success) {
                                    android.widget.Toast.makeText(this, "Radio Error: " + fm.lastError, android.widget.Toast.LENGTH_SHORT).show();
                                }
                                updateGlobalStatusPlayIcon();
                                if (currentScreenState == STATE_SETTINGS) buildRadioUI();
                            });
                        }, 50);
                        clickFeedback();
                        return true;
                    }
                    if (currentScreenState == STATE_SETTINGS) buildRadioUI();
                } else {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic();
                }

                updateGlobalStatusPlayIcon(); // Sync the top status bar's play/stop image
                clickFeedback();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            if (!isSeekPerformed) {
                if (activePlayer == 1) {
                    tuneToNextSavedRadioChannel(true); // 🚀 Just cleanly call the engine!
                } else {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().nextTrack();
                }
                clickFeedback();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            if (!isSeekPerformed) {
                if (activePlayer == 1) {
                    tuneToNextSavedRadioChannel(false); // 🚀 Just cleanly call the engine!
                } else {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().prevTrack();
                }
                clickFeedback();
            }
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            clickFeedback();
            isLongPressConsumed = true; // Blocks the short click from firing again on release

            // 🚀 [Branch 1] The main screen, player screen, settings screen, and other system windows always trigger screen-off!
            if (currentScreenState == STATE_MENU || currentScreenState == STATE_PLAYER || currentScreenState == STATE_SETTINGS
                    || currentScreenState == STATE_BLUETOOTH || currentScreenState == STATE_WIFI || currentScreenState == STATE_BRIGHTNESS
                    || currentScreenState == STATE_STORAGE || currentScreenState == STATE_WEBSERVER) {

                turnOffScreen();
                return true;
            }
            // 🚀 [Branch 2] Weighing up the exception handling when entering the library (Browser) screen
            else if (currentScreenState == STATE_BROWSER) {
                // Check whether the current browser is showing a screen that lists pure tracks/files
                boolean isFileVisible = (currentBrowserMode == BROWSER_FOLDER
                        || currentBrowserMode == BROWSER_VIRTUAL_SONGS
                        || currentBrowserMode == BROWSER_FAVORITES
                        || currentBrowserMode == BROWSER_M3U_SONGS
                        || currentBrowserMode == BROWSER_AUDIOBOOKS);

                if (isFileVisible) {
                    // 💡 [Per request] When files are visible, exclude it from the screen-off targets and keep the existing "playlist popup (long press)" behavior!
                    View c = getCurrentFocus();
                    if (c != null) {
                        c.performLongClick();
                    }
                } else {
                    // 💡 On a root menu or artist/album category window with no files visible, conveniently let screen-off work!
                    turnOffScreen();
                }
                return true;
            }
        }
        return super.onKeyLongPress(keyCode, event);
    }
    // AapService only (re)starts from the A2DP CONNECTION_STATE_CHANGED broadcast, which fires on
    // a state transition. If Android kills this process while AirPods are already connected, the
    // process comes back with no AAP session and no new broadcast to trigger one (the state never
    // changed) -- ear-detection stays dead until the user manually toggles Bluetooth. Re-checking
    // on resume (and right after the A2DP proxy first binds) self-heals that without needing a
    // manual reconnect.
    private void resyncAapWithConnectedDevice() {
        if (globalA2dp == null) return;
        try {
            java.util.List<BluetoothDevice> connected = globalA2dp.getConnectedDevices();
            if (!connected.isEmpty()) {
                BluetoothDevice device = connected.get(0);
                targetDeviceForAudio = device;
                AapService.deviceConnected(this, device);
            }
        } catch (Exception e) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resyncAapWithConnectedDevice();
    }

    // ⭕ [Overwrite with the code below]
    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacks(clockTask);
        progressHandler.removeCallbacks(updateProgressTask);
        volumeHandler.removeCallbacks(hideVolumeTask);
        wheelLockHandler.removeCallbacks(wheelLockReleaseResetRunnable);
        qualityInfoHandler.removeCallbacks(hideQualityInfoTask);
        doubleClickHandler.removeCallbacks(singleClickRunnable);
        radioFreqHandler.removeCallbacks(hideRadioFreqTask);
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
            try { currentFileInputStream.close(); } catch (Exception e) {}
        }
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }

        unregisterReceiver(systemStatusReceiver);

        // Deliberately NOT nulling `instance` here: this is a single-Activity home launcher
        // where MainActivity.instance is relied on everywhere (managers, MediaBtnReceiver)
        // as the de facto app-instance handle. MediaBtnReceiver in particular must still be
        // able to drive playback (e.g. AirPods play/pause) via this reference for the window
        // between the Activity being torn down (screen off, memory trim) and recreated
        // (screen unlock) — nulling it here silently breaks background media-button control.
    }
    // 💡 Function that directly blocks/allows the Android system's own hardware click-sound stream
    private void applySoundSetting() {
        try {
            if (audioManager != null) {
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, !isSoundEffectEnabled);
            }
            // 💡 Key: overwrites the system setting that forcibly blocks the device's touch-panel hardware click sound!
            Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED,
                    isSoundEffectEnabled ? 1 : 0);
        } catch (Exception e) {
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
    private void applySpeakerSetting() {
        boolean externalAudioConnected = false;
        try {
            externalAudioConnected = globalA2dp != null && !globalA2dp.getConnectedDevices().isEmpty();
        } catch (Exception e) {
        }
        applySpeakerSetting(externalAudioConnected);
    }

    private void applySpeakerSetting(boolean externalAudioConnected) {
        boolean shouldMute = isSpeakerDisabled && !externalAudioConnected;
        com.themoon.y1.managers.AudioPlayerManager.getInstance().setSpeakerMuted(shouldMute);
    }

    // 💡 Updates the Bluetooth status-bar icon color — as many phones commonly do, we distinguish between simply being on (white)
    // and actually being connected to a device (earphones, etc.) (blue).
    private static final int BT_ICON_COLOR_ON = 0xFFFFFFFF;
    private static final int BT_ICON_COLOR_CONNECTED = 0xFF2FA8FF;

    private void updateBluetoothStatusIcon() {
        if (ivStatusBluetooth == null) return;
        boolean connected = false;
        try {
            connected = globalA2dp != null && !globalA2dp.getConnectedDevices().isEmpty();
        } catch (Exception e) {
        }
        ivStatusBluetooth.setColorFilter(connected ? BT_ICON_COLOR_CONNECTED : BT_ICON_COLOR_ON);
    }

    // 💡 High-quality Gaussian blur function using Android's hardware acceleration (RenderScript)!
    public Bitmap applyGaussianBlur(Bitmap original) {
        if (original == null)
            return null;
        try {
            Bitmap output = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
            RenderScript rs = RenderScript.create(this);
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            Allocation inAlloc = Allocation.createFromBitmap(rs, original, Allocation.MipmapControl.MIPMAP_NONE,
                    Allocation.USAGE_SCRIPT);
            Allocation outAlloc = Allocation.createFromBitmap(rs, output);

            script.setRadius(25f); // 💡 Blur intensity setting (range 0.0 ~ 25.0, 25 is max)
            script.setInput(inAlloc);
            script.forEach(outAlloc);
            outAlloc.copyTo(output);
            rs.destroy();

            return output;
        } catch (Exception e) {
            return original;
        }
    }

    private interface BlurResultCallback {
        void onBlurred(Bitmap blurred, Bitmap source);
    }

    // The RenderScript blur pass itself (not just decode) is real work on this hardware — running
    // it synchronously on every track change causes a visible hitch on skip/next/prev. Do the blur
    // off the main thread and hand the result back via callback; the caller keeps the immediate
    // (unblurred) foreground art update synchronous since that part is cheap.
    private void applyGaussianBlurAsync(final Bitmap source, final BlurResultCallback callback) {
        if (source == null) {
            callback.onBlurred(null, null);
            return;
        }
        new Thread(() -> {
            final Bitmap blurred = applyGaussianBlur(source);
            runOnUiThread(() -> callback.onBlurred(blurred, source));
        }).start();
    }

    // 💡 1. Main date/time settings screen (time-error and focus-lock bugs fully fixed version)
    private void buildDateTimeUI() {
        currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        containerSettingsItems.removeAllViews();
        // 🚀 [Fix] Wrap the 12-hour/24-hour text with t() so it also goes through the translator!
        String formatRightText = is24HourFormat ? t("24 Hour") : t("12 Hour");
        final LinearLayout rowFormat = createSettingRow("Time Format", formatRightText);
        rowFormat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                is24HourFormat = !is24HourFormat; // toggle
                prefs.edit().putBoolean("is_24h_format", is24HourFormat).apply(); // save permanently

                // 💡 Force-nudge the runtime thread once so the clock hands update immediately
                clockHandler.removeCallbacks(clockTask);
                clockHandler.post(clockTask);

                buildDateTimeUI(); // Refresh the settings screen
            }
        });
        containerSettingsItems.addView(rowFormat);
        final LinearLayout rowYear = createSettingRow("Year", String.valueOf(dtYear));
        rowYear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Year", 2020, 2035, dtYear);
            }
        });
        containerSettingsItems.addView(rowYear);

        final LinearLayout rowMonth = createSettingRow("Month", String.format(java.util.Locale.US, "%02d", dtMonth));
        rowMonth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Month", 1, 12, dtMonth);
            }
        });
        containerSettingsItems.addView(rowMonth);

        final LinearLayout rowDay = createSettingRow("Day", String.format(java.util.Locale.US, "%02d", dtDay));
        rowDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Day", 1, 31, dtDay);
            }
        });
        containerSettingsItems.addView(rowDay);

        final LinearLayout rowHour = createSettingRow("Hour (24H)", String.format(java.util.Locale.US, "%02d", dtHour));
        rowHour.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Hour", 0, 23, dtHour);
            }
        });
        containerSettingsItems.addView(rowHour);

        final LinearLayout rowMinute = createSettingRow("Minute", String.format(java.util.Locale.US, "%02d", dtMinute));
        rowMinute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildDateTimeSelectorUI("Minute", 0, 59, dtMinute);
            }
        });
        containerSettingsItems.addView(rowMinute);

        createCategoryHeader("━━━━━━━━━━━━━━");

        final Button btnApply = createListButton("✅ " + t("APPLY DATE & TIME"));
        btnApply.setTextColor(0xFFFFFFFF);
        btnApply.setTypeface(null, android.graphics.Typeface.BOLD);
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                try {
                    // 🚀 [Time error permanently fixed] Sets the time without touching the device's existing timezone.
                    // Android's built-in `date` command parses completely differently depending on the shell built into the device (Toolbox vs Toybox),
                    // and there's a serious bug where an incorrect format always resets the clock to 1970 or 1980.
                    // To fully prevent this, we apply one format, then check that the year/month/day were actually applied correctly, and if it failed, try the next format —
                    // we write a self-verifying script that follows this approach!

                    String cmd = "settings put global auto_time 0; settings put system auto_time 0; ";

                    // Build the target date as YYYYMMDD (for verification)
                    String targetYMD = String.format(java.util.Locale.US, "%04d%02d%02d", dtYear, dtMonth, dtDay);

                    // Format 1: legacy-Android (Toolbox) only format -> YYYYMMDD.HHmmss
                    String dateToolbox = String.format(java.util.Locale.US, "%04d%02d%02d.%02d%02d%02d", dtYear,
                            dtMonth, dtDay, dtHour, dtMinute, 0);
                    // Format 2: POSIX international standard format (Toybox/Busybox compatible) -> MMDDhhmmYYYY.ss
                    String datePosix = String.format(java.util.Locale.US, "%02d%02d%02d%02d%04d.00", dtMonth, dtDay,
                            dtHour, dtMinute, dtYear);
                    // Format 3: modern-Android (Toybox) string format -> YYYY-MM-DD HH:MM:SS
                    String dateString = String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", dtYear,
                            dtMonth, dtDay, dtHour, dtMinute, 0);

                    // 💡 Self-verifying shell script:
                    // 1. Try the Toolbox format first. (Toybox devices will error out or scramble the time)
                    // 2. Immediately check the applied time, and if it differs from the target date (e.g. reset to 1970), try the POSIX format.
                    // 3. If that still doesn't work, try the string format.
                    String executeCmd = cmd +
                            "date -s " + dateToolbox + "; " +
                            "if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then " +
                            "  date " + datePosix + "; " +
                            "  if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then " +
                            "    date -s \"" + dateString + "\"; " +
                            "  fi; " +
                            "fi; " +
                            "hwclock -w; sync";

                    Process proc = Runtime.getRuntime().exec(new String[] { "su", "-c", executeCmd });
                    proc.waitFor(); // 💡 Wait briefly until the time is fully applied to the system.

                    // Force-broadcast system-wide that the time has changed, to sync the main page clock and system apps.
                    sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));

                    Toast.makeText(MainActivity.this, "Time applied successfully!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Failed: Root access required.", Toast.LENGTH_SHORT).show();
                }

                // 🚀 [Focus bug fix 1] Force-purge the corrupted index to the 'Date & Time Settings' menu position (item #14)
                lastSettingsFocusIndex = 14;
                buildSettingsUI();

                // 🚀 [Focus bug fix 2] Add a tiny 50ms safety delay so focus locks in reliably once the UI has fully laid out.
                containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (containerSettingsItems != null && containerSettingsItems.getChildCount() > 0) {
                            containerSettingsItems.getChildAt(containerSettingsItems.getChildCount() - 1)
                                    .requestFocus();
                        }
                    }
                }, 50);
            }
        });
        containerSettingsItems.addView(btnApply);

        if (containerSettingsItems.getChildCount() > 0)
            containerSettingsItems.getChildAt(0).requestFocus();
    }

    // 💡 2. Vertical list screen for selecting numbers (year/month/day/hour/minute)
    private void buildDateTimeSelectorUI(final String type, int min, int max, int currentValue) {
        currentSettingsDepth = 3; // 🚀 Main settings is depth 0
        containerSettingsItems.removeAllViews();

        Button focusBtn = null;
        for (int i = min; i <= max; i++) {
            final int val = i;
            String displayVal = (type.equals("Minute") || type.equals("Hour") || type.equals("Month")
                    || type.equals("Day")) ? String.format(java.util.Locale.US, "%02d", val) : String.valueOf(val);
            Button btn = createListButton(displayVal);
            btn.setGravity(android.view.Gravity.CENTER); // Nicely center-aligned!

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    if (type.equals("Year"))
                        dtYear = val;
                    else if (type.equals("Month"))
                        dtMonth = val;
                    else if (type.equals("Day"))
                        dtDay = val;
                    else if (type.equals("Hour"))
                        dtHour = val;
                    else if (type.equals("Minute"))
                        dtMinute = val;
                    buildDateTimeUI(); // Automatically returns to the previous screen once selected!
                }
            });
            containerSettingsItems.addView(btn);
            if (val == currentValue)
                focusBtn = btn;
        }

        // Auto-move focus to the currently configured time
        if (focusBtn != null)
            focusBtn.requestFocus();
        else if (containerSettingsItems.getChildCount() > 0)
            containerSettingsItems.getChildAt(0).requestFocus();
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
    private static class TLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
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
        } catch (Exception e) {}

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
    private void buildEqualizerSettingsUI() {
        currentSettingsDepth = 2;
        settingsSubMode = 2; // Activate the EQ sub-mode
        com.themoon.y1.managers.AudioEffectManager.getInstance().loadAndSyncExternalEqProfiles();
        com.themoon.y1.managers.AudioEffectManager.getInstance().ensureAudioEffectsReady();
        containerSettingsItems.removeAllViews();

        // 🚀 2. Show EQ on the sub-settings screen
        String activeName = "Normal";
        if (currentEqProfile.startsWith("preset_")) {
            int pIdx = Integer.parseInt(currentEqProfile.replace("preset_", ""));
            if (pIdx < eqPresetNames.size()) activeName = t(eqPresetNames.get(pIdx)); // 🚀 Translation applied
        } else {
            activeName = currentEqProfile.replace("custom_", ""); // 🚀 Translate the label
        }
        LinearLayout rowSelect = createSettingRow("EQ Profile / Preset", activeName + " 〉");

        // 🚀 [Bug fix] Attach the click event to the button we built and slot it onto the screen!
        rowSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickFeedback();
                buildEqProfileSelectorUI(); // 🚀 Open the hidden preset-selector list screen!
            }
        });
        containerSettingsItems.addView(rowSelect);

        // 2. 4-band bass booster
        final String[] steps = {"OFF", "Weak", "Normal", "Strong"};
        final LinearLayout rowBass = createSettingRow("Bass Boost", t(steps[currentBassBoostStep]));
        rowBass.setOnClickListener(v -> {
            clickFeedback();
            currentBassBoostStep = (currentBassBoostStep + 1) % 4;
            ((TextView) rowBass.getChildAt(1)).setText(t(steps[currentBassBoostStep])); // 🚀 t() applied!
            com.themoon.y1.managers.AudioEffectManager.getInstance().applyAudioEffects();
            prefs.edit().putInt("bass_boost_step", currentBassBoostStep).apply();
        });
        containerSettingsItems.addView(rowBass);

        // 3. 4-band virtualizer (spatial effect)
        final LinearLayout rowVirt = createSettingRow("Virtualizer", t(steps[currentVirtualizerStep]));
        rowVirt.setOnClickListener(v -> {
            clickFeedback();
            currentVirtualizerStep = (currentVirtualizerStep + 1) % 4;
            ((TextView) rowVirt.getChildAt(1)).setText(t(steps[currentVirtualizerStep])); // 🚀 t() applied!
            com.themoon.y1.managers.AudioEffectManager.getInstance().applyAudioEffects();
            prefs.edit().putInt("virtualizer_step", currentVirtualizerStep).apply();
        });
        containerSettingsItems.addView(rowVirt);

        // 4. Custom vault management panel
        createCategoryHeader("━ "+t("PROFILE MANAGEMENT")+" ━");
        if (currentEqProfile.startsWith("custom_")) {
            android.view.View btnSave = createListButtonWithIcon("\uE161", t("Save Current Configuration"));
            btnSave.setOnClickListener(v -> {
                clickFeedback();
                com.themoon.y1.managers.AudioEffectManager.getInstance().saveCustomEqProfile(currentEqProfile.replace("custom_", ""));
                Toast.makeText(this, t("Configuration Saved!"), Toast.LENGTH_SHORT).show();
            });
            containerSettingsItems.addView(btnSave);

            android.view.View btnDel = createListButtonWithIcon("\uE872", t("Delete Current Profile"), 0xFFFF4444);

            btnDel.setOnClickListener(v -> {
                clickFeedback();
                com.themoon.y1.managers.AudioEffectManager.getInstance().deleteCustomEqProfile(currentEqProfile.replace("custom_", ""));
                currentEqProfile = "preset_0";
                com.themoon.y1.managers.AudioEffectManager.getInstance().applyEqProfile();
                buildEqualizerSettingsUI();
            });
            containerSettingsItems.addView(btnDel);
        }

        android.view.View btnCreate = createListButtonWithIcon("\uE145", t("Create New Profile"));
        btnCreate.setOnClickListener(v -> {
            clickFeedback();
            String listStr = prefs.getString("custom_eq_list", "");
            int count = 1;
            while (listStr.contains("User EQ " + count)) count++;
            String newName = "User EQ " + count;
            if (!listStr.isEmpty()) listStr += ",";
            listStr += newName;
            prefs.edit().putString("custom_eq_list", listStr).apply();

            currentEqProfile = "custom_" + newName;

            // 🚀 [Bug fix] So the cached values from previous edits don't carry over when creating a new profile,
            // force-format every frequency band to a clean 0 dB (flat default) state!
            short bands = (equalizer != null) ? equalizer.getNumberOfBands() : 5;
            for (short i = 0; i < bands; i++) {
                customBandLevels[i] = 0;
                if (equalizer != null) {
                    try { equalizer.setBandLevel(i, (short) 0); } catch (Exception e) {}
                }
            }

            com.themoon.y1.managers.AudioEffectManager.getInstance().saveCustomEqProfile(newName);
            com.themoon.y1.managers.AudioEffectManager.getInstance().exportEqProfileToFile(newName); // Also immediately export it as a standalone shareable file the moment it's created!
            com.themoon.y1.managers.AudioEffectManager.getInstance().applyEqProfile();

            // 🚀 [UX overhaul] Boldly skip the main-screen reload step and warp straight into the graph studio!
            buildGraphicEqualizerUI();
        });
        containerSettingsItems.addView(btnCreate);

        // 🚀 5. Advanced Graphic Equalizer (Graphic EQ) studio entry button
        createCategoryHeader("━ "+t("GRAPHIC EQUALIZER")+" ━");
        LinearLayout btnGraphicEq = createSettingRow("Graphic Equalizer", t("Open Editor")+" 〉");
        btnGraphicEq.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                clickFeedback();
                if (currentEqProfile.startsWith("preset_")) {
                    android.widget.Toast.makeText(MainActivity.this, t("Please create a Custom Profile to edit!"), android.widget.Toast.LENGTH_LONG).show();
                } else {
                    buildGraphicEqualizerUI(); // Enter the long-awaited Graphic EQ studio screen!
                }
            }
        });
        containerSettingsItems.addView(btnGraphicEq);

        if (containerSettingsItems.getChildCount() > 0) containerSettingsItems.getChildAt(0).requestFocus();
    }

    // Preset and profile selection window (Depth 2)
    private void buildEqProfileSelectorUI() {
        currentSettingsDepth = 3;
        containerSettingsItems.removeAllViews();

        // 🚀 3. Translate when pulling out the list
        if (equalizer != null) {
            for (int i = 0; i < eqPresetNames.size(); i++) {
                final int pIdx = i;
                final String pId = "preset_" + pIdx;
                String prefix = currentEqProfile.equals(pId) ? "✔ " : "   ";
                // 🚀 Intercept the English name pulled from the system and feed it straight into the translator!
                Button btn = createListButton(prefix + t(eqPresetNames.get(i)));
                if (currentEqProfile.equals(pId)) { btn.setTextColor(0xFF00FF00); btn.setTypeface(null, android.graphics.Typeface.BOLD); }
                btn.setOnClickListener(v -> { clickFeedback(); currentEqProfile = pId; com.themoon.y1.managers.AudioEffectManager.getInstance().applyEqProfile(); buildEqualizerSettingsUI(); });
                containerSettingsItems.addView(btn);
            }
        }

        createCategoryHeader("━ "+t("SELECT USER PROFILES")+" ━");
        String listStr = prefs.getString("custom_eq_list", "");
        if (!listStr.trim().isEmpty()) {
            for (final String prof : listStr.split(",")) {
                if (prof.trim().isEmpty()) continue;
                final String cId = "custom_" + prof;
                String prefix = currentEqProfile.equals(cId) ? "✔ " : "   ";
                Button btn = createListButton(prefix + prof);
                if (currentEqProfile.equals(cId)) { btn.setTextColor(0xFF00FF00); btn.setTypeface(null, android.graphics.Typeface.BOLD); }
                btn.setOnClickListener(v -> { clickFeedback(); currentEqProfile = cId; com.themoon.y1.managers.AudioEffectManager.getInstance().applyEqProfile(); buildEqualizerSettingsUI(); });
                containerSettingsItems.addView(btn);
            }
        } else {
            TextView tvEmpty = new TextView(this); tvEmpty.setText("   " + t("No custom profiles found."));
            tvEmpty.setTextColor(0xFF888888); tvEmpty.setPadding(20, 10, 20, 10);
            containerSettingsItems.addView(tvEmpty);
        }

        // 🚀 [Focus bug fix] Once the screen has fully rendered (50ms delay), explicitly force-assign focus.
        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (containerSettingsItems.getChildCount() > 0) {
                    containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    // =========================================================================
    // 🚀 [Fully fixed] Graphic EQ studio with zero-pixel error and the focus-clipping bug resolved
    // =========================================================================
    private void buildGraphicEqualizerUI() {
        currentSettingsDepth = 3;
        settingsSubMode = 3;
        currentAdjustingBand = -1;

        containerSettingsItems.removeAllViews();
        createCategoryHeader("━ "+t("GRAPHIC EQUALIZER")+" ━");

        TextView tvTitle = new TextView(this);
        tvTitle.setText(t("Editing: ") + currentEqProfile.replace("custom_", "") + " (User)");
        tvTitle.setTextColor(0xFFFF8800);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 20);
        containerSettingsItems.addView(tvTitle);

        final android.widget.RelativeLayout eqContainer = new android.widget.RelativeLayout(this);
        eqContainer.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int)(280 * getResources().getDisplayMetrics().density)));
        eqContainer.setGravity(android.view.Gravity.CENTER);

        if (equalizer != null) {
            final short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            int prevId = -1;

            for (short i = 0; i < bands; i++) {
                final short bandIdx = i;
                int freq = equalizer.getCenterFreq(bandIdx) / 1000;
                int currentLevel = customBandLevels[bandIdx];

                final LinearLayout bandLayout = new LinearLayout(this);
                bandLayout.setOrientation(LinearLayout.VERTICAL);
                bandLayout.setFocusable(true);
                bandLayout.setGravity(android.view.Gravity.CENTER);
                bandLayout.setId(8000 + i); // 8000, 8001, 8002... assigns a unique ID

                // 🚀 [Core technique 1] Force-specify the neighbor node to move to on wheel input, to prevent random system crashes!
                int nextFocusId = (i == bands - 1) ? 8500 : (8000 + i + 1); // if it's the last one, go to the close button (8500)
                int prevFocusId = (i == 0) ? 8500 : (8000 + i - 1);         // if it's the first one, go to the close button (8500)

                bandLayout.setNextFocusDownId(nextFocusId); // wheel down (move right)
                bandLayout.setNextFocusUpId(prevFocusId);   // wheel up (move left)

                android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams((int)(60 * getResources().getDisplayMetrics().density), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                if (prevId != -1) {
                    lp.addRule(android.widget.RelativeLayout.RIGHT_OF, prevId);
                    lp.leftMargin = (int)(5 * getResources().getDisplayMetrics().density);
                }
                bandLayout.setLayoutParams(lp);
                prevId = 8000 + i;

                final EqSliderView slider = new EqSliderView(this);
                slider.setRange(range[0], range[1]);
                slider.setLevel(currentLevel);
                slider.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

                TextView tvFreq = new TextView(this);
                tvFreq.setText(freq >= 1000 ? (freq/1000) + "k" : freq + "");
                tvFreq.setTextColor(0xFFFFFFFF);
                tvFreq.setTextSize(12f);
                tvFreq.setGravity(android.view.Gravity.CENTER);
                tvFreq.setPadding(0, 0, 0, 10);

                bandLayout.addView(slider);
                bandLayout.addView(tvFreq);

                bandLayout.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(android.view.View v, boolean hasFocus) {
                        if (hasFocus) {
                            bandLayout.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg() & 0x66FFFFFF));
                        } else {
                            bandLayout.setBackgroundColor(0x00000000);
                            if (currentAdjustingBand == bandIdx) {
                                currentAdjustingBand = -1;
                                slider.setAdjusting(false);
                            }
                        }
                        slider.setFocused(hasFocus);
                    }
                });

                bandLayout.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        clickFeedback();
                        if (currentAdjustingBand == bandIdx) {
                            currentAdjustingBand = -1;
                            slider.setAdjusting(false);
                        } else {
                            if (currentAdjustingBand != -1) {
                                LinearLayout prevBand = (LinearLayout) eqContainer.findViewById(8000 + currentAdjustingBand);
                                if (prevBand != null) ((EqSliderView) prevBand.getChildAt(0)).setAdjusting(false);
                            }
                            currentAdjustingBand = bandIdx;
                            slider.setAdjusting(true);
                        }
                    }
                });

                bandLayout.setOnKeyListener(new android.view.View.OnKeyListener() {
                    @Override
                    public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && currentAdjustingBand == bandIdx) {
                            if (keyCode == 21 || keyCode == 22) {
                                int step = 100;
                                int level = customBandLevels[bandIdx];
                                if (keyCode == 21) level += step;
                                if (keyCode == 22) level -= step;

                                if (level > range[1]) level = range[1];
                                if (level < range[0]) level = range[0];

                                customBandLevels[bandIdx] = level;
                                try { equalizer.setBandLevel(bandIdx, (short) level); } catch(Exception e){}
                                slider.setLevel(level);
                                com.themoon.y1.managers.AudioEffectManager.getInstance().saveCustomEqProfile(currentEqProfile.replace("custom_", ""));
                                clickFeedback();
                                return true;
                            }
                        }
                        return false;
                    }
                });
                eqContainer.addView(bandLayout);
            }
        }

        containerSettingsItems.addView(eqContainer);

        // 🚀 [UX overhaul 1] Replaced the ambiguous "Done/Close" wording with the mixing-console-native term "Save"!
        Button btnClose = createListButton(t("Save Profile"));
        btnClose.setId(8500);

        btnClose.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                clickFeedback();
                String name = currentEqProfile.replace("custom_", "");
                com.themoon.y1.managers.AudioEffectManager.getInstance().saveCustomEqProfile(name); // 1. Save to the internal local vault
                com.themoon.y1.managers.AudioEffectManager.getInstance().exportEqProfileToFile(name); // 2. 🚀 [Key] Export it live to a separate external file (.json) for user sharing!
                com.themoon.y1.managers.AudioEffectManager.getInstance().applyEqProfile(); // 3. Apply the sound pressure to the audio chipset live, immediately

                android.widget.Toast.makeText(MainActivity.this, t("File saved successfully!"), android.widget.Toast.LENGTH_SHORT).show();
                buildEqualizerSettingsUI(); // 4. Returning to the previous page immediately syncs and displays the name we just set at the top-level router!
            }
        });

        btnClose.setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (equalizer != null) {
                        int totalBands = equalizer.getNumberOfBands();
                        if (keyCode == 21) {
                            android.view.View lastBand = eqContainer.findViewById(8000 + totalBands - 1);
                            if (lastBand != null) lastBand.requestFocus();
                            clickFeedback();
                            return true;
                        }
                        if (keyCode == 22) {
                            android.view.View firstBand = eqContainer.findViewById(8000);
                            if (firstBand != null) firstBand.requestFocus();
                            clickFeedback();
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        containerSettingsItems.addView(btnClose);

        containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 🚀 [UX overhaul 2] Instead of the very top, magnetically snap focus straight to the first mixing fader (node 8000) so wheel input is ready to go!
                android.view.View firstBand = eqContainer.findViewById(8000);
                if (firstBand != null) {
                    firstBand.requestFocus();
                } else if (containerSettingsItems.getChildCount() > 2) {
                    containerSettingsItems.getChildAt(2).requestFocus();
                }
            }
        }, 50);
    }
    // 🚀 [Native engine 1] Build the M3U list screen
    private void buildM3uPlaylistUI() {
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
                            try { libraryCacheDb.setFavorite(songFile.getAbsolutePath(), false); } catch(Exception e){}
                            buildVirtualSongsForFavorites(); // Instantly refresh the screen
                            Toast.makeText(MainActivity.instance, t("Removed from Favorites."), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(t("Cancel"), null)
                .show();
    }

    // 🚀 [New engine] Extracts the file extension and metadata to determine lossless status and bitrate (kbps).
    private void updateNavidromeQualityInfo(com.themoon.y1.managers.AudioPlayerManager am) {
        if (am.navidromePlaylist.isEmpty()) return;
        com.themoon.y1.subsonic.SubsonicSong song = am.navidromePlaylist.get(am.navidromeIndex);
        String localPath = song.getExistingLocalPath();
        if (localPath != null) {
            // Playing the downloaded file — show its real quality info
            updateAudioQualityInfo(new File(localPath));
            return;
        }
        if (layoutAudioQualityContainer == null) return;
        // Streaming: Navidrome transcodes everything to MP3 at maxBitRate=192
        tvQualityExt.setText("MP3");
        tvQualityFormat.setText("STREAM");
        tvQualityBitrate.setText("192 kbps");
        tvQualityBitrate.setVisibility(View.VISIBLE);
        layoutAudioQualityContainer.setVisibility(View.VISIBLE);
        qualityInfoHandler.removeCallbacks(hideQualityInfoTask);
        qualityInfoHandler.postDelayed(hideQualityInfoTask, 3000);
    }

    private void updateAudioQualityInfo(File audioFile) {
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
        } catch (Exception e) {}

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
            } catch (Exception e) {}
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
    private android.os.Handler radioFreqHandler = new android.os.Handler();
    private Runnable hideRadioFreqTask = new Runnable() {
        @Override
        public void run() {
            if (layoutLoadingOverlay != null) {
                layoutLoadingOverlay.setVisibility(View.GONE); // Close the popup
                if (pbLoadingProgress != null) pbLoadingProgress.setVisibility(View.VISIBLE); // Restore the progress bar's original state
            }
        }
    };

    private void showRadioFreqPopup(float freq) {
        if (layoutLoadingOverlay != null) {
            radioFreqHandler.removeCallbacks(hideRadioFreqTask);

            // 🚀 [Fix 1] Invisible-man bug fixed: force-restore the opacity back to 100% after the virtual blackout mode had set it to 0%!
            layoutLoadingOverlay.setAlpha(1.0f);
            layoutLoadingOverlay.setVisibility(View.VISIBLE);

            if (pbLoadingProgress != null) {
                pbLoadingProgress.setVisibility(View.VISIBLE);
                int progress = (int) (((freq - 87.5f) / 20.5f) * 100);
                pbLoadingProgress.setProgress(progress);
            }

            if (tvLoadingProgress != null) {
                tvLoadingProgress.setTextSize(24f);
                // 🚀 [Fix 2] Revive the text-output engine that had been accidentally commented out (//) and left dormant!
                tvLoadingProgress.setText(String.format(java.util.Locale.US, t("Tuning Frequency...\n\n%.1f MHz"), freq));
            }

            radioFreqHandler.postDelayed(hideRadioFreqTask, 1500);
        }
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
    private void buildMainMenuVisibilitySettingsUI() {
        currentSettingsDepth = 2;
        containerSettingsItems.removeAllViews();

        // ❌ Per the artist's request, completely and traceless removed the top category header text ("― SHOW / HIDE MENUS ―")!

        // 1. Fetch the current theme's main-menu buttons in order.
        List<ThemeManager.MenuElement> buttons = new ArrayList<>();
        for (ThemeManager.MenuElement el : ThemeManager.getCurrentTheme().menuElements) {
            if (el.type.equals("button")) buttons.add(el);
        }
        java.util.Collections.sort(buttons, new java.util.Comparator<ThemeManager.MenuElement>() {
            @Override
            public int compare(ThemeManager.MenuElement e1, ThemeManager.MenuElement e2) {
                return e1.focusIndex - e2.focusIndex;
            }
        });

        // 2. Attach a hide/show (HIDDEN/SHOW) switch to each button.
        for (int i = 0; i < buttons.size(); i++) {
            final ThemeManager.MenuElement el = buttons.get(i);
            final int currentItemIndex = i; // 🚀 Stamp the index of which row this button is

            final boolean isHidden = prefs.getBoolean("hide_btn_" + el.id, false);
            String btnName = (el.textNormal != null && !el.textNormal.trim().isEmpty()) ? el.textNormal : el.id;

            final LinearLayout row = createSettingRow(btnName, isHidden ? t("HIDDEN") : t("SHOW"));

            if (isHidden) ((TextView) row.getChildAt(1)).setTextColor(ThemeManager.getTextColorSecondary());
            else ((TextView) row.getChildAt(1)).setTextColor(ThemeManager.getTextColorPrimary());

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickFeedback();
                    boolean newState = !prefs.getBoolean("hide_btn_" + el.id, false);
                    prefs.edit().putBoolean("hide_btn_" + el.id, newState).apply();

                    // 🚀 [Fix 1] Instead of tearing down the whole screen, just swap the text of the row that was pressed!
                    TextView tvRight = (TextView) row.getChildAt(1);
                    tvRight.setText(newState ? t("HIDDEN") : t("SHOW"));

                    // 🚀 [Fix 2] Since wheel focus is currently on it, keep the focus color (usually black) as-is.
                    if (row.hasFocus()) {
                        tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    } else {
                        if (newState) tvRight.setTextColor(ThemeManager.getTextColorSecondary());
                        else tvRight.setTextColor(ThemeManager.getTextColorPrimary());
                    }

                    // 💡 [Fix 3] Leave the screen untouched and quietly re-assemble just the invisible main-menu blueprint in the background.
                  //  applyThemeToMainMenu();

                    // ❌ [Root cause removed] The re-call to 'buildMainMenuVisibilitySettingsUI()', which used to wipe and redraw the screen,
                    // and the delayed function (postDelayed) that used to force focus back in, have both been traceless removed!
                }
            });
            containerSettingsItems.addView(row);
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NAVIDROME BROWSER
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildNavidromeUI() {
        com.themoon.y1.subsonic.SubsonicClient client = com.themoon.y1.subsonic.SubsonicClient.getInstance();

        if (client.getConfigVersion() != lastSeenNavidromeConfigVersion) {
            // Server/user/pass changed via the Web Server web UI since we last browsed —
            // drop the old server's in-memory artist list so we don't show it as if
            // the new settings never took effect.
            lastSeenNavidromeConfigVersion = client.getConfigVersion();
            lastNavidromeArtists = new java.util.ArrayList<>();
            lastNavidromeSongs = new java.util.ArrayList<>();
            navidromeBrowseDepth = NAV_ARTISTS;
            selectedNavidromeArtist = null;
            selectedNavidromeAlbum = null;
            isNavidromeLetterView = false;
        }

        if (!client.isConfigured()) {
            showNavidromeMessage("NOT CONFIGURED",
                    "Open the Web Server page from your computer browser,\nthen fill in the Navidrome settings section.");
            return;
        }

        if (navidromeBrowseDepth == NAV_ARTISTS) {
            tvNavidromePath.setText("NAVIDROME  ▸  Artists");
            // Already have the list from this session? Show it instantly and only
            // refresh silently in the background — no "Loading artists…" flash.
            final boolean showedInstantly = !lastNavidromeArtists.isEmpty();
            if (showedInstantly) {
                tvNavidromeStatus.setTextColor(0xFF00FF88);
                tvNavidromeStatus.setText("●");
                buildNavidromeArtistsUI(lastNavidromeArtists);
            } else {
                tvNavidromeStatus.setTextColor(0xFFFFFF00);
                tvNavidromeStatus.setText("●");
                showNavidromeMessage("", "Loading artists…");
                isNavidromeLoading = true;
            }

            client.getArtists(new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicArtist>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
                    isNavidromeLoading = false;
                    tvNavidromeStatus.setTextColor(0xFF00FF88);
                    boolean changed = !navidromeArtistListsEqual(artists, lastNavidromeArtists);
                    boolean stillOnArtists = currentScreenState == STATE_NAVIDROME
                            && navidromeBrowseDepth == NAV_ARTISTS && !isNavidromeLetterView;
                    lastNavidromeArtists = artists;
                    // Rebuild only for the first load or a real library change, and
                    // only while the artist list is still what's on screen.
                    if (stillOnArtists && (changed || !showedInstantly)) {
                        buildNavidromeArtistsUI(artists);
                    }
                }
                @Override
                public void onError(String message) {
                    isNavidromeLoading = false;
                    if (showedInstantly) return; // a stale list beats an error screen
                    tvNavidromeStatus.setTextColor(0xFFFF5555);
                    showNavidromeMessage("CONNECTION ERROR", message);
                }
            });

        } else if (navidromeBrowseDepth == NAV_ALBUMS && selectedNavidromeArtist != null) {
            tvNavidromePath.setText("NAVIDROME  ▸  " + selectedNavidromeArtist.name);
            showNavidromeMessage("", "Loading albums…");

            client.getArtist(selectedNavidromeArtist.id, new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicAlbum>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicAlbum> albums) {
                    buildNavidromeAlbumsUI(albums);
                }
                @Override
                public void onError(String message) {
                    showNavidromeMessage("ERROR", message);
                }
            });

        } else if (navidromeBrowseDepth == NAV_SONGS && selectedNavidromeAlbum != null) {
            tvNavidromePath.setText("NAVIDROME  ▸  " + selectedNavidromeAlbum.artistName + "  ▸  " + selectedNavidromeAlbum.name);
            showNavidromeMessage("", "Loading songs…");

            client.getAlbum(selectedNavidromeAlbum.id, new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicSong>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
                    lastNavidromeSongs = songs;
                    buildNavidromeSongsUI(songs);
                }
                @Override
                public void onError(String message) {
                    showNavidromeMessage("ERROR", message);
                }
            });
        }
    }

    private boolean navidromeArtistListsEqual(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> a,
                                              java.util.List<com.themoon.y1.subsonic.SubsonicArtist> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            com.themoon.y1.subsonic.SubsonicArtist x = a.get(i), y = b.get(i);
            if (!x.id.equals(y.id) || !x.name.equals(y.name) || x.albumCount != y.albumCount) return false;
        }
        return true;
    }

    private void showNavidromeMessage(String title, String body) {
        containerNavidromeItems.removeAllViews();
        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(title);
            tv.setTextColor(0xFFFF5555);
            tv.setTextSize(16);
            tv.setPadding(20, 20, 20, 6);
            containerNavidromeItems.addView(tv);
        }
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(14);
        tv.setPadding(20, 8, 20, 20);
        containerNavidromeItems.addView(tv);
    }

    private void buildNavidromeArtistsUI(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
        buildNavidromeArtistsUI(artists, null);
    }

    private void buildNavidromeArtistsUI(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists, String focusLetter) {
        lastNavidromeArtists = artists;
        isNavidromeLetterView = false;
        containerNavidromeItems.removeAllViews();
        if (artists.isEmpty()) {
            showNavidromeMessage("", "No artists found on server.");
            return;
        }

        Button btnJump = createListButton("A-Z  Jump to Letter");
        btnJump.setTextColor(0xFF88CCFF);
        btnJump.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { buildNavidromeLetterIndexUI(); }
        });
        containerNavidromeItems.addView(btnJump);

        View focusTarget = null;
        for (final com.themoon.y1.subsonic.SubsonicArtist artist : artists) {
            String label = artist.name + "  (" + artist.albumCount + " albums)";
            Button btn = createListButton(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedNavidromeArtist = artist;
                    navidromeBrowseDepth = NAV_ALBUMS;
                    buildNavidromeUI();
                }
            });
            containerNavidromeItems.addView(btn);
            if (focusTarget == null && focusLetter != null && focusLetter.equals(artist.indexLetter)) {
                focusTarget = btn;
            }
        }
        if (focusTarget != null) {
            final View target = focusTarget;
            // Focus after layout so the ScrollView can scroll to the letter
            containerNavidromeItems.post(new Runnable() {
                @Override public void run() { target.requestFocus(); }
            });
        } else {
            focusFirstNavidromeItem();
        }
    }

    private void buildNavidromeLetterIndexUI() {
        isNavidromeLetterView = true;
        containerNavidromeItems.removeAllViews();
        tvNavidromePath.setText("NAVIDROME  ▸  Jump to Letter");

        java.util.List<String> letters = new java.util.ArrayList<>();
        final java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (com.themoon.y1.subsonic.SubsonicArtist artist : lastNavidromeArtists) {
            String letter = artist.indexLetter != null ? artist.indexLetter : "#";
            if (!letters.contains(letter)) letters.add(letter);
            Integer c = counts.get(letter);
            counts.put(letter, c == null ? 1 : c + 1);
        }
        for (final String letter : letters) {
            Button btn = createListButton(letter + "   (" + counts.get(letter) + " artists)");
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tvNavidromePath.setText("NAVIDROME  ▸  Artists");
                    buildNavidromeArtistsUI(lastNavidromeArtists, letter);
                }
            });
            containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem();
    }

    private void buildNavidromeAlbumsUI(java.util.List<com.themoon.y1.subsonic.SubsonicAlbum> albums) {
        containerNavidromeItems.removeAllViews();
        if (albums.isEmpty()) {
            showNavidromeMessage("", "No albums found for this artist.");
            return;
        }
        for (final com.themoon.y1.subsonic.SubsonicAlbum album : albums) {
            String yearStr = album.year > 0 ? " (" + album.year + ")" : "";
            String label = album.name + yearStr + "  —  " + album.songCount + " songs";
            Button btn = createListButton(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedNavidromeAlbum = album;
                    navidromeBrowseDepth = NAV_SONGS;
                    buildNavidromeUI();
                }
            });
            containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem();
    }

    private void buildNavidromeSongsUI(final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        containerNavidromeItems.removeAllViews();
        if (songs.isEmpty()) {
            showNavidromeMessage("", "No songs found in this album.");
            return;
        }

        // Play All button
        Button btnPlayAll = createListButton("▶  Play Album");
        btnPlayAll.setTextColor(0xFF00FF88);
        btnPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNavidromeAlbum(songs, 0);
            }
        });
        containerNavidromeItems.addView(btnPlayAll);

        // Download Album button — long-press deletes the album's downloads
        Button btnDlAll = createListButton("⬇  Download Album");
        btnDlAll.setTextColor(0xFF88CCFF);
        btnDlAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadNavidromeAlbum(songs);
            }
        });
        btnDlAll.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                int downloaded = 0;
                for (com.themoon.y1.subsonic.SubsonicSong s : songs) if (s.isDownloaded()) downloaded++;
                if (downloaded == 0) {
                    Toast.makeText(MainActivity.this, t("No downloads to delete"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                final int count = downloaded;
                showThemedOptionsDialog(t("Delete Downloads"),
                        count + " " + t("downloaded tracks"),
                        new String[]{ "🗑  " + t("Delete"), t("Cancel") },
                        new Runnable[]{
                                new Runnable() {
                                    @Override public void run() {
                                        for (com.themoon.y1.subsonic.SubsonicSong s : songs) deleteNavidromeDownload(s);
                                        refreshNavidromeSongLabels();
                                        Toast.makeText(MainActivity.this, "🗑 " + t("Deleted") + " " + count, Toast.LENGTH_SHORT).show();
                                    }
                                },
                                null
                        });
                return true;
            }
        });
        containerNavidromeItems.addView(btnDlAll);

        // Individual song rows — single focusable button per song
        // Click = play from this track, long-press = download this track
        for (int i = 0; i < songs.size(); i++) {
            final com.themoon.y1.subsonic.SubsonicSong song = songs.get(i);
            final int index = i;

            int mins = song.durationSecs / 60, secs = song.durationSecs % 60;
            android.view.View btn = createNavidromeSongRow(navidromeSongTitleLabel(song),
                    String.format(Locale.US, "%d:%02d", mins, secs));
            btn.setTag(song); // lets refreshNavidromeSongLabels() update the ✓ marker in place
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playNavidromeAlbum(songs, index); }
            });
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (song.isDownloaded()) {
                        showNavidromeDeleteDialog(song);
                    } else {
                        java.util.List<com.themoon.y1.subsonic.SubsonicSong> single =
                                new java.util.ArrayList<com.themoon.y1.subsonic.SubsonicSong>();
                        single.add(song);
                        showNavidromeDownloadQualityDialog(single);
                    }
                    return true;
                }
            });
            containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem();
    }

    private String navidromeSongTitleLabel(com.themoon.y1.subsonic.SubsonicSong song) {
        String downloadedMark = song.isDownloaded() ? "✓ " : "";
        String trackNum = song.track > 0 ? String.format(Locale.US, "%02d. ", song.track) : "";
        return downloadedMark + trackNum + song.title;
    }

    /** Focusable song row styled like createListButton, but with the duration
     *  pinned to the right edge. LinearLayout rows work with wheel nav as long
     *  as the row itself is focusable (same pattern as createListButtonWithIcon). */
    private android.view.View createNavidromeSongRow(String titleText, String durationText) {
        float d = getResources().getDisplayMetrics().density;
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
        row.setPadding((int) (25 * d), (int) (12 * d), (int) (10 * d), (int) (12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        row.setLayoutParams(lp);

        final TextView tvTitle = new TextView(this);
        tvTitle.setText(titleText);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        final TextView tvDuration = new TextView(this);
        tvDuration.setText(durationText);
        tvDuration.setTextSize(15f);
        tvDuration.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        tvDuration.setTextColor(ThemeManager.getTextColorSecondary());
        LinearLayout.LayoutParams durLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        durLp.leftMargin = (int) (8 * d);
        tvDuration.setLayoutParams(durLp);
        row.addView(tvDuration);

        row.setOnLongClickListener(globalScreenOffLongClickListener);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    row.setBackground(createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    tvDuration.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                } else {
                    row.setBackground(createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
                    tvDuration.setTextColor(ThemeManager.getTextColorSecondary());
                }
            }
        });
        return row;
    }

    /** Update ✓ markers on the visible song list without rebuilding (keeps wheel focus). */
    private void refreshNavidromeSongLabels() {
        if (currentScreenState != STATE_NAVIDROME || navidromeBrowseDepth != NAV_SONGS) return;
        for (int i = 0; i < containerNavidromeItems.getChildCount(); i++) {
            View child = containerNavidromeItems.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() instanceof com.themoon.y1.subsonic.SubsonicSong) {
                TextView tvTitle = (TextView) ((LinearLayout) child).getChildAt(0);
                tvTitle.setText(navidromeSongTitleLabel((com.themoon.y1.subsonic.SubsonicSong) child.getTag()));
            }
        }
    }

    private void playNavidromeAlbum(java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        am.navidromePlaylist.clear();
        am.navidromePlaylist.addAll(songs);
        am.navidromeIndex = startIndex;

        com.themoon.y1.subsonic.SubsonicSong song = songs.get(startIndex);
        String url = com.themoon.y1.subsonic.SubsonicClient.getInstance().getStreamUrl(song.id);
        am.playNavidromeSong(this, song, url);
        changeScreen(STATE_PLAYER);
        progressHandler.removeCallbacks(updateProgressTask);
        progressHandler.post(updateProgressTask);
    }

    private void downloadNavidromeAlbum(java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        showNavidromeDownloadQualityDialog(songs);
    }

    private void showNavidromeDownloadQualityDialog(final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        if (songs == null || songs.isEmpty()) return;
        String what = songs.size() == 1 ? songs.get(0).title : songs.size() + " " + t("tracks");
        showThemedOptionsDialog(t("Download Quality"), what,
                new String[]{ "⬇  " + t("Original Quality"), "⬇  " + t("MP3 192kbps"), t("Cancel") },
                new Runnable[]{
                        new Runnable() { @Override public void run() { enqueueNavidromeDownloads(songs, false); } },
                        new Runnable() { @Override public void run() { enqueueNavidromeDownloads(songs, true); } },
                        null
                });
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
        } catch (Exception ignored) {}
        try {
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            new java.io.File("/storage/sdcard0/Y1_Covers", base + ".jpg").delete();
        } catch (Exception ignored) {}

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
    private void showThemedOptionsDialog(String title, String subtitle, String[] options, final Runnable[] actions) {
        float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createButtonBackground(0xF2151515));
        root.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (14 * d));

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
            Button btn = createListButton(options[i]);
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
                int i = index == -1 ? (dir == 1 ? 0 : root.getChildCount() - 1) : index + dir;
                for (; i >= 0 && i < root.getChildCount(); i += dir) {
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

        dialog.setContentView(root);
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

    private void showNavidromeDeleteDialog(final com.themoon.y1.subsonic.SubsonicSong song) {
        showThemedOptionsDialog(t("Delete Download"), song.title,
                new String[]{ "🗑  " + t("Delete"), t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                if (deleteNavidromeDownload(song)) {
                                    refreshNavidromeSongLabels();
                                    Toast.makeText(MainActivity.this, "🗑 " + t("Deleted") + ": " + song.title, Toast.LENGTH_SHORT).show();
                                }
                            }
                        },
                        null
                });
    }

    /** Delete both downloaded variants of a track, keeping the launcher library,
     *  favorites, and now-empty album/artist folders consistent. */
    private boolean deleteNavidromeDownload(com.themoon.y1.subsonic.SubsonicSong song) {
        boolean deleted = false;
        String[] paths = { song.getLocalPath(), song.getLocalPathMp3() };
        for (String p : paths) {
            java.io.File f = new java.io.File(p);
            if (!f.exists() || !f.delete()) continue;
            deleted = true;
            java.util.Iterator<SongItem> it = customLibrary.iterator();
            while (it.hasNext()) {
                if (it.next().file.getAbsolutePath().equals(p)) it.remove();
            }
            trackNumberMap.remove(p);
            if (favoritePaths.remove(p)) {
                try { libraryCacheDb.setFavorite(p, false); } catch (Exception ignored) {}
            }
            // delete() only succeeds on empty dirs, so this safely prunes
            // the album folder and then the artist folder when they empty out
            java.io.File albumDir = f.getParentFile();
            if (albumDir != null && albumDir.delete()) {
                java.io.File artistDir = albumDir.getParentFile();
                if (artistDir != null) artistDir.delete();
            }
        }
        return deleted;
    }

    // Downloads run strictly one at a time — parallel transfers just divide the
    // ~190kbps link and make every track take the full album's time.
    private String currentNavidromeDownloadId;

    private void enqueueNavidromeDownloads(java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs, boolean transcoded) {
        if (songs == null || songs.isEmpty()) return;
        java.util.List<NavidromeDownloadItem> toAdd = new java.util.ArrayList<NavidromeDownloadItem>();
        long neededBytes = 0;
        for (com.themoon.y1.subsonic.SubsonicSong song : songs) {
            String target = transcoded ? song.getLocalPathMp3() : song.getLocalPath();
            if (new java.io.File(target).exists() || isNavidromeDownloadQueued(song.id)) continue;
            toAdd.add(new NavidromeDownloadItem(song, transcoded));
            if (transcoded) {
                neededBytes += (long) song.durationSecs * 24000L; // 192kbps ≈ 24KB/s
            } else {
                neededBytes += song.sizeBytes > 0 ? song.sizeBytes
                        : (long) song.durationSecs * 130000L; // ~1Mbps FLAC fallback
            }
        }
        if (toAdd.isEmpty()) {
            Toast.makeText(this, "✅ Already downloaded", Toast.LENGTH_SHORT).show();
            return;
        }

        // Free-space check with a 50MB safety margin — a FLAC album on a full
        // card would otherwise fail confusingly mid-queue
        try {
            android.os.StatFs sf = new android.os.StatFs("/storage/sdcard0");
            long available = (long) sf.getAvailableBlocks() * sf.getBlockSize();
            if (neededBytes + 50L * 1024 * 1024 > available) {
                Toast.makeText(this, "❌ " + t("Not enough space") + ": ~" + (neededBytes / (1024 * 1024))
                        + " MB " + t("needed") + ", " + (available / (1024 * 1024)) + " MB " + t("free"),
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception ignored) {}

        navidromeDownloadQueue.addAll(toAdd);
        navidromeQueueTotal += toAdd.size();
        Toast.makeText(this, "⬇ Queued " + toAdd.size() + (toAdd.size() == 1 ? " track" : " tracks"), Toast.LENGTH_SHORT).show();
        if (!isNavidromeDownloading) processNextNavidromeDownload();
    }

    private boolean isNavidromeDownloadQueued(String songId) {
        if (songId.equals(currentNavidromeDownloadId)) return true;
        for (NavidromeDownloadItem item : navidromeDownloadQueue) {
            if (songId.equals(item.song.id)) return true;
        }
        return false;
    }

    private void acquireNavidromeDownloadLocks() {
        try {
            if (navidromeDownloadWakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                navidromeDownloadWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "Y1NavidromeDownload");
                navidromeDownloadWakeLock.setReferenceCounted(false);
            }
            if (!navidromeDownloadWakeLock.isHeld()) navidromeDownloadWakeLock.acquire();

            if (navidromeDownloadWifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                navidromeDownloadWifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Y1NavidromeDownload");
                navidromeDownloadWifiLock.setReferenceCounted(false);
            }
            if (!navidromeDownloadWifiLock.isHeld()) navidromeDownloadWifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseNavidromeDownloadLocks() {
        try { if (navidromeDownloadWakeLock != null && navidromeDownloadWakeLock.isHeld()) navidromeDownloadWakeLock.release(); } catch (Exception ignored) {}
        try { if (navidromeDownloadWifiLock != null && navidromeDownloadWifiLock.isHeld()) navidromeDownloadWifiLock.release(); } catch (Exception ignored) {}
    }

    private void processNextNavidromeDownload() {
        final NavidromeDownloadItem item = navidromeDownloadQueue.poll();
        final com.themoon.y1.subsonic.SubsonicSong song = item != null ? item.song : null;
        if (song == null) {
            isNavidromeDownloading = false;
            currentNavidromeDownloadId = null;
            releaseNavidromeDownloadLocks();
            if (navidromeQueueTotal > 0) {
                Toast.makeText(this, "✅ Downloads finished (" + navidromeQueueDone + "/" + navidromeQueueTotal + ")",
                        Toast.LENGTH_SHORT).show();
            }
            navidromeQueueTotal = 0;
            navidromeQueueDone = 0;
            updateNavidromeDownloadStatus(null);
            refreshNavidromeSongLabels();
            return;
        }
        isNavidromeDownloading = true;
        currentNavidromeDownloadId = song.id;
        acquireNavidromeDownloadLocks();
        try {
            startNavidromeDownload(item, song);
        } catch (Exception e) {
            // downloadSong() threw before registering any async callback — release the
            // locks now instead of leaving them held with nothing left to release them.
            releaseNavidromeDownloadLocks();
            navidromeQueueDone++;
            logNavidromeDownloadError(song, "Failed to start download: " + e.getMessage());
            processNextNavidromeDownload();
        }
    }

    private void startNavidromeDownload(final NavidromeDownloadItem item, final com.themoon.y1.subsonic.SubsonicSong song) {
        updateNavidromeDownloadStatus("⬇ " + (navidromeQueueDone + 1) + "/" + navidromeQueueTotal + "  0%");

        String savePath = item.transcoded ? song.getLocalPathMp3() : song.getLocalPath();
        com.themoon.y1.subsonic.SubsonicClient.getInstance().downloadSong(song.id, savePath, item.transcoded,
                new com.themoon.y1.subsonic.SubsonicClient.DownloadCallback() {
                    @Override
                    public void onProgress(int percent, long bytesSoFar) {
                        String p = percent >= 0 ? percent + "%"
                                : String.format(Locale.US, "%.1f MB", bytesSoFar / 1048576f);
                        updateNavidromeDownloadStatus("⬇ " + (navidromeQueueDone + 1) + "/" + navidromeQueueTotal
                                + "  " + p);
                    }
                    @Override
                    public void onComplete(String path) {
                        navidromeQueueDone++;
                        // Register in the launcher's own library right away (its scan is
                        // manual/boot-time only) and in the system MediaStore
                        registerDownloadedSongInLibrary(song, path);
                        // Transcoded MP3s lose their embedded art (ffmpeg keeps audio
                        // only), so stash the server's cover for Cover Flow / player
                        cacheNavidromeCoverForDownloadedTrack(song, path);
                        android.media.MediaScannerConnection.scanFile(
                                getApplicationContext(), new String[]{path}, null, null);
                        refreshNavidromeSongLabels();
                        processNextNavidromeDownload();
                    }
                    @Override
                    public void onError(String message) {
                        if (item.retryCount < MAX_NAVIDROME_DOWNLOAD_RETRIES) {
                            item.retryCount++;
                            updateNavidromeDownloadStatus("⚠ Retry " + item.retryCount + "/" + MAX_NAVIDROME_DOWNLOAD_RETRIES
                                    + ": " + song.title);
                            navidromeDownloadQueue.addFirst(item); // retry this track next, ahead of the rest of the queue
                            new Handler().postDelayed(new Runnable() {
                                @Override public void run() { processNextNavidromeDownload(); }
                            }, 1500);
                            return;
                        }
                        navidromeQueueDone++;
                        logNavidromeDownloadError(song, message + " (gave up after " + (item.retryCount + 1) + " attempts)");
                        Toast.makeText(MainActivity.this, "❌ " + song.title + ": " + message, Toast.LENGTH_SHORT).show();
                        processNextNavidromeDownload();
                    }
                });
    }

    /**
     * Appends a timestamped line to a log file on the SD card so a failed download can still
     * be diagnosed after the fact — logcat rotates out of everything within a couple of minutes.
     */
    private void logNavidromeDownloadError(com.themoon.y1.subsonic.SubsonicSong song, String message) {
        try {
            File logDir = new File("/storage/sdcard0/Y1_Logs");
            if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, "navidrome_download_errors.log");
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + song.title + " (id=" + song.id + ")  ->  " + message + "\n";
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (Exception ignored) {}
    }

    /**
     * Add a freshly downloaded track to the launcher's in-memory library so it
     * shows up in Artists/Albums/All Songs immediately — the launcher's own scan
     * only runs at boot or manually, and MediaStore isn't consulted at all.
     * Metadata comes straight from the Subsonic API, no tag parsing needed.
     */
    private void registerDownloadedSongInLibrary(com.themoon.y1.subsonic.SubsonicSong song, String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;
            for (SongItem existing : customLibrary) {
                if (existing.file.getAbsolutePath().equals(path)) return;
            }
            String title = song.title != null && !song.title.isEmpty() ? song.title : f.getName();
            // Album artist first — same grouping rule as the tag scan
            String artist = song.albumArtist != null && !song.albumArtist.isEmpty() ? song.albumArtist
                    : (song.artist != null && !song.artist.isEmpty() ? song.artist : t("Unknown Artist"));
            String album = song.album != null && !song.album.isEmpty() ? song.album : t("Unknown Album");
            String year = song.year > 0 ? String.valueOf(song.year) : t("Unknown Year");
            String genre = song.genre != null && !song.genre.isEmpty() ? song.genre : t("Unknown Genre");
            customLibrary.add(new SongItem(f, title, artist, album, year, genre));
            trackNumberMap.put(path, song.track);
            if (libraryCacheDb != null) {
                libraryCacheDb.upsert(new com.themoon.y1.db.LibraryCacheDb.CachedSong(
                        path, f.lastModified(), f.length(), title, artist, album, year, genre, song.track, false));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Save the album cover for a downloaded track in the launcher's own cover
     * convention: Y1_Covers/<track filename>.jpg plus the DB's album art path —
     * the same pair fetchTrackInfoFromInternet writes and Cover Flow reads.
     */
    private void cacheNavidromeCoverForDownloadedTrack(final com.themoon.y1.subsonic.SubsonicSong song,
                                                       final String trackPath) {
        if (song.coverArtId == null || song.coverArtId.isEmpty()) return;
        java.io.File cacheFile = new java.io.File("/storage/sdcard0/Y1_Covers/Navidrome",
                song.coverArtId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        com.themoon.y1.subsonic.SubsonicClient.getInstance().fetchCoverArt(song.coverArtId, 320, cacheFile,
                new com.themoon.y1.subsonic.SubsonicClient.Callback<String>() {
                    @Override
                    public void onSuccess(String coverPath) {
                        try {
                            String base = new java.io.File(trackPath).getName();
                            int dot = base.lastIndexOf('.');
                            if (dot > 0) base = base.substring(0, dot);
                            java.io.File dest = new java.io.File("/storage/sdcard0/Y1_Covers", base + ".jpg");
                            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                            if (!dest.exists()) {
                                java.io.FileInputStream in = new java.io.FileInputStream(coverPath);
                                java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                out.close();
                                in.close();
                            }
                            libraryCacheDb.setAlbumArtPath(trackPath, dest.getAbsolutePath());
                        } catch (Exception ignored) {}
                    }
                    @Override
                    public void onError(String message) {}
                });
    }

    private void updateNavidromeDownloadStatus(String status) {
        if (status == null) {
            tvNavidromeStatus.setText("●");
        } else {
            tvNavidromeStatus.setTextColor(0xFF88CCFF);
            tvNavidromeStatus.setText(status);
        }
    }

    /** Fetch (or reuse cached) Navidrome cover art and apply it to the player screen. */
    public void loadNavidromeCoverArt(final com.themoon.y1.subsonic.SubsonicSong song) {
        if (song == null || song.coverArtId == null || song.coverArtId.isEmpty()) return;
        currentNavidromeCoverArtId = song.coverArtId;
        java.io.File cacheFile = new java.io.File("/storage/sdcard0/Y1_Covers/Navidrome",
                song.coverArtId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        com.themoon.y1.subsonic.SubsonicClient.getInstance().fetchCoverArt(song.coverArtId, 320, cacheFile,
                new com.themoon.y1.subsonic.SubsonicClient.Callback<String>() {
                    @Override
                    public void onSuccess(String path) {
                        // Skip if the user already moved on to a track with different art
                        if (song.coverArtId.equals(currentNavidromeCoverArtId)) applyCachedCoverArt(path);
                    }
                    @Override
                    public void onError(String message) {}
                });
    }

    private void focusFirstNavidromeItem() {
        if (containerNavidromeItems.getChildCount() > 0) {
            containerNavidromeItems.getChildAt(0).requestFocus();
        }
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
        } catch (Exception ignored) {}
    }

}


