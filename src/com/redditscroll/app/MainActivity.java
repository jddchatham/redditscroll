package com.redditscroll.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Category pills: display label -> subreddit name.
    private static final String[][] CATS = {
        {"POPULAR", "popular"},
        {"ALL", "all"},
        {"AI", "artificial"},
        {"CANADA", "canada"},
        {"NEWS", "news"},
        {"WORLD NEWS", "worldnews"},
        {"TECH", "technology"},
        {"PROG", "programming"},
        {"SCIENCE", "science"},
        {"SPACE", "space"},
        {"GADGETS", "gadgets"},
        {"DIY", "DIY"},
        {"ARDUINO", "arduino"},
        {"PI", "raspberry_pi"},
        {"3D PRINT", "3Dprinting"},
        {"ELECTRONICS", "electronics"},
        {"CYBER", "cybersecurity"},
        {"SELF-HOSTED", "selfhosted"},
        {"FUTUROLOGY", "Futurology"},
        {"GAMING", "games"},
    };

    private static final int SORT_HOT = 0;
    private static final int SORT_NEW = 1;
    private static final int SORT_TOP = 2;
    private static final int SORT_RND = 3;
    private static final int FETCH_LIMIT = 100; // one generous RSS fetch per view

    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private ArticlePager pager;
    private FrameLayout root;
    private View hintView;
    private LinearLayout errorView;
    private TextView errorTitle;
    private Button errorButton;
    private TextView offlinePill;
    private TextView loadingPill;
    private TextView matchInfo;
    private TextView[] catPills;
    private TextView sortHotPill;
    private TextView sortNewPill;
    private TextView sortTopPill;
    private TextView sortRndPill;
    private TextView refreshPill;
    private TextView searchPill;
    private LinearLayout searchExpanded;
    private EditText searchInput;

    private final java.util.Random random = new java.util.Random();
    private ArrayList<Reddit> shuffled = null;
    private ArrayList<Reddit> shuffledBase = null;
    private int pendingStartPage = -1;
    private boolean firstPageEvent = true;

    private LinearLayout topBar;

    private ImageLoader images;
    private SharedPreferences prefs;

    // Browsing (subreddit feed) state.
    private final ArrayList<Reddit> posts = new ArrayList<Reddit>();
    private final HashSet<String> postIds = new HashSet<String>();

    // Search state (inside the current subreddit).
    private final ArrayList<Reddit> searchResults = new ArrayList<Reddit>();
    private final HashSet<String> searchIds = new HashSet<String>();

    private final ArrayList<PageRef> pageRefs = new ArrayList<PageRef>();
    private ArrayList<Reddit> visible = new ArrayList<Reddit>();

    private String currentSub = "popular";
    private int sortMode = SORT_HOT;
    private String searchQuery = "";
    private boolean loading = false;
    private boolean renderedOnce = false;
    private int reqSeq = 0;

    private static final int ACCENT = 0xFFFF4500;

    private static class PageRef {
        Reddit post;
        ImageView image;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("redditscroll", MODE_PRIVATE);
        images = new ImageLoader(this);

        String savedSub = prefs.getString("lastSub", "popular");
        for (String[] cat : CATS) {
            if (cat[1].equalsIgnoreCase(savedSub)) { currentSub = cat[1]; break; }
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E11);

        pager = new ArticlePager(this);
        // Pages are touch-driven; block descendant focus so keyboard/ENTER
        // events can never trigger an offscreen page's button.
        pager.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pager.setOnPageChangeListener(new ArticlePager.OnPageChangeListener() {
            @Override
            public void onPageChanged(int page) {
                if (firstPageEvent) { // initial render fires page 0; don't treat it as user navigation
                    firstPageEvent = false;
                    return;
                }
                hideHint();
                updateVisibleImages(page);
            }
        });
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildTopBar();
        root.addView(buildOfflinePill());
        loadingPill = buildLoadingPill();
        root.addView(loadingPill);
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView);

        if (!prefs.getBoolean("hintShown", false)) {
            hintView = buildHint();
            root.addView(hintView);
        }

        setContentView(root);

        // 1. Show cached posts immediately if available.
        String cached = CacheStore.load(this, cacheKey());
        if (cached != null) {
            Reddit.Listing l = Reddit.parse(cached);
            if (l != null) {
                for (Reddit p : l.posts) {
                    String key = p.id != null ? p.id : p.link;
                    if (postIds.add(key)) posts.add(p);
                }
                rebuild(false);
            }
        }
        // 2. Fetch the freshest feed; replaces the list on success.
        loadListing();
    }

    // ------------------------------------------------------------------ data

    private String cacheKey() {
        return currentSub + "_" + sortKey();
    }

    private String sortKey() {
        switch (sortMode) {
            case SORT_NEW: return "new";
            case SORT_TOP: return "top";
            default: return "hot";
        }
    }

    private void loadListing() {
        if (loading) return;
        loading = true;
        setLoadingPill(renderedOnce, "LOADING\u2026");
        final int seq = ++reqSeq;
        final String url = Reddit.listingUrl(currentSub, sortKey(), FETCH_LIMIT);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String xml = Fetch.get(url);
                final Reddit.Listing listing = xml == null ? null : Reddit.parse(xml);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        setLoadingPill(false, null);
                        if (seq != reqSeq) return; // stale response
                        if (listing == null) {
                            onFailure(new Runnable() {
                                @Override public void run() { loadListing(); }
                            });
                        } else {
                            offlinePill.setVisibility(View.GONE);
                            CacheStore.save(MainActivity.this, cacheKey(), xml);
                            boolean hadContent = !posts.isEmpty() || !visible.isEmpty();
                            posts.clear();
                            postIds.clear();
                            shuffled = null;
                            shuffledBase = null;
                            for (Reddit p : listing.posts) {
                                String key = p.id != null ? p.id : p.link;
                                if (postIds.add(key)) posts.add(p);
                            }
                            if (posts.isEmpty() && !hadContent) {
                                showError("0 posts \u00B7 HTTP " + Fetch.lastCode
                                                + " \u00B7 " + Reddit.lastError,
                                        "RETRY", new Runnable() {
                                    @Override public void run() { loadListing(); }
                                });
                                return;
                            }
                            rebuild(false);
                        }
                    }
                });
            }
        });
    }

    private void runSearch() {
        if (loading) return;
        loading = true;
        setLoadingPill(renderedOnce, "SEARCHING\u2026");
        final String query = searchQuery;
        final int seq = ++reqSeq;
        final String url = Reddit.searchUrl(currentSub, query, searchSort(), FETCH_LIMIT);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String xml = Fetch.get(url);
                final Reddit.Listing listing = xml == null ? null : Reddit.parse(xml);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        setLoadingPill(false, null);
                        if (seq != reqSeq || !query.equals(searchQuery)) return; // stale
                        if (listing == null) {
                            onFailure(new Runnable() {
                                @Override public void run() { runSearch(); }
                            });
                        } else {
                            offlinePill.setVisibility(View.GONE);
                            searchResults.clear();
                            searchIds.clear();
                            shuffled = null;
                            shuffledBase = null;
                            for (Reddit p : listing.posts) {
                                String key = p.id != null ? p.id : p.link;
                                if (searchIds.add(key)) searchResults.add(p);
                            }
                            if (searchResults.isEmpty()) {
                                showError("No results for \u201C" + query + "\u201D in r/" + currentSub,
                                        "CLEAR", new Runnable() {
                                            @Override public void run() { clearSearch(); }
                                        });
                            }
                            rebuild(false);
                        }
                    }
                });
            }
        });
    }

    private String searchSort() {
        switch (sortMode) {
            case SORT_NEW: return "new";
            case SORT_TOP: return "top";
            default: return "relevance";
        }
    }

    private boolean autoRetried = false;

    private void onFailure(final Runnable retry) {
        if (!renderedOnce || visible.isEmpty()) {
            final boolean rateLimited = Fetch.lastCode == 429;
            final String why = Fetch.lastCode == 0
                    ? "no connection (DNS/TLS)" : "HTTP " + Fetch.lastCode;
            showError(rateLimited
                            ? "Reddit is rate-limiting (429)"
                            : "Can\u2019t reach Reddit \u00B7 " + why + " \u00B7 " + Reddit.lastError,
                    "RETRY", retry);
            if (rateLimited && !autoRetried) {
                autoRetried = true;
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (errorView.getVisibility() == View.VISIBLE) {
                            errorView.setVisibility(View.GONE);
                            retry.run();
                        }
                    }
                }, 20000);
            }
        } else {
            flashOfflinePill();
        }
    }

    private void onRefreshTap() {
        if (loading) return;
        refreshPill.animate().rotationBy(360).setDuration(500).start();
        if (searchQuery.isEmpty()) {
            loadListing();
        } else {
            runSearch();
        }
    }

    // -------------------------------------------------------------- rendering

    /** Rebuild pager pages from the active list. keepPosition follows the current post. */
    private void rebuild(boolean keepPosition) {
        String keepLink = null;
        if (keepPosition && visible.size() > 0) {
            int idx = Math.min(pager.getCurrentPage(), visible.size() - 1);
            keepLink = visible.get(idx).link;
        }

        visible = computeVisible();

        pager.removeAllViews();
        pageRefs.clear();
        for (Reddit p : visible) {
            pager.addView(buildPage(p));
        }

        int target = 0;
        if (pendingStartPage >= 0) {
            target = Math.min(pendingStartPage, Math.max(0, visible.size() - 1));
            pendingStartPage = -1;
        } else if (keepLink != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (keepLink.equals(visible.get(i).link)) { target = i; break; }
            }
        }
        pager.setPage(target, false);
        pager.requestLayout();
        updateVisibleImages(target);
        updateMatchInfo();
        renderedOnce = true;
        errorView.setVisibility(View.GONE);
        root.bringChildToFront(errorView);
        root.bringChildToFront(topBar);
        if (hintView != null) root.bringChildToFront(hintView);
    }

    private ArrayList<Reddit> computeVisible() {
        ArrayList<Reddit> base = searchQuery.isEmpty() ? posts : searchResults;
        if (sortMode == SORT_RND) {
            if (shuffled == null || shuffledBase != base) {
                shuffled = Reddit.shuffledCopy(base, random);
                shuffledBase = base;
            }
            return new ArrayList<Reddit>(shuffled);
        }
        // hot/new/top/relevance arrive pre-sorted from Reddit's own feeds.
        return new ArrayList<Reddit>(base);
    }

    private void updateVisibleImages(int center) {
        for (int i = 0; i < pageRefs.size(); i++) {
            PageRef r = pageRefs.get(i);
            if (Math.abs(i - center) <= 1) {
                images.load(r.image, r.post.imageUrl);
            } else {
                r.image.setImageDrawable(null);
            }
        }
    }

    private View buildPage(final Reddit p) {
        FrameLayout page = new FrameLayout(this);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF14181C);
        page.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59000000, 0x00000000, 0x55000000, 0xF2000000}));
        page.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.BOTTOM);
        int pad = dp(22);
        box.setPadding(pad, pad, pad, dp(30));

        String brandSub = p.subreddit == null || p.subreddit.isEmpty()
                ? currentSub : p.subreddit;
        TextView brand = new TextView(this);
        brand.setText("r/" + brandSub.toLowerCase() + "  \u00B7  REDDITSCROLL");
        brand.setTextColor(ACCENT);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.18f);
        box.addView(brand);

        TextView title = new TextView(this);
        title.setText(p.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(4);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(10), 0, 0);
        box.addView(title, tlp);

        StringBuilder meta = new StringBuilder();
        if (p.dateText != null && !p.dateText.isEmpty()) meta.append(p.dateText);
        if (p.author != null && !p.author.isEmpty()) meta.append("   \u00B7   u/").append(p.author);
        TextView date = new TextView(this);
        date.setText(meta.toString());
        date.setTextColor(0xFFA9B6BF);
        date.setTextSize(12);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(6), 0, 0);
        box.addView(date, dlp);

        if (p.excerpt != null && !p.excerpt.isEmpty()) {
            TextView excerpt = new TextView(this);
            excerpt.setText(p.excerpt);
            excerpt.setTextColor(0xFFD6DEE4);
            excerpt.setTextSize(14);
            excerpt.setMaxLines(3);
            excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.setMargins(0, dp(8), 0, 0);
            box.addView(excerpt, elp);
        }

        Button read = new Button(this);
        read.setText("OPEN ON REDDIT  \u2192");
        read.setTextColor(0xFF0B0E11);
        read.setTextSize(13);
        read.setTypeface(Typeface.DEFAULT_BOLD);
        read.setAllCaps(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(8));
        read.setBackground(bg);
        read.setPadding(dp(18), 0, dp(18), 0);
        read.setMinHeight(0);
        read.setMinimumHeight(0);
        read.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLink(p.link);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        rlp.setMargins(0, dp(16), 0, 0);
        box.addView(read, rlp);

        page.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PageRef ref = new PageRef();
        ref.post = p;
        ref.image = img;
        pageRefs.add(ref);
        return page;
    }

    // ------------------------------------------------------------------- top bar

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        topBar = bar;
        bar.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        bar.setPadding(pad, dp(12), pad, 0);

        // Row 0: category pills, horizontally scrollable.
        HorizontalScrollView catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        catPills = new TextView[CATS.length];
        for (int i = 0; i < CATS.length; i++) {
            final String sub = CATS[i][1];
            TextView pill = makePill(CATS[i][0]);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            plp.setMargins(0, 0, dp(6), 0);
            pill.setLayoutParams(plp);
            pill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setCategory(sub); }
            });
            catRow.addView(pill);
            catPills[i] = pill;
        }
        catScroll.addView(catRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(catScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        // Row 1: sort pills + refresh + search.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rlp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rlp);

        sortHotPill = makePill("HOT");
        sortHotPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_HOT); }
        });
        row.addView(sortHotPill);

        sortNewPill = makePill("NEW");
        sortNewPill.setLayoutParams(pillMargins());
        sortNewPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_NEW); }
        });
        row.addView(sortNewPill);

        sortTopPill = makePill("TOP");
        sortTopPill.setLayoutParams(pillMargins());
        sortTopPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_TOP); }
        });
        row.addView(sortTopPill);

        sortRndPill = makePill("RND");
        sortRndPill.setLayoutParams(pillMargins());
        sortRndPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSort(SORT_RND); }
        });
        row.addView(sortRndPill);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        refreshPill = makePill("\u27F3");
        LinearLayout.LayoutParams rflp = pillMargins();
        rflp.setMargins(dp(6), 0, dp(6), 0);
        refreshPill.setLayoutParams(rflp);
        refreshPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onRefreshTap(); }
        });
        row.addView(refreshPill);

        searchPill = makePill("\u2315");
        searchPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { expandSearch(); }
        });
        row.addView(searchPill);

        bar.addView(row);

        // Search input gets its own full-width line so it never competes with the pills.
        searchExpanded = buildSearchExpanded();
        searchExpanded.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        slp.setMargins(0, dp(8), 0, 0);
        searchExpanded.setLayoutParams(slp);
        bar.addView(searchExpanded);

        matchInfo = new TextView(this);
        matchInfo.setTextColor(0xFFA9B6BF);
        matchInfo.setTextSize(11);
        matchInfo.setPadding(dp(4), dp(6), dp(4), 0);
        matchInfo.setVisibility(View.GONE);
        bar.addView(matchInfo);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(bar, lp);
        stylePills();
    }

    private LinearLayout.LayoutParams pillMargins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private TextView makePill(String text) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        pill.setLayoutParams(lp);
        return pill;
    }

    private void stylePill(TextView pill, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(ACCENT);
            pill.setTextColor(0xFF0B0E11);
        } else {
            bg.setColor(0x99262B33);
            pill.setTextColor(0xFFD6DEE4);
        }
        bg.setCornerRadius(dp(17));
        pill.setBackground(bg);
    }

    private void stylePills() {
        for (int i = 0; i < CATS.length; i++) {
            stylePill(catPills[i], CATS[i][1].equals(currentSub));
        }
        stylePill(sortHotPill, sortMode == SORT_HOT);
        stylePill(sortNewPill, sortMode == SORT_NEW);
        stylePill(sortTopPill, sortMode == SORT_TOP);
        stylePill(sortRndPill, sortMode == SORT_RND);
    }

    private void setCategory(String sub) {
        if (sub.equals(currentSub)) return;
        currentSub = sub;
        prefs.edit().putString("lastSub", sub).apply();
        // Search is per-subreddit; switching clears it.
        searchQuery = "";
        searchResults.clear();
        searchIds.clear();
        if (searchInput != null) searchInput.setText("");
        hideKeyboard();
        searchExpanded.setVisibility(View.GONE);
        searchPill.setVisibility(View.VISIBLE);
        // Show cache for the new combo instantly, then fetch fresh.
        posts.clear();
        postIds.clear();
        shuffled = null;
        shuffledBase = null;
        String cached = CacheStore.load(this, cacheKey());
        if (cached != null) {
            Reddit.Listing l = Reddit.parse(cached);
            if (l != null) {
                for (Reddit p : l.posts) {
                    String key = p.id != null ? p.id : p.link;
                    if (postIds.add(key)) posts.add(p);
                }
                rebuild(false);
            }
        } else {
            pager.removeAllViews();
            pageRefs.clear();
            visible = new ArrayList<Reddit>();
            renderedOnce = false;
        }
        stylePills();
        updateMatchInfo();
        loadListing();
    }

    private void setSort(int mode) {
        if (sortMode == mode && mode != SORT_RND) return;
        sortMode = mode;
        shuffled = null;
        shuffledBase = null;
        stylePills();
        if (mode == SORT_RND) {
            // Every RND tap: fresh shuffle AND a random starting post.
            ArrayList<Reddit> base = searchQuery.isEmpty() ? posts : searchResults;
            if (!base.isEmpty()) pendingStartPage = random.nextInt(base.size());
            rebuild(false);
        } else if (searchQuery.isEmpty()) {
            loadListing();
        } else {
            runSearch();
        }
    }

    private LinearLayout buildSearchExpanded() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        searchInput = new EditText(this);
        searchInput.setHint("Search r/" + currentSub + "\u2026");
        searchInput.setHintTextColor(0xFF7C8A94);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(13);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232B34);
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        searchInput.setBackground(bg);
        searchInput.setPadding(dp(14), 0, dp(14), 0);
        row.addView(searchInput, new LinearLayout.LayoutParams(0, dp(38), 1f));

        TextView go = makePill("GO");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        glp.setMargins(dp(8), 0, 0, 0);
        go.setLayoutParams(glp);
        stylePill(go, true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitSearch(); }
        });
        row.addView(go);

        TextView close = makePill("\u2715");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        clp.setMargins(dp(8), 0, 0, 0);
        close.setLayoutParams(clp);
        stylePill(close, false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearSearch(); }
        });
        row.addView(close);

        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submitSearch();
                return true;
            }
        });
        return row;
    }

    private void expandSearch() {
        searchInput.setHint("Search r/" + currentSub + "\u2026");
        searchPill.setVisibility(View.GONE);
        searchExpanded.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        searchInput.post(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void submitSearch() {
        String q = searchInput.getText().toString().trim();
        hideKeyboard();
        if (q.isEmpty() || q.equals(searchQuery)) return;
        searchQuery = q;
        searchResults.clear();
        searchIds.clear();
        stylePills();
        updateMatchInfo();
        runSearch();
    }

    private void clearSearch() {
        searchQuery = "";
        searchResults.clear();
        searchIds.clear();
        searchInput.setText("");
        hideKeyboard();
        searchExpanded.setVisibility(View.GONE);
        searchPill.setVisibility(View.VISIBLE);
        stylePills();
        rebuild(false);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchInput != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void updateMatchInfo() {
        boolean searching = !searchQuery.isEmpty();
        matchInfo.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (!searching) return;
        matchInfo.setText("r/" + currentSub.toLowerCase()
                + " \u00B7 \u201C" + searchQuery + "\u201D \u00B7 "
                + searchResults.size() + " loaded \u00B7 pull \u27F3 for fresh");
    }

    // ------------------------------------------------------------------ overlays

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView buildOfflinePill() {
        offlinePill = new TextView(this);
        offlinePill.setText("OFFLINE \u00B7 SHOWING CACHED");
        offlinePill.setTextColor(0xFFD6DEE4);
        offlinePill.setTextSize(11);
        offlinePill.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3262B33);
        bg.setCornerRadius(dp(14));
        offlinePill.setBackground(bg);
        int p = dp(10);
        offlinePill.setPadding(p, dp(6), p, dp(6));
        offlinePill.setAlpha(0f);
        offlinePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(130), 0, 0);
        offlinePill.setLayoutParams(lp);
        return offlinePill;
    }

    private void flashOfflinePill() {
        offlinePill.setVisibility(View.VISIBLE);
        offlinePill.animate().alpha(1f).setDuration(200).start();
        offlinePill.postDelayed(new Runnable() {
            @Override
            public void run() {
                offlinePill.animate().alpha(0f).setDuration(600).start();
            }
        }, 2500);
    }

    private TextView buildLoadingPill() {
        TextView pill = new TextView(this);
        pill.setText("LOADING\u2026");
        pill.setTextColor(0xFF0B0E11);
        pill.setTextSize(11);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(14));
        pill.setBackground(bg);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, dp(24));
        pill.setLayoutParams(lp);
        return pill;
    }

    private void setLoadingPill(boolean show, String text) {
        if (text != null) loadingPill.setText(text);
        loadingPill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View buildHint() {
        TextView hint = new TextView(this);
        hint.setText("Swipe up for the next post \u2191 \u2014 pick a category above");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1C232B);
        bg.setCornerRadius(dp(18));
        hint.setBackground(bg);
        hint.setPadding(dp(20), dp(10), dp(20), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(160), 0, 0);
        hint.setLayoutParams(lp);
        return hint;
    }

    private void hideHint() {
        if (hintView == null) return;
        prefs.edit().putBoolean("hintShown", true).apply();
        final View h = hintView;
        hintView = null;
        h.animate().alpha(0f).setDuration(300).start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                root.removeView(h);
            }
        }, 350);
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF0B0E11);
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);

        errorTitle = new TextView(this);
        errorTitle.setText("Can\u2019t reach Reddit");
        errorTitle.setTextColor(Color.WHITE);
        errorTitle.setTextSize(20);
        errorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        errorTitle.setGravity(Gravity.CENTER);
        box.addView(errorTitle);

        TextView sub = new TextView(this);
        sub.setText("Check your connection and try again.");
        sub.setTextColor(0xFFA9B6BF);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(8), 0, 0);
        box.addView(sub, slp);

        errorButton = new Button(this);
        errorButton.setText("RETRY");
        errorButton.setTextColor(0xFF0B0E11);
        errorButton.setTextSize(13);
        errorButton.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(8));
        errorButton.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(160), dp(46));
        rlp.setMargins(0, dp(24), 0, 0);
        box.addView(errorButton, rlp);
        return box;
    }

    private void showError(String title, String buttonText, final Runnable action) {
        errorTitle.setText(title);
        errorButton.setText(buttonText);
        errorButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                action.run();
            }
        });
        errorView.setVisibility(View.VISIBLE);
        root.bringChildToFront(topBar); // keep search/sort usable over the error screen
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
