package com.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** What the app remembers: the two ports, LAN access, autostart, the page key. */
public final class MagpiePrefs {

    /** 3425: the port the gateway (what agents point their base URL at) listens on. */
    public static final int DEFAULT_GATEWAY_PORT = 3425;

    /** magpie's own default for the page, kept as it is. */
    public static final int DEFAULT_WEB_PORT = 3430;

    private static final String FILE = "magpie";
    private static final String KEY_GATEWAY_PORT = "gatewayPort";
    private static final String KEY_WEB_PORT = "webPort";
    private static final String KEY_LAN = "lan";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_WEB_KEY = "webKey";

    private MagpiePrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int gatewayPort(Context ctx) {
        return portOr(prefs(ctx).getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT), DEFAULT_GATEWAY_PORT);
    }

    public static int webPort(Context ctx) {
        return portOr(prefs(ctx).getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT), DEFAULT_WEB_PORT);
    }

    public static boolean lan(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LAN, false);
    }

    public static boolean autostart(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTOSTART, false);
    }

    /**
     * MAGPIE_WEB_KEY: the page's key, which magpie itself wants to be 16+ letters,
     * digits, - . _ or ~. One is made on first use and kept, so the WebView stays
     * signed in across restarts.
     */
    public static String webKey(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String key = p.getString(KEY_WEB_KEY, null);
        if (key == null || key.length() < 16) {
            key = randomKey();
            p.edit().putString(KEY_WEB_KEY, key).apply();
        }
        return key;
    }

    public static void save(Context ctx, int gatewayPort, int webPort, boolean lan, boolean autostart) {
        prefs(ctx).edit()
                .putInt(KEY_GATEWAY_PORT, gatewayPort)
                .putInt(KEY_WEB_PORT, webPort)
                .putBoolean(KEY_LAN, lan)
                .putBoolean(KEY_AUTOSTART, autostart)
                .apply();
    }

    public static boolean validPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    private static int portOr(int port, int fallback) {
        return validPort(port) ? port : fallback;
    }

    private static String randomKey() {
        final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
