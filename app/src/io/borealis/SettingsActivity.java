package io.borealis;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Borealis's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Borealis / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private android.widget.FrameLayout root;   // holds the view; the screensaver picker adds its live preview on top
    private Dreamer preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        // First time, before Borealis is the TV's home: the app's icon goes straight to the welcome (it turns Borealis on at the end).
        if (OnboardingView.pending(this) && !HomeMode.isFree(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        view = new SettingsView(this);
        root = new android.widget.FrameLayout(this);
        root.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    /** The settings on screen right now (same process as the remote-key service), or null. */
    static volatile SettingsActivity current;

    /** The Home button while the settings are open: the same soft fade as Back, and it always lands on the home screen. */
    void goHomeSoftly() {
        if (isFinishing()) return;
        final android.content.Context app = getApplicationContext();
        finish();   // exactly like Back: the fade-out lands on whatever is underneath (normally the home screen)
        // Opened from inside another app, what is underneath is that app: then Home is started (after the fade).
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (HomeActivity.resumed) return;
            try {
                app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {}
        }, 400);
    }

    // MARK: - Custom wallpaper

    private static final int REQ_WALLPAPER = 4711;

    /** "Custom wallpaper": choose a picture from a file, from the app's own folder, or remove the one set. */
    void pickCustomWallpaper() {
        final List<CharSequence> options = new ArrayList<>();
        options.add(Ui.tr("Escolher um arquivo de imagem", "Choose a picture file", "Elegir un archivo de imagen"));
        options.add(Ui.tr("Usar uma imagem da pasta do app", "Use a picture from the app's folder", "Usar una imagen de la carpeta de la app"));
        final boolean remove = Prefs.hasCustom();
        if (remove) options.add(Ui.tr("Remover meu fundo", "Remove my wallpaper", "Quitar mi fondo"));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Ui.tr("Fundo personalizado", "Custom wallpaper", "Fondo personalizado"))
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) openSystemPicker();
                    else if (which == 1) useWallpaperFolder();
                    else removeWallpaper();
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel", "Cancelar"), null)
                .show();
    }

    private void openSystemPicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(pick, REQ_WALLPAPER);
        } catch (RuntimeException e) {   // this TV has no file chooser: the app's own folder is the way
            useWallpaperFolder();
        }
    }

    /** The newest picture in the app's own folder (Android/data/io.borealis/files/Pictures: adb push can write there, no permission needed). */
    private void useWallpaperFolder() {
        java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        java.io.File best = null;
        if (dir != null) {
            dir.mkdirs();
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    boolean picture = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
                    if (f.isFile() && picture && (best == null || f.lastModified() > best.lastModified())) best = f;
                }
            }
        }
        if (best != null) {
            importWallpaper(null, best);
            return;
        }
        String path = dir == null ? "" : dir.getAbsolutePath();
        new android.app.AlertDialog.Builder(this)
                .setTitle(Ui.tr("Nenhuma imagem na pasta", "No picture in the folder", "No hay ninguna imagen en la carpeta"))
                .setMessage(Ui.tr("Copie uma imagem (JPG, PNG ou WEBP) para esta pasta e escolha esta opção de novo:\n\n",
                        "Copy a picture (JPG, PNG or WEBP) into this folder and choose this option again:\n\n",
                        "Copia una imagen (JPG, PNG o WEBP) a esta carpeta y vuelve a elegir esta opción:\n\n")
                        + path + "\n\nadb push foto.jpg " + path + "/")
                .setPositiveButton("OK", null)
                .show();
    }

    private void removeWallpaper() {
        Prefs.removeCustom();
        HomeData.get(this).settingsChanged();
        if (view != null) view.invalidate();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WALLPAPER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importWallpaper(data.getData(), null);
    }

    /** Reads the picture (from the chooser's address, or from a file) on a background thread, then makes it the wallpaper. */
    private void importWallpaper(final android.net.Uri uri, final java.io.File file) {
        final Context app = getApplicationContext();
        android.widget.Toast.makeText(this, Ui.tr("Carregando a imagem…", "Loading the picture…", "Cargando la imagen…"), android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean saved = false;
            try (java.io.InputStream in = uri != null ? getContentResolver().openInputStream(uri) : new java.io.FileInputStream(file)) {
                saved = in != null && Prefs.saveCustom(app, in);
            } catch (java.io.IOException | RuntimeException e) {
                AppLog.w("custom wallpaper: " + e);
            }
            final boolean ok = saved;
            runOnUiThread(() -> {
                if (ok) {
                    Prefs.setWallpaper(Prefs.WALLPAPER_FILES.length);   // the person's picture comes right after the app's five
                    HomeData.get(app).settingsChanged();
                    if (view != null) view.invalidate();
                }
                android.widget.Toast.makeText(this, ok
                        ? Ui.tr("Fundo atualizado.", "Wallpaper updated.", "Fondo actualizado.")
                        : Ui.tr("Não consegui usar essa imagem.", "I couldn't use that picture.", "No pude usar esa imagen."),
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }, "borealis-wallpaper").start();
    }

    /** Leaving the settings (Back) fades out into the home screen instead of cutting. */
    @Override public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;   // it sent the first run to the welcome
        current = this;
        view.resume();
        // From ADB: am start -n io.borealis/.SettingsActivity --ez screensaver true (straight to the preview).
        if (getIntent().getBooleanExtra("shelf", false)) {   // from the home screen's empty slots
            getIntent().removeExtra("shelf");
            view.showShelfEditor();
        }
        if (getIntent().getBooleanExtra("hidden", false)) {   // from the card "Hidden apps" at the end of All Apps
            getIntent().removeExtra("hidden");
            view.showHiddenApps();
        }
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        if (view == null) { super.onPause(); return; }
        if (current == this) current = null;
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        int code = event.getKeyCode();
                        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && preview instanceof DreamVideo) {
                            ((DreamVideo) preview).openCurrent();   // like the real one: OK opens the app that is playing
                        }
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = DreamVideo.available(this) ? new DreamVideo(this) : new DreamView(this);
        setContentView((View) preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(root);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, WALLPAPERS = 6, GROUP = 7, AVATARS = 8, HIDDEN_APPS = 9;
        private static final float LIST_ROW_H_BASE = 68, LIST_ROW_GAP_BASE = 6;
        /** The long Home Settings list (13 rows with the dollar) gets slimmer rows so it still fits above the bottom edge. */
        private boolean tight() {
            return screen == HOME_SETTINGS && settingLabels().length > 10;
        }
        private float listRowH() { return tight() ? 58 : LIST_ROW_H_BASE; }
        private float listRowGap() { return tight() ? 4 : LIST_ROW_GAP_BASE; }
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = Brand.INK;
        private static final int LILY = Brand.LILY, PEACH = Brand.PEACH, MELON = Brand.MELON;
        private Bitmap wave;          // the design's wave background (assets/wave.jpg), shared look with the home screen
        private boolean waveTried;
        private int waveIdx = -1;
        private final Rect waveDst = new Rect();

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[20];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private int group;   // which of the five groups a GROUP screen shows (1–5)
        private float rowR = -1;   // a fixed corner radius for the next row background (cards); -1 = a pill
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private int a11yKey = -1;   // which screen TalkBack was last told about
        private int introId = -1;   // the item that also says the screen's name, until a key is pressed
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint brand, title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !state.optString("busy").isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                // A switch ended with no ADB on the TV (plan B): open the system's accessibility screen so the person can turn Borealis on.
                if (wasBusy && !nowBusy && state.optBoolean("soft") && !freeActive() && !remoteButtonOn()) {
                    openAccessibilityScreen(getContext());
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Borealis");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (screen == WALLPAPERS) { wpItems(out); return; }
                    for (int i = 1; i <= rowCount(); i++) out.add(i);
                    if (screen == MAIN && hasProfile()) out.add(rowCount() + 1);   // the profile (top right): change the avatar
                    out.add(rowCount() + 2);   // the Back button
                }
                @Override public String text(int id) {
                    if (screen != WALLPAPERS && id == rowCount() + 2) return Ui.tr("Voltar", "Back", "Volver");
                    if (screen == MAIN && id == rowCount() + 1) {
                        return Ui.tr("Perfil: ", "Profile: ", "Perfil: ") + Prefs.name() + ". " + Ui.tr("Aperte OK para mudar o avatar.", "Press OK to change the avatar.", "Presiona OK para cambiar el avatar.");
                    }
                    String t = screen == WALLPAPERS && id > rowCount() ? wpExtraText(id - rowCount())
                            : id >= 1 && id <= rowCount() ? rowText(id - 1) : "";
                    // The first item after a screen opens also says where we are ("Appearance. Wallpaper, …").
                    if (id == introId && !t.isEmpty()) t = a11yTitle() + ". " + t;
                    return t;
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (screen != WALLPAPERS && id == rowCount() + 2) {
                        out.set(Math.round(BACK_X * k), Math.round(BACK_Y * k), Math.round((BACK_X + BACK_W) * k), Math.round((BACK_Y + BACK_H) * k));
                        return true;
                    }
                    if (screen == MAIN && id == rowCount() + 1) {
                        if (profileRect.isEmpty()) return false;
                        profileRect.round(out);
                        return true;
                    }
                    if (screen == WALLPAPERS && id > rowCount()) return wpExtraBounds(id - rowCount(), out);
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() {
                    if (focus == -2 && screen != WALLPAPERS) return rowCount() + 2;
                    if (screen == MAIN && focus == -1) return rowCount() + 1;
                    if (screen != WALLPAPERS) return focus + 1;
                    int n = rowCount();
                    if (wpOnBack) return n + 6;
                    if (wpRow == 1) return wpSaver ? n + 1 + Math.min(4, wpPill) : n + 5;
                    return focus + 1;
                }
                @Override public void focus(int id) {
                    if (screen == WALLPAPERS) {
                        int e = id - rowCount();
                        if (e <= 0) { wpSetRow(0); wpGo(id - 1); }
                        else if (e == 6) wpSetRow(-1);
                        else { wpSetRow(1); wpPill = wpSaver ? Math.min(4, e - 1) : 4; }
                    } else if (id == rowCount() + 2) {
                        focus = -2;
                    } else if (screen == MAIN && id == rowCount() + 1) {
                        focus = -1;
                    } else {
                        focus = id - 1;
                    }
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            spoken.setTitle(this::a11yScreen);
            return spoken;
        }

        /** The name of the screen the person is in (the card's name inside a card; never just "Borealis"). */
        private String a11yTitle() {
            switch (screen) {
                case MAIN: return Ui.tr("Borealis, configurações", "Borealis settings", "Borealis, configuración");
                case GROUP: return group >= 1 ? groupInfo()[group - 1][0] : "Borealis";
                case HOME_CHOICE: return "Launcher";
                case HOME_SETTINGS: return Ui.tr("Configurações", "Settings", "Configuración");
                case REMOTE_KEYS: return groupInfo()[3][0];
                case PICKER: return keyRawLabel(Math.max(0, Math.min(Prefs.KEYS.length - 1, pickFor - 1)));
                case SHELF_EDIT: return groupLabel(ID_SHELF);
                case AVATARS: return Ui.tr("Escolha seu avatar", "Choose your avatar", "Elige tu avatar");
                case HIDDEN_APPS: return Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas");
                case WALLPAPERS: return wpSaver ? groupLabel(ID_SAVER) : settingLabels()[WALLPAPER_ROW];
                default: return "Borealis";
            }
        }

        /** Said when TalkBack rests on the whole screen instead of an item. */
        private String a11yScreen() {
            return a11yTitle() + ". " + (screen == WALLPAPERS
                    ? Ui.tr("Use as setas para os lados para trocar, e para baixo para os botões.",
                            "Use the left and right arrows to change, and down for the buttons.",
                            "Usa las flechas a los lados para cambiar y abajo para los botones.")
                    : Ui.tr("Use as setas para ver as opções.", "Use the arrows to see the options.", "Usa las flechas para ver las opciones."));
        }

        // The picker's own buttons for TalkBack: ids after the pictures. n+1..n+4 the times, n+5 "Preview" / "Hide elements", n+6 "Back".
        private void wpItems(List<Integer> out) {
            int n = rowCount();
            out.add(n + 6);
            for (int i = 1; i <= n; i++) out.add(i);
            if (wpSaver) for (int p = 1; p <= 4; p++) out.add(n + p);
            out.add(n + 5);
        }

        private String wpExtraText(int e) {
            switch (e) {
                case 6: return Ui.tr("Voltar", "Back", "Volver");
                case 5: return wpSaver ? Ui.tr("Ver agora", "Preview", "Ver ahora")
                        : wpClean.target > 0.5f ? Ui.tr("Mostrar elementos", "Show elements", "Mostrar elementos")
                        : Ui.tr("Ocultar elementos", "Hide elements", "Ocultar elementos");
                default: {
                    if (e < 1 || e > 4 || !wpSaver) return "";
                    boolean chosen = SAVER_MINUTES[e - 1] * 60_000L == saverTimeMs();
                    return Ui.tr("Ativar o protetor após ", "Start the screensaver after ", "Activar protector tras ") + SAVER_MINUTES[e - 1] + " min"
                            + (chosen ? Ui.tr(", escolhido", ", selected", ", seleccionado") : "") + ", " + e + Ui.tr(" de ", " of ") + 4;
                }
            }
        }

        private boolean wpExtraBounds(int e, Rect out) {
            if (w == 0 || e < 1 || e > 6) return false;
            float left = w / 2f - SMP_W * k / 2f, right = left + SMP_W * k, rowTop = ROW_TOP * k, bh = BTN_H * k;
            RectF r = focusBox;
            if (e == 6) {
                r.set(BACK_X * k, BACK_Y * k, (BACK_X + BACK_W) * k, (BACK_Y + BACK_H) * k);
            } else if (e == 5) {
                float bw = (wpSaver ? 200 : 290) * k, cx = wpSaver ? right - bw / 2 : w / 2f;
                r.set(cx - bw / 2, rowTop, cx + bw / 2, rowTop + bh);
            } else {
                if (!wpSaver) return false;
                wpPaints();
                String text = Ui.tr("Ativar o protetor após", "Start the screensaver after", "Activar protector tras");
                float x = left + wpHintStrong().measureText(text) + 30 * k + (e - 1) * (PILL_W + PILL_GAP) * k;
                r.set(x, rowTop, x + PILL_W * k, rowTop + PILL_H * k);
            }
            r.round(out);
            return out.width() > 0 && out.height() > 0;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case AVATARS:
                    return row < 0 || row >= Avatars.IDS.length ? "" : Avatars.name(row)
                            + (Avatars.IDS[row].equals(Avatars.current()) ? ", " + Ui.tr("selecionado", "selected", "seleccionado") : "");
                case MAIN: {
                    String[][] info = groupInfo();
                    int[] order = groupOrder();
                    return row < order.length ? info[order[row] - 1][0] + ", " + info[order[row] - 1][1] : "";
                }
                case GROUP: {
                    int[] ids = groupIds();
                    if (row >= ids.length) return "";
                    if (ids[row] == ID_LAUNCHER) {
                        String busyText = state.optString("busy");
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText)
                                : homeName();
                        return groupLabel(ids[row]) + ", " + value;
                    }
                    return groupLabel(ids[row]) + (groupValue(ids[row]).isEmpty() ? "" : ", " + groupValue(ids[row]));
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Borealis, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == homeIsBorealis();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case WALLPAPERS: {
                    if (row >= wpNames().length) return "";
                    return wpNames()[row] + (row == wpCurrent() ? Ui.tr(", em uso", ", in use", ", en uso") : "")
                            + ", " + (row + 1) + Ui.tr(" de ", " of ") + wpNames().length;
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                case HIDDEN_APPS:
                    return row >= 0 && row < hidItems.size() ? hidItems.get(row).label + ". "
                            + Ui.tr("Aperte OK para mostrar de novo.", "Press OK to show it again.", "Presiona OK para mostrarla de nuevo.") : "";
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == HIDDEN_APPS && !hidItems.isEmpty()) {
                hidCell(Math.max(0, Math.min(row, hidItems.size() - 1)), focusBox);
                return focusBox;
            }
            if (screen == AVATARS) {
                avatarCell(Math.max(0, Math.min(row, Avatars.IDS.length - 1)), focusBox);
                return focusBox;
            }
            if (screen == WALLPAPERS) {
                wpItemRect(Math.max(0, Math.min(row, wpFiles().length - 1)), focusBox);
                return focusBox;
            }
            if (screen == MAIN) {
                top = LIST_TOP + 20 + row * (CARD_H + CARD_GAP);
                height = CARD_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (listRowH() + listRowGap());
                height = listRowH();
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void showShelfEditor() {
            openShelfEditor();
            invalidate();
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            if (screen == WALLPAPERS && wpSaver) scheduleLive();
        }

        void pause() {
            running = false;
            if (click != null) {
                click.release();
                click = null;
            }
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            handler.removeCallbacks(liveStart);
            stopLive();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !blinkSpring.settled();
            for (Spring s : pop) moving |= !s.settled();
            moving |= !wpBackPop.settled() || !wpIn.settled() || !profilePop.settled();
            if (screen == WALLPAPERS) moving |= !wpClean.settled() || !wpScroll.settled() || !wpBackPop.settled() || !wpCarPop.settled()
                    || !wpPillPop[0].settled() || !wpPillPop[1].settled() || !wpPillPop[2].settled() || !wpPillPop[3].settled() || !wpPillPop[4].settled() || wpFading() || (live != null && live.getAlpha() < 1f)
                    || (!wpSaver && SystemClock.uptimeMillis() - wpZoomAt >= 40);
            if (moving || (wave == null && SystemClock.uptimeMillis() - lastSky >= 100)) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            brand = Ui.text(Logo.wordmark(getContext()), 76 * k, 0xFFFFFFFF);
            brand.setTextAlign(Paint.Align.CENTER);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            veilPaint.setColor(Brand.ink(0x66));
            pillPaint.setColor(0x1AFFFFFF);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        /** Borealis is the home screen: through ADB (Borealis mode) or, with no ADB, through the accessibility service (soft Home). */
        private boolean homeIsBorealis() {
            return freeActive() || (state.optBoolean("soft") && remoteButtonOn());
        }

        private String homeName() {
            if (freeActive()) return "Borealis";
            return homeIsBorealis() ? "Borealis (" + Ui.tr("Início suave", "soft Home", "Inicio suave") + ")" : Ui.tr("Original", "Original");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 5;
                case GROUP: return groupIds().length;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case AVATARS: return Avatars.IDS.length;
                case HIDDEN_APPS: return hidItems.size();
                case WALLPAPERS: return wpFiles().length;
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            int key = screen * 1000 + Math.max(0, group) * 100 + (wpSaver ? 10 : 0) + (screen == PICKER ? pickFor : 0);
            if (key != a11yKey) {   // a new screen: TalkBack goes to its first item and says where it is, instead of "Borealis"
                boolean first = a11yKey < 0;
                a11yKey = key;
                introId = focus + 1;
                if (!first) postDelayed(spoken::refocus, 300);
            }
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() && !(screen == WALLPAPERS && wpOnBack) ? 1 : 0;
                pop[i].step(dt);
            }
            profilePop.target = screen == MAIN && focus == -1 ? 1 : 0;
            wpBackPop.target = focus == -2 && screen != WALLPAPERS ? 1 : 0;
            if (screen != WALLPAPERS) wpBackPop.step(dt);
            wpIn.step(dt);
            profilePop.step(dt);
            blinkSpring.step(dt);
            if (screen == HIDDEN_APPS) {
                for (int i = 0; i < hidPop.length; i++) {
                    hidPop[i].target = focus == i ? 1 : 0;
                    hidPop[i].step(dt);
                }
            }
            if (screen == WALLPAPERS) {
                if (wpHold) {
                    if (wpSnap != null && !wpReady() && SystemClock.uptimeMillis() - wpHoldAt < 500) {
                        c.drawBitmap(wpSnap, 0, 0, null);   // the old screen, still, while the new one loads
                        invalidate();
                        return;
                    }
                    wpRelease();
                    wpIn.step(dt);
                }
                float in = Ui.clamp(wpIn.value);
                if (in >= 0.995f) {
                    dropSnapshot();
                    drawWallpapers(c, dt);
                } else {   // coming in: the old screen dissolves into the new one, once, all together
                    if (wpSnap != null) c.drawBitmap(wpSnap, 0, 0, null); else drawBackground(c);
                    int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * in));
                    drawWallpapers(c, dt);
                    c.restoreToCount(layer);
                }
                return;
            }
            if (wpSnap != null) dropSnapshot();
            if (screen == HIDDEN_APPS) {   // like the wallpaper picker: its blurred backdrop, a centered title, the Back button
                drawBackground(c);
                drawHiddenApps(c);
                drawBackButton(c);
                return;
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawBackground(c);
            drawProfile(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            float lu = 0.82f * k;   // one unit of the logo's 1000-unit board; the name sits where the old title was
            Logo.drawLockup(c, getContext(), cx - 500 * lu, 612 * k - 760 * lu, lu);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);
            c.drawText(Ui.tr("Desenhado e desenvolvido por Alogus Tov", "Designed and developed by Alogus Tov", "Diseñado y desarrollado por Alogus Tov"), cx, 742 * k, version);
            c.drawText("Powered by Free My TV", cx, 774 * k, version);
            c.drawText(Ui.tr("Usa a API do TMDB, sem apoio nem certificação do TMDB", "Uses the TMDB API; not endorsed or certified by TMDB", "Usa la API de TMDB; sin respaldo ni certificación de TMDB"), cx, 806 * k, version);

            if (screen == MAIN) drawGroups(c); else if (screen == GROUP && group == 5) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else if (screen == AVATARS) drawAvatarGrid(c); else drawList(c);
            drawBackButton(c);
        }

        /**
         * The chosen wallpaper, tiny and blurred, at 80% over the night black: the same backdrop as the wallpaper picker.
         * A new wallpaper cross-fades in over the one before it.
         */
        private void drawBackground(Canvas c) {
            c.drawColor(Brand.INK);
            final int wallpaper = Prefs.wallpaper();
            final long stamp = Prefs.wallpaperStamp();
            if ((wallpaper != bgIdx || stamp != bgStamp) && wallpaper != bgLoading) {
                bgLoading = wallpaper;
                new Thread(() -> {
                    Bitmap pic = decodeWallpaper(Prefs.wallpaperFiles()[wallpaper], 4, 0);
                    final Bitmap soft = pic == null ? null : softBackdrop(pic);
                    if (pic != null) pic.recycle();
                    handler.post(() -> {
                        bgPrev = bgSoft;
                        bgSoft = soft;
                        bgIdx = wallpaper;
                        bgStamp = stamp;
                        bgLoading = -1;
                        bgAt = SystemClock.uptimeMillis();
                        invalidate();
                    });
                }).start();
            }
            float e = Ui.clamp((SystemClock.uptimeMillis() - bgAt) / 300f);
            waveDst.set(0, 0, w, h);
            if (bgPrev != null && e < 1f) {
                bgPaint.setAlpha(Math.round(255 * BACKDROP_ALPHA));
                c.drawBitmap(bgPrev, null, waveDst, bgPaint);
            }
            if (bgSoft != null) {
                bgPaint.setAlpha(Math.round(255 * BACKDROP_ALPHA * (bgPrev != null ? e : 1f)));
                c.drawBitmap(bgSoft, null, waveDst, bgPaint);
            }
            if (e < 1f) invalidate();
        }

        private Bitmap bgSoft, bgPrev;
        private int bgIdx = -1, bgLoading = -1;
        private long bgStamp;
        private long bgAt;
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private final Paint wavePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint veilPaint = new Paint();
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp2 = new RectF();

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = homeName();
            final int[] ids = groupIds();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Sistema", "System", "Sistema"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < ids.length; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, 0xFFFFFFFF, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(groupLabel(ids[i]), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (ids[i] != ID_LAUNCHER) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0xCCFFFFFF, f));
                } else if (working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, 0xFFFFFFFF, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                    c.drawText(mode, right, base, rowValue);
                }
            }

            float y = (LIST_TOP + 20 + ids.length * (ROW_H + ROW_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty() && !(state.optBoolean("soft") && remoteButtonOn())) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** List row: a soft translucent pill; focused, night-black with the lily → peach → melon ring (as the home's pills). Returns how focused it is. */
        /** The blink when something is clicked: the focused row or button fills with the gradient for a moment. */
        private final Spring blinkSpring = new Spring(0.4f, 1f, 0);

        private void blink() {
            blinkSpring.snap(1);
            blinkSpring.target = 0;
            invalidate();
        }

        private void blinkFill(Canvas c, RectF box, float radius, float focus) {
            float a = Ui.clamp(blinkSpring.value) * Ui.clamp((focus - 0.5f) * 2);
            if (a <= 0.01f) return;
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(0xFFFFFFFF);
            wpFill.setShader(new LinearGradient(box.left, 0, box.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            wpFill.setAlpha(Math.round(255 * a));
            c.drawRoundRect(box, radius, radius, wpFill);
            wpFill.setShader(null);
            wpFill.setAlpha(255);
        }

        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            float r0 = rowR >= 0 ? rowR : rowRect.height() / 2;
            if (f <= 0.01f) {
                c.drawRoundRect(rowRect, r0, r0, pillPaint);
                return f;
            }
            float s = 1 + 0.02f * f;
            float cx = rowRect.centerX(), cy = rowRect.centerY();
            tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
            float r = rowR >= 0 ? rowR * s : tmp.height() / 2;
            tmp.offset(0, 8 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            c.drawRoundRect(tmp, r, r, shadow);
            tmp.offset(0, -8 * k * f);
            c.drawRoundRect(tmp, r, r, pillPaint);   // the light pill stays underneath while the dark one comes in: no gap, no blink
            white.setColor(INK);
            white.setAlpha(Math.round(255 * f));
            c.drawRoundRect(tmp, r, r, white);
            blinkFill(c, tmp, r, f);
            float ring = 3.5f * k, half = ring / 2;
            tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(ring);
            ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
            c.drawRoundRect(tmp2, r - half, r - half, ringPaint);
            ringPaint.setShader(null);
            rowRect.set(tmp);
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Borealis", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Borealis or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Borealis", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = homeIsBorealis() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                rowSub.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, 0xFFFFFFFF, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Borealis Beta " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Borealis";
            }
        }

        private Click click;

        private Click click() {
            if (click == null) click = new Click(getContext());
            return click;
        }

        private long moveSig() {
            return screen * 1_000_003L + focus * 131L + wpRow * 17L + wpPill * 7L + Math.round(wpScroll.target) * 1009L + (wpOnBack ? 1 : 0) + pickScroll * 53L + group * 3L;
        }

        /** The keys, with the keyboard-click sounds: a light one whenever something moves or changes, a firmer one on OK. */
        boolean onKey(KeyEvent e) {
            long before = moveSig();
            boolean handled = onKey0(e);
            if (e.getAction() == KeyEvent.ACTION_DOWN && handled) {
                int code = e.getKeyCode();
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
                boolean side = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (!ok && (before != moveSig() || (side && (screen == GROUP || screen == HOME_SETTINGS || screen == SHELF_EDIT)))) click().move();
            }
            return handled;
        }

        private boolean onKey0(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (down) introId = -1;
            if (screen == WALLPAPERS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return wallpaperKey(e, down);
            if (screen == AVATARS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return avatarKey(e, down);
            if (screen == HIDDEN_APPS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return hiddenKey(e, down);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus == -1) focus = rowCount() - 1;   // the profile: up goes round to the last card
                    else if (down && focus == -2) { /* the Back button is the top */ }
                    else if (down && screen == MAIN && focus == 0 && hasProfile()) focus = -1;   // the first card: up to the profile
                    else if (down && screen == MAIN && focus == 0) focus = rowCount() - 1;   // no profile: round to the last
                    else if (down && focus == 0 && (!scrolling() || pickScroll == 0)) focus = -2;   // the first row: up to the Back button
                    else if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = 0;   // from the profile or the Back button: into the list
                    else if (down && screen == MAIN && focus == rowCount() - 1) focus = 0;   // the last card: round to the first
                    else if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == MAIN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && focus >= -1) {
                        focus = -2;   // left: the Back button
                    } else if (down && screen == MAIN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && focus == -2) {
                        if (hasProfile()) focus = -1;
                    } else if (down && screen == MAIN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && focus >= 0 && hasProfile()) {
                        focus = -1;   // right: the profile
                    } else if (down && focus < 0) {
                        // chrome: nothing to adjust
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == GROUP) {
                        int id = groupIds()[focus];
                        if ((id < 100 && id != WALLPAPER_ROW) || id == ID_SPLASH || id == ID_SOUND || id == ID_UPDATE_AUTO) {
                            adjustSetting(id, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                            invalidate();
                        }
                    } else if (down && screen == HOME_SETTINGS && focus != WALLPAPER_ROW) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) goBack();
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }


        /** One step back (the BACK key and the "Back" button): up to the first screen, and from there out to the home. */
        private void goBack() {
            if (screen == MAIN) {
                ((android.app.Activity) getContext()).finish();
                return;
            }
            if (screen == PICKER) {
                focus = keyPos(pickFor - 1);
                screen = REMOTE_KEYS;
            } else if (screen == AVATARS) {
                screen = MAIN;
                focus = -1;   // back on the profile
            } else if (screen == WALLPAPERS) {
                closeWallpapers();
            } else if (screen == HIDDEN_APPS) {
                if (hidFromHome) {   // opened from the card in All Apps: back out to the home screen
                    ((android.app.Activity) getContext()).finish();
                    return;
                }
                group = 2;
                screen = GROUP;
                focus = groupIndexOf(2, ID_HIDDEN);
            } else if (screen == GROUP) {
                focus = groupPos(group);
                screen = MAIN;
            } else if (screen == SHELF_EDIT) {
                group = 2;
                screen = GROUP;
                focus = groupIndexOf(2, ID_SHELF);
            } else if (screen == HOME_CHOICE) {
                group = 5;
                screen = GROUP;
                focus = 0;
            } else {
                focus = screen == REMOTE_KEYS ? groupPos(4) : 0;
                screen = MAIN;
            }
            invalidate();
            spoken.focusMoved();
        }

        // MARK: - The five groups

        /** What each group is called and says about itself (the cards of the first screen). */
        private String[][] groupInfo() {
            return new String[][] {
                {Ui.tr("Aparência", "Appearance", "Apariencia"),
                        Ui.tr("Papel de parede e protetor de tela", "Wallpaper and screensaver", "Fondo de pantalla y protector")},
                {Ui.tr("Tela principal", "Home screen", "Pantalla principal"),
                        Ui.tr("Cartões, prateleira e widgets", "Cards, shelf and widgets", "Tarjetas, estante y widgets")},
                {Ui.tr("Preferências", "Preferences", "Preferencias"),
                        Ui.tr("Idioma, temperatura, hora, seu nome e boas-vindas", "Language, temperature, time, your name and welcome", "Idioma, temperatura, hora, tu nombre y bienvenida")},
                {Ui.tr("Controle remoto", "Remote control", "Control remoto"),
                        Ui.tr("O que abre cada botão", "What each button opens", "Qué abre cada botón")},
                {Ui.tr("Sistema", "System", "Sistema"),
                        Ui.tr("Launcher, ajustes da TV e estado", "Launcher, TV settings and status", "Launcher, ajustes de la TV y estado")}};
        }

        /** Alphabetical order in the language in use. */
        private java.text.Collator collator() {
            return java.text.Collator.getInstance(Ui.locale());
        }

        /** The groups (1–5) in alphabetical order of their names: the order of the cards. */
        private int[] groupOrder() {
            final String[][] info = groupInfo();
            final java.text.Collator col = collator();
            Integer[] g = {1, 2, 3, 4, 5};
            java.util.Arrays.sort(g, (a, b) -> col.compare(info[a - 1][0], info[b - 1][0]));
            int[] out = new int[5];
            for (int i = 0; i < 5; i++) out[i] = g[i];
            return out;
        }

        private int groupPos(int g) {
            int[] o = groupOrder();
            for (int i = 0; i < o.length; i++) if (o[i] == g) return i;
            return 0;
        }

        private int[] sortedByName(int... ids) {
            final java.text.Collator col = collator();
            Integer[] a = new Integer[ids.length];
            for (int i = 0; i < ids.length; i++) a[i] = ids[i];
            java.util.Arrays.sort(a, (x, y) -> col.compare(groupLabel(x), groupLabel(y)));
            int[] out = new int[ids.length];
            for (int i = 0; i < ids.length; i++) out[i] = a[i];
            return out;
        }

        /** The rows of the open group: ids of the old Home Settings rows, plus the ID_* ones. */
        private int[] groupIds() {
            switch (group) {
                case 1: return sortedByName(WALLPAPER_ROW, ID_CUSTOM_WP, ID_SAVER, ID_SPLASH, ID_SOUND);
                case 2: {
                    int[] top = sortedByName(5, 6, ID_SHELF, ID_HIDDEN, 7, 8);
                    int coins = Math.max(0, settingLabels().length - COIN_ROWS);
                    int[] coinIds = new int[coins];
                    for (int i = 0; i < coins; i++) coinIds[i] = COIN_ROWS + i;
                    coinIds = sortedByName(coinIds);
                    int[] ids = new int[top.length + coins];
                    int n = 0;
                    for (int id : top) {
                        ids[n++] = id;
                        if (id == 8) for (int cid : coinIds) ids[n++] = cid;   // the coins stay under the quotes widget
                    }
                    return ids;
                }
                case 3: return sortedByName(0, 1, 3, 4, ID_REMOTE, ID_WELCOME);
                default: return sortedByName(ID_LAUNCHER, ID_TV, ID_REBOOT, ID_UPDATE, ID_UPDATE_AUTO);
            }
        }

        private int groupIndexOf(int g, int id) {
            int was = group;
            group = g;
            int[] ids = groupIds();
            group = was;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
            return 0;
        }

        private String groupLabel(int id) {
            switch (id) {
                case ID_SAVER: return Ui.tr("Protetor de tela", "Screensaver", "Protector de pantalla");
                case ID_SHELF: return Ui.tr("Prateleira", "Shelf", "Estante de apps");
                case ID_HIDDEN: return Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas");
                case ID_LAUNCHER: return "Launcher";
                case ID_SPLASH: return Ui.tr("Logo do launcher ao ligar a TV", "Launcher logo when the TV turns on", "Logo del launcher al encender la TV");
                case ID_SOUND: return Ui.tr("Som de abertura", "Startup sound", "Sonido de inicio");
                case ID_TV: return Ui.tr("Ajustes da TV", "TV Settings", "Ajustes de la TV");
                case ID_REBOOT: return Ui.tr("Reiniciar a TV", "Restart the TV", "Reiniciar TV");
                case ID_CUSTOM_WP: return Ui.tr("Fundo personalizado", "Custom wallpaper", "Fondo personalizado");
                case ID_REMOTE: return Ui.tr("Botão de ajustes do controle", "Remote's settings button", "Botón de ajustes del control");
                case ID_WELCOME: return Ui.tr("Repetir as boas-vindas", "Replay the welcome", "Repetir bienvenida");
                case ID_UPDATE: return Ui.tr("Atualizações", "Updates", "Actualizaciones");
                case ID_UPDATE_AUTO: return Ui.tr("Buscar atualizações sozinho", "Check for updates by itself", "Buscar actualizaciones solo");
                default: return settingLabels()[id];
            }
        }

        private String groupValue(int id) {
            switch (id) {
                case ID_SAVER: {
                    int at = Prefs.screensaver();
                    return at >= 0 && at < Prefs.SAVER_NAMES.length ? Prefs.SAVER_NAMES[at] : "";
                }
                case ID_SPLASH: return onOff(Prefs.splash());
                case ID_SOUND: return onOff(Prefs.startupSound());
                case ID_REMOTE: return onOff(remoteButtonOn());
                case ID_SHELF: case ID_TV: case ID_WELCOME: case ID_REBOOT: return "";
                case ID_CUSTOM_WP: return Prefs.hasCustom() ? Ui.tr("Definido", "Set", "Cargado") : "";
                case ID_HIDDEN: { int n = Prefs.hiddenApps().size(); return n == 0 ? "" : String.valueOf(n); }
                case ID_UPDATE: {
                    String newer = Update.newer(getContext());
                    return newer.isEmpty() ? Ui.tr("Em dia · ", "Up to date · ", "Al día · ") + Update.installed(getContext())
                            : Ui.tr("Nova versão ", "New version ", "Nueva versión ") + newer;
                }
                case ID_UPDATE_AUTO: return onOff(Prefs.updateAuto());
                case ID_LAUNCHER: return homeName();
                default: return settingValues()[id];
            }
        }

        /** True when the accessibility service that sees the remote's buttons (the settings button, the soft transitions) is on. */
        private boolean remoteButtonOn() {
            try {
                String flat = Settings.Secure.getString(getContext().getContentResolver(), "enabled_accessibility_services");
                return flat != null && flat.contains(getContext().getPackageName() + "/");
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Off: first through the TV's own adb (no computer needed); if it is still off after 9 s, the system's accessibility
         * screen opens. On: that screen opens, to change it. It is never turned on without the person asking for it here.
         */
        private void askRemoteButton() {
            final Context ctx = getContext();
            if (remoteButtonOn()) {
                openAccessibilityScreen(ctx);
                return;
            }
            HomeMode.wantRemoteService(ctx);
            postDelayed(() -> {
                if (!remoteButtonOn() && isAttachedToWindow()) openAccessibilityScreen(ctx);
                invalidate();
            }, 9000);
        }

        private static void openAccessibilityScreen(Context ctx) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }

        /** Rows that open another screen (a chevron at the right) instead of changing a value in place. */
        private static boolean opensScreen(int id) {
            return id == WALLPAPER_ROW || id == ID_SAVER || id == ID_SHELF || id == ID_HIDDEN || id == ID_LAUNCHER || id == ID_TV || id == ID_WELCOME;
        }

        private Paint profileText;

        private final Spring profilePop = new Spring(0.28f, 0.85f, 0);
        private final RectF profileRect = new RectF();
        private final android.graphics.Path pencilPath = new android.graphics.Path();

        private boolean hasProfile() {
            return !Avatars.current().isEmpty() || !Prefs.name().isEmpty();
        }

        /** Top left: the profile's avatar and name (when there are any). On the first screen it can be reached (up from the first card) and its pencil opens the avatars. */
        private void drawProfile(Canvas c) {
            String avatar = Avatars.current(), name = Prefs.name();
            if (avatar.isEmpty() && name.isEmpty()) {
                profileRect.setEmpty();
                return;
            }
            if (profileText == null) {
                profileText = Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF);
            }
            float r = 34 * k, cy = (LIST_TOP - 24 - 12) * k;   // the same level as the screen's title
            float textW = name.isEmpty() ? 0 : profileText.measureText(name);
            float inner = (avatar.isEmpty() ? 0 : 2 * r + (name.isEmpty() ? 0 : 20 * k)) + textW;
            float x = LIST_R * k - 26 * k - inner;   // right-aligned with the cards, above them
            float cx = x + r;
            float textX = avatar.isEmpty() ? x : x + 2 * r + 20 * k;
            float endX = textX + textW;
            profileRect.set(x - 16 * k, cy - r - 12 * k, endX + 26 * k, cy + r + 12 * k);
            boolean editable = screen == MAIN && !avatar.isEmpty();
            float f = editable ? Ui.clamp(profilePop.value) : 0;
            if (f > 0.01f) {   // the focus: a dark pill with the gradient ring
                float rr = profileRect.height() / 2;
                white.setColor(INK);
                white.setAlpha(Math.round(220 * f));
                c.drawRoundRect(profileRect, rr, rr, white);
                float ring = 3.5f * k, half = ring / 2;
                tmp2.set(profileRect.left + half, profileRect.top + half, profileRect.right - half, profileRect.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(profileRect.left, 0, profileRect.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp2, rr - half, rr - half, ringPaint);
                ringPaint.setShader(null);
                ringPaint.setColor(0xFFFFFFFF);
            }
            if (!avatar.isEmpty()) Avatars.draw(c, getContext(), avatar, cx, cy, r, 1f);
            if (!name.isEmpty()) c.drawText(name, textX, cy + Ui.halfCap(profileText), profileText);
            if (editable) drawPencilBadge(c, cx + r * 0.72f, cy + r * 0.72f, 15 * k);
        }

        /** The edit mark: a gradient circle with a dark outline and a pencil (the same as the welcome's avatar). */
        private void drawPencilBadge(Canvas c, float bx, float by, float br) {
            tmp.set(bx - br, by - br, bx + br, by + br);
            white.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            white.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, white);
            white.setShader(null);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * k);
            ringPaint.setColor(INK);
            c.drawCircle(bx, by, br, ringPaint);
            float s = br * 1.15f;
            pencilPath.rewind();
            pencilPath.moveTo(-0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, 0.14f * s);
            pencilPath.lineTo(0f, 0.44f * s);
            pencilPath.lineTo(-0.13f * s, 0.14f * s);
            pencilPath.close();
            c.save();
            c.translate(bx, by);
            c.rotate(45);
            white.setColor(INK);
            c.drawPath(pencilPath, white);
            c.restore();
            ringPaint.setColor(0xFFFFFFFF);
            white.setColor(0xFFFFFFFF);
        }

        // MARK: - Avatars (opened from the profile)

        private static final int AV_COLS = 5;

        private void openAvatars() {
            screen = AVATARS;
            focus = Math.max(0, java.util.Arrays.asList(Avatars.IDS).indexOf(Avatars.current()));
            Avatars.preload(getContext());
            invalidate();
            spoken.focusMoved();
        }

        private void avatarCell(int i, RectF out) {
            float d = 132, gap = 22, x = LIST_X + 24 + (i % AV_COLS) * (d + gap), y = LIST_TOP + 40 + (i / AV_COLS) * (d + gap);
            out.set(x * k, y * k, (x + d) * k, (y + d) * k);
        }

        private boolean avatarKey(KeyEvent e, boolean down) {
            int n = Avatars.IDS.length;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && focus >= 0 && focus % AV_COLS > 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && focus >= 0 && focus % AV_COLS < AV_COLS - 1 && focus + 1 < n) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus >= AV_COLS) focus -= AV_COLS;
                    else if (down && focus >= 0) focus = -2;   // the first row: up to the Back button
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = 0;
                    else if (down) {
                        if (focus + AV_COLS < n) focus += AV_COLS;
                        else if (focus / AV_COLS < (n - 1) / AV_COLS) focus = n - 1;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        private void avatarRing(Canvas c, float cx, float cy, float radius, float width, float alpha) {
            float half = width / 2;
            tmp2.set(cx - radius + half, cy - radius + half, cx + radius - half, cy + radius - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setShader(new LinearGradient(cx - radius, 0, cx + radius, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(Math.round(255 * alpha));
            c.drawOval(tmp2, ringPaint);
            ringPaint.setShader(null);
            ringPaint.setColor(0xFFFFFFFF);
        }

        private void drawAvatarGrid(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Escolha seu avatar", "Choose your avatar", "Elige tu avatar"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String cur = Avatars.current();
            for (int i = 0; i < Avatars.IDS.length; i++) {
                avatarCell(i, rowRect);
                float f = Ui.clamp(pop[i].value), cx = rowRect.centerX(), cy = rowRect.centerY(), r = rowRect.width() / 2 * (1 + 0.08f * f);
                Avatars.draw(c, getContext(), Avatars.IDS[i], cx, cy, r, 1f);
                if (Avatars.IDS[i].equals(cur)) {
                    avatarRingColor(c, cx, cy, r, 4.5f * k, 0xFFFFFFFF);
                    drawCheckBadge(c, cx + r * 0.72f, cy + r * 0.72f, 17 * k);
                }
                if (f > 0.01f) avatarRing(c, cx, cy, r, 6 * k, f);   // the focus: flush with the circle
            }
        }

        private void avatarRingColor(Canvas c, float cx, float cy, float radius, float width, int color) {
            float half = width / 2;
            tmp2.set(cx - radius + half, cy - radius + half, cx + radius - half, cy + radius - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setShader(null);
            ringPaint.setColor(color);
            c.drawOval(tmp2, ringPaint);
        }

        private final android.graphics.Path checkPath = new android.graphics.Path();

        /** The chosen avatar's mark: a gradient circle with a dark outline and a check. */
        private void drawCheckBadge(Canvas c, float bx, float by, float br) {
            tmp.set(bx - br, by - br, bx + br, by + br);
            white.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            white.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, white);
            white.setShader(null);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * k);
            ringPaint.setColor(INK);
            c.drawCircle(bx, by, br, ringPaint);
            checkPath.rewind();
            checkPath.moveTo(bx - 0.45f * br, by + 0.02f * br);
            checkPath.lineTo(bx - 0.1f * br, by + 0.38f * br);
            checkPath.lineTo(bx + 0.5f * br, by - 0.34f * br);
            ringPaint.setStrokeWidth(3.2f * k);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(checkPath, ringPaint);
            ringPaint.setStrokeCap(Paint.Cap.BUTT);
            ringPaint.setStrokeJoin(Paint.Join.MITER);
            ringPaint.setColor(0xFFFFFFFF);
        }

        private Paint cardTitle;

        /** First screen: the five groups as cards. */
        private void drawGroups(Canvas c) {
            String[][] info = groupInfo();
            String[] symbols = {"sparkles", "home", "settings", "gamepad", "tv_settings"};
            int[] order = groupOrder();
            if (cardTitle == null) cardTitle = Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF);
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Configurações", "Settings", "Configuración"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            rowR = 30 * k;
            for (int i = 0; i < 5; i++) {
                float y = (LIST_TOP + 20 + i * (CARD_H + CARD_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CARD_H * k);
                float f = drawRowBackground(c, i);
                cardTitle.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                rowSub.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                Symbols.draw(getContext(), c, symbols[order[i] - 1], rowRect.left + 74 * k, rowRect.centerY(), 46 * k, blend(0xCCFFFFFF, 0xFFFFFFFF, f));
                float tx = rowRect.left + 140 * k;
                c.drawText(info[order[i] - 1][0], tx, rowRect.centerY() - 6 * k, cardTitle);
                c.drawText(info[order[i] - 1][1], tx, rowRect.centerY() + 34 * k, rowSub);
                float cw = Symbols.width(getContext(), "chevron_right", 24 * k);
                Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 36 * k - cw / 2, rowRect.centerY(), 24 * k, blend(0x66FFFFFF, 0xCCFFFFFF, f));
            }
            rowR = -1;
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, weather, quotes, then one row per coin. */
        private static final int COIN_ROWS = 10, WALLPAPER_ROW = 9;
        /** Rows of a group that are not in the old Home Settings list: screensaver, shelf, launcher, TV settings. */
        private static final int ID_SAVER = 100, ID_SHELF = 101, ID_LAUNCHER = 102, ID_TV = 103, ID_SPLASH = 104, ID_WELCOME = 105, ID_SOUND = 106, ID_REMOTE = 107, ID_UPDATE = 108, ID_UPDATE_AUTO = 109, ID_HIDDEN = 110, ID_REBOOT = 111, ID_CUSTOM_WP = 112;
        private static final float CARD_H = 130, CARD_GAP = 14;

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Formato de hora", "Time format", "Formato de hora"));
            l.add(Ui.tr("Seu nome", "Your name", "Tu nombre"));
            l.add(Ui.tr("Cartões", "Cards", "Tarjetas"));
            l.add(Ui.tr("Acesso à reprodução", "Playback access", "Acceso a reproducción"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Widget de cotações", "Quotes widget"));
            l.add(Ui.tr("Papel de parede", "Wallpaper", "Fondo de pantalla"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español" : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            String tf = Prefs.timeFormat();
            v[3] = tf.equals("24") ? "24 h" : tf.equals("12") ? "12 h" : Ui.tr("Automático", "Automatic");
            v[4] = Prefs.name().isEmpty() ? Ui.tr("Toque OK para definir", "Press OK to set", "Pulsa OK para definir") : Prefs.name();
            v[5] = Prefs.cardsMode().equals("recent") ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo")
                    : Ui.tr("Recomendados", "Recommended", "Recomendados");
            v[6] = Watched.permitted(getContext()) ? Ui.tr("Ligado", "On", "Activado")
                    : Ui.tr("Desligado \u2014 OK para ligar", "Off \u2014 press OK to turn on", "Desactivado \u2014 OK para activar");
            v[7] = onOff(Prefs.weather());
            v[8] = onOff(Prefs.quotes());
            v[9] = wallpaperName(Prefs.wallpaper());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        private static String wallpaperName(int i) {
            String[] names = Prefs.wallpaperNames();
            return names[Math.max(0, Math.min(i, names.length - 1))];
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        /** The remote's buttons by name, in the order of Prefs.KEYS (the stored one). */
        private String keyRawLabel(int idx) {
            return new String[] {Ui.tr("Botão Netflix", "Netflix button", "Botón Netflix"), Ui.tr("Botão YouTube", "YouTube button", "Botón YouTube"),
                    Ui.tr("Botão Prime Video", "Prime Video button", "Botón Prime Video"), Ui.tr("Botão TCL Channel", "TCL Channel button", "Botón TCL Channel")}[idx];
        }

        /** Screen position → button index, alphabetical by name. */
        private int[] keyOrder() {
            final java.text.Collator col = collator();
            Integer[] a = new Integer[Prefs.KEYS.length];
            for (int i = 0; i < a.length; i++) a[i] = i;
            java.util.Arrays.sort(a, (x, y) -> col.compare(keyRawLabel(x), keyRawLabel(y)));
            int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i];
            return out;
        }

        private int keyIdx(int pos) { return keyOrder()[pos]; }

        private int keyPos(int idx) {
            int[] o = keyOrder();
            for (int i = 0; i < o.length; i++) if (o[i] == idx) return i;
            return 0;
        }

        private String[] keyLabels() {
            int[] o = keyOrder();
            String[] v = new String[o.length];
            for (int i = 0; i < v.length; i++) v[i] = keyRawLabel(o[i]);
            return v;
        }

        private String[] keyValues() {
            int[] o = keyOrder();
            String[] v = new String[o.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[o[i]]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            appLabels.add(Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[keyIdx(focus)]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[keyIdx(focus)], appPkgs.get(next));
                return;
            }
            adjustSetting(focus, dir);
        }

        /** Changes one setting (by its old Home Settings row id) one step. */
        private void adjustSetting(int id, int dir) {
            switch (id) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: {
                    String[] formats = {"auto", "24", "12"};
                    int at = java.util.Arrays.asList(formats).indexOf(Prefs.timeFormat());
                    Prefs.setTimeFormat(formats[(at + dir + formats.length) % formats.length]);
                    break;
                }
                case 4: askName(); return;
                case 5: Prefs.setCardsMode(Prefs.cardsMode().equals("recent") ? "recs" : "recent"); break;
                case 6: askPlaybackAccess(); return;
                case 7: Prefs.setWeather(!Prefs.weather()); break;
                case 8: Prefs.setQuotes(!Prefs.quotes()); break;
                case WALLPAPER_ROW: openWallpapers(false, false); return;
                case ID_SPLASH: Prefs.setSplash(!Prefs.splash()); return;
                case ID_UPDATE_AUTO: Prefs.setUpdateAuto(!Prefs.updateAuto()); return;
                case ID_REMOTE: askRemoteButton(); return;
                case ID_SOUND: Prefs.setStartupSound(!Prefs.startupSound()); if (Prefs.startupSound()) StartupSound.play(getContext()); return;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(id - COIN_ROWS, !Prefs.coin(id - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /** Asks "Restart the TV?" and, if the person accepts, restarts it through the TV's own adb (like the other system switches). */
        private void confirmReboot() {
            final Context ctx = getContext();
            new android.app.AlertDialog.Builder(ctx)
                    .setTitle(Ui.tr("Reiniciar a TV", "Restart the TV", "Reiniciar TV"))
                    .setMessage(Ui.tr("A TV vai reiniciar agora. Deseja continuar?",
                            "The TV will restart now. Do you want to continue?",
                            "El TV se reiniciará ahora. ¿Quieres continuar?"))
                    .setPositiveButton(Ui.tr("Reiniciar", "Restart", "Reiniciar"), (d, w) -> HomeMode.requestReboot(ctx))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel", "Cancelar"), null)
                    .show();
        }

        /**
         * Playback access: Android ties reading what the apps are playing to "Notification access". Opens that screen; TVs
         * that don't have it get the one-line adb command instead.
         */
        private void askPlaybackAccess() {
            if (Watched.permitted(getContext())) {
                new android.app.AlertDialog.Builder(getContext())
                        .setTitle(Ui.tr("Acesso à reprodução", "Playback access", "Acceso a reproducción"))
                        .setMessage(Ui.tr("Já está ligado. Para desligar, retire o Borealis em Acesso a notificações.",
                                "It is already on. To turn it off, remove Borealis from Notification access.",
                                "Ya está activado. Para desactivarlo, quita Borealis en Acceso a notificaciones."))
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            if (Watched.openAccessSettings(getContext())) return;
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle(Ui.tr("Acesso à reprodução", "Playback access", "Acceso a reproducción"))
                    .setMessage(Ui.tr("Esta TV não tem a tela de acesso a notificações. No computador, com a TV conectada ao adb, rode:\n\nadb shell cmd notification allow_listener io.borealis/io.borealis.MediaWatcher",
                            "This TV has no notification access screen. From a computer connected to the TV with adb, run:\n\nadb shell cmd notification allow_listener io.borealis/io.borealis.MediaWatcher",
                            "Este TV no tiene la pantalla de acceso a notificaciones. Desde un computador conectado al TV con adb, ejecuta:\n\nadb shell cmd notification allow_listener io.borealis/io.borealis.MediaWatcher"))
                    .setPositiveButton("OK", null)
                    .show();
        }

        // MARK: - Hidden apps screen

        private static final int HID_COLS = 5;
        private final List<HomeApps.Item> hidItems = new ArrayList<>();
        private Spring[] hidPop = new Spring[0];
        private boolean hidFromHome;
        private Paint hidLabel;
        private float hidLabelK;

        void showHiddenApps() {
            openHiddenApps(true);
        }

        private void openHiddenApps(boolean fromHome) {
            hidFromHome = fromHome;
            hidItems.clear();
            for (String pkg : Prefs.hiddenApps()) {
                HomeApps.Item it = HomeApps.item(getContext(), pkg);
                if (it != null) hidItems.add(it);
            }
            screen = HIDDEN_APPS;
            focus = hidItems.isEmpty() ? -2 : 0;
            hidSprings();
            invalidate();
            spoken.focusMoved();
        }

        private void hidSprings() {
            hidPop = new Spring[hidItems.size()];
            for (int i = 0; i < hidPop.length; i++) {
                hidPop[i] = new Spring(0.28f, 0.85f, 0);
                if (i == focus) hidPop[i].snap(1);
            }
        }

        /** Card i: rows of up to five, centered, the rows scrolling when the focus goes down (the third row on). */
        private void hidCell(int i, RectF out) {
            int n = hidItems.size();
            int row = i / HID_COLS, col = i % HID_COLS, inRow = Math.min(HID_COLS, n - row * HID_COLS);
            float cw = 300, ch = 169, gap = 32, pitch = 303;
            float rowW = inRow * cw + (inRow - 1) * gap;
            float x = (1920 - rowW) / 2f + col * (cw + gap);
            int focusRow = focus >= 0 ? focus / HID_COLS : 0;
            float y = 250 + row * pitch - Math.max(0, focusRow - 1) * pitch;
            out.set(x * k, y * k, (x + cw) * k, (y + ch) * k);
        }

        private boolean hiddenKey(KeyEvent e, boolean down) {
            int n = hidItems.size();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && focus > 0 && focus % HID_COLS > 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && focus >= 0 && focus % HID_COLS < HID_COLS - 1 && focus + 1 < n) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus >= HID_COLS) focus -= HID_COLS;
                    else if (down && focus >= 0) focus = -2;   // the first row: up to the Back button
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = n > 0 ? 0 : -2;
                    else if (down) {
                        if (focus + HID_COLS < n) focus += HID_COLS;
                        else if (focus / HID_COLS < (n - 1) / HID_COLS) focus = n - 1;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** OK on a card: the app is shown again (it goes back to All Apps) and the card leaves this list. */
        private void hiddenRestore() {
            if (focus < 0 || focus >= hidItems.size()) return;
            HomeApps.Item it = hidItems.remove(focus);
            List<String> now = Prefs.hiddenApps();
            now.remove(it.pkg);
            Prefs.setHiddenApps(now);
            if (hidItems.isEmpty()) focus = -2;
            else focus = Math.min(focus, hidItems.size() - 1);
            hidSprings();
            invalidate();
            spoken.focusMoved();
        }

        private void drawHiddenApps(Canvas c) {
            wpPaints();
            if (hidLabel == null || hidLabelK != k) {
                hidLabelK = k;
                hidLabel = Ui.text(Ui.medium, 24 * k, 0xE6FFFFFF);
                hidLabel.setTextAlign(Paint.Align.CENTER);
            }
            c.drawText(Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas"), w / 2f, 78 * k, wpTitle);
            c.drawText(hidItems.isEmpty()
                            ? Ui.tr("Nenhum app oculto. Para ocultar um: segure OK sobre ele em Todos os apps.",
                                    "No hidden apps. To hide one: hold OK on it in All Apps.",
                                    "No hay apps ocultas. Para ocultar una: mantén OK sobre ella en Todas las apps.")
                            : Ui.tr("Escolha o app que quer mostrar de novo.", "Pick the app you want to show again.", "Elige la app que quieres volver a mostrar."),
                    w / 2f, 122 * k, wpDesc);
            RectF cell = new RectF();
            for (int i = 0; i < hidItems.size(); i++) {
                hidCell(i, cell);
                if (cell.bottom < 170 * k || cell.top > h) continue;   // scrolled out
                float f = Ui.clamp(i < hidPop.length ? hidPop[i].value : 0);
                float s = 1 + 0.06f * f, cx = cell.centerX(), cy = cell.centerY();
                float bw = cell.width() * s, bh = cell.height() * s, rad = 18 * k * s;
                tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                if (f > 0.01f) {
                    tmp.offset(0, 10 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                    c.drawRoundRect(tmp, rad, rad, shadow);
                    tmp.offset(0, -10 * k * f);
                }
                HomeApps.Item it = hidItems.get(i);
                int save = c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(tmp, rad, rad, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                if (it.tile != null) c.drawBitmap(it.tile, null, tmp, wpImg);
                if (f < 0.99f) {   // not in focus: a little dimmer
                    wpFill.setStyle(Paint.Style.FILL);
                    wpFill.setShader(null);
                    wpFill.setColor(Ui.alpha(0xFF000000, 0.3f * (1 - f)));
                    c.drawRect(tmp, wpFill);
                }
                c.restoreToCount(save);
                if (f > 0.01f) {
                    float ring = 4.5f * k, half = ring / 2;
                    tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
                    ringPaint.setStyle(Paint.Style.STROKE);
                    ringPaint.setStrokeWidth(ring);
                    ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                    ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                    c.drawRoundRect(tmp2, rad - half, rad - half, ringPaint);
                    ringPaint.setShader(null);
                }
                c.drawText(it.label, cx, cell.bottom + 40 * k, hidLabel);
                if (i == focus) {   // the action, under the card in focus
                    wpButton(c, cx, cell.bottom + 60 * k, 280 * k, 56 * k,
                            Ui.tr("Mostrar de novo", "Show again", "Volver a mostrar"), f);
                }
            }
        }

        /** Hidden apps: the list; picking one shows it again (in All Apps). */
        private void askHiddenApps() {
            final List<String> hidden = Prefs.hiddenApps();
            if (hidden.isEmpty()) {
                new android.app.AlertDialog.Builder(getContext())
                        .setTitle(Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas"))
                        .setMessage(Ui.tr("Nenhum app oculto. Para ocultar um: segure OK sobre ele na prateleira.",
                                "No hidden apps. To hide one: hold OK on it in the shelf.",
                                "No hay apps ocultas. Para ocultar una: mantén OK sobre ella en el estante."))
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            final String[] labels = new String[hidden.size()];
            for (int i = 0; i < labels.length; i++) {
                try {
                    labels[i] = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(hidden.get(i), 0)));
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    labels[i] = hidden.get(i);
                }
            }
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle(Ui.tr("Escolha um app para mostrar de novo", "Pick an app to show it again", "Elige una app para mostrarla de nuevo"))
                    .setItems(labels, (d, which) -> {
                        List<String> now = Prefs.hiddenApps();
                        now.remove(hidden.get(which));
                        Prefs.setHiddenApps(now);
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Fechar", "Close", "Cerrar"), null)
                    .show();
        }

        /** Asks how the user wants to be greeted (the TV's keyboard opens when the field is selected). */
        private void askName() {
            final android.widget.EditText input = new android.widget.EditText(getContext());
            input.setSingleLine(true);
            input.setText(Prefs.name());
            input.setSelection(input.getText().length());
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle(Ui.tr("Como você quer ser chamado?", "What should we call you?", "¿Cómo quieres que te llame?"))
                    .setView(input)
                    .setPositiveButton("OK", (d, which) -> {
                        Prefs.setName(input.getText().toString());
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel", "Cancelar"), null)
                    .show();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            group = 2;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (listRowH() + listRowGap())) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, 0xFFFFFFFF, f));   // white on focus, like every other list
                rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            int total = shelfRows.size();
            if (total > rows && rows > 0) {   // there are more apps than fit: a scroll bar at the right shows where you are
                float top = (LIST_TOP + 20) * k, bottom = (LIST_TOP + 20 + rows * (listRowH() + listRowGap()) - listRowGap()) * k;
                float bx = (LIST_R + 18) * k, bw = 8 * k, track = bottom - top;
                float thumb = Math.max(48 * k, track * rows / total);
                float at = top + (track - thumb) * Math.min(1f, pickScroll / (float) (total - rows));
                tmp.set(bx, top, bx + bw, bottom);
                wpFill.setStyle(Paint.Style.FILL);
                wpFill.setShader(null);
                wpFill.setColor(0x26FFFFFF);
                c.drawRoundRect(tmp, bw / 2, bw / 2, wpFill);
                tmp.set(bx, at, bx + bw, at + thumb);
                wpFill.setShader(new LinearGradient(0, at, 0, at + thumb, Brand.accents(), null, Shader.TileMode.CLAMP));
                wpFill.setColor(0xFFFFFFFF);
                c.drawRoundRect(tmp, bw / 2, bw / 2, wpFill);
                wpFill.setShader(null);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (listRowH() + listRowGap()) + 36) * k;
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
        }

        // MARK: - Wallpaper screen

        private static final float WP_MARGIN = 142, WP_CLIP_R = 1778;
        /** Carousel (1920 space): the sample in the middle, the next ones smaller at each side, each one further out and fainter. */
        private static final float SMP_W = 1100, THUMB_W = 400, THUMB_H = 225, THUMB_R = 20;
        private static final float BACK_X = 60, BACK_Y = 50, BACK_W = 200, BACK_H = 64;
        private static final float BTN_H = 52, PILL_W = 110, PILL_H = 52, PILL_GAP = 14, ROW_TOP = 957, STATUS_R = 16;
        private Bitmap[] wpThumb;                 // the side pictures, rounded
        private Bitmap[] wpSmall;                 // each picture small and square-cornered: what the sample shows until the sharp one is ready
        private Bitmap[] wpFull;                  // the sharp pictures of the one in front and its two neighbors
        private long[] wpFullAt;
        private boolean[] wpPending;
        private Bitmap[] wpBlurs;                 // each picture tiny and blurred: the soft backdrop behind everything
        private int wpBlurFrom = -1;
        private Bitmap wpToLast;                      // the backdrop picture last drawn, and when it first appeared (so it fades in)
        private long wpToAt;
        private long[] wpThumbAt;                     // when each small picture first had its image (it fades in over its empty frame)
        private long wpBlurAt;
        private long wpZoomAt;                    // last frame of the wallpaper sample's slow zoom
        private boolean wpOnBack;                 // focus is on the "Back" button
        private int wpRow;                        // 0 the carousel, 1 the row below it (the times and "Preview", or "Hide elements")
        private int wpPill;                       // in that row: 0-3 a time, 4 the button at the right
        private final Spring wpBackPop = new Spring(0.28f, 0.85f, 0);
        private final Spring wpIn = new Spring(0.32f, 1f, 1);
        private final Spring wpCarPop = new Spring(0.28f, 0.85f, 0);   // the ring around the sample when the carousel has the focus
        private final Spring[] wpPillPop = {new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0)};   // 4 times + the button at the right
        private static final float BACKDROP_ALPHA = 0.8f;
        private final Spring wpClean = new Spring(0.35f, 0.9f, 0), wpScroll = new Spring(0.3f, 0.9f, 0);   // wpScroll: the carousel's position
        private java.util.concurrent.ExecutorService wpPool;
        private final Paint wpFill = new Paint(Paint.ANTI_ALIAS_FLAG), wpImg = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Paint wpName, wpSub, wpHint, wpGreetSmall, wpGreetBig, wpCapsuleText, wpPillText, wpBackText, wpBtnText, wpStatus, wpTabOn, wpTabOff, wpSection, wpCardTitle, wpCardMeta, wpChip, wpTitle, wpDesc;
        private float wpK;
        private boolean wpSaver;                  // the same screen picks the screensaver (its picture is a still of the video)
        private int wpSaverCurrent;
        private Paint svClock, svDate, svWeather, svLabel, svTitle, svSub, svBar, svTime;

        private String[] wpFiles() { return wpSaver ? Prefs.SAVER_POSTERS : Prefs.wallpaperFiles(); }
        private String[] wpNames() { return wpSaver ? Prefs.SAVER_NAMES : Prefs.wallpaperNames(); }
        private int wpCurrent() { return wpSaver ? wpSaverCurrent : Prefs.wallpaper(); }
                private final android.graphics.Path wpClip = new android.graphics.Path();

        private boolean wpFromMain;
        // Remembered between visits (small), so coming back is immediate.
        private static Bitmap wpFullCache;
        private static long wpCustomStamp = -1;   // which version of the person's picture the remembered pictures below show
        private static int wpFullCacheIdx = -1;
        private static boolean wpFullCacheSaver;
        private static final Bitmap[][] CACHE_THUMB = new Bitmap[2][], CACHE_SMALL = new Bitmap[2][], CACHE_BLUR = new Bitmap[2][];
        private static Bitmap[] tilesCache;
        private static int pinnedCache;
        private Bitmap wpSnap;                        // the screen we came from, kept still until the new one has everything it needs
        private boolean wpHold;
        private long wpHoldAt;

        /** A still of what is on screen now (null if there is no memory for it). */
        private Bitmap takeSnapshot() {
            if (w <= 0 || h <= 0) return null;
            try {
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                draw(new Canvas(b));
                return b;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        private void dropSnapshot() {
            Bitmap b = wpSnap;
            wpSnap = null;
            wpHold = false;
            if (b != null) b.recycle();
        }

        /** Everything the new screen draws is loaded: the front picture and its backdrop, its two neighbors, and the shelf. */
        private boolean wpReady() {
            int n = wpFiles().length;
            if (n == 0 || focus < 0 || focus >= n) return true;
            if (wpBlurs == null || wpSmall == null || wpFull == null) return false;
            int a = Math.floorMod(focus - 1, n), b = (focus + 1) % n;
            if (wpBlurs[focus] == null || wpFull[focus] == null) return false;
            if (wpSmall[focus] == null || wpSmall[a] == null || wpSmall[b] == null) return false;
            return wpSaver || wpTiles != null;
        }

        /** The hold is over: from here everything is already loaded, so nothing fades on its own: one fade for the whole screen. */
        private void wpRelease() {
            wpHold = false;
            long long_ago = 1;   // any non-zero time far in the past counts as "already faded in"
            if (wpThumbAt != null) for (int i = 0; i < wpThumbAt.length; i++) if (wpSmall != null && i < wpSmall.length && wpSmall[i] != null) wpThumbAt[i] = long_ago;
            wpToLast = wpBlurs != null && focus >= 0 && focus < wpBlurs.length ? wpBlurs[focus] : null;
            wpToAt = 0;
            wpIn.snap(0);
            wpIn.target = 1;
        }

        private void openWallpapers(boolean saver, boolean fromMain) {
            long stampNow = Prefs.hasCustom() ? Prefs.customVersion() : 0;
            if (stampNow != wpCustomStamp) {   // the person's picture was added, changed or removed: forget what was remembered
                wpCustomStamp = stampNow;
                wpFullCache = null;
                CACHE_THUMB[0] = CACHE_SMALL[0] = CACHE_BLUR[0] = null;
            }
            dropSnapshot();
            wpSnap = takeSnapshot();
            wpSaver = saver;
            wpFromMain = fromMain;
            if (saver) wpSaverCurrent = Prefs.screensaver();
            screen = WALLPAPERS;
            focus = wpCurrent();
            wpClean.snap(0);
            wpScroll.snap(focus);
            wpSmall = wpFull = null;
            wpBlurs = null;
            wpBlurFrom = -1;
            wpBlurAt = 0;
            wpOnBack = false;
            wpRow = 0;
            wpPill = 1;
            wpCarPop.snap(0);
            HomeData.get(getContext()).refreshIfStale();
            wpBackPop.snap(0);
            wpIn.snap(0);   // stays at 0 while the old screen is held; wpRelease() starts the one fade
            if (wpSnap != null) { wpHold = true; wpHoldAt = SystemClock.uptimeMillis(); } else wpIn.target = 1;
            for (Spring sp : wpPillPop) sp.snap(0);
            wpPool = java.util.concurrent.Executors.newFixedThreadPool(2);   // the front picture and the shelf load next to the thumbnails, not after them
            int n = wpFiles().length;
            wpFull = new Bitmap[n];
            wpFullAt = new long[n];
            wpThumbAt = new long[n];
            wpToLast = null;
            wpToAt = 0;
            wpPending = new boolean[n];
            if (wpFullCache != null && !wpFullCache.isRecycled() && wpFullCacheSaver == wpSaver && wpFullCacheIdx == focus && focus < n) {
                wpFull[focus] = wpFullCache;   // the picture in use was seen a moment ago: no wait
                wpFullAt[focus] = 1;
            }
            wantFull(focus, true);   // first the picture in front and the shelf; then the thumbnails; the neighbors a little later
            loadTiles();
            loadThumbs();
            handler.postDelayed(() -> { if (wpPool != null && wpFull != null && screen == WALLPAPERS) wantFull(focus); }, 350);
            spoken.focusMoved();
            scheduleLive();
        }

        private void closeWallpapers() {
            handler.removeCallbacks(liveStart);
            stopLive();
            if (wpPool != null) wpPool.shutdownNow();
            wpPool = null;
            wpSmall = wpFull = null;
            wpBlurs = null;
            wpThumb = null;
            wpTiles = null;
            group = 1;
            screen = GROUP;
            focus = groupIndexOf(1, wpSaver ? ID_SAVER : WALLPAPER_ROW);
        }

        /** Brings item `idx` to the front by the shortest way around the circle. */
        private void wpGo(int idx) {
            int n = wpFiles().length;
            int cur = Math.round(wpScroll.target);
            int delta = Math.floorMod(idx - cur, n);
            if (delta > n / 2) delta -= n;
            wpScroll.target = cur + delta;
            int was = focus;
            focus = Math.floorMod(Math.round(wpScroll.target), n);
            if (focus != was) {
                wpBlurFrom = was;
                wpBlurAt = SystemClock.uptimeMillis();
            }
            wantFull(focus);
            scheduleLive();
        }

        /** Signed distance (in items) from the front of the carousel, the short way around. */
        private float wpDist(int i) {
            int n = wpFiles().length;
            float d = i - wpScroll.value;
            return d - n * Math.round(d / n);
        }

        private float carWidth(float d) {
            d = Math.abs(d);
            if (d >= 2) return 260;
            if (d < 1) return SMP_W + (THUMB_W - SMP_W) * d;
            return THUMB_W + (260 - THUMB_W) * (d - 1);
        }

        private float carOffset(float d) {
            float a = Math.abs(d), o;
            if (a < 1) o = 800 * a;
            else o = 800 + 380 * (a - 1);
            return d < 0 ? -o : o;
        }

        private float carAlpha(float d) {
            float a = Math.abs(d);
            if (a < 1) return 1 - 0.3f * a;
            return Math.max(0, 0.7f * (1 - (a - 1) / 0.5f));
        }

        private float wpTop() { return 225; }

        /** Where item `i` is right now (screen pixels), written to `out`. */
        private void wpItemRect(int i, RectF out) {
            float d = wpDist(i), iw = carWidth(d) * k, ih = iw * 9f / 16f;
            float cx = w / 2f + carOffset(d) * k, cy = (wpTop() + SMP_W * 9f / 32f) * k;
            out.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
        }

        private Bitmap decodeWallpaper(String file, int sample, int targetWidth) {
            try (java.io.InputStream in = Prefs.openWallpaper(getContext(), file)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                if (targetWidth > 0 && targetWidth < 1920) {
                    o.inScaled = true;
                    o.inDensity = 1920;
                    o.inTargetDensity = targetWidth;
                }
                return BitmapFactory.decodeStream(in, null, o);
            } catch (Exception | OutOfMemoryError ignored) {
                return null;
            }
        }

        private Bitmap[] wpTiles;   // the home's bottom panel: the pinned apps' cards, then the first of the other apps
        private int wpPinned;       // how many of wpTiles are the pinned ones (the first row)

        private void loadTiles() {
            final java.util.concurrent.ExecutorService pool = wpPool;
            if (wpSaver || pool == null) return;
            wpTiles = tilesCache;   // the last ones, at once; fresh ones replace them when ready
            wpPinned = pinnedCache;
            pool.execute(() -> {
                List<HomeApps.Item> row1 = new ArrayList<>(), row2 = new ArrayList<>();
                try {
                    for (HomeApps.Item it : HomeApps.shelf(getContext(), false)) if (it.kind == HomeApps.APP && row1.size() < 7) row1.add(it);
                    List<HomeApps.Item> rest = HomeApps.others(getContext());
                    for (HomeApps.Item it : rest) if (it.kind == HomeApps.APP && row2.size() < 7) row2.add(it);
                } catch (RuntimeException e) {
                    return;
                }
                final Bitmap[] out = new Bitmap[row1.size() + row2.size()];
                for (int i = 0; i < out.length; i++) {
                    HomeApps.Item it = i < row1.size() ? row1.get(i) : row2.get(i - row1.size());
                    try {
                        out[i] = renderTile(it, 392, 224);
                    } catch (RuntimeException | OutOfMemoryError ignored) {
                    }
                }
                final int pinned = row1.size();
                handler.post(() -> {
                    if (wpPool == pool) {
                        wpTiles = out;
                        wpPinned = pinned;
                        tilesCache = out;
                        pinnedCache = pinned;
                        invalidate();
                    }
                });
            });
        }

        /** One card of the bottom panel as the home draws it: the finished art if there is one, else the app's own TV art. */
        private Bitmap renderTile(HomeApps.Item it, int tw, int th) {
            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float rr = 17f * tw / 196f;
            RectF box = new RectF(0, 0, tw, th);
            Bitmap art = null;
            try (java.io.InputStream in = getContext().getAssets().open("cards/" + it.key + ".png")) {
                art = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
            if (art != null) {
                cv.drawBitmap(art, null, box, pt);
                art.recycle();
                return out;
            }
            if (it.tile != null) {
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(it.tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(tw / (float) it.tile.getWidth(), th / (float) it.tile.getHeight());
                shader.setLocalMatrix(m);
                pt.setShader(shader);
                cv.drawRoundRect(box, rr, rr, pt);
            }
            return out;
        }

        private void loadThumbs() {
            final int n = wpFiles().length, kind = wpSaver ? 1 : 0;
            if (CACHE_THUMB[kind] == null || CACHE_THUMB[kind].length != n) {
                CACHE_THUMB[kind] = new Bitmap[n];
                CACHE_SMALL[kind] = new Bitmap[n];
                CACHE_BLUR[kind] = new Bitmap[n];
            }
            final Bitmap[] mine = CACHE_THUMB[kind], small = CACHE_SMALL[kind], blurs = CACHE_BLUR[kind];
            wpThumb = mine;
            wpSmall = small;
            wpBlurs = blurs;
            final int tw = Math.max(32, Math.round(THUMB_W * k)), th = Math.max(18, Math.round(THUMB_H * k));
            final float radius = THUMB_R * k;
            final int start = focus;
            final java.util.concurrent.ExecutorService pool = wpPool;
            pool.execute(() -> {
                for (int q = 0; q < n; q++) {
                    // The one in front first, then its two neighbors (what the screen needs to show), then the rest.
                    int i = n >= 3 && q == 1 ? (start + 1) % n : n >= 3 && q == 2 ? Math.floorMod(start - 1, n) : n >= 3 && q >= 3 ? (start + q - 1) % n : (start + q) % n;
                    if (wpThumb != mine) return;
                    if (mine[i] != null) continue;   // kept from the last visit
                    Bitmap b = decodeWallpaper(wpFiles()[i], Integer.highestOneBit(Math.max(1, 1920 / (tw * 2))), 0);
                    if (b == null) continue;
                    Bitmap sm = Bitmap.createScaledBitmap(b, tw, th, true);
                    Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    android.graphics.BitmapShader shader = new android.graphics.BitmapShader(sm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    p.setShader(shader);
                    new Canvas(out).drawRoundRect(new RectF(0, 0, tw, th), radius, radius, p);
                    Bitmap soft = softBackdrop(b);
                    if (b != sm) b.recycle();
                    mine[i] = out;
                    small[i] = sm;
                    blurs[i] = soft;
                    handler.post(() -> { if (wpThumb == mine) invalidate(); });
                }
            });
        }

        /** A picture tiny and blurred (3 box passes), so it stays smooth when stretched over the whole screen. */
        static Bitmap softBackdrop(Bitmap b) {
            try {
                Bitmap soft = Bitmap.createScaledBitmap(b, 192, 108, true);
                if (!soft.isMutable()) {
                    Bitmap copy = soft.copy(Bitmap.Config.ARGB_8888, true);
                    if (copy != null) {
                        if (soft != b) soft.recycle();
                        soft = copy;
                    }
                }
                int[] px = new int[192 * 108];
                soft.getPixels(px, 0, 192, 0, 0, 192, 108);
                for (int pass = 0; pass < 3; pass++) boxBlur(px, 192, 108, 7);
                soft.setPixels(px, 0, 192, 0, 0, 192, 108);
                return soft;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        /** Keeps the sharp pictures of item `idx` and its two neighbors (so the next turn of the carousel never waits for a decode). */
        private void wantFull(final int idx) { wantFull(idx, false); }

        private void wantFull(final int idx, final boolean frontOnly) {
            final java.util.concurrent.ExecutorService pool = wpPool;
            final Bitmap[] mine = wpFull;
            if (pool == null || mine == null) return;
            final int n = mine.length;
            final int width = Math.max(480, Math.min(1920, Math.round(SMP_W * k * 1.15f)));
            for (int i = 0; i < n; i++) {
                int dd = Math.floorMod(i - idx, n);
                if (dd > 1 && dd < n - 1) mine[i] = null;   // far away: let it go
            }
            for (int d : new int[] {0, 1, -1}) {
                if (frontOnly && d != 0) continue;
                final int i = Math.floorMod(idx + d, n);
                if (mine[i] != null || wpPending[i]) continue;
                wpPending[i] = true;
                pool.execute(() -> {
                    final Bitmap b = decodeWallpaper(wpFiles()[i], 1, width);
                    handler.post(() -> {
                        wpPending[i] = false;
                        if (wpPool != pool || wpFull != mine || b == null) return;
                        mine[i] = b;
                        wpFullAt[i] = SystemClock.uptimeMillis();
                        if (i == wpCurrent()) { wpFullCache = b; wpFullCacheIdx = i; wpFullCacheSaver = wpSaver; }
                        invalidate();
                    });
                });
            }
        }

        private boolean wpFading() {
            long now = SystemClock.uptimeMillis();
            if (now - wpBlurAt < 320 || now - wpToAt < 320) return true;
            if (wpThumbAt != null) for (long t : wpThumbAt) if (t != 0 && now - t < 280) return true;
            if (wpFullAt != null) for (long t : wpFullAt) if (now - t < 240) return true;
            return false;
        }

        private void wpSetRow(int row) {
            wpOnBack = row < 0;
            wpRow = Math.max(0, row);
        }

        /** One box-blur pass over opaque pixels (horizontal, then vertical), edges repeated. */
        private static void boxBlur(int[] px, int bw, int bh, int r) {
            int[] tmpRow = new int[Math.max(bw, bh)];
            int div = 2 * r + 1;
            for (int y = 0; y < bh; y++) {
                int base = y * bw;
                for (int x = 0; x < bw; x++) tmpRow[x] = px[base + x];
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    int p = tmpRow[Math.max(0, Math.min(bw - 1, i))];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
                }
                for (int x = 0; x < bw; x++) {
                    px[base + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                    int out = tmpRow[Math.max(0, x - r)], in = tmpRow[Math.min(bw - 1, x + r + 1)];
                    sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                    sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                    sb += (in & 255) - (out & 255);
                }
            }
            for (int x = 0; x < bw; x++) {
                for (int y = 0; y < bh; y++) tmpRow[y] = px[y * bw + x];
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    int p = tmpRow[Math.max(0, Math.min(bh - 1, i))];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
                }
                for (int y = 0; y < bh; y++) {
                    px[y * bw + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                    int out = tmpRow[Math.max(0, y - r)], in = tmpRow[Math.min(bh - 1, y + r + 1)];
                    sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                    sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                    sb += (in & 255) - (out & 255);
                }
            }
        }

        private boolean wallpaperKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        boolean dn = code == KeyEvent.KEYCODE_DPAD_DOWN;
                        if (wpOnBack) {
                            if (dn) wpSetRow(0);
                        } else if (dn) {
                            if (wpRow == 0) {
                                if (wpSaver) {
                                    wpPill = 1;
                                    int now = (int) (saverTimeMs() / 60_000);
                                    for (int i = 0; i < SAVER_MINUTES.length; i++) if (SAVER_MINUTES[i] == now) wpPill = i;
                                } else {
                                    wpPill = 4;
                                }
                                wpSetRow(1);
                            }
                        } else {
                            wpSetRow(wpRow == 0 ? -1 : 0);
                        }
                        spoken.focusMoved();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        if (wpOnBack) {
                            // nothing to the sides of "Back"
                        } else if (wpRow == 0) {
                            int n = wpFiles().length;
                            wpGo(Math.floorMod(focus + dir, n));   // circular: the next one comes to the front
                            spoken.focusMoved();
                        } else if (wpSaver) {   // the four times, then "Preview" at the right
                            wpPill = Math.max(0, Math.min(4, wpPill + dir));
                        }
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void wpPaints() {
            if (wpK == k && wpName != null) return;
            wpK = k;
            wpName = Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF);
            wpName.setTextAlign(Paint.Align.CENTER);
            wpSub = Ui.text(Ui.medium, 23 * k, LILY);
            wpSub.setTextAlign(Paint.Align.CENTER);
            wpHint = Ui.text(Ui.regular, 21 * k, 0x80FFFFFF);
            wpHint.setTextAlign(Paint.Align.CENTER);
            wpTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            wpTitle.setTextAlign(Paint.Align.CENTER);
            wpDesc = Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF);
            wpDesc.setTextAlign(Paint.Align.CENTER);
            wpGreetSmall = Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF);
            wpGreetBig = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
            wpCapsuleText = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
            wpCapsuleText.setTextAlign(Paint.Align.CENTER);
            wpPillText = Ui.text(Ui.medium, 19 * k, 0xFFFFFFFF);
            wpPillText.setTextAlign(Paint.Align.CENTER);
            wpBackText = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
            wpBtnText = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
            wpBtnText.setTextAlign(Paint.Align.CENTER);
            wpTabOn = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
            wpTabOn.setTextAlign(Paint.Align.CENTER);
            wpTabOff = Ui.text(Ui.medium, 24 * k, 0xCCFFFFFF);
            wpTabOff.setTextAlign(Paint.Align.CENTER);
            wpSection = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            wpCardTitle = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            wpCardMeta = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
            wpChip = Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF);
            wpStatus = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
            wpStatus.setTextAlign(Paint.Align.RIGHT);
        }

        /**
         * The picker: a carousel. The picture in focus is the sample (with the screen's own elements) in the middle; the
         * next ones are smaller and fainter at each side. Under it: the buttons and, for the screensaver, the times.
         */
        private void drawWallpapers(Canvas c, float dt) {
            wpPaints();
            wpClean.step(dt);
            wpScroll.step(dt);
            wpBackPop.target = wpOnBack ? 1 : 0;
            wpBackPop.step(dt);
            wpCarPop.target = !wpOnBack && wpRow == 0 ? 1 : 0;
            wpCarPop.step(dt);
            for (int i = 0; i < wpPillPop.length; i++) {
                wpPillPop[i].target = !wpOnBack && wpRow == 1 && wpPill == i ? 1 : 0;
                wpPillPop[i].step(dt);
            }
            float ui = 1 - Ui.clamp(wpClean.value);
            int n = wpFiles().length;
            c.drawColor(INK);
            // Behind everything: the picture in front, tiny and blurred, over the night black (cross-fading from the one before).
            waveDst.set(0, 0, w, h);
            float eb = Ui.clamp((SystemClock.uptimeMillis() - wpBlurAt) / 300f);
            Bitmap to = wpBlurs != null && focus < wpBlurs.length ? wpBlurs[focus] : null;
            Bitmap from = wpBlurs != null && wpBlurFrom >= 0 && wpBlurFrom < wpBlurs.length && wpBlurFrom != focus ? wpBlurs[wpBlurFrom] : null;
            if (to != wpToLast) {   // a picture's backdrop just arrived (or the focus moved): it fades in
                wpToLast = to;
                wpToAt = SystemClock.uptimeMillis();
            }
            float ea = Ui.clamp((SystemClock.uptimeMillis() - wpToAt) / 300f);
            // Until the picture's own backdrop is ready (and while it fades in) the settings' backdrop stays: no dark gap, no pop.
            Bitmap under = from != null && (eb < 1 || to == null) ? from : (to == null || ea < 1 ? bgSoft : null);
            if (under != null) {
                wpImg.setAlpha(Math.round(255 * BACKDROP_ALPHA));
                c.drawBitmap(under, null, waveDst, wpImg);
            }
            if (to != null) {
                wpImg.setAlpha(Math.round(255 * BACKDROP_ALPHA * (from != null && eb < 1 ? eb : ea)));
                c.drawBitmap(to, null, waveDst, wpImg);
            }
            wpImg.setAlpha(255);

            // Where the sample rests (the live preview is placed there).
            float mw = SMP_W * k, mh = mw * 9f / 16f;
            wpMiniScale = mw / w;
            wpMiniX = w / 2f - mw / 2f;
            wpMiniY = wpTop() * k;

            // The items, the farthest first so the nearest are on top.
            int[] order = wpOrder != null && wpOrder.length == n ? wpOrder : (wpOrder = new int[n]);
            for (int i = 0; i < n; i++) order[i] = i;
            for (int i = 1; i < n; i++) {
                int v = order[i];
                float dv = Math.abs(wpDist(v));
                int j = i - 1;
                while (j >= 0 && Math.abs(wpDist(order[j])) < dv) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = v;
            }
            for (int q = 0; q < n; q++) {
                int i = order[q];
                float d = wpDist(i), alpha = carAlpha(d);
                if (alpha <= 0.01f) continue;
                wpItemRect(i, wpBox);
                float iw = wpBox.width();
                float r = Math.max(12 * k, 24 * k * iw / mw);
                if (Math.abs(d) < 1) {
                    // The sample fades with the same curve as the small pictures, so nothing jumps when it turns into one.
                    int layer = alpha < 0.99f ? c.saveLayerAlpha(wpBox.left - 14 * k, wpBox.top - 14 * k, wpBox.right + 14 * k, wpBox.bottom + 14 * k, Math.round(255 * alpha)) : -1;
                    drawSample(c, wpBox, r, ui, i, i == focus, 1 - Ui.clamp(Math.abs(d) * 1.6f));
                    if (layer >= 0) c.restoreToCount(layer);
                    drawBadge(c, i, wpBox, alpha * Ui.clamp((Math.abs(d) - 0.5f) / 0.5f));
                } else {
                    drawThumb(c, i, wpBox, r, alpha);
                    drawBadge(c, i, wpBox, alpha);
                }
            }

            // Above the sample, at its right: whether this one is in use (a filled check) or can be chosen (an empty circle).
            float left = w / 2f - SMP_W * k / 2f, right = left + SMP_W * k;
            float frontness = 1 - Ui.clamp(Math.abs(wpDist(focus)) * 1.6f);
            boolean inUse = focus == wpCurrent();
            drawStatus(c, right, (wpTop() - 40) * k, inUse, frontness);

            // Under it: the name, small.
            float top = wpTop(), tvCx = w / 2f, nameY = (top + SMP_W * 9f / 16f + 78) * k;
            String title = wpNames()[focus];
            wpName.setAlpha(Math.round(255 * frontness));
            c.drawText(title, tvCx, nameY, wpName);
            wpName.setAlpha(255);

            // The row below: for the screensaver, its times and "Preview" at the right; for wallpapers, "Hide elements" centered.
            float rowTop = ROW_TOP * k, bh = BTN_H * k;
            if (wpSaver) {
                Paint label = wpHintStrong();
                String text = Ui.tr("Ativar o protetor após", "Start the screensaver after", "Activar protector tras");
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(text, left, rowTop + bh / 2 + Ui.halfCap(label), label);
                float px = left + label.measureText(text) + 30 * k;
                long now = saverTimeMs();
                for (int i = 0; i < SAVER_MINUTES.length; i++) {
                    float x = px + i * (PILL_W + PILL_GAP) * k;
                    wpTimePill(c, x, rowTop, PILL_W * k, PILL_H * k, SAVER_MINUTES[i] + " min", wpPillPop[i].value, SAVER_MINUTES[i] * 60_000L == now);
                }
                float bw = 200 * k;
                wpButton(c, right - bw / 2, rowTop, bw, bh, Ui.tr("Ver agora", "Preview", "Ver ahora"), wpPillPop[4].value);
            } else {
                float bw = 290 * k;
                String hide = wpClean.target > 0.5f ? Ui.tr("Mostrar elementos", "Show elements", "Mostrar elementos")
                        : Ui.tr("Ocultar elementos", "Hide elements", "Ocultar elementos");
                wpButton(c, tvCx, rowTop, bw, bh, hide, wpPillPop[4].value);
            }
            drawBackButton(c);
            c.drawText(wpSaver ? Ui.tr("Protetor de tela", "Screensaver", "Protector de pantalla") : Ui.tr("Papel de parede", "Wallpaper", "Fondo de pantalla"),
                    w / 2f, 78 * k, wpTitle);
            c.drawText(wpSaver ? Ui.tr("Escolha o protetor que mais gostar. Use as setas para os lados para ver os outros.",
                            "Pick the screensaver you like best. Use the left and right arrows to see the others.",
                            "Elige el protector que más te guste. Usa las flechas a los lados para ver los demás.")
                            : Ui.tr("Escolha o papel de parede que mais gostar. Use as setas para os lados para ver os outros.",
                            "Pick the wallpaper you like best. Use the left and right arrows to see the others.",
                            "Elige el fondo que más te guste. Usa las flechas a los lados para ver los demás."),
                    w / 2f, 122 * k, wpDesc);
            if (wpRow == 0) {   // the arrows at the sides of the sample: more to the left and to the right
                float ay = (wpTop() + SMP_W * 9f / 32f) * k, ax = 90 * k;
                Symbols.draw(getContext(), c, "chevron_right", w - ax, ay, 64 * k, 0xCCFFFFFF);
                c.save();
                c.rotate(180, ax, ay);
                Symbols.draw(getContext(), c, "chevron_right", ax, ay, 64 * k, 0xCCFFFFFF);
                c.restore();
            }
        }

        private int[] wpOrder;
        private final RectF wpBox = new RectF();
        private Paint wpLabel;

        private Paint wpHintStrong() {
            if (wpLabel == null || wpK != wpLabelK) {
                wpLabelK = wpK;
                wpLabel = Ui.text(Ui.medium, 26 * k, 0xE6FFFFFF);
                wpLabel.setTextAlign(Paint.Align.CENTER);
            }
            return wpLabel;
        }
        private float wpLabelK;

        /** A side picture: small, rounded, fainter the further it is from the front. */
        private void drawThumb(Canvas c, int i, RectF r, float radius, float alpha) {
            Bitmap sm = wpSmall != null && i < wpSmall.length ? wpSmall[i] : null;
            Bitmap t = wpThumb != null ? wpThumb[i] : null;
            if (wpThumbAt == null || wpThumbAt.length != wpFiles().length) wpThumbAt = new long[wpFiles().length];
            boolean have = sm != null || t != null;
            if (have && wpThumbAt[i] == 0) { wpThumbAt[i] = SystemClock.uptimeMillis(); invalidate(); }
            float ea = !have ? 0 : Ui.clamp((SystemClock.uptimeMillis() - wpThumbAt[i]) / 250f);   // the image fades in over its empty frame
            if (ea < 1) {
                wpFill.setShader(null);
                wpFill.setColor(Ui.alpha(0x33FFFFFF, alpha));
                c.drawRoundRect(r, radius, radius, wpFill);
            }
            if (sm != null) {   // clipped with the same corner radius the sample has, so nothing changes when one becomes the other
                wpClip.reset();
                wpClip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(wpClip);
                wpImg.setAlpha(Math.round(255 * alpha * ea));
                c.drawBitmap(sm, null, r, wpImg);
                wpImg.setAlpha(255);
                c.restore();
            } else if (t != null) {
                wpImg.setAlpha(Math.round(255 * alpha * ea));
                c.drawBitmap(t, null, r, wpImg);
                wpImg.setAlpha(255);
            }
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            c.drawRoundRect(r, radius, radius, wpFill);
            wpFill.setStyle(Paint.Style.FILL);
        }

        /** The small check on the picture in use, once it is off to the side (it fades in as the picture leaves the front). */
        private void drawBadge(Canvas c, int i, RectF r, float alpha) {
            if (i != wpCurrent() || alpha <= 0.01f) return;
            float bx = r.right - 16 * k, by = r.top + 16 * k;
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(LILY, alpha));
            c.drawCircle(bx, by, 11 * k, wpFill);
            Symbols.draw(getContext(), c, "check", bx, by, 15 * k, Ui.alpha(INK, alpha));
        }

        /** The picture in focus, as the TV shows it, with its elements (and the live preview over it once it settles). */
        private void drawSample(Canvas c, RectF box, float mr, float ui, int idx, boolean front, float elements) {
            float scale = box.width() / w, mx = box.left, my = box.top;
            wpClip.reset();
            wpClip.addRoundRect(box, mr, mr, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(wpClip);
            c.translate(mx, my);
            c.scale(scale, scale);
            c.drawColor(INK);
            waveDst.set(0, 0, w, h);
            long nowMs = SystemClock.uptimeMillis();
            wpZoomAt = nowMs;
            float zoom = 1, pan = 0;
            if (!wpSaver) {   // the wallpaper's sample moves like the screensaver does (the real wallpaper stays still)
                pan = DreamVideo.zoomPhase(nowMs) * elements;   // the motion fades as it leaves the front, so it matches the still small picture
                zoom = 1 + DreamVideo.ZOOM_AMOUNT * pan;
            }
            c.save();
            c.translate(-DreamVideo.PAN_X * w * pan, -DreamVideo.PAN_Y * h * pan);
            c.rotate(DreamVideo.TURN_DEGREES * pan, w / 2f, h / 2f);
            c.scale(zoom, zoom, w / 2f, h / 2f);
            // Always this item's own picture: the small one right away, the sharp one fading in over it when it is ready.
            Bitmap small = wpSmall != null && idx < wpSmall.length ? wpSmall[idx] : null;
            Bitmap full = wpFull != null && idx < wpFull.length ? wpFull[idx] : null;
            if (small != null) {
                wavePaint.setAlpha(255);
                c.drawBitmap(small, null, waveDst, wavePaint);
            }
            if (full != null) {
                float fa = small == null ? 1 : Ui.clamp((nowMs - wpFullAt[idx]) / 220f);
                wavePaint.setAlpha(Math.round(255 * fa));
                c.drawBitmap(full, null, waveDst, wavePaint);
                wavePaint.setAlpha(255);
            }
            c.restore();
            float elem = ui * elements;
            float shown = wpSaver && live != null && front ? elem * (1 - live.getAlpha()) : elem;   // the live preview draws its own elements
            if (live != null && front) live.setElementsAlpha(ui);
            if (shown > 0.004f) {
                if (wpSaver) drawSaverElements(c, shown); else drawHomeElements(c, shown);
            }
            c.restore();
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(0x40FFFFFF);
            c.drawRoundRect(box, mr, mr, wpFill);   // the same line the small pictures have
            wpFill.setStyle(Paint.Style.FILL);
            float f = Ui.clamp(wpCarPop.value) * elements;
            if (f > 0.01f) {   // the focus: the same lily → peach → melon ring the buttons have, just outside the sample
                float ring = 4.5f * k, gap = 3 * k + ring / 2;
                tmp.set(box.left - gap, box.top - gap, box.right + gap, box.bottom + gap);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, mr + gap, mr + gap, ringPaint);
                ringPaint.setShader(null);
            }
        }

        /** "In use" with a filled check (full color), or "Select" with an empty circle: text on the left, circle on the right. */
        private void drawStatus(Canvas c, float right, float cy, boolean inUse, float alpha) {
            if (alpha <= 0.01f) return;
            float r = STATUS_R * k, cx = right - r;
            if (inUse) {
                wpFill.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, Brand.accents(), null, Shader.TileMode.CLAMP));
                wpFill.setStyle(Paint.Style.FILL);
                wpFill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
                c.drawCircle(cx, cy, r, wpFill);
                wpFill.setShader(null);
                Symbols.draw(getContext(), c, "check", cx, cy, r * 1.35f, Ui.alpha(INK, alpha));
            } else {
                wpFill.setShader(null);
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2.5f * k);
                wpFill.setColor(Ui.alpha(0xE6FFFFFF, alpha));
                c.drawCircle(cx, cy, r - 1.25f * k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            wpStatus.setAlpha(Math.round(255 * alpha));
            c.drawText(inUse ? Ui.tr("Em uso", "In use", "En uso") : Ui.tr("Selecionar", "Select", "Seleccionar"),
                    cx - r - 14 * k, cy + Ui.halfCap(wpStatus), wpStatus);
        }

        /** A button under the sample: night with a faint line; focused, the lily → peach → melon ring; "in use" is filled with the gradient. */
        private void wpButton(Canvas c, float cx, float top, float bw, float bh, String text, float f) {
            final boolean filled = false;
            float s = 1 + 0.05f * f, cy = top + bh / 2, r = bh * s / 2;
            tmp2.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
            RectF box = new RectF(tmp2);
            if (f > 0.01f) {
                box.offset(0, 6 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(box, r, r, shadow);
                box.offset(0, -6 * k * f);
            }
            wpFill.setStyle(Paint.Style.FILL);
            if (filled) {
                wpFill.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                c.drawRoundRect(box, r, r, wpFill);
                wpFill.setShader(null);
            } else {
                wpFill.setShader(null);
                wpFill.setColor(blend(Brand.ink(0xE6), Brand.ink(0xFF), f));
                c.drawRoundRect(box, r, r, wpFill);
            }
            if (f < 0.99f && !filled) {
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x59FFFFFF, 1 - f));
                RectF in = new RectF(box.left + k, box.top + k, box.right - k, box.bottom - k);
                c.drawRoundRect(in, r - k, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                RectF in = new RectF(box.left + half, box.top + half, box.right - half, box.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                if (filled) {   // over the gradient fill the ring is white
                    ringPaint.setShader(null);
                    ringPaint.setColor(Ui.alpha(0xFFFFFFFF, f));
                }
                c.drawRoundRect(in, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            blinkFill(c, box, r, f);
            wpBtnText.setColor(filled ? INK : 0xFFFFFFFF);
            c.drawText(text, cx, cy + Ui.halfCap(wpBtnText), wpBtnText);
        }

        /** A time option: gray; focused, a gradient border; chosen, filled with the gradient. */
        private void wpTimePill(Canvas c, float left, float top, float pw, float ph, String text, float f, boolean chosen) {
            float s = 1 + 0.06f * f, cx = left + pw / 2, cy = top + ph / 2, r = ph * s / 2;
            RectF box = new RectF(cx - pw * s / 2, cy - ph * s / 2, cx + pw * s / 2, cy + ph * s / 2);
            wpFill.setStyle(Paint.Style.FILL);
            if (chosen) {   // always full color, focused or not
                wpFill.setColor(0xFFFFFFFF);
                wpFill.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
            } else {
                wpFill.setShader(null);
                wpFill.setColor(0x40FFFFFF);
                c.drawRoundRect(box, r, r, wpFill);   // the light base stays while the dark comes over it: the pill never vanishes
                wpFill.setColor(Ui.alpha(Brand.ink(0xFF), f));
            }
            c.drawRoundRect(box, r, r, wpFill);
            wpFill.setShader(null);
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                RectF in = new RectF(box.left + half, box.top + half, box.right - half, box.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                if (chosen) {
                    ringPaint.setShader(null);
                    ringPaint.setColor(Ui.alpha(0xFFFFFFFF, f));
                } else {
                    ringPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                    ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                }
                c.drawRoundRect(in, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            blinkFill(c, box, r, f);
            wpBtnText.setColor(chosen ? INK : 0xFFFFFFFF);
            c.drawText(text, cx, cy + Ui.halfCap(wpBtnText), wpBtnText);
        }

        /** "Back", top left: a pill; focused (←) it turns night-black with the lily → peach → melon ring. */
        private void drawBackButton(Canvas c) {
            wpPaints();
            float f = Ui.clamp(wpBackPop.value), s = 1 + 0.05f * f;
            float cx = (BACK_X + BACK_W / 2f) * k, cy = (BACK_Y + BACK_H / 2f) * k;
            float bw = BACK_W * k * s, bh = BACK_H * k * s, r = bh / 2;
            tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            if (f > 0.01f) {
                tmp.offset(0, 6 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -6 * k * f);
            }
            wpFill.setShader(null);
            wpFill.setColor(blend(Brand.ink(0xB3), Brand.ink(0xFF), f));
            c.drawRoundRect(tmp, r, r, wpFill);
            blinkFill(c, tmp, r, f);
            if (f < 0.99f) {
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x59FFFFFF, 1 - f));
                tmp2.set(tmp.left + k, tmp.top + k, tmp.right - k, tmp.bottom - k);
                c.drawRoundRect(tmp2, r - k, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                c.drawRoundRect(tmp2, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            float iconX = tmp.left + 36 * k * s;
            c.save();
            c.rotate(180, iconX, tmp.centerY());   // the chevron, pointing left
            Symbols.draw(getContext(), c, "chevron_right", iconX, tmp.centerY(), 26 * k, 0xFFFFFFFF);
            c.restore();
            c.drawText(Ui.tr("Voltar", "Back", "Volver"), iconX + 28 * k, tmp.centerY() + Ui.halfCap(wpBackText), wpBackText);
        }

        // The screensaver picker's live preview: the real screensaver, scaled into the TV (after a short pause on each item).
        private DreamVideo live;
        private float wpMiniScale = 1, wpMiniX, wpMiniY;
        private final Runnable liveStart = new Runnable() {
            @Override public void run() { startLive(); }
        };

        private void scheduleLive() {
            handler.removeCallbacks(liveStart);
            stopLive();
            if (screen == WALLPAPERS && wpSaver && running) handler.postDelayed(liveStart, 450);
        }

        private void startLive() {
            stopLive();
            if (screen != WALLPAPERS || !wpSaver || !running || w == 0 || !(getParent() instanceof android.view.ViewGroup)) return;
            if (!DreamVideo.available(getContext(), focus)) return;
            final DreamVideo d = new DreamVideo(getContext(), focus, true);
            final float radius = 24 * k / wpMiniScale;
            d.setPivotX(0);
            d.setPivotY(0);
            d.setScaleX(wpMiniScale);
            d.setScaleY(wpMiniScale);
            d.setTranslationX(wpMiniX);
            d.setTranslationY(wpMiniY);
            d.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            d.setClipToOutline(true);
            d.setAlpha(0f);
            ((android.view.ViewGroup) getParent()).addView(d, new android.widget.FrameLayout.LayoutParams(w, h));
            d.setElementsAlpha(1 - Ui.clamp(wpClean.value));
            d.start();
            d.animate().alpha(1).setDuration(400).start();
            live = d;
            invalidate();
        }

        private void stopLive() {
            DreamVideo d = live;
            if (d == null) return;
            live = null;
            d.animate().cancel();
            d.stop();
            if (d.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) d.getParent()).removeView(d);
            invalidate();
        }

        /** The home's top, as it looks over the wallpaper: date and time capsule, greeting, round buttons (full-screen coordinates). */
        private void drawHomeElements(Canvas c, float ui) {
            java.util.Date now = new java.util.Date();
            String day = new java.text.SimpleDateFormat("EEE d MMM", Ui.locale()).format(now);
            if (!day.isEmpty()) day = Character.toUpperCase(day.charAt(0)) + day.substring(1).replace(".", "");
            String capsule = day + "     " + android.text.format.DateFormat.getTimeFormat(getContext()).format(now);
            HomeData.Weather wx = HomeData.get(getContext()).weather;
            boolean sampleWx = Double.isNaN(wx.temp);   // before the weather arrives: a sample value, so the capsule is shown complete
            boolean hasWx = Prefs.weather();
            String symbol = hasWx ? (sampleWx ? "cloud" : Symbols.weather(wx.code, wx.isDay)) : null;
            String temp = hasWx ? (sampleWx ? "18°" : HomeData.degrees(wx.temp)) : "";
            float textW = wpCapsuleText.measureText(capsule), iconS = 28 * k;
            float wxW = hasWx ? 28 * k + Symbols.width(getContext(), symbol, iconS) + 10 * k + wpCapsuleText.measureText(temp) : 0;
            float cw = textW + wxW + 80 * k, ch = 58 * k, ctop = 30 * k;
            tmp.set(w / 2f - cw / 2, ctop, w / 2f + cw / 2, ctop + ch);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setShader(null);
            wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
            c.drawRoundRect(tmp, ch / 2, ch / 2, wpFill);
            wpCapsuleText.setAlpha(Math.round(255 * ui));
            wpCapsuleText.setTextAlign(Paint.Align.LEFT);
            float tx = tmp.left + 40 * k, base = tmp.centerY() + Ui.halfCap(wpCapsuleText);
            c.drawText(capsule, tx, base, wpCapsuleText);
            if (hasWx) {
                float ix = tx + textW + 28 * k, iw = Symbols.width(getContext(), symbol, iconS);
                Symbols.draw(getContext(), c, symbol, ix + iw / 2, tmp.centerY(), iconS, Ui.alpha(0xFFFFFFFF, ui));
                c.drawText(temp, ix + iw + 10 * k, base, wpCapsuleText);
            }
            wpCapsuleText.setTextAlign(Paint.Align.CENTER);
            String name = Prefs.name();
            wpGreetSmall.setAlpha(Math.round(204 * ui));
            c.drawText(name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, ", "Hi, ", "¡Hola, ") + name + "!", WP_MARGIN * k - 0, 128 * k, wpGreetSmall);
            wpGreetBig.setAlpha(Math.round(255 * ui));
            c.drawText(Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?"), WP_MARGIN * k, 190 * k, wpGreetBig);
            String[] icons = {"play", "wifi", "bluetooth", "hdmi", "settings"};
            for (int i = 0; i < icons.length; i++) {
                float cx = (WP_CLIP_R - 29 - 88 * (icons.length - 1 - i)) * k, cy = 147 * k, r = 29 * k;
                wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
                c.drawCircle(cx, cy, r, wpFill);
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x40FFFFFF, ui));
                c.drawCircle(cx, cy, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
                Symbols.draw(getContext(), c, icons[i], cx, cy, 30 * k, Ui.alpha(0xFFFFFFFF, ui));
            }
            boolean recent = Prefs.cardsMode().equals("recent");
            // The tabs (Explore / Movies / Shows) when the user has them on; with "Continue watching", its section title instead.
            if (!recent) {
                String[] tabs = {Ui.tr("Explorar", "Explore", "Explorar"), Ui.tr("Filmes", "Movies", "Películas"), Ui.tr("Séries", "Shows", "Series")};
                float tabX = WP_MARGIN * k, tTop = 216 * k, tH = 54 * k;
                for (int i = 0; i < tabs.length; i++) {
                    Paint tp = i == 0 ? wpTabOn : wpTabOff;
                    float tw = Math.max(wpTabOn.measureText(tabs[i]), wpTabOff.measureText(tabs[i])) + 40 * k;
                    tmp.set(tabX, tTop, tabX + tw, tTop + tH);
                    wpFill.setStyle(Paint.Style.FILL);
                    if (i == 0) {   // the selected one: the lily → peach → melon gradient
                        wpFill.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                        wpFill.setColor(Ui.alpha(0xFFFFFFFF, ui));
                    } else {
                        wpFill.setShader(null);
                        wpFill.setColor(Ui.alpha(Brand.ink(0xFF), 0.7f * ui));
                    }
                    c.drawRoundRect(tmp, tH / 2, tH / 2, wpFill);
                    wpFill.setShader(null);
                    tp.setAlpha(Math.round(255 * ui));
                    c.drawText(tabs[i], tmp.centerX(), tmp.centerY() + Ui.halfCap(tp), tp);
                    tabX += tw + 14 * k;
                }
            } else {
                wpSection.setAlpha(Math.round(255 * ui));
                c.drawText(Ui.tr("Continuar reproduzindo", "Continue playing", "Continuar reproduciendo"), WP_MARGIN * k,
                        (216 + 27) * k + Ui.halfCap(wpSection), wpSection);
            }
            // The three cards on the real grid: one wide (it spans what the other two leave), two compact.
            float wide = (WP_CLIP_R - WP_MARGIN) - 2 * 293 - 2 * 54;
            float[] cardWs = {wide, 293, 293};
            int[] tint = {LILY, PEACH, MELON};
            String[] chips = {"Netflix", "YouTube", "Prime Video"};
            float[] done = {0.62f, 0.3f, 0.8f};
            float cx0 = WP_MARGIN * k;
            for (int i = 0; i < 3; i++) {
                float cwid = cardWs[i] * k, top = 302 * k, bot = (302 + 409) * k;
                tmp.set(cx0, top, cx0 + cwid, bot);
                wpFill.setStyle(Paint.Style.FILL);
                wpFill.setShader(new LinearGradient(0, top, 0, bot, Ui.alpha(tint[i], 0.55f * ui), Ui.alpha(Brand.ink(0xE6), ui), Shader.TileMode.CLAMP));
                c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                wpFill.setShader(null);
                if (recent) {   // "Continue watching": a dark fade at the bottom, and the progress line (what was watched in color)
                    wpFill.setShader(new LinearGradient(0, bot - 409 * k * 0.5f, 0, bot, 0x00000000, Ui.alpha(0xCC000000, ui), Shader.TileMode.CLAMP));
                    c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                    wpFill.setShader(null);
                    float inset = 22 * k, bh = 9 * k, btnH = 50 * k, left = cx0 + inset;
                    float right = cx0 + cwid - inset - (i == 0 ? (22 + 26 + 10 + 190 + 24 + 36) * k : 0);
                    float y = bot - inset - btnH / 2 - bh / 2;
                    wpFill.setColor(Ui.alpha(0xE6FFFFFF, ui));
                    c.drawRoundRect(left, y, right, y + bh, bh / 2, bh / 2, wpFill);
                    wpFill.setShader(new LinearGradient(left, 0, right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                    wpFill.setColor(Ui.alpha(0xFFFFFFFF, ui));
                    c.drawRoundRect(left, y, left + Math.max(bh, (right - left) * done[i]), y + bh, bh / 2, bh / 2, wpFill);
                    wpFill.setShader(null);
                    wpChip.setAlpha(Math.round(255 * ui));
                    c.drawText(Ui.tr("32 min restantes", "32 min left", "32 min restantes"), left, y - 12 * k, wpChip);
                }
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x40FFFFFF, ui));
                c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
                // The platform tag at the top left: a small dark pill.
                String chip = chips[i];
                float cwd = wpChip.measureText(chip) + 28 * k;
                tmp.set(cx0 + 16 * k, top + 16 * k, cx0 + 16 * k + cwd, top + 16 * k + 34 * k);
                wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
                c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                wpChip.setAlpha(Math.round(255 * ui));
                c.drawText(chip, tmp.left + 14 * k, tmp.centerY() + Ui.halfCap(wpChip), wpChip);
                if (i == 0) {   // the open card always shows its button
                    String label = recent ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo") : Ui.tr("Assistir", "Watch now", "Ver ahora");
                    float bw = 22 * k + 26 * k + 10 * k + wpChip.measureText(label) * 1.2f + 24 * k;
                    tmp.set(cx0 + cwid - 22 * k - bw, bot - 22 * k - 50 * k, cx0 + cwid - 22 * k, bot - 22 * k);
                    wpFill.setColor(Ui.alpha(0xF2FFFFFF, ui));
                    c.drawRoundRect(tmp, 25 * k, 25 * k, wpFill);
                    Symbols.draw(getContext(), c, "play", tmp.left + 22 * k + 13 * k, tmp.centerY(), 26 * k, Ui.alpha(INK, ui));
                    wpBtnText.setTextSize(22 * k);
                    wpBtnText.setTextAlign(Paint.Align.LEFT);
                    wpBtnText.setColor(Ui.alpha(INK, ui));
                    c.drawText(label, tmp.left + 22 * k + 26 * k + 10 * k, tmp.centerY() + Ui.halfCap(wpBtnText), wpBtnText);
                    wpBtnText.setTextSize(25 * k);
                    wpBtnText.setTextAlign(Paint.Align.CENTER);
                }
                // Title and detail under the card.
                float belowW = cwid;
                wpCardTitle.setAlpha(Math.round(255 * ui));
                c.drawText(TextUtils.ellipsize(Ui.tr("Título de exemplo", "Sample title", "Título de ejemplo"), new TextPaint(wpCardTitle), belowW, TextUtils.TruncateAt.END).toString(),
                        cx0, bot + 40 * k, wpCardTitle);
                String meta = i == 0 ? Ui.tr("Drama · 2 h 10 min · Netflix", "Drama · 2 h 10 min · Netflix", "Drama · 2 h 10 min · Netflix") : "Drama · 2 h";
                wpCardMeta.setAlpha(Math.round(179 * ui));
                c.drawText(TextUtils.ellipsize(meta, new TextPaint(wpCardMeta), belowW, TextUtils.TruncateAt.END).toString(), cx0, bot + 70 * k, wpCardMeta);
                cx0 += cwid + 54 * k;
            }
            // The bottom panel, as the home has it: dark glass across the whole width with the white bar at its center,
            // the pinned apps in its first row and the next row peeking under the edge of the screen.
            float panelTop = 823 * k, rad = 58 * k;
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(0x73000000, ui));
            tmp.set(0, panelTop, w, h + rad);
            c.drawRoundRect(tmp, rad, rad, wpFill);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(Ui.alpha(0x26FFFFFF, ui));
            c.drawRoundRect(tmp, rad, rad, wpFill);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(0xE6FFFFFF, ui));
            tmp.set(w / 2f - 62 * k, 852 * k, w / 2f + 62 * k, 862 * k);
            c.drawRoundRect(tmp, 5 * k, 5 * k, wpFill);
            if (wpTiles != null) {
                float colGap = ((WP_CLIP_R - WP_MARGIN) - 7 * 196) / 6f;
                for (int i = 0; i < wpTiles.length; i++) {
                    int row = i < wpPinned ? 0 : 1, col = i < wpPinned ? i : i - wpPinned;
                    float x = (WP_MARGIN + col * (196 + colGap)) * k, y = (891 + row * 154) * k;
                    tmp.set(x, y, x + 196 * k, y + 112 * k);
                    Bitmap t = wpTiles[i];
                    if (t != null) {
                        wpImg.setAlpha(Math.round(255 * ui));
                        c.drawBitmap(t, null, tmp, wpImg);
                        wpImg.setAlpha(255);
                        wpFill.setColor(Ui.alpha(0xFF000000, 0.08f * ui));
                        c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                    } else {
                        wpFill.setColor(Ui.alpha(0x33FFFFFF, ui));
                        c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                    }
                }
            }
        }

        /** The screensaver's own layout over the picture: clock, date and weather in the lower left, a sample of the info box in the lower right. */
        private void drawSaverElements(Canvas c, float ui) {
            if (svClock == null || wpK != svK) {
                svK = k;
                svClock = Ui.numbers(Ui.thin, 250 * k, 0xF2FFFFFF);
                svClock.setLetterSpacing(-0.01f);
                svDate = Ui.numbers(Ui.semibold, 34 * k, 0xE6FFFFFF);
                svWeather = Ui.numbers(Ui.medium, 34 * k, 0xE6FFFFFF);
                svLabel = Ui.text(Ui.semibold, 17 * k, 0xB3FFFFFF);
                svLabel.setLetterSpacing(0.14f);
                svTitle = Ui.text(Ui.semibold, 29 * k, 0xF2FFFFFF);
                svSub = Ui.text(Ui.medium, 21 * k, 0xCCFFFFFF);
                svBar = new Paint(Paint.ANTI_ALIAS_FLAG);
                svTime = Ui.numbers(Ui.medium, 20 * k, 0xE6FFFFFF);
                svTime.setTextAlign(Paint.Align.RIGHT);
            }
            // Darkening at the bottom, as the real screensaver has.
            svBar.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0, Ui.alpha(0xA6000000, ui), Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.5f, w, h, svBar);
            svBar.setShader(null);
            long now = System.currentTimeMillis();
            float x = 110 * k;
            String date = Ui.date(now);
            HomeData.Weather wx = HomeData.get(getContext()).weather;
            boolean sampleWx = Double.isNaN(wx.temp) && Prefs.weather();
            boolean hasWx = (!Double.isNaN(wx.temp) || sampleWx) && Prefs.weather();
            String symbol = hasWx ? (sampleWx ? "cloud" : Symbols.weather(wx.code, wx.isDay)) : null;
            float size = 34 * k, sw = hasWx ? Symbols.width(getContext(), symbol, size) : 0;
            String temp = hasWx ? (sampleWx ? "18°" : HomeData.degrees(wx.temp)) : "";
            float lineW = svDate.measureText(date) + (hasWx ? 36 * k + sw + 12 * k + svWeather.measureText(temp) : 0);
            String time = Ui.clock(now);
            svClock.setTextSize(250 * k);
            float natural = svClock.measureText(time);
            svClock.setTextSize(Math.max(110 * k, Math.min(250 * k, 250 * k * lineW / Math.max(1, natural))));
            svClock.setAlpha(Math.round(242 * ui));
            svDate.setAlpha(Math.round(230 * ui));
            svWeather.setAlpha(Math.round(230 * ui));
            float clockBase = h - 120 * k;
            c.drawText(time, x - 6 * k, clockBase, svClock);
            float line = clockBase - Ui.cap(svClock) - 40 * k;
            c.drawText(date, x, line, svDate);
            if (hasWx) {
                float wx0 = x + svDate.measureText(date) + 36 * k;
                Symbols.draw(getContext(), c, symbol, wx0 + sw / 2, line - Ui.halfCap(svWeather), size, Ui.alpha(0xE6FFFFFF, ui));
                c.drawText(temp, wx0 + sw + 12 * k, line, svWeather);
            }
            // A sample of the box that shows what is playing (cover, label, title, progress).
            float pad = 26 * k, cover = 100 * k, gap = 26 * k, textW = 430 * k, inner = cover + gap + textW;
            float boxW = pad + inner + pad, boxH = 2 * pad + cover + 44 * k;
            float right = w - 110 * k, bottom = h - 120 * k + 18 * k, left = right - boxW, top = bottom - boxH;
            svBar.setStyle(Paint.Style.FILL);
            svBar.setColor(Ui.alpha(Brand.ink(0xCC), ui));
            tmp.set(left, top, right, bottom);
            c.drawRoundRect(tmp, 30 * k, 30 * k, svBar);
            float cx = left + pad, cy = top + pad;
            tmp.set(cx, cy, cx + cover, cy + cover);
            svBar.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            svBar.setAlpha(Math.round(255 * ui));
            c.drawRoundRect(tmp, 18 * k, 18 * k, svBar);
            svBar.setShader(null);
            float tx = cx + cover + gap;
            svLabel.setAlpha(Math.round(179 * ui));
            svTitle.setAlpha(Math.round(242 * ui));
            svSub.setAlpha(Math.round(204 * ui));
            c.drawText(Ui.tr("Tocando agora", "Now playing", "Reproduciendo ahora").toUpperCase(Ui.locale()), tx, cy + 24 * k, svLabel);
            c.drawText(Ui.tr("Título da música", "Song title", "Título de la canción"), tx, cy + 62 * k, svTitle);
            c.drawText(Ui.tr("Artista", "Artist", "Artista"), tx, cy + 94 * k, svSub);
            float by = cy + cover + 34 * k, bh = 10 * k;
            svTime.setAlpha(Math.round(230 * ui));
            c.drawText("-2:41", cx + inner, by - 10 * k, svTime);   // the time left, as the real box shows it
            svBar.setColor(Ui.alpha(0xE6FFFFFF, ui));
            tmp.set(cx, by, cx + inner, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, svBar);
            tmp.set(cx, by, cx + inner * 0.35f, by + bh);
            svBar.setShader(new LinearGradient(cx, 0, cx + inner, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            svBar.setAlpha(Math.round(255 * ui));
            c.drawRoundRect(tmp, bh / 2, bh / 2, svBar);
            svBar.setShader(null);
        }
        private float svK;

        private static final int[] SAVER_MINUTES = {5, 10, 15, 30};
        private long saverWant, saverWantAt, saverReadAt;
        private long saverRead = 600_000;

        /** Minutes (as milliseconds) the TV waits before the screensaver: what the TV has, or what was just asked for. */
        private long saverTimeMs() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - saverReadAt > 1_000) {
                saverReadAt = now;
                try { saverRead = Settings.System.getLong(getContext().getContentResolver(), "screen_off_timeout", saverRead); } catch (RuntimeException ignored) {}
            }
            if (saverWant > 0 && (saverRead == saverWant || now - saverWantAt > 12_000)) saverWant = 0;
            return saverWant > 0 ? saverWant : saverRead;
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            pickLabels.addAll(appLabels);
            pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String title = keyRawLabel(pickFor - 1);
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (listRowH() + listRowGap())) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, 0xFFFFFFFF, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (listRowH() + listRowGap()) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            final int[] ids = keys ? null : groupIds();
            String[] labels = keys ? keyLabels() : new String[ids.length];
            String[] values = keys ? keyValues() : new String[ids.length];
            if (!keys) for (int i = 0; i < ids.length; i++) { labels[i] = groupLabel(ids[i]); values[i] = groupValue(ids[i]); }
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : groupInfo()[group - 1][0],
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (listRowH() + listRowGap())) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                boolean locked = !keys && ids[i] >= COIN_ROWS && ids[i] < 100 && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x99FFFFFF : 0xE6FFFFFF, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0xB3FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                boolean nav = !keys && opensScreen(ids[i]);
                c.drawText(values[i], rowRect.right - (nav ? 66 : 30) * k, base, rowValue);
                if (nav) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0xCCFFFFFF, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (listRowH() + listRowGap()) + 36) * k;
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        /** "Updates": with a new version waiting, offers to install it; otherwise asks GitHub now and says what it found. */
        private void checkUpdateNow() {
            final Context c = getContext();
            if (!Update.newer(c).isEmpty()) {
                offerInstall();
                return;
            }
            Update.check(c, true, status -> {
                if (status == Update.FOUND) {
                    offerInstall();
                    invalidate();
                    return;
                }
                String text = status == Update.FAILED
                        ? Ui.tr("Não foi possível verificar agora. Veja se a TV tem internet e tente de novo.",
                                "Could not check right now. Make sure the TV is online and try again.",
                                "No se pudo comprobar ahora. Revisa que la TV tenga internet e inténtalo de nuevo.")
                        : Ui.tr("Você está em dia: versão ", "You are up to date: version ", "Estás al día: versión ") + Update.installed(c) + ".";
                tellUpdate(text);
                invalidate();
            });
        }

        private void tellUpdate(String text) {
            new android.app.AlertDialog.Builder(getContext()).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones")).setMessage(text)
                    .setPositiveButton("OK", null).show();
        }

        private void offerInstall() {
            final Context c = getContext();
            new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                    .setMessage(Ui.tr("Nova versão disponível: " + Update.newer(c) + ". Instalar agora? São uns 7 MB; o Android vai pedir uma última confirmação.",
                            "New version available: " + Update.newer(c) + ". Install it now? It is about 7 MB; Android will ask one last confirmation.",
                            "Nueva versión disponible: " + Update.newer(c) + ". ¿Instalarla ahora? Son unos 7 MB; Android te pedirá una última confirmación."))
                    .setPositiveButton(Ui.tr("Instalar", "Install", "Instalar"), (d, w) -> startInstall())
                    .setNegativeButton(Ui.tr("Agora não", "Not now", "Ahora no"), null).show();
        }

        private void startInstall() {
            final Context c = getContext();
            if (!Update.canInstall(c)) {   // once: Android's own switch for "install apps from this source"
                new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                        .setMessage(Ui.tr("O Android precisa da sua permissão, uma só vez, para o Borealis instalar suas atualizações. Na próxima tela ative \"Permitir desta fonte\" e volte. Depois escolha Atualizações de novo.",
                                "Android needs your permission, just once, for Borealis to install its updates. On the next screen turn on \"Allow from this source\" and come back. Then choose Updates again.",
                                "Android necesita tu permiso, una sola vez, para que Borealis instale sus actualizaciones. En la siguiente pantalla activa \"Permitir de esta fuente\" y vuelve. Luego elige Actualizaciones otra vez."))
                        .setPositiveButton("OK", (d, w) -> Update.askPermission(c)).show();
                return;
            }
            if (Update.lowSpace(c)) {   // say it before downloading anything
                tellUpdate(Update.lowSpaceMessage(c));
                return;
            }
            final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                    .setMessage(Ui.tr("Baixando a atualização…", "Downloading the update…", "Descargando la actualización…")).setCancelable(false).show();
            Update.install(c, (ok, error) -> {
                wait.dismiss();
                if (!ok && Update.LOW_SPACE.equals(error)) {
                    tellUpdate(Update.lowSpaceMessage(c));
                } else if (!ok) {
                    tellUpdate(Ui.tr("Não foi possível instalar agora (" + error + "). Tente de novo mais tarde.",
                            "Could not install right now (" + error + "). Try again later.",
                            "No se pudo instalar ahora (" + error + "). Inténtalo de nuevo más tarde."));
                }
            });
        }

        private void activate() {
            Context c = getContext();
            blink();
            click().open();
            if (focus == -2 && screen != WALLPAPERS) {   // the Back button
                goBack();
                return;
            }
            if (screen == HIDDEN_APPS) {   // the app in focus comes back
                hiddenRestore();
                return;
            }
            if (screen == AVATARS) {   // the avatar picked: back to the profile
                if (focus >= 0 && focus < Avatars.IDS.length) Prefs.setAvatar(Avatars.IDS[focus]);
                screen = MAIN;
                focus = -1;
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == MAIN && focus == -1) {   // the profile: the list of avatars
                openAvatars();
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {   // soft Home counts as not-yet-full: choosing Borealis tries ADB again
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                group = 5;
                screen = GROUP;
                focus = 0;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = keyPos(pickFor - 1);
                screen = REMOTE_KEYS;
            } else if (screen == WALLPAPERS) {
                if (wpOnBack) {
                    closeWallpapers();
                    invalidate();
                    spoken.focusMoved();
                    return;
                }
                if (wpRow == 1) {
                    if (!wpSaver) {   // "Hide elements": the sample shows only the picture; again, with everything
                        wpClean.target = wpClean.target > 0.5f ? 0 : 1;
                    } else if (wpPill == 4) {   // "Preview": the real screensaver, full screen, for the one in front
                        pause();
                        activity.showPreview();
                    } else {   // a screensaver time
                        HomeMode.requestSaverTime(c, SAVER_MINUTES[wpPill]);
                        saverWant = SAVER_MINUTES[wpPill] * 60_000L;
                        saverWantAt = SystemClock.uptimeMillis();
                    }
                    invalidate();
                    spoken.focusMoved();
                    return;
                }
                if (focus == wpCurrent()) return;   // already the one in use
                if (wpSaver) {
                    Prefs.setScreensaver(focus);
                    wpSaverCurrent = focus;
                } else {
                    Prefs.setWallpaper(focus);
                }
                HomeData.get(c).settingsChanged();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(keyIdx(focus) + 1);
            } else if (screen == GROUP) {
                int id = groupIds()[focus];
                if (id == ID_SAVER) {
                    openWallpapers(true, true);   // the screensaver picker (its "Preview" button plays it)
                } else if (id == ID_SHELF) {
                    openShelfEditor();
                } else if (id == ID_HIDDEN) {
                    openHiddenApps(false);
                } else if (id == ID_LAUNCHER) {
                    if (busy()) return;
                    screen = HOME_CHOICE;
                    focus = homeIsBorealis() ? 0 : 1;
                } else if (id == ID_WELCOME) {
                    OnboardingView.repeat(c);
                    activity.goHomeSoftly();
                } else if (id == ID_UPDATE) {
                    checkUpdateNow();
                } else if (id == ID_REBOOT) {
                    confirmReboot();
                } else if (id == ID_CUSTOM_WP) {
                    activity.pickCustomWallpaper();
                } else if (id == ID_TV) {
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (id == WALLPAPER_ROW) {
                    openWallpapers(false, false);
                } else {
                    adjustSetting(id, 1);
                    invalidate();
                    return;
                }
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (groupOrder()[focus] == 4) {   // Remote control: the buttons work from inside other apps through this service
                HomeMode.ensureRemoteService(c, 0);
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else {   // one of the other groups
                group = groupOrder()[focus];
                screen = GROUP;
                focus = 0;
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
