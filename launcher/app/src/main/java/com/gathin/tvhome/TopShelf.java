package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top shelf pictures, as on tvOS: a theme can ship shelf/<package>.jpg (or .png) next to config.json, and
 * while that app's dock tile is focused its picture fills the screen behind the hero name, faded into the
 * background towards the dock by a scrim in the layout. Pictures are decoded on a background thread at
 * about half the screen size in RGB_565 (roughly 1 MB each) and kept only for the dock apps.
 */
final class TopShelf {
    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"};

    /** The shelf frame (picture plus its scrim) fades as one; the picture changes inside it. */
    private final View view;
    private final ImageView image;
    private final File dir;
    private final int targetW, targetH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    /** Package whose picture is on screen or fading in; the one the focused tile asks for. */
    private String shown, wanted;

    /** dir is the theme's shelf folder (Config.shelfDir); null turns every call into a no-op. */
    TopShelf(View frame, ImageView image, File dir, int screenW, int screenH) {
        this.view = frame;
        this.image = image;
        this.dir = dir;
        this.targetW = Math.max(1, screenW / 2);
        this.targetH = Math.max(1, screenH / 2);
    }

    /** Decodes the dock apps' pictures ahead of time so moving along the dock never waits on the disk. */
    void preload(List<String> packages) {
        if (dir == null) return;
        for (String p : packages) load(p);
    }

    /** Shows the picture for this app, or fades the shelf out when the app has none. */
    void show(String pkg) {
        if (dir == null || pkg == null) return;
        wanted = pkg;
        Bitmap b = cache.get(pkg);
        if (b != null) {
            swapTo(pkg, b);
        } else if (missing.contains(pkg)) {
            fadeOut();
        } else {
            load(pkg);   // shows it when the decode lands, if focus is still there
        }
    }

    void hide() {
        wanted = null;
        fadeOut();
    }

    private void fadeOut() {
        if (shown == null) return;
        shown = null;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(300).start();
    }

    private void swapTo(String pkg, Bitmap b) {
        if (pkg.equals(shown)) return;
        boolean wasShowing = shown != null;
        shown = pkg;
        view.animate().cancel();
        if (!wasShowing) {
            image.setImageBitmap(b);
            view.animate().alpha(1f).setDuration(500).start();
            return;
        }
        view.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!pkg.equals(shown)) return;
            image.setImageBitmap(b);
            view.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    private void load(String pkg) {
        if (cache.containsKey(pkg) || missing.contains(pkg) || !loading.add(pkg)) return;
        new Thread(() -> {
            Bitmap b = decode(pictureFor(pkg));
            main.post(() -> {
                loading.remove(pkg);
                if (b != null) cache.put(pkg, b);
                else missing.add(pkg);
                if (pkg.equals(wanted)) show(pkg);
            });
        }, "top-shelf").start();
    }

    private File pictureFor(String pkg) {
        for (String ext : EXTENSIONS) {
            File f = new File(dir, pkg + ext);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Downsampled to at least half the screen; a broken or huge file means "no picture", never a crash. */
    private Bitmap decode(File file) {
        if (file == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW && o.outHeight / (sample * 2) >= targetH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
