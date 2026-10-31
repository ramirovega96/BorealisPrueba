package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.io.InputStream;

/**
 * The home screen's sample (the same one the wallpaper picker shows: the user's wallpaper, the capsule, the greeting,
 * the corner buttons, three empty cards and the bottom panel with the shelf) with a looping story on the shelf: hold OK
 * on an app (a ring fills), a menu opens (Move, Remove from shelf, Hide), and the app slides one place over with its
 * arrows. Drawn in a 1920 x 1080 design space, scaled to the 16:9 box it is given. It always animates (the caller keeps
 * invalidating while it is on screen). Nothing here uses layers or alpha groups, so the picture never flickers.
 */
final class ShelfDemo {
    private static final long LOOP = 7600;
    private static final int[] COLORS = {0xFFE50914, 0xFF6C3BE0, 0xFF0064FF, 0xFF1A98FF, 0xFF1DB954, 0xFFFF0033, 0xFF1838C8};
    private static final float MARGIN = 142, CLIP_R = 1778, TILE_W = 196, TILE_H = 112, TILE_Y = 891;
    private static final float COL_GAP = ((CLIP_R - MARGIN) - 7 * TILE_W) / 6f, STEP = TILE_W + COL_GAP;

    private static Bitmap wall;
    private static int wallIndex = -1;
    private static long wallStamp;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), m = new RectF(), screen = new RectF();
    private final Path tri = new Path(), clip = new Path();
    private Paint capsule, greetSmall, greetBig, section, menuText, ok;

    private static float clamp(float x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    private static float span(long t, long from, long to) { return clamp((t - from) / (float) (to - from)); }

    private static float ease(float x) { return x * x * (3 - 2 * x); }

    /** The wallpaper in use (the same as the welcome's backdrop), loaded once and small. */
    private static void loadWall(Context c) {
        int idx = Prefs.wallpaper();
        long stamp = Prefs.wallpaperStamp();
        if (idx == wallIndex && stamp == wallStamp) return;
        wallIndex = idx;
        wallStamp = stamp;
        try (InputStream in = Prefs.openWallpaper(c, Prefs.wallpaperFiles()[idx])) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            wall = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception | OutOfMemoryError e) {
            wall = null;
        }
    }

    private void pill(Canvas c, RectF box, int color) {
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill);
    }

    void draw(Canvas c, Context ctx, RectF area) {
        if (capsule == null) {
            capsule = Ui.text(Ui.medium, 26, 0xFFFFFFFF);
            greetSmall = Ui.text(Ui.regular, 26, 0xCCFFFFFF);
            greetBig = Ui.text(Ui.displaySemibold, 52, 0xFFFFFFFF);
            section = Ui.text(Ui.semibold, 28, 0xFFFFFFFF);
            menuText = Ui.text(Ui.medium, 32, 0xFFFFFFFF);
            ok = Ui.text(Ui.semibold, 30, 0xFFFFFFFF);
            ok.setTextAlign(Paint.Align.CENTER);
        }
        loadWall(ctx);
        float s = area.width() / 1920f;
        long t = SystemClock.uptimeMillis() % LOOP;
        float slide = ease(span(t, 4700, 5500)) - ease(span(t, 6700, 7500));
        float ringA = ease(span(t, 0, 500));
        float holdP = span(t, 600, 2400);
        float okA = ringA * (1 - ease(span(t, 2300, 2600)));
        float menuA = ease(span(t, 2500, 2900)) * (1 - ease(span(t, 4400, 4700)));
        float arrowA = ease(span(t, 4600, 5000)) * (1 - ease(span(t, 6300, 6700)));

        c.save();
        c.translate(area.left, area.top);
        c.scale(s, s);
        screen.set(0, 0, 1920, 1080);
        clip.reset();
        clip.addRoundRect(screen, 56, 56, Path.Direction.CW);
        c.clipPath(clip);

        // The wallpaper in use (or the dark base while it loads).
        c.drawColor(Brand.INK);
        if (wall != null) c.drawBitmap(wall, null, screen, image);

        // The capsule: date, time and weather.
        java.util.Date now = new java.util.Date();
        String day = new java.text.SimpleDateFormat("EEE d MMM", Ui.locale()).format(now);
        if (!day.isEmpty()) day = Character.toUpperCase(day.charAt(0)) + day.substring(1).replace(".", "");
        String cap = day + "     " + android.text.format.DateFormat.getTimeFormat(ctx).format(now);
        boolean hasWx = Prefs.weather();
        capsule.setTextAlign(Paint.Align.LEFT);
        float textW = capsule.measureText(cap), iconW = hasWx ? Symbols.width(ctx, "cloud", 28) : 0;
        float wxW = hasWx ? 28 + iconW + 10 + capsule.measureText("18°") : 0;
        float cw = textW + wxW + 80, ch = 58, ctop = 30;
        r.set(960 - cw / 2, ctop, 960 + cw / 2, ctop + ch);
        pill(c, r, Brand.ink(0xB3));
        float tx = r.left + 40, base = r.centerY() + Ui.halfCap(capsule);
        c.drawText(cap, tx, base, capsule);
        if (hasWx) {
            float ix = tx + textW + 28;
            Symbols.draw(ctx, c, "cloud", ix + iconW / 2, r.centerY(), 28, 0xFFFFFFFF);
            c.drawText("18°", ix + iconW + 10, base, capsule);
        }

        // The greeting and the corner buttons.
        String name = Prefs.name();
        c.drawText(name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, ", "Hi, ", "¡Hola, ") + name + "!", MARGIN, 128, greetSmall);
        c.drawText(Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?"), MARGIN, 190, greetBig);
        String[] icons = {"play", "wifi", "bluetooth", "hdmi", "settings"};
        for (int i = 0; i < icons.length; i++) {
            float cx = CLIP_R - 29 - 88 * (icons.length - 1 - i), cy = 147;
            fill.setShader(null);
            fill.setColor(Brand.ink(0xB3));
            c.drawCircle(cx, cy, 29, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            line.setColor(0x40FFFFFF);
            c.drawCircle(cx, cy, 28, line);
            Symbols.draw(ctx, c, icons[i], cx, cy, 30, 0xFFFFFFFF);
        }
        c.drawText(Ui.tr("Continuar reproduzindo", "Continue playing", "Continuar reproduciendo"), MARGIN, 243 + Ui.halfCap(section), section);

        // The three cards, empty (the real grid: one wide, two compact).
        float wide = (CLIP_R - MARGIN) - 2 * 293 - 2 * 54;
        float[] cardWs = {wide, 293, 293};
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
        float x0 = MARGIN;
        for (int i = 0; i < 3; i++) {
            r.set(x0, 302, x0 + cardWs[i], 711);
            fill.setShader(new LinearGradient(0, 302, 0, 711, Ui.alpha(tint[i], 0.55f), Brand.ink(0xE6), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 18, 18, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            line.setColor(0x40FFFFFF);
            c.drawRoundRect(r, 18, 18, line);
            x0 += cardWs[i] + 54;
        }
        line.setStyle(Paint.Style.FILL);

        // The bottom panel: dark glass across the width, the white bar, the shelf row and the next row peeking.
        float panelTop = 823, rad = 58;
        fill.setShader(null);
        fill.setColor(0x73000000);
        r.set(0, panelTop, 1920, 1080 + rad);
        c.drawRoundRect(r, rad, rad, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2);
        line.setColor(0x26FFFFFF);
        c.drawRoundRect(r, rad, rad, line);
        line.setStyle(Paint.Style.FILL);
        fill.setColor(0xE6FFFFFF);
        r.set(960 - 62, 852, 960 + 62, 862);
        c.drawRoundRect(r, 5, 5, fill);
        fill.setColor(0x26FFFFFF);
        for (int i = 0; i < 7; i++) {
            float x = MARGIN + i * STEP;
            r.set(x, TILE_Y + 154, x + TILE_W, TILE_Y + 154 + TILE_H);
            c.drawRoundRect(r, 17, 17, fill);
        }
        for (int i = 0; i < 7; i++) {
            float x = MARGIN + i * STEP;
            if (i == 1) x += STEP * slide;
            else if (i == 2) x -= STEP * slide;
            r.set(x, TILE_Y, x + TILE_W, TILE_Y + TILE_H);
            fill.setColor(COLORS[i]);
            c.drawRoundRect(r, 17, 17, fill);
            m.set(r.centerX() - 40, r.centerY() - 6, r.centerX() + 40, r.centerY() + 6);
            pill(c, m, 0xD9FFFFFF);
        }

        // The focus: the lily -> peach -> melon ring, on the second app (it travels with it).
        float fx = MARGIN + STEP + STEP * slide, ring = 4.5f, gap = 3 + ring / 2;
        r.set(fx - gap, TILE_Y - gap, fx + TILE_W + gap, TILE_Y + TILE_H + gap);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(ring);
        line.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
        line.setAlpha(Math.round(255 * ringA));
        c.drawRoundRect(r, 17 + gap, 17 + gap, line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);

        // The arrows beside the app while it moves.
        if (arrowA > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, arrowA));
            float cy = TILE_Y + TILE_H / 2f;
            tri.reset();
            tri.moveTo(fx - 22, cy - 22);
            tri.lineTo(fx - 22, cy + 22);
            tri.lineTo(fx - 54, cy);
            tri.close();
            c.drawPath(tri, fill);
            tri.reset();
            tri.moveTo(fx + TILE_W + 22, cy - 22);
            tri.lineTo(fx + TILE_W + 22, cy + 22);
            tri.lineTo(fx + TILE_W + 54, cy);
            tri.close();
            c.drawPath(tri, fill);
        }

        // "Hold OK": a ring that fills above the focused app.
        if (okA > 0.01f) {
            float cx = MARGIN + STEP + TILE_W / 2f, cy = 770;
            fill.setColor(Ui.alpha(0xFF000000, 0.45f * okA));
            c.drawCircle(cx, cy, 52, fill);
            r.set(cx - 52, cy - 52, cx + 52, cy + 52);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(9);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Ui.alpha(0xFFFFFFFF, okA));
            c.drawArc(r, -90, 360 * holdP, false, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setStyle(Paint.Style.FILL);
            ok.setAlpha(Math.round(255 * okA));
            c.drawText("OK", cx, cy + 10, ok);
            ok.setAlpha(255);
        }

        // The menu that opens above the app (alpha applied color by color: no layers).
        if (menuA > 0.01f) {
            float mx = MARGIN + STEP - 10, mw = 540, rowH = 84, mh = 3 * rowH + 24, my = TILE_Y - 30 - mh + (1 - menuA) * 20;
            m.set(mx, my, mx + mw, my + mh);
            fill.setColor(Ui.alpha(0xFF232027, 0.95f * menuA));
            c.drawRoundRect(m, 34, 34, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            line.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * menuA));
            c.drawRoundRect(m, 34, 34, line);
            line.setStyle(Paint.Style.FILL);
            String[] names = {Ui.tr("Mover", "Move", "Mover"), Ui.tr("Remover da estante", "Remove from shelf", "Quitar del estante"), Ui.tr("Ocultar", "Hide", "Ocultar")};
            String[] sym = {"swap_horiz", "close", "visibility_off"};
            menuText.setAlpha(Math.round(255 * menuA));
            for (int i = 0; i < 3; i++) {
                float ry = my + 12 + i * rowH;
                if (i == 0) {
                    m.set(mx + 12, ry, mx + mw - 12, ry + rowH - 4);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * menuA));
                    c.drawRoundRect(m, 26, 26, fill);
                }
                Symbols.draw(ctx, c, sym[i], mx + 52, ry + rowH / 2f - 2, 38, Ui.alpha(0xFFFFFFFF, menuA));
                c.drawText(names[i], mx + 100, ry + rowH / 2f - 2 + Ui.halfCap(menuText), menuText);
            }
            menuText.setAlpha(255);
        }

        // The edge of the sample: the same fine line the wallpaper pictures have.
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(4);
        line.setColor(0x40FFFFFF);
        c.drawRoundRect(screen, 56, 56, line);
        line.setStyle(Paint.Style.FILL);
        c.restore();
    }
}
