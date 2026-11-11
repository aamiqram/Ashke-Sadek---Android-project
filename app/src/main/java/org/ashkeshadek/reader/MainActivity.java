package org.ashkeshadek.reader;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Ashke Shadek — native offline songbook reader.
 * Network access is limited to verified GitHub app-update metadata and APK downloads.
 * Songs and microphone audio stay on-device; no analytics or cloud speech service is used.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFFF7F5EF;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1E2924;
    private static final int MUTED = 0xFF78827B;
    private static final int GREEN = 0xFF28634F;
    private static final int GREEN_DARK = 0xFF1D4C3D;
    private static final int BORDER = 0xFFE7E8E1;
    private static final int SOFT_GREEN = 0xFFEAF2ED;
    private static final long CACHE_BUDGET_BYTES = 1024L * 1024L;
    private static final int SONG_BATCH_SIZE = 60;
    private static final String PREFS = "ashke_shadek_local";
    private static final int MIC_PERMISSION = 410;
    private static final int REQUEST_EXPORT_USER_DATA = 512;
    private static final int REQUEST_IMPORT_USER_DATA = 513;
    private static final String USER_DATA_BACKUP_FORMAT = "ashke-shadek-user-data";
    private static final String FEEDBACK_OUTBOX_KEY = "feedback_outbox_v1";
    private static final String PERFORMANCE_EVENTS_KEY = "performance_events_v1";
    private static final String LAST_CRASH_DIAGNOSTIC_KEY = "last_crash_diagnostic_v1";
    private static volatile boolean crashDiagnosticHandlerInstalled;
    private static final int MAX_PENDING_FEEDBACK = 20;
    private static final String[] FEEDBACK_CATEGORIES = {
            "অ্যাপে সমস্যা হচ্ছে", "অ্যাপ ধীর / আটকে যাচ্ছে", "গান খুঁজে পাচ্ছি না",
            "গানের লেখা বা সাজানো ভুল", "নতুন ফিচার বা উন্নতির পরামর্শ"
    };
    private static final Pattern WORD_PATTERN =
            Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern NORMALIZE_SEPARATOR_PATTERN =
            Pattern.compile("[\\p{Punct}\\p{Z}\\s\\u200c\\u200d]+");
    private static final String[][] OLD_BENGALI_ALIASES = {
            {"হইয়াছে", "হয়েছে"}, {"হইয়াছে", "হয়েছে"}, {"হইয়াছি", "হয়েছি"},
            {"করিয়াছি", "করেছি"}, {"করিয়াছে", "করেছে"}, {"করিয়াছেন", "করেছেন"},
            {"হইয়া", "হয়ে"}, {"হইয়ে", "হয়ে"}, {"হইলে", "হলে"},
            {"হইতে", "হতে"}, {"হইবে", "হবে"}, {"হইল", "হল"},
            {"করিয়া", "করে"}, {"করিতে", "করতে"}, {"করিতেছে", "করছে"},
            {"করতেছে", "করছে"}, {"যাইতেছে", "যাচ্ছে"}, {"যাইয়া", "গিয়ে"},
            {"দিয়া", "দিয়ে"}, {"লইয়া", "নিয়ে"}, {"পাইয়া", "পেয়ে"},
            {"খাইয়া", "খেয়ে"}, {"বলিয়া", "বলে"}, {"দেখিয়া", "দেখে"},
            {"তাহার", "তার"}, {"তাহাকে", "তাকে"}, {"যাহা", "যা"},
            {"যাহার", "যার"}, {"যাহাতে", "যাতে"}, {"ইহা", "এটা"}
    };
    private static final Pattern[] OLD_BENGALI_ALIAS_PATTERNS = buildAliasPatterns();

    private static Pattern[] buildAliasPatterns() {
        Pattern[] patterns = new Pattern[OLD_BENGALI_ALIASES.length];
        for (int i = 0; i < OLD_BENGALI_ALIASES.length; i++) {
            patterns[i] = Pattern.compile("(?<![\\p{L}\\p{M}])"
                    + Pattern.quote(OLD_BENGALI_ALIASES[i][0])
                    + "(?![\\p{L}\\p{M}])");
        }
        return patterns;
    }

    private final List<Song> songs = new ArrayList<>();
    private final java.util.Map<String, SearchDocument> searchIndex = new java.util.HashMap<>();
    private final List<String> volumeNames = new ArrayList<>();
    private SharedPreferences prefs;
    private Set<String> favorites = new LinkedHashSet<>();
    private Set<String> bookmarks = new LinkedHashSet<>();
    private String lastSongId = "";
    private List<String> recentIds = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout page;
    private String currentScreen = "home";
    private Song currentSong;
    private ListView readerList;
    private List<Song> readerBookSongs = Collections.emptyList();
    private SongReaderAdapter readerAdapter;
    private TextView readerHeaderTitle;
    private TextView readerHeaderSubtitle;
    private int readerPageWidthPx;
    private int readerTextMeasurePx;
    private String lastReaderOffsetSongId = "";
    private int lastReaderOffsetPx = -1;
    private long lastReaderOffsetWriteMs;
    private EditText activeSearch;
    private EditText pendingVoiceTarget = null;
    private ResultReceiver voiceReceiver;
    private ServiceConnection voiceServiceConnection;
    private boolean voiceServiceStarted;
    private boolean voiceServiceBound;
    private AlertDialog voiceDialog;
    private TextView voiceStatus;
    private LinearLayout voiceCandidates;
    private AlertDialog songSearchDialog;
    private EditText dialogSearchField;
    private EditText searchBeforeDialog;
    private final java.util.Map<String, VoiceCandidate> voiceCandidateHistory =
            new java.util.HashMap<>();
    private String pinnedVoiceCandidateId = "";
    private int pinnedVoiceCandidateScore;
    private long lastVoiceCandidateUpdateMs;
    private String lastVoiceCandidateQuery = "";
    private boolean voiceSessionCompleted = false;
    private boolean voiceListeningStarted = false;
    private boolean voiceCandidateSelected;
    private boolean catalogReady;
    private volatile boolean searchIndexReady;
    private LinearLayout activeSearchResults;
    private String activeSearchQuery = "";
    private int activeSearchVolumeFilter;
    private String latestVoiceTranscript = "";
    private boolean homeHasInteracted;
    private View homeLoadingIndicator;
    private long launchStartedUptimeMs;
    private long voiceSessionStartedUptimeMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable voicePrewarmTask;
    private boolean voiceModelServiceActive;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchStartedUptimeMs = SystemClock.uptimeMillis();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        installCrashDiagnosticHandler();
        favorites = new LinkedHashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
        bookmarks = new LinkedHashSet<>(prefs.getStringSet("bookmarks", Collections.emptySet()));
        lastSongId = prefs.getString("last_song", "");
        recentIds = parseRecent(prefs.getString("recent", ""));
        volumeNames.add("প্রথম খণ্ড");
        volumeNames.add("দ্বিতীয় খণ্ড");
        volumeNames.add("তৃতীয় খণ্ড");
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= 23 ? BG : GREEN_DARK);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? BG : GREEN_DARK);
        int lightSystemBars = 0;
        if (Build.VERSION.SDK_INT >= 23) lightSystemBars |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) lightSystemBars |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (lightSystemBars != 0) getWindow().getDecorView().setSystemUiVisibility(lightSystemBars);
        renderHome();
        loadCatalogInBackground();
        long lastUpdateCheck = prefs.getLong("last_update_check_ms", 0L);
        if (System.currentTimeMillis() - lastUpdateCheck >= 24L * 60L * 60L * 1000L) {
            checkForAppUpdates(false);
        }
    }

    private void renderStartupScreen() {
        currentScreen = "loading";
        mount("এশকে ছাদেক", "ভান্ডারী গানের বই", false, false);
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(24), dp(28), dp(24));
        page.addView(body, new LinearLayout.LayoutParams(-1, -1));
        TextView title = label("এশকে ছাদেক", 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        body.addView(title, matchWrap());
        addSpace(body, 10);
        TextView status = label("গানের বই প্রস্তুত হচ্ছে…", 15, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setTag("startup-status");
        body.addView(status, matchWrap());
        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progressParams.topMargin = dp(20);
        body.addView(progress, progressParams);
    }

    private void loadCatalogInBackground() {
        catalogReady = false;
        searchIndexReady = false;
        activeSearchResults = null;
        if (page != null && currentScreen.equals("loading")) {
            TextView status = page.findViewWithTag("startup-status");
            if (status != null) status.setText("গানের বই প্রস্তুত হচ্ছে…");
        }
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            long catalogStarted = SystemClock.uptimeMillis();
            songs.clear();
            searchIndex.clear();
            boolean loaded = loadCatalog();
            if (loaded) {
                loadEmphasis();
                enforceStorageBudget();
            }
            long catalogElapsed = SystemClock.uptimeMillis() - catalogStarted;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!loaded) {
                    renderCatalogLoadError();
                    return;
                }
                catalogReady = true;
                favorites = new LinkedHashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
                bookmarks = new LinkedHashSet<>(prefs.getStringSet("bookmarks", Collections.emptySet()));
                if (homeLoadingIndicator != null) homeLoadingIndicator.setVisibility(View.GONE);
                logMemory("main-after-catalog");
                Log.i("AshkeStartup", "catalogDataReadyMs=" + catalogElapsed
                        + " firstScreenMs=" + (SystemClock.uptimeMillis() - launchStartedUptimeMs));
                appendPerformanceDiagnostic("catalogReadyMs=" + catalogElapsed
                        + ", firstScreenMs=" + (SystemClock.uptimeMillis() - launchStartedUptimeMs));
                if (currentScreen.equals("loading")
                        || (currentScreen.equals("home") && !homeHasInteracted)) {
                    renderHome();
                } else if (activeSearchResults != null) {
                    String query = activeSearch == null
                            ? activeSearchQuery : activeSearch.getText().toString();
                    renderSearchResults(activeSearchResults, query, activeSearchVolumeFilter);
                }
            });
            if (loaded) {
                new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    long indexStarted = SystemClock.uptimeMillis();
                    buildSearchIndex();
                    searchIndexReady = true;
                    long indexElapsed = SystemClock.uptimeMillis() - indexStarted;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Log.i("AshkeStartup", "searchIndexReadyMs=" + indexElapsed
                                + " totalStartupMs=" + (SystemClock.uptimeMillis() - launchStartedUptimeMs));
                        appendPerformanceDiagnostic("searchIndexReadyMs=" + indexElapsed
                                + ", totalStartupMs=" + (SystemClock.uptimeMillis() - launchStartedUptimeMs));
                        if (activeSearchResults != null) {
                            String query = activeSearch == null
                                    ? activeSearchQuery : activeSearch.getText().toString();
                            renderSearchResults(activeSearchResults, query, activeSearchVolumeFilter);
                        }
                        if (!latestVoiceTranscript.isEmpty()) {
                            renderVoiceCandidates(latestVoiceTranscript);
                        }
                        scheduleOfflineVoicePrewarm();
                    });
                }, "ashke-search-index").start();
            }
        }, "ashke-catalog-startup").start();
    }

    private void renderCatalogLoadError() {
        currentScreen = "loading-error";
        mount("এশকে ছাদেক", "ভান্ডারী গানের বই", false, false);
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24));
        page.addView(body, new LinearLayout.LayoutParams(-1, -1));
        TextView message = label("গানের বইটি খোলা যায়নি। আবার চেষ্টা করুন।", 15, TEXT, true);
        message.setGravity(Gravity.CENTER);
        body.addView(message, matchWrap());
        addSpace(body, 14);
        TextView retry = navButton("আবার চেষ্টা করুন");
        body.addView(retry, matchHeight(50));
        retry.setOnClickListener(v -> renderStartupScreenAndRetry());
    }

    private void renderStartupScreenAndRetry() {
        songs.clear();
        searchIndex.clear();
        renderStartupScreen();
        loadCatalogInBackground();
    }

    @Override protected void onResume() {
        super.onResume();
        AppUpdater.resumePendingInstall(this);
    }

    /**
     * Parse one song at a time. JsonReader avoids keeping the full 2.35 MB
     * JSON object tree alive alongside the final Song objects during startup.
     * Lyrics are reconstructed directly from their canonical Word paragraphs;
     * each paragraph is stored once in Song.lyrics and represented by offsets.
     */
    private boolean loadCatalog() {
        try (InputStream input = openCatalog();
             JsonReader reader = new JsonReader(new InputStreamReader(input, "UTF-8"))) {
            boolean foundSongs = false;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("songs".equals(name)) {
                    foundSongs = true;
                    reader.beginArray();
                    while (reader.hasNext()) songs.add(readSong(reader));
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (!foundSongs || songs.size() != 615) {
                throw new IllegalStateException("Catalogue song count mismatch: " + songs.size());
            }
            return true;
        } catch (Exception error) {
            songs.clear();
            Log.e("AshkeCatalog", "Could not stream the song catalogue.", error);
            return false;
        }
    }

    private Song readSong(JsonReader reader) throws IOException {
        String id = "";
        String title = "";
        String category = "";
        int volume = 0;
        int number = 0;
        StringBuilder lyrics = new StringBuilder();
        List<LyricBlock> blocks = new ArrayList<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "id":
                    id = reader.nextString();
                    break;
                case "volume":
                    volume = reader.nextInt();
                    break;
                case "number":
                    number = reader.nextInt();
                    break;
                case "title":
                    title = reader.nextString();
                    break;
                case "category":
                    category = reader.nextString();
                    break;
                case "lyricBlocks":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        appendLyricBlock(reader, lyrics, blocks);
                    }
                    reader.endArray();
                    break;
                default:
                    // "lyrics", "firstLine" and source metadata are derivable
                    // from paragraph blocks or are not required by the reader.
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();

        if (id.isEmpty() || title.isEmpty() || volume < 1 || volume > 3
                || number < 1 || blocks.isEmpty()) {
            throw new IOException("Invalid song record: " + id);
        }
        return new Song(id, volume, number, title, lyrics.toString(), category, blocks);
    }

    private void appendLyricBlock(JsonReader reader, StringBuilder lyrics,
                                  List<LyricBlock> blocks) throws IOException {
        String text = "";
        int alignment = 0;
        float leftIndent = 0f;
        float firstLineIndent = 0f;
        float rightIndent = 0f;
        float spaceBefore = 0f;
        float spaceAfter = 0f;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "text":
                    text = reader.nextString();
                    break;
                case "alignment":
                    alignment = reader.nextInt();
                    break;
                case "leftIndentPt":
                    leftIndent = (float) reader.nextDouble();
                    break;
                case "firstLineIndentPt":
                    firstLineIndent = (float) reader.nextDouble();
                    break;
                case "rightIndentPt":
                    rightIndent = (float) reader.nextDouble();
                    break;
                case "spaceBeforePt":
                    spaceBefore = (float) reader.nextDouble();
                    break;
                case "spaceAfterPt":
                    spaceAfter = (float) reader.nextDouble();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();

        if (!blocks.isEmpty()) lyrics.append('\n');
        int start = lyrics.length();
        lyrics.append(text);
        int end = lyrics.length();
        blocks.add(new LyricBlock(start, end, alignment, leftIndent, firstLineIndent,
                rightIndent, spaceBefore, spaceAfter));
    }

    /** Gradle unzips .gz assets, so the packaged file is songs.json — accept both. */
    private InputStream openCatalog() throws Exception {
        try {
            return new GZIPInputStream(getAssets().open("songs.json.gz"));
        } catch (Exception missingGz) {
            return getAssets().open("songs.json");
        }
    }

    /** Applies the exact bold character ranges extracted from the original Word runs. */
    private void loadEmphasis() {
        try (InputStream input = new GZIPInputStream(getAssets().open("lyrics_emphasis.json.gz"));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            JSONArray rows = new JSONArray(out.toString("UTF-8"));
            int position = 0;
            for (Song song : songs) {
                for (LyricBlock block : song.lyricBlocks) {
                    if (position >= rows.length()) {
                        Log.w("AshkeShadek", "Lyric emphasis sidecar ended before the catalogue.");
                        return;
                    }
                    JSONArray spans = rows.getJSONArray(position++);
                    for (int i = 0; i < spans.length(); i++) {
                        JSONArray span = spans.getJSONArray(i);
                        int start = span.getInt(0);
                        int end = span.getInt(1);
                        if (start >= 0 && end > start && end <= block.length()) {
                            if (block.boldSpans == null) block.boldSpans = new ArrayList<>();
                            block.boldSpans.add(new int[]{start, end});
                        }
                    }
                }
            }
            if (position != rows.length()) {
                Log.w("AshkeShadek", "Lyric emphasis count does not match the catalogue.");
            }
        } catch (Exception error) {
            Log.w("AshkeShadek", "Could not load original lyric emphasis ranges.", error);
        }
    }

    private void mount(String title, String subtitle, boolean showBack, boolean withBottomNav) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, system.top, 0, system.bottom);
                return insets;
            });
        }

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(10), dp(18), dp(10));
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBackgroundColor(BG);
        if (showBack) {
            TextView back = iconButton("‹", 30);
            back.setOnClickListener(v -> onBackPressed());
            top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(46)));
        }
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView brand = label(title, 21, TEXT, true);
        brand.setTag("screen-title");
        TextView sub = label(subtitle, 11, MUTED, false);
        sub.setTag("screen-subtitle");
        titleBox.addView(brand);
        if (subtitle != null && !subtitle.isEmpty()) titleBox.addView(sub);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        top.addView(titleBox, titleParams);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (withBottomNav) root.addView(bottomNav(), new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private void renderHome() {
        currentScreen = "home";
        homeLoadingIndicator = null;
        mount("এশকে ছাদেক", "ভান্ডারী গানের বই", false, true);
        ScrollView scroll = pageScroll();
        scroll.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) homeHasInteracted = true;
            return false;
        });
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        body.setPadding(dp(18), dp(6), dp(18), dp(22));

        LinearLayout hero = cardColumn();
        hero.setBackground(roundFill(GREEN_DARK, 22));
        hero.setPadding(dp(20), dp(22), dp(20), dp(20));
        TextView over = label("আপনার গানের বই", 12, 0xFFDAE8DE, true);
        TextView heroTitle = label("এশকে ছাদেক", 30, 0xFFFFFFFF, true);
        TextView heroSub = label("তিন খণ্ডের ভান্ডারী গান — সবসময় সঙ্গে", 13, 0xFFE0EAE3, false);
        hero.addView(over);
        addSpace(hero, 8);
        hero.addView(heroTitle);
        addSpace(hero, 6);
        hero.addView(heroSub);
        body.addView(hero, matchWrap());
        addSpace(body, 18);

        if (!catalogReady) {
            LinearLayout loading = new LinearLayout(this);
            loading.setGravity(Gravity.CENTER_VERTICAL);
            loading.setPadding(dp(4), 0, dp(4), 0);
            ProgressBar progress = new ProgressBar(this);
            loading.addView(progress, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView loadingText = label("গানের তালিকা প্রস্তুত হচ্ছে…", 11, MUTED, false);
            LinearLayout.LayoutParams loadingTextParams = new LinearLayout.LayoutParams(-2, -2);
            loadingTextParams.leftMargin = dp(8);
            loading.addView(loadingText, loadingTextParams);
            body.addView(loading, matchWrap());
            homeLoadingIndicator = loading;
            addSpace(body, 10);
        }

        if (catalogReady && !lastSongId.isEmpty()) {
            Song last = byId(lastSongId);
            if (last != null) {
                LinearLayout continueCard = cardColumn();
                continueCard.setPadding(dp(16), dp(14), dp(16), dp(14));
                TextView kicker = label("পড়তে থাকুন", 12, GREEN, true);
                TextView title = label(last.title, 17, TEXT, true);
                TextView meta = label(volumeNames.get(last.volume - 1) + "  ·  গান " + bn(last.number), 12, MUTED, false);
                continueCard.addView(kicker); addSpace(continueCard, 4); continueCard.addView(title); addSpace(continueCard, 4); continueCard.addView(meta);
                continueCard.setOnClickListener(v -> openSong(last));
                body.addView(continueCard, matchWrap());
                addSpace(body, 18);
            }
        }
        List<Song> recentToShow = new ArrayList<>();
        if (catalogReady) for (String rid : recentIds) {
            Song rs = byId(rid);
            if (rs == null || lastSongId.equals(rid)) continue;
            recentToShow.add(rs);
            if (recentToShow.size() >= 4) break;
        }
        if (!recentToShow.isEmpty()) {
            body.addView(label("সম্প্রতি পড়া গান", 17, TEXT, true));
            addSpace(body, 8);
            int shown = 0;
            for (Song recent : recentToShow) {
                LinearLayout recentCard = cardRow();
                recentCard.addView(label(recent.title, 14, TEXT, true), new LinearLayout.LayoutParams(0,-2,1f));
                recentCard.addView(label("›", 23, GREEN, true));
                recentCard.setOnClickListener(v -> openSong(recent));
                body.addView(recentCard, matchWrap());
                addSpace(body, 7);
                if (++shown >= 4) break;
            }
            if (shown > 0) addSpace(body, 12);
        }

        TextView section = label("গান খুঁজুন", 18, TEXT, true);
        body.addView(section);
        addSpace(body, 10);
        EditText search = searchField("গানের নাম বা গানের কোনো লাইন লিখুন");
        activeSearch = search;
        body.addView(searchInputRow(search), matchWrap());
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        search.addTextChangedListener(watcher(text -> {
            if (!text.isEmpty()) homeHasInteracted = true;
            renderSearchResults(results, text, 0);
        }));
        addSpace(body, 18);

        body.addView(label("তিনটি খণ্ড", 18, TEXT, true));
        addSpace(body, 10);
        for (int v = 1; v <= 3; v++) {
            final int volume = v;
            LinearLayout volumeCard = cardRow();
            TextView number = badge("০" + bn(v), SOFT_GREEN, GREEN_DARK);
            volumeCard.addView(number, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12), 0, 0, 0);
            info.addView(label(volumeNames.get(v - 1), 16, TEXT, true));
            addSpace(info, 3);
            int count = catalogReady ? countInVolume(v) : new int[]{0, 82, 289, 244}[v];
            info.addView(label(count + "টি গান", 12, MUTED, false));
            volumeCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            volumeCard.addView(label("›", 26, GREEN, true));
            volumeCard.setOnClickListener(view -> {
                homeHasInteracted = true;
                if (!catalogReady) {
                    Toast.makeText(this, "গানের তালিকা প্রস্তুত হচ্ছে…", Toast.LENGTH_SHORT).show();
                    return;
                }
                renderVolume(volume);
            });
            body.addView(volumeCard, matchWrap());
            addSpace(body, 9);
        }
        addSpace(body, 10);
        body.addView(label("দ্রুত প্রবেশ", 18, TEXT, true));
        addSpace(body, 10);
        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        TextView fav = quickTile("♡", "প্রিয় গান", favorites.size());
        TextView marks = quickTile("▣", "বুকমার্ক", bookmarks.size());
        quick.addView(fav, new LinearLayout.LayoutParams(0, dp(86), 1f));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(0, dp(86), 1f); markLp.leftMargin = dp(9); quick.addView(marks, markLp);
        fav.setOnClickListener(v -> {
            homeHasInteracted = true;
            if (catalogReady) renderSaved("favorites");
            else Toast.makeText(this, "গানের তালিকা প্রস্তুত হচ্ছে…", Toast.LENGTH_SHORT).show();
        });
        marks.setOnClickListener(v -> {
            homeHasInteracted = true;
            if (catalogReady) renderSaved("bookmarks");
            else Toast.makeText(this, "গানের তালিকা প্রস্তুত হচ্ছে…", Toast.LENGTH_SHORT).show();
        });
        body.addView(quick);

        addSpace(body, 16);
        LinearLayout updateCard = cardRow();
        updateCard.addView(label("↻", 24, GREEN, true),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout updateInfo = new LinearLayout(this);
        updateInfo.setOrientation(LinearLayout.VERTICAL);
        updateInfo.addView(label("অ্যাপ আপডেট", 15, TEXT, true));
        updateInfo.addView(label("নতুন সংস্করণ খুঁজুন", 12, MUTED, false));
        updateCard.addView(updateInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        updateCard.addView(label("›", 24, GREEN, true));
        updateCard.setOnClickListener(v -> {
            homeHasInteracted = true;
            checkForAppUpdates(true);
        });
        body.addView(updateCard, matchWrap());

        addSpace(body, 8);
        LinearLayout backupCard = cardRow();
        backupCard.addView(label("⇄", 23, GREEN, true),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout backupInfo = new LinearLayout(this);
        backupInfo.setOrientation(LinearLayout.VERTICAL);
        backupInfo.addView(label("ডেটা ব্যাকআপ ও পুনরুদ্ধার", 14, TEXT, true));
        backupInfo.addView(label("প্রিয় গান, বুকমার্ক ও পড়ার অবস্থান", 11, MUTED, false));
        backupCard.addView(backupInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        backupCard.addView(label("›", 24, GREEN, true));
        backupCard.setOnClickListener(v -> {
            homeHasInteracted = true;
            if (catalogReady) showUserDataBackupOptions();
            else Toast.makeText(this, "গানের তালিকা প্রস্তুত হচ্ছে…", Toast.LENGTH_SHORT).show();
        });
        body.addView(backupCard, matchWrap());

        addSpace(body, 8);
        LinearLayout feedbackCard = cardRow();
        feedbackCard.addView(label("✎", 23, GREEN, true),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout feedbackInfo = new LinearLayout(this);
        feedbackInfo.setOrientation(LinearLayout.VERTICAL);
        feedbackInfo.addView(label("সমস্যা জানান / মতামত দিন", 14, TEXT, true));
        int pendingReports = loadPendingFeedbackReports().length();
        feedbackInfo.addView(label(pendingReports == 0 ? "সমস্যা, ধীরগতি বা পরামর্শ" :
                bn(pendingReports) + "টি রিপোর্ট এই ফোনে রাখা আছে", 11, MUTED, false));
        feedbackCard.addView(feedbackInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        feedbackCard.addView(label("›", 24, GREEN, true));
        feedbackCard.setOnClickListener(v -> renderFeedbackForm());
        body.addView(feedbackCard, matchWrap());
    }

    private void renderFeedbackForm() {
        currentScreen = "feedback";
        mount("সমস্যা জানান / মতামত দিন", "আপনার অভিজ্ঞতা দিয়ে অ্যাপ উন্নত করুন", true, true);
        ScrollView scroll = pageScroll();
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        body.setPadding(dp(18), dp(8), dp(18), dp(24));
        TextView explanation = label("রিপোর্ট এখন শুধু এই ফোনে সংরক্ষিত হবে; কোনো সার্ভারে পাঠানো হবে না। পরে শেয়ার করতে পারবেন।",
                13, MUTED, false);
        body.addView(explanation, matchWrap());
        addSpace(body, 14);
        body.addView(label("আপনি কী জানাতে চান?", 15, TEXT, true));
        addSpace(body, 6);
        Spinner category = new Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, FEEDBACK_CATEGORIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        body.addView(category, matchHeight(50));
        addSpace(body, 12);
        body.addView(label("সমস্যা বা পরামর্শটি লিখুন", 15, TEXT, true));
        addSpace(body, 6);
        EditText description = new EditText(this);
        description.setTextSize(15);
        description.setTextColor(TEXT);
        description.setHintTextColor(MUTED);
        description.setHint("কী করতে চেয়েছিলেন, কী ঘটেছে, আর কী আশা করেছিলেন—সহজ করে লিখুন।");
        description.setGravity(Gravity.TOP | Gravity.START);
        description.setMinLines(5);
        description.setPadding(dp(14), dp(12), dp(14), dp(12));
        description.setBackground(roundStroke(SURFACE, BORDER, 14));
        description.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        description.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        body.addView(description, matchWrap());
        addSpace(body, 8);
        CheckBox includeDevice = new CheckBox(this);
        includeDevice.setText("অ্যাপের সংস্করণ ও ফোনের তথ্য যোগ করুন (ঐচ্ছিক)");
        includeDevice.setTextColor(TEXT);
        body.addView(includeDevice, matchWrap());
        CheckBox includeDiagnostics = new CheckBox(this);
        includeDiagnostics.setText("সাম্প্রতিক পারফরম্যান্স ও ক্র্যাশ তথ্য যোগ করুন (ঐচ্ছিক)");
        includeDiagnostics.setTextColor(TEXT);
        body.addView(includeDiagnostics, matchWrap());
        addSpace(body, 4);
        body.addView(label("মাইক্রোফোনের রেকর্ডিং, ব্যক্তিগত ফাইল বা পড়ার ইতিহাস যোগ হয় না। পারফরম্যান্স/ক্র্যাশ তথ্য শুধু আপনি বাছলে রিপোর্টে যুক্ত হয়।",
                12, MUTED, false), matchWrap());
        addSpace(body, 14);
        TextView save = navButton("এই ফোনে রিপোর্ট রাখুন");
        body.addView(save, matchHeight(50));
        addSpace(body, 8);
        TextView pending = navButton("সংরক্ষিত রিপোর্ট দেখুন ("
                + bn(loadPendingFeedbackReports().length()) + ")");
        body.addView(pending, matchHeight(46));
        pending.setOnClickListener(v -> showPendingFeedbackReports());
        save.setOnClickListener(v -> saveFeedbackReport(category.getSelectedItemPosition(),
                description.getText().toString(), includeDevice.isChecked(),
                includeDiagnostics.isChecked(), pending));
    }

    private void saveFeedbackReport(int categoryIndex, String description,
                                   boolean includeDevice, boolean includeDiagnostics,
                                   TextView pendingButton) {
        String message = description == null ? "" : description.trim();
        if (message.isEmpty()) {
            Toast.makeText(this, "রিপোর্টে কী ঘটেছে তা লিখুন।", Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray queue = loadPendingFeedbackReports();
        if (queue.length() >= MAX_PENDING_FEEDBACK) {
            Toast.makeText(this, "সংরক্ষিত রিপোর্টের সীমা পূর্ণ। পুরোনো রিপোর্ট শেয়ার করে জায়গা খালি করুন।",
                    Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject report = new JSONObject();
        try {
            report.put("schemaVersion", 1);
            report.put("id", UUID.randomUUID().toString());
            report.put("createdAtMs", System.currentTimeMillis());
            int safeCategory = Math.max(0, Math.min(categoryIndex, FEEDBACK_CATEGORIES.length - 1));
            report.put("category", FEEDBACK_CATEGORIES[safeCategory]);
            report.put("description", message);
            if (includeDevice) report.put("deviceDetails", getFeedbackDeviceDetails());
            if (includeDiagnostics) report.put("diagnostics", loadFeedbackDiagnostics());
            queue.put(report);
            prefs.edit().putString(FEEDBACK_OUTBOX_KEY, queue.toString()).apply();
        } catch (Exception error) {
            Toast.makeText(this, "রিপোর্টটি সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।",
                    Toast.LENGTH_LONG).show();
            Log.w("AshkeFeedback", "Could not save local report", error);
            return;
        }
        pendingButton.setText("সংরক্ষিত রিপোর্ট দেখুন (" + bn(queue.length()) + ")");
        new AlertDialog.Builder(this)
                .setTitle("রিপোর্ট ফোনে সংরক্ষিত")
                .setMessage("এটি কোথাও পাঠানো হয়নি। চাইলে এখন শেয়ার করুন, অথবা পরে সংরক্ষিত রিপোর্ট থেকে খুলুন।")
                .setNeutralButton("শেয়ার করুন", (dialog, which) -> shareFeedbackReport(report))
                .setPositiveButton("ঠিক আছে", null)
                .show();
    }

    private JSONArray loadPendingFeedbackReports() {
        try {
            return new JSONArray(prefs == null ? "[]"
                    : prefs.getString(FEEDBACK_OUTBOX_KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private JSONArray loadRecentPerformanceEvents() {
        try {
            return new JSONArray(prefs.getString(PERFORMANCE_EVENTS_KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private JSONArray loadFeedbackDiagnostics() {
        JSONArray diagnostics = loadRecentPerformanceEvents();
        String crash = prefs.getString(LAST_CRASH_DIAGNOSTIC_KEY, "");
        if (crash != null && !crash.isEmpty()) diagnostics.put("lastCrash: " + crash);
        return diagnostics;
    }

    private void installCrashDiagnosticHandler() {
        if (crashDiagnosticHandlerInstalled) return;
        synchronized (MainActivity.class) {
            if (crashDiagnosticHandlerInstalled) return;
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Context applicationContext = getApplicationContext();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    StringBuilder diagnostic = new StringBuilder();
                    diagnostic.append("timeMs=").append(System.currentTimeMillis())
                            .append(", thread=").append(thread == null ? "unknown" : thread.getName())
                            .append(", type=").append(error == null ? "unknown" : error.getClass().getName());
                    if (error != null) {
                        StackTraceElement[] frames = error.getStackTrace();
                        for (int i = 0; i < Math.min(frames.length, 24); i++) {
                            diagnostic.append("\n at ").append(frames[i].toString());
                        }
                    }
                    applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(LAST_CRASH_DIAGNOSTIC_KEY, diagnostic.toString()).commit();
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            });
            crashDiagnosticHandlerInstalled = true;
        }
    }

    private String getFeedbackDeviceDetails() {
        String versionName = "unknown";
        long versionCode = -1L;
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            versionName = info.versionName == null ? "unknown" : info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { }
        return "app=" + versionName + " (" + versionCode + "), android="
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), device="
                + Build.MANUFACTURER + " " + Build.MODEL;
    }

    private void showPendingFeedbackReports() {
        JSONArray queue = loadPendingFeedbackReports();
        if (queue.length() == 0) {
            Toast.makeText(this, "এখনো কোনো রিপোর্ট সংরক্ষণ করা হয়নি।", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[queue.length()];
        for (int i = 0; i < queue.length(); i++) {
            JSONObject report = queue.optJSONObject(i);
            if (report == null) {
                labels[i] = "অসম্পূর্ণ রিপোর্ট";
                continue;
            }
            String summary = report.optString("description", "").replace('\n', ' ').trim();
            if (summary.length() > 70) summary = summary.substring(0, 67) + "…";
            labels[i] = report.optString("category", "রিপোর্ট") + "\n" + summary;
        }
        new AlertDialog.Builder(this)
                .setTitle("এই ফোনে রাখা রিপোর্ট")
                .setItems(labels, (dialog, index) -> {
                    JSONObject report = queue.optJSONObject(index);
                    if (report != null) showPendingFeedbackReport(report);
                })
                .setNegativeButton("বন্ধ", null)
                .show();
    }

    private void showPendingFeedbackReport(JSONObject report) {
        StringBuilder details = new StringBuilder(report.optString("description", ""));
        if (report.has("deviceDetails")) {
            details.append("\n\n").append(report.optString("deviceDetails", ""));
        }
        JSONArray diagnostics = report.optJSONArray("diagnostics");
        if (diagnostics != null && diagnostics.length() > 0) {
            details.append("\n\nপারফরম্যান্স তথ্য:");
            for (int i = 0; i < diagnostics.length(); i++) {
                details.append("\n• ").append(diagnostics.optString(i, ""));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(report.optString("category", "রিপোর্ট"))
                .setMessage(details.toString())
                .setNeutralButton("শেয়ার করুন", (dialog, which) -> shareFeedbackReport(report))
                .setNegativeButton("মুছুন", (dialog, which) -> confirmDeleteFeedbackReport(report))
                .setPositiveButton("বন্ধ", null)
                .show();
    }

    private void confirmDeleteFeedbackReport(JSONObject report) {
        new AlertDialog.Builder(this)
                .setTitle("সংরক্ষিত রিপোর্ট মুছবেন?")
                .setMessage("মুছে দিলে এই রিপোর্ট ফোন থেকে সরানো হবে।")
                .setNegativeButton("রাখুন", null)
                .setPositiveButton("মুছুন", (dialog, which) -> {
                    String id = report.optString("id", "");
                    JSONArray queue = loadPendingFeedbackReports();
                    JSONArray remaining = new JSONArray();
                    for (int i = 0; i < queue.length(); i++) {
                        JSONObject item = queue.optJSONObject(i);
                        if (item != null && !id.equals(item.optString("id", ""))) remaining.put(item);
                    }
                    prefs.edit().putString(FEEDBACK_OUTBOX_KEY, remaining.toString()).apply();
                    Toast.makeText(this, "রিপোর্টটি মুছে ফেলা হয়েছে।", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void shareFeedbackReport(JSONObject report) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "এশকে ছাদেক অ্যাপের রিপোর্ট: "
                    + report.optString("category", "মতামত"));
            share.putExtra(Intent.EXTRA_TEXT, formatFeedbackReport(report));
            startActivity(Intent.createChooser(share, "রিপোর্টটি কোথায় শেয়ার করবেন?"));
        } catch (Exception error) {
            Toast.makeText(this, "শেয়ার করার অ্যাপ পাওয়া যায়নি। রিপোর্টটি এই ফোনে রাখা আছে।",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String formatFeedbackReport(JSONObject report) {
        StringBuilder text = new StringBuilder("এশকে ছাদেক অ্যাপের রিপোর্ট\n")
                .append("ধরন: ").append(report.optString("category", ""))
                .append("\nবিবরণ: ").append(report.optString("description", ""));
        if (report.has("deviceDetails")) {
            text.append("\n\nঅ্যাপ ও ফোন: ").append(report.optString("deviceDetails", ""));
        }
        JSONArray diagnostics = report.optJSONArray("diagnostics");
        if (diagnostics != null && diagnostics.length() > 0) {
            text.append("\n\nঐচ্ছিক পারফরম্যান্স তথ্য:");
            for (int i = 0; i < diagnostics.length(); i++) {
                text.append("\n- ").append(diagnostics.optString(i, ""));
            }
        }
        return text.toString();
    }

    private void showUserDataBackupOptions() {
        new AlertDialog.Builder(this)
                .setTitle("ডেটা ব্যাকআপ ও পুনরুদ্ধার")
                .setMessage("অ্যাপ বদলানো বা নতুন করে ইনস্টল করার আগে প্রিয় গান, বুকমার্ক, সাম্প্রতিক গান এবং পড়ার অবস্থান JSON ফাইলে সংরক্ষণ করুন।")
                .setPositiveButton("ব্যাকআপ তৈরি", (d, which) -> requestUserDataExport())
                .setNeutralButton("ব্যাকআপ পুনরুদ্ধার", (d, which) -> requestUserDataImport())
                .setNegativeButton("বন্ধ", null)
                .show();
    }

    private void requestUserDataExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "Ashke-Shadek-User-Data-Backup.json");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_USER_DATA);
        } catch (Exception error) {
            Toast.makeText(this, "ফাইল সংরক্ষণের জায়গা খোলা যায়নি।", Toast.LENGTH_LONG).show();
        }
    }

    private void requestUserDataImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_USER_DATA);
        } catch (Exception error) {
            Toast.makeText(this, "ব্যাকআপ ফাইল বেছে নেওয়া যায়নি।", Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject createUserDataBackup() throws Exception {
        JSONObject backup = new JSONObject();
        backup.put("format", USER_DATA_BACKUP_FORMAT);
        backup.put("schemaVersion", 1);
        backup.put("packageName", getPackageName());

        JSONArray favoriteIds = new JSONArray();
        for (String id : favorites) favoriteIds.put(id);
        backup.put("favorites", favoriteIds);

        JSONArray bookmarkIds = new JSONArray();
        for (String id : bookmarks) bookmarkIds.put(id);
        backup.put("bookmarks", bookmarkIds);

        JSONArray recent = new JSONArray();
        for (String id : recentIds) recent.put(id);
        backup.put("recent", recent);
        backup.put("lastSongId", lastSongId == null ? "" : lastSongId);

        JSONObject scrollPositions = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!key.startsWith("scroll_") || !(value instanceof Number)) continue;
            String songId = key.substring("scroll_".length());
            if (byId(songId) == null) continue;
            long position = ((Number) value).longValue();
            if (position >= 0 && position <= 5_000_000L) {
                scrollPositions.put(songId, position);
            }
        }
        backup.put("scrollPositions", scrollPositions);
        return backup;
    }

    private Set<String> validBackupSongIds(JSONArray ids) {
        Set<String> valid = new LinkedHashSet<>();
        if (ids == null) return valid;
        for (int i = 0; i < ids.length() && valid.size() < songs.size(); i++) {
            String id = ids.optString(i, "");
            if (byId(id) != null) valid.add(id);
        }
        return valid;
    }

    private List<String> validBackupRecent(JSONArray ids) {
        List<String> valid = new ArrayList<>();
        if (ids == null) return valid;
        for (int i = 0; i < ids.length() && valid.size() < 12; i++) {
            String id = ids.optString(i, "");
            if (byId(id) != null && !valid.contains(id)) valid.add(id);
        }
        return valid;
    }

    private void writeUserDataBackup(Uri destination) throws Exception {
        JSONObject backup = createUserDataBackup();
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("ব্যাকআপ ফাইল খোলা যায়নি।");
            output.write(backup.toString(2).getBytes("UTF-8"));
            output.flush();
        }
    }

    private void restoreUserDataBackup(Uri source) throws Exception {
        JSONObject backup;
        try (InputStream input = getContentResolver().openInputStream(source);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("ব্যাকআপ ফাইল খোলা যায়নি।");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 256 * 1024) {
                    throw new IllegalStateException("ব্যাকআপ ফাইলটি অস্বাভাবিক বড়।");
                }
                bytes.write(buffer, 0, count);
            }
            backup = new JSONObject(bytes.toString("UTF-8"));
        }

        if (!USER_DATA_BACKUP_FORMAT.equals(backup.optString("format"))
                || backup.optInt("schemaVersion", -1) != 1
                || !getPackageName().equals(backup.optString("packageName"))) {
            throw new IllegalArgumentException("এটি এশকে ছাদেকের সমর্থিত ব্যাকআপ ফাইল নয়।");
        }

        Set<String> importedFavorites = validBackupSongIds(backup.optJSONArray("favorites"));
        Set<String> importedBookmarks = validBackupSongIds(backup.optJSONArray("bookmarks"));
        List<String> importedRecent = validBackupRecent(backup.optJSONArray("recent"));
        String importedLast = backup.optString("lastSongId", "");
        if (byId(importedLast) == null) importedLast = "";

        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet("favorites", new HashSet<>(importedFavorites));
        editor.putStringSet("bookmarks", new HashSet<>(importedBookmarks));
        editor.putString("last_song", importedLast);
        editor.putString("recent", joinRecent(importedRecent));

        JSONObject scroll = backup.optJSONObject("scrollPositions");
        if (scroll != null) {
            Iterator<String> keys = scroll.keys();
            while (keys.hasNext()) {
                String songId = keys.next();
                if (byId(songId) == null) continue;
                long position = scroll.optLong(songId, -1L);
                if (position >= 0 && position <= 5_000_000L) {
                    editor.putInt("scroll_" + songId, (int) position);
                }
            }
        }
        editor.apply();

        favorites = new LinkedHashSet<>(importedFavorites);
        bookmarks = new LinkedHashSet<>(importedBookmarks);
        recentIds = importedRecent;
        lastSongId = importedLast;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT_USER_DATA) {
                writeUserDataBackup(uri);
                Toast.makeText(this, "আপনার ডেটার ব্যাকআপ সংরক্ষণ করা হয়েছে।",
                        Toast.LENGTH_LONG).show();
            } else if (requestCode == REQUEST_IMPORT_USER_DATA) {
                restoreUserDataBackup(uri);
                Toast.makeText(this, "প্রিয় গান, বুকমার্ক ও পড়ার অবস্থান পুনরুদ্ধার হয়েছে।",
                        Toast.LENGTH_LONG).show();
                renderHome();
            }
        } catch (Exception error) {
            String message = error.getMessage();
            Toast.makeText(this, message == null ? "ব্যাকআপ প্রক্রিয়া সম্পন্ন হয়নি।" : message,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void checkForAppUpdates(boolean userInitiated) {
        if (userInitiated) {
            Toast.makeText(this, "নতুন সংস্করণ খোঁজা হচ্ছে…", Toast.LENGTH_SHORT).show();
        }
        AppUpdater.checkForUpdates(this, new AppUpdater.CheckCallback() {
            @Override public void onUpdateAvailable(AppUpdater.UpdateInfo update) {
                prefs.edit().putLong("last_update_check_ms", System.currentTimeMillis()).apply();
                long dismissed = prefs.getLong("dismissed_update_version", 0L);
                if (!userInitiated && dismissed == update.versionCode) return;
                showUpdateAvailable(update);
            }

            @Override public void onNoUpdate() {
                prefs.edit().putLong("last_update_check_ms", System.currentTimeMillis()).apply();
                if (userInitiated) {
                    Toast.makeText(MainActivity.this, "আপনার অ্যাপটি সর্বশেষ সংস্করণে আছে।",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override public void onError(String message) {
                Log.i("AshkeUpdater", "Update check failed: " + message);
                if (userInitiated) showUpdateCheckUnavailable(message);
            }
        });
    }

    private void showUpdateCheckUnavailable(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("আপডেট পরীক্ষা করা যাচ্ছে না")
                .setMessage((reason == null || reason.trim().isEmpty()
                        ? "কারণ জানা যায়নি।" : reason.trim())
                        + "\n\nইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন। "
                        + "আপনার গান পড়া ও অন্যান্য অফলাইন সুবিধা ব্যবহার করতে পারবেন।")
                .setPositiveButton("আবার চেষ্টা করুন", (dialog, which) -> checkForAppUpdates(true))
                .setNegativeButton("ঠিক আছে", null)
                .show();
    }

    private void showUpdateAvailable(AppUpdater.UpdateInfo update) {
        StringBuilder message = new StringBuilder();
        message.append("সংস্করণ ").append(update.versionName).append(" পাওয়া গেছে।");
        if (!update.releaseNotes.trim().isEmpty()) {
            message.append("\n\n").append(update.releaseNotes.trim());
        }
        if (update.requiresMigration) {
            message.append("\n\nএটি বর্তমান ইনস্টল করা অ্যাপের সাইনিং সার্টিফিকেটে সই করা নয়। "
                    + "একবারের মাইগ্রেশন প্রয়োজন; পুরোনো অ্যাপ আনইনস্টল করলে স্থানীয় প্রিয় গান ও বুকমার্ক মুছে যেতে পারে।");
        } else {
            message.append("\n\nAPK যাচাই করে Android-এর ইনস্টল নিশ্চিতকরণে আপডেট হবে। "
                    + "একই সাইনিং কী থাকলে আপনার প্রিয় গান ও বুকমার্ক থাকবে।");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("নতুন আপডেট")
                .setMessage(message.toString())
                .setNegativeButton("পরে", (d, which) ->
                        prefs.edit().putLong("dismissed_update_version", update.versionCode).apply());
        if (update.requiresMigration) {
            builder.setNeutralButton("ডেটা ব্যাকআপ", (d, which) -> requestUserDataExport());
            builder.setPositiveButton("রিলিজ পেজ খুলুন", (d, which) -> openUpdateReleasePage(update));
        } else {
            builder.setPositiveButton("ডাউনলোড ও ইনস্টল", (d, which) ->
                    AppUpdater.downloadAndInstall(MainActivity.this, update,
                            new AppUpdater.InstallCallback() {
                @Override public void onStatus(String text) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }
                @Override public void onPermissionRequired() {
                    Toast.makeText(MainActivity.this,
                            "Android সেটিংসে এই উৎস থেকে অ্যাপ ইনস্টল করার অনুমতি দিন; ফিরে এলে আপডেট শুরু হবে।",
                            Toast.LENGTH_LONG).show();
                }
                @Override public void onMigrationRequired(String reason) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("একবার নতুন করে ইনস্টল প্রয়োজন")
                            .setMessage(reason + "\n\nরিলিজ পেজ থেকে মাইগ্রেশন APK ইনস্টল করুন।")
                            .setPositiveButton("রিলিজ পেজ", (ignored, button) -> openUpdateReleasePage(update))
                            .setNegativeButton("এখন নয়", null)
                            .show();
                }
                @Override public void onError(String error) {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }));
        }
        builder.show();
    }

    private void openUpdateReleasePage(AppUpdater.UpdateInfo update) {
        try {
            String url = update.releasePageUrl;
            if (url == null || !url.startsWith(
                    "https://github.com/aamiqram/Ashke-Sadek---Android-project/releases/")) {
                url = "https://github.com/aamiqram/Ashke-Sadek---Android-project/releases";
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(this, "রিলিজ পেজ খোলা যায়নি।", Toast.LENGTH_LONG).show();
        }
    }

    private void renderVolume(int volume) {
        currentScreen = "volume";
        mount(volumeNames.get(volume - 1), countInVolume(volume) + "টি গান", true, true);
        ScrollView scroll = pageScroll();
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        body.setPadding(dp(18), dp(4), dp(18), dp(18));
        EditText search = searchField("এই খণ্ডে গান খুঁজুন"); activeSearch = search;
        body.addView(searchInputRow(search), matchWrap());
        addSpace(body, 10);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        renderSearchResults(results, "", volume);
        search.addTextChangedListener(watcher(text -> renderSearchResults(results, text, volume)));
    }

    private void renderIndex() {
        currentScreen = "index";
        mount("গানের সূচী", "খণ্ড বেছে নিয়ে গান পড়ুন", false, true);
        ScrollView scroll=pageScroll(); LinearLayout body=(LinearLayout)scroll.getChildAt(0); body.setPadding(dp(18),dp(6),dp(18),dp(72));
        EditText search=searchField("সব খণ্ডে গান খুঁজুন"); body.addView(searchInputRow(search),matchWrap()); activeSearch=search; addSpace(body,10);
        LinearLayout results=new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        renderSearchResults(results,"",0);
        search.addTextChangedListener(watcher(q -> renderSearchResults(results,q,0)));
    }

    private void renderSaved(String type) {
        currentScreen = type;
        boolean isFav = type.equals("favorites");
        mount(isFav ? "প্রিয় গান" : "বুকমার্ক", isFav ? favorites.size()+"টি সংরক্ষিত গান" : bookmarks.size()+"টি বুকমার্ক", true, true);
        ScrollView scroll=pageScroll(); LinearLayout body=(LinearLayout)scroll.getChildAt(0); body.setPadding(dp(18),dp(6),dp(18),dp(72));
        Set<String> ids=isFav?favorites:bookmarks;
        if(ids.isEmpty()) {
            TextView empty=label(isFav?"এখনো কোনো প্রিয় গান যোগ করা হয়নি।":"এখনো কোনো বুকমার্ক নেই।",15,MUTED,false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0,dp(50),0,dp(30)); body.addView(empty,matchWrap());
            TextView hint=label(isFav?"গান পড়ার সময় ♡ চাপলে এখানে পাবেন।":"গান পড়ার সময় বুকমার্ক চিহ্ন চাপলে এখানে পাবেন।",13,MUTED,false); hint.setGravity(Gravity.CENTER); body.addView(hint,matchWrap());
            return;
        }
        List<Song> ordered=new ArrayList<>();
        for(String id:ids){Song s=byId(id);if(s!=null)ordered.add(s);}
        Collections.sort(ordered, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                if (a.volume != b.volume) return a.volume - b.volume;
                return a.number - b.number;
            }
        });
        int firstBatch = Math.min(ordered.size(), SONG_BATCH_SIZE);
        addSongRows(body, ordered.subList(0, firstBatch));
        if (firstBatch < ordered.size()) addMoreResults(body, ordered, firstBatch);
    }

    private void renderSearchResults(LinearLayout target, String query, int volumeFilter) {
        target.removeAllViews();
        activeSearchResults = target;
        activeSearchQuery = query == null ? "" : query;
        activeSearchVolumeFilter = volumeFilter;
        List<Song> matches = new ArrayList<>();
        java.util.Map<String, Integer> scores = new java.util.HashMap<>();
        String q = normalize(query);
        SearchQuery preparedQuery = prepareSearchQuery(query);
        Song direct = q.isEmpty() ? null : resolveDirectJump(query, volumeFilter > 0 ? volumeFilter : 0);
        if (direct != null && volumeFilter > 0 && direct.volume != volumeFilter) direct = null;
        if (direct != null) {
            matches.add(direct);
        } else {
            if (!q.isEmpty() && !searchIndexReady) {
                TextView preparing = label("গানের খোঁজ প্রস্তুত হচ্ছে…", 14, MUTED, false);
                preparing.setPadding(dp(6), dp(18), dp(6), dp(18));
                target.addView(preparing);
                return;
            }
            for (Song song : songs) {
                if (volumeFilter > 0 && song.volume != volumeFilter) continue;
                int score = q.isEmpty() ? 1 : scoreSongMatch(song, preparedQuery);
                if (score > 0) {
                    matches.add(song);
                    scores.put(song.id, score);
                }
            }
            if (!q.isEmpty()) {
                Collections.sort(matches, (a, b) -> {
                    int scoreCompare = Integer.compare(scores.get(b.id), scores.get(a.id));
                    if (scoreCompare != 0) return scoreCompare;
                    if (a.volume != b.volume) return Integer.compare(a.volume, b.volume);
                    return Integer.compare(a.number, b.number);
                });
            }
        }
        if (query.trim().isEmpty() && volumeFilter == 0 && !currentScreen.equals("index")) return;
        if (matches.isEmpty()) {
            TextView none = label("কোনো গান পাওয়া যায়নি। অন্য শব্দ দিয়ে খুঁজুন।", 14, MUTED, false);
            none.setPadding(dp(6), dp(18), dp(6), dp(18));
            target.addView(none);
            return;
        }
        TextView count = label(matches.size() + "টি সম্ভাব্য গান পাওয়া গেছে", 11, MUTED, false);
        count.setPadding(dp(4), dp(5), dp(4), dp(6));
        target.addView(count);
        int firstBatch = Math.min(matches.size(), SONG_BATCH_SIZE);
        addSongRows(target, matches.subList(0, firstBatch));
        if (firstBatch < matches.size()) addMoreResults(target, matches, firstBatch);
    }

    /** Reuses a compact catalogue index instead of normalizing all 615 lyric corpora per keystroke. */
    private void buildSearchIndex() {
        searchIndex.clear();
        for (Song song : songs) {
            String corpus = song.title + " " + song.lyrics;
            String modern = modernizeOldBengali(corpus);
            searchIndex.put(song.id, new SearchDocument(normalize(corpus), normalize(modern),
                    phoneticNormalize(corpus), normalize(song.title),
                    searchWords(corpus), searchWords(modern)));
        }
    }

    private SearchQuery prepareSearchQuery(String value) {
        String original = value == null ? "" : value;
        LinkedHashSet<String> uniqueTerms = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(original);
        while (matcher.find()) {
            String token = normalize(matcher.group());
            if (token.length() >= 2) uniqueTerms.add(token);
        }
        List<SearchTerm> terms = new ArrayList<>(uniqueTerms.size());
        for (String token : uniqueTerms) {
            String modern = normalize(modernizeOldBengali(token));
            terms.add(new SearchTerm(token, modern, phoneticNormalize(token)));
        }
        return new SearchQuery(normalize(original),
                normalize(modernizeOldBengali(original)),
                phoneticNormalize(original), terms);
    }

    private int scoreSongMatch(Song song, SearchQuery query) {
        return scoreSongMatch(song, query, false);
    }

    /**
     * Shared catalogue matcher for typed and spoken input. Query variants are
     * prepared once; the voice path applies stricter evidence rules so normal
     * conversation and short ASR fragments do not produce confident song hits.
     */
    private int scoreSongMatch(Song song, SearchQuery query, boolean voiceInput) {
        if (query.normalized.length() < (voiceInput ? 3 : 1)) return 0;
        SearchDocument doc = searchIndex.get(song.id);
        if (doc == null) return 0;

        // A short substring is not useful evidence: it creates many accidental
        // matches while someone is speaking. Keep typed autocomplete permissive.
        int phraseMinimum = voiceInput ? 5 : 3;
        // A long single spoken word can still occur incidentally in a lyric.
        // Require at least two distinct words before accepting phrase matches.
        if (query.normalized.length() >= phraseMinimum
                && (!voiceInput || query.terms.size() >= 2)) {
            if (doc.title.contains(query.normalized)) return 2400 + query.normalized.length();
            if (doc.normalized.contains(query.normalized)) return 1800 + query.normalized.length();
            if (!query.modern.equals(query.normalized) && doc.modern.contains(query.modern))
                return 1550 + query.modern.length();
            if (query.phonetic.length() >= phraseMinimum && doc.phonetic.contains(query.phonetic))
                return 1300 + query.phonetic.length();
        }
        if (query.terms.isEmpty()) return 0;
        // Two isolated common words are weak evidence. Short queries can still
        // qualify above when they form a contiguous lyric phrase.
        if (voiceInput && query.terms.size() < 3) return 0;

        int exact = 0, modern = 0, phonetic = 0, prefix = 0, fuzzy = 0;
        for (SearchTerm term : query.terms) {
            if (doc.words.contains(term.original)) exact++;
            else if (doc.modernWords.contains(term.original)
                    || doc.words.contains(term.modern)) modern++;
            else if (doc.phoneticWords.contains(term.phonetic)) phonetic++;
            else if (!voiceInput && term.original.length() >= 2
                    && hasPrefixMatch(term.original, doc.words, doc.modernWords)) prefix++;
            else if (!voiceInput && term.original.length() >= 4
                    && hasOneEditMatch(term.original, doc.words)) fuzzy++;
        }

        int matched = exact + modern + phonetic + prefix + fuzzy;
        if (matched == 0) return 0;
        int coverage = matched * 100 / query.terms.size();

        // Voice suggestions need corroboration from multiple recognized words.
        // A single long phrase can still qualify through the phrase checks above.
        int orderedMatches = 0;
        if (voiceInput) {
            int required = query.terms.size() == 1 ? 2
                    : Math.max(2, (query.terms.size() * 60 + 99) / 100);
            if (matched < required) return 0;

            // Matching words anywhere in a long song is not enough. The words
            // heard by the microphone should also occur in lyric order nearby.
            orderedMatches = Math.max(
                    countOrderedMatches(doc.normalized, query.terms, 0),
                    Math.max(countOrderedMatches(doc.modern, query.terms, 1),
                            countOrderedMatches(doc.phonetic, query.terms, 2)));
            int requiredInOrder = Math.max(2, (query.terms.size() + 1) / 2);
            if (orderedMatches < requiredInOrder) return 0;
        } else if (query.terms.size() >= 3 && matched < 2) {
            return 0;
        }

        int score = exact * 125 + modern * 105 + phonetic * 82
                + prefix * 54 + fuzzy * 38 + coverage;
        if (query.terms.size() >= 2 && matched == query.terms.size()) score += 100;
        if (coverage == 100) score += 25;
        if (voiceInput) score += orderedMatches * 35;
        return score;
    }

    /**
     * Counts how many recognized words occur in lyric order within a short
     * character window. Uses existing normalized strings, avoiding a second
     * per-song token list and its memory cost on low-RAM Android devices.
     */
    private int countOrderedMatches(String text, List<SearchTerm> terms, int form) {
        int cursor = 0;
        int matched = 0;
        for (SearchTerm term : terms) {
            String word = form == 1 ? term.modern
                    : form == 2 ? term.phonetic : term.original;
            int position = text.indexOf(word, cursor);
            if (position < 0 || position - cursor > 80) continue;
            matched++;
            cursor = position + word.length();
        }
        return matched;
    }

    /**
     * Sung audio garbles words, so the strict word/ordered matcher often rejects
     * the real song. This fallback scores character n-gram overlap between the
     * whole transcript and the lyrics. A small sampled sketch keeps the per-song
     * scan cheap; exact verification runs only for songs whose sketch looks
     * promising. Calibration: tests/calibrate_voice_fuzzy.py.
     */
    private static final int VOICE_GRAM_SIZE = 3;
    private static final int VOICE_GRAM_SKETCH_LIMIT = 256;
    /** Sketch recall gate, as a fraction of the lyric grams expected to match. */
    private static final double VOICE_FUZZY_SKETCH_GATE = 0.40;
    /**
     * Exact containment ratio a song must reach before it may be suggested.
     * Calibration (tests/calibrate_voice_fuzzy.py, seeded over the real
     * catalogue): 0.40 keeps 96–98% of lightly garbled sung transcripts and
     * about 35% of heavily garbled ones in the top suggestions, against only
     * ~0.13 stray conversation candidates per chatter window.
     */
    private static final double VOICE_FUZZY_VERIFY_RATIO = 0.40;
    /** Transcripts keep growing; only the tail window drives the fuzzy score. */
    private static final int VOICE_FUZZY_QUERY_LIMIT = 260;

    private int scoreVoiceFuzzyMatch(SearchDocument doc, SearchQuery query) {
        String original = fuzzyVoiceWindow(query.normalized);
        String modern = fuzzyVoiceWindow(query.modern);
        int grams = original.length() - VOICE_GRAM_SIZE + 1;
        if (doc.gramSketch.length == 0 || grams < 10) return 0;
        double sampleFraction = doc.gramSketch.length / (double) doc.gramCount;
        int sketchGate = Math.max(2, (int) Math.ceil(
                sampleFraction * grams * VOICE_FUZZY_SKETCH_GATE));
        int sketchHits = countGramsInSketch(original, doc.gramSketch);
        if (sketchHits < sketchGate) return 0;
        int verified = Math.max(
                countGramsInText(original, doc.normalized),
                countGramsInText(modern, doc.modern));
        double ratio = verified / (double) grams;
        if (ratio < VOICE_FUZZY_VERIFY_RATIO) return 0;
        return (int) (420 + ratio * 500);
    }

    private String fuzzyVoiceWindow(String text) {
        if (text.length() <= VOICE_FUZZY_QUERY_LIMIT) return text;
        return text.substring(text.length() - VOICE_FUZZY_QUERY_LIMIT);
    }

    private static long[] buildGramSketch(String normalized) {
        int count = normalized.length() - VOICE_GRAM_SIZE + 1;
        if (count <= 0) return new long[0];
        int keep = Math.min(count, VOICE_GRAM_SKETCH_LIMIT);
        long[] sketch = new long[keep];
        for (int i = 0; i < keep; i++) {
            int start = (int) ((long) i * count / keep);
            sketch[i] = gramHash(normalized, start, start + VOICE_GRAM_SIZE);
        }
        java.util.Arrays.sort(sketch);
        return sketch;
    }

    private static long gramHash(String text, int start, int end) {
        long hash = 0xcbf29ce484222325L;
        for (int i = start; i < end; i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean sketchContains(long[] sketch, long hash) {
        int low = 0, high = sketch.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long value = sketch[mid];
            if (value == hash) return true;
            if (value < hash) low = mid + 1; else high = mid - 1;
        }
        return false;
    }

    private static int countGramsInSketch(String query, long[] sketch) {
        int count = query.length() - VOICE_GRAM_SIZE + 1;
        if (count <= 0) return 0;
        int hits = 0;
        for (int i = 0; i < count; i++) {
            if (sketchContains(sketch, gramHash(query, i, i + VOICE_GRAM_SIZE))) hits++;
        }
        return hits;
    }

    private static int countGramsInText(String query, String text) {
        int count = query.length() - VOICE_GRAM_SIZE + 1;
        if (count <= 0) return 0;
        int hits = 0;
        for (int i = 0; i < count; i++) {
            if (text.indexOf(query.substring(i, i + VOICE_GRAM_SIZE)) >= 0) hits++;
        }
        return hits;
    }

    private boolean hasPrefixMatch(String query, Set<String> original, Set<String> modern) {
        for (String word : original) if (word.startsWith(query)) return true;
        for (String word : modern) if (word.startsWith(query)) return true;
        return false;
    }

    /** Search-only spelling bridge; displayed lyrics always remain in the original text. */
    private String modernizeOldBengali(String value) {
        String result = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
        for (int i = 0; i < OLD_BENGALI_ALIASES.length; i++) {
            Matcher matcher = OLD_BENGALI_ALIAS_PATTERNS[i].matcher(result);
            result = matcher.replaceAll(Matcher.quoteReplacement(OLD_BENGALI_ALIASES[i][1]));
        }
        return result;
    }

    private String phoneticNormalize(String value) {
        String result = normalize(value);
        return result.replace("শ", "স").replace("ষ", "স").replace("ণ", "ন")
                .replace("ী", "ি").replace("ঈ", "ই").replace("ূ", "ু")
                .replace("ঊ", "উ").replace("ৎ", "ত");
    }

    private Set<String> searchWords(String value) {
        Set<String> words = new HashSet<>();
        if (value == null) return words;
        Matcher matcher = WORD_PATTERN.matcher(value);
        while (matcher.find()) {
            String word = normalize(matcher.group());
            if (word.length() >= 2) words.add(word);
        }
        return words;
    }

    private boolean hasOneEditMatch(String query, Set<String> candidates) {
        for (String candidate : candidates) {
            if (Math.abs(candidate.length() - query.length()) > 1 || candidate.length() < 3) continue;
            if (oneEditAway(query, candidate)) return true;
        }
        return false;
    }

    private boolean oneEditAway(String first, String second) {
        int i = 0, j = 0, edits = 0;
        while (i < first.length() && j < second.length()) {
            if (first.charAt(i) == second.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (first.length() > second.length()) i++;
            else if (second.length() > first.length()) j++;
            else { i++; j++; }
        }
        if (i < first.length() || j < second.length()) edits++;
        return edits <= 1;
    }

    private void addSongRows(LinearLayout parent, List<Song> list) {
        for(Song song:list){
            LinearLayout item=cardRow();
            LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);
            left.addView(label(song.title,15,TEXT,true));
            TextView snippet = label(song.firstLine(), 12, MUTED, false);
            snippet.setMaxLines(2);
            snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);
            left.addView(snippet);
            addSpace(left,4);
            left.addView(label(volumeShort(song.volume)+"  ·  গান "+bn(song.number),11,MUTED,false));
            item.addView(left,new LinearLayout.LayoutParams(0,-2,1f));
            TextView arrow=label("›",25,GREEN,true); item.addView(arrow);
            item.setOnClickListener(v->openSong(song));
            parent.addView(item,matchWrap()); addSpace(parent,8);
        }
    }

    private void addMoreResults(LinearLayout parent, List<Song> matches, int from) {
        TextView more = navButton("আরও গান দেখুন  ↓");
        more.setPadding(dp(12), dp(10), dp(12), dp(10));
        parent.addView(more, matchWrap());
        addSpace(parent, 8);
        more.setOnClickListener(v -> {
            parent.removeView(more);
            int to = Math.min(matches.size(), from + SONG_BATCH_SIZE);
            addSongRows(parent, matches.subList(from, to));
            if (to < matches.size()) addMoreResults(parent, matches, to);
        });
    }

    private void openSong(Song song) {
        if (songSearchDialog != null) songSearchDialog.dismiss();
        saveReaderPosition();
        currentSong = song;
        rememberSong(song);
        renderReader(song);
    }

    private void rememberSong(Song song) {
        if (song == null) return;
        lastSongId = song.id;
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        recent.add(song.id);
        recent.addAll(recentIds);
        recentIds = new ArrayList<>();
        int added = 0;
        for (String id : recent) {
            if (added++ >= 12) break;
            recentIds.add(id);
        }
        prefs.edit().putString("last_song", lastSongId)
                .putString("recent", joinRecent(recentIds)).apply();
    }

    private void renderReader(Song song) {
        currentScreen = "reader";
        currentSong = song;
        mount("গান " + bn(song.number), volumeNames.get(song.volume - 1), true, false);
        readerHeaderTitle = root.findViewWithTag("screen-title");
        readerHeaderSubtitle = root.findViewWithTag("screen-subtitle");

        TextView headerSearch = iconButton("⌕", 22);
        headerSearch.setContentDescription("গান খুঁজুন");
        headerSearch.setOnClickListener(v -> showSongSearchDialog());
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        ((LinearLayout) root.getChildAt(0)).addView(headerSearch, hsLp);
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int availableWidth = Math.max(dp(168),
                getResources().getDisplayMetrics().widthPixels - dp(24));
        // Wider book column: the original printed lines average ~39 characters, so the
        // old 336dp column re-wrapped almost every printed line at a readable font.
        // 432dp holds a complete printed line on normal phones; narrower screens keep
        // a proportionally scaled font so the wrap points stay book-like everywhere.
        readerPageWidthPx = Math.min(availableWidth, dp(432));
        readerTextMeasurePx = Math.max(dp(132), readerPageWidthPx - dp(36));

        LinearLayout textSizeControls = new LinearLayout(this);
        textSizeControls.setOrientation(LinearLayout.HORIZONTAL);
        textSizeControls.setGravity(Gravity.CENTER_VERTICAL);
        textSizeControls.setPadding(dp(18), dp(5), dp(18), dp(5));
        textSizeControls.setBackgroundColor(SURFACE);
        TextView textSizeLabel = label("লেখার আকার", 12, MUTED, false);
        textSizeControls.addView(textSizeLabel, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView smallerText = navButton("ছোট −");
        TextView currentTextSize = label(bn(currentReaderTextSizeSp(screenWidthDp)), 12, MUTED, true);
        currentTextSize.setGravity(Gravity.CENTER);
        TextView largerText = navButton("বড় +");
        textSizeControls.addView(smallerText, new LinearLayout.LayoutParams(dp(64), dp(40)));
        textSizeControls.addView(currentTextSize, new LinearLayout.LayoutParams(dp(36), dp(40)));
        textSizeControls.addView(largerText, new LinearLayout.LayoutParams(dp(64), dp(40)));
        smallerText.setContentDescription("গানের লেখা ছোট করুন");
        largerText.setContentDescription("গানের লেখা বড় করুন");
        smallerText.setOnClickListener(v -> changeReaderTextSize(currentSong, -1));
        largerText.setOnClickListener(v -> changeReaderTextSize(currentSong, 1));
        page.addView(textSizeControls, matchWrap());

        readerList = new ReaderListView(this);
        readerList.setDivider(null);
        readerList.setDividerHeight(dp(6));
        readerList.setSelector(android.R.color.transparent);
        readerList.setCacheColorHint(BG);
        readerList.setClipToPadding(false);
        readerList.setPadding(0, dp(6), 0, dp(14));
        readerBookSongs = new ArrayList<>(songs);
        Collections.sort(readerBookSongs, (a, b) -> {
            if (a.volume != b.volume) return Integer.compare(a.volume, b.volume);
            return Integer.compare(a.number, b.number);
        });
        readerAdapter = new SongReaderAdapter();
        readerList.setAdapter(readerAdapter);
        readerList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) { }
            @Override public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                                           int visibleItemCount, int totalItemCount) {
                updateReaderSongFromScroll(firstVisibleItem);
            }
        });
        page.addView(readerList, new LinearLayout.LayoutParams(-1, 0, 1f));
        int initialPosition = readerPositionFor(song);
        int savedOffset = Math.max(0, prefs.getInt("scroll_" + song.id, 0));
        readerList.post(() -> {
            readerList.setSelection(initialPosition);
            readerList.post(() -> {
                int firstVisible = readerList.getFirstVisiblePosition();
                View row = readerList.getChildAt(initialPosition - firstVisible);
                int maxOffset = row == null ? 0
                        : Math.max(0, row.getHeight() - readerList.getHeight());
                readerList.setSelectionFromTop(initialPosition, -Math.min(savedOffset, maxOffset));
            });
        });
    }

    private final class ReaderListView extends ListView {
        ReaderListView(Context context) { super(context); }

        @Override public void fling(int velocityY) {
            // Keep one fast accidental swipe from racing through several songs.
            int limit = dp(1500);
            int safeVelocity = Math.max(-limit, Math.min(limit, velocityY));
            super.fling(safeVelocity);
        }
    }

    private final class SongReaderAdapter extends BaseAdapter {
        @Override public int getCount() { return readerBookSongs.size(); }
        @Override public Song getItem(int position) { return readerBookSongs.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            return createReaderSongRow(position, getItem(position), convertView);
        }
    }

    private int readerPositionFor(Song song) {
        for (int i = 0; i < readerBookSongs.size(); i++) {
            if (readerBookSongs.get(i).id.equals(song.id)) return i;
        }
        return 0;
    }

    private View createReaderSongRow(int position, Song song, View convertView) {
        FrameLayout outer = convertView instanceof FrameLayout
                ? (FrameLayout) convertView : new FrameLayout(this);
        outer.removeAllViews();
        outer.setBackgroundColor(BG);
        outer.setPadding(0, dp(8), 0, dp(8));

        LinearLayout paper = new LinearLayout(this);
        paper.setOrientation(LinearLayout.VERTICAL);
        paper.setPadding(dp(18), dp(16), dp(18), dp(22));
        paper.setBackground(roundStroke(SURFACE, BORDER, 18));
        FrameLayout.LayoutParams paperParams = new FrameLayout.LayoutParams(
                readerPageWidthPx, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        outer.addView(paper, paperParams);

        TextView meta = label(volumeNames.get(song.volume - 1) + "  ·  গান " + bn(song.number),
                12, GREEN, true);
        meta.setGravity(Gravity.CENTER);
        paper.addView(meta, matchWrap());
        addSpace(paper, 10);

        TextView title = label(song.title, 22, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(4), 1f);
        paper.addView(title, matchWrap());
        if (!song.category.isEmpty()) {
            addSpace(paper, 4);
            TextView category = label(song.category, 13, MUTED, true);
            category.setGravity(Gravity.RIGHT);
            paper.addView(category, matchWrap());
        }

        addSpace(paper, 10);
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.leftMargin = dp(22);
        lineParams.rightMargin = dp(22);
        paper.addView(line, lineParams);
        addSpace(paper, 14);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        TextView favorite = readerAction(favorites.contains(song.id) ? "♥" : "♡",
                favorites.contains(song.id) ? GREEN : TEXT, "প্রিয়");
        TextView bookmark = readerAction(bookmarks.contains(song.id) ? "▣" : "▢",
                bookmarks.contains(song.id) ? GREEN : TEXT, "বুকমার্ক");
        actions.addView(favorite, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams bookmarkParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
        bookmarkParams.leftMargin = dp(8);
        actions.addView(bookmark, bookmarkParams);
        paper.addView(actions);
        favorite.setOnClickListener(v -> {
            toggleSet(favorites, song.id, "favorites");
            favorite.setText(favorites.contains(song.id) ? "♥\nপ্রিয়" : "♡\nপ্রিয়");
            favorite.setTextColor(favorites.contains(song.id) ? GREEN : TEXT);
        });
        bookmark.setOnClickListener(v -> {
            toggleSet(bookmarks, song.id, "bookmarks");
            bookmark.setText(bookmarks.contains(song.id) ? "▣\nবুকমার্ক" : "▢\nবুকমার্ক");
            bookmark.setTextColor(bookmarks.contains(song.id) ? GREEN : TEXT);
        });

        addSpace(paper, 12);
        addReaderLyrics(paper, song, readerTextMeasurePx,
                effectiveReaderTextSizeSp(getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density));

        addSpace(paper, 20);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Song previous = readerSongAt(position - 1);
        Song next = readerSongAt(position + 1);
        TextView previousButton = navButton("‹  আগের গান");
        TextView nextButton = navButton("পরের গান  ›");
        navigation.addView(previousButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        nextParams.leftMargin = dp(8);
        navigation.addView(nextButton, nextParams);
        previousButton.setEnabled(previous != null);
        nextButton.setEnabled(next != null);
        previousButton.setAlpha(previous == null ? 0.4f : 1f);
        nextButton.setAlpha(next == null ? 0.4f : 1f);
        previousButton.setOnClickListener(v -> { if (previous != null) scrollToReaderSong(previous); });
        nextButton.setOnClickListener(v -> { if (next != null) scrollToReaderSong(next); });
        paper.addView(navigation);

        TextView scrollHint = label("নিচে স্ক্রল করলে পরের গান পড়তে পারবেন", 11, MUTED, false);
        scrollHint.setGravity(Gravity.CENTER);
        scrollHint.setPadding(0, dp(12), 0, 0);
        paper.addView(scrollHint, matchWrap());
        return outer;
    }

    private Song readerSongAt(int position) {
        return position < 0 || position >= readerBookSongs.size()
                ? null : readerBookSongs.get(position);
    }

    private void scrollToReaderSong(Song song) {
        if (readerList == null || song == null) return;
        saveReaderPosition();
        int position = readerPositionFor(song);
        int offset = Math.max(0, prefs.getInt("scroll_" + song.id, 0));
        readerList.smoothScrollToPositionFromTop(position, -offset, 260);
    }

    private void updateReaderSongFromScroll(int firstVisibleItem) {
        if (readerList == null || firstVisibleItem < 0
                || firstVisibleItem >= readerBookSongs.size()) return;
        Song visible = readerBookSongs.get(firstVisibleItem);
        saveVisibleReaderOffset(firstVisibleItem, visible);
        if (currentSong == null || !currentSong.id.equals(visible.id)) {
            saveReaderPosition();
            currentSong = visible;
            rememberSong(visible);
            if (readerHeaderTitle != null) readerHeaderTitle.setText("গান " + bn(visible.number));
            if (readerHeaderSubtitle != null) {
                readerHeaderSubtitle.setText(volumeNames.get(visible.volume - 1));
            }
        }
    }

    private void saveVisibleReaderOffset(int position, Song song) {
        if (readerList == null || song == null) return;
        int firstVisible = readerList.getFirstVisiblePosition();
        View row = readerList.getChildAt(position - firstVisible);
        if (row == null) return;
        int offset = Math.max(0, -row.getTop());
        long now = SystemClock.uptimeMillis();
        boolean songChanged = !song.id.equals(lastReaderOffsetSongId);
        if (!songChanged && Math.abs(offset - lastReaderOffsetPx) < dp(24)
                && now - lastReaderOffsetWriteMs < 800L) return;
        prefs.edit().putInt("scroll_" + song.id, offset).apply();
        lastReaderOffsetSongId = song.id;
        lastReaderOffsetPx = offset;
        lastReaderOffsetWriteMs = now;
    }

    private void addReaderLyrics(LinearLayout parent, Song song, int textMeasure, int lyricSizeSp) {
        final float wordTextWidthPt = 671.8f;
        for (LyricBlock block : song.lyricBlocks) {
            String blockText = song.lyrics.substring(block.startOffset, block.endOffset);
            TextView lyricLine = label(blockText, lyricSizeSp, TEXT, false);
            int gravity = block.alignment == 1 ? Gravity.CENTER_HORIZONTAL
                    : block.alignment == 2 ? Gravity.RIGHT : Gravity.LEFT;
            lyricLine.setGravity(gravity);
            if (Build.VERSION.SDK_INT >= 23) {
                lyricLine.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                lyricLine.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
            }
            if (block.alignment == 3 && Build.VERSION.SDK_INT >= 26) {
                lyricLine.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
            }
            lyricLine.setLineSpacing(0f, 1.0f);
            int leftIndent = responsiveWordMeasure(block.leftIndentPt, textMeasure, wordTextWidthPt);
            int firstIndent = Math.max(0, leftIndent + responsiveWordMeasure(
                    block.firstLineIndentPt, textMeasure, wordTextWidthPt));
            if ((block.boldSpans != null && !block.boldSpans.isEmpty())
                    || leftIndent > 0 || firstIndent > 0) {
                SpannableString styled = new SpannableString(blockText);
                if (block.boldSpans != null) for (int[] span : block.boldSpans) {
                    int start = Math.max(0, Math.min(span[0], styled.length()));
                    int end = Math.max(start, Math.min(span[1], styled.length()));
                    if (end > start) styled.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (leftIndent > 0 || firstIndent > 0) {
                    styled.setSpan(new LeadingMarginSpan.Standard(firstIndent, leftIndent),
                            0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                lyricLine.setText(styled);
            }
            lyricLine.setPadding(0, 0,
                    responsiveWordMeasure(block.rightIndentPt, textMeasure, wordTextWidthPt), 0);
            lyricLine.setTextIsSelectable(true);
            // Keep every printed line on one screen line whenever possible: when an
            // original line slightly overflows the column, shrink the font just for
            // that line instead of wrapping mid-sentence — the printed book never
            // breaks these lines, so wrapping here looks wrong to the reader.
            if (!blockText.contains("\n")) {
                int rightIndent = responsiveWordMeasure(
                        block.rightIndentPt, textMeasure, wordTextWidthPt);
                int targetWidth = textMeasure - leftIndent - firstIndent - rightIndent;
                if (targetWidth > 0) {
                    android.text.TextPaint plainPaint =
                            new android.text.TextPaint(lyricLine.getPaint());
                    android.text.TextPaint boldPaint = new android.text.TextPaint(plainPaint);
                    boldPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                    float measuredWidth = Math.max(plainPaint.measureText(blockText),
                            boldPaint.measureText(blockText));
                    if (measuredWidth > targetWidth) {
                        float fitScale = Math.max(0.72f, targetWidth / measuredWidth);
                        if (fitScale < 0.985f) {
                            lyricLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                                    lyricSizeSp * fitScale);
                        }
                    }
                }
            }
            LinearLayout.LayoutParams lyricParams = matchWrap();
            lyricParams.topMargin = responsiveWordMeasure(
                    block.spaceBeforePt, textMeasure, wordTextWidthPt);
            lyricParams.bottomMargin = responsiveWordMeasure(
                    block.spaceAfterPt, textMeasure, wordTextWidthPt);
            parent.addView(lyricLine, lyricParams);
        }
    }

    private int defaultReaderTextSizeSp(float screenWidthDp) {
        return 16;
    }

    private int currentReaderTextSizeSp(float screenWidthDp) {
        int fallback = defaultReaderTextSizeSp(screenWidthDp);
        return Math.max(8, Math.min(28, prefs.getInt("reader_text_size_sp", fallback)));
    }

    private int effectiveReaderTextSizeSp(float screenWidthDp) {
        int preferred = currentReaderTextSizeSp(screenWidthDp);
        // Match the widened book column: dp(396) is the 432dp page minus its gutters,
        // so phones with the full column keep the reader's chosen size unchanged.
        float standardTextWidth = dp(396);
        float widthScale = standardTextWidth <= 0 ? 1f : readerTextMeasurePx / standardTextWidth;
        return Math.max(8, Math.round(preferred * widthScale));
    }

    private void changeReaderTextSize(Song song, int delta) {
        float density = getResources().getDisplayMetrics().density;
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
        int current = currentReaderTextSizeSp(screenWidthDp);
        int updated = Math.max(8, Math.min(28, current + delta));
        if (updated == current) return;
        saveReaderPosition();
        prefs.edit().putInt("reader_text_size_sp", updated).apply();
        renderReader(song);
    }

    private void showSongSearchDialog() {
        if (songSearchDialog != null && songSearchDialog.isShowing()) return;
        searchBeforeDialog = activeSearch;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText search = searchField("গানের নাম বা একটি লাইন লিখুন");
        dialogSearchField = search;
        content.addView(searchInputRow(search), matchWrap());
        addSpace(content, 8);

        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(false);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(results);
        content.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        activeSearch = search;
        results.addView(label("গানের নাম বা গানের একটি লাইন লিখুন, অথবা মাইক্রোফোনে বলুন।",
                13, MUTED, false));
        search.addTextChangedListener(watcher(query -> {
            if (query.trim().isEmpty()) {
                results.removeAllViews();
                results.addView(label("গানের নাম বা গানের একটি লাইন লিখুন, অথবা মাইক্রোফোনে বলুন।",
                        13, MUTED, false));
            } else {
                renderSearchResults(results, query, 0);
            }
        }));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("গান খুঁজুন")
                .setView(content)
                .setNegativeButton("বন্ধ", null)
                .create();
        songSearchDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (songSearchDialog == dialog) songSearchDialog = null;
            if (dialogSearchField == search) dialogSearchField = null;
            if (activeSearch == search) activeSearch = searchBeforeDialog;
            searchBeforeDialog = null;
        });
        dialog.show();
        int width = Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(32));
        int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.68f);
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, height);
        search.requestFocus();
        search.post(() -> {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(search,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void toggleSet(Set<String> set,String id,String key){
        if(set.contains(id))set.remove(id);else set.add(id);
        prefs.edit().putStringSet(key,new HashSet<>(set)).apply();
    }

    private void startOfflineVoice(EditText target) {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTarget = target;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        cancelVoicePrewarmSchedule();
        cancelVoiceSession();
        voiceSessionCompleted = false;
        voiceListeningStarted = false;
        voiceCandidateSelected = false;
        voiceCandidateHistory.clear();
        pinnedVoiceCandidateId = "";
        pinnedVoiceCandidateScore = 0;
        lastVoiceCandidateQuery = "";
        lastVoiceCandidateUpdateMs = 0L;
        voiceSessionStartedUptimeMs = SystemClock.uptimeMillis();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(10), dp(24), dp(18));
        TextView icon = label("🎙", 30, GREEN, true);
        icon.setGravity(Gravity.CENTER);
        content.addView(icon, matchWrap());
        voiceStatus = label("বাংলা অফলাইন মডেল চালু হচ্ছে…", 15, TEXT, true);
        voiceStatus.setGravity(Gravity.CENTER);
        voiceStatus.setPadding(0, dp(12), 0, dp(8));
        content.addView(voiceStatus, matchWrap());
        TextView hint = label("প্রস্তুত হলে নিজে থেকেই শুনবে। গানের একটি লাইন বলুন—সম্ভাব্য গান দেখা গেলে সেটিতে ছুঁয়ে খুলুন।",
                13, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint, matchWrap());
        voiceCandidates = new LinearLayout(this);
        voiceCandidates.setOrientation(LinearLayout.VERTICAL);
        voiceCandidates.setPadding(0, dp(12), 0, 0);
        content.addView(voiceCandidates, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("বাংলা ভয়েস সার্চ")
                .setView(content)
                .setNegativeButton("বাতিল", (d, which) -> cancelVoiceSession())
                .setPositiveButton("প্রস্তুত হচ্ছে…", null)
                .create();
        voiceDialog = dialog;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (!voiceSessionCompleted && voiceServiceStarted) cancelVoiceSession();
        });
        dialog.show();
        Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        action.setEnabled(false);
        action.setOnClickListener(v -> {
            if (!voiceServiceStarted) return;
            voiceStatus.setText("শোনা শেষ করে গান খোঁজা হচ্ছে…");
            action.setText("খুঁজছি…");
            action.setEnabled(false);
            sendVoiceServiceCommand(VoiceRecognitionService.ACTION_FINISH);
        });

        voiceReceiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (voiceCandidateSelected || voiceSessionCompleted) return;
                String text = data == null ? "" : data.getString("text", "");
                if (resultCode == VoiceRecognitionService.RESULT_STATUS) {
                    if (voiceStatus != null) voiceStatus.setText(text);
                    if (voiceDialog != null) {
                        Button button = voiceDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        if (text.startsWith("প্রস্তুত")) {
                            if (!voiceListeningStarted) {
                                voiceListeningStarted = true;
                                appendPerformanceDiagnostic("voiceModelReadyMs="
                                        + (SystemClock.uptimeMillis() - voiceSessionStartedUptimeMs));
                            button.setText("শোনা হচ্ছে…");
                                button.setEnabled(false);
                                voiceStatus.setText("মাইক্রোফোন চালু হচ্ছে…");
                                sendVoiceServiceCommand(VoiceRecognitionService.ACTION_BEGIN);
                            }
                        } else if (text.startsWith("শুনছি")) {
                            button.setText("শোনা শেষ করুন");
                            button.setEnabled(true);
                        } else {
                            button.setText("প্রস্তুত হচ্ছে…");
                            button.setEnabled(false);
                        }
                    }
                } else if (resultCode == VoiceRecognitionService.RESULT_PARTIAL) {
                    if (voiceStatus != null) voiceStatus.setText("শুনছি…\n" + text);
                    renderVoiceCandidates(text);
                } else if (resultCode == VoiceRecognitionService.RESULT_AUDIO_HINT) {
                    // Only the status line changes: never the action button
                    // state, which still belongs to the recognition flow.
                    if (voiceStatus != null) {
                        if (!text.isEmpty()) {
                            voiceStatus.setText(text);
                        } else if (!latestVoiceTranscript.isEmpty()) {
                            voiceStatus.setText("শুনছি…\n" + latestVoiceTranscript);
                        } else {
                            voiceStatus.setText("শুনছি — একটি ছোট গানের লাইন বলুন।");
                        }
                    }
                } else if (resultCode == VoiceRecognitionService.RESULT_TEXT) {
                    finishVoiceDialog();
                    handleVoiceSearchResult(target, text);
                } else if (resultCode == VoiceRecognitionService.RESULT_ERROR) {
                    finishVoiceDialog();
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                    if (target == null) {
                        renderIndex();
                        if (activeSearch != null) activeSearch.requestFocus();
                    } else target.requestFocus();
                } else if (resultCode == VoiceRecognitionService.RESULT_FINISHED
                        && voiceDialog != null && !voiceSessionCompleted) {
                    finishVoiceDialog();
                    Toast.makeText(MainActivity.this,
                            "ভয়েস প্রসেসটি বন্ধ হয়ে গেছে। আবার চেষ্টা করুন।",
                            Toast.LENGTH_LONG).show();
                }
            }
        };

        try {
            Intent startIntent = new Intent(this, VoiceRecognitionService.class)
                    .setAction(VoiceRecognitionService.ACTION_START)
                    .putExtra(VoiceRecognitionService.EXTRA_RECEIVER, voiceReceiver);
            startService(startIntent);
            voiceServiceStarted = true;
            voiceModelServiceActive = true;
            voiceServiceConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    // A short-lived binding keeps the active ASR worker attached
                    // to the visible activity while the user is recording.
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    voiceServiceBound = false;
                    if (voiceDialog != null && !voiceSessionCompleted) {
                        finishVoiceDialog();
                        Toast.makeText(MainActivity.this,
                                "কম মেমরির কারণে ভয়েস প্রসেস বন্ধ হয়েছে। আবার চেষ্টা করুন।",
                                Toast.LENGTH_LONG).show();
                    }
                }
                @Override public void onBindingDied(ComponentName name) {
                    onServiceDisconnected(name);
                }
                @Override public void onNullBinding(ComponentName name) {
                    onServiceDisconnected(name);
                }
            };
            voiceServiceBound = bindService(startIntent, voiceServiceConnection, Context.BIND_AUTO_CREATE);
            if (!voiceServiceBound) {
                sendVoiceServiceCommand(VoiceRecognitionService.ACTION_CANCEL);
                throw new IllegalStateException("ভয়েস সার্ভিসে সংযোগ করা যায়নি।");
            }
        } catch (Exception error) {
            finishVoiceDialog();
            Toast.makeText(this, "অফলাইন ভয়েস সার্ভিস চালু করা যায়নি।",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void sendVoiceServiceCommand(String action) {
        if (!voiceServiceStarted) return;
        try {
            Intent command = new Intent(this, VoiceRecognitionService.class).setAction(action);
            if (voiceReceiver != null) {
                command.putExtra(VoiceRecognitionService.EXTRA_RECEIVER, voiceReceiver);
            }
            startService(command);
            voiceModelServiceActive = true;
        } catch (Exception error) {
            finishVoiceDialog();
            Toast.makeText(this, "ভয়েস সার্ভিস পাওয়া যাচ্ছে না। আবার চেষ্টা করুন।",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void unbindVoiceService() {
        if (voiceServiceBound && voiceServiceConnection != null) {
            try { unbindService(voiceServiceConnection); } catch (IllegalArgumentException ignored) { }
        }
        voiceServiceBound = false;
        voiceServiceConnection = null;
    }

    private void finishVoiceDialog() {
        voiceSessionCompleted = true;
        if (voiceDialog != null) {
            AlertDialog old = voiceDialog;
            voiceDialog = null;
            old.dismiss();
        }
        voiceStatus = null;
        voiceCandidates = null;
        voiceReceiver = null;
        voiceServiceStarted = false;
        unbindVoiceService();
        voiceListeningStarted = false;
    }

    private void cancelVoiceSession() {
        voiceSessionCompleted = true;
        if (voiceServiceStarted) {
            sendVoiceServiceCommand(VoiceRecognitionService.ACTION_CANCEL);
        }
        voiceReceiver = null;
        voiceServiceStarted = false;
        unbindVoiceService();
        voiceStatus = null;
        voiceCandidates = null;
        if (voiceDialog != null) {
            AlertDialog old = voiceDialog;
            voiceDialog = null;
            old.dismiss();
        }
        voiceListeningStarted = false;
    }

    private boolean hasRoomForVoicePrewarm() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        long minimumRam = 3L * 1024L * 1024L * 1024L;
        return memory.totalMem >= minimumRam && memory.availMem >= 256L * 1024L * 1024L;
    }

    private void scheduleOfflineVoicePrewarm() {
        if (!searchIndexReady || voiceModelServiceActive || voiceServiceStarted
                || voicePrewarmTask != null || !hasRoomForVoicePrewarm()) return;
        voicePrewarmTask = () -> {
            voicePrewarmTask = null;
            if (isFinishing() || isDestroyed() || voiceServiceStarted) return;
            try {
                startService(new Intent(this, VoiceRecognitionService.class)
                        .setAction(VoiceRecognitionService.ACTION_PREWARM));
                voiceModelServiceActive = true;
            } catch (Exception error) {
                Log.w("AshkeShadekVoice", "Could not prewarm bundled voice model", error);
            }
        };
        mainHandler.postDelayed(voicePrewarmTask, 2500L);
    }

    private void cancelVoicePrewarmSchedule() {
        if (voicePrewarmTask != null) mainHandler.removeCallbacks(voicePrewarmTask);
        voicePrewarmTask = null;
    }

    private void stopOfflineVoiceService() {
        cancelVoicePrewarmSchedule();
        if (voiceServiceStarted) cancelVoiceSession();
        try { stopService(new Intent(this, VoiceRecognitionService.class)); }
        catch (Exception error) {
            Log.w("AshkeShadekVoice", "Could not stop voice model service", error);
        }
        voiceModelServiceActive = false;
    }

    /** Shows the strongest three live catalogue candidates while recognition continues. */
    private void renderVoiceCandidates(String transcript) {
        if (voiceCandidates == null) return;
        String query = transcript == null ? "" : transcript.trim();
        if (query.isEmpty() || query.equals(lastVoiceCandidateQuery)) return;
        latestVoiceTranscript = query;
        if (!searchIndexReady) {
            if (voiceCandidates.getChildCount() == 0) {
                voiceCandidates.addView(label("গানের খোঁজ প্রস্তুত হচ্ছে…", 12, MUTED, false));
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastVoiceCandidateUpdateMs < 180L) return;
        lastVoiceCandidateQuery = query;
        lastVoiceCandidateUpdateMs = now;
        SearchQuery preparedQuery = prepareSearchQuery(query);
        for (Song song : songs) {
            int score = scoreSongMatch(song, preparedQuery, true);
            // Strict word evidence can vanish entirely when singing garbles the
            // recognized words. The fuzzy fallback keeps voice suggestions alive
            // for such transcripts; typed search never runs it.
            boolean strictEvidence = score >= 300;
            if (!strictEvidence) {
                SearchDocument doc = searchIndex.get(song.id);
                if (doc != null) score = scoreVoiceFuzzyMatch(doc, preparedQuery);
            }
            if (score >= 300) {
                VoiceCandidate candidate = voiceCandidateHistory.get(song.id);
                if (candidate == null) {
                    candidate = new VoiceCandidate(song);
                    voiceCandidateHistory.put(song.id, candidate);
                }
                if (!query.equals(candidate.lastObservedQuery)) {
                    candidate.observations++;
                    candidate.lastObservedQuery = query;
                }
                if (score >= candidate.bestScore) {
                    candidate.bestScore = score;
                    // Word-based excerpts only mean something for strict
                    // matches; fuzzy matches keep the previous excerpt.
                    if (strictEvidence) {
                        candidate.excerpt = matchingLyricExcerpt(song, preparedQuery);
                    }
                }
                if (pinnedVoiceCandidateId.isEmpty()
                        || (song.id.equals(pinnedVoiceCandidateId)
                        && candidate.bestScore >= pinnedVoiceCandidateScore)
                        || (candidate.observations >= 2
                        && candidate.bestScore >= pinnedVoiceCandidateScore + 100)) {
                    pinnedVoiceCandidateId = song.id;
                    pinnedVoiceCandidateScore = candidate.bestScore;
                }
            }
        }
        List<VoiceCandidate> matches = new ArrayList<>(voiceCandidateHistory.values());
        Collections.sort(matches, (x, y) -> {
            if (x.song.id.equals(pinnedVoiceCandidateId)) return -1;
            if (y.song.id.equals(pinnedVoiceCandidateId)) return 1;
            int byScore = Integer.compare(y.bestScore, x.bestScore);
            if (byScore != 0) return byScore;
            if (x.song.volume != y.song.volume) return Integer.compare(x.song.volume, y.song.volume);
            return Integer.compare(x.song.number, y.song.number);
        });
        voiceCandidates.removeAllViews();
        if (matches.isEmpty()) {
            voiceCandidates.addView(label("এখনো নিশ্চিত মিল পাওয়া যায়নি — শুনছি…", 12, MUTED, false));
            return;
        }
        voiceCandidates.addView(label("সম্ভাব্য গান · শোনা চলছে", 12, GREEN, true));
        for (int i = 0; i < Math.min(3, matches.size()); i++) {
            VoiceCandidate candidate = matches.get(i);
            Song song = candidate.song;
            LinearLayout row = cardRow();
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.addView(label(song.title, 14, TEXT, true));
            details.addView(label(volumeShort(song.volume) + " · গান " + bn(song.number), 11, MUTED, false));
            TextView excerpt = label(candidate.excerpt, 12, MUTED, false);
            excerpt.setMaxLines(2);
            excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(excerpt);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(label("খুলুন  ›", 12, GREEN, true));
            row.setOnClickListener(v -> {
                voiceCandidateSelected = true;
                cancelVoiceSession();
                openSong(song);
            });
            voiceCandidates.addView(row, matchWrap());
            addSpace(voiceCandidates, 6);
        }
    }

    private String matchingLyricExcerpt(Song song, SearchQuery query) {
        String bestText = song.firstLine();
        int bestMatches = 0;
        for (LyricBlock block : song.lyricBlocks) {
            String text = song.lyrics.substring(block.startOffset, block.endOffset).trim();
            String normalized = normalize(text);
            String modern = normalize(modernizeOldBengali(text));
            int matches = 0;
            for (SearchTerm term : query.terms) {
                if (normalized.contains(term.original) || modern.contains(term.original)
                        || normalized.contains(term.modern) || modern.contains(term.modern)) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                bestText = text;
                if (matches == query.terms.size()) break;
            }
        }
        return bestText == null ? "" : bestText;
    }

    private void handleVoiceSearchResult(EditText target, String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            Toast.makeText(this, "কোনো কথা শনাক্ত হয়নি। আবার বলুন অথবা লিখে খুঁজুন।",
                    Toast.LENGTH_LONG).show();
            return;
        }
        phrase = phrase.trim();
        if (handleReaderVoiceCommand(phrase)) return;
        Song direct = resolveDirectJump(phrase, currentSong != null ? currentSong.volume : 0);
        if (direct != null) {
            openSong(direct);
            return;
        }
        if (target != null) {
            target.setText(phrase);
            target.setSelection(target.length());
        } else {
            renderIndex();
            if (activeSearch != null) {
                activeSearch.setText(phrase);
                activeSearch.setSelection(activeSearch.length());
            }
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            EditText target = pendingVoiceTarget;
            pendingVoiceTarget = null;
            if (ok) startOfflineVoice(target);
            else {
                Toast.makeText(this, "ভয়েস সার্চের অনুমতি দেওয়া হয়নি। লিখে খুঁজতে পারবেন।",
                        Toast.LENGTH_LONG).show();
                if (target == null) renderIndex();
            }
        }
    }

    private boolean handleReaderVoiceCommand(String phrase) {
        if (phrase == null) return false;
        String q = phrase.toLowerCase(Locale.ROOT).replace(" ", "");
        if (currentScreen.equals("reader") && currentSong != null) {
            if (q.contains("পরেরগান") || q.contains("পরবর্তীগান") || q.contains("nextsong")) {
                Song next = byPosition(currentSong.volume, currentSong.number + 1);
                if (next == null && currentSong.volume < 3) next = byPosition(currentSong.volume + 1, 1);
                if (next != null) scrollToReaderSong(next);
                return true;
            }
            if (q.contains("আগেরগান") || q.contains("পূর্বেরগান") || q.contains("previoussong")) {
                Song prev = byPosition(currentSong.volume, currentSong.number - 1);
                if (prev == null && currentSong.volume > 1) prev = byPosition(currentSong.volume - 1, countInVolume(currentSong.volume - 1));
                if (prev != null) scrollToReaderSong(prev);
                return true;
            }
            if (q.contains("উপরে") || q.contains("শুরুতে") || q.contains("গোড়ায়যাও")) {
                if (readerList != null) readerList.smoothScrollToPosition(0);
                return true;
            }
        }
        if (q.contains("সূচিপত্রদেখাও") || q.contains("সূচীদেখাও") || q.contains("গানেরসূচি") || q.contains("সূচিপত্রখোলো")) {
            renderIndex();
            return true;
        }
        return false;
    }

    private Song resolveDirectJump(String query,int defaultVolume){
        String q=query==null?"":query.toLowerCase(Locale.ROOT);
        int vol=defaultVolume>0?defaultVolume:1;
        if(q.contains("তৃতীয়")||q.contains("তৃতীয়")||q.contains("তৃতীয় খণ্ড")||q.contains("৩য় খণ্ড")||q.contains("তিন নম্বর খণ্ড")||q.contains("thirdvolume")||q.contains("3rdvolume")||q.contains("volume3"))vol=3;
        else if(q.contains("দ্বিতীয়")||q.contains("দ্বিতীয়")||q.contains("২য় খণ্ড")||q.contains("দুই নম্বর খণ্ড")||q.contains("secondvolume")||q.contains("2ndvolume")||q.contains("volume2"))vol=2;
        else if(q.contains("প্রথম")||q.contains("১ম খণ্ড")||q.contains("এক নম্বর খণ্ড")||q.contains("firstvolume")||q.contains("1stvolume")||q.contains("volume1"))vol=1;
        int number=-1;
        Matcher matcher=Pattern.compile("([০-৯0-9]{1,3})").matcher(q);
        List<Integer> foundNumbers=new ArrayList<>();
        while(matcher.find()) { try { foundNumbers.add(Integer.parseInt(digitsToAscii(matcher.group(1)))); } catch(Exception ignored) {} }
        if(foundNumbers.size()>=2 && defaultVolume==0) { vol=foundNumbers.get(0); number=foundNumbers.get(1); }
        else if(!foundNumbers.isEmpty()) number=foundNumbers.get(foundNumbers.size()-1);
        if(number<1){
            String[][] words={{"এক","১"},{"দুই","২"},{"তিন","৩"},{"চার","৪"},{"পাঁচ","৫"},{"ছয়","৬"},{"ছয়","৬"},{"সাত","৭"},{"আট","৮"},{"নয়","৯"},{"নয়","৯"},{"দশ","১০"},{"এগারো","১১"},{"বারো","১২"},{"তেরো","১৩"},{"চৌদ্দ","১৪"},{"পনেরো","১৫"},{"ষোল","১৬"},{"সতেরো","১৭"},{"আঠারো","১৮"},{"উনিশ","১৯"},{"বিশ","২০"},{"ত্রিশ","৩০"},{"চল্লিশ","৪০"},{"পঞ্চাশ","৫০"},{"ষাট","৬০"},{"সত্তর","৭০"},{"আশি","৮০"},{"নব্বই","৯০"},{"একশ","১০০"},{"একশত","১০০"}};
            for(String[] pair:words){if(q.contains(pair[0])&&(q.contains("নম্বর")||q.contains("গান")||q.contains("খণ্ড"))){number=Integer.parseInt(digitsToAscii(pair[1]));break;}}
        }
        if(number>=1){Song song=byPosition(vol,number);if(song!=null)return song;}
        return null;
    }

    private static final String BN_DIGITS="০১২৩৪৫৬৭৮৯";
    private String digitsToAscii(String value){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            int pos=BN_DIGITS.indexOf(c);
            if(pos>=0) out.append((char)('0'+pos)); else out.append(c);
        }
        return out.toString();
    }
    private String joinRecent(List<String> ids){StringBuilder b=new StringBuilder();for(String id:ids){if(b.length()>0)b.append(',');b.append(id);}return b.toString();}
    private List<String> parseRecent(String saved){List<String> out=new ArrayList<>();if(saved!=null&&!saved.isEmpty()){for(String id:saved.split(",")){if(!id.trim().isEmpty()&&!out.contains(id.trim()))out.add(id.trim());if(out.size()>=12)break;}}return out;}

    private String normalize(String value){
        if(value==null)return "";
        String s=Normalizer.normalize(value,Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
        s=s.replace("য়","য়").replace("ড়","ড়").replace("ঢ়","ড়").replace("ৎ","ত");
        s=NORMALIZE_SEPARATOR_PATTERN.matcher(s).replaceAll("");
        return s;
    }

    private Song byId(String id){for(Song s:songs)if(s.id.equals(id))return s;return null;}
    private Song byPosition(int volume,int number){for(Song s:songs)if(s.volume==volume&&s.number==number)return s;return null;}
    private int countInVolume(int volume){int c=0;for(Song s:songs)if(s.volume==volume)c++;return c;}
    private String volumeShort(int v){return v==1?"১ম খণ্ড":v==2?"২য় খণ্ড":"৩য় খণ্ড";}
    private String bn(int n){String s=Integer.toString(n);StringBuilder out=new StringBuilder();for(char c:s.toCharArray()){if(c>='0'&&c<='9')out.append(BN_DIGITS.charAt(c-'0'));else out.append(c);}return out.toString();}

    private ScrollView pageScroll(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);return scroll;}
    private LinearLayout bottomNav(){
        LinearLayout bar=new LinearLayout(this);bar.setBackgroundColor(SURFACE);bar.setGravity(Gravity.CENTER);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setPadding(dp(4),dp(6),dp(4),dp(6));
        String[] labels={"⌂\nহোম","☷\nসূচী","♡\nপ্রিয়","▣\nবুকমার্ক"};String[] screens={"home","index","favorites","bookmarks"};
        for(int i=0;i<labels.length;i++){final String screen=screens[i];TextView item=label(labels[i],12,screen.equals(currentScreen)?GREEN:MUTED,screen.equals(currentScreen));item.setGravity(Gravity.CENTER);bar.addView(item,new LinearLayout.LayoutParams(0,-1,1f));item.setOnClickListener(v->{if(screen.equals("home")){homeHasInteracted=true;renderHome();}else if(!catalogReady){Toast.makeText(this,"গানের তালিকা প্রস্তুত হচ্ছে…",Toast.LENGTH_SHORT).show();}else if(screen.equals("index"))renderIndex();else renderSaved(screen);});}
        return bar;
    }

    private LinearLayout cardColumn(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(roundFill(SURFACE,18));l.setElevation(dp(1));return l;}
    private LinearLayout cardRow(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setPadding(dp(14),dp(12),dp(14),dp(12));l.setBackground(roundStroke(SURFACE,BORDER,17));l.setClickable(true);l.setFocusable(true);return l;}
    private LinearLayout searchInputRow(EditText search) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView mic = actionButton("🎙", "বলুন");
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(66), dp(52));
        micParams.leftMargin = dp(8);
        row.addView(mic, micParams);
        mic.setContentDescription("মাইক্রোফোনে গান খুঁজুন");
        mic.setOnClickListener(v -> {
            if (!catalogReady) {
                Toast.makeText(this, "গানের তালিকা প্রস্তুত হচ্ছে…", Toast.LENGTH_SHORT).show();
                return;
            }
            startOfflineVoice(search);
        });
        return row;
    }
    private EditText searchField(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(15);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(15),0,dp(15),0);e.setBackground(roundStroke(SURFACE,BORDER,15));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);return e;}
    private TextView label(String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));t.setIncludeFontPadding(true);return t;}
    private TextView iconButton(String text,int size){TextView t=label(text,size,GREEN,true);t.setGravity(Gravity.CENTER);t.setBackground(roundFill(SOFT_GREEN,14));return t;}
    private TextView actionButton(String icon,String text){TextView t=label(icon+"\n"+text,13,GREEN_DARK,true);t.setGravity(Gravity.CENTER);t.setBackground(roundStroke(SOFT_GREEN,0xFFD8E5DC,15));return t;}
    private TextView readerAction(String icon,int color,String text){TextView t=label(icon+"\n"+text,15,color,true);t.setGravity(Gravity.CENTER);t.setBackground(roundStroke(SURFACE,BORDER,13));return t;}
    private TextView navButton(String text){TextView t=label(text,14,GREEN_DARK,true);t.setGravity(Gravity.CENTER);t.setBackground(roundStroke(SOFT_GREEN,0xFFD8E5DC,14));return t;}
    private TextView badge(String text,int bg,int color){TextView t=label(text,15,color,true);t.setGravity(Gravity.CENTER);t.setBackground(roundFill(bg,14));return t;}
    private TextView quickTile(String icon,String title,int count){TextView t=label(icon+"   "+title+"\n"+bn(count)+"টি",14,TEXT,true);t.setGravity(Gravity.CENTER);t.setBackground(roundStroke(SURFACE,BORDER,15));return t;}
    private GradientDrawable roundFill(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable roundStroke(int color,int stroke,int radius){GradientDrawable d=roundFill(color,radius);d.setStroke(dp(1),stroke);return d;}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private int responsiveWordMeasure(float points, int contentWidthPx, float sourceWidthPt) {
        if (points <= 0f || contentWidthPx <= 0 || sourceWidthPt <= 0f) return 0;
        return Math.max(0, Math.round(points * contentWidthPx / sourceWidthPt));
    }
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams matchHeight(int height){return new LinearLayout.LayoutParams(-1,dp(height));}
    private void addSpace(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private interface TextChangeListener { void onTextChanged(String value); }

    /**
     * One search pass after the user pauses briefly, rather than rebuilding
     * transient Bengali/phonetic search forms for all 615 songs on every key.
     */
    private TextWatcher watcher(TextChangeListener callback) {
        return new TextWatcher() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            private Runnable pending;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) handler.removeCallbacks(pending);
                String query = s.toString();
                pending = () -> callback.onTextChanged(query);
                handler.postDelayed(pending, 65L);
            }

            @Override public void afterTextChanged(Editable editable) { }
        };
    }

    @Override public void onBackPressed(){
        if(currentScreen.equals("reader")){
            saveReaderPosition();
            if(currentSong!=null){int v=currentSong.volume;renderVolume(v);}else renderHome();
        }
        else if(!currentScreen.equals("home"))renderHome();
        else super.onBackPressed();
    }

    private void saveReaderPosition() {
        if (currentSong != null && readerList != null) {
            int position = readerPositionFor(currentSong);
            int firstVisible = readerList.getFirstVisiblePosition();
            View row = readerList.getChildAt(position - firstVisible);
            if (row != null) {
                int offset = Math.max(0, -row.getTop());
                prefs.edit().putInt("scroll_" + currentSong.id, offset).apply();
                lastReaderOffsetSongId = currentSong.id;
                lastReaderOffsetPx = offset;
                lastReaderOffsetWriteMs = SystemClock.uptimeMillis();
            }
        }
    }

    @Override protected void onPause() {
        saveReaderPosition();
        if (voiceServiceStarted) cancelVoiceSession();
        super.onPause();
    }

    @Override protected void onStart() {
        super.onStart();
        scheduleOfflineVoicePrewarm();
    }

    private void enforceStorageBudget() {
        // The app never downloads or stores media. Any temporary cache is disposable and capped at 1 MiB.
        File cache = getCacheDir();
        if (directorySize(cache) > CACHE_BUDGET_BYTES) deleteDirectoryContents(cache);
        // Keep only known catalogue IDs in saved lists; each list is naturally limited to 615 songs.
        normalizeSavedSet("favorites");
        normalizeSavedSet("bookmarks");
    }

    private void normalizeSavedSet(String key) {
        Set<String> saved = new LinkedHashSet<>(prefs.getStringSet(key, Collections.emptySet()));
        Set<String> valid = new LinkedHashSet<>();
        for (String id : saved) if (byId(id) != null) valid.add(id);
        saved = valid;
        if (saved.size() > songs.size()) {
            LinkedHashSet<String> capped = new LinkedHashSet<>();
            for (String id : saved) { if (capped.size() >= songs.size()) break; capped.add(id); }
            saved = capped;
        }
        prefs.edit().putStringSet(key, saved).apply();
    }

    private long directorySize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long size = 0L;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) size += directorySize(child);
        return size;
    }

    private void deleteDirectoryContents(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) deleteDirectoryContents(child);
            child.delete();
        }
    }

    private void logMemory(String phase) {
        Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memoryInfo);
        Runtime runtime = Runtime.getRuntime();
        String event = phase
                + " pssKb=" + memoryInfo.getTotalPss()
                + " javaHeapUsedKb=" + ((runtime.totalMemory() - runtime.freeMemory()) / 1024L)
                + " nativeHeapAllocatedKb=" + (Debug.getNativeHeapAllocatedSize() / 1024L);
        Log.i("AshkeMemory", event);
        appendPerformanceDiagnostic(event);
    }

    private void appendPerformanceDiagnostic(String event) {
        if (prefs == null) return;
        JSONArray events = loadRecentPerformanceEvents();
        events.put(System.currentTimeMillis() + " " + event);
        while (events.length() > 16) events.remove(0);
        prefs.edit().putString(PERFORMANCE_EVENTS_KEY, events.toString()).apply();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        logMemory("trim-level-" + level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) stopOfflineVoiceService();
        if (level == TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_BACKGROUND) {
            // Only throw away reconstructible temporary files; song data and
            // user state live in assets/preferences and are never cache-only.
            deleteDirectoryContents(getCacheDir());
        }
    }

    @Override protected void onStop() {
        stopOfflineVoiceService();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        logMemory("system-low-memory");
        stopOfflineVoiceService();
        deleteDirectoryContents(getCacheDir());
    }

    @Override protected void onDestroy(){stopOfflineVoiceService();super.onDestroy();}

    private static class SearchQuery {
        final String normalized, modern, phonetic;
        final List<SearchTerm> terms;
        SearchQuery(String normalized, String modern, String phonetic, List<SearchTerm> terms) {
            this.normalized = normalized; this.modern = modern;
            this.phonetic = phonetic; this.terms = terms;
        }
    }

    /** Per-token forms are calculated once per keystroke/transcript, not per song. */
    private static class SearchTerm {
        final String original, modern, phonetic;
        SearchTerm(String original, String modern, String phonetic) {
            this.original = original;
            this.modern = modern;
            this.phonetic = phonetic;
        }
    }

    private static class SearchDocument {
        final String normalized, modern, phonetic, title;
        final Set<String> words, modernWords, phoneticWords = new HashSet<>();
        /** Evenly sampled character n-gram hashes of the normalized lyrics. */
        final long[] gramSketch;
        /** Total character n-gram count the sketch was sampled from. */
        final int gramCount;
        SearchDocument(String normalized, String modern, String phonetic, String title,
                       Set<String> words, Set<String> modernWords) {
            this.normalized = normalized; this.modern = modern; this.phonetic = phonetic;
            this.title = title; this.words = words; this.modernWords = modernWords;
            for (String word : words) phoneticWords.add(phoneticizeWord(word));
            for (String word : modernWords) phoneticWords.add(phoneticizeWord(word));
            this.gramCount = Math.max(0, normalized.length() - VOICE_GRAM_SIZE + 1);
            this.gramSketch = buildGramSketch(normalized);
        }
    }

    private static class VoiceCandidate {
        final Song song;
        int bestScore;
        int observations;
        String lastObservedQuery = "";
        String excerpt;

        VoiceCandidate(Song song) {
            this.song = song;
            this.excerpt = song.firstLine();
        }
    }
    private static String phoneticizeWord(String value) {
        return value.replace("শ", "স").replace("ষ", "স").replace("ণ", "ন")
                .replace("ী", "ি").replace("ঈ", "ই").replace("ূ", "ু")
                .replace("ঊ", "উ").replace("ৎ", "ত");
    }

    private static class Song {
        final String id; final int volume; final int number; final String title; final String lyrics;
        final String category; final List<LyricBlock> lyricBlocks;
        Song(String id,int volume,int number,String title,String lyrics,String category,List<LyricBlock> lyricBlocks){
            this.id=id; this.volume=volume; this.number=number; this.title=title; this.lyrics=lyrics;
            this.category=category; this.lyricBlocks=lyricBlocks;
        }
        String firstLine() {
            int lineEnd = lyrics.indexOf('\n');
            return lineEnd < 0 ? lyrics : lyrics.substring(0, lineEnd);
        }
    }

    private static class LyricBlock {
        final int startOffset; final int endOffset; final int alignment;
        final float leftIndentPt; final float firstLineIndentPt; final float rightIndentPt;
        final float spaceBeforePt; final float spaceAfterPt;
        List<int[]> boldSpans;
        LyricBlock(int startOffset,int endOffset,int alignment,float leftIndentPt,float firstLineIndentPt,
                   float rightIndentPt,float spaceBeforePt,float spaceAfterPt){
            this.startOffset=startOffset; this.endOffset=endOffset; this.alignment=alignment;
            this.leftIndentPt=leftIndentPt; this.firstLineIndentPt=firstLineIndentPt;
            this.rightIndentPt=rightIndentPt; this.spaceBeforePt=spaceBeforePt; this.spaceAfterPt=spaceAfterPt;
        }
        int length() { return endOffset - startOffset; }
    }
}
