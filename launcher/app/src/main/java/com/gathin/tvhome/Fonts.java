package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;

import androidx.core.content.res.ResourcesCompat;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Makes android:fontFamily="@font/..." work below Android 8. Older TextViews read that attribute as the
 * font file's path ("res/font/x.ttf"), look the path up in Typeface's system font map, find nothing and fall
 * back to Roboto. Registering each bundled font in that map under its path lets every layout, style, popup
 * and the screensaver pick it up unchanged. Android 8 and newer resolve font resources themselves.
 */
final class Fonts {
    private static final String TAG = "TvHome.Fonts";
    private static final int[] FONTS = {
            R.font.be_vietnam_pro_light, R.font.be_vietnam_pro_regular, R.font.be_vietnam_pro_medium};
    private static boolean registered;

    private Fonts() {
    }

    /** Call before inflating a layout. Runs once per process; a failure leaves the system font in place. */
    @SuppressWarnings("unchecked")
    static void register(Context ctx) {
        if (registered || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return;
        registered = true;
        try {
            Field f = Typeface.class.getDeclaredField("sSystemFontMap");
            f.setAccessible(true);
            Map<String, Typeface> map = (Map<String, Typeface>) f.get(null);
            if (map == null) return;
            TypedValue path = new TypedValue();
            int n = 0;
            for (int id : FONTS) {
                ctx.getResources().getValue(id, path, true);
                Typeface tf = ResourcesCompat.getFont(ctx, id);
                if (path.string != null && tf != null) {
                    map.put(path.string.toString(), tf);
                    n++;
                }
            }
            Log.i(TAG, n + " of " + FONTS.length + " bundled fonts registered");
        } catch (Exception e) {
            // The system font has the Vietnamese glyphs too; only the look changes.
            Log.w(TAG, "bundled fonts not registered: " + e);
        }
    }
}
