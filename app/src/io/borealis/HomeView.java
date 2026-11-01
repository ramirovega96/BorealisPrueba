package io.borealis;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 142, CONTENT_R = 1778;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 27;
    private static final float TILE_RADIUS = 17, TILE_MAX_W = 188;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 7, VISIBLE_ROWS = 3;
    // Apps panel (opens with Down from the shelf): 7 columns; pinned apps first, a thin line, then all apps A-Z.
    private static final float P_TOP = 106, P_RADIUS = 58, P_FIRST = 193, P_W = 196, P_H = 112, P_PITCH = 170, P_CLIP = 160, P_RADIUS_CELL = 17;
    // The panel at rest on the home screen: only its first row (the favorite apps), as a glass bar just under the greeting.
    private static final float P_CLOSED_TOP = 296, P_CLOSED_FIRST = 150, P_CLOSED_PITCH = 154, P_CLOSED_H = 196;
    private static final boolean DRIFT = true;   // the background drifts slowly all the time (set false if the TV drops frames)
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, INFO = 9, RETURN = 10;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 178, L_HELLO_BASE = 133, L_TABBAR_TOP = 216, L_TABBAR_H = 54, L_CARDS_TOP = 580, L_CARD_H = 340, L_SECTION_CY = 534;
    /** The row of recommendations: up to MAX_CARDS cards; the focused one is wide, the others compact; the row scrolls sideways. */
    private static final int MAX_CARDS = 10;
    private static final float L_WIDE_W = 784;
    private static final float L_TALL_W = 243, L_CARD_GAP = 40, L_BUTTON = 56, L_BUTTON_GAP = 32, L_BUTTON_CY = 147;
    private static final float L_PLATTER_TOP = 300, L_TILE_H = 108;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_MENU = 6, Z_PLAYER = 7;
    // Design palette: Lily Blossom, Sweet Peach, Tasty Melon (the focus ring) and Black Night (dark pills).
    private static final int LILY = Brand.LILY, PEACH = Brand.PEACH, MELON = Brand.MELON, PILL = Brand.INK;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Watched watched;
    private final NowPlaying nowPlaying = NowPlaying.get();
    private final Watched.Listener watchedListener = () -> { if (recentCards()) updateCards(); };
    private final NowPlaying.Listener playerListener = () -> onPlayer();
    private boolean accessOk;
    private final Spring[] playerFocus = {new Spring(0.3f, 0.82f, 0), new Spring(0.3f, 0.82f, 0), new Spring(0.3f, 0.82f, 0)};
    private int playerIndex = 1;
    private final RectF playerBox = new RectF();
    private final Path playerPath = new Path();
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG), barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint playerTitle, playerArtist, chipText, sectionPaint;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer, infoLayer, returnLayer;
    private int zone = Z_TOP;   // until the apps are loaded; then the focus starts on the first app (see setShelf)
    private boolean startedOnShelf;   // the first focus already went to the apps

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[10];   // 3 tabs + up to 7 corner buttons
    private final RectF[] topRects = new RectF[10];
    // HDMI menu
    private final Spring menu = new Spring(0.3f, 0.9f, 0);
    /** Each corner button blinks with the gradient fill when it is clicked. */
    private final Spring[] btnFlash = {new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0)};
    private final List<MRow> menuRows = new ArrayList<>();
    private final RectF menuRow = new RectF();
    private boolean menuOpen, menuDirty;
    private int menuIndex, menuButton;
    private long menuAt;
    private final Spring[] cardFocus = new Spring[MAX_CARDS];
    private final Spring cardScroll = new Spring(0.34f, 1f, 0);   // how far the row of cards is scrolled to the left (px)
    private final Spring[] cardGrow = new Spring[MAX_CARDS];   // 1 = the wide (expanded) card, 0 = the compact ones
    private final Rect srcRect = new Rect();
    private final Map<String, Bitmap> ownCards = new HashMap<>();   // the user's own tile art (assets/cards/<key>.png)
    private final RectF[] cardRects = new RectF[MAX_CARDS];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip;
    private Paint lHello, infoPaint, iconStroke, btnLabel, menuTag;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF(), pillRect = new RectF(), iconRect = new RectF();
    private Bitmap wave;          // the static wave background (assets/wave.jpg)
    private boolean waveTried;
    private int waveIdx = -1;     // which wallpaper `wave` holds (Prefs.wallpaper())
    private long waveStamp;       // and, for the person's own picture, which version of it
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private List<HomeApps.Item> panel = new ArrayList<>();   // pinned apps + the rest A-Z
    private int pinnedCount;
    /** Empty slots of the 7-app shelf: a dashed outline with a plus; OK on one opens the shelf editor. */
    private final Spring[] phFocus = new Spring[COLUMNS];
    { for (int i = 0; i < COLUMNS; i++) phFocus[i] = new Spring(0.3f, 0.82f, 0); }
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int phCount() { return pinnedCount > 0 && pinnedCount < COLUMNS ? COLUMNS - pinnedCount : 0; }
    private int phGrid = -1;   // with the "All Apps" panel open: which empty slot of its first row has the focus (-1 = none)
    /** The empty slot that has the focus now (0 = the first one), on the shelf or in the open panel; -1 if none. */
    private int phSlot() {
        if (phCount() == 0) return -1;
        if (sheetOpen) return phGrid;
        return zone == Z_SHELF && shelfIndex >= pinnedCount ? shelfIndex - pinnedCount : -1;
    }
    private void openShelfSettings() {
        click.open();
        activity.startActivity(new android.content.Intent().setComponent(
                new android.content.ComponentName(activity, SettingsActivity.class)).putExtra("shelf", true));
    }
    private boolean onPlaceholder() { return !sheetOpen && zone == Z_SHELF && phCount() > 0 && shelfIndex >= pinnedCount; }
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    /** A long OK on a shelf app opens its menu (move, hide, remove); "moving" is the mode where the arrows move that app. */
    private boolean moving;
    private final Runnable longOk = this::longOkFired;

    private void longOkFired() {
        if (!okDown || menuOpen) return;
        boolean here = sheetOpen ? phGrid < 0 && !panel.isEmpty() && gridIndex < panel.size()
                : zone == Z_SHELF && !onPlaceholder() && !shelf.isEmpty() && shelfIndex < shelf.size();
        if (!here) return;
        okDown = false;
        if (!ui.getBoolean("longOkUsed", false)) ui.edit().putBoolean("longOkUsed", true).apply();   // the hint is not needed any more
        openCardMenu();
    }

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case INFO: applyTone(); drawInfoPill(c); break;
                case RETURN: drawReturn(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    /** While a song plays, its card moves once a second (the progress line and nothing else). */
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            boolean live = false;
            for (Recs.Pick p : cards) live |= p != null && p.live;
            if (live && !sheetOpen) heroDirty = true;
            handler.postDelayed(this, live ? 250 : 1000);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        watched = Watched.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        infoLayer = new Layer(a, INFO);
        returnLayer = new Layer(a, RETURN);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer, infoLayer, returnLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Borealis");
        spoken.setTitle(this::a11yTitle);
        drawerLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        returnLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < MAX_CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardGrow[i] = new Spring(0.42f, 0.86f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    /** Once a day (if left on): asks GitHub for a newer version; once a day per version, a notice says how to get it. */
    private void checkForUpdate() {
        Update.check(getContext(), false, status -> noticeIfNewer());
        noticeIfNewer();
    }

    private int bellOffset() {
        return !lightButtons.isEmpty() && lightButtons.get(0).kind == HomeApps.UPDATE ? 1 : 0;
    }

    /** The bell appears (or goes) when a new version is found (or installed); the focus stays on the same button. */
    private void refreshUpdateButton() {
        boolean has = bellOffset() == 1, want = !Update.newer(getContext()).isEmpty();
        if (has == want) return;
        makeLightButtons();
        if (topIndex >= 3) topIndex = Math.max(3, topIndex + (want ? 1 : -1));
        layoutButtons();
        retarget(true);
        status.invalidate();
    }

    /** The bell was pressed: say what is new and offer to install it. */
    /** The power icon: "Restart the TV?" and, if accepted, the restart goes through the TV's own adb (HomeMode.rebootNow). */
    private void askReboot() {
        if (!(activity instanceof HomeActivity)) return;
        final Context c = getContext();
        ((HomeActivity) activity).showNotice(false,
                Ui.tr("Reiniciar a TV?", "Restart the TV?", "¿Reiniciar el TV?"),
                Ui.tr("A TV vai desligar e ligar de novo agora.", "The TV will shut down and start again now.",
                        "El TV se apagará y volverá a encenderse ahora."),
                Ui.tr("Reiniciar", "Restart", "Reiniciar"), () -> HomeMode.requestReboot(c),
                Ui.tr("Cancelar", "Cancel", "Cancelar"), null);
    }

    private void askUpdate() {
        final String version = Update.newer(getContext());
        if (version.isEmpty() || !(activity instanceof HomeActivity)) return;
        ((HomeActivity) activity).showNotice(false,
                Ui.tr("Nova versão: ", "New version: ", "Nueva versión: ") + version,
                Ui.tr("Atualizar leva menos de um minuto. O Android pedirá uma última confirmação para instalar.",
                        "Updating takes under a minute. Android will ask one last confirmation to install.",
                        "Actualizar toma menos de un minuto. Android te pedirá una última confirmación para instalar."),
                Ui.tr("Atualizar agora", "Update now", "Actualizar ahora"), this::startUpdate,
                Ui.tr("Agora não", "Not now", "Ahora no"), null);
    }

    private void startUpdate() {
        final Context c = getContext();
        if (!Update.canInstall(c)) {   // once: Android's own switch for "install apps from this source"
            if (activity instanceof HomeActivity) {
                ((HomeActivity) activity).showNotice(false,
                        Ui.tr("Uma permissão, só uma vez", "One permission, just once", "Un permiso, solo una vez"),
                        Ui.tr("Na próxima tela ative \"Permitir desta fonte\" e volte. Depois toque no sino de novo.",
                                "On the next screen turn on \"Allow from this source\" and come back. Then press the bell again.",
                                "En la siguiente pantalla activa \"Permitir de esta fuente\" y vuelve. Luego presiona la campana otra vez."),
                        "OK", () -> Update.askPermission(activity), null, null);
            }
            return;
        }
        if (Update.lowSpace(c)) {
            showToast(Update.lowSpaceMessage(c));
            return;
        }
        showToast(Ui.tr("Baixando a atualização…", "Downloading the update…", "Descargando la actualización…"));
        Update.install(c, (ok, error) -> {
            if (!ok && Update.LOW_SPACE.equals(error)) showToast(Update.lowSpaceMessage(c));
            else if (!ok) showToast(Ui.tr("Não foi possível instalar agora (" + error + ").", "Could not install right now (" + error + ").",
                    "No se pudo instalar ahora (" + error + ")."));
        });
    }

    private void noticeIfNewer() {
        refreshUpdateButton();
        String newer = Update.newer(getContext());
        if (newer.isEmpty()) return;
        String key = newer + ":" + System.currentTimeMillis() / (24L * 3600 * 1000);
        if (key.equals(Prefs.updateShown())) return;
        Prefs.setUpdateShown(key);
        showToast(Update.message(newer));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        final String fromDream = DreamInfo.takeOpenRequest(getContext());   // OK on the screensaver's widget
        if (fromDream != null && !fromDream.isEmpty()) handler.postDelayed(() -> DreamInfo.launch(activity, fromDream), 250);
        checkForUpdate();
        if (running) return;
        running = true;
        clockMinute = -1;   // the time format may have changed in Settings
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        watched.addListener(watchedListener);
        nowPlaying.addListener(playerListener);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.removeCallbacks(liveTick);
        handler.postDelayed(liveTick, 1000);
        checkNotices();
        returnPending = true;
        startReturn();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(liveTick);
        data.removeListener(this);
        recs.removeListener(this);
        watched.removeListener(watchedListener);
        nowPlaying.removeListener(playerListener);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        if (animated) homePressedAt = SystemClock.uptimeMillis();   // the Home button: the window is still arriving when we resume (see startReturn)
        zone = !shelf.isEmpty() ? Z_SHELF : !tabsVisible() && !cards.isEmpty() ? Z_CARDS : Z_TOP;   // the apps first
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        cardIndex = 0;
        menuOpen = false;
        menu.snap(0);
        if (tab != 0) selectTab(0);
        topIndex = topHome();
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        String newVersion = Update.newer(getContext());
        if (!newVersion.isEmpty()) {   // a new version: the bell, first at the left
            lightButtons.add(HomeApps.action(HomeApps.UPDATE, "update", Ui.tr("Atualizar para ", "Update to ", "Actualizar a ") + newVersion, "bell"));
        }
        lightButtons.add(HomeApps.action(HomeApps.REBOOT, "reboot", Ui.tr("Reiniciar a TV", "Restart the TV", "Reiniciar TV"), "power"));   // next to Wi-Fi
        lightButtons.add(HomeApps.action(HomeApps.WIFI, "wifi", "Wi-Fi", "wifi"));
        lightButtons.add(HomeApps.action(HomeApps.BLUETOOTH, "bluetooth", "Bluetooth", "bluetooth"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entrada de vídeo", "Video input", "Entrada de video"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings", "Configuración"), "settings"));
    }

    private static String[] tabs() {
        return Ui.es ? TABS_ES : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(815 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(menuLayer, width, Math.round(620 * kk));
        measure(infoLayer, width, Math.round(110 * kk));
        measure(returnLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(menuLayer, 0, 0);
        place(infoLayer, 0, 0);
        returnLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        startReturn();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 48 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 24 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 24 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 27 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 22 * k, L_DIM);
        chipText = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
        sectionPaint = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        sectionPaint.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
        playerTitle = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        playerArtist = Ui.text(Ui.medium, 17 * k, 0xB8FFFFFF);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        lHello = Ui.text(Ui.regular, 26 * k, L_INK);
        btnLabel = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        menuTag = Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF);
        infoPaint = Ui.numbers(Ui.medium, 22 * k, 0xFFFFFFFF);
        iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeWidth(3.0f * k);
        iconStroke.setColor(0xFFFFFFFF);
        for (Paint p : new Paint[] {lHello, lTitle, lCardTitle, lCardMeta}) p.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt), growMoving = step(cardGrow, dt);
        boolean cardScrolling = cardScroll.step(dt);
        if (growMoving || cardScrolling) layoutCards();
        if (topMoving) layoutButtons();
        boolean menuMoving = menu.step(dt), flashMoving = false;
        for (Spring sp : btnFlash) flashMoving |= sp.step(dt);
        if (flashMoving) status.invalidate();
        if (menuMoving || menuDirty) {
            menuLayer.invalidate();
            menuDirty = false;
        }
        menuLayer.setVisibility(menu.value > 0.002f || menuOpen || moving ? VISIBLE : INVISIBLE);
        if (menuOpen && up - menuAt > (screenReader() ? 30000 : 4000)) closeMenu();
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving | growMoving | cardScrolling;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | step(phFocus, dt) | step(playerFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean returning = tickReturn(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        if (DRIFT && w > 0) {   // oversized background, drifting slowly (only a view transform: nothing is redrawn)
            float t = up / 1000f;
            sky.setScaleX(1.12f);
            sky.setScaleY(1.12f);
            sky.setTranslationX((float) Math.sin(t * 0.24) * 0.045f * w);
            sky.setTranslationY((float) Math.cos(t * 0.16) * 0.045f * h);
        }
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (heroDirty || heroFading || bgFading) infoLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty || sheetMoving) sheetLayer.invalidate();   // the rows spread out while the panel rises
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            heroLayer.invalidate();
            infoLayer.invalidate();   // the date and time pill has its own layer (it stays when the panel rises)
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || returning || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, 0, lift);   // the shelf is now the panel's first row
        show(tiles, 0, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(panel.isEmpty() ? INVISIBLE : VISIBLE);
        sheetLayer.setAlpha(1);
        sheetLayer.setTranslationY((1 - sheet.value) * (P_CLOSED_TOP - P_TOP) * k);   // at rest it shows its first row at the bottom
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Recs.Pick x = a.get(i), y = b.get(i);
            if (!Recs.key(x).equals(Recs.key(y)) || x.progress != y.progress || !String.valueOf(x.meta).equals(String.valueOf(y.meta)) || !String.valueOf(x.info).equals(String.valueOf(y.info))
                    || (x.backdrop != null && !x.backdrop.equals(y.backdrop))) return false;   // "Seguir viendo" changes while the key stays
        }
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lHello.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xCCFFFFFF));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFFE0E0E6));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!sheetOpen) rebuildPanel();
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            if (!startedOnShelf && !list.isEmpty()) {   // the first time the apps are known: the focus starts on them, not on the tabs or the suggestions
                startedOnShelf = true;
                if (zone == Z_TOP || zone == Z_CARDS) {
                    zone = Z_SHELF;
                    shelfIndex = 0;
                }
            }
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** The system is low on memory: let go of the finished tile images (they are made again when a tile needs them). */
    void trimMemory() {
        crispLogos.clear();
        ownCards.clear();
        invalidate();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    private Paint hintPaint;
    private float hintK;

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < topFocus.length; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = 6 * k, spacing = 24 * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    /** The finished tile art for an app (assets/cards/<key>.png), or null to draw the tile from its logo as before. */
    private Bitmap ownCard(String key) {
        if (key == null || tileRects.length == 0 || ownCards.containsKey(key)) return null;   // containsKey: no art for this app
        // Only the finished (shrunk) tile is kept in memory; the original is read again if the size ever changes.
        int tw = Math.round(tileRects[0].width() * (1 + FOCUS_GROW));
        String ck = "card:" + key + "@" + tw;
        Bitmap fit = crispLogos.get(ck);
        if (fit != null) return fit;
        Bitmap raw = null;
        try (java.io.InputStream in = getContext().getAssets().open("cards/" + key + ".png")) {
            raw = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        if (raw == null) {
            ownCards.put(key, null);
            return null;
        }
        // Shrunk in steps to the size it has when focused (sharper than letting the GPU shrink it in one go).
        int th = Math.round(tw * raw.getHeight() / (float) raw.getWidth());
        if (tw >= raw.getWidth()) {
            fit = raw;
        } else {
            fit = downscale(raw, tw, th);
            raw.recycle();
        }
        crispLogos.put(ck, fit);
        return fit;
    }

    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Bitmap own = ownCard(it.key);
        if (own != null) {   // the design's finished tile: drawn as is (its corners are already transparent)
            c.drawBitmap(own, null, cell, bitmapPaint);
            if (fc > 0.01f) ring(c, cell, r, fc, 5 * k);
            return;
        }
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, style[0], style[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, tileFill);
            tileFill.setShader(null);
            if (fc > 0.01f) {
                tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                        Ui.alpha(0xFFFFFFFF, 0.08f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(cell, r, r, tileFill);
                tileFill.setShader(null);
            }
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        if (fc > 0.01f) ring(c, cell, r, fc, 5 * k);   // focus: gradient ring
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    /** Like roundImage, but the photo keeps its proportions (center-cropped to fill the cell), with an opacity. */
    private void coverImage(Canvas c, Bitmap image, RectF cell, float r, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float scale = Math.max(cell.width() / iw, cell.height() / ih);
        if (!Ui.LEGACY) {
            float sw = cell.width() / scale, sh = cell.height() / scale;
            srcRect.set(Math.round((iw - sw) / 2), Math.round((ih - sh) / 2), Math.round((iw + sw) / 2), Math.round((ih + sh) / 2));
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            bitmapPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            c.drawBitmap(image, srcRect, cell, bitmapPaint);
            bitmapPaint.setAlpha(255);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(cell.centerX() - iw * scale / 2, cell.centerY() - ih * scale / 2);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
    }

    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[Math.min(shelfIndex, tileRects.length - 1)].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        String label = TextUtils.ellipsize(it.label, new TextPaint(drawerLabel), room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        float x = r.left + 34 * k;
        c.drawText(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city(), x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        c.drawText(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(HomeData.brl(data.prices[i]), right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(HomeData.change(change), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && (zone == Z_TOP || zone == Z_MENU) ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(cardGrow, cards.isEmpty() ? 0 : cardIndex, snap);
        cardScroll.target = cardScrollTarget();
        if (snap) cardScroll.snap(cardScroll.target);
        if (snap) layoutCards();
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        aim(playerFocus, !sheetOpen && zone == Z_PLAYER ? playerIndex : -1, snap);
        retargetGrid(snap);
        gridDirty = true;
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        rebuildPanel();
    }

    /** Pinned apps (the shelf's) first, then every other app in alphabetical order. */
    private void rebuildPanel() {
        List<HomeApps.Item> all = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) all.add(it);
        java.util.Set<String> pinned = new java.util.HashSet<>();
        for (HomeApps.Item it : all) if (it.pkg != null) pinned.add(it.pkg);
        pinnedCount = all.size();
        List<HomeApps.Item> rest = new ArrayList<>();
        for (HomeApps.Item it : others) if (it.pkg == null || !pinned.contains(it.pkg)) rest.add(it);   // no duplicates
        java.util.Collections.sort(rest, (x, y) -> x.label.toLowerCase(java.util.Locale.ROOT).compareTo(y.label.toLowerCase(java.util.Locale.ROOT)));
        all.addAll(rest);
        int hiddenCount = Prefs.hiddenApps().size();
        if (hiddenCount > 0) all.add(hiddenCard(hiddenCount));   // at the very end: the way back to the hidden apps
        panel = all;
        gridFocus = new Spring[panel.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridIndex = Math.min(gridIndex, Math.max(0, panel.size() - 1));
        retargetGrid(true);
        phGrid = -1;
        gridDirty = true;
    }

    private void openSheet() {
        if (panel.isEmpty()) return;
        sheetOpen = true;
        gridIndex = zone == Z_SHELF ? Math.min(onPlaceholder() ? pinnedCount : shelfIndex, panel.size() - 1)   // the same app stays focused
                : zone == Z_CARDS && panel.size() > pinnedCount ? pinnedCount : 0;   // from the cards: the first of the other apps
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        if (phGrid >= 0) {   // from an empty slot: back on the same empty slot of the shelf
            shelfIndex = pinnedCount + phGrid;
            phGrid = -1;
        } else if (gridIndex < Math.min(COLUMNS, pinnedCount)) shelfIndex = gridIndex;   // back on the same app of the first row
        else shelfIndex = Math.min(shelfIndex, Math.max(0, Math.min(COLUMNS, shelf.size()) - 1));
        zone = Z_SHELF;
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private int pinnedRows() {
        return (pinnedCount + COLUMNS - 1) / COLUMNS;
    }

    private int panelRows() {
        return pinnedRows() + (panel.size() - pinnedCount + COLUMNS - 1) / COLUMNS;
    }

    private int rowOf(int i) {
        return i < pinnedCount ? i / COLUMNS : pinnedRows() + (i - pinnedCount) / COLUMNS;
    }

    private int colOf(int i) {
        return i < pinnedCount ? i % COLUMNS : (i - pinnedCount) % COLUMNS;
    }

    /** The item at a row and column (the last of the row if it is a short one). */
    private int idxAt(int row, int col) {
        int pr = pinnedRows();
        if (row < pr) return Math.min(row * COLUMNS + col, pinnedCount - 1);
        return Math.min(pinnedCount + (row - pr) * COLUMNS + col, panel.size() - 1);
    }

    /** Panel metrics blend from the resting state (home screen) to the open one. */
    private float openness() {
        return Ui.clamp(sheet.value);
    }

    private float firstTop() {
        return P_CLOSED_FIRST + (P_FIRST - P_CLOSED_FIRST) * openness();
    }

    private float pitch() {
        return P_CLOSED_PITCH + (P_PITCH - P_CLOSED_PITCH) * openness();
    }

    /** Cell of item i with the panel scrolled by `scrollRows` rows. */
    private void panelRect(int i, float scrollRows, RectF out) {
        float gap = ((CONTENT_R - MARGIN) - COLUMNS * P_W) / (COLUMNS - 1);
        float x = (MARGIN + colOf(i) * (P_W + gap)) * k;
        float y = (firstTop() + (rowOf(i) - scrollRows) * pitch()) * k;
        out.set(x, y, x + P_W * k, y + P_H * k);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < phFocus.length; i++) {
            phFocus[i].target = phSlot() == i ? 1 : 0;
            if (snap) phFocus[i].snap(phFocus[i].target);
        }
        int focusAt = sheetOpen ? (phGrid >= 0 ? -1 : gridIndex) : zone == Z_SHELF && !onPlaceholder() ? shelfIndex : -1;   // closed: the home screen's first row (an empty slot is no app of the panel)
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = i == focusAt ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        if (!sheetOpen) {
            scroll.target = 0;
            if (snap) scroll.snap(0);
            spoken.focusMoved();
            return;
        }
        float room = (h - 24 * k) / k - P_FIRST - P_H;   // how far below the first row's top a row may still sit fully visible
        float rowsBelow = room / P_PITCH;
        int r = phGrid >= 0 ? 0 : rowOf(gridIndex);
        float target = scroll.target;
        if (r < target) target = r;
        else if (r - target > rowsBelow) target = r - rowsBelow;
        float max = Math.max(0, panelRows() - 1 - rowsBelow);
        scroll.target = Math.max(0, Math.min(max, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        float top = P_TOP * k, rad = P_RADIUS * k;
        // At rest only the first row (the favorites) shows, on a glass bar; opening, the glass grows to the whole screen.
        float o = openness(), closedBottom = (P_TOP + P_CLOSED_H) * k;
        ringRect.set((MARGIN - 40) * k * (1 - o), top, w + ((CONTENT_R + 40) * k - w) * (1 - o), closedBottom + (h + rad - closedBottom) * o);
        fill.setShader(null);
        fill.setColor(0x73000000);
        c.drawRoundRect(ringRect, rad, rad, fill);
        border.setColor(0x26FFFFFF);
        c.drawRoundRect(ringRect, rad, rad, border);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * Ui.clamp(o * 2f - 0.5f)));   // the handle shows only while it opens
        tmp.set(w / 2f - 62 * k, 135 * k, w / 2f + 62 * k, 145 * k);
        c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
        drawPlayer(c);
        int n = panel.size();
        if (n == 0) return;
        c.save();
        c.clipRect(0, top + (P_CLIP * k - top) * o, w, closedBottom + (h - closedBottom) * o);   // at rest: only the first row
        float sc = scroll.value;
        if (pinnedCount > 0 && pinnedCount < n) {   // thin white line between the pinned apps and all the apps
            float y = (firstTop() + (pinnedRows() - sc) * pitch() - (pitch() - P_H) / 2f) * k;
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.5f * Ui.clamp(openness() * 2)));
            c.drawRect(MARGIN * k, y - 1 * k, CONTENT_R * k, y + 1 * k, fill);
        }
        int focused = -1;
        for (int i = 0; i < n; i++) {
            panelRect(i, sc, tmp);
            if (tmp.bottom < P_CLIP * k - 20 * k || tmp.top + sheetLayer.getTranslationY() > h) continue;
            if (sheetOpen && phGrid < 0 && i == gridIndex) focused = i;
            if (i != focused) drawPanelCell(c, i);
        }
        if (focused >= 0) drawPanelCell(c, focused);   // the focused one last, over its neighbours
        for (int i = 0; i < phCount(); i++) drawPlaceholder(c, i, sc);
        c.restore();
        // A small, quiet hint at the bottom: holding OK on an app opens its options.
        if (hintPaint == null || hintK != k) {
            hintK = k;
            hintPaint = Ui.text(Ui.regular, 22 * k, 0xFFFFFFFF);
            hintPaint.setTextAlign(Paint.Align.CENTER);
        }
        // At the bottom of the screen; it shows as the panel opens, and never again once OK has been held on an app.
        hintPaint.setAlpha(ui.getBoolean("longOkUsed", false) ? 0 : Math.round(170 * Ui.clamp(openness() * 2 - 0.5f)));
        c.drawText(Ui.tr("Segure OK sobre um app para ver as opções", "Hold OK on an app for options", "Mantén OK sobre una app para ver opciones"),
                w / 2f, h - 34 * k, hintPaint);
    }

    /**
     * The player strip on the glass above the first row of apps: cover, title and artist, and previous / pause or play /
     * next. It appears only while a session of audio exists, and fades out as the "All Apps" sheet rises.
     */
    private void drawPlayer(Canvas c) {
        if (!PLAYER_STRIP || !nowPlaying.visible() || playerTitle == null) return;
        float a = 1 - Ui.clamp(sheet.value * 3);
        if (a <= 0.01f) return;
        float top = 112 * k, size = 54 * k, x0 = MARGIN * k;
        int layer = -1;
        if (a < 0.99f) layer = c.saveLayerAlpha(x0 - 10 * k, top - 14 * k, x0 + 640 * k, top + size + 14 * k, Math.round(255 * a));
        // Cover (or a gradient with a note when the app gives none).
        playerBox.set(x0, top, x0 + size, top + size);
        if (nowPlaying.cover != null) {
            roundImage(c, nowPlaying.cover, playerBox, 12 * k);
        } else {
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setShader(new LinearGradient(x0, top, x0 + size, top + size, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(playerBox, 12 * k, 12 * k, barPaint);
            barPaint.setShader(null);
            iconFill.setStyle(Paint.Style.FILL);
            iconFill.setColor(PILL);
            float nx = playerBox.centerX(), ny = playerBox.centerY();
            c.drawCircle(nx - 6 * k, ny + 9 * k, 6 * k, iconFill);
            c.drawRect(nx - 1 * k, ny - 14 * k, nx + 3 * k, ny + 9 * k, iconFill);
            c.drawRect(nx + 3 * k, ny - 14 * k, nx + 12 * k, ny - 9 * k, iconFill);
        }
        // Title and artist.
        float tx = x0 + size + 16 * k, textW = 330 * k;
        String title = TextUtils.ellipsize(nowPlaying.title, new TextPaint(playerTitle), textW, TextUtils.TruncateAt.END).toString();
        if (nowPlaying.artist.isEmpty()) {
            c.drawText(title, tx, top + size / 2 + Ui.halfCap(playerTitle), playerTitle);
        } else {
            c.drawText(title, tx, top + size / 2 - 5 * k, playerTitle);
            c.drawText(TextUtils.ellipsize(nowPlaying.artist, new TextPaint(playerArtist), textW, TextUtils.TruncateAt.END).toString(),
                    tx, top + size / 2 + 20 * k, playerArtist);
        }
        // Controls.
        float bx = tx + textW + 14 * k;
        for (int i = 0; i < 3; i++) {
            float f = Ui.clamp(playerFocus[i].value), s = 1 + 0.1f * f;
            float cx = bx + size / 2 + i * (size + 12 * k), cy = top + size / 2, rad = size * s / 2;
            playerBox.set(cx - rad, cy - rad, cx + rad, cy + rad);
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setShader(null);
            barPaint.setColor(f > 0.01f ? Ui.alpha(0xFFFFFFFF, 0.14f + 0.8f * f) : 0x24FFFFFF);
            c.drawCircle(cx, cy, rad, barPaint);
            if (f > 0.01f) ring(c, playerBox, rad, f, 4 * k);
            drawTransport(c, i, cx, cy, 24 * k * s, f > 0.5f ? PILL : 0xFFFFFFFF);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** Previous (0), play or pause (1) and next (2), drawn as shapes. */
    private void drawTransport(Canvas c, int kind, float cx, float cy, float size, int color) {
        iconFill.setStyle(Paint.Style.FILL);
        iconFill.setColor(color);
        float u = size / 2;
        playerPath.reset();
        if (kind == 1) {
            if (nowPlaying.playing) {
                c.drawRoundRect(cx - u * 0.68f, cy - u * 0.8f, cx - u * 0.12f, cy + u * 0.8f, 2 * k, 2 * k, iconFill);
                c.drawRoundRect(cx + u * 0.12f, cy - u * 0.8f, cx + u * 0.68f, cy + u * 0.8f, 2 * k, 2 * k, iconFill);
            } else {
                playerPath.moveTo(cx - u * 0.5f, cy - u * 0.85f);
                playerPath.lineTo(cx - u * 0.5f, cy + u * 0.85f);
                playerPath.lineTo(cx + u * 0.85f, cy);
                playerPath.close();
                c.drawPath(playerPath, iconFill);
            }
        } else if (kind == 0) {
            c.drawRoundRect(cx - u * 0.85f, cy - u * 0.75f, cx - u * 0.5f, cy + u * 0.75f, 2 * k, 2 * k, iconFill);
            playerPath.moveTo(cx + u * 0.85f, cy - u * 0.8f);
            playerPath.lineTo(cx - u * 0.3f, cy);
            playerPath.lineTo(cx + u * 0.85f, cy + u * 0.8f);
            playerPath.close();
            c.drawPath(playerPath, iconFill);
        } else {
            c.drawRoundRect(cx + u * 0.5f, cy - u * 0.75f, cx + u * 0.85f, cy + u * 0.75f, 2 * k, 2 * k, iconFill);
            playerPath.moveTo(cx - u * 0.85f, cy - u * 0.8f);
            playerPath.lineTo(cx + u * 0.3f, cy);
            playerPath.lineTo(cx - u * 0.85f, cy + u * 0.8f);
            playerPath.close();
            c.drawPath(playerPath, iconFill);
        }
    }

    private boolean recentCards() {
        return Prefs.cardsMode().equals("recent");
    }

    /** The Explore / Movies / Shows tabs only exist in "Recommended" mode; "Continue watching" mode has a section title instead. */
    private boolean tabsVisible() {
        return !recentCards();
    }

    /** The first thing that can hold focus in the top row: the selected tab, or (without tabs) the first corner button. */
    private int topHome() {
        return tabsVisible() ? tab : 3 + bellOffset() + 1;   // (the power button is first; the focus starts on Wi-Fi)
    }

    private String sectionTitle = "";

    private static final boolean PLAYER_STRIP = false;   // the player strip is off until its place on the screen is decided

    private boolean playerShown() {
        return PLAYER_STRIP && nowPlaying.visible() && !sheetOpen;
    }

    /** The audio session changed (a song, play/pause, or it ended): the strip redraws, and the focus leaves it if it went away. */
    private void onPlayer() {
        if (!nowPlaying.visible() && zone == Z_PLAYER) {
            zone = Z_SHELF;
            retarget(false);
        }
        gridDirty = true;
    }

    private void activatePlayer() {
        click.open();
        if (playerIndex == 0) nowPlaying.previous();
        else if (playerIndex == 2) nowPlaying.next();
        else nowPlaying.toggle();
    }

    /** An empty slot of the shelf: dashed rounded outline and a plus in the middle, the size of an app tile. */
    private void drawPlaceholder(Canvas c, int slot, float sc) {
        // Row 0, the column after the last pinned app (panelRect would treat the index as an unpinned app on row 1).
        float gap = ((CONTENT_R - MARGIN) - COLUMNS * P_W) / (COLUMNS - 1);
        float px = (MARGIN + (pinnedCount + slot) * (P_W + gap)) * k, py = (firstTop() - sc * pitch()) * k;
        tmp.set(px, py, px + P_W * k, py + P_H * k);
        float f = phFocus[slot].value, fc = Ui.clamp(f), s = 1 + 0.07f * f;
        float cx = tmp.centerX(), cy = tmp.centerY(), hw = tmp.width() * s / 2, hh = tmp.height() * s / 2, rad = P_RADIUS_CELL * k * s;
        RectF box = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        if (fc > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.14f * fc));
            c.drawRoundRect(box, rad, rad, fill);
        }
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth((2.5f + 1.5f * fc) * k);
        dashPaint.setStrokeCap(Paint.Cap.ROUND);
        dashPaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {14 * k, 12 * k}, 0));
        dashPaint.setColor(Ui.alpha(0xFFFFFFFF, 0.45f + 0.5f * fc));
        RectF in = new RectF(box);
        in.inset(2 * k, 2 * k);
        c.drawRoundRect(in, rad, rad, dashPaint);
        dashPaint.setPathEffect(null);
        float arm = Math.min(hw, hh) * 0.26f;
        dashPaint.setStrokeWidth(5 * k);
        c.drawLine(cx - arm, cy, cx + arm, cy, dashPaint);
        c.drawLine(cx, cy - arm, cx, cy + arm, dashPaint);
    }

    private void drawPanelCell(Canvas c, int i) {
        HomeApps.Item it = panel.get(i);
        RectF base = new RectF();
        panelRect(i, scroll.value, base);
        float f = gridFocus[i].value, fc = Ui.clamp(f);
        Bitmap img = ownCard(it.key);
        if (img == null) img = it.tile;
        drawCard(c, img, base, P_RADIUS_CELL * k, f, 0.07f);
        if (sheetOpen && i == gridIndex) focusedCell.set(cell);
        if (fc > 0.01f) ring(c, cell, P_RADIUS_CELL * k * (1 + 0.07f * f), fc, 4.5f * k);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                    Ui.alpha(0xFFFFFFFF, 0.1f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen || refreshSheet) {
                    refreshSheet = false;
                    setOthers(o);
                }
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (moving) {   // moving a shelf app: left and right move it; OK or Back finish
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) moveShelfApp(kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || kc == KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0) {
                    moving = false;
                    menuDirty = true;
                    click.open();
                    showToast(Ui.tr("Posição guardada.", "Position saved.", "Posición guardada."));
                }
                return true;
            }
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        handler.removeCallbacks(longOk);
                        handler.postDelayed(longOk, 650);   // held: the shelf app's menu
                    }
                } else {
                    handler.removeCallbacks(longOk);
                    if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    // MARK: - The "Hidden apps" card at the end of All Apps

    private Bitmap hiddenTile;

    private HomeApps.Item hiddenCard(int count) {
        if (hiddenTile == null) {
            Bitmap b = Bitmap.createBitmap(HomeApps.TILE_W, HomeApps.TILE_H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, HomeApps.TILE_W, HomeApps.TILE_H, new int[] {0xFF3A3346, 0xFF221F2B}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, HomeApps.TILE_W, HomeApps.TILE_H, p);
            p.setShader(null);
            Symbols.draw(getContext(), cv, "visibility_off", HomeApps.TILE_W / 2f, HomeApps.TILE_H / 2f, 100, 0xE6FFFFFF);
            hiddenTile = b;
        }
        HomeApps.Item it = new HomeApps.Item();
        it.kind = HomeApps.HIDDEN_CARD;
        it.key = "hidden-apps";
        it.label = Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas") + " (" + count + ")";
        it.tile = hiddenTile;
        return it;
    }

    // MARK: - Card menu (long OK on an app): the same dropdown as HDMI and Settings, hanging from the card

    private boolean cardMenu, menuAbove, refreshSheet;
    private final RectF menuCard = new RectF();
    private float menuTy;
    private final Paint moveArrow = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Where the focused card is, in screen coordinates (the shelf's tile, or the cell of the open panel). */
    private boolean cardRect(RectF out) {
        if (sheetOpen) {
            if (phGrid >= 0 || gridIndex < 0 || gridIndex >= panel.size()) return false;
            panelRect(gridIndex, scroll.value, out);
        } else {
            if (shelfIndex < 0 || shelfIndex >= shelf.size()) return false;
            panelRect(shelfIndex, 0, out);
        }
        out.offset(0, sheetLayer.getTranslationY());
        return true;
    }

    private MRow cardRow(String symbol, String label, Runnable run) {
        MRow r = new MRow();
        r.symbol = symbol;
        r.label = label;
        r.run = run;
        return r;
    }

    private void openCardMenu() {
        RectF r = new RectF();
        if (!cardRect(r)) return;
        final HomeApps.Item it = sheetOpen ? panel.get(gridIndex) : shelf.get(shelfIndex);
        if (it.pkg == null || it.kind != HomeApps.APP) return;
        boolean pinned = !sheetOpen || gridIndex < pinnedCount;
        menuRows.clear();
        if (pinned) {   // an app of the shelf: move it or take it off
            menuRows.add(cardRow("swap_horiz", Ui.tr("Mover", "Move", "Mover"), this::startMoving));
            menuRows.add(cardRow("close", Ui.tr("Remover da prateleira", "Remove from the shelf", "Quitar del estante"), () -> removeFromShelf(it)));
        } else {        // any other app of the panel: hide it
            menuRows.add(cardRow("visibility_off", Ui.tr("Ocultar", "Hide", "Ocultar"), () -> hideApp(it)));
        }
        float boxH = menuRows.size() * 62 * k + 28 * k;
        menuAbove = r.bottom + 30 * k + boxH + 12 * k > h;   // no room below: it hangs above the card
        menuCard.set(r);
        menuTy = menuAbove ? r.top - 30 * k - boxH : r.bottom;
        menuLayer.setTranslationY(menuTy);
        cardMenu = true;
        menuOpen = true;
        menuIndex = 0;
        menuAt = SystemClock.uptimeMillis();
        menu.target = 1;
        menuDirty = true;
        click.open();
        spoken.focusMoved();
    }

    private void activateCardMenu() {
        if (menuIndex < 0 || menuIndex >= menuRows.size()) return;
        Runnable run = menuRows.get(menuIndex).run;
        cardMenu = false;
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        click.open();
        if (run != null) run.run();
    }

    private List<String> shelfPackagesNow() {
        List<String> out = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) out.add(it.pkg);
        return out;
    }

    private void removeFromShelf(HomeApps.Item it) {
        List<String> pkgs = shelfPackagesNow();
        pkgs.remove(it.pkg);
        Prefs.setShelf(pkgs);
        showToast(it.label + Ui.tr(" saiu da prateleira.", " is off the shelf.", " salió del estante."));
        refreshSheet = true;
        reload();
    }

    private void hideApp(HomeApps.Item it) {
        List<String> hidden = Prefs.hiddenApps();
        if (!hidden.contains(it.pkg)) hidden.add(it.pkg);
        Prefs.setHiddenApps(hidden);
        showToast(it.label + Ui.tr(" oculto. Para mostrar: Configurações → Apps ocultos.",
                " hidden. To show it again: Settings → Hidden apps.",
                " oculta. Para mostrarla: Ajustes → Apps ocultas."));
        refreshSheet = true;
        reload();
    }

    private void startMoving() {
        moving = true;
        updateMoveArrows();
        showToast(Ui.tr("Mova com as setas esquerda e direita. OK para terminar.",
                "Move it with the left and right arrows. OK to finish.",
                "Muévela con las flechas izquierda y derecha. OK para terminar."));
    }

    /** The arrows around the card being moved live in the menu layer, which is moved to the card's height. */
    private void updateMoveArrows() {
        RectF r = new RectF();
        if (cardRect(r)) {
            menuTy = r.centerY() - 120 * k;
            menuLayer.setTranslationY(menuTy);
        }
        menuDirty = true;
    }

    private void drawMoveArrows(Canvas c) {
        RectF r = new RectF();
        if (!cardRect(r)) return;
        r.offset(0, -menuTy);
        int index = sheetOpen ? gridIndex : shelfIndex, count = sheetOpen ? pinnedCount : shelf.size();
        moveArrow.setStyle(Paint.Style.STROKE);
        moveArrow.setStrokeWidth(4.5f * k);
        moveArrow.setStrokeCap(Paint.Cap.ROUND);
        moveArrow.setStrokeJoin(Paint.Join.ROUND);
        for (int side = -1; side <= 1; side += 2) {
            if ((side < 0 && index <= 0) || (side > 0 && index >= count - 1)) continue;
            float cx = side < 0 ? r.left + 30 * k : r.right - 30 * k, cy = r.centerY();
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.55f));
            c.drawCircle(cx, cy, 24 * k, fill);
            Path chevron = new Path();
            chevron.moveTo(cx - side * 5 * k, cy - 11 * k);
            chevron.lineTo(cx + side * 6 * k, cy);
            chevron.lineTo(cx - side * 5 * k, cy + 11 * k);
            moveArrow.setColor(0xFFFFFFFF);
            c.drawPath(chevron, moveArrow);
        }
    }

    /** One step left or right for the app being moved: the shelf (and the open panel) change on screen at once and it is saved. */
    private void moveShelfApp(int dir) {
        int from = sheetOpen ? gridIndex : shelfIndex, to = from + dir;
        int limit = sheetOpen ? pinnedCount : shelf.size();
        if (from < 0 || from >= shelf.size() || to < 0 || to >= limit) return;
        List<HomeApps.Item> copy = new ArrayList<>(shelf);
        java.util.Collections.swap(copy, from, to);
        setShelf(copy);
        if (sheetOpen) {
            rebuildPanel();
            gridIndex = to;
            retargetGrid(false);
            gridDirty = true;
        }
        shelfIndex = Math.min(to, Math.max(0, shelf.size() - 1));
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item it : copy) if (it.pkg != null) pkgs.add(it.pkg);
        Prefs.setShelf(pkgs);
        retarget(false);
        updateMoveArrows();
        click.move();
    }

    private void back() {
        if (menuOpen && cardMenu) {
            closeMenu();
            click.move();
            return;
        }
        if (zone == Z_MENU) {
            closeMenu();
            click.move();
            return;
        }
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (menuOpen && cardMenu) {
            moveMenu(dx, dy);
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (zone == Z_MENU) {
            moveMenu(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int slots = Math.min(shelf.size(), COLUMNS) + phCount();   // the apps and the empty slots
                    if (slots < 2) return;
                    int next = (shelfIndex + dx + slots) % slots;   // endless: past the last one comes the first, and the other way round
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[Math.min(shelfIndex, tileRects.length - 1)].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (panel.isEmpty()) return;
                    openSheet();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = panel.size();
        if (n == 0) {
            if (dy < 0) closeSheet();
            return;
        }
        if (phGrid >= 0) {   // on an empty slot of the first row
            if (dx != 0) {
                int slot = phGrid + dx;
                if (slot < 0) {
                    phGrid = -1;
                    gridIndex = Math.max(0, pinnedCount - 1);
                } else if (slot < phCount()) {
                    phGrid = slot;
                } else {   // endless: past the last empty slot comes the first app of the row
                    phGrid = -1;
                    gridIndex = 0;
                }
            } else if (dy < 0) {
                closeSheet();
                click.move();
                return;
            } else {
                if (panelRows() < 2) return;
                int col = pinnedCount + phGrid;
                phGrid = -1;
                gridIndex = idxAt(1, col);
            }
            retargetGrid(false);
            click.move();
            gridDirty = true;
            return;
        }
        int r = rowOf(gridIndex), col = colOf(gridIndex);
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && rowOf(candidate) == r) next = candidate;
            else if (dx > 0 && r == 0 && phCount() > 0 && gridIndex == pinnedCount - 1) {   // past the last app: the first empty slot
                phGrid = 0;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            } else if (dx < 0 && r == 0 && phCount() > 0 && gridIndex == 0) {   // before the first app: the last empty slot
                phGrid = phCount() - 1;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            } else {
                next = idxAt(r, dx > 0 ? 0 : COLUMNS - 1);   // endless along the row, as on the shelf
            }
        } else {
            int targetRow = r + dy;
            if (targetRow < 0) {   // up from the first row closes the panel
                closeSheet();
                click.move();
                return;
            }
            if (targetRow == 0 && phCount() > 0 && col >= pinnedCount) {   // up into an empty slot of the first row
                phGrid = col - pinnedCount;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            }
            if (targetRow < panelRows()) next = idxAt(targetRow, col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        if (menuOpen && cardMenu) {
            activateCardMenu();
            return;
        }
        if (zone == Z_MENU) {
            activateMenu();
            return;
        }
        if (zone == Z_PLAYER && !sheetOpen) {
            activatePlayer();
            return;
        }
        if (sheetOpen && phGrid >= 0) {   // an empty slot: take the person to the shelf editor
            openShelfSettings();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (panel.isEmpty()) return;
            it = panel.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            if (p.music && nowPlaying.visible() && !nowPlaying.playing) nowPlaying.toggle();   // the song goes on where it was
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = cardRects[cardIndex];
        } else {
            if (onPlaceholder() || shelf.isEmpty() || shelfIndex >= shelf.size()) {
                if (onPlaceholder()) openShelfSettings();   // an empty slot: take the person to the shelf editor
                return;
            }
            it = shelf.get(shelfIndex);
            r = new RectF();
            panelRect(shelfIndex, 0, r);
            r.offset(0, sheetLayer.getTranslationY());
        }
        click.open();
        if (!sheetOpen && zone == Z_TOP && topIndex >= 3 && topIndex - 3 < btnFlash.length) {   // the corner button blinks
            btnFlash[topIndex - 3].snap(1);
            btnFlash[topIndex - 3].target = 0;
            status.invalidate();
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.HIDDEN_CARD) {   // the card at the end of All Apps
            try {
                activity.startActivity(new android.content.Intent(activity, SettingsActivity.class).putExtra("hidden", true));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        if (it.kind == HomeApps.UPDATE) {   // the bell
            askUpdate();
            return;
        }
        if (it.kind == HomeApps.REBOOT) {   // the power icon
            askReboot();
            return;
        }
        if (!sheetOpen && zone == Z_TOP && (it.kind == HomeApps.INPUTS || it.kind == HomeApps.WIFI || it.kind == HomeApps.BLUETOOTH || it.kind == HomeApps.SETTINGS)) {   // only the corner buttons open menus
            openMenu(it.kind);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
        else if (zone == Z_SHELF && it.kind == HomeApps.APP) rememberLaunch(it, r);   // the card zooms back when we return
    }

    // MARK: - Coming back from an app: its card zooms down from the full screen to its place

    private HomeApps.Item returnItem;
    private final RectF returnTo = new RectF(), returnBox = new RectF();
    private boolean returning, zoomShown, returnPending;
    private final Spring returnSpring = new Spring(0.5f, 0.92f, 0);   // coming back: unhurried
    private static final long RETURN_MAX_MS = 20 * 60_000L, HOME_HOLD_MS = 320;
    private long homePressedAt;
    private long returnHoldUntil, returnHoldMax;
    private boolean waitingFocus;

    /** An app was opened from the shelf or the panel: remember its card (in case this process is gone when we come back). */
    private void rememberLaunch(HomeApps.Item it, RectF r) {
        returnItem = it;
        returnTo.set(r);
        ui.edit().putString("ret_pkg", it.pkg).putFloat("ret_l", r.left).putFloat("ret_t", r.top)
                .putFloat("ret_r", r.right).putFloat("ret_b", r.bottom).putLong("ret_at", System.currentTimeMillis()).apply();
    }

    /** The home screen is back: if an app was opened from here a moment ago, its card shrinks down into its place. */
    private void startReturn() {
        if (!returnPending || w == 0) return;   // too early (the view has no size yet): onSizeChanged calls this again
        returnPending = false;
        boolean viaHome = SystemClock.uptimeMillis() - homePressedAt < 1500;
        HomeApps.Item it = returnItem;
        RectF from = new RectF(returnTo);
        long at = ui.getLong("ret_at", 0);
        String pkg = ui.getString("ret_pkg", null);
        if (it == null && pkg != null) {   // this process was restarted while the app ran
            for (HomeApps.Item p : shelf) if (pkg.equals(p.pkg)) it = p;
            for (HomeApps.Item p : panel) if (it == null && pkg.equals(p.pkg)) it = p;
            from.set(ui.getFloat("ret_l", 0), ui.getFloat("ret_t", 0), ui.getFloat("ret_r", 0), ui.getFloat("ret_b", 0));
        }
        ui.edit().remove("ret_pkg").apply();
        // The soft Home (no ADB): the window arrives late and the full-screen card would sit there waiting for it. No zoom.
        if (HomeMode.soft(getContext()) && !HomeMode.isFree(getContext())) {
            endReturn();
            return;
        }
        boolean tooOld = at == 0 || System.currentTimeMillis() - at > RETURN_MAX_MS;
        AppLog.w("zoom back: item=" + (it == null ? "none" : it.pkg) + (tooOld ? " (too long ago)" : ""));
        if (it == null || tooOld || (panel.isEmpty() && shelf.isEmpty())) {
            endReturn();
            return;
        }
        if (!sheetOpen && sheet.value > 0.01f) {   // Home was pressed with the panel open: it closes now, behind the card
            sheet.snap(0);
            updateMotion();
        }
        if (sheetOpen) {
            returnTo.set(from);
        } else {
            // Its place in the panel's first row (the shelf) or, if it isn't there, the middle of that row.
            int idx = -1;
            String want = pkg != null ? pkg : it.pkg;
            for (int i = 0; i < shelf.size() && i < COLUMNS; i++) if (want != null && want.equals(shelf.get(i).pkg)) idx = i;   // the shelf is the panel's first row
            if (idx >= 0) {
                panelRect(idx, 0, returnTo);
            } else {
                panelRect(0, 0, returnTo);
                returnTo.offset(w / 2f - returnTo.centerX(), 0);
            }
            returnTo.offset(0, (P_CLOSED_TOP - P_TOP) * k);
        }
        returnItem = it;
        zoomShown = true;
        returning = true;
        returnSpring.snap(1);
        returnSpring.target = 0;
        // With the Home button the home window is still fading in when we resume: hold the full-screen card a moment so
        // the zoom is seen (with Back the window is already there).
        // The card waits (full screen) until our window is really the one on screen: how soon that happens depends on
        // the app we leave and on Home or Back. Then a short beat, and it shrinks.
        long now = SystemClock.uptimeMillis();
        waitingFocus = !hasWindowFocus();
        returnHoldMax = now + 800;
        returnHoldUntil = now + (viaHome ? HOME_HOLD_MS : 120);
        returnLayer.setVisibility(VISIBLE);
        returnLayer.invalidate();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && waitingFocus) {
            waitingFocus = false;
            returnHoldUntil = Math.max(returnHoldUntil, SystemClock.uptimeMillis() + 160);
        }
    }

    private void endReturn() {
        returning = false;
        zoomShown = false;
        waitingFocus = false;
        returnItem = null;
        returnLayer.setVisibility(INVISIBLE);
    }

    private boolean tickReturn(float dt) {
        if (!returning) return false;
        long clock = SystemClock.uptimeMillis();
        if (clock < returnHoldUntil || (waitingFocus && clock < returnHoldMax)) {
            returnLayer.invalidate();
            return true;
        }
        boolean moving = returnSpring.step(dt);
        returnLayer.invalidate();
        if (!moving) {
            returning = false;
            endReturn();   // arrived back: the real card is there
        }
        return moving;
    }

    private void drawReturn(Canvas c) {
        HomeApps.Item it = returnItem;
        if (it == null || !zoomShown) return;
        float t = Math.max(0, Math.min(1.04f, returnSpring.value));
        // The full screen, a bit larger (so the card's rounded corners stay out of sight when it fills the screen).
        float grow = 0.12f * w;
        float bl = -grow, bt = -grow * h / w, br = w + grow, bb = h + grow * h / w;
        returnBox.set(returnTo.left + (bl - returnTo.left) * t, returnTo.top + (bt - returnTo.top) * t,
                returnTo.right + (br - returnTo.right) * t, returnTo.bottom + (bb - returnTo.bottom) * t);
        float r = TILE_RADIUS * k * (1 - Ui.clamp(t));
        int alpha = Math.round(255 * Ui.clamp(t * 2.2f));   // it fades out as it lands
        if (alpha <= 0) return;
        Bitmap img = ownCard(it.key);
        if (img == null) img = it.tile;
        if (img != null) {
            bitmapPaint.setAlpha(alpha);
            imageFill.setAlpha(alpha);
            roundImage(c, img, returnBox, r);
            bitmapPaint.setAlpha(255);
            imageFill.setAlpha(255);
            return;
        }
        int[] style = TILE_STYLE.get(it.key);
        tileFill.setShader(style != null ? new LinearGradient(0, returnBox.top, 0, returnBox.bottom, style[0], style[1], Shader.TileMode.CLAMP) : null);
        tileFill.setColor(0xFF161618);
        tileFill.setAlpha(alpha);
        c.drawRoundRect(returnBox, r, r, tileFill);
        tileFill.setShader(null);
        tileFill.setAlpha(255);
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9, A11Y_ALL = 8, WRAP = 10;   // A11Y_ALL: the "All apps" entry under the shelf (what Down does for the remote)

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (menuOpen) {   // the dropdown under a corner button (HDMI, Wi-Fi, Bluetooth, TV): its rows are all there is to reach
            for (int i = 0; i < menuRows.size(); i++) out.add(a11yId(Z_MENU, i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < panel.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        // The row the person is in shows all its items; the other rows show only the one the remote would land on
        // (the first the first time, then the one left behind). So TalkBack's Up and Down end where the remote ends.
        boolean other = zone != Z_TOP && zone != Z_CARDS && zone != Z_SHELF;   // a zone we don't list: everything stays reachable
        if (zone == Z_TOP || other) {
            for (int i = tabsVisible() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        } else if (topHome() < 3 + lightButtons.size()) {
            out.add(a11yId(Z_TOP, topHome()));
        }
        int nc = Math.min(MAX_CARDS, cards.size());
        if (zone == Z_CARDS || other) {
            for (int i = 0; i < nc; i++) out.add(a11yId(Z_CARDS, i));
        } else if (nc > 0) {
            out.add(a11yId(Z_CARDS, Math.min(cardIndex, nc - 1)));
        }
        if (zone == Z_SHELF || other) {
            for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        } else if (!shelf.isEmpty()) {
            out.add(a11yId(Z_SHELF, Math.min(shelfIndex, shelf.size() - 1)));
        }
        // Past the last card or app comes the first, and before the first the last (as the remote does): two nodes at the ends.
        if (!other && zone == Z_CARDS && nc >= 2) { out.add(a11yId(WRAP, 0)); out.add(a11yId(WRAP, 1)); }
        if (!other && zone == Z_SHELF && shelf.size() >= 2) { out.add(a11yId(WRAP, 2)); out.add(a11yId(WRAP, 3)); }
        if (!panel.isEmpty()) out.add(a11yId(A11Y_ALL, 0));
    }

    /** What TalkBack says for the whole screen: where the person is, never just "Borealis". */
    private String a11yTitle() {
        if (menuOpen && menuButton < lightButtons.size()) return label(lightButtons, menuButton);
        if (sheetOpen) return Ui.tr("Todas as apps", "All apps", "Todas las apps");
        return Ui.tr("Borealis, início", "Borealis, home", "Borealis, inicio");
    }

    /** The screen changed under TalkBack (a list opened or closed): ask it to move to the focused item and read it. */
    private void a11yRefocus() {
        postDelayed(spoken::refocus, 350);
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case Z_MENU: {
                if (i >= menuRows.size()) return "";
                MRow r = menuRows.get(i);
                return r.label + (r.tag == null ? "" : ", " + r.tag) + ", " + (i + 1) + Ui.tr(" de ", " of ") + menuRows.size();
            }
            case GRID:
                if (i >= panel.size()) return "";
                return label(panel, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + panel.size();
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3");
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF: return label(shelf, i);
            case WRAP: {   // 0/1: before the first / after the last card; 2/3: the same on the shelf. They read the item they lead to.
                boolean cardsRow = i < 2, after = i % 2 == 1;
                if (cardsRow) return cards.isEmpty() ? "" : pickText(cards.get(after ? 0 : Math.min(MAX_CARDS, cards.size()) - 1));
                return shelf.isEmpty() ? "" : label(shelf, after ? 0 : shelf.size() - 1);
            }
            case A11Y_ALL: return Ui.tr("Todas as apps, abrir", "All apps, open", "Todas las apps, abrir");
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case WRAP: {
                boolean cardsRow = i < 2, after = i % 2 == 1;
                RectF[] rects = cardsRow ? cardRects : tileRects;
                int count = cardsRow ? Math.min(MAX_CARDS, cards.size()) : Math.min(shelf.size(), rects.length);
                if (count < 2) return false;
                RectF edge = rects[after ? count - 1 : 0];
                float gap = 6 * k, width = 18 * k;
                if (after) a11yRect.set(edge.right + gap, edge.top, edge.right + gap + width, edge.bottom);
                else a11yRect.set(edge.left - gap - width, edge.top, edge.left - gap, edge.bottom);
                if (a11yRect.left < 0 || a11yRect.right > getWidth()) return false;
                dy = (cardsRow ? heroLayer : tiles).getTranslationY();
                break;
            }
            case A11Y_ALL: {
                if (panel.isEmpty() || sheetOpen) return false;
                a11yRect.set(0, h - 24 * k, getWidth(), h);
                dy = 0;
                break;
            }
            case Z_MENU: {
                if (!menuRowRect(i, a11yRect)) return false;
                dy = menuLayer.getTranslationY();
                break;
            }
            case GRID: {
                if (!sheetOpen || i >= panel.size()) return false;
                panelRect(i, scroll.target, a11yRect);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(MAX_CARDS, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (menuOpen) return menuRows.isEmpty() ? -1 : a11yId(Z_MENU, Math.min(menuIndex, menuRows.size() - 1));
        if (sheetOpen) return panel.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() || shelfIndex >= shelf.size() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= panel.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (group == Z_MENU) {
            if (!menuOpen || i >= menuRows.size() || i == menuIndex) return;
            menuIndex = i;
            menuAt = SystemClock.uptimeMillis();
            menuDirty = true;
            click.move();
            return;
        }
        if (group == WRAP) {
            boolean cardsRow = i < 2, after = i % 2 == 1;
            if (cardsRow) {
                int n = Math.min(MAX_CARDS, cards.size());
                if (n < 2) return;
                cardIndex = after ? 0 : n - 1;
                zone = Z_CARDS;
            } else {
                if (shelf.size() < 2) return;
                shelfIndex = after ? 0 : shelf.size() - 1;
                zone = Z_SHELF;
            }
            retarget(false);
            click.move();
            a11yRefocus();
            return;
        }
        if (group == A11Y_ALL) {   // going down from the shelf: the all-apps panel opens, as it does with the remote
            if (!sheetOpen && !panel.isEmpty()) {
                openSheet();
                click.move();
                a11yRefocus();
            }
            return;
        }
        if (sheetOpen) return;
        // Entering another row of the screen (up or down): the item the remote would land on (the first one the first time,
        // then the one left behind), not the one TalkBack found nearest.
        final int asked = i;
        if (group != zone) {
            if (group == Z_CARDS) i = Math.max(0, Math.min(cardIndex, cards.size() - 1));
            else if (group == Z_SHELF) i = Math.max(0, Math.min(shelfIndex, shelf.size() - 1));
            else if (group == Z_TOP) i = topHome();
        }
        switch (group) {
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
        spoken.focusMoved();   // the rows show/hide their items as the row changes
        if (i != asked) a11yRefocus();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 20 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 14 * k;
        }
        layoutButtons();
        layoutCards();
        heroDirty = statusDirty = true;
    }

    /** The three cards side by side: the expanded one is wide, the others compact, and each width follows its spring. */
    private void layoutCards() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, x = MARGIN * k - cardScroll.value;
        float tall = L_TALL_W * k, wide = L_WIDE_W * k;
        for (int i = 0; i < MAX_CARDS; i++) {
            float cw = tall + (wide - tall) * cardGrow[i].value;
            cardRects[i].set(x, top, x + cw, top + ch);
            x += cw + g;
        }
        heroDirty = true;
    }

    /** How far (px) the row of cards must be scrolled so the focused card starts at the left margin (the last ones stop at the right margin). */
    private float cardScrollTarget() {
        int n = Math.min(MAX_CARDS, cards.size());
        if (n <= 1) return 0;
        float total = L_WIDE_W + (n - 1) * (L_TALL_W + L_CARD_GAP);   // with the focused card wide and the others compact
        float visible = CONTENT_R - MARGIN;
        if (total <= visible) return 0;
        int f = Math.max(0, Math.min(cardIndex, n - 1));
        float left = f * (L_TALL_W + L_CARD_GAP);   // the cards before the focused one are all compact
        return Math.min(left, total - visible) * k;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        accessOk = Watched.permitted(getContext());
        if (recentCards() && tab != 0) {   // no tabs in this mode: everything
            tab = 0;
            cardIndex = 0;
        }
        List<Recs.Pick> list;
        if (recentCards()) {
            // What was being watched first; if there are fewer than three, TMDB's picks fill the rest (never an empty card).
            list = watched.picks(null);
            if (list.size() < MAX_CARDS) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Recs.Pick p : list) seen.add(p.title == null ? "" : p.title.toLowerCase(java.util.Locale.ROOT));
                for (Recs.Pick r : recs.now(null)) {
                    if (list.size() >= MAX_CARDS) break;
                    if (seen.add(r.title == null ? "" : r.title.toLowerCase(java.util.Locale.ROOT))) list.add(r);
                }
            }
        } else {
            list = recs.now(TAB_KIND[tab]);
        }
        if (list.size() > MAX_CARDS) list = new ArrayList<>(list.subList(0, MAX_CARDS));
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        if (!recentCards()) {   // the three tabs preloaded (as many as fit in memory): switching tabs is instant
            int budget = recs.capacity();
            for (Recs.Pick p : cards) {
                recs.backdrop(p);
                budget--;
            }
            for (Recs.Pick p : recs.wanted()) {
                if (budget <= 0) break;
                if (!cards.contains(p)) {
                    recs.backdrop(p);
                    budget--;
                }
            }
        }
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        boolean any = false;
        for (Recs.Pick p : cards) any |= p.left != null;
        sectionTitle = any ? Ui.tr("Continuar reproduzindo", "Continue playing", "Continuar reproduciendo")
                : Ui.tr("Sugestões para você", "Suggestions for you", "Sugerencias para ti");
        if (!tabsVisible() && zone == Z_TOP && topIndex < 3) {   // the tabs are gone: the focus goes to the first corner button
            topIndex = 3;
            retarget(false);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardIndex = 0;   // a new tab opens with its first card expanded
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        int wallpaper = Prefs.wallpaper();
        long stamp = Prefs.wallpaperStamp();
        if (wallpaper != waveIdx || stamp != waveStamp) {   // another wallpaper picked in Settings (or the person's own picture replaced)
            waveIdx = wallpaper;
            waveStamp = stamp;
            wave = null;
            waveTried = false;
        }
        if (wave == null && !waveTried) {
            waveTried = true;
            try (java.io.InputStream in = Prefs.openWallpaper(getContext(), Prefs.wallpaperFiles()[wallpaper])) {
                // Decoded at the screen's size (the file is 1920 wide): a 720p TV needs less than half the memory.
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                if (w > 0 && w < 1920) {
                    o.inScaled = true;
                    o.inDensity = 1920;
                    o.inTargetDensity = w;
                }
                wave = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) {
            }
        }
        if (wave != null) {   // the design's wave background replaces the blurred photo
            imagePaint.setColorFilter(v);
            drawImage(c, wave, 1);
            imagePaint.setColorFilter(null);
            return;
        }
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        String name = Prefs.name();
        String hi = name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!")
                : Ui.tr("Olá, " + name + "!", "Hi, " + name + "!", "¡Hola, " + name + "!");
        String ask = Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?");
        // The profile avatar, round, to the left of the greeting (the text moves over to make room).
        float off = 0;
        String avatar = Avatars.current();
        if (!avatar.isEmpty()) {
            float ar = 44 * k;
            float acx = MARGIN * k + ar, acy = (L_HELLO_BASE + L_TITLE_BASE) / 2f * k - 12 * k;
            Avatars.draw(c, getContext(), avatar, acx, acy, ar, 1f);
            ringPaint.setStyle(Paint.Style.STROKE);   // the white border the shortcuts have
            ringPaint.setShader(null);
            ringPaint.setStrokeWidth(3 * k);
            ringPaint.setColor(0xFFFFFFFF);
            c.drawCircle(acx, acy, ar + 1.5f * k, ringPaint);
            off = 2 * ar + 26 * k;
        }
        c.drawText(hi, MARGIN * k + off - Ui.leftBearing(lHello, hi), L_HELLO_BASE * k, lHello);
        c.drawText(ask, MARGIN * k + off - Ui.leftBearing(lTitle, ask), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        if (tabsVisible()) {
            for (int i = 0; i < 3; i++) {
                RectF r = topRects[i];
                float focus = Ui.clamp(topFocus[i].value);
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                if (i == tab) {
                    // Selected tab: filled with the lily → peach → melon gradient; focus adds a white border.
                    fill.setShader(new LinearGradient(cell.left, 0, cell.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                    c.drawRoundRect(cell, rr, rr, fill);
                    fill.setShader(null);
                    if (focus > 0.01f) {
                        border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * focus));
                        tmp.set(cell.left + k, cell.top + k, cell.right - k, cell.bottom - k);
                        c.drawRoundRect(tmp, rr - k, rr - k, border);
                    }
                } else {
                    // Other tabs: dark pill; focus only adds the gradient ring (like the corner buttons), no fill.
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(PILL, 0.7f + 0.25f * focus));
                    c.drawRoundRect(cell, rr, rr, fill);
                    if (focus > 0.01f) ring(c, cell, rr, focus, 4.5f * k);
                }
                Paint p = i == tab ? lTabOn : lTab;
                if (i == tab) lTabOn.setColor(0xFFFFFFFF);
                int old = p.getColor();
                if (i != tab && focus > 0.01f) p.setColor(Ui.alpha(0xFFFFFFFF, 0.75f + 0.25f * focus));
                float tx = r.centerX() - p.measureText(tabs()[i]) / 2, ty = r.centerY() + Ui.halfCap(p);
                c.drawText(tabs()[i], tx, ty, p);
                p.setColor(old);
            }
        } else {
            float ty = L_SECTION_CY * k + Ui.halfCap(sectionPaint);   // above the cards, under the favorite apps
            c.drawText(sectionTitle, MARGIN * k - Ui.leftBearing(sectionPaint, sectionTitle), ty, sectionPaint);
        }
        int top = -1;
        int shownCards = recentCards() ? Math.min(MAX_CARDS, cards.size()) : Math.max(3, Math.min(MAX_CARDS, cards.size()));
        for (int i = 0; i < shownCards; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < shownCards; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (cards.isEmpty() && recentCards()) drawNothingToContinue(c);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        if (base.right < -60 * k || base.left > w + 60 * k) return;   // scrolled out of the screen: nothing to draw (or to load)
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        if (p == null && recentCards()) return;    // fewer than the row holds: no empty boxes
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        if (b != null && b.tall != null && b.wide != null) {
            float e = Ui.clamp(cardGrow[i].value);
            coverImage(c, b.tall, cell, r, 1f);                      // compact crop underneath, wide crop fades in on top
            if (e > 0.01f) coverImage(c, b.wide, cell, r, e);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(cell.left, cell.top, cell.left + cell.width() * 0.5f, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.22f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        float shown = progressOf(p);
        if (p != null && shown >= 0) drawProgress(c, cell, r, shown, p.left, Ui.clamp(cardGrow[i].value), Math.max(f, Ui.clamp(cardGrow[i].value)), playLabel(p),
                p.live && nowPlaying.dur > 0 ? NowPlaying.remaining(nowPlaying.dur - nowPlaying.position()) : null);
        if (p != null) drawChip(c, cell, p.chip != null ? p.chip : Recs.serviceName(p));
        ring(c, cell, r, f, 5 * k);
        float vis = Math.max(f, Ui.clamp(cardGrow[i].value));   // the open card always shows its button; the others when focused
        if (vis > 0.05f && p != null) drawPlay(c, cell, Ui.clamp(cardGrow[i].value), f, vis, playLabel(p));
        if (p == null) return;
        TextPaint tp = new TextPaint(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, tp, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 40 * k, lCardTitle);
        String line = p.meta;
        if (p.left != null) {   // "Seguir viendo": time left first on the compact card, last on the wide one (all in one line)
            boolean wide = cardGrow[i].value > 0.5f;
            line = p.detail == null || p.detail.isEmpty() ? p.left : wide ? p.detail + " \u00B7 " + p.left : p.left + " \u00B7 " + p.detail;
        }
        if (line != null && !line.isEmpty()) {
            TextPaint mp = new TextPaint(lCardMeta);
            c.drawText(TextUtils.ellipsize(line, mp, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 70 * k, lCardMeta);
        }
    }

    /** The platform tag at the top left of a card ("Netflix", "Paramount+"): a small dark pill with white text. */
    private void drawChip(Canvas c, RectF card, String text) {
        if (text == null || text.isEmpty() || chipText == null) return;
        float padX = 14 * k, hgt = 34 * k, max = card.width() - 36 * k;
        String t = TextUtils.ellipsize(text, new TextPaint(chipText), max - 2 * padX, TextUtils.TruncateAt.END).toString();
        float wd = chipText.measureText(t) + 2 * padX;
        tmp.set(card.left + 16 * k, card.top + 16 * k, card.left + 16 * k + wd, card.top + 16 * k + hgt);
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setShader(null);
        barPaint.setColor(Brand.ink(0xB3));
        c.drawRoundRect(tmp, hgt / 2, hgt / 2, barPaint);
        c.drawText(t, tmp.left + padX, tmp.centerY() + Ui.halfCap(chipText), chipText);
    }

    /** "Seguir viendo": a soft dark fade at the bottom of the card and the progress line (lily → peach → melon). */
    /** How far a card has got: for the song playing now, from the live position; otherwise what the history saved. */
    private float progressOf(Recs.Pick p) {
        if (p == null) return -1;
        if (p.live && nowPlaying.dur > 0) return Ui.clamp(nowPlaying.position() / (float) nowPlaying.dur);
        return p.progress;
    }

    /** The card's button: "Seguir escuchando" for a song, "Continuar viendo" for the rest of the history, "Ver ahora" for suggestions. */
    private static String playLabel(Recs.Pick p) {
        if (p.music && p.live) return Ui.tr("Abrir ", "Open ", "Abrir ") + (p.label == null ? "" : p.label);   // it is playing: just go there
        if (p.music) return Ui.tr("Continuar ouvindo", "Keep listening", "Seguir escuchando");
        return p.left != null ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo") : Ui.tr("Assistir", "Watch now", "Ver ahora");
    }

    private void drawProgress(Canvas c, RectF card, float radius, float progress, String label, float expanded, float vis, String button, String countdown) {
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setShader(new LinearGradient(0, card.bottom - card.height() * 0.5f, 0, card.bottom,
                0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, radius, radius, barPaint);
        // On the play button's row, to its left with a gap and centered with it (the room is always reserved, focused or not);
        // what was watched in color, what is left in white, the time left above the bar.
        float inset = 22 * k, bh = 9 * k, btnH = 50 * k, left = card.left + inset;
        float btnW = expanded > 0.5f ? 22 * k + 26 * k + 10 * k + btnLabel.measureText(button) + 24 * k : btnH;
        float right = card.right - inset - (btnW + 36 * k) * vis;   // the whole width until the button shows
        float y = card.bottom - inset - btnH / 2 - bh / 2;
        tmp.set(left, y, right, y + bh);
        barPaint.setShader(null);
        barPaint.setColor(0xE6FFFFFF);
        c.drawRoundRect(tmp, bh / 2, bh / 2, barPaint);
        tmp.set(left, y, left + Math.max(bh, (right - left) * Ui.clamp(progress)), y + bh);
        barPaint.setShader(new LinearGradient(left, 0, right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, bh / 2, bh / 2, barPaint);
        barPaint.setShader(null);
        float cw = 0;
        if (countdown != null && chipText != null) {   // the time left, counting down, at the top right of the line
            cw = chipText.measureText(countdown);
            c.drawText(countdown, right - cw, y - 12 * k, chipText);
        }
        if (label != null && !label.isEmpty() && chipText != null) {
            String t = TextUtils.ellipsize(label, new TextPaint(chipText), Math.max(0, right - left - cw - 16 * k), TextUtils.TruncateAt.END).toString();
            c.drawText(t, left, y - 12 * k, chipText);
        }
    }

    /** The cards area when "Seguir viendo" has nothing to continue yet (or lacks the permission). */
    private void drawNothingToContinue(Canvas c) {
        float top = L_CARDS_TOP * k, left = MARGIN * k, right = CONTENT_R * k, hgt = L_CARD_H * k, r = 18 * k;
        tmp.set(left, top, right, top + hgt);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.55f));
        c.drawRoundRect(tmp, r, r, fill);
        border.setColor(0x26FFFFFF);
        c.drawRoundRect(tmp, r, r, border);
        String one, two;
        if (!accessOk) {
            one = Ui.tr("Ative o acesso à reprodução para continuar o que você estava assistindo",
                    "Turn on playback access to continue what you were watching",
                    "Activa el acceso a reproducción para retomar lo que estabas viendo");
            two = Ui.tr("Configurações do Borealis \u203A Acesso à reprodução",
                    "Borealis Settings \u203A Playback access",
                    "Ajustes de Borealis \u203A Acceso a reproducción");
        } else {
            one = Ui.tr("Aqui aparecerá o que você estiver assistindo",
                    "What you watch will show up here",
                    "Aquí aparecerá lo que estés viendo");
            two = Ui.tr("Reproduza algo e volte ao início para continuar de onde parou",
                    "Play something and come back home to pick it up where you left off",
                    "Reproduce algo y vuelve al inicio para retomarlo donde lo dejaste");
        }
        float cx = tmp.centerX(), cy = tmp.centerY();
        Paint.Align a1 = lCardTitle.getTextAlign(), a2 = lCardMeta.getTextAlign();
        lCardTitle.setTextAlign(Paint.Align.CENTER);
        lCardMeta.setTextAlign(Paint.Align.CENTER);
        float maxW = tmp.width() - 80 * k;
        c.drawText(TextUtils.ellipsize(one, new TextPaint(lCardTitle), maxW, TextUtils.TruncateAt.END).toString(), cx, cy - 6 * k, lCardTitle);
        c.drawText(TextUtils.ellipsize(two, new TextPaint(lCardMeta), maxW, TextUtils.TruncateAt.END).toString(), cx, cy + 36 * k, lCardMeta);
        lCardTitle.setTextAlign(a1);
        lCardMeta.setTextAlign(a2);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.04f * f;
            float cw = r.width() * s, ch = r.height() * s, rad = ch / 2;
            cell.set(r.right - cw, r.centerY() - ch / 2, r.right, r.centerY() + ch / 2);   // grows to the left, the right edge stays
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(PILL, 0.92f * f));
                c.drawRoundRect(cell, rad, rad, fill);
                ring(c, cell, rad, f, 4.5f * k);
            }
            HomeApps.Item it = lightButtons.get(i);
            if (i < btnFlash.length && btnFlash[i].value > 0.01f) {   // the blink when it is clicked: the gradient fill
                fill.setShader(new LinearGradient(cell.left, 0, cell.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                fill.setAlpha(Math.round(255 * Ui.clamp(btnFlash[i].value)));
                c.drawRoundRect(cell, rad, rad, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.left + ch / 2, cell.centerY(), 28 * k * s, 0xFFFFFFFF);
            if (it.kind == HomeApps.UPDATE) {   // a small dot: there is something new
                fill.setShader(null);
                fill.setColor(MELON);
                c.drawCircle(cell.left + ch * 0.72f, cell.top + ch * 0.26f, 7 * k * s, fill);
            }
            if (f > 0.35f) {   // the name appears once the pill is wide enough
                btnLabel.setAlpha(Math.round(255 * Ui.clamp((f - 0.35f) / 0.65f)));
                c.drawText(it.label, cell.left + ch - 2 * k, cell.centerY() + Ui.halfCap(btnLabel), btnLabel);
                btnLabel.setAlpha(255);
            }
        }
    }

    /** Positions the corner buttons from the right edge: the focused one stretches into a pill and pushes the rest left. */
    private void layoutButtons() {
        if (btnLabel == null) return;
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            float f = Ui.clamp(topFocus[3 + i].value);
            float extra = (btnLabel.measureText(lightButtons.get(i).label) + 24 * k) * f;
            topRects[3 + i].set(right - b - extra, cy - b / 2, right, cy + b / 2);
            right -= b + extra + L_BUTTON_GAP * k;
        }
    }

    // MARK: - HDMI menu: opens under its button; Up/Down choose, OK switches, Back (or 4 s of silence) closes

    /** One row of the dropdown menu (HDMI input or Wi-Fi). */
    private static final class MRow {
        String label, tag, symbol;
        HomeApps.Item item;
        int bars = -1;          // 0..4 draws signal bars instead of the symbol
        boolean dim, dot;       // dim = grayed out; dot = shows the status dot (HDMI)
        boolean on;             // dot filled
        Runnable run;           // the card menu's action
    }

    private void openMenu(int kind) {
        cardMenu = false;
        menuLayer.setTranslationY(0);
        menuTy = 0;
        menuRows.clear();
        for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).kind == kind) menuButton = i;
        if (kind == HomeApps.INPUTS) {
            for (HomeApps.Item it : drawerItems) {   // live TV first, then the HDMI ports
                if (it.kind == HomeApps.LIVE_TV) {
                    MRow tv = new MRow();
                    tv.item = it;
                    tv.label = Ui.tr("TV aberta", "Live TV", "TV en vivo");
                    tv.symbol = "live_tv";
                    menuRows.add(tv);
                    continue;
                }
                if (it.kind != HomeApps.INPUT) continue;
                MRow r = new MRow();
                r.item = it;
                r.label = it.label;
                r.symbol = it.symbol;
                r.dot = true;
                r.on = it.connected;
                r.dim = !it.connected;
                r.tag = it.connected ? Ui.tr("Conectado", "Connected", "Conectado") : Ui.tr("Sem sinal", "No signal", "Sin señal");
                menuRows.add(r);
            }
            if (menuRows.isEmpty()) {
                showToast(Ui.tr("Sem entradas de vídeo", "No video inputs", "No hay entradas de video"));
                return;
            }
        } else if (kind == HomeApps.SETTINGS) {
            MRow tv = new MRow();
            tv.item = HomeApps.action(HomeApps.ANDROID_SETTINGS, "android_settings", Ui.tr("Configurações da TV", "TV settings", "Configuración de la TV"), "tv_settings");
            tv.symbol = "tv_settings";
            tv.label = tv.item.label;
            menuRows.add(tv);
            MRow own = new MRow();
            own.item = lightButtons.get(menuButton);
            own.symbol = "settings";
            own.label = Ui.tr("Configurações do launcher", "Launcher settings", "Configuración del launcher");
            menuRows.add(own);
        } else if (kind == HomeApps.BLUETOOTH) {
            btRows();
            MRow more = new MRow();
            more.item = lightButtons.get(menuButton);
            more.symbol = "settings";
            more.label = Ui.tr("Conectar ou ver mais", "Pair or see more", "Conectar o ver más");
            menuRows.add(more);
        } else {
            askLocationOnce();
            HomeApps.Item wifi = lightButtons.get(menuButton);
            MRow cur = wifiRow();
            cur.item = wifi;
            menuRows.add(cur);
            MRow more = new MRow();
            more.item = wifi;
            more.symbol = "settings";
            more.label = Ui.tr("Ver mais redes", "See more networks", "Ver más redes");
            menuRows.add(more);
        }
        menuOpen = true;
        menuIndex = 0;
        menuAt = SystemClock.uptimeMillis();
        zone = Z_MENU;
        menu.target = 1;
        menuDirty = true;
        retarget(false);
        spoken.focusMoved();
    }

    /** Android only gives the Wi-Fi network's name to apps that may see the location: asked once, the first time the Wi-Fi menu opens. */
    private void askLocationOnce() {
        try {
            if (getContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            android.content.SharedPreferences sp = getContext().getSharedPreferences("asked", Context.MODE_PRIVATE);
            if (sp.getBoolean("location", false)) return;
            sp.edit().putBoolean("location", true).apply();
            activity.requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION}, 77);
        } catch (RuntimeException ignored) {
        }
    }

    /** The current connection: Wi-Fi with signal, name and band; cable; or none. */
    private MRow wifiRow() {
        MRow r = new MRow();
        r.symbol = "wifi";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                r.label = Ui.tr("Conectado por cabo", "Wired connection", "Conectado por cable");
                r.tag = "Ethernet";
                return r;
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && info != null) {
                String ssid = info.getSSID();
                if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
                if (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) ssid = Ui.tr("Rede Wi-Fi", "Wi-Fi network", "Red Wi-Fi");
                int f = info.getFrequency();
                r.label = ssid;
                String band = f <= 0 ? null : f > 5900 ? "6 GHz" : f > 4900 ? "5 GHz" : "2.4 GHz";
                String on = Ui.tr("Conectado", "Connected", "Conectado");
                r.tag = band == null ? on : on + " · " + band;
                r.bars = Math.max(0, Math.min(4, android.net.wifi.WifiManager.calculateSignalLevel(info.getRssi(), 5)));
                return r;
            }
        } catch (RuntimeException ignored) {
        }
        r.label = Ui.tr("Sem conexão", "Not connected", "Sin conexión");
        r.dim = true;
        return r;
    }

    /** Connected Bluetooth devices (remote, headphones, gamepad) with their battery when Android reports it. */
    private void btRows() {
        MRow none = new MRow();
        none.symbol = "bluetooth";
        none.dim = true;
        none.item = lightButtons.get(menuButton);
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) {
                none.label = Ui.tr("Bluetooth indisponível", "Bluetooth unavailable", "Bluetooth no disponible");
                menuRows.add(none);
                return;
            }
            if (!ad.isEnabled()) {
                none.label = Ui.tr("Bluetooth desligado", "Bluetooth is off", "Bluetooth apagado");
                menuRows.add(none);
                return;
            }
            int count = 0;
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                boolean on = false;
                try {
                    on = (Boolean) d.getClass().getMethod("isConnected").invoke(d);
                } catch (Exception ignored) {
                }
                if (!on || count >= 4) continue;
                int battery = -1;
                try {
                    battery = (Integer) d.getClass().getMethod("getBatteryLevel").invoke(d);
                } catch (Exception ignored) {
                }
                MRow r = new MRow();
                r.item = none.item;
                r.label = d.getName() == null ? d.getAddress() : d.getName();
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                r.symbol = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ? "speaker"
                        : major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ? "gamepad" : "bluetooth";
                r.tag = battery >= 0 && battery <= 100 ? battery + "%" : null;
                menuRows.add(r);
                count++;
            }
            if (count == 0) {
                none.label = Ui.tr("Nenhum dispositivo conectado", "No connected devices", "Sin dispositivos conectados");
                menuRows.add(none);
            }
        } catch (RuntimeException e) {
            none.label = Ui.tr("Bluetooth indisponível", "Bluetooth unavailable", "Bluetooth no disponible");
            menuRows.add(none);
        }
    }

    /** A spoken-feedback service (TalkBack) is on: it needs more time than the 4 s of the dropdown. Our own key service doesn't count. */
    private boolean screenReader() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && !am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void closeMenu() {
        if (cardMenu) {   // the card's menu: the focus never left the card
            cardMenu = false;
            menuOpen = false;
            menu.target = 0;
            menuDirty = true;
            spoken.focusMoved();
            return;
        }
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        zone = Z_TOP;
        topIndex = 3 + menuButton;
        retarget(false);
        spoken.focusMoved();
    }

    private void moveMenu(int dx, int dy) {
        menuAt = SystemClock.uptimeMillis();
        if (dy < 0 && menuIndex == 0) {
            closeMenu();
            click.move();
        } else if (dy != 0) {
            int next = Math.max(0, Math.min(menuRows.size() - 1, menuIndex + dy));
            if (next != menuIndex) {
                menuIndex = next;
                menuDirty = true;
                click.move();
                spoken.focusMoved();
            }
        }
    }

    private void activateMenu() {
        if (menuIndex >= menuRows.size()) return;
        HomeApps.Item it = menuRows.get(menuIndex).item;
        RectF r = new RectF(menuRow);
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        zone = Z_TOP;
        topIndex = 3 + menuButton;
        retarget(false);
        click.open();
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    private void drawBars(Canvas c, float cx, float cy, int level, float alpha) {
        float bw = 5 * k, gap = 3 * k, total = 4 * bw + 3 * gap, x = cx - total / 2;
        for (int i = 0; i < 4; i++) {
            float h = (7 + 5 * i) * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha * (i < level ? 1f : 0.28f)));
            tmp.set(x + i * (bw + gap), cy + 13 * k - h, x + i * (bw + gap) + bw, cy + 13 * k);
            c.drawRoundRect(tmp, 1.5f * k, 1.5f * k, fill);
        }
    }

    /** Where row i of the open dropdown is (same measures as drawMenu); false while it can't be placed yet. */
    private boolean menuRowRect(int i, RectF out) {
        if (!menuOpen || i < 0 || i >= menuRows.size() || btnLabel == null || menuTag == null) return false;
        float rowH = 62 * k, pad = 14 * k, wantW = 290 * k;
        for (MRow r : menuRows) {
            float need = 64 * k + btnLabel.measureText(r.label) + (r.tag == null ? 0 : 30 * k + menuTag.measureText(r.tag)) + 34 * k;
            wantW = Math.max(wantW, need + 2 * pad);
        }
        float w = Math.min(wantW, 620 * k);
        RectF btn = new RectF(cardMenu ? menuCard : topRects[3 + menuButton]);
        if (cardMenu) btn.offset(0, -menuTy);
        float left = btn.centerX() - w / 2;
        if (left + w > CONTENT_R * k + 24 * k) left = CONTENT_R * k + 24 * k - w;
        if (left < 10 * k) left = 10 * k;
        float top = cardMenu && menuAbove ? btn.top - 30 * k - (menuRows.size() * rowH + 2 * pad) : btn.bottom + 30 * k;
        out.set(left + pad, top + pad + i * rowH, left + w - pad, top + pad + i * rowH + rowH - 8 * k);
        return true;
    }

    private void drawMenu(Canvas c) {
        if (moving) drawMoveArrows(c);
        float m = Ui.clamp(menu.value);
        if (m <= 0.005f || menuRows.isEmpty() || btnLabel == null) return;
        int n = menuRows.size();
        float rowH = 62 * k, pad = 14 * k;
        float wantW = 290 * k;
        for (MRow r : menuRows) {
            float need = 64 * k + btnLabel.measureText(r.label) + (r.tag == null ? 0 : 30 * k + menuTag.measureText(r.tag)) + 34 * k;
            wantW = Math.max(wantW, need + 2 * pad);
        }
        float w = Math.min(wantW, 620 * k), h = n * rowH + 2 * pad;
        RectF btn = new RectF(cardMenu ? menuCard : topRects[3 + menuButton]);
        if (cardMenu) btn.offset(0, -menuTy);
        float left = btn.centerX() - w / 2;
        if (left + w > CONTENT_R * k + 24 * k) left = CONTENT_R * k + 24 * k - w;
        if (left < 10 * k) left = 10 * k;
        float top = cardMenu && menuAbove ? btn.top - 30 * k - h : btn.bottom + 30 * k;
        c.save();
        c.translate(0, (1 - m) * -14 * k);
        RectF box = new RectF(left, top, left + w, top + h);
        tmp.set(box);
        tmp.offset(0, 10 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.35f * m));
        shadows.draw(c, tmp, 30 * k, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.96f * m));
        c.drawRoundRect(box, 30 * k, 30 * k, fill);
        Path arrow = new Path();
        float ax = Math.max(left + 44 * k, Math.min(left + w - 44 * k, btn.centerX()));
        if (cardMenu && menuAbove) {   // hanging above the card: the arrow points down at it
            arrow.moveTo(ax - 13 * k, top + h - 1);
            arrow.lineTo(ax, top + h + 15 * k);
            arrow.lineTo(ax + 13 * k, top + h - 1);
        } else {
            arrow.moveTo(ax - 13 * k, top + 1);
            arrow.lineTo(ax, top - 15 * k);
            arrow.lineTo(ax + 13 * k, top + 1);
        }
        arrow.close();
        c.drawPath(arrow, fill);
        for (int i = 0; i < n; i++) {
            MRow it = menuRows.get(i);
            RectF row = new RectF(left + pad, top + pad + i * rowH, left + w - pad, top + pad + i * rowH + rowH - 8 * k);
            boolean on = i == menuIndex;
            if (on) {
                menuRow.set(row);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.1f * m));
                c.drawRoundRect(row, row.height() / 2, row.height() / 2, fill);
                ring(c, row, row.height() / 2, m, 3.5f * k);
            }
            float a = (on ? 1f : 0.72f) * (it.dim ? 0.6f : 1f) * m;
            if (it.bars >= 0) drawBars(c, row.left + 32 * k, row.centerY(), it.bars, a);
            else Symbols.draw(getContext(), c, it.symbol, row.left + 32 * k, row.centerY(), 26 * k, Ui.alpha(0xFFFFFFFF, a));
            btnLabel.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(it.label, row.left + 64 * k, row.centerY() + Ui.halfCap(btnLabel), btnLabel);
            if (it.tag != null) {
                menuTag.setColor(Ui.alpha(0xFFFFFFFF, a * 0.8f));
                float tw = menuTag.measureText(it.tag), tx = row.right - 24 * k - tw;
                c.drawText(it.tag, tx, row.centerY() + Ui.halfCap(menuTag), menuTag);
                if (it.dot) {
                    float dx = tx - 16 * k, dr = 6 * k;
                    if (it.on) {
                        fill.setColor(Ui.alpha(LILY, m));
                        c.drawCircle(dx, row.centerY(), dr, fill);
                    } else {
                        iconStroke.setColor(Ui.alpha(0xFFFFFFFF, 0.45f * m));
                        c.drawCircle(dx, row.centerY(), dr - 1 * k, iconStroke);
                        iconStroke.setColor(0xFFFFFFFF);
                    }
                }
            }
        }
        btnLabel.setColor(0xFFFFFFFF);
        c.restore();
    }

    /** The play button on the focused card: "Watch now" on the wide one, only the icon on a compact one. */
    private void drawPlay(Canvas c, RectF card, float expanded, float f, float vis, String label) {
        float h = 50 * k, margin = 22 * k;
        boolean wide = expanded > 0.5f;
        float w = wide ? 22 * k + 26 * k + 10 * k + btnLabel.measureText(label) + 24 * k : h;
        ringRect.set(card.right - margin - w, card.bottom - margin - h, card.right - margin, card.bottom - margin);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.9f * vis));
        c.drawRoundRect(ringRect, h / 2, h / 2, fill);
        pillRect.set(ringRect);
        ring(c, pillRect, h / 2, f, 3 * k);
        Symbols.draw(getContext(), c, "play", wide ? pillRect.left + 22 * k + 13 * k : pillRect.centerX(), pillRect.centerY(), 26 * k, Ui.alpha(0xFFFFFFFF, vis));
        if (wide) {
            btnLabel.setAlpha(Math.round(255 * vis));
            c.drawText(label, pillRect.left + 22 * k + 26 * k + 10 * k, pillRect.centerY() + Ui.halfCap(btnLabel), btnLabel);
            btnLabel.setAlpha(255);
        }
    }


    /** Focus ring: a lily → peach → melon gradient stroke inside `r`, with corner radius `radius`. */
    private void ring(Canvas c, RectF r, float radius, float alpha, float width) {
        if (alpha <= 0.01f) return;
        float half = width / 2;
        ringRect.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(width);
        ringPaint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
        ringPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        float rr = Math.max(0, radius - half);
        c.drawRoundRect(ringRect, rr, rr, ringPaint);
        ringPaint.setShader(null);
    }

    /** Date, time and weather in a dark pill, always visible at the top center. */
    private void drawInfoPill(Canvas c) {
        if (infoPaint == null) return;
        String date = Ui.dateShort(System.currentTimeMillis());
        String time = clockText;
        HomeData.Weather wx = data.weather;
        boolean hasWx = !Double.isNaN(wx.temp) && Prefs.weather();
        String temp = hasWx ? HomeData.degrees(wx.temp) : "";
        String wxIcon = hasWx ? Symbols.weather(wx.code, wx.isDay) : null;
        float icon = 26 * k, gapIcon = 10 * k, gapItem = 30 * k, padX = 24 * k, ph = 58 * k;
        float total = icon + gapIcon + infoPaint.measureText(date) + gapItem + icon + gapIcon + infoPaint.measureText(time);
        if (hasWx) total += gapItem + Symbols.width(getContext(), wxIcon, icon) + gapIcon + infoPaint.measureText(temp);
        float cx = w / 2f, top = 30 * k;
        pillRect.set(cx - total / 2 - padX, top, cx + total / 2 + padX, top + ph);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.8f));
        c.drawRoundRect(pillRect, ph / 2, ph / 2, fill);
        float x = cx - total / 2, cy = pillRect.centerY(), base = cy + Ui.halfCap(infoPaint);
        drawCalendarIcon(c, x + icon / 2, cy, icon);
        x += icon + gapIcon;
        c.drawText(date, x, base, infoPaint);
        x += infoPaint.measureText(date) + gapItem;
        drawClockIcon(c, x + icon / 2, cy, icon);
        x += icon + gapIcon;
        c.drawText(time, x, base, infoPaint);
        x += infoPaint.measureText(time) + gapItem;
        if (hasWx) {
            float sw = Symbols.width(getContext(), wxIcon, icon);
            Symbols.draw(getContext(), c, wxIcon, x + sw / 2, cy, icon, 0xFFFFFFFF);
            x += sw + gapIcon;
            c.drawText(temp, x, base, infoPaint);
        }
    }

    private void drawCalendarIcon(Canvas c, float cx, float cy, float s) {
        float half = s / 2;
        iconRect.set(cx - half * 0.85f, cy - half * 0.7f, cx + half * 0.85f, cy + half * 0.9f);
        c.drawRoundRect(iconRect, 3 * k, 3 * k, iconStroke);
        c.drawLine(iconRect.left, cy - half * 0.15f, iconRect.right, cy - half * 0.15f, iconStroke);
        c.drawLine(cx - half * 0.4f, iconRect.top - 3 * k, cx - half * 0.4f, iconRect.top + 3 * k, iconStroke);
        c.drawLine(cx + half * 0.4f, iconRect.top - 3 * k, cx + half * 0.4f, iconRect.top + 3 * k, iconStroke);
    }

    private void drawClockIcon(Canvas c, float cx, float cy, float s) {
        float r = s * 0.45f;
        c.drawCircle(cx, cy, r, iconStroke);
        c.drawLine(cx, cy, cx, cy - r * 0.55f, iconStroke);
        c.drawLine(cx, cy, cx + r * 0.4f, cy + r * 0.25f, iconStroke);
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:   // the favorite apps are the first row: up are the tabs and buttons, down the recommendations, then all the apps
                if (dy == 0 || shelf.isEmpty()) return false;
                if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = topHome();
                } else if (!cards.isEmpty()) {
                    zone = Z_CARDS;
                    cardIndex = Math.min(cardIndex, cards.size() - 1);   // goes back to the card we left
                } else {
                    return false;   // nothing to recommend: Down opens the panel with all the apps (move())
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int count = cards.size();
                    if (count == 0) return true;
                    int next = (cardIndex + dx + count) % count;   // endless, like the shelf: past the last comes the first
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    if (!shelf.isEmpty()) {
                        zone = Z_SHELF;
                        shelfIndex = Math.min(shelfIndex, shelf.size() - 1);   // goes back to the app we left
                    } else {
                        zone = Z_TOP;
                        topIndex = topHome();
                    }
                } else {
                    if (panel.isEmpty()) return true;
                    openSheet();   // below the recommendations: all the other apps
                    click.move();
                    return true;
                }
                break;
            case Z_PLAYER:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, playerIndex + dx));
                    if (next == playerIndex) return true;
                    playerIndex = next;
                } else if (dy < 0) {
                    if (cards.isEmpty()) {
                        zone = Z_TOP;
                        topIndex = topHome();
                    } else {
                        zone = Z_CARDS;
                        cardIndex = Math.min(cardIndex, cards.size() - 1);
                    }
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = Math.min(shelfIndex, shelf.size() - 1);
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(tabsVisible() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;   // focus only moves the ring; OK selects the tab
                } else if (dy > 0) {
                    if (!shelf.isEmpty()) {
                        zone = Z_SHELF;
                        shelfIndex = Math.min(shelfIndex, shelf.size() - 1);
                    } else if (!cards.isEmpty()) {
                        zone = Z_CARDS;
                        cardIndex = Math.min(cardIndex, cards.size() - 1);
                    } else {
                        return true;
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
