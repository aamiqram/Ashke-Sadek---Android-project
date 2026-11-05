package org.ashkeshadek.reader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Ashke Shadek — lightweight, fully offline songbook reader.
 * No network permission, SDK dependency, analytics, third-party libraries or remote content.
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

    private final List<Song> songs = new ArrayList<>();
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
    private ScrollView readerScroll;
    private EditText activeSearch;
    private EditText pendingVoiceTarget = null;
    private OfflineBengaliVoice offlineVoice;
    private AlertDialog voiceDialog;
    private TextView voiceStatus;
    private boolean voiceSessionCompleted = false;
    private boolean voiceListeningStarted = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        favorites = new LinkedHashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
        bookmarks = new LinkedHashSet<>(prefs.getStringSet("bookmarks", Collections.emptySet()));
        lastSongId = prefs.getString("last_song", "");
        recentIds = parseRecent(prefs.getString("recent", ""));
        loadCatalog();
        loadEmphasis();
        enforceStorageBudget();
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
    }

    private void loadCatalog() {
        try (InputStream input = openCatalog();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            InputStream in = input;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            in.close();
            JSONObject data = new JSONObject(out.toString("UTF-8"));
            JSONArray array = data.getJSONArray("songs");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                List<LyricBlock> blocks = new ArrayList<>();
                JSONArray blockArray = item.optJSONArray("lyricBlocks");
                if (blockArray != null) {
                    for (int blockIndex = 0; blockIndex < blockArray.length(); blockIndex++) {
                        JSONObject block = blockArray.getJSONObject(blockIndex);
                        blocks.add(new LyricBlock(
                                block.optString("text", ""), block.optInt("alignment", 0),
                                (float) block.optDouble("leftIndentPt", 0),
                                (float) block.optDouble("firstLineIndentPt", 0),
                                (float) block.optDouble("rightIndentPt", 0),
                                (float) block.optDouble("spaceBeforePt", 0),
                                (float) block.optDouble("spaceAfterPt", 0)
                        ));
                    }
                }
                Song song = new Song(
                        item.getString("id"), item.getInt("volume"), item.getInt("number"),
                        item.getString("title"), item.optString("lyrics", ""), item.optString("firstLine", ""),
                        item.optString("category", ""), blocks
                );
                String corpus = song.title + " " + song.firstLine + " " + song.lyrics;
                String modernCorpus = modernizeOldBengali(corpus);
                song.searchableText = normalize(corpus);
                song.searchableModernText = normalize(modernCorpus);
                song.searchablePhoneticText = phoneticNormalize(corpus + " " + modernCorpus);
                Set<String> originalWords = searchWords(corpus);
                Set<String> modernWords = searchWords(modernCorpus);
                song.searchableWords.addAll(originalWords);
                song.searchableModernWords.addAll(modernWords);
                for (String word : originalWords) song.searchablePhoneticWords.add(phoneticNormalize(word));
                for (String word : modernWords) song.searchablePhoneticWords.add(phoneticNormalize(word));
                songs.add(song);
            }
        } catch (Exception e) {
            Toast.makeText(this, "গানের তালিকা খোলা যায়নি। অ্যাপটি পুনরায় ইনস্টল করুন।", Toast.LENGTH_LONG).show();
        }
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
                        if (start >= 0 && end > start && end <= block.text.length()) {
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
        TextView sub = label(subtitle, 11, MUTED, false);
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
        mount("এশকে ছাদেক", "ভান্ডারী গানের বই", false, true);
        ScrollView scroll = pageScroll();
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

        if (!lastSongId.isEmpty()) {
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
        for (String rid : recentIds) { Song rs = byId(rid); if (rs == null || lastSongId.equals(rid)) continue; recentToShow.add(rs); if (recentToShow.size() >= 4) break; }
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
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = searchField("গানের নাম বা গানের কোনো লাইন লিখুন");
        activeSearch = search;
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView mic = actionButton("🎙", "ভয়েস");
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(66), dp(52)); micLp.leftMargin = dp(8);
        searchRow.addView(mic, micLp);
        mic.setOnClickListener(v -> startOfflineVoice(search));
        body.addView(searchRow);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        search.addTextChangedListener(watcher(text -> renderSearchResults(results, text, 0)));
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
            info.addView(label(countInVolume(v) + "টি গান", 12, MUTED, false));
            volumeCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            volumeCard.addView(label("›", 26, GREEN, true));
            volumeCard.setOnClickListener(view -> renderVolume(volume));
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
        fav.setOnClickListener(v -> renderSaved("favorites"));
        marks.setOnClickListener(v -> renderSaved("bookmarks"));
        body.addView(quick);
    }

    private void renderVolume(int volume) {
        currentScreen = "volume";
        mount(volumeNames.get(volume - 1), countInVolume(volume) + "টি গান", true, true);
        ScrollView scroll = pageScroll();
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        body.setPadding(dp(18), dp(4), dp(18), dp(18));
        EditText search = searchField("এই খণ্ডে গান খুঁজুন"); activeSearch = search;
        body.addView(search, matchHeight(52));
        addSpace(body, 10);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        renderSearchResults(results, "", volume);
        search.addTextChangedListener(watcher(text -> renderSearchResults(results, text, volume)));
    }

    private void renderIndex() {
        currentScreen = "index";
        mount("গানের সূচী", "খণ্ড বেছে নিয়ে গান পড়ুন", false, true);
        ScrollView scroll=pageScroll(); LinearLayout body=(LinearLayout)scroll.getChildAt(0); body.setPadding(dp(18),dp(6),dp(18),dp(72));
        EditText search=searchField("সব খণ্ডে গান খুঁজুন"); body.addView(search,matchHeight(52)); activeSearch=search; addSpace(body,10);
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
        List<Song> matches = new ArrayList<>();
        java.util.Map<String, Integer> scores = new java.util.HashMap<>();
        String q = normalize(query);
        Song direct = q.isEmpty() ? null : resolveDirectJump(query, volumeFilter > 0 ? volumeFilter : 0);
        if (direct != null && volumeFilter > 0 && direct.volume != volumeFilter) direct = null;
        if (direct != null) {
            matches.add(direct);
        } else {
            for (Song song : songs) {
                if (volumeFilter > 0 && song.volume != volumeFilter) continue;
                int score = q.isEmpty() ? 1 : scoreSongMatch(song, query);
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

    private int scoreSongMatch(Song song, String query) {
        String compact = normalize(query);
        if (compact.isEmpty()) return 0;
        if (song.searchableText.contains(compact)) return 1000 + compact.length();
        if (song.searchableModernText.contains(compact)) return 850 + compact.length();
        String phonetic = phoneticNormalize(query);
        if (phonetic.length() >= 3 && song.searchablePhoneticText.contains(phonetic))
            return 700 + phonetic.length();

        List<String> terms = new ArrayList<>(searchWords(query));
        if (terms.isEmpty()) return 0;
        int exact = 0, modern = 0, phoneticHits = 0, fuzzy = 0;
        for (String token : terms) {
            if (token.length() < 2) continue;
            String modernToken = normalize(modernizeOldBengali(token));
            String soundToken = phoneticNormalize(token);
            if (song.searchableWords.contains(token)) exact++;
            else if (song.searchableModernWords.contains(token)
                    || song.searchableWords.contains(modernToken)
                    || song.searchableModernWords.contains(modernToken)) modern++;
            else if (song.searchablePhoneticWords.contains(soundToken)) phoneticHits++;
            else if (token.length() >= 4 && hasOneEditMatch(token, song.searchableWords)) fuzzy++;
        }
        int matched = exact + modern + phoneticHits + fuzzy;
        int usableTerms = 0;
        for (String token : terms) if (token.length() >= 2) usableTerms++;
        if (matched == 0) return 0;
        if (usableTerms >= 2 && matched < 2) return 0;
        if (usableTerms >= 4 && matched * 4 < usableTerms) return 0;
        return exact * 100 + modern * 78 + phoneticHits * 62 + fuzzy * 38
                + (matched * 25 / Math.max(1, usableTerms));
    }

    /** Search-only spelling bridge; displayed lyrics always remain in the original text. */
    private String modernizeOldBengali(String value) {
        String[][] aliases = {
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
        String result = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
        for (String[] alias : aliases) {
            result = result.replaceAll("(?<![\\p{L}\\p{M}])" + Pattern.quote(alias[0])
                    + "(?![\\p{L}\\p{M}])", Matcher.quoteReplacement(alias[1]));
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
        Matcher matcher = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+").matcher(value);
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
            TextView snippet = label(song.firstLine, 12, MUTED, false);
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
        saveReaderPosition();
        currentSong=song; lastSongId=song.id;
        LinkedHashSet<String> recent=new LinkedHashSet<>(); recent.add(song.id); recent.addAll(recentIds); recentIds=new ArrayList<>();
        int added=0; for(String id:recent){if(added++>=12)break;recentIds.add(id);}
        prefs.edit().putString("last_song",lastSongId).putString("recent",joinRecent(recentIds)).apply();
        renderReader(song);
    }

    private void renderReader(Song song) {
        currentScreen="reader"; currentSong=song;
        mount("গান "+bn(song.number),volumeNames.get(song.volume-1),true,false);
        TextView headerSearch = iconButton("⌕", 22);
        headerSearch.setOnClickListener(v -> startOfflineVoice(null));
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        ((LinearLayout) root.getChildAt(0)).addView(headerSearch, hsLp);
        FrameLayout frame=new FrameLayout(this);
        page.addView(frame,new LinearLayout.LayoutParams(-1,0,1f));
        readerScroll=new ScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.setClipToPadding(false);
        frame.addView(readerScroll,new FrameLayout.LayoutParams(-1,-1));

        float density = getResources().getDisplayMetrics().density;
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
        int availableWidth = Math.max(dp(168),
                getResources().getDisplayMetrics().widthPixels - dp(24));
        int readerWidth = Math.min(availableWidth, dp(760));
        int textMeasure = Math.max(dp(132), readerWidth - dp(36));
        final float wordTextWidthPt = 671.8f;

        FrameLayout sheet = new FrameLayout(this);
        readerScroll.addView(sheet, new ScrollView.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(10), dp(18), dp(112));
        FrameLayout.LayoutParams bodyParams = new FrameLayout.LayoutParams(
                readerWidth, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sheet.addView(body, bodyParams);

        TextView meta=label(volumeNames.get(song.volume-1)+"  ·  গান "+bn(song.number),12,GREEN,true);
        meta.setGravity(Gravity.CENTER); body.addView(meta,matchWrap()); addSpace(body,12);
        TextView title=label(song.title,22,TEXT,true); title.setGravity(Gravity.CENTER); title.setLineSpacing(dp(4),1f); body.addView(title,matchWrap());
        if (!song.category.isEmpty()) {
            addSpace(body, 4);
            TextView category = label(song.category, 13, MUTED, true);
            category.setGravity(Gravity.RIGHT);
            category.setPadding(0, 0, dp(4), 0);
            body.addView(category, matchWrap());
        }
        addSpace(body,10);
        View line=new View(this);line.setBackgroundColor(BORDER); LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(-1,dp(1)); lineLp.leftMargin=dp(22);lineLp.rightMargin=dp(22);body.addView(line,lineLp);addSpace(body,18);

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView favorite=readerAction(favorites.contains(song.id)?"♥":"♡",favorites.contains(song.id)?GREEN:TEXT,"প্রিয়");
        TextView bookmark=readerAction(bookmarks.contains(song.id)?"▣":"▢",bookmarks.contains(song.id)?GREEN:TEXT,"বুকমার্ক");
        actions.addView(favorite,new LinearLayout.LayoutParams(0,dp(62),1f));
        LinearLayout.LayoutParams bookmarkLp = new LinearLayout.LayoutParams(0,dp(62),1f);
        bookmarkLp.leftMargin = dp(8);
        actions.addView(bookmark,bookmarkLp);
        body.addView(actions);addSpace(body,18);
        favorite.setOnClickListener(v->{toggleSet(favorites,song.id,"favorites");favorite.setText(favorites.contains(song.id)?"♥\nপ্রিয়":"♡\nপ্রিয়");favorite.setTextColor(favorites.contains(song.id)?GREEN:TEXT);});
        bookmark.setOnClickListener(v->{toggleSet(bookmarks,song.id,"bookmarks");bookmark.setText(bookmarks.contains(song.id)?"▣\nবুকমার্ক":"▢\nবুকমার্ক");bookmark.setTextColor(bookmarks.contains(song.id)?GREEN:TEXT);});

        int lyricSizeSp = screenWidthDp < 360f ? 17 : (screenWidthDp < 600f ? 18 : 20);
        for (LyricBlock block : song.lyricBlocks) {
            TextView lyricLine = label(block.text, lyricSizeSp, TEXT, false);
            int gravity = block.alignment == 1 ? Gravity.CENTER_HORIZONTAL
                    : block.alignment == 2 ? Gravity.RIGHT : Gravity.LEFT;
            lyricLine.setGravity(gravity);
            if (Build.VERSION.SDK_INT >= 23) {
                lyricLine.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
                lyricLine.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
            }
            if (block.alignment == 3 && Build.VERSION.SDK_INT >= 26) {
                lyricLine.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
            }
            // Word paragraphs use single line spacing; avoid adding artificial vertical gaps.
            lyricLine.setLineSpacing(0f, 1.0f);
            int leftIndent = responsiveWordMeasure(block.leftIndentPt, textMeasure, wordTextWidthPt);
            int firstIndent = Math.max(0, leftIndent + responsiveWordMeasure(
                    block.firstLineIndentPt, textMeasure, wordTextWidthPt));
            if (!block.boldSpans.isEmpty() || leftIndent > 0 || firstIndent > 0) {
                SpannableString styled = new SpannableString(block.text);
                for (int[] span : block.boldSpans) {
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
            LinearLayout.LayoutParams lyricParams = matchWrap();
            lyricParams.topMargin = responsiveWordMeasure(block.spaceBeforePt, textMeasure, wordTextWidthPt);
            lyricParams.bottomMargin = responsiveWordMeasure(block.spaceAfterPt, textMeasure, wordTextWidthPt);
            body.addView(lyricLine, lyricParams);
        }
        addSpace(body,26);
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);
        Song previous=byPosition(song.volume,song.number-1); if(previous==null && song.volume>1) previous=byPosition(song.volume-1,countInVolume(song.volume-1));
        Song next=byPosition(song.volume,song.number+1); if(next==null && song.volume<3) next=byPosition(song.volume+1,1);
        TextView prev=navButton("‹  আগের গান"); TextView nxt=navButton("পরের গান  ›");
        nav.addView(prev,new LinearLayout.LayoutParams(0,dp(52),1f));LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(0,dp(52),1f);nextLp.leftMargin=dp(8);nav.addView(nxt,nextLp);
        prev.setEnabled(previous!=null);nxt.setEnabled(next!=null);prev.setAlpha(previous==null?0.4f:1f);nxt.setAlpha(next==null?0.4f:1f);
        Song pSong=previous,nSong=next;
        prev.setOnClickListener(v->{if(pSong!=null)openSong(pSong);});nxt.setOnClickListener(v->{if(nSong!=null)openSong(nSong);});
        body.addView(nav);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView find = navButton("⌕  গান খুঁজুন");
        TextView toTop = navButton("↑  উপরে যান");
        tools.addView(find, new LinearLayout.LayoutParams(0,dp(46),1f));
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(0,dp(46),1f);
        topLp.leftMargin = dp(8);
        tools.addView(toTop, topLp);
        addSpace(body, 18);
        body.addView(tools);
        find.setOnClickListener(v -> startOfflineVoice(null));
        toTop.setOnClickListener(v->readerScroll.smoothScrollTo(0,0));
        readerScroll.post(() -> {
            if (song.id.equals(prefs.getString("last_song", ""))) {
                int saved = Math.max(0, prefs.getInt("scroll_" + song.id, 0));
                readerScroll.scrollTo(0, saved);
            }
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
        cancelVoiceSession();
        voiceSessionCompleted = false;
        voiceListeningStarted = false;

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
        TextView hint = label("প্রথমে প্রস্তুত হওয়া পর্যন্ত অপেক্ষা করুন। তারপর শুরু করে শুধু গানের একটি লাইন বলুন।",
                13, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("বাংলা ভয়েস সার্চ")
                .setView(content)
                .setNegativeButton("বাতিল", (d, which) -> cancelVoiceSession())
                .setPositiveButton("শুরু করুন", null)
                .create();
        voiceDialog = dialog;
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (!voiceSessionCompleted && offlineVoice != null) {
                offlineVoice.cancel();
                offlineVoice = null;
            }
        });
        dialog.show();
        Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        action.setEnabled(false);
        action.setOnClickListener(v -> {
            if (offlineVoice == null) return;
            if (!voiceListeningStarted) {
                voiceListeningStarted = true;
                voiceStatus.setText("মাইক্রোফোন চালু হচ্ছে…");
                action.setEnabled(false);
                offlineVoice.beginListening();
            } else {
                voiceStatus.setText("কথা শনাক্ত করা হচ্ছে…");
                action.setText("খোঁজা হচ্ছে…");
                action.setEnabled(false);
                offlineVoice.finish();
            }
        });

        offlineVoice = new OfflineBengaliVoice(this, new OfflineBengaliVoice.Listener() {
            @Override public void onStatus(String message) {
                if (voiceStatus != null) voiceStatus.setText(message);
                if (voiceDialog == null) return;
                Button button = voiceDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (message.startsWith("প্রস্তুত")) {
                    button.setText("শুরু করুন");
                    button.setEnabled(true);
                } else if (message.startsWith("শুনছি")) {
                    button.setText("শেষ করুন ও খুঁজুন");
                    button.setEnabled(true);
                }
            }
            @Override public void onPartial(String text) {
                if (voiceStatus != null) voiceStatus.setText("শুনছি…\n" + text);
            }
            @Override public void onResult(String phrase) {
                finishVoiceDialog();
                handleVoiceSearchResult(target, phrase);
            }
            @Override public void onError(String message) {
                finishVoiceDialog();
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                if (target == null) {
                    renderIndex();
                    if (activeSearch != null) activeSearch.requestFocus();
                } else target.requestFocus();
            }
        });
        offlineVoice.start();
    }

    private void finishVoiceDialog() {
        voiceSessionCompleted = true;
        if (voiceDialog != null) {
            AlertDialog old = voiceDialog;
            voiceDialog = null;
            old.dismiss();
        }
        voiceStatus = null;
        offlineVoice = null;
        voiceListeningStarted = false;
    }

    private void cancelVoiceSession() {
        voiceSessionCompleted = true;
        if (offlineVoice != null) {
            offlineVoice.cancel();
            offlineVoice = null;
        }
        voiceStatus = null;
        if (voiceDialog != null) {
            AlertDialog old = voiceDialog;
            voiceDialog = null;
            old.dismiss();
        }
        voiceListeningStarted = false;
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
                if (next != null) openSong(next);
                return true;
            }
            if (q.contains("আগেরগান") || q.contains("পূর্বেরগান") || q.contains("previoussong")) {
                Song prev = byPosition(currentSong.volume, currentSong.number - 1);
                if (prev == null && currentSong.volume > 1) prev = byPosition(currentSong.volume - 1, countInVolume(currentSong.volume - 1));
                if (prev != null) openSong(prev);
                return true;
            }
            if (q.contains("উপরে") || q.contains("শুরুতে") || q.contains("গোড়ায়যাও")) {
                if (readerScroll != null) readerScroll.smoothScrollTo(0, 0);
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
        s=s.replaceAll("[\\p{Punct}\\p{Z}\\s\\u200c\\u200d]+","");
        return s;
    }

    private Set<String> searchWords(String value) {
        Set<String> words = new HashSet<>();
        if (value == null) return words;
        Matcher matcher = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+").matcher(value);
        while (matcher.find()) {
            String word = normalize(matcher.group());
            if (word.length() >= 2) words.add(word);
        }
        return words;
    }

    private String modernizeOldBengali(String value) {
        String[][] aliases = {
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
        String result = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
        for (String[] alias : aliases) {
            result = result.replaceAll("(?<![\\p{L}\\p{M}])" + Pattern.quote(alias[0])
                    + "(?![\\p{L}\\p{M}])", Matcher.quoteReplacement(alias[1]));
        }
        return result;
    }

    private String phoneticNormalize(String value) {
        String result = normalize(value);
        return result.replace("শ", "স").replace("ষ", "স").replace("ণ", "ন")
                .replace("ী", "ি").replace("ঈ", "ই").replace("ূ", "ু")
                .replace("ঊ", "উ").replace("ৎ", "ত");
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

    private Song byId(String id){for(Song s:songs)if(s.id.equals(id))return s;return null;}
    private Song byPosition(int volume,int number){for(Song s:songs)if(s.volume==volume&&s.number==number)return s;return null;}
    private int countInVolume(int volume){int c=0;for(Song s:songs)if(s.volume==volume)c++;return c;}
    private String volumeShort(int v){return v==1?"১ম খণ্ড":v==2?"২য় খণ্ড":"৩য় খণ্ড";}
    private String bn(int n){String s=Integer.toString(n);StringBuilder out=new StringBuilder();for(char c:s.toCharArray()){if(c>='0'&&c<='9')out.append(BN_DIGITS.charAt(c-'0'));else out.append(c);}return out.toString();}

    private ScrollView pageScroll(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);return scroll;}
    private LinearLayout bottomNav(){
        LinearLayout bar=new LinearLayout(this);bar.setBackgroundColor(SURFACE);bar.setGravity(Gravity.CENTER);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setPadding(dp(4),dp(6),dp(4),dp(6));
        String[] labels={"⌂\nহোম","☷\nসূচী","♡\nপ্রিয়","▣\nবুকমার্ক"};String[] screens={"home","index","favorites","bookmarks"};
        for(int i=0;i<labels.length;i++){final String screen=screens[i];TextView item=label(labels[i],12,screen.equals(currentScreen)?GREEN:MUTED,screen.equals(currentScreen));item.setGravity(Gravity.CENTER);bar.addView(item,new LinearLayout.LayoutParams(0,-1,1f));item.setOnClickListener(v->{if(screen.equals("home"))renderHome();else if(screen.equals("index"))renderIndex();else renderSaved(screen);});}
        return bar;
    }

    private LinearLayout cardColumn(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(roundFill(SURFACE,18));l.setElevation(dp(1));return l;}
    private LinearLayout cardRow(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setPadding(dp(14),dp(12),dp(14),dp(12));l.setBackground(roundStroke(SURFACE,BORDER,17));l.setClickable(true);l.setFocusable(true);return l;}
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
    private TextWatcher watcher(TextChangeListener callback){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){callback.onTextChanged(s.toString());}public void afterTextChanged(Editable e){}};}

    @Override public void onBackPressed(){
        if(currentScreen.equals("reader")){
            saveReaderPosition();
            if(currentSong!=null){int v=currentSong.volume;renderVolume(v);}else renderHome();
        }
        else if(!currentScreen.equals("home"))renderHome();
        else super.onBackPressed();
    }

    private void saveReaderPosition() {
        if (currentSong != null && readerScroll != null) {
            prefs.edit().putInt("scroll_" + currentSong.id, Math.max(0, readerScroll.getScrollY())).apply();
        }
    }

    @Override protected void onPause() {
        saveReaderPosition();
        if (offlineVoice != null) cancelVoiceSession();
        super.onPause();
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

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) deleteDirectoryContents(getCacheDir());
    }

    @Override protected void onDestroy(){cancelVoiceSession();super.onDestroy();}

    private static class Song {
        final String id; final int volume; final int number; final String title; final String lyrics; final String firstLine;
        final String category; final List<LyricBlock> lyricBlocks;
        String searchableText = "";
        String searchableModernText = "";
        String searchablePhoneticText = "";
        final Set<String> searchableWords = new HashSet<>();
        final Set<String> searchableModernWords = new HashSet<>();
        final Set<String> searchablePhoneticWords = new HashSet<>();
        Song(String id,int volume,int number,String title,String lyrics,String firstLine,String category,List<LyricBlock> lyricBlocks){
            this.id=id; this.volume=volume; this.number=number; this.title=title; this.lyrics=lyrics; this.firstLine=firstLine;
            this.category=category; this.lyricBlocks=lyricBlocks;
        }
    }

    private static class LyricBlock {
        final String text; final int alignment;
        final float leftIndentPt; final float firstLineIndentPt; final float rightIndentPt;
        final float spaceBeforePt; final float spaceAfterPt;
        final List<int[]> boldSpans = new ArrayList<>();
        LyricBlock(String text,int alignment,float leftIndentPt,float firstLineIndentPt,float rightIndentPt,float spaceBeforePt,float spaceAfterPt){
            this.text=text; this.alignment=alignment; this.leftIndentPt=leftIndentPt; this.firstLineIndentPt=firstLineIndentPt;
            this.rightIndentPt=rightIndentPt; this.spaceBeforePt=spaceBeforePt; this.spaceAfterPt=spaceAfterPt;
        }
    }
}
