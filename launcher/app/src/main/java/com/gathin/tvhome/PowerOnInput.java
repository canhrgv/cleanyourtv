package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the first input with a picture when the TV is switched on, in config power_on_inputs order (Mi TV 3:
 * HDMI 1, 2, 3). A port can be connected without a picture (the device on it is off), and the TV only
 * measures the signal of the input on screen, so each connected input is opened in turn until one reports a
 * stable signal; when none does, Home comes back. A device that powers up with the TV can take a few seconds
 * to show on its port, so the ports are checked a few times first.
 *
 * Xiaomi's mitv.tv.SourceManager (the API the TV's own player uses) says which ports are connected and
 * reports the signal through OnSourceChangeListener.onSourceSignalStable. Both are reached by reflection from
 * the com.xiaomi.mitv.api library, so the launcher builds and runs unchanged on TVs without it. Inputs open
 * like an Inputs menu row. The scan steps aside as soon as the owner uses the remote on Home or goes back to it.
 *
 * A power-on is seen in whichever way comes first: Home starting for the first time after a boot (see
 * onHomeStarted; Xiaomi's firmware marks third-party apps stopped at boot and never hands them BOOT_COMPLETED),
 * BOOT_COMPLETED on other TVs, or SCREEN_ON after standby (see listenForScreenOn). The boot is remembered by the
 * kernel's boot id, so one power-on scans once. The receiver also answers ACTION_CHECK for testing over adb;
 * it is guarded by the DUMP permission, which only the system and the adb shell hold.
 */
public class PowerOnInput extends BroadcastReceiver {
    static final String ACTION_CHECK = "com.gathin.tvhome.action.CHECK_POWER_ON_INPUT";
    private static final String TAG = "TvHome.PowerOnInput";
    /**
     * Connected ports are looked for at once and then every 2 seconds for 20 seconds: right after a power-on the
     * Mi TV 3's TV service reports no port at all for about 6 seconds.
     */
    private static final long CONNECT_INTERVAL_MS = 2000;
    private static final int CONNECT_ATTEMPTS = 11;
    /**
     * How long an opened input gets before the next one is tried when no "locked" report comes. A running Mi TV 3
     * reports within 7 seconds; right after a power-on the device on the port renegotiates HDMI and takes longer.
     */
    private static final long SIGNAL_WAIT_MS = 20000;
    /** The TV can report "unlocked" before it reports "locked": after "unlocked", wait this long for "locked". */
    private static final long UNLOCK_GRACE_MS = 8000;
    /** Home starting later than this after a boot (an update, a crash) is not a power-on. */
    private static final long BOOT_WINDOW_MS = 10 * 60_000L;
    private static final String PREFS = "power_on_input", KEY_BOOT = "scanned_boot";
    /**
     * The last scan's steps, in the launcher's external files folder, readable over adb:
     * cat /sdcard/Android/data/com.gathin.tvhome/files/power-on.log. Firmware that drops app logs at info level
     * during boot (the Mi TV 3) leaves this as the only record of a power-on scan.
     */
    static final String JOURNAL = "power-on.log";

    /** Set by MainActivity: the last key press on Home and the last time Home came to the front. */
    static volatile long lastHomeKeyAt, lastHomeShownAt;
    private static boolean screenOnRegistered;

    /**
     * A TV that goes to standby instead of powering off comes back with SCREEN_ON, not BOOT_COMPLETED, and
     * SCREEN_ON only reaches receivers registered at run time, so the launcher's process listens for it.
     */
    static void listenForScreenOn(Context ctx) {
        if (screenOnRegistered) return;
        screenOnRegistered = true;
        ctx.getApplicationContext().registerReceiver(new PowerOnInput(), new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** While a scan runs it owns the TV's input; Home's live preview waits for it. */
    static boolean scanning() {
        return Scan.running;
    }

    /** Called when Home is created: the first time after a boot, this is the power-on. */
    static void onHomeStarted(Context ctx, Config cfg) {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS || !firstForThisBoot(ctx)) return;
        start(ctx, cfg, null);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (!firstForThisBoot(context)) return;   // Home starting got there first
        } else if (!Intent.ACTION_SCREEN_ON.equals(action) && !ACTION_CHECK.equals(action)) {
            return;
        }
        start(context, Config.load(context), this);
    }

    /** pending is the receiver to finish once the scan is over, or null when Home started the scan. */
    private static void start(Context ctx, Config cfg, BroadcastReceiver pending) {
        if (cfg.powerOnInputs.isEmpty() || cfg.sourceIntentAction.isEmpty() || cfg.sourceIntentExtra.isEmpty()) return;
        if (Scan.running) return;   // two of the triggers can arrive together
        try {
            Class.forName("mitv.tv.TvContext");
        } catch (Throwable t) {
            Log.w(TAG, "no Xiaomi TV API on this TV, power_on_inputs ignored");
            return;
        }
        new Scan(ctx.getApplicationContext(), cfg, pending != null ? pending.goAsync() : null).start();
    }

    /** True once per boot, for whichever trigger asks first; the boot is told apart by the kernel's boot id. */
    private static boolean firstForThisBoot(Context ctx) {
        String boot = bootId();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (boot.equals(prefs.getString(KEY_BOOT, ""))) return false;
        prefs.edit().putString(KEY_BOOT, boot).commit();
        return true;
    }

    private static String bootId() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = r.readLine();
            if (id != null && !id.trim().isEmpty()) return id.trim();
        } catch (Exception ignored) {
        }
        // No boot id: the boot's wall-clock start, to the ten minutes (the clock may be set after boot).
        return "t" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600_000L;
    }

    /** mitv.tv.TvContext.getInstance().getSourceManager(), or null while the TV service is not up yet. */
    private static Object sourceManager() {
        try {
            Class<?> tvContext = Class.forName("mitv.tv.TvContext");
            Object context = tvContext.getMethod("getInstance").invoke(null);
            return tvContext.getMethod("getSourceManager").invoke(context);
        } catch (Throwable t) {
            Log.w(TAG, "Xiaomi SourceManager not available: " + t);
            return null;
        }
    }

    /** One power-on scan: find connected ports, open them in order, stop at the first with a picture. */
    private static final class Scan {
        static boolean running;
        private final Context ctx;
        private final Config cfg;
        /** Looked up on each attempt until it answers: right after boot the TV service may still be starting. */
        private Object sources;
        private final PendingResult pending;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final long startedAt = SystemClock.uptimeMillis();
        private final File journal;
        private List<Integer> candidates = new ArrayList<>();
        private int index = -1;
        private long openedAt;
        private Object listener;
        private Runnable timeout;
        private boolean done;

        Scan(Context ctx, Config cfg, PendingResult pending) {
            this.ctx = ctx;
            this.cfg = cfg;
            this.pending = pending;
            File dir = Config.dir(ctx);
            this.journal = dir != null ? new File(dir, JOURNAL) : null;
            if (journal != null) journal.delete();   // the journal holds the last scan only
        }

        void start() {
            running = true;
            note("scan for " + cfg.powerOnInputs + ", " + SystemClock.elapsedRealtime() / 1000 + " s after boot");
            waitForConnected(0);
        }

        /** Logs at warning level, which boot-time log filters keep, and appends to the journal. */
        private void note(String what) {
            Log.w(TAG, what);
            if (journal == null) return;
            try (FileWriter w = new FileWriter(journal, true)) {
                w.write(String.format(java.util.Locale.ROOT, "%7.1f s  %s%n", SystemClock.elapsedRealtime() / 1000.0, what));
            } catch (Exception ignored) {
            }
        }

        private void waitForConnected(int attempt) {
            main.postDelayed(() -> {
                if (done) return;
                if (lastHomeKeyAt > startedAt) {
                    finish("the remote is in use, staying on Home");
                    return;
                }
                if (sources == null) sources = sourceManager();
                candidates = sources != null ? connected() : new ArrayList<>();
                if (!candidates.isEmpty()) {
                    listen();
                    next();
                } else if (attempt + 1 < CONNECT_ATTEMPTS) {
                    waitForConnected(attempt + 1);
                } else {
                    finish("no input connected, staying on Home");
                }
            }, attempt == 0 ? 0 : CONNECT_INTERVAL_MS);
        }

        private List<Integer> connected() {
            List<Integer> out = new ArrayList<>();
            for (int id : cfg.powerOnInputs) {
                try {
                    Object c = sources.getClass().getMethod("isSourceConnected", int.class).invoke(sources, id);
                    if (Boolean.TRUE.equals(c)) out.add(id);
                } catch (Exception e) {
                    note("cannot ask input " + id + ": " + e);
                }
            }
            note("connected inputs, in order: " + out);
            return out;
        }

        private void next() {
            if (done) return;
            // Pressing a key on Home, or going back to Home while an input was being tried, is the owner's call.
            if (lastHomeKeyAt > startedAt || (index >= 0 && lastHomeShownAt > openedAt)) {
                finish("the remote is in use, scan stopped");
                return;
            }
            index++;
            if (index >= candidates.size()) {
                ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(ctx.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish("no connected input has a picture, back to Home");
                return;
            }
            int id = candidates.get(index);
            note("trying input " + id);
            openedAt = SystemClock.uptimeMillis();
            ctx.startActivity(new Intent(cfg.sourceIntentAction).putExtra(cfg.sourceIntentExtra, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            timeout = () -> {
                note("input " + id + " showed no picture in time");
                next();
            };
            main.postDelayed(timeout, SIGNAL_WAIT_MS);
        }

        /** Called on the main thread for every signal report; only the input being tried counts. */
        private void onSignal(int id, boolean stable) {
            if (!done) note("signal of input " + id + ": " + (stable ? "locked" : "unlocked"));
            if (done || index < 0 || index >= candidates.size() || candidates.get(index) != id) return;
            main.removeCallbacks(timeout);
            if (stable) {
                finish("input " + id + " has a picture");
                return;
            }
            // A "locked" in the meantime still wins; repeated "unlocked" reports never stretch a port past the cap.
            long now = SystemClock.uptimeMillis();
            long until = Math.min(now + UNLOCK_GRACE_MS, openedAt + SIGNAL_WAIT_MS + UNLOCK_GRACE_MS);
            main.postDelayed(timeout, Math.max(0, until - now));
        }

        /** Registers an OnSourceChangeListener built with a Proxy, since the interface is only there at run time. */
        private void listen() {
            try {
                Class<?> type = Class.forName("mitv.tv.SourceManager$OnSourceChangeListener");
                listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onSourceSignalStable":
                            if (args != null && args.length == 2) {
                                int id = (Integer) args[0];
                                boolean stable = (Boolean) args[1];
                                main.post(() -> onSignal(id, stable));
                            }
                            return false;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && proxy == args[0];
                        case "toString":
                            return "PowerOnInput listener";
                        default:
                            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                });
                sources.getClass().getMethod("addSourceStatusListener", type).invoke(sources, listener);
            } catch (Throwable t) {
                // Without signal reports every opened input waits the full SIGNAL_WAIT_MS before the next.
                note("no signal reports: " + t);
                listener = null;
            }
        }

        private void finish(String why) {
            if (done) return;
            done = true;
            running = false;
            main.removeCallbacks(timeout);
            note(why);
            if (listener != null) {
                try {
                    Class<?> type = Class.forName("mitv.tv.SourceManager$OnSourceChangeListener");
                    sources.getClass().getMethod("removeSourceStatusListener", type).invoke(sources, listener);
                } catch (Throwable ignored) {
                }
            }
            if (pending != null) pending.finish();
        }
    }
}
