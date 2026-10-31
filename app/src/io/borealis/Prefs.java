package io.borealis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Borealis > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "dark");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** "auto" (24 h, or 12 h in English), "24" or "12". */
    static String timeFormat() {
        if (app == null) return "auto";
        String v = sp().getString("timeFormat", "auto");
        return v.equals("12") || v.equals("24") ? v : "auto";
    }

    static void setTimeFormat(String v) { put("timeFormat", v); }

    /** How the user wants to be greeted ("" = no name). */
    static String name() {
        return app == null ? "" : sp().getString("name", "").trim();
    }

    static void setName(String v) { put("name", v == null ? "" : v.trim()); }
    /** The profile avatar (an id from Avatars.IDS); empty until chosen. */
    static String avatar() { return app == null ? "" : sp().getString("avatar", ""); }
    static void setAvatar(String v) { put("avatar", v == null ? "" : v); }

    /** What the three cards of the home screen show: "recent" (continue watching) or "recs" (TMDB recommendations). */
    static String cardsMode() {
        if (app == null) return "recent";
        return sp().getString("cardsMode", "recent").equals("recs") ? "recs" : "recent";
    }

    static void setCardsMode(String v) { put("cardsMode", v); }

    /**
     * Wallpapers and screensavers are the same set, in the same order (16:9, 1920x1080): Latin word + a palette color.
     * The first two screensavers are videos (the wallpaper is a frame); the rest are the wallpaper moving slowly.
     */
    static final String[] WALLPAPER_FILES = {"wave.jpg", "wall2.jpg", "wall3.jpg", "wall4.jpg", "wall5.jpg"};
    static final String[] WALLPAPER_NAMES = {"Aurora Lily", "Lumen Peach", "Velum Night", "Pictura Peach", "Luna Melon"};
    static final String[] SAVER_FILES = {"aurora.mp4", "aurora2.mp4", "wall3.jpg", "wall4.jpg", "wall5.jpg"};
    static final String[] SAVER_POSTERS = WALLPAPER_FILES;
    static final String[] SAVER_NAMES = WALLPAPER_NAMES;

    static int wallpaper() {
        if (app == null) return 0;
        int v = sp().getInt("wallpaper", 0);
        return v >= 0 && v < wallpaperFiles().length ? v : 0;
    }

    // MARK: - The person's own wallpaper (one picture, kept in the app's private folder)

    /** In the list of wallpapers, the name that stands for the person's own picture (it is not an asset). */
    static final String CUSTOM = "custom";
    private static final String CUSTOM_NAME = "custom_wallpaper.jpg";
    private static long customCheckedAt;
    private static boolean customHas;
    private static long customStampValue;

    private static java.io.File customFile() {
        return new java.io.File(app.getFilesDir(), CUSTOM_NAME);
    }

    /** Looks at the file at most once a second (the home screen asks every frame). */
    private static synchronized void checkCustom() {
        if (app == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (customCheckedAt != 0 && now - customCheckedAt < 1000) return;
        customCheckedAt = now;
        java.io.File f = customFile();
        customHas = f.isFile() && f.length() > 0;
        customStampValue = customHas ? f.lastModified() : 0;
    }

    static boolean hasCustom() {
        checkCustom();
        return customHas;
    }

    /** Changes whenever the person's picture is added or replaced (0 if there is none), whether or not it is the one in use. */
    static long customVersion() {
        checkCustom();
        return customHas ? customStampValue : 0;
    }

    /** Changes whenever the person's picture is replaced; 0 while another wallpaper is the one in use. Screens compare it to reload. */
    static long wallpaperStamp() {
        if (app == null || !hasCustom()) return 0;
        return sp().getInt("wallpaper", 0) == WALLPAPER_FILES.length ? customStampValue : 0;
    }

    /** The wallpapers to choose from: the five of the app, plus "My wallpaper" once the person has set one. */
    static String[] wallpaperFiles() {
        return hasCustom() ? concat(WALLPAPER_FILES, new String[] {CUSTOM}) : WALLPAPER_FILES;
    }

    static String[] wallpaperNames() {
        return hasCustom() ? concat(WALLPAPER_NAMES, new String[] {Ui.tr("Meu fundo", "My wallpaper", "Mi fondo")}) : WALLPAPER_NAMES;
    }

    /** Opens a wallpaper by its name in wallpaperFiles(): an asset, or the person's own picture. */
    static java.io.InputStream openWallpaper(Context c, String file) throws java.io.IOException {
        if (CUSTOM.equals(file)) return new java.io.FileInputStream(customFile());
        return c.getAssets().open(file);
    }

    /**
     * Keeps the picture read from `in` as the person's wallpaper: turned upright (camera EXIF), cut to 16:9 from the center
     * and at most 1920x1080, like the wallpapers of the app. Returns false if it is not a picture or is too big.
     */
    static boolean saveCustom(Context c, java.io.InputStream in) {
        if (app == null) return false;
        android.graphics.Bitmap src = null, upright = null, out = null;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > 48L * 1024 * 1024) return false;
                buf.write(chunk, 0, n);
            }
            byte[] data = buf.toByteArray();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1920 * 9 / 16) sample *= 2;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            src = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (src == null) return false;
            int degrees = 0;
            try {
                int orientation = new android.media.ExifInterface(new java.io.ByteArrayInputStream(data))
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            } catch (java.io.IOException | RuntimeException ignored) {
            }
            upright = src;
            if (degrees != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(degrees);
                upright = android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            }
            int sw = upright.getWidth(), sh = upright.getHeight();
            int cw = sw, ch = Math.round(sw * 9f / 16f);
            if (ch > sh) {
                ch = sh;
                cw = Math.round(sh * 16f / 9f);
            }
            int x = (sw - cw) / 2, y = (sh - ch) / 2;
            int ow = Math.min(1920, cw), oh = Math.round(ow * 9f / 16f);
            out = android.graphics.Bitmap.createBitmap(ow, oh, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(out).drawBitmap(upright, new android.graphics.Rect(x, y, x + cw, y + ch),
                    new android.graphics.Rect(0, 0, ow, oh), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            java.io.File dest = customFile(), tmp = new java.io.File(dest.getPath() + ".tmp");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                if (!out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fo)) return false;
                fo.getFD().sync();
            }
            if (!tmp.renameTo(dest)) return false;
            synchronized (Prefs.class) { customCheckedAt = 0; }
            version++;
            return true;
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
            AppLog.w("custom wallpaper: " + e);
            return false;
        } finally {
            if (out != null) out.recycle();
            if (upright != null && upright != src) upright.recycle();
            if (src != null) src.recycle();
        }
    }

    /** Deletes the person's picture; if it was the one in use, the first wallpaper of the app comes back. */
    static void removeCustom() {
        if (app == null) return;
        boolean inUse = sp().getInt("wallpaper", 0) == WALLPAPER_FILES.length;
        if (inUse) sp().edit().putInt("wallpaper", 0).apply();
        customFile().delete();
        synchronized (Prefs.class) { customCheckedAt = 0; }
        version++;
    }

    static void setWallpaper(int v) {
        if (app == null) return;
        sp().edit().putInt("wallpaper", v).apply();
        version++;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Read with MODE_MULTI_PROCESS: the settings screen (process :home) writes it, the screensaver may run in the main process. */
    @SuppressWarnings("deprecation")
    static int screensaver() {
        if (app == null) return 0;
        int v = app.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS).getInt("screensaver", 0);
        return v >= 0 && v < SAVER_FILES.length ? v : 0;
    }

    @SuppressWarnings("deprecation")
    static void setScreensaver(int v) {
        if (app == null) return;
        app.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS).edit().putInt("screensaver", v).apply();
        version++;
    }

    // MARK: - City (from config.properties)

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    /** The Borealis opening when the TV turns on (on by default). */
    static boolean splash() { return sp().getBoolean("splash", true); }
    static void setSplash(boolean on) { put("splash", on); }
    static boolean startupSound() { return sp().getBoolean("startupSound", true); }
    static void setStartupSound(boolean on) { put("startupSound", on); }

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", false); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // Update check (bookkeeping does not bump `version`: it must not redraw or restart the screens).
    static boolean updateAuto() { return sp().getBoolean("updateAuto", true); }
    static void setUpdateAuto(boolean on) { put("updateAuto", on); }
    static String updateFound() { return sp().getString("updateFound", ""); }
    static void setUpdateFound(String v) { sp().edit().putString("updateFound", v == null ? "" : v).apply(); }
    static String updateApkUrl() { return sp().getString("updateApk", ""); }
    static String updateShaUrl() { return sp().getString("updateSha", ""); }
    static void setUpdateApk(String apk, String sha) { sp().edit().putString("updateApk", apk).putString("updateSha", sha).apply(); }
    static long updateAt() { return sp().getLong("updateAt", 0); }
    static void setUpdateAt(long t) { sp().edit().putLong("updateAt", t).apply(); }
    /** "version:day" of the last time the notice was shown on the home screen (once a day per version). */
    static String updateShown() { return sp().getString("updateShown", ""); }
    static void setUpdateShown(String v) { sp().edit().putString("updateShown", v).apply(); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Hidden apps (hidden from the shelf and from All Apps; Settings > Hidden apps brings them back)

    static List<String> hiddenApps() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("hiddenApps", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setHiddenApps(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("hiddenApps", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
