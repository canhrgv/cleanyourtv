package com.gathin.tvhome;

import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Proxy;

/**
 * A live picture of one input (config home_preview_input, HDMI 1 on the Mi TV 3) in a TvView on Home, the way
 * Xiaomi's own launcher shows its small TV window: the TvView is registered with mitv.tv.TvViewManager, which
 * follows the view's place on screen and scales the TV's video plane into it, and the input is selected with
 * SourceManager.setCurrentSource. Unregistering hands the screen back to Android, so stop() runs whenever Home
 * leaves the front. Everything is reached by reflection from the com.xiaomi.mitv.api library; on TVs without
 * it the preview never starts.
 */
final class HdmiPreview {
    private static final String TAG = "TvHome.HdmiPreview";

    interface Listener {
        /** Whether the input shows a picture; called on the main thread. */
        void onSignal(boolean picture);
    }

    private final TvView view;
    private final int source;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object tvViews, sources, signalListener;
    private boolean started;

    HdmiPreview(TvView view, int source, Listener listener) {
        this.view = view;
        this.source = source;
        this.listener = listener;
    }

    /** True when Xiaomi's TV API is there and a device is plugged into the input. */
    boolean connected() {
        if (!lookUp()) return false;
        try {
            return Boolean.TRUE.equals(sources.getClass().getMethod("isSourceConnected", int.class).invoke(sources, source));
        } catch (Exception e) {
            return false;
        }
    }

    /** Puts the input's picture into the view. Safe to call again while started. */
    void start() {
        if (started || !lookUp()) return;
        try {
            listenForSignal();
            tvViews.getClass().getMethod("registerMainTvView", android.view.View.class, Object.class).invoke(tvViews, view, null);
            sources.getClass().getMethod("setCurrentSource", int.class).invoke(sources, source);
            started = true;
            Log.i(TAG, "preview of input " + source + " started");
        } catch (Exception e) {
            Log.w(TAG, "preview not started: " + e);
            stop();
        }
    }

    /** Releases the input and gives the screen back to Android. */
    void stop() {
        if (tvViews == null) return;
        try {
            if (started) tvViews.getClass().getMethod("unregisterTvView", android.view.View.class).invoke(tvViews, view);
        } catch (Exception e) {
            Log.w(TAG, "preview not released: " + e);
        }
        try {
            if (signalListener != null) {
                Class<?> type = Class.forName("mitv.tv.SourceManager$OnSourceChangeListener");
                sources.getClass().getMethod("removeSourceStatusListener", type).invoke(sources, signalListener);
            }
        } catch (Exception ignored) {
        }
        signalListener = null;
        started = false;
    }

    private boolean lookUp() {
        if (tvViews != null && sources != null) return true;
        try {
            Class<?> tvContext = Class.forName("mitv.tv.TvContext");
            Object context = tvContext.getMethod("getInstance").invoke(null);
            sources = tvContext.getMethod("getSourceManager").invoke(context);
            tvViews = tvContext.getMethod("getTvViewManager").invoke(context);
            return sources != null && tvViews != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void listenForSignal() throws Exception {
        Class<?> type = Class.forName("mitv.tv.SourceManager$OnSourceChangeListener");
        signalListener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onSourceSignalStable":
                    if (args != null && args.length == 2 && (Integer) args[0] == source) {
                        boolean picture = (Boolean) args[1];
                        main.post(() -> listener.onSignal(picture));
                    }
                    return false;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "HdmiPreview listener";
                default:
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        });
        sources.getClass().getMethod("addSourceStatusListener", type).invoke(sources, signalListener);
    }
}
