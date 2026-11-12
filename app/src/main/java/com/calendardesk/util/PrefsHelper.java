package com.calendardesk.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences 统一封装。
 * 存储项：hefeng_key, manual_offset, current_city
 */
public class PrefsHelper {

    private static final String NAME = "calendar_prefs";
    private static SharedPreferences prefs;

    private PrefsHelper() {}

    public static void init(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------- String ----------

    public static String getString(String key, String defValue) {
        return prefs != null ? prefs.getString(key, defValue) : defValue;
    }

    public static void putString(String key, String value) {
        if (prefs != null) prefs.edit().putString(key, value).apply();
    }

    // ---------- long ----------

    public static long getLong(String key, long defValue) {
        return prefs != null ? prefs.getLong(key, defValue) : defValue;
    }

    public static void putLong(String key, long value) {
        if (prefs != null) prefs.edit().putLong(key, value).apply();
    }

    // ---------- Convenience keys ----------

    public static final String KEY_HEFENG_API = "hefeng_key";
    public static final String KEY_MANUAL_OFFSET = "manual_offset";
    public static final String KEY_CURRENT_CITY = "current_city";
    public static final String KEY_CURRENT_CITY_ID = "current_city_id";

    public static String getHefengKey(String defaultKey) {
        return getString(KEY_HEFENG_API, defaultKey);
    }

    public static void setHefengKey(String key) {
        putString(KEY_HEFENG_API, key);
    }

    public static long getManualOffset() {
        return getLong(KEY_MANUAL_OFFSET, 0L);
    }

    public static void setManualOffset(long offsetMs) {
        putLong(KEY_MANUAL_OFFSET, offsetMs);
    }

    public static String getCurrentCity(String defaultCity) {
        return getString(KEY_CURRENT_CITY, defaultCity);
    }

    public static void setCurrentCity(String city) {
        putString(KEY_CURRENT_CITY, city);
    }

    public static String getCurrentCityId(String defaultId) {
        return getString(KEY_CURRENT_CITY_ID, defaultId);
    }

    public static void setCurrentCityId(String id) {
        putString(KEY_CURRENT_CITY_ID, id);
    }
}
