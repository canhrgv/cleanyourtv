package com.gathin.tvhome;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Current weather for the clock, from Open-Meteo (no key, no account). One request per 30 minutes at most,
 * on a background thread; the last answer is kept for the process, so Home shows it at once after an app.
 * Any failure keeps the previous reading, or shows nothing: the home screen never waits on the network.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long MAX_AGE_MS = 30 * 60_000L;

    /** The reading the clock shows: temperature in whole degrees Celsius and a WMO weather code. */
    static final class Reading {
        final int celsius;
        final int code;

        Reading(int celsius, int code) {
            this.celsius = celsius;
            this.code = code;
        }

        int iconRes() {
            if (code == 0) return R.drawable.ic_weather_clear;
            if (code <= 2) return R.drawable.ic_weather_partly_cloudy;
            if (code == 45 || code == 48) return R.drawable.ic_weather_fog;
            if (code >= 95) return R.drawable.ic_weather_storm;
            if (code >= 51 && code <= 82) return R.drawable.ic_weather_rain;
            return R.drawable.ic_weather_cloudy;
        }

        int labelRes() {
            if (code == 0) return R.string.weather_clear;
            if (code <= 2) return R.string.weather_partly_cloudy;
            if (code == 3) return R.string.weather_cloudy;
            if (code == 45 || code == 48) return R.string.weather_fog;
            if (code >= 51 && code <= 57) return R.string.weather_drizzle;
            if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.string.weather_rain;
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.weather_snow;
            if (code >= 95) return R.string.weather_storm;
            return R.string.weather_cloudy;
        }
    }

    interface Listener {
        void onWeather(Reading reading);
    }

    // Static so a reading survives the activity being recreated; it only resets when the process dies.
    private static Reading last;
    private static long fetchedAt;
    private static boolean busy;

    private final String latitude, longitude;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** location is config.xml weather_location, "latitude,longitude"; null when it is empty or malformed. */
    static Weather forLocation(String location) {
        String[] parts = location.split(",");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].trim()), lon = Double.parseDouble(parts[1].trim());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            return new Weather(String.format(Locale.ROOT, "%.4f", lat), String.format(Locale.ROOT, "%.4f", lon));
        } catch (NumberFormatException e) {
            Log.w(TAG, "ignoring weather_location: " + location);
            return null;
        }
    }

    private Weather(String latitude, String longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Hands the cached reading over at once, and fetches a new one when it is older than 30 minutes. */
    void refresh(Listener listener) {
        if (last != null) listener.onWeather(last);
        if (busy || (last != null && System.currentTimeMillis() - fetchedAt < MAX_AGE_MS)) return;
        busy = true;
        new Thread(() -> {
            Reading r = fetch();
            main.post(() -> {
                busy = false;
                if (r == null) return;
                last = r;
                fetchedAt = System.currentTimeMillis();
                listener.onWeather(r);
            });
        }, "weather").start();
    }

    private Reading fetch() {
        // Android before 7.1.1 lacks the ISRG roots behind Open-Meteo's Let's Encrypt certificate, so those sets
        // ask over plain HTTP; the request carries only the city's coordinates and the answer is public.
        String scheme = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 ? "https" : "http";
        HttpURLConnection c = null;
        try {
            URL url = new URL(scheme + "://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                    + "&current=temperature_2m,weather_code&timezone=auto");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            JSONObject current = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONObject("current");
            return new Reading((int) Math.round(current.getDouble("temperature_2m")), current.getInt("weather_code"));
        } catch (Exception e) {
            Log.w(TAG, "weather not updated: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
